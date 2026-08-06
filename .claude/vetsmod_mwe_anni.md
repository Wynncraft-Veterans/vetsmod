---
name: vetsmod MWE/anni subsystem
description: Architecture-as-built for the vetsmod ↔ fishbot anni MWE subsystem (snapshot pipeline, /wv anni renderer, anni-motd, debug harness, mode state, passive-mode boss bar + outlines/nametags, aggressive-mode zone lines + scroll spot + ghosts prompt + chat alerts, in-game `/wv anni rsvp`, party back-report).
type: project
---

# vetsmod MWE/anni subsystem

Architecture-as-built reference. Read this first if you're touching the subsystem; the code itself is the source of truth, this doc captures the load-bearing design context that isn't obvious from the code.

## Package layout

All anni-side code lives under `org.wynnvets.mwe.anni.*`:

```
mwe/anni/
├── state/
│   ├── AnniSnapshot.java          — immutable Gson record of the wire shape (schema_version=1)
│   └── AnniSnapshotCache.java     — process-wide volatile + listener bus
├── network/
│   ├── AnniWsHandler.java         — V1 outbound/inbound listener; routes anni_state and anni_query_response
│   └── AnniQueryClient.java       — single-flight pull (anni_query); 8s deadline; null on failure
├── debug/
│   └── AnniDebugCommands.java     — /wv debug tree anni … harness (inject, dump, refresh, guess, time, external, flash, mode set)
├── mode/
│   ├── AnniMode.java                  — enum SILENT/PASSIVE/AGGRESSIVE + config converters
│   ├── AnniModeManager.java           — single chokepoint for transitions; enforces /stream mutex
│   ├── AnniWindowWatcher.java         — subscribes to AnniSnapshotCache; resets vetsAnniMode to silent at T+30m
│   └── StreamerModeChatDetector.java  — chat-line backup for Wynntils' isInStream(); auto-flips to silent on stream-on
├── bossbar/
│   ├── VetsBossBarManager.java         — per-tick driver; owns synthetic LerpingBossEvent; three T-20s gates
│   ├── VetsBossBarContentBuilder.java  — pure (snapshot, secs, x, z) → Component (seeking/assigned/countdown variants)
│   └── FlashTracker.java               — per-field diff + bold↔underline pulse + name-ping sound
├── zone/
│   ├── AnniZone.java               — 60s cached fetcher for api.wynncraft.com/v3/map/world-events; 48-block disc test
│   └── AnniZoneLineRenderer.java   — stacked Gizmos.circle cylinder cage
├── outline/
│   ├── AnniOutlinePalette.java   — ChatFormatting-derived role/tier colour table
│   ├── AnniOutlineRegistry.java  — username → tier entry, rebuilt off snapshot (lowercase-keyed)
│   └── AnniOutlineTicker.java    — per-tick driver; applies glow via EntityExtension; exposes isOutlineSuppressionActive() flag for the mixins
├── aggressive/
│   ├── AggressiveAlertDispatcher.java  — snapshot diffs + T-10m/T-5m readiness
│   ├── AnniAggressiveTicker.java       — volatile flag; mode==AGGRESSIVE ∧ window
│   └── GhostsPromptHandler.java        — rising-edge zone-entry prompt
├── waypoint/
│   └── ScrollSpotMarkerProvider.java   — Wynntils MarkerProvider, dark-red beacon
├── command/
│   ├── AnniRsvpCommand.java        — brigadier handler: auth gate, dispatch, render ack
│   └── AnniScrollspotCommand.java  — invoked from debug tree (not main /wv)
├── party/
│   └── AnniPartyReporter.java      — snapshot listener; on organiser-set change calls PartyRosterListener.requestRecapture
└── render/
    ├── AnniHoverBuilder.java      — colour tokens, role chips, rsvp badges, link/click/hover helpers
    ├── AnniCommandRenderer.java   — /wv anni payload builder; returns List<MutableComponent>
    └── AnniMotdRenderer.java      — world-join anni-motd (two-line "Annihilation returns in X.Yh! / You have been assigned …")
```

## Snapshot pipeline

1. **vets-anni** assembles per-uuid snapshots via `app/domain/snapshot.py::assemble_snapshot`. The `event` block is populated whenever any past `AnniEvent` exists; `event: null` is only emitted on a truly empty DB.
2. **temp-server's poller** (`app/services/anni_snapshot_poller.py`) hits vets-anni's `/api/internal/anni-snapshot-batch` and `/anni-player/{uuid}`; pushes per-uuid changes as `anni_state` frames every ~10 s in the hot window, ~5 min otherwise. Eligibility refreshes every 60 s.
3. **vetsmod's `AnniWsHandler`** receives `anni_state` (push) and `anni_query_response` (pull) frames, hydrates into `AnniSnapshot`, drops into `AnniSnapshotCache`.
4. **`AnniSnapshotCache`** is a single-player, volatile, listener-bus cache. Listeners fire on the WS reader thread — bounce to the main thread via `Minecraft.getInstance().execute(...)` if you need to touch render state.

## `/wv anni` render dispatch

Entry: `CommandRegistry.anni()` → `StampFetcher.fetchStampAndCreateAnniCommandMessage()` → `AnniCommandRenderer.render(snapshot)`.

`StampFetcher`:
- `vetsAnniEnabled=false` → legacy fallback.
- `vetsAnniEnabled=true && cache populated` → renderer.
- `vetsAnniEnabled=true && cache cold` → `AnniQueryClient.query()` (auto-pull), then renderer if successful, else legacy.

`AnniCommandRenderer.render(snapshot)` returns `List<MutableComponent>`:
- `null` → "fall back to legacy" (the only case is external + announced).
- One element → single `[VETSMOD]` block.
- Two elements → two blocks, each gets its own full `[VETSMOD]` badge via `sendLocalMessageNewBlock`.

Three branches:
- **Not announced** — header (or red headline for external) + prediction line + (if vets) the not-announced registration block. No board section here (no anni to be placed in).
- **Far-out (T-2h+)** — header (link + "returns in Xh Ym (HH:MM, day d)") + Assigned Role *or* Eligible Roles + RSVP Type + Attendance Chance + Party Assignment + Host. Countdown resolution scales with magnitude (`AnniCommandRenderer.appendCountdown`): `≥ 1h → Xh Ym`, `< 1h ≥ 1m → Xm Ys`, `< 1m → Ys` — sub-hour readouts surface seconds. Same scaling in `AnniMotdRenderer.formatHours` for the world-join motd.
- **Imminent (within 2h)** — same body as far-out + a second block with `Change Anni Mode? (Click:) / [Silent] | [Passive] | [Aggressive]`.

### Not-announced registration block

Three sub-shapes:
- **Unregistered** → `unregisteredNudgeBlock` — red bold "You have not yet opened anni.wynnvets.org/me!" + dark-red italic followup.
- **Registered, no specific roles** → `fillOnlyExplainerBlock` — four lines (yellow bold italic / gold italic / 0x7E7E7E italic / white bold italic) with clickable "fill slot" and "here" links.
- **Registered with roles** → role chips + dark-green italic "You're all set for the next #Annihilation" (where #Annihilation links to the Discord channel in dark aqua).

### `isExternal(snapshot)` — vets-tier detection

Uses the **bridge predicate** at `OutboundDisplayHandler.shouldDisplayMessages` (the same set nazbot's `!enable unauth` lets bridge):
- `GuildStateManager.isReturners()`, OR
- `GuildStateManager.isGuildless() && isWaitlistUnlocked()`, OR
- `GuildStateManager.isHonouraryUnlocked()`.

Not the dazebot auth tier. Reason: gating the registration nudge on `~vetsmod` Discord linking would skip exactly the users we want to catch.

The `externalOverride` field (settable via `/wv debug tree anni external <auto|true|false>`) and the preset-name auto-set (`external_*` → forced external, `member_*` → forced vets) bypass this when set.

### Prediction line

`Prediction: <Xh> from now | <MMM d> @<HH:mm> ±~<σ>h` — gold label, yellow numerics, white-bold-pipe separator, gray italic uncertainty.

`σ = window_hours / √12` (~3.06h on the standard 10.6h Uniform window). NOT half-window — q0/q4 are misleading tails and ± in chat reads as standard deviation.

### Role styles

`AnniHoverBuilder.displayRole(roleCode)` reads `vetsAnniRoleStyle`:

| Code | descriptive (default) | short | formal |
|--|--|--|--|
| TANK | TANK | TANK | TANK |
| HEAL/HEALER | HEALER | HEAL | HEALER |
| PRIMARY | BOSSKILL | PRIM | PRIMARY |
| SECONDARY | SUNKILL | SUNK | SECONDARY |
| TERTIARY | MOBKILL | MOBK | TERTIARY |
| FILL | FILL | FILL | FILL |

Short codes are reserved for boss-bar space constraints; chat callers always use the configured style.

### RSVP / attendance / board labels

Long forms in chat (`HARD RSVP` / `SOFT RSVP` / `EARLY WALK-IN` / `LATE WALK-IN`). Short forms (HRSVP etc.) reserved for boss-bar space.

`Attendance Chance` is state-dependent:
- party → "ASSIGNED" (light purple bold).
- wont_assign → "COULD NOT ASSIGN" (red bold).
- unassigned → the bar.
- everything else → line omitted.

`Eligible Roles` becomes `Assigned Role` when on a party (and the duplicate `Role:` in the party block is dropped).

`Board` label everywhere is `Party Assignment`.

## anni-motd (world-join)

Entry: `GuildStateManager.fetchAndDisplayStampMessage` → `StampFetcher.fetchStampAndCreateMessage` → `AnniMotdRenderer.render`.

Two lines after a full `[VETSMOD]` badge (uses `sendLocalMessageNewBlock` so it doesn't collapse into the guild motd's badge):

```
Annihilation returns in 7.2h!
You have been assigned to TANK with Party 2 on EU5
```

or:

```
Annihilation returns in 7.2h!
You aren't yet assigned and are hard RSVP'd!
```

Line 1 is red+bold, time as decimal hours (or minutes when < ~6 min). Line 2 is gray prose with bold-coloured callouts for role/party/world or just the RSVP clause.

External users get `null` from `AnniMotdRenderer.render` → fall through to legacy stamp text (spec §"For external users").

`vetsAnniPromptRsvp=false` suppresses line 2.

## Mode state

`VetsConfig.VETS_ANNI_MODE` — string, `silent`/`passive`/`aggressive`, default `silent`, persisted. User-facing via `/wv config`.

`/wv anni <mode>` routes through `AnniModeManager.transitionTo(target, Source.USER_COMMAND)` which writes the config + prints a confirmation. **Refused → silent** when `Models.StreamerMode.isInStream() || StreamerModeChatDetector.lastSeenInStream()` is true (spec §3.1 mutex). Refused transitions print "Anni mode change refused: /stream is active. Stream is suboptimal for anni — try /toggle ghosts NONE instead.".

**`AnniMode`** (`mode/AnniMode.java`):
- Enum SILENT / PASSIVE / AGGRESSIVE.
- `fromConfig()` / `fromString(String)` / `toConfigValue()` for round-tripping with `VetsConfig`.

**`AnniModeManager`** (`mode/AnniModeManager.java`):
- Single chokepoint for transitions. `transitionTo(target, Source)` with `Source ∈ {USER_COMMAND, AUTO_WINDOW_CLOSE, AUTO_STREAM_ACTIVATED, DEBUG_BYPASS_MUTEX}`.
- DEBUG_BYPASS_MUTEX skips the stream check for screen-capture testing.
- AUTO_STREAM_ACTIVATED suppresses the manager's own confirmation so the detector can print a friendlier "auto-changed to silent: /stream activated" message instead.

**`StreamerModeChatDetector`** (`mode/StreamerModeChatDetector.java`):
- Belt-and-braces backup for `Models.StreamerMode.isInStream()`. Hooks `ChatLogMixin` via a one-line `observe(message)` call on the chat HEAD inject.
- Substring patterns on the format-stripped chat line: `"Streamer mode was enabled"` (on) / `"Streamer mode disabled"` (off).
- On stream-on: flips `lastSeenInStream=true` and calls `transitionTo(SILENT, AUTO_STREAM_ACTIVATED)` if the current mode isn't already silent.
- Stream-off does NOT auto-restore the previous mode (user re-enables manually).

**`AnniWindowWatcher`** (`mode/AnniWindowWatcher.java`):
- Registered in `VetsmodClient.onInitializeClient()` after `AnniWsHandler.register()`.
- Subscribes to `AnniSnapshotCache`.
- Caches the most recent non-null `stamp_epoch` (since vets-anni emits null once the anni starts).
- On every snapshot update, checks `now > lastKnownStamp + 30 min`; if true and mode != silent, resets to silent and clears `lastKnownStamp`. Writes the config directly (predates AnniModeManager — non-controversial cleanup needs no mutex check).
- One-shot per anni cycle.

## Boss bar

The boss-bar manager uses a render-side filter (priority-500 `@Redirect` on `events.values()` inside `BossHealthOverlay#render`). *Note: this intentionally does NOT cancel `BossHealthOverlay#update` — vanilla's update handlers do `events.get(uuid).setName(...)`, and cancelling at update would NPE the next UpdateProgress / UpdateName / UpdateStyle packet for that UUID, disconnecting the client.*

Let vanilla and Wynntils track bars normally (no `update()` cancellation, no `events.clear()`). The priority-500 `BossHealthOverlayMixin` `@Redirect`s the `events.values()` call inside `BossHealthOverlay#render(GuiGraphics)` — while active, it returns just our UUID's entry (or an empty collection if our entry isn't there yet); when inactive, it returns the unmodified collection. Wynntils' overlays render through their own paths and aren't affected. `Models.StreamerMode.isInStream()` works without the let-through hack (Wynntils tracks the streamer-mode bar normally).

**`VetsBossBarManager`** (`bossbar/VetsBossBarManager.java`):
- Owns a deterministic `UUID.nameUUIDFromBytes("vetsmod-anni-bossbar".getBytes())` plus a single synthetic `LerpingBossEvent`. Exposes `static UUID barUuid()` for the render mixin.
- `activate()` calls `events.put(uuid, ours)` — does NOT clear pre-existing entries (the render filter handles "only-our-bar-visible"). On `deactivate()`: `events.remove(uuid)`.
- Per-tick driver (`ClientTickEvents.END_CLIENT_TICK`):
  1. Mode gate — SILENT → deactivate, return.
  2. `vetsAnniBossbarEnabled` kill-switch.
  3. Snapshot + `stamp_epoch` presence.
  4. Hard wall-clock T-20s watchdog (Gate 2 of 3) — independent of the content builder.
  5. 2.5h failsafe deactivate.
  6. **Activation gate**: show iff `secondsUntilAnni ≤ 90 m` OR the player is in the anni zone. Outside both, even passive/aggressive stays quiet — no perpetual bar hours before anni.
  7. Build content; null → deactivate (Gate 1); else activate + setName + setProgress.

**`VetsBossBarContentBuilder`** (`bossbar/VetsBossBarContentBuilder.java`):
- Pure `(snapshot, secondsUntilAnni, playerX, playerZ) → Component | null`.
- Returns `null` at `≤20s` (Gate 1 — manager deactivates).
- T-2m + in-zone (`AnniZone.isInZone`) → `anni.wynnvets.org: ANNI IN 099s | SCROLLS IN 079s!` (spec §3.1.1.3).
- Assigned (party OR committed slot) → `anni.wynnvets.org/me: Role: <SHORT> | Party <n> | World: <w>` (spec §3.1.1.2). Role colours: TANK=blue, HEAL=green, PRIM=red, SUNK=yellow, MOBK=light-purple, FILL=dark-aqua.
- Seeking (no party + no committed slot) → `Seeking a <HRSVP|SRSVP|WALKIN|LATE> <CORE|FILL> SLOT for anni.wynnvets.org in <X>min` (spec §3.1.1.1). Slot type derived from `registration.roles` (specific role = CORE).
- All chips wrap via `FlashTracker.styleFor(fieldKey, base)` so the bold↔underline pulse applies per-field.

**Bar colour: never PINK.** `colorFor("party")` returns `PURPLE`, not PINK as the original spec suggested. Wynncraft's forced resource pack overrides `boss_bar/pink_background.png` (and its progress sprite) to be fully transparent so they can repurpose pink-bar slots as text-only HUD strips (the Lv.92 Returners XP line, territory/region names like "Corrupted Road", "ROOTS OF CORRUPTION"). Reproducible on bare vanilla MC 1.21.11 with the Wynncraft pack loaded (empirically confirmed against Wynncraft's forced resource pack). Confirmed via `/wv debug trigger bossBarsDump` — every Wynncraft text-only bar shipped `color=PINK overlay=PROGRESS`. **Future colour additions: pick from `{PURPLE, RED, GREEN, YELLOW, BLUE, WHITE}` only.**

**Overlay style: NOTCHED_10 with a 100-min progress window.** Per-user request — segment dividers every 10 minutes for readability. Vanilla only ships `NOTCHED_6/10/12/20` (no `NOTCHED_9`). `PROGRESS_FULL_AT_SECONDS` decoupled from `ANNI_WINDOW_SECONDS` (still 90 min activation gate) and stretched to 100 min so each of 10 segments = exactly 10 minutes of wall-clock time. At T-90m activation the bar reads ~90% = 9 of 10 segments filled, matching the "90 mins = 9 notches" mapping. Drains one segment per 10 min through T-20s.

**`FlashTracker`** (`bossbar/FlashTracker.java`):
- Subscribes to `AnniSnapshotCache`. Two flash models:
  - **Role / party / RSVP**: timed-window flash. On a snapshot diff against last-seen, marks the field "flashing" for `vetsAnniFlashIntensity` ms (`subtle=5000`, `normal=10000` default, `strong=20000`). First observation skipped via `roleObserved` / `partyObserved` / `rsvpObserved` sentinels — login with existing state doesn't bing on every reconnect, but **null → set** transitions during the session DO flash + ping.
  - **World**: state-driven, never latched. `tick()` recomputes `worldMismatch = party.world != null && currentWorld != party.world` (read via `Models.WorldState.getCurrentWorldName()`); `styleFor("world", base)` consults that live flag. **Position movement** silently toggles the flash — walk onto the assigned world → flash stops, walk off → flash resumes — but **never** triggers a ping. **Pings** fire only when the snapshot's `party.world` *value* changes (assignment / reassignment / unassignment), wired through the same `applyDiff` path with a `worldObserved` sentinel that skips the first-observation case.
- Pulse half-period: 250ms fixed (toggled in `tick()`, driven by `VetsBossBarManager.tick()`).
- `styleFor(fieldKey, baseStyle)` applies `.withUnderlined(true)` to `baseStyle` iff the field is flashing AND the phase bit is high — produces the spec's "`&l ↔ &n&l`" alternation.
- Sound: 2× `EXPERIENCE_ORB_PICKUP` plays (110ms apart) on a ping event; multiple simultaneous triggers collapse via a cap-2 queue so an "everything-changed-at-once" snapshot still produces a single 2-ping burst.
- Bounces snapshot diffs off the WS reader thread via `Minecraft.getInstance().execute(...)`.
- Debug entry: `forceFlash(fieldKey)` for `/wv debug tree anni flash <field>`.

**Two T-20s deactivation gates** (parent plan §"Risks"):
1. Content builder returns null at `≤20s`.
2. Wall-clock watchdog in `VetsBossBarManager.tick()` — independent of builder state. Re-checks every client tick (~50 ms), so it also covers the kick-into-queue case at T-20 without needing a separate world-change hook.

Plus the slower net: `AnniWindowWatcher` resets to silent at T+30m.

## Anni zone

`AnniZone` (`zone/AnniZone.java`) — JSON fetcher for `https://api.wynncraft.com/v3/map/world-events`, looks up event `a63b2c02`, parses its `location[].event.{x,z}` into disc centres. 60s refresh, stale-fallback on transient failure, cold-fallback returns `isInZone() = false` (so the boss-bar countdown variant defaults to the assigned/seeking text rather than a false-positive zone claim).

Live API shape (probed 2026-06-16): `location: [{event: {x:315, y:31, z:-1291}, spawn: {x:350, y:29, z:-1291}, reward: {x:348, y:29, z:-1292}, radius: 48}]`. Spec mandates 48-block radius; we hard-code the constant in case the API drifts. Squared-distance comparison, no `Math.sqrt`.

Downstream consumers (outline gate, scroll waypoint, ghosts prompt) reuse this same fetcher.

## Debug harness

All under `/wv debug tree anni …` (the `tree` literal nests subsystem-specific debug trees so top-level `/wv debug` stays generic).

- `snapshot inject preset <name>` — loads from `src/client/resources/assets/vetsmod/anni_test_snapshots/<name>.json`. Preset name auto-sets the external override (`external_*` → forced external, `member_*` → forced vets, others → auto).
- `snapshot inject file <name>` — loads from `vetsmod/dumps/anni/<name>.json`.
- `snapshot inject <json>` — raw inline.
- `snapshot dump` — write current cache to `vetsmod/dumps/anni/snapshot-<ts>.json`.
- `snapshot clear` — set cache to null.
- `snapshot refresh` — pull fresh from server (alias for the auto-pull path).
- `guess` — pull + print fishbot-style one-liner (announced stamp + countdown OR prediction window).
- `time <seconds>` — mutate the cached snapshot's `event.stamp_epoch` to `NOW + seconds`, preserving everything else. Round-trips through JSON.
- `external <auto|true|false>` — override `isExternal` for testing.
- `flash <role|party|world|rsvp>` — force a `FlashTracker` pulse on the named field (10s at the `normal` default, sound + bold/underline alternation). `world` is the exception — it has no timed window, so forcing it lasts until the next tick recomputes the real mismatch.
- `mode set <silent|passive|aggressive>` — `AnniModeManager.transitionTo(..., DEBUG_BYPASS_MUTEX)`. Skips the `/stream` mutex so we can verify rendering during screen capture.
- `zone <enter|exit>` — force `AnniOutlineTicker.setForceInZone(...)` to bypass the geo-check.

All gated by `VetsLogger.isDebugEnabled()` (`/wv debug true`).

### Template substitution in fixtures

Preset and inline JSON injections run through `substituteNowTokens`. Tokens replaced with the current epoch-seconds:
- `"{NOW}"` → integer
- `"{NOW+72h}"`, `"{NOW-30m}"`, `"{NOW+1.5s}"` → integer

So fixtures stay valid across long stretches of time. Use it for any new preset that includes an epoch.

### Bundled presets

| Preset | Branch tested |
|--|--|
| `empty` | Truly empty DB (no event ever) |
| `external_no_anni` | External + not announced (lean external render) |
| `member_no_anni` | Vets + not announced + registered with roles (all-set affirmation) |
| `member_no_anni_fill` | Vets + not announced + registered but no specific roles (4-line fill explainer) |
| `member_no_anni_unregistered` | Vets + not announced + registered=false (red unregistered nudge) |
| `member_announced` | Vets + announced (NOW+8h) + unassigned (attendance bar) |
| `member_in_party` | Vets + announced (NOW+8h) + party (ASSIGNED + party block) |

## Config keys

| Key | Type | Default | Purpose |
|--|--|--|--|
| `vetsAnniEnabled` | bool | false (auto-true on first vets-tier auth ack) | Master MWE toggle |
| `vetsAnniMode` | string | `silent` | Active anni mode |
| `vetsAnniRoleStyle` | string | `descriptive` | Role-naming style (`descriptive`/`short`/`formal`) — boss bar always uses `short` |
| `vetsAnniShowHoverDetails` | bool | true | Populate hover tooltips on chips |
| `vetsAnniPromptRsvp` | bool | true | Show line 2 of anni-motd; show RSVP-suggest pills on `/wv anni` |
| `vetsAnniShowPrediction` | bool | true | Show `\guess`-style prediction in `/wv anni` not-announced |
| `vetsAnniBossbarEnabled` | bool | true | Master kill-switch for the synthetic boss bar. Only consulted in passive/aggressive. |
| `vetsAnniFlashIntensity` | string | `normal` | `subtle`=5s / `normal`=10s / `strong`=20s flash duration per field change. |
| `vetsAnniFlashSound` | bool | true | Whether per-field changes also play the name-ping sound twice. |
| `vetsAnniOutlinesEnabled` | bool | true | Outline overlay (role-coloured glow on party / light-grey on other vets parties / no glow on outsiders). Only consulted while the highlight gate holds. |
| `vetsAnniNametagsEnabled` | bool | true | Nametag overlay (matching colour scheme). Separable from outlines. |

## Open follow-ups

- **Client-side prediction for the no-snapshot case** — external users falling back to legacy still see "not announced" instead of a prediction. Would need a ~30-line Uniform(71.4h, 82h) model anchored on `AnniStampPoller`. Deferred pending user call.
- **Active-mode highlight on the mode-switch UI** — current state isn't visually indicated. User clicks to switch; no "you are here" marker.
- **Boss-bar countdown stamp source** — spec §3.1.1.3 footnote suggests T-5m text should derive from Wynntils' world-event countdown rather than purely from `stamp_epoch`. The boss bar uses the stamp; revisit if a real anni test reveals drift.

## Player highlights

The highlight overlay recolours nearby players' outlines + nametags while the user is at the anni in the active window. Three tiers, single ticker.

### Snapshot schema bump (v1 → v2)

The highlights subsystem requires `schema_version = 2` on the vets-anni side. The single addition is `event.all_parties`: a lightweight per-party member listing keyed off the active event. Each entry is `{ordinal, members: [{uuid, username, role}, ...]}` — same `_party_member_refs` projection as `board.party.members`, so the two views never drift. Used by `AnniOutlineRegistry` to tier "in another vets-anni party" players without per-uuid round-trips. Empty list when no parties exist yet for the active event. `AnniSnapshot.Event#allParties()` returns `Collections.emptyList()` on v1 payloads (Gson leaves unknown fields null, accessor coerces), so older snapshots remain consumable.

### Activation gate — strictly tighter than the boss bar

| Subsystem | Gate |
|--|--|
| Boss bar | `mode != SILENT` AND ( `secondsUntilAnni ≤ 90m` **OR** `in zone` ) |
| Highlights | `mode != SILENT` AND `in window (T-2h..T+30m)` **AND** `in zone` AND ( `vetsAnniOutlinesEnabled` OR `vetsAnniNametagsEnabled` ) |

Highlights are the high-intrusiveness UI; the boss bar is the low-intrusiveness one. Both ride the same mode + snapshot data but evaluate window/zone independently. `AnniOutlineTicker` re-evaluates the four-condition gate every client tick.

### `AnniOutlinePalette` (`outline/AnniOutlinePalette.java`)

Spec-canonical ChatFormatting-derived table. `CustomColor` values come from `CustomColor.fromChatFormatting(ChatFormatting.X)` so the outline ARGB stays in sync with vanilla's interpretation of the matching colour code elsewhere (chat text, scoreboard).

| Role / tier | ChatFormatting | Source |
|--|--|--|
| FILL | `WHITE` (§f) | `chatFormattingForRole("FILL")` |
| TANK | `AQUA` (§b) | `chatFormattingForRole("TANK")` |
| HEAL / HEALER | `GREEN` (§a) | `chatFormattingForRole("HEAL")` |
| TERTIARY | `LIGHT_PURPLE` (§d) | `chatFormattingForRole("TERTIARY")` |
| SECONDARY | `YELLOW` (§e) | `chatFormattingForRole("SECONDARY")` |
| PRIMARY | `RED` (§c) | `chatFormattingForRole("PRIMARY")` |
| Other vets party | `GRAY` (§7) | `OTHER_VETS_PARTY` |

Unknown or null roles fall through to `GRAY` (§7), the same colour as the other-vets-party tier — not to FILL.

Single source of truth, one hop removed: nothing outside `AnniOutlineRegistry` reads this table. `ownPartyEntry` derives both halves of its `Entry` — outline `CustomColor` and nametag `ChatFormatting` — from one `chatFormattingForRole` call, and the ticker and `NametagMixin`'s anni branch read that `Entry`. Deriving both from one call is what stops them drifting.

That guarantee covers the own-party tier only. `OTHER_PARTY_ENTRY` pairs `OTHER_VETS_PARTY` with a separately written `ChatFormatting.GRAY` literal, so its outline and nametag agree by convention rather than by construction — change one and the other does not follow.

### `AnniOutlineRegistry` (`outline/AnniOutlineRegistry.java`)

Username → `Entry(tier, role, outlineColor, nametagFormatting)`. Keys are lowercase (matches whatever `GameProfile.name()` gives the consumer, defensive against snapshot case mismatches). Rebuilt off every `AnniSnapshotCache` push:

1. Own-party members (from `snapshot.board.party.members`) added first → `Tier.OWN_PARTY` with role-coloured entry.
2. Every other vets party (from `snapshot.event.all_parties`) added via `putIfAbsent` — own-party entries already win, so a player who appears in both lists (always — own party IS one of all_parties) keeps their role colour.
3. The local player (matched by `snapshot.mc_username`) is excluded — no self-outline.

`isAnyActive()` for quick "anything to do" checks. `getEntry(String username)` for the consumer lookups. `clearAll()` for unit-test teardown (no production caller; the rebuild path overwrites). Concurrent hashmap; reads from main / render threads, writes from the WS reader thread.

### `AnniOutlineTicker` (`outline/AnniOutlineTicker.java`)

Subscribes `ClientTickEvents.END_CLIENT_TICK` once at init. Each tick:

1. Evaluate the four-condition gate (mode, window, zone, at-least-one-toggle).
2. If the gate fails and `suppressionActive` was true → clear every glow we previously applied (walk `level.players()`, set `setGlowColor(NONE)` for tracked usernames), reset the flag, return.
3. If the gate holds → walk `level.players()`:
   - Skip the local player.
   - Registry hit → `setGlowColor(entry.outlineColor())`.
   - Was applied last tick + no longer in registry → `setGlowColor(NONE)`.
   - Otherwise → leave alone.
4. Maintain the `appliedUsernames` set so the cleanup walk only touches entities we coloured.

Exposes `isOutlineSuppressionActive()` for the two mixins to read. Debug `setForceInZone(boolean)` lets `/wv debug tree anni zone enter|exit` bypass the geo-check during dev.

### `EntityGlowingMixin` (`mixin/client/EntityGlowingMixin.java`)

Six-line HEAD-cancellable inject on `Entity.isCurrentlyGlowing()`. Returns `true` whenever `getGlowColor() != CustomColor.NONE`. Lets us outline players Wynncraft never put in a relationship team. No mode gate — the glow-colour field is `NONE` by default, so this only fires for entities the ticker has explicitly enrolled. Per outlines.md §3 Option C "Cons".

### `EntityOutlineColorMixin` (`mixin/client/EntityOutlineColorMixin.java`)

TAIL inject on `EntityRenderer.extractRenderState(Entity, EntityRenderState, F)`. While `AnniOutlineTicker.isOutlineSuppressionActive()` AND `vetsAnniOutlinesEnabled` AND the entity is an `AbstractClientPlayer` NOT in the registry → sets `state.outlineColor = 0` directly. Native team-colour outline vanishes; registry members fall through (Wynntils' own `EntityRendererMixin` TAIL inject overrides `state.outlineColor` from `EntityExtension.getGlowColor`, which `AnniOutlineTicker` has already set to the tier colour).

**Why this and not a getTeamColor mixin (first try).** Earlier draft was `EntityTeamColorMixin` — HEAD-cancellable on `Entity.getTeamColor()`, returning `0` for outsiders. It rendered every outsider with an **opaque black** outline because vanilla 1.21.11's `extractRenderState` body does:

```java
state.outlineColor = shouldEntityAppearGlowing(entity)
    ? ARGB.opaque(entity.getTeamColor())   // forces alpha=0xFF
    : 0;
```

`ARGB.opaque(0)` = `0xFF000000`, an opaque black RGBA — the outline buffer happily renders that as a solid black glow halo. Skipping the wrap entirely by clobbering `state.outlineColor` at TAIL of extract sidesteps the issue.

Mixin order vs. Wynntils' own TAIL inject doesn't matter because the branches are disjoint: registry members get their colour via the glow-colour pipeline (which we don't touch), and outsiders get `state.outlineColor = 0` regardless of which TAIL inject runs first — Wynntils' inject only fires when `getGlowColor() != NONE`, which is the registry-member case.

Side effect: tab-list colour for outsiders is **not** affected — only `state.outlineColor` is touched, which is render-state-only.

### `NametagMixin` anni branch (`mixin/client/NametagMixin.java`)

TAIL inject on `AvatarRenderer.extractRenderState(Avatar, AvatarRenderState, F)`. The anni branch runs BEFORE the existing supporter glint branch in the same inject body. While `AnniOutlineTicker.isOutlineSuppressionActive()` AND `vetsAnniNametagsEnabled`:

- Registry hit → recolour to `entry.nametagFormatting()` (matches the outline colour family).
- Registry miss → recolour to `ChatFormatting.DARK_GRAY` (outsider treatment).

Recolour mechanism:

```java
String stripped = ChatFormatting.stripFormatting(state.nameTag.getString());
state.nameTag = Component.literal(stripped).withStyle(fmt);
```

**Two non-obvious fixes baked in:**

1. **TAIL of `extractRenderState`, not HEAD of `submitNameTag`.** Original design hooked `submitNameTag` HEAD with priority 900. Wynntils' `CustomNametagRendererFeature.onPlayerNameTagRender` (subscribed to `PlayerNametagRenderEvent`, dispatched from Wynntils' priority-1000 HEAD inject on the same method) **cancels** the call whenever it adds gear-hover lines (hovered raycast target) or a Wynntils account-type badge. Cancellation propagates via the mixin processor's generated `if (ci.isCancelled()) return;` and skips every later-priority HEAD inject on the same method. Moving to `extractRenderState` TAIL writes the override into `state.nameTag` *before* Wynntils' handler ever reads it; Wynntils' prefixed-name component picks up our colour unchanged.

2. **`ChatFormatting.stripFormatting` is mandatory.** Wynncraft embeds the team colour as a legacy `§<code>` prefix INSIDE the nametag string content — `state.nameTag.getString()` returns `"§awonderkas"` for a friend-team-coloured player, not `"wonderkas"`. Without the strip, `Component.literal("§awonderkas").withStyle(RED)` renders GREEN because vanilla's text renderer parses the leading `§a` at draw time and silently overrides the Style. Confirmed live via `/wv debug trigger nametagsDump`'s `original=` field. Without this strip every recoloured nametag silently reverts to the team colour.

Falls through to the supporter glint branch only when the anni gate is off; an own-party supporter inside the gate shows their role colour for the duration and the glint resumes after the gate closes.

### Config keys

| Key | Default | Purpose |
|--|--|--|
| `vetsAnniOutlinesEnabled` | true | Master toggle for the outline overlay half. Only consulted while the highlight gate holds. |
| `vetsAnniNametagsEnabled` | true | Master toggle for the nametag overlay half. Separable from outlines so users can pick one half. |

### Debug

- `/wv debug tree anni zone enter|exit` → `AnniOutlineTicker.setForceInZone(...)` — bypasses the geo-check so dev sessions can verify the overlay without flying to the anni location.
- `/wv debug tree anni snapshot inject preset <name>` — bundled presets are listed in `AnniDebugCommands.PRESETS` (`empty`, `external_no_anni`, `member_announced`, `member_in_party`, `member_no_anni`, `member_no_anni_fill`, `member_no_anni_unregistered`).
- `/wv debug trigger bossBarsDump` — dumps vanilla's `BossHealthOverlay#events` map with per-bar `UUID/color/overlay/progress/name`. Was the smoking gun for the PINK-colour suppression — the dump immediately revealed every Wynncraft text-only bar shipped `color=PINK overlay=PROGRESS`. Useful for any future "why doesn't my bar render" question.
- `/wv debug trigger nametagsDump` — walks `level.players()` and reports each player's username, `AnniOutlineRegistry` hit/miss, tier, role, and the `ChatFormatting` the `NametagMixin` would resolve to right now. Was instrumental in finding the §-code-in-string bug — the `original=` field on the dumped line surfaces the embedded leading `§a` that vanilla's text renderer parses at draw time. Use whenever a nametag colour doesn't match what you expect; the dump's `→ <username> (<colour> via registry)` segment shows what should render in-world.

## Render-pipeline lessons

Forward-looking knowledge collected while building the subsystem; consult before adding a new render mixin or per-tick component.

1. **Prefer render-side / read-side filtering to packet-side / write-side mutation.** Four working precedents:
   - `BossHealthOverlayMixin` (`@Redirect` on `events.values()`)
   - `EntityOutlineColorMixin` (TAIL on `extractRenderState`, zeroes `state.outlineColor`)
   - `EntityGlowingMixin` (HEAD-cancellable on `isCurrentlyGlowing`)
   - `NametagMixin` (TAIL on `extractRenderState`, rebuilds `state.nameTag`)
   - Cancelling vanilla packets has burned us twice (boss-bar crash on `update()` cancel, nametag short-circuit by Wynntils' cancel). Keep vanilla bookkeeping intact; intercept what comes out, not what goes in.

2. **Don't trust `state.<field>` to mean what you think — vanilla often wraps or coerces values right before assignment.** `state.outlineColor = ARGB.opaque(getTeamColor())` — returning 0 from the getter produces opaque-black, not transparent. Always check the assignment site, not just the source value.

3. **Wynncraft embeds formatting in string content, not just in Component Style.** `state.nameTag.getString()` returns `"§awonderkas"`. Any time you `.getString()` a Wynncraft component and rebuild it with `.withStyle(...)`, strip § codes first with `ChatFormatting.stripFormatting()`. The text renderer parses leading § codes at draw time and they win over your Style.

4. **Wynncraft's resource pack overrides specific vanilla sprites.** `boss_bar/pink_background.png` is fully transparent (so PINK/PROGRESS bars render as text-only HUD strips). Other vanilla GUI sprites *may* be similarly overridden; if a vanilla render component goes unexpectedly invisible, blame the resource pack first. Stick to `{PURPLE, RED, GREEN, YELLOW, BLUE, WHITE}` for any new boss-bar colour and don't assume vanilla sprite paths are intact.

5. **Wynntils' `CustomNametagRendererFeature` and `EntityRendererMixin` are positioned at `submitNameTag` HEAD / `extractRenderState` TAIL respectively.** They cancel or override depending on player state. Test mixin behaviour with players who:
   - have a Wynntils account-type (Donator / etc.) — triggers `addAccountTypeNametag` + event cancel
   - you're currently looking at (raycast hit) — triggers `addGearNametags` + event cancel
   - have no Wynntils data — event proceeds, vanilla body runs
   - are local-player (skips some paths)
   - The nametag bug surfaced only on hovered Wynntils-tracked players; non-tracked players appeared to work, masking the bug. Use the dump command to verify the mixin is *resolving* even when in-world rendering looks normal.

6. **MixinSquared / Mixin disallows non-private static methods on mixin classes** (`vetsmod$resetLoggedNametags` crashed mod load with `InvalidMixinException`). Static fields are fine; static methods must be `private`. Same restriction applies to any helper you'd add to the mixin class itself; put them in a sibling helper class if you need public statics.

7. **`@Inject HEAD` on a cancellable method is processed in `priority` order** — higher priority runs FIRST. If any earlier-priority inject cancels via `ci.cancel()`, the mixin processor's generated `if (ci.isCancelled()) return;` guard skips your inject. For "I must always run" semantics, TAIL of an earlier method is more reliable than HEAD of a later one.

8. **`isOutlineSuppressionActive()` / `isAggressiveActive()` are the cheap public flags** for "are we doing the anni-active rendering thing right now". Each is a `volatile boolean` set per tick by its ticker — safe to query from render-thread mixins without extra locking. Expose a parallel flag from any new ticker with its own gate semantics.

## Aggressive mode

### Architecture

Five client-side components plus three wire pieces (V1ApiManager send,
AnniWsHandler ack route, AnniScrollspotClient single-flight future) and
one vets-anni internal endpoint.

| Component | Gate | Notes |
|---|---|---|
| `AnniAggressiveTicker` | computed each tick | Single cheap public `isAggressiveActive()`; every other aggressive-mode component reads it. |
| `AggressiveAlertDispatcher` | aggressive AND `vetsAnniChatAlerts` | Per-field 5s cooldown, silent first-observation (mirror FlashTracker). Two readiness alerts: T-10m world mismatch, T-5m zone absence — each at most once per stamp_epoch. |
| `AnniZoneLineRenderer` | aggressive AND `vetsAnniZoneLines` | `WorldRenderEvents.AFTER_ENTITIES`. Stacked `Gizmos.circle` cylinder cage — 21 rings per disc spaced `Y_STEP=10` blocks (±100 vertical), Y snapped to multiples of 10 so rings don't jitter as the player walks. 200-block squared-distance cull per disc. |
| `ScrollSpotMarkerProvider` | aggressive AND `vetsAnniScrollWaypoint` AND non-null entry | Registered once with `Models.Marker.registerMarkerProvider`, deferred to CLIENT_STARTED so it runs after Wynntils' own init. Default fallback `345 45 -1315` when the user is in a party with no host-pinned spot. Dark-red beacon (icon-only was infeasible — see decision #5 below). |
| `GhostsPromptHandler` | aggressive AND `vetsAnniGhostsPrompt` | Rising edge of `AnniZone.isInZone`. Walks `level.players()` + `Models.Player.isPlayerGhost`: any ghost-flagged player → prompt (ghosts confirmed on); else per-stamp_epoch sentinel. |

### Locked decisions

1. **Aggressive gate = `mode == AGGRESSIVE ∧ window` only.** No zone gate. Per user: aggressive features are window-scoped, not location-scoped. Zone lines render whenever you're aggressive + in-window so a Lutho user can see the boundary as they fly in.
2. **Two readiness alerts** appended to `AggressiveAlertDispatcher`. T-10m world-mismatch, T-5m zone-absence. Each latches a per-stamp_epoch sentinel — once per anni.
3. **Ghosts-prompt detection** = `Models.Player.isPlayerGhost(player)` walk. Reads `PlayerModel.ghosts` (the cache Wynntils maintains from `_ghostN` team assignments). If any visible player is ghost → ghosts on → prompt. Else ambiguous → per-stamp_epoch sentinel (`vetsAnniGhostsPromptShownForStamp`).
4. **Chat-alert scope = exactly role / world / party / RSVP.** No attendance-band, no party-membership.
5. **Scroll waypoint = `Texture.MAP` + dark-red beacon.** `Texture.MAP` (the 14×14 generic, NOT `Texture.MAP_ICON` which is the 21×38 content-book tab — enum naming is a known footgun). *Note: this intentionally does NOT use "no beacon beam, icon only" — Wynntils' `BeaconBeamFeature.onRenderLevelLast` NPE-crashes the render thread on a null beacon colour, and there is no per-marker "skip beacon" path: `null` crashes, `CustomColor.NONE` falls back to user-config beacon, custom 0-alpha colour is overridden to opaque by Wynntils' own `withAlpha(localAlpha)` before render. Suppressing the beacon would require a mixin into Wynntils.* Final: `CustomColor.fromChatFormatting(ChatFormatting.DARK_RED)`.
6. **Scrollspot command lives under `/wv debug tree anni scrollspot`, not `/wv anni`.** Hidden from main brigadier (no tab-complete) because it's a staff-only command used on rare occasions. Gated on `requireDebug` (must have `/wv debug true`) AND `requireStaffOrOrganiser` (staff tier OR local UUID in `snapshot.organisers`). Subcommands: `set <x> <y> <z>` / `here` / `clear` are real host writes via WS; `localinject <x> <y> <z>` / `localclear` paint the marker provider directly for visual testing without coordinating a host. All five gated identically.

### Wire shape

- Snapshot bumped to `schema_version: 3` adding `board.party.scroll_spot: {x,y,z}|null` (only on the local player's own party — not on `event.all_parties`). `AnniSnapshot.Party.scrollSpot()` returns the new nullable `ScrollSpot` record.
- vets-anni: 3 nullable `IntField`s on `Party` (`scroll_spot_x|y|z`); migration `4_20260617220201_add_party_scroll_spot.py`; `_build_party_block` splices `_scroll_spot_dict(party)`; `lifecycle_task._wipe()` clears the three columns inside the same transaction as `BoardPlacement.delete()`.
- New internal endpoint `POST /api/internal/anni-party-scrollspot` (body `{actor_mc_uuid, scroll_spot}`) — derives party from actor's `BoardPlacement` in the active event, rejects 403 unless `party.host.mc_uuid == actor_mc_uuid`.
- temp-server: `_handle_anni_scrollspot_set` in `app/chat/inbound.py` (right after `anni_query`) — authenticated-session gate, forwards via `state.anni_snapshot_poller.set_scroll_spot(body)` (new helper reusing the poller's `httpx.AsyncClient` + secret), ack as `anni_scrollspot_response`.
- vetsmod: `V1ApiManager.sendAnniScrollspotSet(x,y,z)` (null triplet = clear); `AnniWsHandler.onInbound` routes `anni_scrollspot_response` → `AnniScrollspotClient.onResponse`; `AnniScrollspotClient.set(...)` / `clear()` return `CompletableFuture<Ack>` with 5s timeout.

### New config keys

- `vetsAnniZoneLines` (bool, default `true`)
- `vetsAnniScrollWaypoint` (bool, default `true`)
- `vetsAnniChatAlerts` (bool, default `true`)
- `vetsAnniGhostsPrompt` (bool, default `true`)
- `vetsAnniGhostsPromptShownForStamp` (string, internal — not user-facing — persists the stamp_epoch the prompt last fired for; survives restarts)

### New debug commands

All gated on `requireDebug` (must have `/wv debug true`); the `scrollspot` subtree is also gated on `requireStaffOrOrganiser`.

- `/wv debug tree anni scrollspot set <x> <y> <z>` — host write via WS frame (real action; vets-anni 403s unless you're the host of your assigned party).
- `/wv debug tree anni scrollspot here` — host write using your current block-pos.
- `/wv debug tree anni scrollspot clear` — host write that clears the party's spot.
- `/wv debug tree anni scrollspot localinject <x> <y> <z>` — local-only paint into the marker provider; no server round-trip. For visual testing of the waypoint render without coordinating a host.
- `/wv debug tree anni scrollspot localclear` — clear the local-only injection.
- `/wv debug tree anni alert <role|world|party|rsvp|zone|world_ready>` — synthesise a chat alert without contriving a diff or waiting for a T-N boundary.
- `/wv debug trigger ghostsPromptDump` — dump `aggressive_active / toggle_on / in_zone / stamp / shown_for` + per-player `isPlayerGhost` result + `would_fire_on_rising_edge`. Mirrors `nametagsDump` / `bossBarsDump`.
- `/wv debug trigger zoneLinesDump` — dump aggressive gate state + every cached `AnniZone.Disc` + squared distance to the player. Diagnostic for "why aren't lines rendering."

### Render-pipeline lessons (cont'd)

9. **`Gizmos.circle` exists.** The zone-line renderer uses it. Centre is `Vec3(disc.x, snappedY, disc.z)` — the disc geometry is 2D in `AnniZone` so Y is unspecified; snapping to multiples of `Y_STEP` lets the cylinder-cage stack stay anchored as the player moves. Other available primitives: `cuboid`, `circle`, `line`, `arrow`, `rect`, `point`, `billboardTextOverBlock`. None require Mojang-mappings remapping — they're in `net.minecraft.gizmos.*` and resolve directly under Loom 1.15.5.
10. **Wynntils `Models.Player.isPlayerGhost(player)` is the canonical "is this player phased to another world" check.** Backed by `PlayerModel.ghosts` — a `Map<UUID, Integer>` populated from `_<TIER><N>` team assignment events. Cleared on `WORLD` state change. The ghosts-prompt uses it as a presence-detector for "does the user have `/toggle ghosts on`" (if there's a ghost in the player list, ghosts can't be off, because the server filters them otherwise).
11. **MarkerProvider lifecycle.** `Models.Marker.registerMarkerProvider(...)` is one-shot at vetsmod load. The provider's `isEnabled()` is what gates per-tick visibility — do NOT call `registerMarkerProvider` from a snapshot listener or reconnect handler (the registration list would grow on every reconnect). `ScrollSpotMarkerProvider.registerWithWynntils()` is idempotent via a static flag for belt-and-braces.
12. **Init order between vetsmod and Wynntils.** Fabric does NOT guarantee entrypoint order between sibling mods. Static-touching `Models.Marker` (or any other `Models.X`) from `VetsmodClient.onInitializeClient()` body triggers `Models.<clinit>` which constructs `FriendsModel` / `PartyModel` etc. — their `<init>`s call `WynntilsMod.postEvent(...)` against the not-yet-initialised eventBus, NPE, half-init Models, then Wynntils' own `Managers.<clinit>` cascade-crashes the game during boot. **Defer any `Models.X.something(...)` init-time call into the existing `ClientLifecycleEvents.CLIENT_STARTED` handler** (fires after every mod's onInitializeClient). Runtime calls (per-tick, per-snapshot-listener) are always safe — by tick time Wynntils is up. Saved as `feedback_vetsmod_wynntils_init_order.md` memory.
13. **Wynntils `BeaconBeamFeature` is unconditional.** Every entry in `Models.Marker.getAllMarkers()` gets a beacon beam — there's no per-marker "skip" path. `marker.beaconColor()` is called and `.withAlpha(float).asInt()` invoked on it: null → render-thread NPE → "Pose stack not empty" next-frame crash; `CustomColor.NONE` → fallback to user-config beacon (still visible); custom 0-alpha → Wynntils overrides alpha back to opaque before render. Conclusion: if you register a MarkerProvider, you ship a beacon. Pick a colour you can live with.
14. **Cold-start world-join must trigger a snapshot pull.** `StampFetcher.fetchStampAndCreateMessage` (world-join motd path) reads `AnniSnapshotCache.latest()` and on `null` fires a fire-and-forget `AnniQueryClient.query()` before falling through to legacy stamp text. *Note: this intentionally does NOT rely on the push poller alone — outside the T-2h hot window the poller fires every 5 min, so on cold-start a user inside the anni zone would otherwise see legacy motd + no boss bar for up to 5 min.* Any new component that depends on `AnniSnapshotCache.latest()` being warm at world-join time gets this for free.

## `/wv anni rsvp`

### Architecture

Backs the `[Hard]`/`[Soft]` upgrade-prompt buttons in `AnniCommandRenderer` (their `SuggestCommand("/wv anni rsvp hard|soft")` targets this command). One brigadier command + a network client + a single vets-anni endpoint; no schema bump (`rsvp.notice` is already on the snapshot at v2/v3).

Wire pieces on the network layer:
- `V1ApiManager.sendAnniRsvp(String notice)` (mirror of `sendAnniScrollspotSet`).
- `AnniWsHandler.onInbound` routes `anni_rsvp_response` to `AnniRsvpClient.onResponse`.

`AnniRsvpClient` is a single-flight ack future (clone of `AnniScrollspotClient`); also exposes `lastAttemptedNotice` / `lastAck` / `pendingCount` for `/wv debug trigger rsvpDump`.

### Auth chain

```
/wv anni rsvp hard
  → AnniRsvpCommand.hard (GuildStateManager.isAuthenticatedThisSession() gate)
  → AnniRsvpClient.send("hard")
  → V1ApiManager.sendAnniRsvp("hard")  → {"type":"anni_rsvp","notice":"hard"}
  → temp-server _handle_anni_rsvp (stamps actor_mc_uuid from session)
  → POST vets-anni /api/internal/anni-rsvp-by-uuid
  → app/domain/rsvp_by_uuid.execute_uuid_rsvp(...)
        → rsvp_domain.set_rsvp(player, event, RSVP_HARD)
        → _auto_place_after_rsvp → _broadcast_board_snapshot
        → _post_public(app.state.fishbot, "<user> has **HARD** RSVP'd …")
  → 200 {"status":"ok"} or 4xx {"status":"error","detail":"…"}
  → temp-server forwards as {"type":"anni_rsvp_response","status":…,"detail":…}
  → AnniRsvpClient.onResponse pops the head future
  → AnniRsvpCommand.renderAck (main thread) prints "You have HARD RSVP'd…"
    with the HARD token coloured via AnniHoverBuilder.noticeColor("hard")
    (AQUA per the noticeColor map).
```

### Locked decisions

1. **`/wv anni rsvp` lives in the main brigadier tree**, NOT under `/wv debug`
   (opposite of scrollspot). This is a public-facing user command — the
   `rsvpUpgradePrompt` buttons in `AnniCommandRenderer` point
   `SuggestCommand` at it. The debug-tree mirror under
   `/wv debug tree anni rsvp` exists for symmetry but is gated on
   `requireDebug` only (no staff/organiser perm — action only affects
   the caller's own RSVP).
2. **Unauthenticated message uses spec wording**:
   `"Use \\rsvp on discord — or run ~vetsmod first."` Surfaces both the
   Discord fallback and the link path. Differs from scrollspot's
   `"Run ~vetsmod to authenticate before using /wv anni scrollspot."`.
3. **vets-anni reuses the cog's helpers via underscore-imports**.
   `app/domain/rsvp_by_uuid.execute_uuid_rsvp` imports
   `_auto_place_after_rsvp`, `_broadcast_board_snapshot`, `_post_public`,
   `_notice_label`, and `RsvpOutcome` directly from `app/bot/cogs/rsvp.py`.
   No refactor into a shared module; the leading underscore is a
   convention, not an access boundary, and the diff is smaller.
   `app/domain/rsvp_shared.py` is a fallback if an import cycle ever
   surfaces (none today; pytest `test_rsvp.py` + `test_rsvp_by_uuid.py`
   = 64 passing).
4. **First-invocation `AnniPlayer` placeholder uses `mc_uuid[:8]` as
   `mc_username`** (matches `auto_promoter.py::_tick`'s `op.username if op
   else uuid[:8]` fallback for online-but-unknown players). Full UUIDs
   are 36 chars — too long for the 32-char `mc_username` field — so a
   `mc_username = actor_mc_uuid` fallback would fail Tortoise's
   validator. Public message names the player as the `uuid[:8]` until
   auto-promoter / presence-poller hydrates the row on the next cycle.
5. **No `username_hint` field threaded through the WS frame.**
   Temp-server already has the session's `mc_uuid` and the placeholder
   fallback covers brand-new users — no need for a fragile client-side
   username hint that would also need an "is this username actually
   yours" verification.
6. **T-90 cutoff branching**: revokes pass through unconditionally
   (matches Discord cog behaviour); hard/soft within cutoff get a 409
   with `detail: "RSVP is closed (within 90 min of anni)"` so the user
   sees a clear reason.
7. **`AnniRsvpClient.lastAttemptedNotice` / `lastAck` are race-prone**
   (static volatiles, no per-call correlation) — acceptable for
   debug-only `rsvpDump` view. The `pendingCount()` accessor gives an
   honest in-flight indicator.
8. **Auto-refresh on success.** `AnniRsvpCommand.renderAck` fires
   `AnniQueryClient.query()` after a successful ack. Without this,
   `/wv anni` reads the cached snapshot which is up to 5 minutes stale
   outside the T-2h hot window (push poller cadence). User reported the
   exact symptom: post-`/wv anni rsvp soft`, the next `/wv anni` still
   showed `RSVP Type: EARLY WALK-IN`. The query is fire-and-forget; the
   listener bus in `AnniSnapshotCache` re-renders everything that
   subscribes (boss bar, outlines, flash, future `/wv anni`).
9. **Server-side cache-bust coordinates with the auto-refresh.** Temp-server's
   `_handle_anni_rsvp` pops the user's UUID from
   `state.anni_snapshots_by_uuid` on a successful forward to vets-anni.
   Otherwise the immediate follow-up `anni_query` from vetsmod hits
   temp-server's 15s short-cache and returns pre-RSVP state. Server-side
   pop is one line and avoids extending the WS protocol with a
   "force-fresh" flag.
10. **`buckets_domain.promote_from_wontassign`** — helper that fixes
    the revoke + re-RSVP regression.
    `_auto_place_after_rsvp` dispatches three ways:
    - No placement → `ensure_placed` (insert into main UNASSIGNED).
    - Currently in WONTASSIGN → `promote_from_wontassign` (move back to
      main UNASSIGNED). The fresh RSVP overrides the prior demote — an
      explicit `/wv anni rsvp` is a strong user signal.
    - Any other placement (party, walk-in lane, etc.) → no-op (staff
      intent / original lane preserved). Pinned by
      `test_revoke_then_re_rsvp_does_not_promote_party_placement`.
11. **`wont_reason = "RSVP retracted"` when the player has a revoked
    Rsvp.** Vetsmod render is `wont_reason`-driven so the string flows
    verbatim through `AnniSnapshot.Board.wontReason()` into
    `AnniCommandRenderer.boardSection`'s `case "wont_assign"` branch.
    User intent: "won't assign (retracted) != Sitting out". The query
    is one `Rsvp.filter(event, player, revoked_at__isnull=False).exists()`
    on the same path. *Note: this intentionally does NOT use
    `BUCKET_LABEL.get(BucketKind.WONTASSIGN)` ("Sitting out") for every
    WONTASSIGN — that was the original hard-coded behaviour.*

### Public-message format

Reuses the cog's exact format strings, byte-equivalent to a Discord
`\rsvp` invocation:

```
hard/soft: `<user>` has **HARD/SOFT** RSVP'd for the anni <t:N:R> (<t:N:F>).
revoke:    `<user>` withdrew their RSVP.
```

Discord-timestamp tags only (CLAUDE.md "anni timing must localise per
viewer"). `<user>` is `player.mc_username` (the `uuid[:8]` placeholder
for brand-new UUIDs until auto-promoter hydrates).

### Render-pipeline lessons (cont'd)

15. **`app.state.fishbot` is the reach-out for `_post_public`** from a
    FastAPI route handler. Set in `main.py::lifespan`
    (`app.state.fishbot = bot`); route handlers read via
    `request.app.state.fishbot`. May be `None` when `FISHBOT_TOKEN` is
    unset — `_post_public` already no-ops on missing channel/bot so no
    try/except needed at the endpoint level. Same reach-out pattern
    `anni_ping_poller.py` uses.

16. **Brigadier literals don't need `SuggestionProvider`s.** Three
    literal children (`hard`/`soft`/`revoke`) under a `rsvp` literal
    give brigadier its own suggestion list — same shape as
    `silent`/`passive`/`aggressive` already did under `anni`. No
    `StringArgumentType.word()` + custom `SuggestionProvider` needed.

17. **`AnniHoverBuilder.noticeColor` is the canonical RSVP colour map**
    even from chat-output code, not just from `/wv anni` renderer code.
    Saved as `feedback_anni_rsvp_colours.md` memory — call the helper,
    don't inline `ChatFormatting.AQUA`/`GREEN` literals.

### New debug commands

All under `/wv debug tree anni …` (subtree) + `/wv debug trigger …`
(flat trigger family), gated on `requireDebug` (must have
`/wv debug true`).

- `/wv debug tree anni rsvp hard|soft|revoke` — debug mirror of main
  brigadier. Identical effect; bypasses no logic. For symmetry with
  the scrollspot debug-tree mirror.
- `/wv debug trigger rsvpDump` — dump auth state, in-flight queue
  depth, last attempt/ack, and the snapshot's `rsvp` block. Same
  pattern as `bossBarsDump` / `nametagsDump` / `zoneLinesDump` /
  `ghostsPromptDump`. Read `AnniRsvpClient`'s public
  `lastAttemptedNotice()` / `lastAck()` / `pendingCount()` accessors.

## Party back-report

`PartyRosterListener` uses a direct organiser-presence gate (the legacy
`party_status` frame is removed end-to-end across vetsmod / temp-server
/ vets-anni). Anni parties only exist inside the active window, and an
anni party's host is always in `organisers`, so the broader year-round
signal would be over-collection and the organiser-presence gate is the
exact signal vets-anni needs.

### What ships

1. **Snapshot field** — `organiser_usernames: list[str]` parallels the
   existing `organisers: list[str]` (UUIDs) on every snapshot. Built in
   one query pair in
   [`vets-anni/app/domain/snapshot.py::_organisers`](../../vets-anni/app/domain/snapshot.py)
   to keep UUID/username order in lockstep. Names are needed because
   Wynncraft only exposes party members by username — see
   `Wynntils PartyModel.getPartyMembers()`.

2. **vets-anni endpoint** — `POST /api/internal/anni-party-observation`
   in
   [`anni_internal.py`](../../vets-anni/app/web/routers/anni_internal.py).
   Body `{observer_mc_uuid, party_member_usernames, leader_username,
   world}`. Resolves names via `state.resolve_uuid()` (roster cache →
   alias fallback, the latter populated from WAPI's `legacyName` field
   through temp-server's existing `/v1/outbound/aliases`). Writes
   `{member_uuid: leader_uuid}` into `state.party_leader_by_uuid`;
   unresolvable leader = no-op (`{resolved:0}`); the observer's session
   UUID is the authoritative fallback for the observer's own entry.
   Returns `{status, resolved, dropped}` for observability.

3. **TTL gate** — `_PARTY_LEADER_TTL_SECONDS = 60` in
   [`state.py`](../../vets-anni/app/services/state.py);
   [`presence_poller`](../../vets-anni/app/services/presence_poller.py)
   degrades stale entries back to `ONLINE_WORLD` so a vetsmod
   disconnect mid-window doesn't pin a user to yellow forever.

4. **temp-server inbound handler** — `_handle_anni_party_observation`
   in
   [`inbound.py`](../../temporary-server/app/chat/inbound.py)
   mirrors `_handle_anni_rsvp`: auth-required, stamp
   `observer_mc_uuid` from session, forward via new
   `AnniSnapshotPoller.send_party_observation(body)` helper,
   typed response frame.

5. **vetsmod gate refactor** —
   [`PartyRosterListener.flush`](../src/client/java/org/wynnvets/listeners/PartyRosterListener.java)
   now reads `AnniSnapshotCache.latest().organiserUsernames()` and
   tests case-insensitive overlap with the captured party's
   `leader + members`. Calls
   `V1ApiManager.sendAnniPartyObservation(members, leader, world)`.
   Pure predicate `shouldSend(snap, anniSnapshot, stamp, now)` is the
   testable core; legacy `vetsConnected` / `tier` parameters are gone.

6. **Mid-window snapshot trigger** —
   [`AnniPartyReporter`](../src/client/java/org/wynnvets/mwe/anni/party/AnniPartyReporter.java)
   subscribes to `AnniSnapshotCache`. On any change to the lowercased
   `organiser_usernames` set, calls
   `PartyRosterListener.requestRecapture()`. Handles "anni opens while
   parked in a static party for 30 min" — no `PartyEvent` would fire
   on its own. Registered from `VetsmodClient.onClientStarted`
   (CLIENT_STARTED), never from `onInitializeClient` per
   `feedback_vetsmod_wynntils_init_order.md`.

### What got deleted

The legacy `party_status` machinery is excised in full:

- **vetsmod**: `V1ApiManager.sendPartyStatus`, cohort-gate parameters,
  `OnlineMemberService.refreshAsync()` call site, `VETS_TIERS`
  constant, the TODO comment that envisioned this exact swap.
- **temp-server**: `_handle_party_status`,
  `state.party_status_by_reporter`, `/v1/outbound/party_status` route
  (`app/routes/static.py`), disconnect-time cleanup line, the
  protocol-doc section.
- **vets-anni**: `party_status_poller.py`,
  `tests/test_party_status_poller.py`, its `main.py` task wiring,
  `party_status_poll_*` settings, `tempserver.party_status()` client
  method. `state.party_leader_by_uuid` keeps the same name (only the
  data source changed).

### Implementation notes

- **Wire format is `{observer_mc_uuid, party_member_usernames: [str],
  leader_username, world}` — names over the wire, vets-anni does the
  resolution.** *Note: this intentionally does NOT use the
  `[{uuid, username}]` shape — Wynntils' `PartyModel.getPartyMembers()`
  returns `List<String>` of usernames only, and Wynncraft's tab list is
  fake (80 sentinels per `TabListGuildParser`), so client-side
  name→UUID resolution would drop 30-60% of members.*
- **Snapshot helper `_organisers` returns a `(uuids, usernames)` tuple
  from one query pair.** Re-querying would race on order desync if a
  host reassignment landed mid-build.
- **`observer_mc_uuid` injected by temp-server, never trusted from the
  frame body** (impersonation vector — the frame body's
  `observer_mc_uuid`, if present, is discarded).
- **TTL gate on the presence corroboration check.** *Note: this
  intentionally does NOT pin the user to yellow indefinitely on a
  vetsmod disconnect — without the TTL, no other writer would clear
  the entry.* Default 60 s; tune during live anni if presence
  flickers.
- **`AnniPartyReporter` is a thin snapshot listener, NOT a wrapper
  around the send call.** `PartyRosterListener.flush()` calls
  `V1ApiManager.sendAnniPartyObservation` directly — same shape as
  the other `sendAnniX` methods. Splitting the send into a separate
  class would be pure indirection.
- **Response frame is typed (`anni_party_observation_response`).** The
  handler in
  [`AnniWsHandler`](../src/client/java/org/wynnvets/mwe/anni/network/AnniWsHandler.java)
  debug-logs only — no consumer state today. Mirror
  `AnniRsvpClient`'s diagnostic accessors only if a debug-trigger
  dump surfaces the need.
- **The listener gate reads `stampEpoch` from the snapshot, not from
  `AnniStampPoller`.** `PartyRosterListener.snapshotStamp(snapshot)`
  reads `snapshot.event().stampEpoch()` — the same field `/wv anni`
  consumes. *Note: this intentionally does NOT read
  `AnniStampPoller.getLatestStamp()` — the legacy live-temp-server
  cache returns 0 in dev when no anni is actually announced, so
  debug-inject (which populates only `AnniSnapshotCache`) would fail
  the gate.* General rule: any new code reads the active stamp from
  the snapshot, not from the legacy poller.

### Reference templates

Four clean reference templates exist for the shapes this section
touches. Don't redesign — clone.

**Auth + forward template (temp-server `inbound.py`)** — four nearly
identical handlers:
1. `_handle_anni_query` (read path; session-or-frame uuid resolution).
2. `_handle_anni_scrollspot_set` (host-only write; session uuid only).
3. `_handle_anni_rsvp` (self-only write; session uuid + cache-bust).
4. `_handle_anni_party_observation` (clone of #3 with a different
   poller forward; stamps actor's UUID into the body so the client
   can't impersonate, returns `anni_party_observation_response` with
   `{status, detail}`).

If the count of these grows to 5, extract a
`_handle_anni_authenticated_forward(...)` helper in the same file.

**Poller helper template (temp-server `anni_snapshot_poller.py`)** —
three clones: `set_scroll_spot(body)`, `set_rsvp(body)`, and
`report_party_observation(body)`. All reuse `self._client` +
`self._secret` + `self._base_url`, return `{"status":"ok"}` /
`{"status":"error","detail":"…"}` / unreachable.

**Single-flight ack client (vetsmod `org.wynnvets.mwe.anni.network`)** —
three clones: `AnniQueryClient` (returns `CompletableFuture<AnniSnapshot>`),
`AnniScrollspotClient` (returns `CompletableFuture<Ack>`),
`AnniRsvpClient` (returns `CompletableFuture<Ack>`). FIFO
`ConcurrentLinkedDeque`, 5 s `orTimeout`, `exceptionally` removes the
head on timeout. The party-observation report is fire-and-forget (no
ack is needed because vetsmod doesn't render anything from the
response), so it skips the single-flight client and just enqueues the
frame send via `V1ApiManager.sendAnniPartyObservation(...)`.
**Don't add an `AnniPartyReporterClient` unless an ack arrives that
the client needs to react to.**

**V1ApiManager send template** — four clones (`sendAnniQuery`,
`sendAnniScrollspotSet`, `sendAnniRsvp`, `sendAnniPartyObservation`).
Check `inboundClient.isConnected()`, build `{"type":"...",...}` JSON,
call `inboundClient.send(payload)`, return bool. Mirror.

**`AnniWsHandler.onInbound` demux** — four routes; add a fifth if a
new frame type needs a vetsmod-side consumer.

### What carries forward to future MWEs

- **Auth + forward template** (`_handle_anni_party_observation` is the
  fourth instance after `_handle_anni_query`/`_scrollspot_set`/`_rsvp`).
  If the count crosses 5, extract a shared helper.
- **`AnniPartyReporter`'s snapshot-listener pattern** generalises to
  any future MWE that needs "fire a back-report when X organiser-like
  state appears". Don't duplicate the listener bus; subscribe and
  diff.
- **`presence_poller`'s TTL gate** is the right shape for any
  future presence corroboration source — bare presence of a key is
  not enough; freshness has to be checked.

## Source-of-truth pointers

- Snapshot wire contract — [`vets-anni/.claude/snapshot_integration.md`](../../vets-anni/.claude/snapshot_integration.md)

*Note: the original cross-repo spec and the S3–S5 investigation prep docs lived under `vets-anni/.claude/ephemeral/` and were deleted as part of the post-S7 cleanup. The load-bearing decisions from each are inlined into the relevant sections above (§Boss bar, §Player highlights, §Aggressive mode); the code is the remaining source of truth.*
