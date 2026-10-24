---
name: vetsmod MWE/anni subsystem
description: Architecture-as-built for the vetsmod ↔ fishbot anni MWE subsystem (snapshot pipeline, /wv anni renderer, anni-motd, debug harness, mode state, passive-mode boss bar + outlines/nametags, aggressive-mode zone lines + scroll spot + ghosts prompt + chat alerts, in-game `/wv anni rsvp`, party back-report).
type: project
---

# vetsmod MWE/anni subsystem

Architecture-as-built reference. Read this first if you're touching the subsystem; the code itself is the source of truth, this doc captures the load-bearing design context that isn't obvious from the code.

Organised by subject, not by the order the work happened. Each category — config keys, debug commands, wire shapes — has exactly one section, and that section is the complete list.

## Package layout

Most anni-side code lives under `org.wynnvets.mwe.anni.*`. The ones that do not, and are easy to miss: `fetcher.polling.AnniSnapshotPoller` and `AnniStampPoller`, `fetcher.ondemand.StampFetcher`, `commands.CommandRegistry`, `listeners.PartyRosterListener`, the four mixins under `mixin.client`, and `debug.anni`, the `/wv debug tree anni` harness.

```
mwe/anni/
├── AnniPartyReporter.java          — snapshot listener; on organiser-set change or the window opening calls PartyRosterListener.requestRecapture
├── ScrollSpotMarkerProvider.java   — Wynntils MarkerProvider, dark-red beacon
├── state/
│   ├── AnniSnapshot.java          — Gson-hydrated bean mirroring the wire shape; snake_case fields, deliberately not a record
│   ├── AnniSnapshotCache.java     — process-wide volatile + listener bus
│   ├── AnniSnapshots.java         — the four field accessors the diffing surfaces read (role / partyOrdinal / partyWorld / rsvpNotice), all null-guarded
│   └── AnniWindows.java           — the anni window edges, named once each, plus inHotWindow / hotWindowClosed
├── network/
│   ├── AnniWsSender.java          — the four anni sends on the v1 inbound socket (anni_query, anni_scrollspot_set, anni_rsvp, anni_party_observation)
│   ├── AnniWsHandler.java         — V1 outbound/inbound listener; one outbound + four inbound frame types (§Snapshot pipeline)
│   ├── AnniQueryClient.java       — FIFO-queued pull (anni_query), no correlation IDs; 8s deadline
│   ├── AnniRsvpClient.java        — ack client for anni_rsvp (§`/wv anni rsvp`)
│   └── AnniScrollspotClient.java  — ack client for anni_scrollspot_set (§Aggressive mode)
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
│   ├── AnniOutlinePalette.java   — ChatFormatting-derived role colour table; the mod's single role table for chat and outline alike
│   ├── AnniOutlineRegistry.java  — username → tier entry, rebuilt off snapshot (lowercase-keyed)
│   └── AnniOutlineTicker.java    — per-tick driver; applies glow via EntityExtension; exposes isOutlineSuppressionActive() flag for the mixins
├── aggressive/
│   ├── AggressiveAlertDispatcher.java  — snapshot diffs + T-10m/T-5m readiness
│   ├── AnniAggressiveTicker.java       — volatile flag; mode==AGGRESSIVE ∧ window
│   └── GhostsPromptHandler.java        — rising-edge zone-entry prompt
├── command/
│   ├── AnniRsvpCommand.java        — brigadier handler: auth gate, dispatch, render ack
│   └── AnniScrollspotCommand.java  — invoked from debug tree (not main /wv)
└── render/
    ├── AnniHoverBuilder.java      — colour tokens, role chips, rsvp badges, link/click/hover helpers
    ├── AnniCommandRenderer.java   — /wv anni payload builder; returns List<MutableComponent>
    ├── AnniOnboardingBlocks.java  — the not-announced registration block: unregistered nudge, fill-only explainer, has-roles affirmation
    ├── AnniStatusSections.java    — the announced body's status lines: role, RSVP type and upgrade prompt, attendance, party
    └── AnniMotdRenderer.java      — world-join anni-motd (two-line "Annihilation returns in X.Yh! / You have been assigned …")

debug/anni/                         — the /wv debug tree anni harness (§Debug harness)
```

## Snapshot pipeline

1. **vets-anni** assembles per-uuid snapshots via `app/domain/snapshot.py::assemble_snapshot`. The `event` block is populated whenever any past `AnniEvent` exists; `event: null` is only emitted on a truly empty DB.
2. **temp-server's poller** (`app/services/anni_snapshot_poller.py`) hits vets-anni's `/api/internal/anni-snapshot-batch` and `/api/internal/anni-player/{uuid}`; pushes per-uuid changes as `anni_state` frames every ~10 s in the hot window, ~5 min otherwise, to the uuids in its subscription set (filled at inbound auth). The server sends them only to an authenticated outbound socket, which vetsmod opens only for a confirmed member tier against a server advertising `chat_tiers: "all"` ([vetsmod_networking.md](vetsmod_networking.md) §1); a player with no key, or tier `other`, gets none. Without a push, and outside the `/wv debug tree anni` simulation commands, the cache is refreshed only by pulls: step 5's cold-start pull, the post-connect re-pull, `AnniSnapshotPoller`, a cold-cache `/wv anni`, and the refresh after an in-game RSVP (`AnniRsvpCommand`).
3. **vetsmod's `AnniWsHandler`** is the frame router. On the *outbound* channel it handles `anni_state` (push) alone — hydrating it into `AnniSnapshot` and dropping it into `AnniSnapshotCache`. On *inbound* it branches four ways: `anni_query_response`, `anni_scrollspot_response` and `anni_rsvp_response` go to their single-flight clients (`AnniQueryClient` / `AnniScrollspotClient` / `AnniRsvpClient`), and `anni_party_observation_response` is debug-logged inline with no queue and no consumer state.
4. **`AnniSnapshotCache`** is a single-player, volatile, listener-bus cache. Listeners run on whichever thread called `update` — the one delivering WebSocket frames, or the render thread, which runs client commands, when `AnniSnapshotDebugCommands` injects — so bounce to the main thread via `McUtils.mc().execute(...)` if you need to touch render state. `schemaVersion()` is surfaced but never validated: a v1 payload just degrades, it is not rejected.
5. **Cold start pulls rather than waiting for the push.** `StampFetcher.fetchStampAndCreateMessage` (the world-join motd path) reads `AnniSnapshotCache.latest()` and on `null` fires a fire-and-forget `AnniQueryClient.query()` before falling through to legacy stamp text. *Note: this intentionally does NOT rely on the push alone. The ~5 min figure is temporary-server's out-of-window `anni_state` cadence (step 2), and vetsmod's own `AnniSnapshotPoller` does not close that gap: it runs on a fixed 30 s schedule but its tick returns immediately unless the cached stamp is inside the 90-minute bar window, floored at `> 0`; its 30 s initial delay, the only non-zero one among the pollers, costs nothing here, because a tick at 0 s would find the cache empty and return (see [vetsmod_networking.md](vetsmod_networking.md) §5). So on cold start a user already inside the anni zone — hours before the window opens — would otherwise see legacy motd and no boss bar until the next push. A player without an authenticated outbound socket gets no push, and `AnniSnapshotPoller` has no stamp to poll against while the cache is empty, so for them the wait would last until some other pull: a reconnect's re-pull, an in-game RSVP, or a cold-cache `/wv anni`.* Any new component that depends on the cache being warm at world-join time gets this for free — but only for a vet, since `StampFetcher` fires the pull only when `GuildStateManager.isEligibleForEnrichment()` (`AnniMotdRenderer` declines an external user's snapshot, and `AnniCommandRenderer` declines an announced one and gives a not-announced one only its short external form), and only once a pull returns a snapshot. Per temporary-server (at ffd8c17), that needs an inbound socket an `/unlock` key has authenticated, because the frame names no UUID; an unauthenticated pull is answered `mc_uuid required` and resolves null. `AnniWsHandler.register()` installs the reconnect counterpart — a post-connect `AnniQueryClient::query` re-pull, which also runs on the first inbound connect and is not gated on eligibility.

## Anni time windows

**There are five window concepts, not one and not three, and they stay five.**
The three that gate anni surfaces are declared once each in
`mwe/anni/state/AnniWindows`; before that they were **seven declarations across
five classes**. Two of those seven carried a comment naming their partner — one
by class, one by symbol — and nothing checked either. The other two concepts stay
where they are, for the reasons in the table.

> ⚠️ **There is no T−30m. The 1800 is T+30m** — thirty minutes *after* the
> announced stamp, the **closing** edge of a two-sided window, not a boundary
> before it. `AnniWindows.inHotWindow` is
> `secondsUntil >= -HOT_WINDOW_AFTER_SECONDS && secondsUntil <= HOT_WINDOW_BEFORE_SECONDS`,
> i.e. `now ∈ [stamp − 7200, stamp + 1800]`, pinned by `AnniWindowsTest` at
> `-1800` in and `-1801` out. It is the most misread value in the set — even
> the brief for an earlier doc audit called it "T−30m".
>
> ⚠️ **"90 minutes" denotes two unrelated things in this repo.**
> `AnniWindows.BAR_WINDOW_SECONDS` is the client-side boss-bar and snapshot-poll
> window. vets-anni *also* closes RSVP 90 minutes before an anni, server-side —
> see [vetsmod_commands.md](vetsmod_commands.md). They share the number and
> nothing else, and the RSVP cutoff has **no client constant at all**. Check
> which one a claim is about before reconciling it.
>
> ⚠️ **Grepping `7200` finds none of the three constants that hold it** — two
> are written `2L * 60L * 60L` and one `2L * 60 * 60`. Same for 1800 and 5400.
> Grep the symbol, not the number.

| Concept | Value | Who reads it | Shape |
|--|--|--|--|
| Hot window, open edge | 2 h | `AnniOutlineTicker`, `AnniAggressiveTicker` | `AnniWindows.inHotWindow(secondsUntil)` — both edges **inclusive** |
| Hot window, close edge | 30 m | the same two, plus `AnniWindowWatcher` | the watcher needs the closing edge alone, inverted: `AnniWindows.hotWindowClosed(anchor, now)` |
| Bar / poll window | 90 m | `VetsBossBarManager`, `AnniSnapshotPoller` | `AnniWindows.BAR_WINDOW_SECONDS` — a constant, **no shared predicate**; see below |
| Drop-dead | 20 s | `VetsBossBarManager.DROP_DEAD_SECONDS_BEFORE_ANNI`, `VetsBossBarContentBuilder.T_MINUS_20_GATE_SECONDS` | two boss-bar timing constants, each naming the other in its Javadoc; deliberately not in `AnniWindows` |
| Party-observation | ±2 h | `PartyRosterListener.ACTIVE_WINDOW_SEC` | **symmetric** — a third guard shape, answering a different question |

`AnniCommandRenderer.TWO_HOURS_SECONDS` is a sixth entry and is not a window at
all: it selects the far-out versus imminent render branch of `/wv anni`, and
`AnniStatusSections.rsvpSection` drops the RSVP upgrade prompt at or below it.
It stays where it is for the same reason `PartyRosterListener`'s does — two
files agreeing on a number today is not shared configuration.

**Three constant declarations now hold 7200** —
`AnniWindows.HOT_WINDOW_BEFORE_SECONDS`, `PartyRosterListener.ACTIVE_WINDOW_SEC`
and `AnniCommandRenderer.TWO_HOURS_SECONDS` — where there used to be four.

⚠️ **Greping for `7200` finds none of them.** Two are spelled `2L * 60L * 60L` and
one `2L * 60 * 60`; that grep returns only the `"7200"` string in
`AnniDebugSuggestions`' time-suggestion array. Census them by name, or grep
`60L \* 60L\|60 \* 60`. The earlier wording here promised the grep would find
three declarations and it never could — a verification recipe is a
hypothesis too.

**Why the 90-minute pair gets a constant and not a predicate.** The two
comparisons share the number and **not** their floor. `AnniSnapshotPoller` floors
at `secondsUntilAnni > 0`; `VetsBossBarManager` floors twenty seconds earlier, at
a `DROP_DEAD_SECONDS_BEFORE_ANNI` hard return *earlier in the same method* — which
is why its window expression reads one-sided when the window is not. The bar's
effective window is `(T-20s, T-90m]`, the poller's is `(T, T-90m]`, and the last
twenty seconds before an anni are polled but not drawn. A shared `inBarWindow`
would have to change one of them. Anyone reading the bar's `inWindow` line alone
and concluding it has no lower bound is reading the expression, not the method;
§"Activation gate" below has always had this right.

**And the bar's gate is not a window test.** It is `inWindow || AnniZone.isInZone(...)`,
so a player who walks into the zone hours early gets the bar. `AnniWindows`
supplies the number, not the gate — a future simplification that drops the
`!inZone` arm changes when the bar appears.

**Nothing was unified.** Both candidate collapses are user-visible and neither has
a motivation: moving the bar and poller 90 m → 2 h makes the bar appear thirty
minutes earlier and adds a third again as much polling; moving the tickers
2 h → 90 m stops highlights appearing between T-2h and T-90m.

`AnniWindows` is pure — no clock, no cache. Callers keep their own
`Instant.now().getEpochSecond()` line and pass the result in, the shape
`PartyRosterListener.shouldSend(…, long now)` already used. Pinned by
`AnniWindowsTest` over the boundary table `-1801, -1800, -1799, 0, 5399, 5400,
5401, 7199, 7200, 7201`.

## Snapshot field accessors

The four fields the boss bar's `FlashTracker` and the aggressive-mode
`AggressiveAlertDispatcher` diff — `role`, `partyOrdinal`, `partyWorld`,
`rsvpNotice` — are pulled out by `mwe/anni/state/AnniSnapshots`. Each class used
to carry its own four private extractors, fourteen call sites between them; three
of the four pairs were byte-identical (one under two names, `extractRoleCode` and
`extractRole`) and `extractPartyWorld`'s two bodies were not.

**All four guard a null snapshot, and that is load-bearing exactly once.** A null
snapshot is a legal cache state — `AnniSnapshotCache.update(null)` is a documented
"no snapshot available" signal and `/wv debug tree anni snapshot clear` makes that
call, so `latest()` can return null at any point in a warm session, not only on a
cold cache. `FlashTracker.updateWorldMismatch` hands `latest()` straight to
`partyWorld` from the boss bar's per-tick path and is the only site that can reach
the guard; the other thirteen sit behind a caller's own null check.

Two arms in there look redundant and are not: `partyOrdinal`'s `> 0` re-check
recovers the "unassigned" signal, because `AnniSnapshot.Party.ordinal()` returns a
primitive `int` and collapses an absent JSON key to `0`; and `rsvpNotice` reads a
revoked RSVP as absent rather than as its old value, which is the field's meaning.

**Not migrated, deliberately.** `GhostsPromptHandler.currentStampEpoch` and
`PartyRosterListener.snapshotStamp` are the same chain with a `0L` sentinel
instead of a `Long` — adopting them is a question about what `0` means, not a
rehoming. `AnniWindowWatcher`'s variant is the one whose divergence is
load-bearing: it keys off a locally cached anchor rather than the live snapshot,
because vets-anni emits `stamp_epoch: null` once the anni begins.

## Mode state

Three config keys, not one, and the second is what makes the automatic
transitions safe:

- `VetsConfigKeys.VETS_ANNI_MODE` — string, `silent`/`passive`/`aggressive`, default `silent` on disk, persisted. **Not exposed through `/wv config`**: it is absent from `VetsConfigSchema.USER_CONFIG_KEYS`, so `ConfigCommands.configGet`/`configSet` reject it as an unknown key and `configList` never prints it. The normal user path is `/wv anni <mode>`; `/wv debug tree anni mode set <mode>` also writes it, behind `requireDebug`.
- `VETS_ANNI_MODE_USER_SET` (bool) and `VETS_ANNI_USER_MODE` (string) — set together, and **only** by a `Source.USER_COMMAND` transition. They are what makes an explicit pick survive a stream-forced SILENT and the T+30m window close.

**`AnniMode`** (`mode/AnniMode.java`):
- Enum SILENT / PASSIVE / AGGRESSIVE.
- `fromConfig()` / `fromString(String)` / `toConfigValue()` for round-tripping with `VetsConfig`.

**`AnniModeManager`** (`mode/AnniModeManager.java`) — the single chokepoint.

- `transitionTo(target, Source)` with `Source ∈ {USER_COMMAND, AUTO_WINDOW_CLOSE, AUTO_STREAM_ACTIVATED, AUTO_STREAM_DEACTIVATED, AUTO_STARTUP_DEFAULT, DEBUG_BYPASS_MUTEX}` — **six** constants.
- **The `/stream` mutex applies to PASSIVE/AGGRESSIVE targets only.** `transitionTo` computes `wantsActive = target != SILENT` and consults `Models.StreamerMode.isInStream() || StreamerModeChatDetector.lastSeenInStream()` only when that holds (spec §3.1). A refusal returns `false` and leaves `VETS_ANNI_MODE` untouched — it does **not** force silent. A SILENT target skips the check entirely and always lands.
- Only a `USER_COMMAND` refusal prints "You have /stream, which is incompatible with vetsmod anni features, active. We would strongly prefer you use /toggle ghosts NONE instead, which does the same thing without hiding you from organisers!"; the auto sources decline silently with a debug log.
- Likewise on success: all four `AUTO_*` sources suppress the manager's generic "Anni mode: X (was Y)" confirmation, so the caller can print something friendlier. Only `USER_COMMAND` and `DEBUG_BYPASS_MUTEX` print it.
- `DEBUG_BYPASS_MUTEX` skips the stream check for screen-capture testing.
- `preferredMode()` — the user's remembered pick (`VETS_ANNI_USER_MODE`, honoured only while `VETS_ANNI_MODE_USER_SET`), else the eligibility default: PASSIVE when `GuildStateManager.isEligibleForEnrichment()`, SILENT otherwise. Every automatic *restore* target comes from here (see also [vetsmod_config.md](vetsmod_config.md)).
- `applyStartupDefaultIfNeeded()` — called from `GuildLifecycle` on entered-world, on guild-info-updated and when the `/gu stats` check completes, and from `UnlockManager` on an ok auth ack (on the client thread). An auth failure or a newly stored key waits for the next of those, so a transient failure can't flicker the bar. No-ops once `VETS_ANNI_MODE_USER_SET` is true; otherwise it re-applies the eligibility default in whichever direction it points (SILENT→PASSIVE when eligibility is gained, PASSIVE→SILENT when lost) via `Source.AUTO_STARTUP_DEFAULT`. It computes that default directly rather than calling `preferredMode()`. Net effect: a still-unset vets-tier user is effectively defaulted to `passive` at world-join even though the on-disk default is `silent`.

**`StreamerModeChatDetector`** (`mode/StreamerModeChatDetector.java`):
- Belt-and-braces backup for `Models.StreamerMode.isInStream()`. Hooks `ChatLogMixin` via a one-line `observe(message)` call on the chat HEAD inject.
- Substring patterns on the format-stripped chat line: `"Streamer mode was enabled"` (on) / `"Streamer mode disabled"` (off).
- On the *leading edge* of stream-on (repeat lines are no-ops): sets `lastSeenInStream=true`, and if the current mode isn't already SILENT calls `transitionTo(SILENT, AUTO_STREAM_ACTIVATED)`, printing its own "/stream activated. We have switched your anni mode to silent. (…)" block only when that returns true.
- On stream-off — and only while `lastSeenInStream` was true — it clears the flag and **does** attempt a restore to `AnniModeManager.preferredMode()` via `Source.AUTO_STREAM_DEACTIVATED`, printing "/stream deactivated! We have switched your anni mode back to <mode>." only when `transitionTo` actually returns true. Nothing is printed when the preferred target is SILENT (`handleStreamOff` returns before `transitionTo` in that case), when it already equals `AnniMode.fromConfig()`, or when `transitionTo` still refuses because Wynntils' own `isInStream()` has not yet cleared.

**`AnniWindowWatcher`** (`mode/AnniWindowWatcher.java`):
- Registered in `VetsmodClient.onInitializeClient()` after `AnniWsHandler.register()`.
- Subscribes to `AnniSnapshotCache`.
- Caches the most recent non-null `stamp_epoch` (since vets-anni emits null once the anni starts).
- On every snapshot update, queues a client-thread task (`McUtils.mc().execute`, so every anni-mode write is on the client thread) that checks `AnniWindows.hotWindowClosed(lastKnownStamp, now)` — i.e. `now > lastKnownStamp + 30 min`; if true it computes `AnniModeManager.preferredMode()` and, only when `AnniMode.fromConfig()` differs, calls `transitionTo(target, Source.AUTO_WINDOW_CLOSE)`. `lastKnownStamp` is cleared regardless of whether a transition fired. Delegating to `preferredMode()` is what preserves an explicit user pick across the window boundary — the target is **not** hard-coded SILENT.
- It writes no config directly; everything goes through `transitionTo`, so when the preferred target is PASSIVE/AGGRESSIVE the `/stream` mutex applies and the reset can be silently declined (debug log only). A SILENT target is never subject to the mutex and always lands.
- One-shot per anni cycle.

## `/wv anni` render dispatch

Entry: `CommandRegistry.anni()` → `StampFetcher.fetchStampAndCreateAnniCommandMessage()` → `AnniCommandRenderer.render(snapshot)`.

`StampFetcher`:
- cache populated → renderer.
- cache cold, a vet (`GuildStateManager.isEligibleForEnrichment()`) → `AnniQueryClient.query()` (auto-pull), then renderer if it resolves a snapshot, else legacy. The 8 s deadline is one of those null resolutions, so a pull that times out drops to legacy too.
- cache cold, not a vet → legacy fallback, with no pull.

`AnniCommandRenderer.render(snapshot)` returns `List<MutableComponent>`:
- `null` → "fall back to legacy" (the only case is external + announced).
- One element → single `[VETSMOD]` block.
- Two elements → two blocks, each gets its own full `[VETSMOD]` badge via `sendLocalMessageNewBlock`.

"Announced" is `event.announced() && stampEpoch != null && stampEpoch > now`, and that test runs **before** the external check. A snapshot with `announced=true` and a stamp already in the past therefore renders the *not-announced* form and can never reach the null/legacy path — reachable via the debug `time <seconds>` leaf, and in the real gap between an anni starting and vets-anni nulling `stamp_epoch` on the following frame.

When `fetchSimple()` yields no countdown (an HTTP or parse failure, or a past or missing stamp), `StampFetcher.legacyFallback` falls back to `AnniStampPoller.getLatestStamp()`. A future cached stamp renders as the countdown; an empty or past one prints the red not-announced line, also after a failed fetch, because `fetchSimple()` returns `null` for both. So the future never resolves `null`, and `CommandRegistry.anni`'s yellow `Annihilation timer is currently unavailable.` is reached only through its `exceptionally` arm, when the composed future fails: that arm returns `null`, and the guard's `null` half prints the line for it. The `isEmpty()` half is defensive only — no producer returns an empty list.

Three branches:
- **Not announced** — for external users the red headline + prediction line. For vets users: the white headline "Next annihilation not yet announced." alone on the first line; then the `anni.wynnvets.org` link, followed for a player with roles by a white bold ` | ` and their `Eligible Roles` line; then the prediction line; then, for a player without roles, the not-announced registration block. No board section here (no anni to be placed in).
- **Far-out (T-2h+)** — header (link + "returns in Xh Ym (HH:MM, day d)") + Assigned Role *or* Eligible Roles + RSVP Type + Attendance Chance + Party Assignment + Host. Countdown resolution scales with magnitude (`AnniCommandRenderer.appendCountdown`): `≥ 1h → Xh Ym`, `< 1h ≥ 1m → Xm Ys`, `< 1m → Ys` — sub-hour readouts surface seconds. `AnniMotdRenderer.formatHours` uses a **different** ladder: one-decimal hours (`7.2h`) for anything ≥ 0.1 h (6 min), then `Xm YYs`, then `Ys`. It never emits `Xh Ym`; only the sub-6-minute tail coincides.
- **Imminent (at or within 2h)** — the far-out test is `secondsUntil > TWO_HOURS_SECONDS`, so exactly 2h takes this branch. Same body as far-out, minus the RSVP upgrade prompt (`AnniStatusSections.rsvpSection` drops it at or below 2h), + a second block with `Change Anni Mode? (Click:) / [Silent] | [Passive] | [Aggressive]`.

### Not-announced registration block

Under the prediction, for a vets user without roles; a player with roles gets none (their roles sit on the link line). Two sub-shapes:
- **Unregistered** → `unregisteredNudgeBlock` — red bold "You have not yet chosen your roles on anni.wynnvets.org/me!" + dark-red italic followup. It reads true for every roleless player, since vets-anni marks a registration registered only when it holds a role. The announced body's Eligible Roles label for the same case is "no roles chosen".
- **Registered, no specific roles** → `fillOnlyExplainerBlock` — four lines (yellow bold italic / gold italic / 0x7E7E7E italic / white bold italic) with clickable "fill slot" and "here" links.
- **Registered with roles** → no block. `AnniOnboardingBlocks.vetsNotAnnouncedRoles` returns the `Eligible Roles:` gray label and role chips joined by a dark-gray ` · `, which the render puts beside the link. The label is always `Eligible Roles` here — the `Assigned Role` swap belongs to `renderFarOut`, and the not-announced path never consults `snapshot.board()`.

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

`Prediction: <countdown> from now|overdue | <MMM d> @<HH:mm>±~<σ>` — gold label, yellow numerics, white-bold-pipe separator, gray italic uncertainty hugging the time. The countdown comes from `formatCoarseHours` (hours only above 1h) and the qualifier flips from `from now` to `overdue` past the median; the uncertainty comes from `formatUncertainty` (nearest hour as `Xh`; below half an hour `Xmin`, at least `1min`; zero or less `0h`).

`σ = window_hours / √12` (≈3.06h for the 10.6h Uniform window one observed snapshot carried; the window is read from each snapshot, so neither figure is a constant). NOT half-window — q0/q4 are misleading tails and ± in chat reads as standard deviation.

Gated by `vetsAnniShowPrediction`. When that is on but `event.prediction()` is null — or any of `earliestEpoch()` / `medianEpoch()` / `latestEpoch()` is — `renderNotAnnounced` substitutes a gray `Prediction window unavailable (probably a vetsmod API error).` instead.

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

**Policy and mechanism differ here, so keep them apart.** The policy — short codes (HrdRSVP / SUNK / MOBK) belong on the boss bar, and chat callers spell things out — stands, and is recorded as the `feedback_anni_short_codes_reserved_for_bossbar.md` memory. The mechanism is not what "reserved" implies: `short` **is** one of the three configured `vetsAnniRoleStyle` values, user-selectable for chat with `/wv config vetsAnniRoleStyle short`, and `displayRole`'s only callers are chat surfaces (`AnniHoverBuilder.roleChip`, `AnniStatusSections.registrationSection`, `AnniMotdRenderer.assignedToPartyLine`). The bar does not read this table at all — it uses its own hardcoded `VetsBossBarContentBuilder.toShortLabel`.

### RSVP / attendance / board labels

`AnniHoverBuilder.rsvpBadge` resolves its key from `rsvp.notice()` — but only while `rsvp != null && notice != null && !rsvp.revoked()` — otherwise from `attendance.noticeEffective()`. Long forms in chat: `RSVP-HARD` / `RSVP-SOFT`, and **`RSVP-NONE`** for everyone without an RSVP, which `noticeColor` colours yellow for an on-time walk-in, red for a late one and gray when neither source yields a key, plus an upper-cased passthrough of any unrecognised key. Short forms (`HrdRSVP` etc.) are the boss bar's, emitted by `VetsBossBarContentBuilder.rsvpChip`.

`Attendance Chance` is state-dependent:
- party → "ASSIGNED" (light purple bold).
- wont_assign → "COULD NOT ASSIGN" (red bold) — hardcoded in `attendanceSection`, so unlike the board line it does **not** follow `wont_reason`.
- unassigned → `AnniHoverBuilder.attendanceBar`, a 6-cell clamped band bar + label, or the gray `attendance: unknown` when `snapshot.attendance()` is null.
- everything else, including a null `board` or `board.state()` → `attendanceSection` returns null and the line is dropped.

In the announced branches only, `Eligible Roles` becomes `Assigned Role` when `board.state()` is `party` (and the duplicate `Role:` in the party block is dropped). `assignedRoleSection` reads `board.role()`, falls back to scanning the party member list for the local player, and prints a gray `TBD` when neither yields one.

The board line is labelled `Party Assignment` for `unplaced` / `unassigned` / `wont_assign` / unknown states and for a party with no details attached. Once a party really is attached, `partyAssignedBlock` switches to `Party: N  World: W`, plus a `Host:` line when `party.host().username()` is non-null.

`AnniHoverBuilder.noticeColor` is the intended RSVP colour map for chat surfaces — `AnniRsvpCommand.successComponent` calls it — and it is the one to call from chat-output code, not just from `/wv anni` renderer code. Saved as the `feedback_anni_rsvp_colours.md` memory: call the helper, don't inline `ChatFormatting.AQUA`/`GREEN` literals.

**It is not universal, it is not going to be, and the reason is settled.**
`VetsBossBarContentBuilder.rsvpChip` inlines its own map and **keeps it.** The
boss bar is the one surface whose labels are its own; unifying the two changes
what the bar says, which is
disqualifying on its own. Nothing in the bar's chip is to be folded into
`noticeColor` or vice versa.

Three corrections to how this decision was once recorded, because the old
wording sent it round in a circle:

1. **`rsvpChip` is not one of "the three".** "The three" are the
   *role*-colour tables — `AnniOutlinePalette.chatFormattingForRole`,
   `AnniHoverBuilder.roleColor` and `VetsBossBarContentBuilder.roleColor`. This is
   a *notice*-colour table, a fourth that the old record never enumerated. (And of the
   three role tables only the last diverges, which is why the first two collapsed
   — see §`AnniOutlinePalette`.)
2. **The PINK rationale does not apply here.** `net.minecraft.ChatFormatting` has
   no `PINK` constant. The PINK-invisibility evidence is entirely about
   `BossEvent.BossBarColor.PINK`, the bar's *background texture*, and belongs to
   `VetsBossBarContentBuilder.colorFor` — a different method answering a different
   question about a different enum. See **Bar colour: never PINK** under §Boss bar.
3. **The hue that differs is kept on purpose.** `hard → BLUE` versus `AQUA` is
   the bar's own colour for the hard RSVP, in both of its spellings; every other
   notice that can arrive agrees (table below).

⚠️ **There are four notice states, not nine keys.** vets-anni's
`AttendanceNotice` enum (`app/constants.py`) has exactly four values —
`attend_early`, `rsvp_hard`, `rsvp_soft`, `attend_late` — and **one snapshot
ships them in two spellings**, because the two blocks serialise differently:

- `attendance.notice_effective` carries the **raw enum value**
  (`app/domain/snapshot.py`, `"notice_effective": effective.value`).
- `rsvp.notice` is **normalised server-side** to a bare `hard` / `soft`
  (`"hard" if row.notice.value == "rsvp_hard" else "soft"`).

So `hard` and `rsvp_hard` are one state seen from two blocks, as are `soft` and
`rsvp_soft`. **`walkin`, `walk_in` and `late` are emitted by nothing** — they are
defensive client-side aliases, and any count that treats them as wire keys is
counting a namespace that does not exist. The settled colour policy is hard =
AQUA, soft = GREEN, on-time walk-in (`attend_early`) = YELLOW, late walk-in
(`attend_late`) = RED, **no notice held = GRAY**; `AnniHoverBuilder.noticeColor`
implements it exactly.

| Notice key | Emitted by | `noticeColor` (chat) | `rsvpChip` (bar) | |
|--|--|--|--|--|
| `null` | either block, no fact held | `GRAY` | `GRAY`, labelled `NotRSVP` | ✓ |
| `hard` | `rsvp.notice` | `AQUA` | `AQUA`, labelled `HrdRSVP` | ✓ |
| `soft` | `rsvp.notice` | `GREEN` | `GREEN`, labelled `SftRSVP` | ✓ |
| `rsvp_hard` | `attendance.notice_effective` | `AQUA` | `AQUA`, labelled `HrdRSVP` | ✓ |
| `rsvp_soft` | `attendance.notice_effective` | `GREEN` | `GREEN`, labelled `SftRSVP` | ✓ |
| `attend_early` | `attendance.notice_effective` | `YELLOW` | `YELLOW`, labelled `NotRSVP` | ✓ |
| `attend_late` | `attendance.notice_effective` | `RED` | `RED`, labelled `NotRSVP` | ✓ |
| `walkin` | — **nothing** | `YELLOW` | `GRAY`, labelled `WALKIN` | ✗ |
| `walk_in` | — **nothing** | `YELLOW` | `GRAY`, labelled `WALK_IN` | ✗ |
| `late` | — **nothing** | `RED` | `GRAY`, labelled `LATE` | ✗ |
| anything else | — | `GRAY` | `GRAY`, upper-cased passthrough | ✓ |

Eleven rows. On the eight that can actually arrive, the bar agrees with chat
(the hard RSVP moved from blue to aqua on 2026-10-23). The three dead
aliases fall to the bar's upper-cased gray passthrough; nothing sends them.

`rsvpChip` prefers `rsvp.notice` (`hard` / `soft`) and falls back to
`attendance.notice_effective`, and has an arm for both spellings, so a walk-in,
who by definition has no RSVP row and reaches the fallback, gets a yellow or
red `NotRSVP` rather than the raw enum value; a gray `NotRSVP` is a player whose
tier cannot be tracked without an RSVP. It folds with `Locale.ROOT`, as
`noticeColor` does.

## anni-motd (world-join)

Entry: `WorldJoinAnnouncer.fetchAndDisplayStampMessage` → `StampFetcher.fetchStampAndCreateMessage` → `AnniMotdRenderer.render`.

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
| `wont_assign` | "You won't be assigned - <wontReason>." / "You won't be assigned to this anni." — no callouts, no RSVP clause |
| `unplaced` | "You're not yet on the placement queue and <rsvpClause>!" |

`rsvpClause` yields six strings — hard / soft / walking-in-early / walking-in-late / "have not RSVP'd" (no notice key) / "have an unrecognised RSVP" (unknown key) — keyed off `snapshot.rsvp().notice()` when there **is** a live notice — `rsvp != null && rsvp.notice() != null && !rsvp.revoked()`, all three — and off `attendance.noticeEffective()` otherwise. "Unless `revoked()`" named one of the three ways the fallback is reached; a missing `rsvp` block and a null `notice` take it too, and for a walk-in it is the missing block that does.

External users get `null` from `AnniMotdRenderer.render` → fall through to legacy stamp text (spec §"For external users").

Line 2 is dropped two ways: `vetsAnniPromptRsvp=false` suppresses it outright, and `buildStatusLine` returns null — so nothing is appended — when `board` or `board.state()` is null, or the state is none of the four above.

## Boss bar

The boss-bar manager uses a render-side filter (priority-500 `@Redirect` on `events.values()` inside `BossHealthOverlay#render`). *Note: this intentionally does NOT cancel `BossHealthOverlay#update` — vanilla's update handlers call a setter on `events.get(uuid)` (e.g. `setName(...)`), and cancelling at update would NPE the next UpdateProgress / UpdateName / UpdateStyle packet for that UUID, disconnecting the client.*

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

  **The snapshot pings follow the bar.** Steps 1–6 are early returns from `tickInner`, and `tick()` is `try { tickInner(); FlashTracker.tick(); } catch …`, so `FlashTracker.tick()` still runs with the bar down. What keeps it quiet is `applyDiff`: it queues the 2× ping only while `VetsBossBarManager.isActive()` holds, so with the bar down (SILENT, `vetsAnniBossbarEnabled` off, or outside the window and zone) role/party/RSVP/world transitions are diffed into the baseline but play nothing. `isActive()` is the previous tick's state, so a change that itself brings the bar up does not ping. A debug `flash` (`forceFlash`) is not gated and pings in any mode. `deactivate()` → `FlashTracker.reset()` zeroes `pendingSounds` and the `*Observed` sentinels, so a ping in flight is cut off and the first snapshot after the bar drops is absorbed as a new baseline. A throwing tick also skips that tick's `FlashTracker.tick()`.

**`VetsBossBarContentBuilder`** (`bossbar/VetsBossBarContentBuilder.java`):
- `(AnniSnapshot, secondsUntilAnni, playerX, playerZ) → MutableComponent | null`. **Not pure**, despite doing no I/O: `build` reads `AnniZone`'s volatile disc cache through `isInZone`, and every chip goes through `FlashTracker.styleFor`, which consults time-bounded flash windows and a 250 ms pulse bit. The same four arguments demonstrably yield different Components 250 ms apart.
- Returns `null` at `≤20s` (`T_MINUS_20_GATE_SECONDS` — manager deactivates).
- In-zone (`AnniZone.isInZone`) and inside `COUNTDOWN_THRESHOLD_SECONDS`, which is **5 min**, not the spec's 2 ("spec called for 2 min, user-iterated to 5 min on 2026-06-16") → the countdown variant, `anni.wynnvets.org: ANNI IN <NNN>s | …` (spec §3.1.1.3). The trailing half has two branches: `scrollSecs = max(0, anniSecs - 20)`, and the literal `SCROLLS IN <NNN>s!` is emitted only while `scrollSecs ≤ SCROLL_COUNTDOWN_START_SECONDS` (70); above that it renders an italic light-purple `Get ready for scrolls!` instead. So `ANNI IN 099s | SCROLLS IN 079s` — the string the old spec sample showed — is unproducible: 99 s implies `scrollSecs = 79 > 70`.
- Assigned (party OR committed slot) → `anni.wynnvets.org: Role: <SHORT> | Party <n> | World: <w>` (spec §3.1.1.2). `buildAssigned` deliberately drops the `/me` path the spec writes — on a ~40-char bar every glyph counts. Role colours: TANK=blue, HEAL=green, PRIM=red, SUNK=yellow, MOBK=light-purple, FILL=dark-aqua. `isAssigned` is also satisfied by a non-FILL entry in `registration.roles` alone, while `resolveRoleCode` reads only `board.role()`, so a player registered for a specific role but not yet placed sees a gray `Role: TBD | Party TBD | World: TBD`. That is intended: they have committed to a slot the board has not filled yet.
- Seeking (no party + no committed slot) → `anni.wynnvets.org (<X>mins) | <HrdRSVP|SftRSVP|NotRSVP> for <CORE|FILL>`, reworded from spec §3.1.1.1's `Seeking a <RSVP> <SLOT> …`: the link, the minutes in dark-gray brackets (white bold number, gray `mins`, or `min` for one), a gray ` | `, the bold RSVP chip (its `&n` pulses while it flashes), a gray ` for ` and the slot chip. Slot type derived from `registration.roles` (specific role = CORE, which is unreachable in practice: a specific role takes the Assigned variant). A player the board will not assign (`board.state` `wont_assign`) sees `anni.wynnvets.org: You won't be assigned` instead, with no chip and no `wont_reason` (the bar has no room for it; the world-join MOTD and `/wv anni` show it); the bar is already red. vets-anni sends a player volunteering to sit out as plain `unassigned`, so they still see the chip.
- **Four** chips wrap via `FlashTracker.styleFor(fieldKey, base)` — `role` / `party` / `world` in the assigned variant and `rsvp` in the seeking variant — so the underline pulse (`styleFor` adds `&n` to the bold chip in the on phase) applies per-field. The slot chip, the link badge, the minutes chip and the whole countdown variant are unwrapped and never pulse.

**Bar colour: never PINK.** The signature is `colorFor(AnniSnapshot)`, not `colorFor(String)`: it returns `PURPLE` when `board.state()` is `party` — not PINK as the original spec suggested — `RED` for `wont_assign`, and otherwise quantises `attendance.band()` through `bandToBarColor` (≤2 RED, ≤4 YELLOW, else GREEN), falling back to `PURPLE` with no snapshot or no band. Wynncraft's forced resource pack overrides `boss_bar/pink_background.png` (and its progress sprite) to be fully transparent so they can repurpose pink-bar slots as text-only HUD strips (the Lv.92 Returners XP line, territory/region names like "Corrupted Road", "ROOTS OF CORRUPTION"). Reproducible on bare vanilla MC 1.21.11 with the Wynncraft pack loaded (empirically confirmed against Wynncraft's forced resource pack). Confirmed via `/wv debug trigger bossBarsDump` — every Wynncraft text-only bar shipped `color=PINK overlay=PROGRESS`. **Future colour additions: pick from `{PURPLE, RED, GREEN, YELLOW, BLUE, WHITE}` only.**

**Overlay style: NOTCHED_10 with a 100-min progress window.** Per-user request — segment dividers every 10 minutes for readability. Vanilla only ships `NOTCHED_6/10/12/20` (no `NOTCHED_9`). `PROGRESS_FULL_AT_SECONDS` decoupled from `AnniWindows.BAR_WINDOW_SECONDS` (still the 90 min activation gate) and stretched to 100 min so each of 10 segments = exactly 10 minutes of wall-clock time. At T-90m activation the bar reads ~90% = 9 of 10 segments filled, matching the "90 mins = 9 notches" mapping. Drains one segment per 10 min through T-20s.

**`FlashTracker`** (`bossbar/FlashTracker.java`):
- Subscribes to `AnniSnapshotCache`. Two flash models:
  - **Role / party / RSVP**: timed-window flash. On a snapshot diff against last-seen, marks the field "flashing" for a duration **named** by `vetsAnniFlashIntensity` — the config value is a name, not a millisecond count, and `FlashTracker.flashDurationMs()` maps `subtle`→5000, `normal`→10000 (also the fallback for null or unrecognised), `strong`→20000. First observation skipped via `roleObserved` / `partyObserved` / `rsvpObserved` sentinels — login with existing state doesn't bing on every reconnect, but **null → set** transitions during the session DO flash, and ping while the bar is up.
  - **World**: state-driven, never latched. `tick()` recomputes `worldMismatch = partyWorld != null && (currentWorld == null || !currentWorld.equalsIgnoreCase(partyWorld))` (current world read via `Models.WorldState.getCurrentWorldName()`) — the comparison is case-insensitive, and an unknown current world counts as a mismatch; `styleFor("world", base)` consults that live flag. **Position movement** silently toggles the flash — walk onto the assigned world → flash stops, walk off → flash resumes — but **never** triggers a ping. **Pings** fire only when the snapshot's `party.world` *value* changes (assignment / reassignment / unassignment), wired through the same `applyDiff` path with a `worldObserved` sentinel that skips the first-observation case.
- Pulse half-period: 250ms fixed (toggled in `tick()`, driven by `VetsBossBarManager.tick()`).
- `styleFor(fieldKey, baseStyle)` applies `.withUnderlined(true)` to `baseStyle` iff the field is flashing AND the phase bit is high — produces the spec's "`&l ↔ &n&l`" alternation.
- Sound: `SOUNDS_PER_CHANGE` = 2× `NOTE_BLOCK_PLING` (the Wynntils chat-mention ding), drained one per tick by `FlashTracker.tick()` at `SOUND_INTER_DELAY_MS` = 300 ms, so the second starts 300–350 ms after the first. Each plays at `PING_VOLUME` = 1.0, the most a sound instance can ask for; Wynntils plays its mention ding at `forUI`'s default 0.25. The UI and Master volume sliders scale both. An "everything-changed-at-once" snapshot still produces a single 2-ping burst, because `applyDiff` ORs all four field diffs into one queueing decision and `SOUNDS_CAP` bounds the queue.
- Bounces snapshot diffs off the thread that called `AnniSnapshotCache.update` — the one delivering WebSocket frames, or the render thread, which runs client commands, for a `/wv debug tree anni` injection — via `McUtils.mc().execute(...)`.
- Debug entry: `forceFlash(fieldKey)` for `/wv debug tree anni flash <field>`.

**Two T-20s deactivation gates** (parent plan §"Risks"):
1. `VetsBossBarContentBuilder.build` returns null at `≤20s`.
2. Wall-clock watchdog (`DROP_DEAD_SECONDS_BEFORE_ANNI`) in `VetsBossBarManager.tickInner` — independent of builder state. Re-checks every client tick (~50 ms), so it also covers the kick-into-queue case at T-20 without needing a separate world-change hook.

Each gate holds its 20 s in its own constant, `T_MINUS_20_GATE_SECONDS` and `DROP_DEAD_SECONDS_BEFORE_ANNI`, so neither depends on the other. Each constant's Javadoc names the other: change both together. Both gates end in the same `deactivate()`, so if the values differ the larger alone decides when the bar goes, silently shadowing the other gate, and `progressFor`'s ramp (the manager's constant) and the scroll countdown (the builder's) stop ending at the same moment.

Two, not three. The source's comments used to read `"Gate n of 3"`, counting `AnniWindowWatcher`'s T+30m **mode** reset as the third — which is not a bar gate; an earlier fix renumbered them to `"of 2"`, and two of those comments had both been numbered `"Gate 1 of 3"` for different gates. There is also a separate 2.5 h `FAILSAFE_DEACTIVATE_MS` watchdog, which is not a T-20s gate either.

Plus the slower net: at T+30m `AnniWindowWatcher` restores `AnniModeManager.preferredMode()` — not an unconditional reset to silent: it is silent both in the never-set-a-mode, not-enrichment-eligible sub-case, and whenever the user's remembered pick was silent.

## Player highlights

The highlight overlay recolours nearby players' outlines + nametags while the user is at the anni in the active window. Three tiers, single ticker.

### Snapshot schema bump (v1 → v2)

The `event.all_parties` half of the highlight tiering needs `schema_version = 2` from vets-anni. Nothing client-side *enforces* it — `schemaVersion()` is read only by `AnniSnapshotDebugCommands` diagnostics, so a v1 payload degrades silently to own-party-only tiering rather than being rejected. The single addition is `event.all_parties`: a lightweight per-party member listing keyed off the active event. Each entry is `{ordinal, members: [{uuid, username, role}, ...]}` — same `_party_member_refs` projection as `board.party.members`, so the two views never drift. Used by `AnniOutlineRegistry` to tier "in another vets-anni party" players without per-uuid round-trips. Empty list when no parties exist yet for the active event. `AnniSnapshot.Event#allParties()` returns `Collections.emptyList()` on v1 payloads (Gson leaves unknown fields null, accessor coerces), so older snapshots remain consumable.

### Activation gate — tighter than the boss bar before the stamp, wider after it

| Subsystem | Gate |
|--|--|
| Boss bar | `mode != SILENT` AND `vetsAnniBossbarEnabled` AND a snapshot with `stamp_epoch` AND `secondsUntilAnni > 20s` AND ( `secondsUntilAnni ≤ 90m` **OR** `in zone` ) AND the content builder returned non-null — with the 2.5 h stuck-snapshot failsafe on top |
| Highlights | `mode != SILENT` AND ( `vetsAnniOutlinesEnabled` OR `vetsAnniNametagsEnabled` ) AND `in window (T-2h..T+30m)` **AND** `in zone` — written in `gateHolds`' evaluation order |

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
| Other vets party | `GRAY` (§7) | `AnniOutlineRegistry.OTHER_VETS_PARTY` |

Unknown or null roles fall through to `GRAY` (§7), the same colour as the other-vets-party tier — not to FILL.

**This is now the mod's only role table for chat and outline alike.** `AnniHoverBuilder.roleColor` carried an independent copy of the same seven arms for the `/wv anni` and anni-motd surfaces; it delegates here and keeps its two call sites (`AnniHoverBuilder.roleChip` and `AnniMotdRenderer.assignedToPartyLine`). The palette is the surviving body because it is fieldless and `ChatFormatting`-only, whereas `AnniHoverBuilder` is a render class pulling in `VetsConfig` and `AnniSnapshot` — routing the outline hot path and `NametagMixin` through it to reach a switch statement would be the wrong dependency edge. `VetsBossBarContentBuilder.roleColor` is a **third** role table and stays divergent on purpose (`TANK`→BLUE, `FILL`→DARK_AQUA, spec-cited as "distinct from S4's outline colours"); do not fold it in.

The two copies were not interchangeable until a later fix corrected the fold. `chatFormattingForRole` folded with the no-argument `toUpperCase()`, so under a Turkish or Azeri default `fill` / `primary` / `tertiary` upper-cased their `i` to U+0130, matched no arm, and fell to `GRAY` — an own-party member outlined and nametagged as an outsider while the chat hover, which had always passed `Locale.ROOT`, showed the right colour. It passes `Locale.ROOT` now too. Pinned by `AnniOutlinePaletteTest` and by the cross-table equivalence cases in `AnniHoverBuilderTest`.

**`OTHER_VETS_PARTY` no longer lives here.** It moved to `AnniOutlineRegistry`, directly above the `OTHER_PARTY_ENTRY` that is its only reader. Two reasons: it was a Wynntils `CustomColor` in the palette's static initializer, which made the class unloadable from a test at all (CLAUDE.md used to name it as one of its two `<clinit>` examples), and it is paired there with a separately written `ChatFormatting.GRAY` literal. That tier's outline and nametag therefore agree **by convention rather than by construction** — change one and the other does not follow — and a convention is only checkable when both halves are in one expression. The palette now declares no fields at all.

The own-party tier has the stronger guarantee: `ownPartyEntry` derives both halves of its `Entry` — outline `CustomColor` and nametag `ChatFormatting` — from one `chatFormattingForRole` call, and the ticker and `NametagMixin`'s anni branch read that `Entry`. Deriving both from one call is what stops them drifting.

Note the relocation did **not** make `AnniOutlineRegistry` testable: it builds a `CustomColor` in `ownPartyEntry` too. The blocker moved into a class that was already blocked.

### `AnniOutlineRegistry` (`outline/AnniOutlineRegistry.java`)

Username → `Entry(tier, role, outlineColor, nametagFormatting)`. Keys are lowercase (matches whatever `GameProfile.name()` gives the consumer, defensive against snapshot case mismatches). Rebuilt off every `AnniSnapshotCache` push:

1. Own-party members (from `snapshot.board.party.members`) added first → `Tier.OWN_PARTY` with role-coloured entry.
2. Every other vets party (from `snapshot.event.all_parties`) added via `putIfAbsent` — own-party entries already win, so a player who appears in both lists (always — own party IS one of all_parties) keeps their role colour.
3. The local player (matched by `snapshot.mc_username`) is excluded — no self-outline.

`getEntry(String username)` for the consumer lookups. Concurrent hashmap; reads from main / render threads. Rebuilds after registration write from whichever thread called `AnniSnapshotCache.update` — the one delivering WebSocket frames, or the render thread, which runs client commands, for a debug injection — and the debug API below writes from the render thread too.

Beside those, a `// ── Debug API ──` block that `AnniHighlightDebugCommands` drives and no other caller touches: `debugSet(String, Entry)`, `debugRemove(String)`, `debugOwnPartyEntry(String role)` and `debugOtherPartyEntry()` back the `/wv debug tree anni registry set|clear` leaves. `size()` and `clearAll()` belong to that group too, though both sit above it — each has exactly one caller, `AnniHighlightDebugCommands.registryClearAll`, and no test *exercises* the class. (Two test files now name it in Javadoc — `AnniOutlinePaletteTest` twice in doclint-resolved `{@link}`s and `AnniHoverBuilderTest` once in `{@code}` — so a grep under `src/test` does return hits.) (The rebuild path does not need `clearAll()`: `rebuildFrom` does its own inline `entries.clear(); entries.putAll(next)`.)

### `AnniOutlineTicker` (`outline/AnniOutlineTicker.java`)

Subscribes `ClientTickEvents.END_CLIENT_TICK` once at init. Each tick:

1. Evaluate the four-condition gate, in `gateHolds`' own short-circuit order:
   **mode, at-least-one-toggle, window, zone**. The toggles are second, not
   last — a client with both off never reaches `AnniSnapshotCache` or the clock.
2. If the gate fails and `suppressionActive` was true → clear every glow we previously applied (walk `level.players()`, set `setGlowColor(NONE)` for tracked usernames), reset the flag, return.
3. If the gate holds → walk `level.players()`:
   - Skip the local player.
   - Registry hit, with `vetsAnniOutlinesEnabled` on → `setGlowColor(entry.outlineColor())`. The gate passed on the OR of the two toggles, so this reads the outlines toggle again.
   - Was applied last tick + no longer in registry, or outlines now off → `setGlowColor(NONE)`, so turning outlines off clears registry glows within a tick.
   - Otherwise → leave alone.
4. Maintain the `appliedUsernames` set so the cleanup walk only touches entities we coloured.

Exposes `isOutlineSuppressionActive()` for the two mixins to read. Debug `setForceInZone(boolean)` lets `/wv debug tree anni zone enter|exit` bypass the geo-check during dev.

### `EntityGlowingMixin` (`mixin/client/EntityGlowingMixin.java`)

Six-line HEAD-cancellable inject on `Entity.isCurrentlyGlowing()`. Returns `true` whenever `getGlowColor() != CustomColor.NONE`. Written so we could outline players Wynncraft never put in a relationship team; ⚠️ in 1.21.11 vanilla keys its outline pass on `EntityRenderState#appearsGlowing()` (`outlineColor != 0`), not on the glowing flag, so whether this is still load-bearing is unconfirmed. No mode gate — the glow-colour field is `NONE` by default, so this only fires for entities the ticker has explicitly enrolled. Per outlines.md §3 Option C "Cons".

### `EntityOutlineColorMixin` (`mixin/client/EntityOutlineColorMixin.java`)

TAIL inject on `EntityRenderer.extractRenderState(Entity, EntityRenderState, F)`. While `AnniOutlineTicker.isOutlineSuppressionActive()` AND `vetsAnniOutlinesEnabled` AND the entity is an `AbstractClientPlayer` NOT in the registry → sets `state.outlineColor = 0` directly. Native team-colour outline vanishes; registry members fall through (Wynntils' own `EntityRendererMixin` TAIL inject overrides `state.outlineColor` from `EntityExtension.getGlowColor`, which `AnniOutlineTicker` has already set to the tier colour).

**Why this and not a getTeamColor mixin (first try).** Earlier draft was `EntityTeamColorMixin` — HEAD-cancellable on `Entity.getTeamColor()`, returning `0` for outsiders. It rendered every outsider with an **opaque black** outline because vanilla 1.21.11's `extractRenderState` body does:

```java
state.outlineColor = shouldEntityAppearGlowing(entity)
    ? ARGB.opaque(entity.getTeamColor())   // forces alpha=0xFF
    : 0;
```

`ARGB.opaque(0)` = `0xFF000000`, opaque black in ARGB order (alpha in the top byte) — the outline buffer happily renders that as a solid black glow halo. Skipping the wrap entirely by clobbering `state.outlineColor` at TAIL of extract sidesteps the issue.

Mixin order vs. Wynntils' own TAIL inject doesn't matter because the branches are disjoint: registry members get their colour via the glow-colour pipeline (which we don't touch), and outsiders get `state.outlineColor = 0` regardless of which TAIL inject runs first — Wynntils' inject only fires when `getGlowColor() != NONE`, and `AnniOutlineTicker` (the only caller of `EntityExtension.setGlowColor` in vetsmod or Wynntils) sets a non-`NONE` colour only for registry members. Edges: the ticker re-syncs once per client tick, so for up to a tick after a player leaves the registry both injects fire on them (ours only with `vetsAnniOutlinesEnabled` on) and whichever TAIL runs last wins that frame; after one joins, neither fires until the next tick. The ticker also never enrols the local player, so a local player who is a registry member gets neither — reachable only via a missing or mismatched `snapshot.mc_username` or the debug `registry set` leaf, since the registry rebuild already skips them.

Side effect: tab-list colour for outsiders is **not** affected — only `state.outlineColor` is touched, which is render-state-only.

### `NametagMixin` anni branch (`mixin/client/NametagMixin.java`)

TAIL inject on `AvatarRenderer.extractRenderState(Avatar, AvatarRenderState, F)` — **one of two injectors**; the other is a MixinExtras `@WrapOperation` around the `submitNameTag` call, and both share `applyOverride`. The anni branch runs BEFORE the supporter glint branch in the TAIL body. ⚠️ **When wynnmod is loaded (`WYNNMOD_PRESENT`) the TAIL path returns before the supporter branch entirely** and the wrap owns the glint instead — see [vetsmod_mixins.md](vetsmod_mixins.md). While `AnniOutlineTicker.isOutlineSuppressionActive()` AND `vetsAnniNametagsEnabled`:

- Registry hit → recolour to `entry.nametagFormatting()` (matches the outline colour family).
- Registry miss → recolour to `ChatFormatting.DARK_GRAY` (outsider treatment).

Recolour mechanism:

```java
String stripped = ChatFormatting.stripFormatting(state.nameTag.getString());
state.nameTag = Component.literal(stripped).withStyle(fmt);
```

**Two non-obvious fixes baked in:**

1. **TAIL of `extractRenderState`, not HEAD of `submitNameTag`.** Original design hooked `submitNameTag` HEAD with priority 900. Wynntils' `CustomNametagRendererFeature.onPlayerNameTagRender` (subscribed to `PlayerNametagRenderEvent`, dispatched from Wynntils' priority-1000 HEAD inject on the same method) **cancels** the call whenever it draws the nametag itself — when it adds gear-hover lines for the hovered player, or when the player is a Wynntils user (neither while Wynntils' own player-viewer screen is open on that player) — and also when its `hidePlayerNametags` option is on. Cancellation propagates via the mixin processor's generated `if (ci.isCancelled()) return;` and skips every HEAD inject that runs after it on the same method — every numerically *higher* priority, since Mixin applies in ascending order and the first-applied HEAD callback runs first ([vetsmod_mixins.md](vetsmod_mixins.md) §"Injection priorities"). The 900 inject ran before Wynntils' cancel; what it lost was the draw itself, since Wynntils draws its own nametag in place of vanilla's. Moving to `extractRenderState` TAIL writes the override into `state.nameTag` *before* Wynntils' handler ever reads it; Wynntils' prefixed-name component picks up our colour unchanged.

2. **`ChatFormatting.stripFormatting` is mandatory.** Wynncraft embeds the team colour as a legacy `§<code>` prefix INSIDE the nametag string content — `state.nameTag.getString()` returns `"§awonderkas"` for a friend-team-coloured player, not `"wonderkas"`. Without the strip, `Component.literal("§awonderkas").withStyle(RED)` renders GREEN because vanilla's text renderer parses the leading `§a` at draw time and silently overrides the Style. Without this strip every recoloured nametag silently reverts to the team colour. `/wv debug trigger nametagsDump`'s `original=` column shows the prefix, with `§` printed as `&` (`&awonderkas`).

Falls through to the supporter glint branch when the anni gate is off — and only when `WYNNMOD_PRESENT` is false, since that flag returns out of the TAIL path before the supporter branch is reached; an own-party supporter inside the gate shows their role colour for the duration and the glint resumes after the gate closes.

## Aggressive mode

### Architecture

Five client-side components plus three wire pieces (AnniWsSender send,
AnniWsHandler ack route, AnniScrollspotClient single-flight future) and
one vets-anni internal endpoint.

| Component | Gate | Notes |
|---|---|---|
| `AnniAggressiveTicker` | computed each tick | Single cheap public `isAggressiveActive()`; every other aggressive-mode component reads it. |
| `AggressiveAlertDispatcher` | aggressive AND `vetsAnniChatAlerts` | Per-field 5s cooldown, silent first-observation (mirror FlashTracker). The gate governs *emission* only — the snapshot listener keeps running while gated off, to maintain last-seen state and reset the per-stamp sentinels. `forceAlert` (debug) bypasses both gate and cooldown. |
| `AnniZoneLineRenderer` | aggressive AND `vetsAnniZoneLines` | `WorldRenderEvents.AFTER_ENTITIES`. Stacked `Gizmos.circle` cylinder cage — 21 rings per disc spaced `Y_STEP=10` blocks (±100 vertical), Y snapped to multiples of 10 so rings don't jitter as the player walks. 200-block horizontal (X/Z only) squared-distance cull to the disc *centre*, so with the 48-block `DISC_RADIUS` a ring's near edge vanishes at roughly 152 blocks. |
| `ScrollSpotMarkerProvider` | aggressive AND `vetsAnniScrollWaypoint` AND non-null entry | Registered once with `Models.Marker.registerMarkerProvider`, deferred to CLIENT_STARTED so it runs after Wynntils' own init. It is a provider of its own, registered beside the `USER_WAYPOINTS_PROVIDER` that `MarkerModel` registers itself rather than overriding it, so the user's `/compass` waypoint is left in place. Default fallback `345 45 -1315` when the user is in a party with no host-pinned spot. Dark-red beacon (icon-only was infeasible — see decision #5 below). |
| `GhostsPromptHandler` | aggressive AND `vetsAnniGhostsPrompt` | Rising edge of `AnniZone.isInZone`. Walks `level.players()` + `Models.Player.isPlayerGhost`: any ghost-flagged player → prompt (ghosts confirmed on); else per-stamp_epoch sentinel. |

### Locked decisions

1. **Aggressive gate = `mode == AGGRESSIVE ∧ window` only.** No zone gate. Per user: aggressive features are window-scoped, not location-scoped. Zone lines render whenever you're aggressive + in-window rather than only in the zone. They still are not visible from Lutho, though — `AnniZoneLineRenderer` culls any disc centre more than 200 blocks away horizontally, so they appear on final approach.
2. **Two readiness alerts** appended to `AggressiveAlertDispatcher`. The T-10m world-mismatch fires on the first tick at or after T-10m *at which a party world is assigned*, so an unassigned player gets it late or not at all; the T-5m zone-absence latches unconditionally on the first tick inside T-5m. Neither fires past `stamp_epoch`. Each latches an **in-memory** per-stamp_epoch sentinel — once per anni *per client session*. Unlike the ghosts prompt these are not persisted, so a restart mid-window re-fires them.
3. **Ghosts-prompt detection** = `Models.Player.isPlayerGhost(player)` walk. Reads `PlayerModel.ghosts` (the cache Wynntils maintains from `_ghostN` team assignments). If any visible player is ghost → ghosts on → prompt. Else ambiguous → per-stamp_epoch sentinel (`vetsAnniGhostsPromptShownForStamp`). A player whose lookup throws (Wynntils not initialised, or a transient model failure) counts as not ghost-flagged, and the walk goes on to the next player: `anyVisibleGhost`'s catch sits *inside* the `level.players()` loop and continues.
4. **Chat-alert scope = exactly role / world / party / RSVP.** No attendance-band, no party-membership.
5. **Scroll waypoint = `Texture.MAP` + dark-red beacon.** `Texture.MAP` (the 14×14 generic, NOT `Texture.MAP_ICON` which is the 21×38 content-book tab — enum naming is a known footgun). *Note: this intentionally does NOT use "no beacon beam, icon only" — Wynntils' `BeaconBeamFeature.onRenderLevelLast` NPE-crashes the render thread on a null beacon colour, and there is no per-marker "skip beacon" path: `null` crashes, `CustomColor.NONE` falls back to user-config beacon, a custom 0-alpha colour is ignored, since Wynntils recomputes the alpha itself. Suppressing the beacon would require a mixin into Wynntils — see [vetsmod_rendering.md](vetsmod_rendering.md) §6 for the mechanism.* Final: `CustomColor.fromChatFormatting(ChatFormatting.DARK_RED)`.
6. **Scrollspot command lives under `/wv debug tree anni scrollspot`, not `/wv anni`.** It is kept out of the `/wv anni` subtree because only a few hosts use it, and rarely. That does not hide it: nothing under `/wv debug tree` carries a Brigadier `requires(…)`, so the path tab-completes for every player whether or not debug is on. Refused at execution unless `requireDebug` (must have `/wv debug true`) AND `requireStaffOrOrganiser` (staff tier OR local UUID in `snapshot.organisers`) pass. Subcommands: `set <x> <y> <z>` / `here` / `clear` are real host writes via WS; `localinject <x> <y> <z>` / `localclear` paint the marker provider directly for visual testing without coordinating a host. All five gated identically.

### Wire shape

Snapshot `schema_version: 3` adds `board.party.scroll_spot: {x,y,z}|null`, read client-side as `AnniSnapshot.Party.scrollSpot()`, which returns a nullable `AnniSnapshot.ScrollSpot` — a Gson-populated static nested class, not a record. It is present only on the local player's own party: `PartySummary` declares no `scroll_spot`, so `event.all_parties` genuinely cannot carry one.

The server halves — the `Party` columns and their migration, the `POST /api/internal/anni-party-scrollspot` host check, and temp-server's forwarding handler — live in [`vets-anni/.claude/snapshot_integration.md`](../../vets-anni/.claude/snapshot_integration.md), which states them more precisely. The three vetsmod-side wire pieces (`AnniWsSender.sendAnniScrollspotSet`, the `AnniWsHandler.onInbound` route, `AnniScrollspotClient`) are described once under §Party back-report → Reference templates.

## `/wv anni rsvp`

### Architecture

Backs the `[Hard]`/`[Soft]` upgrade-prompt buttons in `AnniStatusSections` (their `SuggestCommand("/wv anni rsvp hard|soft")` targets this command). One brigadier command + a network client + a single vets-anni endpoint; no schema bump (`rsvp.notice` is already on the snapshot at v2/v3).

Wire pieces on the network layer:
- `AnniWsSender.sendAnniRsvp(String notice)` (mirror of `sendAnniScrollspotSet`).
- `AnniWsHandler.onInbound` routes `anni_rsvp_response` to `AnniRsvpClient.onResponse`.

`AnniRsvpClient` is a single-flight ack future (clone of `AnniScrollspotClient`) whose `Ack` is `(ok, detail, result)`; `result` tells a revoke that withdrew an RSVP from one that found none (`nothing_to_withdraw`, which prints "You had no active RSVP to withdraw."; the follow-up snapshot pull still runs, since a revoke of nothing usually means the cached snapshot shows an RSVP the server no longer has) and is null from an older server, which keeps the "Your RSVP has been withdrawn." line. Its `/wv debug trigger rsvpDump` diagnostic goes through `debugDump()`, which reads the private statics (`pending`, `lastAttemptedNotice`, `lastAck`) directly. It once also declared public accessors wrapping those three; they had no callers and were removed in an earlier cleanup pass.

### Auth chain

```
/wv anni rsvp hard
  → AnniRsvpCommand.hard (GuildStateManager.isAuthenticatedThisSession() gate)
  → AnniRsvpClient.send("hard")
  → AnniWsSender.sendAnniRsvp("hard")  → {"type":"anni_rsvp","notice":"hard"}
  → temp-server _handle_anni_rsvp (stamps actor_mc_uuid from session)
  → POST vets-anni /api/internal/anni-rsvp-by-uuid   ← server side; see
                                                       snapshot_integration.md
  → 200 {"status":"ok","result":"recorded"|"withdrawn"|"nothing_to_withdraw"} or
    4xx {"status":"error","detail":"…"}
  → temp-server forwards as {"type":"anni_rsvp_response","status":…,"detail":…,"result":…}
    (result is null from an older vets-anni)
  → AnniRsvpClient.onResponse pops the head future (parseAck reads status, detail, result)
  → AnniRsvpCommand.renderAck (main thread) prints "You have HARD RSVP'd…"
    with the HARD token coloured via AnniHoverBuilder.noticeColor("hard")
    (AQUA per the noticeColor map).
```

### Locked decisions

1. **`/wv anni rsvp` lives in the main brigadier tree**, NOT under `/wv debug`
   (opposite of scrollspot). This is a public-facing user command — the
   `rsvpUpgradePrompt` buttons in `AnniStatusSections` point
   `SuggestCommand` at it. The debug-tree mirror under
   `/wv debug tree anni rsvp` is gated on
   `requireDebug` only (no staff/organiser perm — action only affects
   the caller's own RSVP).
2. **Unauthenticated message uses spec wording**:
   `"You aren't authenticated (~vetsmod on vetscord). For now, use the discord \\rsvp on discord (or use ~vetsmod)."` Surfaces both the
   Discord fallback and the link path. Differs from scrollspot's
   `"You aren't authenticated (~vetsmod on vetscord)."`, which names no command path: a
   scrollspot host has already typed the real one (locked decision 6 above).
3. **No `username_hint` field threaded through the WS frame.**
   Temp-server already has the session's `mc_uuid`, and vets-anni's own
   placeholder fallback covers brand-new users — no need for a fragile
   client-side username hint that would also need an "is this username
   actually yours" verification.
4. **`AnniRsvpClient.lastAttemptedNotice` / `lastAck` are race-prone**
   (static volatiles, no per-call correlation) — acceptable for a
   debug-only `rsvpDump` view. `debugDump()` reads the statics directly;
   the three public accessors that once wrapped them had no callers and
   were removed in an earlier cleanup pass.
5. **Auto-refresh on success.** `AnniRsvpCommand.renderAck` fires
   `AnniQueryClient.query()` after a successful ack. Without this,
   `/wv anni` reads the cached snapshot, which the push poller's cadence
   was meant to keep at most 5 minutes stale outside the T-2h hot window.
   A player with no authenticated outbound socket (no key, or tier
   `other`) gets no `anni_state` push, so for them it would stay stale until
   the next pull. User reported the
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
/ vets-anni). vets-anni lists every party host in `organisers`, so the
gate, together with the ±2 h stamp window, is a cheap client-side
pre-filter that skips parties with no organiser in them. vets-anni itself
upgrades a member to `ONLINE_PARTY` only when the leader reported for that
member is their assigned host.

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
   `AnniWsSender.sendAnniPartyObservation(members, leader, world)`.
   Pure predicate `shouldSend(snap, anniSnapshot, stamp, now)` is the
   testable core; legacy `vetsConnected` / `tier` parameters are gone.

2. **Mid-window snapshot trigger** —
   [`AnniPartyReporter`](../src/client/java/org/wynnvets/mwe/anni/AnniPartyReporter.java)
   subscribes to `AnniSnapshotCache` and calls
   `PartyRosterListener.requestRecapture()` when the pure
   `AnniPartyReporter.shouldRecapture(prev, next, wasInWindow, nowInWindow)`
   says so: on any change to the lowercased `organiser_usernames` set, or when
   `PartyRosterListener.inActiveWindow(stampEpoch, now)` goes from false to
   true between two snapshot updates, the window opening. That handles "anni
   opens while parked in a static party for 30 min", where no `PartyEvent`
   would fire on its own, including a set that was already complete before the
   window opened. The window is read only when a snapshot arrives and
   `AnniSnapshotPoller` runs from T-90m, so the edge fires on the first update
   after T-2h, at about T-90m unless an earlier pull lands. It fires once per
   opening, not on every update inside the window.
   `AnniPartyReporter.register()` is called from `VetsmodClient.onClientStarted()`,
   the bootstrap's `CLIENT_STARTED` phase, beside the other Wynntils-facing
   registrations. Its listener reaches `Models.Party` only through
   `PartyRosterListener.requestRecapture()`, which hands the read to a
   render-thread task, and snapshots arrive only over the sockets, which open at
   a Wynncraft join, or from a `/wv debug tree anni` command, both after
   `CLIENT_STARTED`. So no snapshot reaches it before
   Wynntils' entrypoint has run, in either phase ([project_wynntils.md](project_wynntils.md)
   §"Init order between vetsmod and Wynntils").

### What got deleted

The legacy `party_status` machinery is excised in full. On the vetsmod
side that is `V1ApiManager.sendPartyStatus`, the cohort-gate parameters,
the `OnlineMemberService.refreshAsync()` call site, the `VETS_TIERS`
constant, and the TODO comment that envisioned this exact swap. The
temp-server and vets-anni halves are listed in
[`vets-anni/.claude/snapshot_integration.md`](../../vets-anni/.claude/snapshot_integration.md).

### Implementation notes

- **Wire format is `{type: "anni_party_observation",
  party_member_usernames: [str], leader_username, world}` — names over
  the wire, vets-anni does the resolution.** *Note: this intentionally
  does NOT use the `[{uuid, username}]` shape — Wynntils'
  `PartyModel.getPartyMembers()` returns `List<String>` of usernames
  only, and Wynncraft's tab list is fake (80 sentinels per
  `TabListGuildParser`), so client-side name→UUID resolution would drop
  30-60% of members.* The client never supplies `observer_mc_uuid`;
  temp-server stamps it from the session when it forwards the
  observation to vets-anni.
- **`AnniPartyReporter` is a thin snapshot listener, NOT a wrapper
  around the send call.** `PartyRosterListener.flush()` calls
  `AnniWsSender.sendAnniPartyObservation` directly — same shape as
  the other `sendAnniX` methods. Wrapping that call in
  `AnniPartyReporter` would be pure indirection.
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
  cache holds only temp-server's real stamp, which an inject never
  touches, so debug-inject (which populates only `AnniSnapshotCache`)
  would fail the gate whenever no real anni is within two hours.*
  General rule: any new code reads the active stamp from the
  snapshot, not from the legacy poller.

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
`ConcurrentLinkedDeque`. "Single-flight" is this repo's name for that FIFO of
per-call futures; it does not coalesce concurrent calls (each call sends its own
frame). The `orTimeout` deadline is **8 s on all three**. On the two ack
clients that is about 3 s over temporary-server's 5 s httpx timeout for the
forward to vets-anni, which bounds each phase of the request rather than the
whole of it.
Each client returns the `exceptionally` stage it derives from `orTimeout`, so
all three resolve `null` at the deadline rather than completing exceptionally.
The `exceptionally` handler removes *that* future by identity
(`remove(future)`) — **not** the head. Head-polling (`pollFirst()`)
belongs to the response path; cloning "removes the head" into a timeout
handler would drop an unrelated in-flight future. The party-observation report is fire-and-forget (no
ack is needed because vetsmod doesn't render anything from the
response), so it skips the single-flight client and just enqueues the
frame send via `AnniWsSender.sendAnniPartyObservation(...)`.
**Don't add an `AnniPartyReporterClient` unless an ack arrives that
the client needs to react to.**

**AnniWsSender send template** — four clones (`sendAnniQuery`,
`sendAnniScrollspotSet`, `sendAnniRsvp`, `sendAnniPartyObservation`).
Build `{"type":"...",...}` JSON, hand it to
`V1ApiManager.sendInboundIfConnected(payload)` (one read of the socket for
the check and the send), return its bool.
Mirror.

**`AnniWsHandler.onInbound` demux** — four routes; add a fifth if a
new frame type needs a vetsmod-side consumer.

### What carries forward to future MWEs

- **`AnniPartyReporter`'s snapshot-listener pattern** generalises to
  any future MWE that needs "fire a back-report when X organiser-like
  state appears". Don't duplicate the listener bus; subscribe and
  diff.

## Anni zone

`AnniZone` (`zone/AnniZone.java`) — JSON fetcher for `https://api.wynncraft.com/v3/map/world-events`, looks up event `a63b2c02`, parses its `location[].event.{x,z}` into disc centres. 60s fixed-rate refresh on a daemon thread, with an immediate initial pull, started from `VetsmodClient`. Stale-fallback covers *every* failure path — a non-200, a 200 with no `a63b2c02` entry, and any exception all keep the previous centres. Cold-fallback returns `isInZone() = false` (so the boss-bar countdown variant defaults to the assigned/seeking text rather than a false-positive zone claim).

`AnniZone` holds **its own** `HttpClient` in a `static final` field, not the mod-wide shared one from `org.wynnvets.util.HttpClients` — see [vetsmod_networking.md §3](vetsmod_networking.md). Its `HTTP_TIMEOUT_SECONDS = 10L` drives both the connect timeout and the request timeout, and it pins no HTTP version, so the world-events endpoint negotiates HTTP/2; the shared client is 5 s and `HTTP_1_1`, so adopting it would change both. The field is `static final` rather than built inside `refresh()`, which is where it used to be — a fresh connection pool and selector thread on every 60 s tick. One consequence of the hoist: a construction failure is now an `ExceptionInInitializerError` in `<clinit>` rather than a caught debug line with the previous centres retained, and `isInZone` is on the tick path.

Live API shape (probed 2026-06-16): `location: [{event: {x:315, y:31, z:-1291}, spawn: {x:350, y:29, z:-1291}, reward: {x:348, y:29, z:-1292}, radius: 48}]`. Spec mandates 48-block radius; we hard-code the constant in case the API drifts. Squared-distance comparison, no `Math.sqrt`.

Downstream consumers reuse this same fetcher: the outline gate (`AnniOutlineTicker`, via `forceInZone || isInZone`), the ghosts prompt (`GhostsPromptHandler`), aggressive-mode alerts (`AggressiveAlertDispatcher`), and the boss bar itself (`VetsBossBarManager`'s activation gate plus `VetsBossBarContentBuilder`'s countdown gate) — all via `isInZone`; plus `AnniZoneLineRenderer` via `getDiscs()`, and the debug dump via both. ⚠️ `isCold()` has **no behavioural consumer at all** — the zone dump is its only caller anywhere. Its own Javadoc used to offer it to "S4/S5 consumers" for gating stronger overrides on a warm cache; no outline or ghosts-prompt class ever took it up, and that sentence has been corrected at the source.

The scroll waypoint does **not**. `ScrollSpotMarkerProvider` never references `AnniZone` at all: its position comes from `board.party.scroll_spot`, falling back to the hard-coded `345 45 -1315` **only while the player is in a party** — `computeEntry` returns null on a null `board.party()` before it reads the spot — and its gate is `AnniAggressiveTicker.isAggressiveActive()`. ⚠️ **That precondition is the marker's, not the literal's.** `AggressiveAlertDispatcher.fireZoneReadinessAlert` computes the same fallback with a plain `spot != null ? … : "345 45 -1315"`, so the T-5m chat alert *does* print those coordinates to a player with no party. Do not generalise "only in a party" beyond the waypoint.

## Config keys

One table, every key. `vetsAnniOutlinesEnabled` and `vetsAnniNametagsEnabled` together gate whether the S4 highlight pass runs (at least one must be on); `vetsAnniNametagsEnabled` then gates its own nametag-recolour branch, and `vetsAnniOutlinesEnabled` gates the registry-tier glow and the outsider-outline suppression; the last four gate the §Aggressive mode components.

| Key | Type | Default | Purpose |
|--|--|--|--|
| `vetsAnniMode` | string | `silent` on disk, but `AnniModeManager.applyStartupDefaultIfNeeded` re-applies the eligibility default (`passive` while enrichment-eligible, else `silent`) for a user who never chose a mode, at world-join and when eligibility changes (see §Mode state) | Active anni mode. Not settable via `/wv config` — see §Mode state |
| `vetsAnniRoleStyle` | string | `descriptive` | Role-naming style (`descriptive`/`short`/`formal`) — boss bar always uses `short` |
| `vetsAnniShowHoverDetails` | bool | true | Populate hover tooltips on chips |
| `vetsAnniPromptRsvp` | bool | true | Show line 2 of anni-motd. Its only reader is `AnniMotdRenderer.render` — it does **not** gate the `[Hard]`/`[Soft]` pills on `/wv anni`, which `AnniStatusSections.rsvpSection` emits from `!rsvped && farOut` with no config read |
| `vetsAnniShowPrediction` | bool | true | Show `\guess`-style prediction in `/wv anni` not-announced |
| `vetsAnniBossbarEnabled` | bool | true | Master kill-switch for the synthetic boss bar. Only consulted in passive/aggressive. |
| `vetsAnniFlashIntensity` | string | `normal` | `subtle`=5s / `normal`=10s / `strong`=20s flash duration per field change. |
| `vetsAnniFlashSound` | bool | true | Whether a field change while the bar is up (or a debug `flash`, in any mode) plays `SoundEvents.NOTE_BLOCK_PLING` (the Wynntils chat-mention ding) twice, at full volume and about 300 ms apart. Concurrent changes collapse to one 2-ping burst. |
| `vetsAnniOutlinesEnabled` | bool | true | Counts toward the gate's at-least-one-toggle condition in `AnniOutlineTicker.gateHolds`, so it is read even when the gate then fails, and gates two behaviours: `AnniOutlineTicker`'s `setGlowColor` for registry members, and `EntityOutlineColorMixin` zeroing an outsider's `state.outlineColor`. It does not gate `EntityGlowingMixin`, which acts on any non-`NONE` glow colour. With this off and `vetsAnniNametagsEnabled` on, registry members get no tier glow, and outsiders that vanilla already renders as glowing keep their native team outline instead of having it zeroed |
| `vetsAnniNametagsEnabled` | bool | true | Nametag overlay (matching colour scheme). Separable from outlines so users can pick one half. `NametagMixin`'s anni branch reads this key, so turning it off with `vetsAnniOutlinesEnabled` on drops the nametag recolour; with this on and `vetsAnniOutlinesEnabled` off, registry members keep the recoloured nametag and get no tier glow. |
| `vetsAnniZoneLines` | bool | true | `AnniZoneLineRenderer`'s cylinder cage around each anni disc. |
| `vetsAnniScrollWaypoint` | bool | true | `ScrollSpotMarkerProvider`'s dark-red beacon on the party's scroll spot: the host-pinned one, or the fixed default until the host pins one; no marker outside a party. |
| `vetsAnniChatAlerts` | bool | true | `AggressiveAlertDispatcher`'s per-field diff alerts and the two readiness alerts. |
| `vetsAnniGhostsPrompt` | bool | true | `GhostsPromptHandler`'s rising-edge zone-entry prompt. |

Internal, not user-facing:

- `vetsAnniGhostsPromptShownForStamp` (string, default `""`) — persists the stamp_epoch the ghosts prompt last fired for, and only on the *ambiguous* (no visible ghost) branch; the ghost-confirmed branch prompts on every zone rising edge and writes nothing. Survives restarts.
- `vetsAnniModeUserSet` (bool) and `vetsAnniUserMode` (string) — the remembered explicit pick; see §Mode state.

## Debug harness

All under `/wv debug tree anni …` (the `tree` literal nests subsystem-specific debug trees so top-level `/wv debug` stays generic). `AnniDebugCommands.buildCommandTree` registers **11 top-level literals** and **24 executable leaves**. A node typed without the argument it needs, such as a bare `registry clear`, prints a red usage line instead of going to the server as "Invalid command"; 18 nodes carry one. A bare container such as `registry` that needs a subcommand still goes to the server.

**`snapshot`** — cache manipulation.
- `snapshot inject preset <name>` — loads from `src/client/resources/assets/vetsmod/anni_test_snapshots/<name>.json`. Preset name auto-sets the external override (`external_*` → forced external, `member_*` → forced vets, others → auto).
- `snapshot inject file <name>` — loads from `vetsmod/dumps/anni/<name>.json`.
- `snapshot inject <json>` — raw inline. Brigadier resolves a literal child ahead of an argument sibling whatever the registration order (`CommandNode.getRelevantNodes` returns the matching literal alone), so `inject file …` and `inject preset …` never reach the greedy inline form.
- `snapshot dump` — write current cache to `vetsmod/dumps/anni/snapshot-<ts>.json`.
- `snapshot clear` — set cache to null.
- `snapshot refresh` — pull fresh from server (alias for the auto-pull path).

Every inject, and `time`, holds `AnniSnapshotPoller`'s scheduled pull (`setDebugHold`), so an injected in-window snapshot is not overwritten 30 s later. `snapshot clear`, `snapshot refresh` and `guess` release the hold, and so does `/wv debug false`; other pulls, such as the post-connect re-pull, still replace an injected snapshot, and leave the hold standing until one of those releases it.

**Single-purpose literals.**
- `guess` — pull + print fishbot-style one-liner (announced stamp + countdown OR prediction window). The pull goes through `AnniQueryClient.query()`, whose response handler pushes any snapshot it gets into `AnniSnapshotCache`. So, like `snapshot refresh`, a successful `guess` replaces an injected fixture. On a failed pull it prints the cached snapshot, flagged stale, if one is cached; otherwise it prints a red `anni guess: no snapshot returned`.
- `time <seconds>` — round-trip the cached snapshot through JSON and rewrite the `event` block. It does **not** preserve everything else. For a positive argument it sets `stamp_epoch = NOW + seconds`, forces `announced = true`, and nulls `prediction`. For `seconds <= 0` it nulls `stamp_epoch` and sets `announced = false`, leaving `prediction` alone — so the suggested `-60` (nominally 1m ago) cannot produce a stamp one minute in the past.
- `external <auto|true|false>` — override `isExternal` for testing.
- `zone <enter|exit>` — sets/clears `AnniOutlineTicker.setForceInZone(...)`, so dev sessions can verify the highlight overlay without flying to the anni location. It affects the **highlight gate only**. The boss bar, the ghosts prompt and the T-5m zone-readiness alert call `AnniZone.isInZone` directly and never see the override. The zone lines and scroll waypoint make no zone-presence check at all: the zone lines gate on `AnniAggressiveTicker.isAggressiveActive()` plus `vetsAnniZoneLines` (they read the cached discs' centres and radii via `AnniZone.getDiscs()`, and cull by distance to a centre rather than by containment), and the waypoint on a non-null entry plus `isAggressiveActive()` plus `vetsAnniScrollWaypoint`.
- `flash <role|party|world|rsvp>` — force a `FlashTracker` pulse on the named field (10s at the `normal` default; the sound plays only when `vetsAnniFlashSound` is on, but even with the bar down). The pulse is rendered by the synthetic boss bar alone, so it is invisible unless the bar is currently active. `world` is the exception — it has no timed window, so forcing it lasts until the next tick recomputes the real mismatch.
- `mode set <silent|passive|aggressive>` — `AnniModeManager.transitionTo(..., DEBUG_BYPASS_MUTEX)`. Skips the `/stream` mutex so we can verify rendering during screen capture.
- `alert <role|world|party|rsvp|zone|world_ready>` — synthesise a chat alert without contriving a diff or waiting for a T-N boundary.

**`scrollspot`** — the only subtree with a second gate (`requireStaffOrOrganiser`). All five leaves are gated identically even though only the first three write to the server.
- `scrollspot set <x> <y> <z>` — host write via WS frame (real action; vets-anni 403s unless you're the host of your assigned party).
- `scrollspot here` — host write using your current block-pos.
- `scrollspot clear` — host write that clears the party's spot.
- `scrollspot localinject <x> <y> <z>` — local-only paint into the marker provider; no server round-trip. For visual testing of the waypoint render without coordinating a host.
- `scrollspot localclear` — clear the local marker, whether it came from `localinject` or from the latest snapshot; no server round-trip, and the next snapshot update recomputes it.

**`rsvp`** — debug mirror of the main brigadier `/wv anni rsvp`. Identical effect: both paths call `AnniRsvpCommand.hard|soft|revoke`, and the debug path adds only the `requireDebug` check. It is the debug tree's only mirror. The scrollspot debug-tree node is **not** a mirror: `scrollspot` has no main-brigadier registration, so the debug tree is its only entry point (locked decision 6 above).
- `rsvp hard` / `rsvp soft` / `rsvp revoke`.

**`registry`** — arbitrary `AnniOutlineRegistry` injection, for verifying the highlight and nametag branches without coordinating a real anni party. Wiped by the next snapshot rebuild, so re-inject after any `snapshot inject` / `snapshot clear`.
- `registry set <username> <role>` — `role` may be a role code or `other`, which routes to the grey other-vets-party tier.
- `registry clear <username>` / `registry clearall`.

All gated by `VetsLogger.isDebugEnabled()` (`/wv debug true`).

### `/wv debug trigger …` dumps

A flat trigger family beside the `tree` subtree. **These are ungated** — unlike every `tree anni` leaf, they run for anyone who types them. Three of the five print to chat and two do not: `bossBarsDump`, `nametagsDump` and `zoneLinesDump` go to chat via `ChatUtils.sendLocalMessage*`, while `ghostsPromptDump` and `rsvpDump` write to the log via `VetsLogger.info` — check `latest.log`, not chat.

- `bossBarsDump` — dumps vanilla's `BossHealthOverlay#events` map with per-bar `UUID/color/overlay/progress/name`. Was the smoking gun for the PINK-colour suppression — the dump immediately revealed every Wynncraft text-only bar shipped `color=PINK overlay=PROGRESS`. Useful for any future "why doesn't my bar render" question.
- `nametagsDump` — walks `level.players()` and reports each player's username, `AnniOutlineRegistry` hit/miss, tier, role, `original=` display name, and the `ChatFormatting` the `NametagMixin` would resolve to right now, plus a header line of `outlineSuppressionActive` / `vetsAnniNametagsEnabled` / `vetsAnniOutlinesEnabled`. Use whenever a nametag colour doesn't match what you expect; the dump's `→ <username> (<colour> via registry)` segment shows what should render in-world. Its `original=` column is the player's `getDisplayName()` string (the component vanilla copies into `state.nameTag`) with each `§` shown as `&`, so the embedded leading team-colour code reads as `&a`. It never reads render state.
- `ghostsPromptDump` — dump `aggressive_active / toggle_on / in_zone / stamp / shown_for` + per-player `isPlayerGhost` result + `would_fire_on_rising_edge`.
- `zoneLinesDump` — dump aggressive gate state + every cached `AnniZone.Disc` + squared distance to the player. Diagnostic for "why aren't lines rendering."
- `rsvpDump` — dump auth state, in-flight queue depth, last attempt/ack, and the snapshot's `rsvp` block. It calls `AnniRsvpClient.debugDump()`, which reads the private statics directly.

### Template substitution in fixtures

Every `snapshot inject` path — preset, file and inline — runs through `substituteNowTokens`; it is the first step of `parseAndInject`. Tokens replaced with the current epoch-seconds:
- `"{NOW}"` → integer
- `"{NOW+72h}"`, `"{NOW-30m}"`, `"{NOW+1.5s}"` → integer

So fixtures stay valid across long stretches of time. Use it for any new preset that includes an epoch.

### Bundled presets

Listed in `AnniDebugSuggestions.PRESETS`.

| Preset | Branch tested |
|--|--|
| `empty` | Known player, no `AnniEvent` on file — `event` and `rsvp` null, unregistered, unplaced, band 1 |
| `external_no_anni` | External + not announced (lean external render) |
| `member_no_anni` | Vets + not announced + registered with roles (all-set affirmation) |
| `member_no_anni_fill` | Vets + not announced + registered but no specific roles (4-line fill explainer) |
| `member_no_anni_unregistered` | Vets + not announced + registered=false (red unregistered nudge) |
| `member_announced` | Vets + announced (NOW+8h) + unassigned (attendance bar) |
| `member_in_party` | Vets + announced (NOW+8h) + party (ASSIGNED + party block) |

All seven are `schema_version: 1` with no `event.all_parties`. `member_in_party` still covers the own-party role colours (six members, one per role, via the v1 `board.party.members` field) and, by registry miss, the outsider branch; only the *other-vets-party* grey tier is unreachable from a preset. Reach that one with `/wv debug tree anni registry set <username> other`, plus `zone enter`, a non-silent mode, and a cached stamp inside the hot window (`AnniWindows.inHotWindow`, T-2h to T+30m). No preset supplies one: the two announced presets sit at `NOW+8h` and the rest carry no stamp. The stamp requirement also applies to seeing `member_in_party`'s role colours in-world. So inject a preset, run `time`, and only then `registry set`: `time` goes through a cache update, and the registry's rebuild drops any injected entry. Any offset works: `time`, like every inject, holds `AnniSnapshotPoller`'s scheduled pull until `snapshot clear`, `snapshot refresh`, `guess` or `/wv debug false`. Any other pull that returns a snapshot still replaces the cache, and through the same rebuild the injected entry, such as the post-connect re-pull after a reconnect or the refresh after an in-game RSVP. An `anni_state` push would too, when the outbound socket is authenticated.

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
  `_post_public` guards it itself: it returns early on `bot is None` (and on an
  unset channel id or a `None` channel) since vets-anni `3e1aed4`, and
  `execute_uuid_rsvp` no longer guards it one level up.
  ⚠️ `anni_ping_poller.py` does **not** use this reach-out at all: the
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
