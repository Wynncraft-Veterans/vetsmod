---
name: vetsmod MWE/anni subsystem
description: Architecture-as-built for the anni integration after S1+S2+S3 (snapshot pipeline, /wv anni renderer, anni-motd, debug harness, mode state, passive-mode boss bar). Source-of-truth for S4+ implementers.
type: project
---

# vetsmod MWE/anni subsystem (S1+S2+S3 reference)

Reference for the **as-built** state after S1 (snapshot pipeline), S2 (`/wv anni` + anni-motd renderers, debug harness, mode state), and S3 (passive-mode boss bar) of the multi-stage plan at `C:/Users/tjpas/.claude/plans/this-is-a-massive-drifting-moth.md`. Read this first if you're picking up S4+; the plan file is the architectural intent, this doc is what the codebase actually does.

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
│   └── AnniZone.java               — 60s cached fetcher for api.wynncraft.com/v3/map/world-events; 48-block disc test
└── render/
    ├── AnniHoverBuilder.java      — colour tokens, role chips, rsvp badges, link/click/hover helpers
    ├── AnniCommandRenderer.java   — /wv anni payload builder; returns List<MutableComponent>
    └── AnniMotdRenderer.java      — world-join anni-motd (two-line "Annihilation returns in X.Yh! / You have been assigned …")
```

## Snapshot pipeline (S1)

1. **vets-anni** assembles per-uuid snapshots via `app/domain/snapshot.py::assemble_snapshot`. The `event` block is populated whenever any past `AnniEvent` exists (S2 fix); `event: null` is only emitted on a truly empty DB.
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
- **Far-out (T-2h+)** — header (link + "returns in Xh Ym (HH:MM, day d)") + Assigned Role *or* Eligible Roles + RSVP Type + Attendance Chance + Party Assignment + Host.
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

Short codes are reserved for boss-bar space constraints in S3; chat callers always use the configured style.

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

## Boss bar (S3)

Originally implemented as Option B from [`boss-bar.md`](C:/Low-Perm-Program-Files/Projects/vets-anni/.claude/ephemeral/vetsmod-integration-investigation-prep/boss-bar.md) §3 (priority-500 mixin cancelling `BossHealthOverlay#update`), but **that approach crashed the client** during S3 testing on 2026-06-16. Vanilla's update handlers do `events.get(uuid).setName(...)`, and cancelling Add packets (or wiping the map at activate-time) left the server's view inconsistent with `events` — subsequent UpdateProgress / UpdateName / UpdateStyle for those UUIDs dereferenced `null` and disconnected the client.

**Current approach: render-side filter.** Let vanilla and Wynntils track bars normally (no `update()` cancellation, no `events.clear()`). The priority-500 `BossHealthOverlayMixin` instead `@Redirect`s the `events.values()` call inside `BossHealthOverlay#render(GuiGraphics)` — while active, it returns just our UUID's entry (or an empty collection if our entry isn't there yet); when inactive, it returns the unmodified collection. Wynntils' overlays render through their own paths and aren't affected. `Models.StreamerMode.isInStream()` now works without the let-through hack (Wynntils tracks the streamer-mode bar normally).

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

## Anni zone (S3)

`AnniZone` (`zone/AnniZone.java`) — JSON fetcher for `https://api.wynncraft.com/v3/map/world-events`, looks up event `a63b2c02`, parses its `location[].event.{x,z}` into disc centres. 60s refresh, stale-fallback on transient failure, cold-fallback returns `isInZone() = false` (so the boss-bar countdown variant defaults to the assigned/seeking text rather than a false-positive zone claim).

Live API shape (probed 2026-06-16): `location: [{event: {x:315, y:31, z:-1291}, spawn: {x:350, y:29, z:-1291}, reward: {x:348, y:29, z:-1292}, radius: 48}]`. Spec mandates 48-block radius; we hard-code the constant in case the API drifts. Squared-distance comparison, no `Math.sqrt`.

S4/S5 consumers (outline gate, scroll waypoint, ghosts prompt) will reuse this same fetcher.

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
- `flash <role|party|world|rsvp>` — force a `FlashTracker` pulse on the named field (5s default, sound + bold/underline alternation).
- `mode set <silent|passive|aggressive>` — `AnniModeManager.transitionTo(..., DEBUG_BYPASS_MUTEX)`. Skips the `/stream` mutex so we can verify rendering during screen capture.
- `zone <enter|exit>` — S4+ placeholder.

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

## Config keys (S1 + S2 + S3)

| Key | Type | Default | Purpose |
|--|--|--|--|
| `vetsAnniEnabled` | bool | false (auto-true on first vets-tier auth ack) | Master MWE toggle |
| `vetsAnniMode` | string | `silent` | Active anni mode |
| `vetsAnniRoleStyle` | string | `descriptive` | Role-naming style (`descriptive`/`short`/`formal`) — boss bar always uses `short` |
| `vetsAnniShowHoverDetails` | bool | true | Populate hover tooltips on chips |
| `vetsAnniPromptRsvp` | bool | true | Show line 2 of anni-motd; show RSVP-suggest pills on `/wv anni` |
| `vetsAnniShowPrediction` | bool | true | Show `\guess`-style prediction in `/wv anni` not-announced |
| `vetsAnniBossbarEnabled` | bool | true | Master kill-switch for the synthetic boss bar (S3). Only consulted in passive/aggressive. |
| `vetsAnniFlashIntensity` | string | `normal` | `subtle`=5s / `normal`=10s / `strong`=20s flash duration per field change. |
| `vetsAnniFlashSound` | bool | true | Whether per-field changes also play the name-ping sound twice. |

## Open follow-ups not done in S2/S3

- **Client-side prediction for the no-snapshot case** — external users falling back to legacy still see "not announced" instead of a prediction. Would need a ~30-line Uniform(71.4h, 82h) model anchored on `AnniStampPoller`. Deferred pending user call.
- **Active-mode highlight on the mode-switch UI** — current state isn't visually indicated. User clicks to switch; no "you are here" marker.
- **Boss-bar countdown stamp source** — spec §3.1.1.3 footnote suggests T-5m text should derive from Wynntils' world-event countdown rather than purely from `stamp_epoch`. S3 uses the stamp; revisit if a real anni test reveals drift.

## For the S4 agent

S3 prepared more than it strictly needed; pickup notes:

- **`AnniZone.isInZone(x, z)` is shipped and live-tested.** S4's outline-window gate (T-2h to T+30m, in-zone) calls this directly. The fetcher polls `api.wynncraft.com/v3/map/world-events` for event `a63b2c02` every 60 s with stale-fallback.
- **`Models.WorldState.getCurrentWorldName()` is the canonical "what world am I on" source** — used by `FlashTracker.tick()` already. S4's per-tick driver can reuse without re-deriving.
- **Mixin architecture lesson from S3:** prefer **render-side suppression** to update-path cancellation when the destination might dereference on missing entries. Cancelling vanilla packets at HEAD looks clean in theory but leaves the server's view inconsistent with the local map — `BossHealthOverlay` NPE'd within hours. The render-filter pattern in `BossHealthOverlayMixin` (`@Redirect` on `Ljava/util/Map;values()Ljava/util/Collection;` inside `render(GuiGraphics)`) is the working precedent. If S4's `EntityTeamPacketMixin` faces a similar choice, lean toward filtering the read side, not the write side.
- **`AnniModeManager.current()` returns the live mode** — S4 outline gate reads this for `mode != SILENT`.
- **`/wv debug tree anni snapshot inject preset member_in_party`** sets up the most useful S4 test fixture (assigned to party, in EU5, with party members for the outline rules to act on).

## Source-of-truth pointers

- Spec — [`C:/Low-Perm-Program-Files/Projects/vets-anni/.claude/ephemeral/vetsmod-fishbot-integration-spec.md`](C:/Low-Perm-Program-Files/Projects/vets-anni/.claude/ephemeral/vetsmod-fishbot-integration-spec.md)
- Plan — `C:/Users/tjpas/.claude/plans/this-is-a-massive-drifting-moth.md`
- Snapshot wire contract — [`vets-anni/.claude/snapshot_integration.md`](C:/Low-Perm-Program-Files/Projects/vets-anni/.claude/snapshot_integration.md)
- Boss-bar investigation prep (for S3) — [`boss-bar.md`](C:/Low-Perm-Program-Files/Projects/vets-anni/.claude/ephemeral/vetsmod-integration-investigation-prep/boss-bar.md)
- Outlines investigation prep (for S4) — [`outlines.md`](C:/Low-Perm-Program-Files/Projects/vets-anni/.claude/ephemeral/vetsmod-integration-investigation-prep/outlines.md)
- Waypoints investigation prep (for S5) — [`waypoints.md`](C:/Low-Perm-Program-Files/Projects/vets-anni/.claude/ephemeral/vetsmod-integration-investigation-prep/waypoints.md)
