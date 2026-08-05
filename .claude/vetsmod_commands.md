---
name: vetsmod /wv Command Reference
description: Complete /wv command tree — subcommands, permissions, handlers, chat-command mixins (/unlock, /g/wg/v)
type: project
originSessionId: dc63f47a-2d15-4f8d-9b6a-41d3049f0cc2
---
# vetsmod Commands Reference

Commands are split between `/wv` Brigadier-registered subcommands (via `CommandRegistry`) and intercepted chat commands (via mixins).

## 1. /wv subcommand tree

Entry point: [src/client/java/org/wynnvets/commands/CommandRegistry.java:46-143](src/client/java/org/wynnvets/commands/CommandRegistry.java#L46-L143)

**Permission predicates** (currently all-true TODOs at lines 148 and 153):
- `userIsCaptain()` → true
- `userIsVet()` → true

**Handlers are stored in:** `CommandRegistry`, `ConfigCommands`, `HelpCommands`, `DebugCommands`, and on-demand fetchers under `fetcher/ondemand/`.

### /wv help [<subcommand>]
No permission. [HelpCommands.java:22-297](src/client/java/org/wynnvets/commands/HelpCommands.java#L22-L297). Subcommands: `config`, `check`, `return`, `staff`, `list`, `motd`, `anni`, `line`, `debug`, `debug set`, `debug trigger`.

### /wv check <playerName>
Captain+. [CommandRegistry.java:159-182](src/client/java/org/wynnvets/commands/CommandRegistry.java#L159-L182). Delegates to `UserInfoFetcher.checkUser()` which chains Mojang UUID → WynnCraft profile → Returners roster membership.

### /wv return
Vet/Returners. [CommandRegistry.java:261-264](src/client/java/org/wynnvets/commands/CommandRegistry.java#L261-L264). GETs `VetsApi.RETURN`, shows latest return-channel post.

The post is authored in Discord, so the body arrives with Discord's `<t:EPOCH:STYLE>` timestamp markup intact — temporary-server's markdown converter passes it through untouched. [`DiscordTimestamps.expand()`](src/client/java/org/wynnvets/chat/DiscordTimestamps.java) rewrites it into the reader's own timezone client-side (that's the whole point of the markup — the API can't know the zone), supporting all seven Discord styles with `f` as the default, and hangs a hover carrying the absolute time, its zone, and the relative offset off each expansion. Malformed or out-of-range epochs fall through as the raw markup.

[`ReturnFetcher.announcementFooter()`](src/client/java/org/wynnvets/fetcher/ondemand/ReturnFetcher.java) then appends `For more info, click here for the announcement post!`, where `here` deep-links to the return channel (`discord.com/channels/<guild>/<RETURN_CHANNEL_ID>`). The API serves the message body with no message ID, so the link targets the channel rather than the post. Skipped when the body is blank (nothing cached upstream).

### /wv list [world]
Vet. Two variants:
- `/wv list` — [CommandRegistry.java:222-230](src/client/java/org/wynnvets/commands/CommandRegistry.java#L222-L230), triggers `ListFetcher.fetchList()`: online members grouped by tier, staff underlined, supporters glint.
- `/wv list world` (Captain+) — [CommandRegistry.java:232-254](src/client/java/org/wynnvets/commands/CommandRegistry.java#L232-L254), triggers `WorldListFetcher.fetchWorldList()`: dispatches `/find` for each online member, groups by region (GeoIP2 codes: AF/AS/EU/NA/OC/SA/Other/Private).

### /wv staff
Unlocked. [CommandRegistry.java:209-220](src/client/java/org/wynnvets/commands/CommandRegistry.java#L209-L220). GETs `VetsApi.STAFF`, shows online staff sorted by rank → alpha.

### /wv motd
Vet. [CommandRegistry.java:184-202](src/client/java/org/wynnvets/commands/CommandRegistry.java#L184-L202). Returns guild MOTD (`VetsApi.GUILD_MOTD`) for Returners/waitlist/honourary, else plain MOTD.

### /wv anni [silent | passive | aggressive]
Public. [CommandRegistry.java](src/client/java/org/wynnvets/commands/CommandRegistry.java). Dispatches via [StampFetcher.fetchStampAndCreateAnniCommandMessage()](src/client/java/org/wynnvets/fetcher/ondemand/StampFetcher.java):

- **Snapshot-driven (S2):** when `vetsAnniEnabled` is on and `AnniSnapshotCache.latest()` is non-null, renders the rich multi-section view via [AnniCommandRenderer](src/client/java/org/wynnvets/mwe/anni/render/AnniCommandRenderer.java). Three sub-branches:
  - *Not announced* — `\guess`-style prediction window (earliest/median/latest), registration status, role chips or registration nudge. Prediction gated by `vetsAnniShowPrediction`.
  - *Announced 2h+ out* — relative countdown, role chips, RSVP widget, attendance bar, board state. When assigned to a party also surfaces party ordinal, role, world, host.
  - *Announced within 2h* — compact status row + `[silent] [passive] [aggressive]` suggest-command widget. The mode literals are S2 stubs (real wiring in S3).
- **Legacy fallback:** when `vetsAnniEnabled` is off or no snapshot is cached, GETs `VetsApi.STAMP` (Unix seconds) and formats the original countdown. Less than 1 hour: red `Annihilation is in X mins!`; 1+ hours: `Annihilation returns in X hours Y mins!`; past: `The time for the next annihilation has not yet been announced`.

`silent`/`passive`/`aggressive` — route through [`AnniModeManager.transitionTo`](src/client/java/org/wynnvets/mwe/anni/mode/AnniModeManager.java). Refused when `Models.StreamerMode.isInStream()` OR the chat-line [`StreamerModeChatDetector`](src/client/java/org/wynnvets/mwe/anni/mode/StreamerModeChatDetector.java) signals stream-on (spec §3.1: silent is the ONLY mode allowed with `/stream`). Successful transitions update `vetsAnniMode`; the [`VetsBossBarManager`](src/client/java/org/wynnvets/mwe/anni/bossbar/VetsBossBarManager.java) tick-loop picks up the change on the next tick.

### /wv anni rsvp {hard | soft | revoke}
S6. Authenticated only (must have run `~vetsmod`). [AnniRsvpCommand.java](src/client/java/org/wynnvets/mwe/anni/command/AnniRsvpCommand.java). In-game RSVP — byte-equivalent to a Discord `\rsvp hard|soft|revoke`: same Rsvp row, same auto-placement into Unassigned, same `RSVP_CHANNEL_ID` public confirmation.

- `hard` — commit to attending. Boss-bar / `/wv anni` render switches to HARD chip (aqua).
- `soft` — tentative. SOFT chip (green).
- `revoke` — withdraw. Soft-deletes (`Rsvp.revoked_at`) and posts `<user> withdrew their RSVP.` to RSVP_CHANNEL_ID. Silent (no public post) if there was no active RSVP to withdraw.

Lights up the `[Hard]` / `[Soft]` `SuggestCommand` buttons in [AnniCommandRenderer.rsvpUpgradePrompt](src/client/java/org/wynnvets/mwe/anni/render/AnniCommandRenderer.java) — those buttons emit `/wv anni rsvp hard` / `/wv anni rsvp soft` verbatim, so user click-through now resolves.

Trust chain: vetsmod sends `anni_rsvp` ([`V1ApiManager.sendAnniRsvp`](src/client/java/org/wynnvets/api/V1ApiManager.java)) → temp-server's [`_handle_anni_rsvp`](../../temporary-server/app/chat/inbound.py) stamps the session's `mc_uuid` as `actor_mc_uuid` → vets-anni's [`POST /api/internal/anni-rsvp-by-uuid`](../../vets-anni/app/web/routers/anni_internal.py) calls [`execute_uuid_rsvp`](../../vets-anni/app/domain/rsvp_by_uuid.py) which reuses the Discord cog's `_auto_place_after_rsvp` / `_broadcast_board_snapshot` / `_post_public` helpers verbatim. Ack flows back as `anni_rsvp_response` (routed to [`AnniRsvpClient`](src/client/java/org/wynnvets/mwe/anni/network/AnniRsvpClient.java)'s pending future; 5s timeout).

Unauthenticated message uses spec wording: `Use \rsvp on discord — or run ~vetsmod first.` T-90 cutoff is enforced server-side (revokes are unaffected; vets-anni surfaces `RSVP is closed (within 90 min of anni)` for hard/soft attempts inside the cutoff).

### /wv anni scrollspot {set <x> <y> <z> | here | clear}
S5. Authenticated only (must have run `~vetsmod`). [AnniScrollspotCommand.java](src/client/java/org/wynnvets/mwe/anni/aggressive/AnniScrollspotCommand.java). Per-party host pins (or clears) the in-game scroll-spot coordinate; visible to all party members through `board.party.scroll_spot` on the next snapshot push.

- `set <x> <y> <z>` — pin explicit coords.
- `here` — pin the player's current block position.
- `clear` — remove the spot.

Trust chain: vetsmod sends an `anni_scrollspot_set` inbound frame ([`V1ApiManager.sendAnniScrollspotSet`](src/client/java/org/wynnvets/api/V1ApiManager.java)); temp-server's [`_handle_anni_scrollspot_set`](../../temporary-server/app/chat/inbound.py) reads the session's MC UUID and forwards as `actor_mc_uuid` to vets-anni's [`POST /api/internal/anni-party-scrollspot`](../../vets-anni/app/web/routers/anni_internal.py). vets-anni rejects unless the actor is the host of their currently-assigned party; the client-side `isAuthenticatedThisSession()` check is UX, not security. Ack flows back as `anni_scrollspot_response` (routed to [`AnniScrollspotClient`](src/client/java/org/wynnvets/mwe/anni/network/AnniScrollspotClient.java)'s pending future; 5s timeout); failures surface the server's `detail` string verbatim (e.g. `Scroll spot rejected: only the party host can set scroll_spot`).

### /wv config [<key> [<value>]]
No permission. [ConfigCommands.java:78-347](src/client/java/org/wynnvets/commands/ConfigCommands.java#L78-L347). Three forms:
- `/wv config` — list all `VetsConfig.USER_CONFIG_KEYS` with current values
- `/wv config <key>` — show current value
- `/wv config <key> <value>` — persist

Suggestion providers (ConfigCommands.java:27 and :38): colour names via `VetsConfig.getColorNames()`, sprite names (10 total), opacity int values `reset|0|25|50|69|75|100`, tri-state `default|true|false`. Validation via `VetsConfig.isValidColor()`, `isValidSprite()`.

### /wv line <church|scrap>
Returners. [CommandRegistry.java:270-280](src/client/java/org/wynnvets/commands/CommandRegistry.java#L270-L280). Delegates to `TerritoryLineManager.toggle(alias)`. Requires `areFeaturesEnabled()` (Returners only).

Aliases → territories:
- `church` → Forest of Eyes
- `scrap` → Corkus Sea Cove

### /wv debug [...]
Public. Tree built in [DebugCommands.java:141](src/client/java/org/wynnvets/debug/DebugCommands.java#L141):
- `/wv debug` — dumps diagnostics (`DiagnosticsHandler.execute()`)
- `/wv debug true|false` — toggle debug logging (persists 3 days via `VETS_DEBUG_ENABLED_AT`)
- `/wv debug set` — list debug config keys
- `/wv debug set <key> [value]` — get/set debug key (e.g. `itemDump`)
- `/wv debug trigger charDump` — render PUA glyphs `\uE001-\uE040` in `chat/prefix` font, 8 per line
- `/wv debug trigger forceChecks` — force guild/rank/staff re-check via `GuildStateManager.forceGuildRecheck()`
- `/wv debug trigger tabDump` — `TabDumpHandler.execute()`
- `/wv debug trigger rsvpDump` (S6) — dump `isAuthenticatedThisSession()` + `AnniRsvpClient.pendingCount()` + `lastAttemptedNotice` + `lastAck` + current snapshot `rsvp` block. Diagnostic for "why did my `/wv anni rsvp` not land".
- `/wv debug tree anni rsvp {hard|soft|revoke}` (S6) — debug mirror of the main `/wv anni rsvp` tree; identical effect, gated on `requireDebug` only (action only touches the caller's own RSVP, no staff/organiser perm needed).

## 2. Chat command mixins

Target `ClientPacketListener.sendCommand(String)`; HEAD + cancellable.

### UnlockCommandMixin
[src/client/java/org/wynnvets/mixin/client/command/UnlockCommandMixin.java](src/client/java/org/wynnvets/mixin/client/command/UnlockCommandMixin.java)

Intercepts `/unlock <key>`. The "key" is a 43-character URL-safe base64 bearer token (32 bytes of entropy via `secrets.token_urlsafe(32)`) issued by dazebot's `/vetsmod` Discord command.

Flow:
1. Mixin cancels the server-bound packet.
2. Delegates to `GuildStateManager.tryUnlock(key)` → `UnlockManager.tryUnlock(key)`.
3. Local checks: length ∈ [32, 200], charset is URL-safe base64 (`a-zA-Z0-9_-`).
4. Persists to `vetsAuthKey` and dispatches an `auth` frame on the inbound WS via `V1ApiManager.sendAuth(key)`.
5. Player sees `⏳ Saved your vetsmod key. Verifying with the server…` (yellow).
6. Server's auth-frame ack lands asynchronously → `GuildStateManager.onAuthSuccess(tier)` shows `✅ vetsmod authentication verified — tier: <tier>` (green) **at most once per error→recovery cycle**, gated by the persisted `printSuccessfulAuth` flag (defaults `true`; latched to `false` after a successful render; reset to `true` by `onAuthFailure`). Display path: **action bar** during normal in-world play, **chat fallback** when a `Screen` is open at dispatch time (Wynncraft's class-selection screen on login is the canonical case — action-bar overlays are suppressed under screens but chat renders through). `onAuthFailure(detail)` always displays `❌ vetsmod authentication failed: <reason>` (red) **in chat** (it carries an actionable Discord URL that the action bar would truncate). Manually re-show the next ack with `/wv config printSuccessfulAuth true`.

The legacy SHA-256 password matching has been removed. Users with stored `vetsWaitlistUnlockTime` / `vetsHonouraryUnlockTime` markers from before the migration get a session-start warning prompting them to run `/vetsmod` in Discord — see [vetsmod_guild_system.md §5](vetsmod_guild_system.md#5-sessionauthwarning).

### GuildChatCommandMixin
[src/client/java/org/wynnvets/mixin/client/chat/GuildChatCommandMixin.java:18-27](src/client/java/org/wynnvets/mixin/client/chat/GuildChatCommandMixin.java#L18-L27)

Routes `/g`, `/wg`, `/v`, `/msg` through `GuildChatDispatcher.intercept(command)`. Staff `/v` goes through `CommandDispatcher` → `MessageFanoutDispatcher` for fan-out to online staff with 🔐 lock prefix.

## 3. Suggestion providers

`ConfigCommands.SUGGEST_CONFIG_KEYS` (line 27) and `SUGGEST_CONFIG_VALUES` (line 38) provide Brigadier tab completion. Tri-state keys suggest `default|true|false`. Int keys (opacity) suggest `reset|0|25|50|69|75|100`. String keys suggest from `VetsConfig.getColorNames()` or the 10 sprite names.

## 4. Things to know when adding commands

- Register new subcommand inside `CommandRegistry.register()` using Brigadier literal/argument pattern.
- Gate with a permission predicate from `CommandRegistry` (currently all return true — future work).
- Avoid heavy work on the main thread; use `CompletableFuture` from `HttpClient`.
- Chat output: use `ChatUtils.dispatchToChat()` (thread-safe, sets INTERNAL_CHAT_DISPATCH ThreadLocal).
- Tab completion: use `SuggestionProviders` or `SuggestionProvider`s from `ConfigCommands` as templates.
