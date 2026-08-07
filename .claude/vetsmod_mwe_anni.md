---
name: vetsmod MWE/anni subsystem
description: Architecture-as-built for the vetsmod ↔ fishbot anni MWE subsystem (snapshot pipeline, /wv anni renderer, anni-motd, debug harness, mode state, passive-mode boss bar + outlines/nametags, aggressive-mode zone lines + scroll spot + ghosts prompt + chat alerts, in-game `/wv anni rsvp`, party back-report).
type: project
---

# vetsmod MWE/anni subsystem

Architecture-as-built reference. Read this first if you're touching the subsystem; the code itself is the source of truth, this doc captures the load-bearing design context that isn't obvious from the code.

Organised by subject, not by the order the work happened. Each category — config keys, debug commands, wire shapes — has exactly one section, and that section is the complete list.

## Package layout

Most anni-side code lives under `org.wynnvets.mwe.anni.*`. The ones that do not, and are easy to miss: `fetcher.polling.AnniSnapshotPoller` and `AnniStampPoller`, `fetcher.ondemand.StampFetcher`, `commands.CommandRegistry`, `listeners.PartyRosterListener`, `api.V1ApiManager`'s four `sendAnni*` methods, and the four mixins under `mixin.client`.

```
mwe/anni/
├── state/
│   ├── AnniSnapshot.java          — Gson-hydrated bean mirroring the wire shape; snake_case fields, deliberately not a record
│   └── AnniSnapshotCache.java     — process-wide volatile + listener bus
├── network/
│   ├── AnniWsHandler.java         — V1 outbound/inbound listener; one outbound + four inbound frame types (§Snapshot pipeline)
│   └── AnniQueryClient.java       — FIFO-queued pull (anni_query), no correlation IDs; 8s deadline
├── debug/
│   └── AnniDebugCommands.java     — the /wv debug tree anni harness: 11 top-level literals, 24 leaves (§Debug harness)
├── mode/
│   ├── AnniMode.java                  — enum SILENT/PASSIVE/AGGRESSIVE + config converters
│   ├── AnniModeManager.java           — single chokepoint for transitions; enforces /stream mutex
│   ├── AnniWindowWatcher.java         — subscribes to AnniSnapshotCache; at T+30m restores AnniModeManager.preferredMode()
│   └── StreamerModeChatDetector.java  — chat-line backup for isInStream(); SILENT on stream-on, preferredMode() back on stream-off
├── bossbar/
│   ├── VetsBossBarManager.java         — per-tick driver; owns synthetic LerpingBossEvent; T-20s watchdog + 2.5h failsafe
│   ├── VetsBossBarContentBuilder.java  — (snapshot, secs, x, z) → Component + colorFor(snapshot); seeking/assigned/countdown variants
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
2. **temp-server's poller** (`app/services/anni_snapshot_poller.py`) hits vets-anni's `/api/internal/anni-snapshot-batch` and `/api/internal/anni-player/{uuid}`; pushes per-uuid changes as `anni_state` frames every ~10 s in the hot window, ~5 min otherwise. Eligibility refreshes every 60 s.
3. **vetsmod's `AnniWsHandler`** is the frame router. On the *outbound* channel it handles `anni_state` (push) alone — hydrating it into `AnniSnapshot` and dropping it into `AnniSnapshotCache`. On *inbound* it branches four ways: `anni_query_response`, `anni_scrollspot_response` and `anni_rsvp_response` go to their single-flight clients (`AnniQueryClient` / `AnniScrollspotClient` / `AnniRsvpClient`), and `anni_party_observation_response` is debug-logged inline with no queue and no consumer state.
4. **`AnniSnapshotCache`** is a single-player, volatile, listener-bus cache. Listeners run on whichever thread called `update` — the WS reader thread in production, the client command thread when `AnniDebugCommands` injects — so bounce to the main thread via `Minecraft.getInstance().execute(...)` if you need to touch render state. `schemaVersion()` is surfaced but never validated: a v1 payload just degrades, it is not rejected.
5. **Cold start pulls rather than waiting for the push.** `StampFetcher.fetchStampAndCreateMessage` (the world-join motd path) reads `AnniSnapshotCache.latest()` and on `null` fires a fire-and-forget `AnniQueryClient.query()` before falling through to legacy stamp text. *Note: this intentionally does NOT rely on the push poller alone — outside the T-2h hot window `AnniSnapshotPoller` fires every 5 min, so on cold start a user already inside the anni zone would otherwise see legacy motd and no boss bar for up to 5 min.* Any new component that depends on the cache being warm at world-join time gets this for free — but only while `vetsAnniEnabled` is true, since `StampFetcher` gates the pull behind `anniIntegrationActive()`. `AnniWsHandler.register()` installs the reconnect counterpart — a post-connect `AnniQueryClient::query` re-pull.

## Mode state

Three config keys, not one, and the second is what makes the automatic
transitions safe:

- `VetsConfig.VETS_ANNI_MODE` — string, `silent`/`passive`/`aggressive`, default `silent` on disk, persisted. **Not exposed through `/wv config`**: it is absent from `VetsConfig.USER_CONFIG_KEYS`, so `ConfigCommands.configGet`/`configSet` reject it as an unknown key and `configList` never prints it. The normal user path is `/wv anni <mode>`; `/wv debug tree anni mode set <mode>` also writes it, behind `requireDebug`.
- `VETS_ANNI_MODE_USER_SET` (bool) and `VETS_ANNI_USER_MODE` (string) — set together, and **only** by a `Source.USER_COMMAND` transition. They are what makes an explicit pick survive a stream-forced SILENT and the T+30m window close.

**`AnniMode`** (`mode/AnniMode.java`):
- Enum SILENT / PASSIVE / AGGRESSIVE.
- `fromConfig()` / `fromString(String)` / `toConfigValue()` for round-tripping with `VetsConfig`.

**`AnniModeManager`** (`mode/AnniModeManager.java`) — the single chokepoint.

- `transitionTo(target, Source)` with `Source ∈ {USER_COMMAND, AUTO_WINDOW_CLOSE, AUTO_STREAM_ACTIVATED, AUTO_STREAM_DEACTIVATED, AUTO_STARTUP_DEFAULT, DEBUG_BYPASS_MUTEX}` — **six** constants.
- **The `/stream` mutex applies to PASSIVE/AGGRESSIVE targets only.** `transitionTo` computes `wantsActive = target != SILENT` and consults `Models.StreamerMode.isInStream() || StreamerModeChatDetector.lastSeenInStream()` only when that holds (spec §3.1). A refusal returns `false` and leaves `VETS_ANNI_MODE` untouched — it does **not** force silent. A SILENT target skips the check entirely and always lands.
- Only a `USER_COMMAND` refusal prints "Anni mode change refused: /stream is active. Stream is suboptimal for anni — try /toggle ghosts NONE instead."; the auto sources decline silently with a debug log.
- Likewise on success: all four `AUTO_*` sources suppress the manager's generic "Anni mode: X (was Y)" confirmation, so the caller can print something friendlier. Only `USER_COMMAND` and `DEBUG_BYPASS_MUTEX` print it.
- `DEBUG_BYPASS_MUTEX` skips the stream check for screen-capture testing.
- `preferredMode()` — the user's remembered pick (`VETS_ANNI_USER_MODE`, honoured only while `VETS_ANNI_MODE_USER_SET`), else the eligibility default: PASSIVE when `GuildStateManager.isEligibleForEnrichment()`, SILENT otherwise. Every automatic *restore* target comes from here (see also [vetsmod_config.md](vetsmod_config.md)).
- `applyStartupDefaultIfNeeded()` — called from `GuildStateManager` on entered-world and on guild-info-updated. No-ops once `VETS_ANNI_MODE_USER_SET` is true; otherwise it re-applies the eligibility default in whichever direction it points (SILENT→PASSIVE when eligibility is gained, PASSIVE→SILENT when lost) via `Source.AUTO_STARTUP_DEFAULT`. It computes that default directly rather than calling `preferredMode()`. Net effect: a still-unset vets-tier user is effectively defaulted to `passive` at world-join even though the on-disk default is `silent`.

**`StreamerModeChatDetector`** (`mode/StreamerModeChatDetector.java`):
- Belt-and-braces backup for `Models.StreamerMode.isInStream()`. Hooks `ChatLogMixin` via a one-line `observe(message)` call on the chat HEAD inject.
- Substring patterns on the format-stripped chat line: `"Streamer mode was enabled"` (on) / `"Streamer mode disabled"` (off).
- On the *leading edge* of stream-on (repeat lines are no-ops): sets `lastSeenInStream=true`, and if the current mode isn't already SILENT calls `transitionTo(SILENT, AUTO_STREAM_ACTIVATED)`, printing its own "auto-changed to silent: /stream activated" block only when that returns true.
- On stream-off — and only while `lastSeenInStream` was true — it clears the flag and **does** attempt a restore to `AnniModeManager.preferredMode()` via `Source.AUTO_STREAM_DEACTIVATED`, printing "Anni mode auto-restored to <mode>: /stream deactivated." only when `transitionTo` actually returns true. Nothing is printed when the preferred target is SILENT (`handleStreamOff` returns before `transitionTo` in that case), when it already equals `AnniMode.fromConfig()`, or when `transitionTo` still refuses because Wynntils' own `isInStream()` has not yet cleared.

**`AnniWindowWatcher`** (`mode/AnniWindowWatcher.java`):
- Registered in `VetsmodClient.onInitializeClient()` after `AnniWsHandler.register()`.
- Subscribes to `AnniSnapshotCache`.
- Caches the most recent non-null `stamp_epoch` (since vets-anni emits null once the anni starts).
- On every snapshot update, checks `now > lastKnownStamp + 30 min`; if true it computes `AnniModeManager.preferredMode()` and, only when `AnniMode.fromConfig()` differs, calls `transitionTo(target, Source.AUTO_WINDOW_CLOSE)`. `lastKnownStamp` is cleared regardless of whether a transition fired. Delegating to `preferredMode()` is what preserves an explicit user pick across the window boundary — the target is **not** hard-coded SILENT.
- It writes no config directly; everything goes through `transitionTo`, so when the preferred target is PASSIVE/AGGRESSIVE the `/stream` mutex applies and the reset can be silently declined (debug log only). A SILENT target is never subject to the mutex and always lands.
- One-shot per anni cycle.

## `/wv anni` render dispatch

Entry: `CommandRegistry.anni()` → `StampFetcher.fetchStampAndCreateAnniCommandMessage()` → `AnniCommandRenderer.render(snapshot)`.

`StampFetcher`:
- `vetsAnniEnabled=false` → legacy fallback.
- `vetsAnniEnabled=true && cache populated` → renderer.
- `vetsAnniEnabled=true && cache cold` → `AnniQueryClient.query()` (auto-pull), then renderer if it resolves a snapshot, else legacy. ⚠️ "else legacy" holds for a null resolution, **not** for the 8 s deadline: `query()` completes exceptionally there, and the `thenCompose` in this path propagates rather than falling back — see [`anni-ack-clients-ortimeout-completes-exceptionally`](ephemeral/bugs-found-via-mellow-rain/anni-ack-clients-ortimeout-completes-exceptionally.md).

`AnniCommandRenderer.render(snapshot)` returns `List<MutableComponent>`:
- `null` → "fall back to legacy" (the only case is external + announced).
- One element → single `[VETSMOD]` block.
- Two elements → two blocks, each gets its own full `[VETSMOD]` badge via `sendLocalMessageNewBlock`.

"Announced" is `event.announced() && stampEpoch != null && stampEpoch > now`, and that test runs **before** the external check. A snapshot with `announced=true` and a stamp already in the past therefore renders the *not-announced* form and can never reach the null/legacy path — reachable via the debug `time <seconds>` leaf, and in the real gap between an anni starting and vets-anni nulling `stamp_epoch` on the following frame.

There is a fourth terminal state neither branch produces. `StampFetcher.legacyFallback` resolves `null` when `fetchSimple()` yields nothing (HTTP or parse failure) while `AnniStampPoller.getLatestStamp()` still holds a non-zero future stamp; `CommandRegistry.anni` then prints a yellow `Annihilation timer is currently unavailable.`. `StampFetcher`'s own class Javadoc claims the manual invocation never returns null, which is wrong. The `isEmpty()` half of that guard is defensive only — no producer returns an empty list.

Three branches:
- **Not announced** — header (or red headline for external) + prediction line + (if vets) the not-announced registration block. No board section here (no anni to be placed in).
- **Far-out (T-2h+)** — header (link + "returns in Xh Ym (HH:MM, day d)") + Assigned Role *or* Eligible Roles + RSVP Type + Attendance Chance + Party Assignment + Host. Countdown resolution scales with magnitude (`AnniCommandRenderer.appendCountdown`): `≥ 1h → Xh Ym`, `< 1h ≥ 1m → Xm Ys`, `< 1m → Ys` — sub-hour readouts surface seconds. `AnniMotdRenderer.formatHours` uses a **different** ladder: one-decimal hours (`7.2h`) for anything ≥ 0.1 h (6 min), then `Xm YYs`, then `Ys`. It never emits `Xh Ym`; only the sub-6-minute tail coincides.
- **Imminent (at or within 2h)** — the far-out test is `secondsUntil > TWO_HOURS_SECONDS`, so exactly 2h takes this branch. Same body as far-out + a second block with `Change Anni Mode? (Click:) / [Silent] | [Passive] | [Aggressive]`.

### Not-announced registration block

Three sub-shapes:
- **Unregistered** → `unregisteredNudgeBlock` — red bold "You have not yet opened anni.wynnvets.org/me!" + dark-red italic followup.
- **Registered, no specific roles** → `fillOnlyExplainerBlock` — four lines (yellow bold italic / gold italic / 0x7E7E7E italic / white bold italic) with clickable "fill slot" and "here" links.
- **Registered with roles** → an `Eligible Roles:` gray label, role chips joined by a dark-gray ` · `, then a dark-green italic "You're all set for the next #Annihilation" (where #Annihilation links to the Discord channel in dark aqua). The label is always `Eligible Roles` here — the `Assigned Role` swap belongs to `renderFarOut`, and the not-announced path never consults `snapshot.board()`.

### `isExternal(snapshot)` — vets-tier detection

Calls `GuildStateManager.isEligibleForEnrichment()` and negates it. That helper implements the same three trip-wires the bridge gate uses — the set nazbot's `!enable unauth` lets through:
- `GuildStateManager.isReturners()`, OR
- `GuildStateManager.isGuildless() && isWaitlistUnlocked()`, OR
- `GuildStateManager.isHonouraryUnlocked()`.

`OutboundDisplayHandler.shouldDisplayMessages` is a *private duplicate* of that predicate, not the code `isExternal` calls. Same semantics today; two copies to keep in step.

Not the dazebot auth tier. Reason: gating the registration nudge on `~vetsmod` Discord linking would skip exactly the users we want to catch.

`isExternal` also ignores its `AnniSnapshot` parameter entirely — the answer is whole-client state, not per-snapshot.

The `externalOverride` field (settable via `/wv debug tree anni external <auto|true|false>`) bypasses this when set, as does the preset-name auto-set. Preset names are matched with `startsWith("external")` / `startsWith("member")` — no underscore needed — and any *other* preset name resets the override to auto rather than leaving it alone.

### Prediction line

`Prediction: <countdown> from now|overdue | <MMM d> @<HH:mm> ±~<σ>h` — gold label, yellow numerics, white-bold-pipe separator, gray italic uncertainty. The countdown comes from `formatCoarseHours` (hours only above 1h) and the qualifier flips from `from now` to `overdue` past the median; the uncertainty comes from `formatUncertainty`.

`σ = window_hours / √12` (~3.06h on the standard 10.6h Uniform window). NOT half-window — q0/q4 are misleading tails and ± in chat reads as standard deviation.

Gated by `vetsAnniShowPrediction`. When that is on but `event.prediction()` is null — or any of `earliestEpoch()` / `medianEpoch()` / `latestEpoch()` is — `renderNotAnnounced` substitutes a gray `Prediction window unavailable — no recent anchor.` instead.

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

**Policy and mechanism differ here, so keep them apart.** The policy — short codes (HRSVP / SUNK / MOBK) belong on the boss bar, and chat callers spell things out — stands, and is recorded as the `feedback_anni_short_codes_reserved_for_bossbar.md` memory. The mechanism is not what "reserved" implies: `short` **is** one of the three configured `vetsAnniRoleStyle` values, so a user can select it for chat, and `displayRole`'s only callers are chat surfaces (`AnniHoverBuilder.roleChip`, `AnniCommandRenderer.registrationSection`, `AnniMotdRenderer.assignedToPartyLine`). The bar does not read this table at all — it uses its own hardcoded `VetsBossBarContentBuilder.toShortLabel`.

### RSVP / attendance / board labels

`AnniHoverBuilder.rsvpBadge` resolves its key from `rsvp.notice()` — but only while `rsvp != null && notice != null && !rsvp.revoked()` — otherwise from `attendance.noticeEffective()`. Long forms in chat: `HARD RSVP` / `SOFT RSVP` / `EARLY WALK-IN` / `LATE WALK-IN`, plus a gray **`NO RSVP`** when neither source yields a key, and an upper-cased passthrough of any unrecognised key. Short forms (HRSVP etc.) are the boss bar's, emitted by `VetsBossBarContentBuilder.rsvpChip`.

`Attendance Chance` is state-dependent:
- party → "ASSIGNED" (light purple bold).
- wont_assign → "COULD NOT ASSIGN" (red bold) — hardcoded in `attendanceSection`, so unlike the board line it does **not** follow `wont_reason`.
- unassigned → `AnniHoverBuilder.attendanceBar`, a 6-cell clamped band bar + label, or the gray `attendance: unknown` when `snapshot.attendance()` is null.
- everything else, including a null `board` or `board.state()` → `attendanceSection` returns null and the line is dropped.

In the announced branches only, `Eligible Roles` becomes `Assigned Role` when `board.state()` is `party` (and the duplicate `Role:` in the party block is dropped). `assignedRoleSection` reads `board.role()`, falls back to scanning the party member list for the local player, and prints a gray `TBD` when neither yields one.

The board line is labelled `Party Assignment` for `unplaced` / `unassigned` / `wont_assign` / unknown states and for a party with no details attached. Once a party really is attached, `partyAssignedBlock` switches to `Party: N  World: W`, plus a `Host:` line when `party.host().username()` is non-null.

`AnniHoverBuilder.noticeColor` is the intended RSVP colour map for chat surfaces — `AnniRsvpCommand.successComponent` calls it — and it is the one to call from chat-output code, not just from `/wv anni` renderer code. Saved as the `feedback_anni_rsvp_colours.md` memory: call the helper, don't inline `ChatFormatting.AQUA`/`GREEN` literals. It is **not yet universal**: `VetsBossBarContentBuilder.rsvpChip` still inlines its own map and disagrees (hard → BLUE, not AQUA). Whether that divergence is one of the parent plan's three deliberately-divergent boss-bar colour tables is unresolved — see the parent plan's Phase 5 item 6.

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

Line 1 is red+bold, time via `AnniMotdRenderer.formatHours`.

Line 2 is gray prose, and the two blocks above are examples, not the whole set — `buildStatusLine` switches on `board.state()` four ways:

| `board.state()` | Line 2 |
|--|--|
| `party` | "You have been assigned to <ROLE> with Party <n>[ on <world>]" — bold-coloured callouts. Degrades to "You have been assigned to a party (details pending)." when `board.party()` is null, and the " on <world>" tail appears only when `party.world()` is non-null and non-empty |
| `unassigned` | "You aren't yet assigned and <rsvpClause>!" |
| `wont_assign` | "You won't be assigned — <wontReason>." / "You won't be assigned to this anni." — no callouts, no RSVP clause |
| `unplaced` | "You're not yet on the placement queue and <rsvpClause>!" |

`rsvpClause` yields six strings — hard / soft / walking-in-early / walking-in-late / "have not RSVP'd" (no notice key) / "have an unrecognised RSVP" (unknown key) — keyed off `snapshot.rsvp().notice()` unless `revoked()`, in which case off `attendance.noticeEffective()`.

External users get `null` from `AnniMotdRenderer.render` → fall through to legacy stamp text (spec §"For external users").

Line 2 is dropped two ways: `vetsAnniPromptRsvp=false` suppresses it outright, and `buildStatusLine` returns null — so nothing is appended — when `board` or `board.state()` is null, or the state is none of the four above.

## Boss bar

The boss-bar manager uses a render-side filter (priority-500 `@Redirect` on `events.values()` inside `BossHealthOverlay#render`). *Note: this intentionally does NOT cancel `BossHealthOverlay#update` — vanilla's update handlers do `events.get(uuid).setName(...)`, and cancelling at update would NPE the next UpdateProgress / UpdateName / UpdateStyle packet for that UUID, disconnecting the client.*

Let vanilla and Wynntils track bars normally (no `update()` cancellation, no `events.clear()`). The priority-500 `BossHealthOverlayMixin` `@Redirect`s the `events.values()` call inside `BossHealthOverlay#render(GuiGraphics)` — while active, it returns just our UUID's entry (or an empty collection if our entry isn't there yet); when inactive, it returns the unmodified collection. Wynntils' overlays render through their own paths and aren't affected. `Models.StreamerMode.isInStream()` works without the let-through hack (Wynntils tracks the streamer-mode bar normally).

**`VetsBossBarManager`** (`bossbar/VetsBossBarManager.java`):
- Owns a deterministic `UUID.nameUUIDFromBytes("vetsmod-anni-bossbar".getBytes())` plus a single synthetic `LerpingBossEvent`. Exposes `static UUID barUuid()` for the render mixin.
- `activate()` calls `events.put(uuid, ours)` — does NOT clear pre-existing entries (the render filter handles "only-our-bar-visible"). It is called on **every** build-success tick, not once on the rising edge: the `events` map is not ours to trust across a world transfer or a reconnect, and while `isActive()` holds with our UUID missing, `BossHealthOverlayMixin`'s `@Redirect` returns an empty collection and hides *every* bar. The `active = true` / `activatedAtMs = …` assignments sit behind an `if (!active)` guard so the re-puts don't keep bumping the failsafe clock. `deactivate()` does `events.remove(uuid)`, clears both fields, and calls `FlashTracker.reset()` — it is that method's only caller.
- Per-tick driver (`ClientTickEvents.END_CLIENT_TICK`):
  1. Mode gate — SILENT → deactivate, return.
  2. `vetsAnniBossbarEnabled` kill-switch.
  3. Snapshot + `stamp_epoch` presence.
  4. Hard wall-clock T-20s watchdog (`DROP_DEAD_SECONDS_BEFORE_ANNI`) — independent of the content builder.
  5. 2.5h `FAILSAFE_DEACTIVATE_MS` deactivate, measured from `activatedAtMs`.
  6. **Activation gate**: show iff `secondsUntilAnni ≤ 90 m` OR the player is in the anni zone. Outside both, even passive/aggressive stays quiet — no perpetual bar hours before anni.
  7. Build content; null → deactivate; else `activate()` + `setName` + `setProgress(progressFor(...))` + `setColor(VetsBossBarContentBuilder.colorFor(snapshot))` — four calls, and the colour is therefore re-derived from the live snapshot every tick.

  **Steps 1–6 gate the bar, not the pings.** All of them are early returns from `tickInner`, and `tick()` is `try { tickInner(); FlashTracker.tick(); } catch …` — so `FlashTracker.tick()` still runs, and `FlashTracker`'s `AnniSnapshotCache` listener is not mode-gated either. With the bar down (SILENT, or `vetsAnniBossbarEnabled` off) snapshot role/party/RSVP/world transitions still queue and play the 2× ping; `vetsAnniFlashSound=false` is the only config that stops the queueing. One caveat: `deactivate()` → `FlashTracker.reset()` zeroes `pendingSounds` and the `*Observed` sentinels, so the first snapshot after the bar drops is absorbed silently as a new baseline and pings resume from the next change. A throwing tick also skips that tick's `FlashTracker.tick()`.

**`VetsBossBarContentBuilder`** (`bossbar/VetsBossBarContentBuilder.java`):
- `(AnniSnapshot, secondsUntilAnni, playerX, playerZ) → MutableComponent | null`. **Not pure**, despite doing no I/O: `build` reads `AnniZone`'s volatile disc cache through `isInZone`, and every chip goes through `FlashTracker.styleFor`, which consults time-bounded flash windows and a 250 ms pulse bit. The same four arguments demonstrably yield different Components 250 ms apart.
- Returns `null` at `≤20s` (`T_MINUS_20_GATE_SECONDS` — manager deactivates).
- In-zone (`AnniZone.isInZone`) and inside `COUNTDOWN_THRESHOLD_SECONDS`, which is **5 min**, not the spec's 2 ("spec called for 2 min, user-iterated to 5 min on 2026-06-16") → the countdown variant, `anni.wynnvets.org: ANNI IN <NNN>s | …` (spec §3.1.1.3). The trailing half has two branches: `scrollSecs = max(0, anniSecs - 20)`, and the literal `SCROLLS IN <NNN>s!` is emitted only while `scrollSecs ≤ SCROLL_COUNTDOWN_START_SECONDS` (70); above that it renders an italic light-purple `Get ready for scrolls!` instead. So `ANNI IN 099s | SCROLLS IN 079s` — the string the old spec sample showed — is unproducible: 99 s implies `scrollSecs = 79 > 70`.
- Assigned (party OR committed slot) → `anni.wynnvets.org: Role: <SHORT> | Party <n> | World: <w>` (spec §3.1.1.2). `buildAssigned` deliberately drops the `/me` path the spec writes — on a ~40-char bar every glyph counts. Role colours: TANK=blue, HEAL=green, PRIM=red, SUNK=yellow, MOBK=light-purple, FILL=dark-aqua. Note `isAssigned` can be satisfied by a non-FILL entry in `registration.roles` alone while `resolveRoleCode` reads only `board.role()`, so this variant is reachable showing a gray `Role: TBD`.
- Seeking (no party + no committed slot) → `Seeking a <HRSVP|SRSVP|WALKIN|LATE> <CORE|FILL> SLOT for anni.wynnvets.org in <X>min` (spec §3.1.1.1). Slot type derived from `registration.roles` (specific role = CORE).
- **Four** chips wrap via `FlashTracker.styleFor(fieldKey, base)` — `role` / `party` / `world` in the assigned variant and `rsvp` in the seeking variant — so the bold↔underline pulse applies per-field. The slot chip, the link badge, the minutes chip and the whole countdown variant are unwrapped and never pulse.

**Bar colour: never PINK.** The signature is `colorFor(AnniSnapshot)`, not `colorFor(String)`: it returns `PURPLE` when `board.state()` is `party` — not PINK as the original spec suggested — `RED` for `wont_assign`, and otherwise quantises `attendance.band()` through `bandToBarColor` (≤2 RED, ≤4 YELLOW, else GREEN), falling back to `PURPLE` with no snapshot or no band. Wynncraft's forced resource pack overrides `boss_bar/pink_background.png` (and its progress sprite) to be fully transparent so they can repurpose pink-bar slots as text-only HUD strips (the Lv.92 Returners XP line, territory/region names like "Corrupted Road", "ROOTS OF CORRUPTION"). Reproducible on bare vanilla MC 1.21.11 with the Wynncraft pack loaded (empirically confirmed against Wynncraft's forced resource pack). Confirmed via `/wv debug trigger bossBarsDump` — every Wynncraft text-only bar shipped `color=PINK overlay=PROGRESS`. **Future colour additions: pick from `{PURPLE, RED, GREEN, YELLOW, BLUE, WHITE}` only.**

**Overlay style: NOTCHED_10 with a 100-min progress window.** Per-user request — segment dividers every 10 minutes for readability. Vanilla only ships `NOTCHED_6/10/12/20` (no `NOTCHED_9`). `PROGRESS_FULL_AT_SECONDS` decoupled from `ANNI_WINDOW_SECONDS` (still 90 min activation gate) and stretched to 100 min so each of 10 segments = exactly 10 minutes of wall-clock time. At T-90m activation the bar reads ~90% = 9 of 10 segments filled, matching the "90 mins = 9 notches" mapping. Drains one segment per 10 min through T-20s.

**`FlashTracker`** (`bossbar/FlashTracker.java`):
- Subscribes to `AnniSnapshotCache`. Two flash models:
  - **Role / party / RSVP**: timed-window flash. On a snapshot diff against last-seen, marks the field "flashing" for `vetsAnniFlashIntensity` ms (`subtle=5000`, `normal=10000` default, `strong=20000`). First observation skipped via `roleObserved` / `partyObserved` / `rsvpObserved` sentinels — login with existing state doesn't bing on every reconnect, but **null → set** transitions during the session DO flash + ping.
  - **World**: state-driven, never latched. `tick()` recomputes `worldMismatch = partyWorld != null && (currentWorld == null || !currentWorld.equalsIgnoreCase(partyWorld))` (current world read via `Models.WorldState.getCurrentWorldName()`) — the comparison is case-insensitive, and an unknown current world counts as a mismatch; `styleFor("world", base)` consults that live flag. **Position movement** silently toggles the flash — walk onto the assigned world → flash stops, walk off → flash resumes — but **never** triggers a ping. **Pings** fire only when the snapshot's `party.world` *value* changes (assignment / reassignment / unassignment), wired through the same `applyDiff` path with a `worldObserved` sentinel that skips the first-observation case.
- Pulse half-period: 250ms fixed (toggled in `tick()`, driven by `VetsBossBarManager.tick()`).
- `styleFor(fieldKey, baseStyle)` applies `.withUnderlined(true)` to `baseStyle` iff the field is flashing AND the phase bit is high — produces the spec's "`&l ↔ &n&l`" alternation.
- Sound: `SOUNDS_PER_CHANGE` = 2× `EXPERIENCE_ORB_PICKUP`, drained one per tick by `FlashTracker.tick()` at `SOUND_INTER_DELAY_MS` = 110 ms. An "everything-changed-at-once" snapshot still produces a single 2-ping burst, because `applyDiff` ORs all four field diffs into one queueing decision and `SOUNDS_CAP` bounds the queue.
- Bounces snapshot diffs off the WS reader thread via `Minecraft.getInstance().execute(...)`.
- Debug entry: `forceFlash(fieldKey)` for `/wv debug tree anni flash <field>`.

**Two T-20s deactivation gates** (parent plan §"Risks"):
1. `VetsBossBarContentBuilder.build` returns null at `≤20s`.
2. Wall-clock watchdog (`DROP_DEAD_SECONDS_BEFORE_ANNI`) in `VetsBossBarManager.tickInner` — independent of builder state. Re-checks every client tick (~50 ms), so it also covers the kick-into-queue case at T-20 without needing a separate world-change hook.

Two, not three. The source's own "Gate n of 3" comments count `AnniWindowWatcher`'s T+30m **mode** reset as the third, and that is not a bar gate. There is also a separate 2.5 h `FAILSAFE_DEACTIVATE_MS` watchdog, which is not a T-20s gate either.

Plus the slower net: at T+30m `AnniWindowWatcher` restores `AnniModeManager.preferredMode()` — silent only in the never-set-a-mode, not-enrichment-eligible sub-case.

## Player highlights

The highlight overlay recolours nearby players' outlines + nametags while the user is at the anni in the active window. Three tiers, single ticker.

### Snapshot schema bump (v1 → v2)

The `event.all_parties` half of the highlight tiering needs `schema_version = 2` from vets-anni. Nothing client-side *enforces* it — `schemaVersion()` is read only by `AnniDebugCommands` diagnostics, so a v1 payload degrades silently to own-party-only tiering rather than being rejected. The single addition is `event.all_parties`: a lightweight per-party member listing keyed off the active event. Each entry is `{ordinal, members: [{uuid, username, role}, ...]}` — same `_party_member_refs` projection as `board.party.members`, so the two views never drift. Used by `AnniOutlineRegistry` to tier "in another vets-anni party" players without per-uuid round-trips. Empty list when no parties exist yet for the active event. `AnniSnapshot.Event#allParties()` returns `Collections.emptyList()` on v1 payloads (Gson leaves unknown fields null, accessor coerces), so older snapshots remain consumable.

### Activation gate — tighter than the boss bar before the stamp, wider after it

| Subsystem | Gate |
|--|--|
| Boss bar | `mode != SILENT` AND `vetsAnniBossbarEnabled` AND a snapshot with `stamp_epoch` AND `secondsUntilAnni > 20s` AND ( `secondsUntilAnni ≤ 90m` **OR** `in zone` ) AND the content builder returned non-null — with the 2.5 h stuck-snapshot failsafe on top |
| Highlights | `mode != SILENT` AND `in window (T-2h..T+30m)` **AND** `in zone` AND ( `vetsAnniOutlinesEnabled` OR `vetsAnniNametagsEnabled` ) |

Neither gate contains the other. Before the stamp the highlight gate is the tighter one — it adds the zone requirement the bar treats as an alternative. After it, containment breaks in both directions: across the whole T-20s → T+30m stretch the bar is hard off (`DROP_DEAD_SECONDS_BEFORE_ANNI`) while in-zone highlights stay on, and `vetsAnniBossbarEnabled=false` kills the bar without touching highlights.

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

`isAnyActive()` for quick "anything to do" checks; `getEntry(String username)` for the consumer lookups. Concurrent hashmap; reads from main / render threads, writes from the WS reader thread.

Beside those, a `// ── Debug API ──` block that `AnniDebugCommands` drives and no other caller touches: `size()`, `debugSet(String, Entry)`, `debugRemove(String)`, `debugOwnPartyEntry(String role)` and `debugOtherPartyEntry()` back the `/wv debug tree anni registry set|clear|clearall` leaves. `clearAll()` belongs to that group too — it has exactly one caller, `AnniDebugCommands.registryClearAll`, and no test references the class at all. (The rebuild path does not need it: `rebuildFrom` does its own inline `entries.clear(); entries.putAll(next)`.)

### `AnniOutlineTicker` (`outline/AnniOutlineTicker.java`)

Subscribes `ClientTickEvents.END_CLIENT_TICK` once at init. Each tick:

1. Evaluate the four-condition gate (mode, window, zone, at-least-one-toggle).
2. If the gate fails and `suppressionActive` was true → clear every glow we previously applied (walk `level.players()`, set `setGlowColor(NONE)` for tracked usernames), reset the flag, return.
3. If the gate holds → walk `level.players()`:
   - Skip the local player.
   - Registry hit → `setGlowColor(entry.outlineColor())`, applied regardless of `vetsAnniOutlinesEnabled` (the gate already passed on the OR of the two toggles; this call reads neither).
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

2. **`ChatFormatting.stripFormatting` is mandatory.** Wynncraft embeds the team colour as a legacy `§<code>` prefix INSIDE the nametag string content — `state.nameTag.getString()` returns `"§awonderkas"` for a friend-team-coloured player, not `"wonderkas"`. Without the strip, `Component.literal("§awonderkas").withStyle(RED)` renders GREEN because vanilla's text renderer parses the leading `§a` at draw time and silently overrides the Style. Without this strip every recoloured nametag silently reverts to the team colour. ⚠️ The `original=` column this was supposedly confirmed against **does not exist** — see [`nametags-dump-missing-original-column`](ephemeral/bugs-found-via-mellow-rain/nametags-dump-missing-original-column.md).

Falls through to the supporter glint branch only when the anni gate is off; an own-party supporter inside the gate shows their role colour for the duration and the glint resumes after the gate closes.

## Aggressive mode

### Architecture

Five client-side components plus three wire pieces (V1ApiManager send,
AnniWsHandler ack route, AnniScrollspotClient single-flight future) and
one vets-anni internal endpoint.

| Component | Gate | Notes |
|---|---|---|
| `AnniAggressiveTicker` | computed each tick | Single cheap public `isAggressiveActive()`; every other aggressive-mode component reads it. |
| `AggressiveAlertDispatcher` | aggressive AND `vetsAnniChatAlerts` | Per-field 5s cooldown, silent first-observation (mirror FlashTracker). The gate governs *emission* only — the snapshot listener keeps running while gated off, to maintain last-seen state and reset the per-stamp sentinels. `forceAlert` (debug) bypasses both gate and cooldown. |
| `AnniZoneLineRenderer` | aggressive AND `vetsAnniZoneLines` | `WorldRenderEvents.AFTER_ENTITIES`. Stacked `Gizmos.circle` cylinder cage — 21 rings per disc spaced `Y_STEP=10` blocks (±100 vertical), Y snapped to multiples of 10 so rings don't jitter as the player walks. 200-block horizontal (X/Z only) squared-distance cull to the disc *centre*, so with the 48-block `DISC_RADIUS` a ring's near edge vanishes at roughly 152 blocks. |
| `ScrollSpotMarkerProvider` | aggressive AND `vetsAnniScrollWaypoint` AND non-null entry | Registered once with `Models.Marker.registerMarkerProvider`, deferred to CLIENT_STARTED so it runs after Wynntils' own init. Default fallback `345 45 -1315` when the user is in a party with no host-pinned spot. Dark-red beacon (icon-only was infeasible — see decision #5 below). |
| `GhostsPromptHandler` | aggressive AND `vetsAnniGhostsPrompt` | Rising edge of `AnniZone.isInZone`. Walks `level.players()` + `Models.Player.isPlayerGhost`: any ghost-flagged player → prompt (ghosts confirmed on); else per-stamp_epoch sentinel. |

### Locked decisions

1. **Aggressive gate = `mode == AGGRESSIVE ∧ window` only.** No zone gate. Per user: aggressive features are window-scoped, not location-scoped. Zone lines render whenever you're aggressive + in-window rather than only in the zone. They still are not visible from Lutho, though — `AnniZoneLineRenderer` culls any disc centre more than 200 blocks away horizontally, so they appear on final approach.
2. **Two readiness alerts** appended to `AggressiveAlertDispatcher`. The T-10m world-mismatch fires on the first tick at or after T-10m *at which a party world is assigned*, so an unassigned player gets it late or not at all; the T-5m zone-absence latches unconditionally on the first tick inside T-5m. Neither fires past `stamp_epoch`. Each latches an **in-memory** per-stamp_epoch sentinel — once per anni *per client session*. Unlike the ghosts prompt these are not persisted, so a restart mid-window re-fires them.
3. **Ghosts-prompt detection** = `Models.Player.isPlayerGhost(player)` walk. Reads `PlayerModel.ghosts` (the cache Wynntils maintains from `_ghostN` team assignments). If any visible player is ghost → ghosts on → prompt. Else ambiguous → per-stamp_epoch sentinel (`vetsAnniGhostsPromptShownForStamp`).
4. **Chat-alert scope = exactly role / world / party / RSVP.** No attendance-band, no party-membership.
5. **Scroll waypoint = `Texture.MAP` + dark-red beacon.** `Texture.MAP` (the 14×14 generic, NOT `Texture.MAP_ICON` which is the 21×38 content-book tab — enum naming is a known footgun). *Note: this intentionally does NOT use "no beacon beam, icon only" — Wynntils' `BeaconBeamFeature.onRenderLevelLast` NPE-crashes the render thread on a null beacon colour, and there is no per-marker "skip beacon" path: `null` crashes, `CustomColor.NONE` falls back to user-config beacon, a custom 0-alpha colour is ignored, since Wynntils recomputes the alpha itself. Suppressing the beacon would require a mixin into Wynntils — see [vetsmod_rendering.md](vetsmod_rendering.md) §6 for the mechanism.* Final: `CustomColor.fromChatFormatting(ChatFormatting.DARK_RED)`.
6. **Scrollspot command lives under `/wv debug tree anni scrollspot`, not `/wv anni`.** Hidden from main brigadier (no tab-complete) because it's a staff-only command used on rare occasions. Gated on `requireDebug` (must have `/wv debug true`) AND `requireStaffOrOrganiser` (staff tier OR local UUID in `snapshot.organisers`). Subcommands: `set <x> <y> <z>` / `here` / `clear` are real host writes via WS; `localinject <x> <y> <z>` / `localclear` paint the marker provider directly for visual testing without coordinating a host. All five gated identically.

### Wire shape

Snapshot `schema_version: 3` adds `board.party.scroll_spot: {x,y,z}|null`, read client-side as `AnniSnapshot.Party.scrollSpot()`, which returns a nullable `AnniSnapshot.ScrollSpot` — a Gson-populated static nested class, not a record. It is present only on the local player's own party: `PartySummary` declares no `scroll_spot`, so `event.all_parties` genuinely cannot carry one.

The server halves — the `Party` columns and their migration, the `POST /api/internal/anni-party-scrollspot` host check, and temp-server's forwarding handler — live in [`vets-anni/.claude/snapshot_integration.md`](../../vets-anni/.claude/snapshot_integration.md), which states them more precisely. The three vetsmod-side wire pieces (`V1ApiManager.sendAnniScrollspotSet`, the `AnniWsHandler.onInbound` route, `AnniScrollspotClient`) are described once under §Party back-report → Reference templates.

## `/wv anni rsvp`

### Architecture

Backs the `[Hard]`/`[Soft]` upgrade-prompt buttons in `AnniCommandRenderer` (their `SuggestCommand("/wv anni rsvp hard|soft")` targets this command). One brigadier command + a network client + a single vets-anni endpoint; no schema bump (`rsvp.notice` is already on the snapshot at v2/v3).

Wire pieces on the network layer:
- `V1ApiManager.sendAnniRsvp(String notice)` (mirror of `sendAnniScrollspotSet`).
- `AnniWsHandler.onInbound` routes `anni_rsvp_response` to `AnniRsvpClient.onResponse`.

`AnniRsvpClient` is a single-flight ack future (clone of `AnniScrollspotClient`). It declares public `lastAttemptedNotice()` / `lastAck()` / `pendingCount()` accessors intended for `/wv debug trigger rsvpDump`, but the dump goes through `debugDump()` instead, which reads the private statics directly — so all three currently have zero callers repo-wide.

### Auth chain

```
/wv anni rsvp hard
  → AnniRsvpCommand.hard (GuildStateManager.isAuthenticatedThisSession() gate)
  → AnniRsvpClient.send("hard")
  → V1ApiManager.sendAnniRsvp("hard")  → {"type":"anni_rsvp","notice":"hard"}
  → temp-server _handle_anni_rsvp (stamps actor_mc_uuid from session)
  → POST vets-anni /api/internal/anni-rsvp-by-uuid   ← server side; see
                                                       snapshot_integration.md
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
3. **No `username_hint` field threaded through the WS frame.**
   Temp-server already has the session's `mc_uuid`, and vets-anni's own
   placeholder fallback covers brand-new users — no need for a fragile
   client-side username hint that would also need an "is this username
   actually yours" verification.
4. **`AnniRsvpClient.lastAttemptedNotice` / `lastAck` are race-prone**
   (static volatiles, no per-call correlation) — acceptable for a
   debug-only `rsvpDump` view. `pendingCount()` was added as the honest
   in-flight indicator; as built, `debugDump()` reads the statics
   directly and none of the three accessors is called.
5. **Auto-refresh on success.** `AnniRsvpCommand.renderAck` fires
   `AnniQueryClient.query()` after a successful ack. Without this,
   `/wv anni` reads the cached snapshot which is up to 5 minutes stale
   outside the T-2h hot window (push poller cadence). User reported the
   exact symptom: post-`/wv anni rsvp soft`, the next `/wv anni` still
   showed `RSVP Type: EARLY WALK-IN`. The query is fire-and-forget; the
   listener bus in `AnniSnapshotCache` re-renders everything that
   subscribes (boss bar, outlines, flash, future `/wv anni`).

The server-side decisions this flow depends on — how vets-anni reuses
the Discord cog's helpers, the `uuid[:8]` placeholder for a first-time
`AnniPlayer`, the T-90 cutoff's 409, the WONTASSIGN promotion on
re-RSVP, the `wont_reason = "RSVP retracted"` string this doc's renderer
consumes, temp-server's short-cache pop, and the public-message format —
are in [`vets-anni/.claude/snapshot_integration.md`](../../vets-anni/.claude/snapshot_integration.md).

## Party back-report

`PartyRosterListener` uses a direct organiser-presence gate (the legacy
`party_status` frame is removed end-to-end across vetsmod / temp-server
/ vets-anni). Anni parties only exist inside the active window, and an
anni party's host is always in `organisers`, so the broader year-round
signal would be over-collection and the organiser-presence gate is the
exact signal vets-anni needs.

The snapshot field the gate reads (`organiser_usernames`, parallel to
`organisers`), vets-anni's `POST /api/internal/anni-party-observation`
receiver and its presence TTL, and temp-server's forwarding handler are
in [`vets-anni/.claude/snapshot_integration.md`](../../vets-anni/.claude/snapshot_integration.md).
Names rather than UUIDs go over the wire because Wynncraft only exposes
party members by username — see `Wynntils PartyModel.getPartyMembers()`.

### What ships

1. **vetsmod gate refactor** —
   [`PartyRosterListener.flush`](../src/client/java/org/wynnvets/listeners/PartyRosterListener.java)
   now reads `AnniSnapshotCache.latest().organiserUsernames()` and
   tests case-insensitive overlap with the captured party's
   `leader + members`. Calls
   `V1ApiManager.sendAnniPartyObservation(members, leader, world)`.
   Pure predicate `shouldSend(snap, anniSnapshot, stamp, now)` is the
   testable core; legacy `vetsConnected` / `tier` parameters are gone.

2. **Mid-window snapshot trigger** —
   [`AnniPartyReporter`](../src/client/java/org/wynnvets/mwe/anni/party/AnniPartyReporter.java)
   subscribes to `AnniSnapshotCache`. On any change to the lowercased
   `organiser_usernames` set, calls
   `PartyRosterListener.requestRecapture()`. Handles "anni opens while
   parked in a static party for 30 min" — no `PartyEvent` would fire
   on its own. Registered from `VetsmodClient.onClientStarted`
   (CLIENT_STARTED), never from `onInitializeClient` per
   `feedback_vetsmod_wynntils_init_order.md`.

### What got deleted

The legacy `party_status` machinery is excised in full. On the vetsmod
side that is `V1ApiManager.sendPartyStatus`, the cohort-gate parameters,
the `OnlineMemberService.refreshAsync()` call site, the `VETS_TIERS`
constant, and the TODO comment that envisioned this exact swap. The
temp-server and vets-anni halves are listed in
[`vets-anni/.claude/snapshot_integration.md`](../../vets-anni/.claude/snapshot_integration.md).

### Implementation notes

- **Wire format is `{observer_mc_uuid, party_member_usernames: [str],
  leader_username, world}` — names over the wire, vets-anni does the
  resolution.** *Note: this intentionally does NOT use the
  `[{uuid, username}]` shape — Wynntils' `PartyModel.getPartyMembers()`
  returns `List<String>` of usernames only, and Wynncraft's tab list is
  fake (80 sentinels per `TabListGuildParser`), so client-side
  name→UUID resolution would drop 30-60% of members.* The client never
  supplies `observer_mc_uuid`; temp-server stamps it from the session.
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

Five clean reference templates exist for the wire shapes this subsystem
uses — three vetsmod-side, two in temp-server. Don't redesign — clone.

**Auth + forward template (temp-server `chat/inbound.py`)** — four
near-identical handlers: `_handle_anni_query` (read path; session-or-frame
uuid resolution, and the only one open to unauthenticated sessions),
`_handle_anni_scrollspot_set` (host-only write; session uuid only),
`_handle_anni_rsvp` (self-only write; session uuid, and pops the user's
snapshot from the cache so the follow-up `anni_query` bypasses the 15 s
short-cache), and `_handle_anni_party_observation` (stamps the actor's UUID
into the body so a client can't impersonate). Each returns its own typed
`*_response` rather than the generic ack.

⚠️ The old form of this template advised extracting a
`_handle_anni_authenticated_forward` helper "if the count grows to 5". That
advice has effectively been answered elsewhere and should not be followed
literally: the **staff-action** family did grow to five, and the shape it
settled on is `_staff_session_or_error` — a single gate returning
`(session, error_payload)`, called as the first statement of every handler.
Clone that precedent rather than inventing a new forward helper.

The closest literal "auth-and-forward" handler today is not in `inbound.py`
at all — it is `routes/anni_delta.py`'s `anni_snapshot_delta` with
`_check_secret`, which is worth reading for two decisions: it **fails closed**
(unset secret is a 503, not an open door) and it returns a 200 `noop` rather
than a 5xx when the poller is absent, with the secret check ordered first so
the noop can't be used to probe.

**Poller helper template (temp-server `services/anni_snapshot_poller.py`)** —
three request-path clones: `set_scroll_spot(body)`, `set_rsvp(body)` and
`send_party_observation(body)`. (The last is `send_`, not `report_` — the
previous version of this template had the name wrong.) All three reuse the
poller's own `_client` / `_secret` / `_base_url` and return
`{"status": "ok"}` or `{"status": "error", "detail": …}`.

The reason this is a *template* rather than a base class is that **there is no
base class**: all six of temp-server's pollers are plain classes repeating the
same hand-written run loop, and `GlintedPoller` is the instance the later ones
name in their docstrings as the thing they mirror. See
[server_services.md](server_services.md) §10.

**Single-flight ack client (vetsmod `org.wynnvets.mwe.anni.network`)** —
three clones: `AnniQueryClient` (returns `CompletableFuture<AnniSnapshot>`),
`AnniScrollspotClient` (returns `CompletableFuture<Ack>`),
`AnniRsvpClient` (returns `CompletableFuture<Ack>`). FIFO
`ConcurrentLinkedDeque`; the `orTimeout` deadline is **5 s on the two
ack clients and 8 s on `AnniQueryClient`**, not 5 s across the board.
The `exceptionally` handler removes *that* future by identity
(`remove(future)`) — **not** the head. Head-polling (`pollFirst()`)
belongs to the response path and to `AnniQueryClient.drainPending()`;
cloning "removes the head" into a timeout handler would drop an
unrelated in-flight future. The party-observation report is fire-and-forget (no
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

- **`AnniPartyReporter`'s snapshot-listener pattern** generalises to
  any future MWE that needs "fire a back-report when X organiser-like
  state appears". Don't duplicate the listener bus; subscribe and
  diff.

## Anni zone

`AnniZone` (`zone/AnniZone.java`) — JSON fetcher for `https://api.wynncraft.com/v3/map/world-events`, looks up event `a63b2c02`, parses its `location[].event.{x,z}` into disc centres. 60s fixed-rate refresh on a daemon thread, with an immediate initial pull, started from `VetsmodClient`. Stale-fallback covers *every* failure path — a non-200, a 200 with no `a63b2c02` entry, and any exception all keep the previous centres. Cold-fallback returns `isInZone() = false` (so the boss-bar countdown variant defaults to the assigned/seeking text rather than a false-positive zone claim).

Live API shape (probed 2026-06-16): `location: [{event: {x:315, y:31, z:-1291}, spawn: {x:350, y:29, z:-1291}, reward: {x:348, y:29, z:-1292}, radius: 48}]`. Spec mandates 48-block radius; we hard-code the constant in case the API drifts. Squared-distance comparison, no `Math.sqrt`.

Downstream consumers reuse this same fetcher: the outline gate (`AnniOutlineTicker`, via `forceInZone || isInZone`), the ghosts prompt (`GhostsPromptHandler`), aggressive-mode alerts (`AggressiveAlertDispatcher`), and the boss bar itself (`VetsBossBarManager`'s activation gate plus `VetsBossBarContentBuilder`'s countdown gate) — all via `isInZone`; plus `AnniZoneLineRenderer` and the debug dump, via `getDiscs()` / `isCold()`.

The scroll waypoint does **not**. `ScrollSpotMarkerProvider` never references `AnniZone` at all: its position comes from `board.party.scroll_spot`, falling back to the hard-coded `345 45 -1315`, and its gate is `AnniAggressiveTicker.isAggressiveActive()`.

## Config keys

One table, every key. `vetsAnniOutlinesEnabled` and `vetsAnniNametagsEnabled` gate the two halves of §Player highlights; the last four gate the §Aggressive mode components.

| Key | Type | Default | Purpose |
|--|--|--|--|
| `vetsAnniEnabled` | bool | false (auto-true on first vets-tier auth ack) | Master MWE toggle |
| `vetsAnniMode` | string | `silent` on disk, but `AnniModeManager.applyStartupDefaultIfNeeded` promotes still-unset enrichment-eligible users to `passive` at world-join | Active anni mode. Not settable via `/wv config` — see §Mode state |
| `vetsAnniRoleStyle` | string | `descriptive` | Role-naming style (`descriptive`/`short`/`formal`) — boss bar always uses `short` |
| `vetsAnniShowHoverDetails` | bool | true | Populate hover tooltips on chips |
| `vetsAnniPromptRsvp` | bool | true | Show line 2 of anni-motd. Its only reader is `AnniMotdRenderer.render` — it does **not** gate the `[Hard]`/`[Soft]` pills on `/wv anni`, which `AnniCommandRenderer.rsvpSection` emits from `!rsvped && farOut` with no config read |
| `vetsAnniShowPrediction` | bool | true | Show `\guess`-style prediction in `/wv anni` not-announced |
| `vetsAnniBossbarEnabled` | bool | true | Master kill-switch for the synthetic boss bar. Only consulted in passive/aggressive. |
| `vetsAnniFlashIntensity` | string | `normal` | `subtle`=5s / `normal`=10s / `strong`=20s flash duration per field change. |
| `vetsAnniFlashSound` | bool | true | Whether a field change (or a debug `flash`) plays `SoundEvents.EXPERIENCE_ORB_PICKUP` twice, 110 ms apart. Concurrent changes collapse to one 2-ping burst. |
| `vetsAnniOutlinesEnabled` | bool | true | Counts toward the gate's at-least-one-toggle condition in `AnniOutlineTicker.gateHolds`, so it is read even when the gate then fails, and gates exactly one behaviour: `EntityOutlineColorMixin` zeroing an outsider's `state.outlineColor`. It does **not** gate `AnniOutlineTicker`'s `setGlowColor` call or `EntityGlowingMixin` — with this off and `vetsAnniNametagsEnabled` on, registry members still get their tier glow, and outsiders that vanilla already renders as glowing keep their native team outline instead of having it zeroed |
| `vetsAnniNametagsEnabled` | bool | true | Nametag overlay (matching colour scheme). Separable from outlines so users can pick one half. |
| `vetsAnniZoneLines` | bool | true | `AnniZoneLineRenderer`'s cylinder cage around each anni disc. |
| `vetsAnniScrollWaypoint` | bool | true | `ScrollSpotMarkerProvider`'s dark-red beacon on the host-pinned scroll spot. |
| `vetsAnniChatAlerts` | bool | true | `AggressiveAlertDispatcher`'s per-field diff alerts and the two readiness alerts. |
| `vetsAnniGhostsPrompt` | bool | true | `GhostsPromptHandler`'s rising-edge zone-entry prompt. |

Internal, not user-facing:

- `vetsAnniGhostsPromptShownForStamp` (string, default `""`) — persists the stamp_epoch the ghosts prompt last fired for, and only on the *ambiguous* (no visible ghost) branch; the ghost-confirmed branch prompts on every zone rising edge and writes nothing. Survives restarts.
- `vetsAnniModeUserSet` (bool) and `vetsAnniUserMode` (string) — the remembered explicit pick; see §Mode state.

## Debug harness

All under `/wv debug tree anni …` (the `tree` literal nests subsystem-specific debug trees so top-level `/wv debug` stays generic). `AnniDebugCommands.buildCommandTree` registers **11 top-level literals** and **24 executable leaves**.

**`snapshot`** — cache manipulation.
- `snapshot inject preset <name>` — loads from `src/client/resources/assets/vetsmod/anni_test_snapshots/<name>.json`. Preset name auto-sets the external override (`external_*` → forced external, `member_*` → forced vets, others → auto).
- `snapshot inject file <name>` — loads from `vetsmod/dumps/anni/<name>.json`.
- `snapshot inject <json>` — raw inline. Registered *after* the two literals so the literals win resolution.
- `snapshot dump` — write current cache to `vetsmod/dumps/anni/snapshot-<ts>.json`.
- `snapshot clear` — set cache to null.
- `snapshot refresh` — pull fresh from server (alias for the auto-pull path).

**Single-purpose literals.**
- `guess` — pull + print fishbot-style one-liner (announced stamp + countdown OR prediction window).
- `time <seconds>` — round-trip the cached snapshot through JSON and rewrite the `event` block. It does **not** preserve everything else. For a positive argument it sets `stamp_epoch = NOW + seconds`, forces `announced = true`, and nulls `prediction`. For `seconds <= 0` it nulls `stamp_epoch` and sets `announced = false`, leaving `prediction` alone — so the suggested `-60` ("1m ago") cannot produce a stamp one minute in the past.
- `external <auto|true|false>` — override `isExternal` for testing.
- `zone <enter|exit>` — sets/clears `AnniOutlineTicker.setForceInZone(...)`, so dev sessions can verify the highlight overlay without flying to the anni location. It affects the **highlight gate only**: the boss bar, zone lines, scroll waypoint and ghosts prompt all call `AnniZone.isInZone` directly and never see the override.
- `flash <role|party|world|rsvp>` — force a `FlashTracker` pulse on the named field (10s at the `normal` default; the sound plays only when `vetsAnniFlashSound` is on). The pulse is rendered by the synthetic boss bar alone, so it is invisible unless the bar is currently active. `world` is the exception — it has no timed window, so forcing it lasts until the next tick recomputes the real mismatch.
- `mode set <silent|passive|aggressive>` — `AnniModeManager.transitionTo(..., DEBUG_BYPASS_MUTEX)`. Skips the `/stream` mutex so we can verify rendering during screen capture.
- `alert <role|world|party|rsvp|zone|world_ready>` — synthesise a chat alert without contriving a diff or waiting for a T-N boundary.

**`scrollspot`** — the only subtree with a second gate (`requireStaffOrOrganiser`). All five leaves are gated identically even though only the first three write to the server.
- `scrollspot set <x> <y> <z>` — host write via WS frame (real action; vets-anni 403s unless you're the host of your assigned party).
- `scrollspot here` — host write using your current block-pos.
- `scrollspot clear` — host write that clears the party's spot.
- `scrollspot localinject <x> <y> <z>` — local-only paint into the marker provider; no server round-trip. For visual testing of the waypoint render without coordinating a host.
- `scrollspot localclear` — clear the local-only injection.

**`rsvp`** — debug mirror of the main brigadier `/wv anni rsvp`. Identical effect; bypasses no logic. Exists for symmetry with the scrollspot debug-tree mirror.
- `rsvp hard` / `rsvp soft` / `rsvp revoke`.

**`registry`** — arbitrary `AnniOutlineRegistry` injection, for verifying the highlight and nametag branches without coordinating a real anni party. Wiped by the next snapshot rebuild, so re-inject after any `snapshot inject` / `snapshot clear`.
- `registry set <username> <role>` — `role` may be a role code or `other`, which routes to the grey other-vets-party tier.
- `registry clear <username>` / `registry clearall`.

All gated by `VetsLogger.isDebugEnabled()` (`/wv debug true`).

### `/wv debug trigger …` dumps

A flat trigger family beside the `tree` subtree. **These are ungated** — unlike every `tree anni` leaf, they run for anyone who types them. Three of the five print to chat and two do not: `bossBarsDump`, `nametagsDump` and `zoneLinesDump` go to chat via `ChatUtils.sendLocalMessage*`, while `ghostsPromptDump` and `rsvpDump` write to the log via `VetsLogger.info` — check `latest.log`, not chat.

- `bossBarsDump` — dumps vanilla's `BossHealthOverlay#events` map with per-bar `UUID/color/overlay/progress/name`. Was the smoking gun for the PINK-colour suppression — the dump immediately revealed every Wynncraft text-only bar shipped `color=PINK overlay=PROGRESS`. Useful for any future "why doesn't my bar render" question.
- `nametagsDump` — walks `level.players()` and reports each player's username, `AnniOutlineRegistry` hit/miss, tier, role, and the `ChatFormatting` the `NametagMixin` would resolve to right now, plus a header line of `outlineSuppressionActive` / `vetsAnniNametagsEnabled` / `vetsAnniOutlinesEnabled`. Use whenever a nametag colour doesn't match what you expect; the dump's `→ <username> (<colour> via registry)` segment shows what should render in-world. ⚠️ It emits **no** `original=` column and never reads render state, so it cannot show you the embedded leading `§a` — see [`nametags-dump-missing-original-column`](ephemeral/bugs-found-via-mellow-rain/nametags-dump-missing-original-column.md).
- `ghostsPromptDump` — dump `aggressive_active / toggle_on / in_zone / stamp / shown_for` + per-player `isPlayerGhost` result + `would_fire_on_rising_edge`.
- `zoneLinesDump` — dump aggressive gate state + every cached `AnniZone.Disc` + squared distance to the player. Diagnostic for "why aren't lines rendering."
- `rsvpDump` — dump auth state, in-flight queue depth, last attempt/ack, and the snapshot's `rsvp` block. It calls `AnniRsvpClient.debugDump()`, which reads the private statics directly; the public `lastAttemptedNotice()` / `lastAck()` / `pendingCount()` accessors have **no callers repo-wide**.

### Template substitution in fixtures

Every `snapshot inject` path — preset, file and inline — runs through `substituteNowTokens`; it is the first step of `parseAndInject`. Tokens replaced with the current epoch-seconds:
- `"{NOW}"` → integer
- `"{NOW+72h}"`, `"{NOW-30m}"`, `"{NOW+1.5s}"` → integer

So fixtures stay valid across long stretches of time. Use it for any new preset that includes an epoch.

### Bundled presets

Listed in `AnniDebugCommands.PRESETS`.

| Preset | Branch tested |
|--|--|
| `empty` | Known player, no `AnniEvent` on file — `event` and `rsvp` null, unregistered, unplaced, band 1 |
| `external_no_anni` | External + not announced (lean external render) |
| `member_no_anni` | Vets + not announced + registered with roles (all-set affirmation) |
| `member_no_anni_fill` | Vets + not announced + registered but no specific roles (4-line fill explainer) |
| `member_no_anni_unregistered` | Vets + not announced + registered=false (red unregistered nudge) |
| `member_announced` | Vets + announced (NOW+8h) + unassigned (attendance bar) |
| `member_in_party` | Vets + announced (NOW+8h) + party (ASSIGNED + party block) |

All seven are `schema_version: 1` with no `event.all_parties`. `member_in_party` still covers the own-party role colours (six members, one per role, via the v1 `board.party.members` field) and, by registry miss, the outsider branch; only the *other-vets-party* grey tier is unreachable from a preset. Reach that one with `/wv debug tree anni registry set <username> other`, plus `zone enter` and a non-silent mode.

## Where the render-pipeline lessons went

Building this subsystem produced a set of forward-looking lessons that are not
about anni at all. They now live with the subject they belong to, so there is
one copy of each:

- **Render pipeline** — resource-pack sprite overrides, the cheap per-tick
  `volatile boolean` gate flags, `Gizmos.circle` and its sibling primitives,
  MarkerProvider lifecycle, and Wynntils' unconditional `BeaconBeamFeature`:
  [vetsmod_rendering.md](vetsmod_rendering.md) §6.
- **Mixins** — render-side over packet-side filtering, `state.<field>`
  coercion at the assignment site, the § codes Wynncraft embeds in nametag
  string content, Wynntils' `AvatarRendererMixin` HEAD cancel and what to test
  against it, and Mixin's non-private-static and `priority` rules:
  [vetsmod_mixins.md](vetsmod_mixins.md).
- **Wynntils API** — `Models.Player.isPlayerGhost` as a one-way ghosts-on
  detector, and the `Models.X`-at-init cold-start crash:
  [project_wynntils.md](project_wynntils.md).
- **Brigadier** — literal children give you a suggestion list without a
  `SuggestionProvider`: [vetsmod_commands.md](vetsmod_commands.md) §4.

Two stayed here because they are anni facts rather than lessons: the cold-start
snapshot pull is §Snapshot pipeline step 5, and `AnniHoverBuilder.noticeColor`
as the canonical RSVP colour map sits with the other chat labels under
§`/wv anni` render dispatch.

One is server-side, and it lands here:

- **`app.state.fishbot` is how a vets-anni FastAPI route handler reaches the
  optional bot.** `main.py`'s lifespan does `app.state.fishbot = bot`, and
  route handlers read it back as
  `getattr(request.app.state, "fishbot", None)`. It is genuinely `None`
  whenever `FISHBOT_TOKEN` is unset — `start_fishbot` returns `(None, None)`
  and the web app runs on regardless — so **every** caller must guard it.
  ⚠️ Two things that were previously claimed here are false. `_post_public`
  does **not** no-op on a missing bot: it guards a falsy channel id and a
  `None` channel, never `bot is None`, so the guard that actually protects it
  lives one level up in `execute_uuid_rsvp` — see
  [`vets-anni-post-public-missing-bot-guard`](ephemeral/bugs-found-via-mellow-rain/vets-anni-post-public-missing-bot-guard.md).
  And `anni_ping_poller.py` does **not** use this reach-out at all: the
  lifespan passes the bot to it positionally, as
  `anni_ping_poller.run(state, settings, bot)`. Two different patterns in one
  process — reach-out for request handlers, closure-passed for pollers.

## Open follow-ups

- **Client-side prediction for the no-snapshot case** — external users falling back to legacy still see "not announced" instead of a prediction. Would need a ~30-line Uniform(71.4h, 82h) model anchored on `AnniStampPoller`. Deferred pending user call.
- **Active-mode highlight on the mode-switch UI** — current state isn't visually indicated. User clicks to switch; no "you are here" marker.
- **Boss-bar countdown stamp source** — spec §3.1.1.3 footnote suggests T-5m text should derive from Wynntils' world-event countdown rather than purely from `stamp_epoch`. The boss bar uses the stamp; revisit if a real anni test reveals drift.

## Source-of-truth pointers

- Snapshot wire contract — [`vets-anni/.claude/snapshot_integration.md`](../../vets-anni/.claude/snapshot_integration.md)

*Note: the original cross-repo spec and the S3–S5 investigation prep docs lived under `vets-anni/.claude/ephemeral/` and are gone. The load-bearing decisions from each are inlined into the relevant sections above (§Boss bar, §Player highlights, §Aggressive mode); the code is the remaining source of truth. The directory itself still exists and still holds `README.md` and `auto-mode.md` — it was those particular files that were removed, not the directory, so don't go looking for it as though it were deleted.*
