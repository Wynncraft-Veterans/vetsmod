---
name: vetsmod /wv Command Reference
description: Complete /wv command tree — subcommands, permissions, handlers, chat-command mixins (/unlock, /g/wg/v)
type: project
originSessionId: dc63f47a-2d15-4f8d-9b6a-41d3049f0cc2
---
# vetsmod Commands Reference

Commands are split between `/wv` Brigadier-registered subcommands (via `CommandRegistry`) and intercepted chat commands (via mixins).

## 1. /wv subcommand tree

Entry point: [CommandRegistry.register()](../src/client/java/org/wynnvets/commands/CommandRegistry.java)

**Permission predicates** (currently all-true TODOs):
- `userIsCaptain()` → true
- `userIsVet()` → true

**Handlers are stored in:** `CommandRegistry`, `ConfigCommands`, `HelpCommands`, `DebugCommands`, `DistributeCommands`, and on-demand fetchers under `fetcher/ondemand/`. **Not exhaustive** — see also `CautionCommands`, `InviteGate`, `AnniRsvpCommand`, `AnniScrollspotCommand` and `AnniDebugCommands`.

**Gap:** `/wv invite-force <playerName>` (confirmed-staff bypass of the invite gate), undocumented. See `CommandRegistry.inviteForce` and `InviteGate` (380 L).

**Gap:** the `/caution`, `/caution-go`, `/warn` and `/eject` chat-intercept commands, undocumented. They are not Brigadier-registered — they share `GuildChatDispatcher`'s intercept path. See `CautionCommands` (465 L).

**Gap:** nine of the twelve intercepted prefixes are undocumented — `/a`, `/encourage`, the chat-path `/wv check`, `/gu invite`, `/guild invite`, `/caution-go`, `/caution`, `/warn`, `/eject`. Only `/g`, `/wg` and `/v` are covered, in §2. See `GuildChatDispatcher.intercept`.

**Gap:** the standalone root-level `/motd` command (registered outside the `/wv` tree, ungated), undocumented. See `CommandRegistry.register`.

### /wv help [<subcommand>]
No permission. [HelpCommands](../src/client/java/org/wynnvets/commands/HelpCommands.java). The `/wv help <subcommand>` literals are `config`, `check`, `return`, `staff`, `list`, `motd`, `anni`, `line`, `debug`, `debug set`, `debug trigger`. What bare `/wv help` *prints* is a different, rank-gated set: `/wv help`, `/wv anni`, `/wv config`, `/wv debug` always, then `/wv motd` for vets, `/wv list` and `/wv staff` when unlocked, `/wv return` and `/wv line` for Returners, `/wv check` for staff.

### /wv check <playerName>
Confirmed staff. Both the Brigadier `.requires` visibility gate and the runtime check are `GuildStateManager.isConfirmedStaff()` — server-confirmed staff from the WS auth ack, not a Captain rank check. [CommandRegistry.check()](../src/client/java/org/wynnvets/commands/CommandRegistry.java). Delegates to `UserInfoFetcher.checkUser()` which chains Mojang UUID → WynnCraft profile → Returners roster membership.

### /wv return
Vet/Returners. [CommandRegistry.returnInfo()](../src/client/java/org/wynnvets/commands/CommandRegistry.java). GETs `VetsApi.RETURN`, shows latest return-channel post.

The post is authored in Discord, so the body arrives with Discord's `<t:EPOCH:STYLE>` timestamp markup intact — temporary-server's markdown converter passes it through untouched. [`DiscordTimestamps.expand()`](../src/client/java/org/wynnvets/chat/DiscordTimestamps.java) rewrites it into the reader's own timezone client-side (that's the whole point of the markup — the API can't know the zone), supporting all seven Discord styles with `f` as the default, and hangs a hover carrying the absolute time, its zone, and the relative offset off each expansion. Malformed or out-of-range epochs fall through as the raw markup.

[`ReturnFetcher.announcementFooter()`](../src/client/java/org/wynnvets/fetcher/ondemand/ReturnFetcher.java) then appends `For more info, click here for the announcement post!`, where `here` deep-links to the return channel (`discord.com/channels/<guild>/<RETURN_CHANNEL_ID>`). The API serves the message body with no message ID, so the link targets the channel rather than the post. Skipped when the body is blank (nothing cached upstream).

### /wv list [world]
Vet. Two variants:
- `/wv list` — [CommandRegistry.list()](../src/client/java/org/wynnvets/commands/CommandRegistry.java), triggers `ListFetcher.fetchList()`: online members grouped by tier, staff underlined, supporters glint.
- `/wv list world` (staff) — the Brigadier `.requires` is the all-true `userIsCaptain` stub; the real gate is `GuildStateManager.isStaff()` inside the handler, plus a refresh-in-progress bail — [CommandRegistry.listWorld()](../src/client/java/org/wynnvets/commands/CommandRegistry.java), triggers `WorldListFetcher.fetchWorldList()`: dispatches `/find` for each online member, groups by region (GeoIP2 codes: AF/AS/EU/NA/OC/SA/Other/Private).

### /wv staff
Unlocked. [CommandRegistry.staff()](../src/client/java/org/wynnvets/commands/CommandRegistry.java). GETs `VetsApi.STAFF`, shows online staff sorted by rank → alpha.

### /wv motd
Vet. [CommandRegistry.motd()](../src/client/java/org/wynnvets/commands/CommandRegistry.java). Returns guild MOTD (`VetsApi.GUILD_MOTD`) for Returners/waitlist/honourary, else plain MOTD.

### /wv anni [silent | passive | aggressive]
Public. [CommandRegistry.anni()](../src/client/java/org/wynnvets/commands/CommandRegistry.java). Dispatches via [StampFetcher.fetchStampAndCreateAnniCommandMessage()](../src/client/java/org/wynnvets/fetcher/ondemand/StampFetcher.java):

- **Snapshot-driven (S2):** when `vetsAnniEnabled` is on and `AnniSnapshotCache.latest()` is non-null, renders the rich multi-section view via [AnniCommandRenderer](../src/client/java/org/wynnvets/mwe/anni/render/AnniCommandRenderer.java). Three sub-branches:
  - *Not announced* — `\guess`-style prediction window (earliest/median/latest), registration status, role chips or registration nudge. Prediction gated by `vetsAnniShowPrediction`.
  - *Announced 2h+ out* — relative countdown, role chips, RSVP widget, attendance bar, board state. When assigned to a party also surfaces party ordinal, role, world, host.
  - *Announced within 2h* — compact status row + `[silent] [passive] [aggressive]` suggest-command widget. The mode literals are S2 stubs (real wiring in S3).
- **Legacy fallback:** when `vetsAnniEnabled` is off or no snapshot is cached, GETs `VetsApi.STAMP` (Unix seconds) and formats the original countdown. Less than 1 hour: red `Annihilation is in X mins!`; 1+ hours: `Annihilation returns in X hours Y mins!`; past: `The time for the next annihilation has not yet been announced`.

`silent`/`passive`/`aggressive` — route through [`AnniModeManager.transitionTo`](../src/client/java/org/wynnvets/mwe/anni/mode/AnniModeManager.java). Refused when `Models.StreamerMode.isInStream()` OR the chat-line [`StreamerModeChatDetector`](../src/client/java/org/wynnvets/mwe/anni/mode/StreamerModeChatDetector.java) signals stream-on (spec §3.1: silent is the ONLY mode allowed with `/stream`). Successful transitions update `vetsAnniMode`; the [`VetsBossBarManager`](../src/client/java/org/wynnvets/mwe/anni/bossbar/VetsBossBarManager.java) tick-loop picks up the change on the next tick.

### /wv anni rsvp {hard | soft | revoke}
S6. Authenticated only (must have run `~vetsmod`). [AnniRsvpCommand](../src/client/java/org/wynnvets/mwe/anni/command/AnniRsvpCommand.java). In-game RSVP — byte-equivalent to a Discord `\rsvp hard|soft|revoke`: same Rsvp row, same auto-placement into Unassigned, same `RSVP_CHANNEL_ID` public confirmation.

- `hard` — commit to attending. Boss-bar / `/wv anni` render switches to HARD chip (aqua).
- `soft` — tentative. SOFT chip (green).
- `revoke` — withdraw. Soft-deletes (`Rsvp.revoked_at`) and posts `<user> withdrew their RSVP.` to RSVP_CHANNEL_ID. Silent (no public post) if there was no active RSVP to withdraw.

Lights up the `[Hard]` / `[Soft]` `SuggestCommand` buttons in [AnniCommandRenderer.rsvpUpgradePrompt()](../src/client/java/org/wynnvets/mwe/anni/render/AnniCommandRenderer.java) — those buttons emit `/wv anni rsvp hard` / `/wv anni rsvp soft` verbatim, so user click-through now resolves.

Trust chain: vetsmod sends `anni_rsvp` ([`V1ApiManager.sendAnniRsvp`](../src/client/java/org/wynnvets/api/V1ApiManager.java)) → temp-server's [`_handle_anni_rsvp`](../../temporary-server/app/chat/inbound.py) stamps the session's `mc_uuid` as `actor_mc_uuid` → vets-anni's [`POST /api/internal/anni-rsvp-by-uuid`](../../vets-anni/app/web/routers/anni_internal.py) calls [`execute_uuid_rsvp`](../../vets-anni/app/domain/rsvp_by_uuid.py) which reuses the Discord cog's `_auto_place_after_rsvp` / `_broadcast_board_snapshot` / `_post_public` helpers verbatim. Ack flows back as `anni_rsvp_response` (routed to [`AnniRsvpClient`](../src/client/java/org/wynnvets/mwe/anni/network/AnniRsvpClient.java)'s pending future; 5s timeout).

Unauthenticated message uses spec wording: `Use \rsvp on discord — or run ~vetsmod first.` T-90 cutoff is enforced server-side (revokes are unaffected; vets-anni surfaces `RSVP is closed (within 90 min of anni)` for hard/soft attempts inside the cutoff).

### /wv config [<key> [<value>]]
No permission. [ConfigCommands](../src/client/java/org/wynnvets/commands/ConfigCommands.java). Three forms:
- `/wv config` — list all `VetsConfig.USER_CONFIG_KEYS` with current values
- `/wv config <key>` — show current value
- `/wv config <key> <value>` — persist

Suggestion providers (`ConfigCommands.SUGGEST_CONFIG_KEYS` and `ConfigCommands.SUGGEST_CONFIG_VALUES`): colour names via `VetsConfig.getColorNames()`, sprite names (10 total), opacity int values `reset|0|25|50|69|75|100`, tri-state `default|true|false`. Validation via `VetsConfig.isValidColor()`, `isValidSprite()`.

### /wv line <church|scrap|bat|hegea|lighthouse>
Returners. [CommandRegistry.lineToggle()](../src/client/java/org/wynnvets/commands/CommandRegistry.java). Delegates to `TerritoryLineManager.toggle(alias)`. Requires `areFeaturesEnabled()` (Returners only).

Aliases → territories (five, from `TerritoryLineManager.LINE_ALIASES`):
- `church` → Forest of Eyes
- `scrap` → Corkus Sea Cove
- `bat` → Royal Barracks
- `hegea` → Fort Hegea
- `lighthouse` → Contested District

### /wv debug [...]
Public. Tree built in [DebugCommands.buildCommandTree()](../src/client/java/org/wynnvets/debug/DebugCommands.java):
- `/wv debug` — dumps diagnostics (`DiagnosticsHandler.execute()`)
- `/wv debug true|false` — toggle debug logging (persists 3 days via `VETS_DEBUG_ENABLED_AT`)
- `/wv debug set` — list debug config keys
- `/wv debug set <key> [value]` — get/set debug key (e.g. `itemDump`)
- `/wv debug trigger charDump` — render PUA glyphs `\uE001-\uE040` in `chat/prefix` font, 8 per line
- `/wv debug trigger forceChecks` — force guild/rank/staff re-check via `GuildStateManager.forceGuildRecheck()`
- `/wv debug trigger tabDump` — `TabDumpHandler.execute()`
- `/wv debug trigger rsvpDump` (S6) — dump `isAuthenticatedThisSession()` + in-flight queue depth + `lastAttemptedNotice` + `lastAck` + current snapshot `rsvp` block, all read directly by `AnniRsvpClient.debugDump()`. Diagnostic for "why did my `/wv anni rsvp` not land".
- `/wv debug tree anni rsvp {hard|soft|revoke}` (S6) — debug mirror of the main `/wv anni rsvp` tree; identical effect, gated on `requireDebug` only (action only touches the caller's own RSVP, no staff/organiser perm needed).

**Not exhaustive** — `/wv debug trigger` has four further leaves (`bossBarsDump`, `nametagsDump`, `ghostsPromptDump`, `zoneLinesDump`); see `DebugCommands.buildCommandTree`.

**Gap:** `/wv debug tree anni` registers 24 executable leaves, of which this list names three (the `rsvp` trio). See `AnniDebugCommands.buildCommandTree` and [vetsmod_mwe_anni.md](vetsmod_mwe_anni.md).

### /wv distribute <name|@selector> <aspects|tomes|emeralds> <count>
Chief/Owner. Tree built in [DistributeCommands.buildCommandTree()](../src/client/java/org/wynnvets/distribute/DistributeCommands.java). Automates the Guild Management GUI to hand a resource to guild members.

**Two gates, deliberately different tiers.** Brigadier visibility is `GuildStateManager.isStaffOfAnyGuild()` (captain+, so the command autocompletes reliably for staff); execution is re-checked at the executor by `DistributeCommands.ensureChief()` → `isChiefOfAnyGuild()`. A captain sees the command and gets a red error on run. Same defensive-double-check shape as `/wv check` and `/wv invite-force`.

- `<name>` — `NameOrSelectorArgument`, a custom Brigadier `ArgumentType<String>` reading one space-delimited token. The built-in `StringArgumentType.string()` restricts unquoted input to `[0-9A-Za-z_.+-]` and would fail to parse `@random` at the `@`. Suggests the four selectors plus current guild usernames from `Models.Guild.getGuildMembers()`.
- `<resource>` — `aspects` | `tomes` | `emeralds`, mapping to `MemberSlotPresser.Resource` hotbar buttons 0/1/2. One press sends 1 Aspect, 1 Guild Tome, or 1024 Emeralds.
- `<count>` — `IntegerArgumentType.integer(1, 255)`. The upper bound is an unsigned-byte cap against typo'd bulk sends.

Four `@`-selectors decide the recipient set, each one executor branch in `DistributeCommands.distribute`. What `<count>` *means* changes with the selector:

| Selector | Recipients | `<count>` means |
|---|---|---|
| *(literal name)* | that one player, resolved current→legacy via wapi | presses sent to them |
| `@random` | `min(count, roster)` random members | number of recipients, one each |
| `@objectives` | members whose tile shows a completed guild objective | total rewards, split evenly, random +1 for the remainder |
| `@graids` | members appearing in the guild log's graid entries | total rewards, proportional to participation frequency |
| `@split` | all three pools in sequence | total rewards, thirds, remainder randomised |

Everything downstream of the executor — the menu routes, the pagination and press state machines, the tick constants, the `NoAspects` opt-out and the failure/stall matrix — is in [vetsmod_distribute.md](vetsmod_distribute.md).

## 2. Chat command mixins

Target `ClientPacketListener.sendCommand(String)`; HEAD + cancellable.

### UnlockCommandMixin
[UnlockCommandMixin](../src/client/java/org/wynnvets/mixin/client/command/UnlockCommandMixin.java)

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
[GuildChatCommandMixin](../src/client/java/org/wynnvets/mixin/client/chat/GuildChatCommandMixin.java)

Routes `/g`, `/wg`, `/v` and nine more prefixes through `GuildChatDispatcher.intercept(command)` — **not exhaustive**, see `GuildChatDispatcher.intercept`, which matches 12. Staff `/v` goes through `CommandDispatcher` → `MessageFanoutDispatcher` for fan-out to online staff with 🔐 lock prefix. `/msg` is **not** intercepted: vetsmod only ever emits it, via `Handlers.Command.queueCommand` in the fan-out and as a `SuggestCommand` click on `/wv list` entries.

## 3. Suggestion providers

`ConfigCommands.SUGGEST_CONFIG_KEYS` and `SUGGEST_CONFIG_VALUES` provide Brigadier tab completion. Tri-state keys suggest `default|true|false`. Int keys (opacity) suggest `reset|0|25|50|69|75|100`. String keys suggest from `VetsConfig.getColorNames()` or the 10 sprite names.

## 4. Things to know when adding commands

- Register new subcommand inside `CommandRegistry.register()` using Brigadier literal/argument pattern.
- Gate with a real predicate. `CommandRegistry`'s own `userIsCaptain`/`userIsVet` are all-true stubs; the genuinely-gated commands call a `GuildStateManager` predicate directly — `isConfirmedStaff()`, `isStaff()`, `areFeaturesEnabled()`, `isUnlocked()`, `isStaffOfAnyGuild()`, `isChiefOfAnyGuild()` and `isAuthenticatedThisSession()` are all in use.
- Avoid heavy work on the main thread; use `CompletableFuture` from `HttpClient`.
- Chat output: use `ChatUtils.dispatchToChat(Component, Style)` (thread-safe, marks the dispatch internal so the chat pipeline skips re-logging and the rewriter chain). There is no no-argument form.
- Tab completion: use Brigadier `SuggestionProvider`s, with `ConfigCommands.SUGGEST_CONFIG_KEYS` / `SUGGEST_CONFIG_VALUES` as templates.
- **A fixed set of choices does not need a `SuggestionProvider` at all.** Literal children give Brigadier its own suggestion list for free — `hard`/`soft`/`revoke` under `rsvp`, and `silent`/`passive`/`aggressive` under `anni`, are both spelled that way. Reach for `StringArgumentType.word()` plus a custom provider only when the value set is open or computed at runtime.
