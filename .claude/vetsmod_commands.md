---
name: vetsmod /wv Command Reference
description: Complete /wv command tree — subcommands, permissions, handlers, chat-command mixins (/unlock, /g/wg/v)
type: project
originSessionId: dc63f47a-2d15-4f8d-9b6a-41d3049f0cc2
---
# vetsmod Commands Reference

This is the index of vetsmod's command surface. §1 is the Brigadier tree: the `/wv` command and a
standalone `/motd`. §2 is the chat commands vetsmod's mixins intercept before they reach the server. Each
§1 entry gives the command's path, its gates, its handler, what it does, its side effects and its known
bugs.
Where another doc owns the detail, the entry links it rather than restating it.

## 1. The `/wv` tree

### Registration

[`CommandRegistry.register`](../src/client/java/org/wynnvets/commands/CommandRegistry.java) registers the
roots `/motd` and `/wv` on the dispatcher it is given. `/wv`'s `debug` and `distribute` subtrees are built
elsewhere and mounted with `.then(…)`: `/wv debug` by
[`DebugCommands.buildCommandTree`](../src/client/java/org/wynnvets/debug/DebugCommands.java), whose `tree`
literal mounts
[`AnniDebugCommands.buildCommandTree`](../src/client/java/org/wynnvets/debug/anni/AnniDebugCommands.java),
and `/wv distribute` by
[`DistributeCommands.buildCommandTree`](../src/client/java/org/wynnvets/distribute/DistributeCommands.java).
Those classes are the only builders. The classes their nodes call into, such as `HelpCommands`,
`ConfigCommands`, `AnniRsvpCommand`, `AnniScrollspotCommand`, `AnniSnapshotDebugCommands`,
`AnniHighlightDebugCommands` and `AnniDebugSuggestions`, register nothing.

`VetsmodClient.onInitializeClient` only adds `register` as a `ClientCommandRegistrationCallback`. Fabric
calls it on every login, with a new dispatcher, so the whole tree is rebuilt on each server join.

### Gates

The tables name these kinds of gate:

- **Parse gate:** a node's `.requires(…)`. Brigadier tests it live when it parses a typed command:
  typing a refused child gets Fabric's red "Incorrect argument for command" (Brigadier's
  `dispatcherUnknownArgument`, which Fabric shows instead of forwarding), and nothing reaches the
  handler or the server. So a handler that re-tests its own parse gate cannot refuse through Brigadier.
  Tab completion reads a copy instead: Fabric copies the client tree into the vanilla dispatcher only
  when a `minecraft:commands` packet arrives, leaving out each node whose `.requires` fails at that
  moment, and vetsmod never calls `ClientCommandManager.refreshCommandCompletions()`. So a node is
  suggested or hidden as its gate stood at the last such packet. A typed `/wv check` can also meet
  `GuildChatDispatcher`'s intercept before Fabric's hook sees it (below).
- **Runtime gate:** the handler's own test, which prints a refusal and returns.

`CommandRegistry.userIsVet` and `userIsCaptain` are all-true stubs ("TODO: Replace with correct permission
checks once available"). A node whose only parse gate is one of them is ungated, and the tables below say
so rather than naming the stub.

What each gate checks:

| Gate | Checks | Home |
|---|---|---|
| `isStaff()` | `StaffRankChecker`'s cached `/gu rank` answer: Captain+ in whatever guild the player was in when it last answered. Persisted, refreshed at most daily unless a caller forces it, as `/wv list world` does | `GuildStateManager` |
| `isConfirmedStaff()` | `is_staff` from the last ok auth ack, which temporary-server resolves against the staff roster. An auth failure or leaving the server clears it | `GuildStateManager`, reading `V1ApiManager` |
| `isStaffOfAnyGuild()` | `isConfirmedStaff()`, or Captain+ in the current guild by Wynntils' live rank | `GuildStateManager` |
| `isChiefOfAnyGuild()` | the auth ack's confirmed rank is chief or owner, or Wynntils' live rank is Chief or Owner | `GuildStateManager` |
| `isUnlocked()` | `isReturners()`, or waitlist- or honourary-unlocked: an authenticated tier this session, or a legacy on-disk marker (bug `legacy-unlock-markers-still-grant-client-unlock`). Also a debug override that nothing sets | `GuildStateManager` |
| `isReturners()` | `GuildChecker`'s unexpired `/gu stats` result, else Wynntils' guild name is "Returners" | `GuildStateManager` |
| `areFeaturesEnabled()` | the same as `isReturners()` | `GuildStateManager` |
| `isAuthenticatedThisSession()` | an ok auth ack has arrived, and no auth failure, newly stored `/unlock` key or leaving the game server has cleared it since. It does not follow the inbound socket, so it stays true across a socket drop | `GuildStateManager`, reading `UnlockManager` |
| `requireDebug` | `VetsLogger.isDebugEnabled()`, which `/wv debug true` turns on, and which startup restores when it was turned on less than three days earlier | `AnniDebugCommands` |
| `requireStaffOrOrganiser` | `isStaff()`, or the local player's UUID is among the cached anni snapshot's `organisers` (an injected snapshot counts) | `AnniDebugCommands` |
| the `/stream` mutex | refuses a change to passive or aggressive while Wynntils' streamer mode or `StreamerModeChatDetector` says `/stream` is on | `AnniModeManager.transitionTo` |

[vetsmod_guild_system.md](vetsmod_guild_system.md) §1 describes the guild and unlock state behind these.

### Root `/motd`

| Command | Parse gate | Runtime gate | Handler | What it does | Side effects | Bugs |
|---|---|---|---|---|---|---|
| `/motd` | none | none | `CommandRegistry.motd` | Prints the guild MOTD for a Returners, guildless-and-waitlist-unlocked or honourary-unlocked player, else the plain MOTD. An empty guild MOTD falls back to the plain one | GETs `VetsApi.GUILD_MOTD` for those players, and `VetsApi.MOTD` for everyone else or as the fallback | `help-footers-name-gates-return-and-motd-lack` |

`/wv motd` runs the same handler.

### `/wv help`

| Command | Parse gate | Runtime gate | Handler | What it does | Side effects | Bugs |
|---|---|---|---|---|---|---|
| `/wv help` | none | none | `HelpCommands.help` → `buildHelp` | The index page. `/wv help`, `/wv anni`, `/wv config` and `/wv debug` always; `/wv motd` when `isEligibleForEnrichment()`; `/wv list` and `/wv staff` when `isUnlocked()`; `/wv return` and `/wv line` when `areFeaturesEnabled()`; `/wv check` when `isStaff()` | chat only | `help-advertises-wv-check-on-a-weaker-gate`, `help-footers-name-gates-return-and-motd-lack`, `wv-help-omits-five-registered-paths` |
| `/wv help config`, `check`, `return`, `staff`, `list`, `motd`, `anni`, `line`, `debug`, `debug set`, `debug trigger` | none | none | `HelpCommands.helpX` → `buildHelpX` | One detail page each. Most are a usage line, a line of prose and a `Requires:` footer; the config page lists its keys with no footer, and the debug pages list their subcommands, keys or actions above the footer | chat only | `help-config-page-understates-its-keys`, `help-config-print-bridge-row-says-bridge-only`, `help-config-row-says-wynntils-guild-can-stay-null`, `help-debug-trigger-documents-three-of-eight-leaves` |

Every page is a `buildX` returning the page plus a `helpX` that sends it, so a page is a value a
test can compare: `HelpCommandsTest` pins each one as a tree. The pages are assembled from `HelpCommands`'
own primitives (`title`, `heading`, `line`, `row`, `requires`, `detailPage`, and the `gray` / `dim`
fragment constructors). Every trailing newline belongs to its literal, not to a primitive. `buildHelp`
takes its gates as parameters rather than reading `GuildStateManager`, which is what makes the gate
matrix enumerable.

Three colour rules run through the pages, and each is a property of the *content* rather than of the call
site that wrote it:

- **A row's head splits at its first argument token.** `row` renders the command in YELLOW and, from the
  first `<` or `[` that follows a space, GOLD. `HelpCommands.argumentStart` returns the bracket's index,
  so the space stays YELLOW. `/wv debug set <key>` is two runs, and `/wv debug trigger charDump`, a
  literal subcommand rather than an argument, is one.
- **`gray` describes and `dim` clarifies.** The GRAY → DARK_GRAY change marks a change of thought, not a
  line wrap: a description too long for one chat line wraps into a second GRAY fragment rather than
  spilling into the DARK_GRAY run mid-sentence. Only the debug set and debug trigger pages use `dim`.
- **The `Requires:` footer colours the *viewer*, not the command.** Every page but the index and the config
  page carries one. `HelpCommands.Access` has the states `DENIED` RED, `GRANTED` GREEN, `CONFIRMED`
  AQUA and `PUBLIC` WHITE. The gated detail pages take an `Access` resolved by
  `access(met, confirmed)`, in which `confirmed` wins outright so a stale local cache cannot report
  DENIED on a permission the server has granted. `access` never returns `PUBLIC`; the anni, debug,
  debug set and debug trigger pages pass it as a constant. GREEN means *this should work*; AQUA means
  *the server agrees*. Five of the six gated pages take `sessionBacked(met)`, whose confirmation is
  `met && isAuthenticatedThisSession()` (so an authenticated viewer who fails the local gate still sees
  DENIED). `/wv check` is the exception and pairs `isStaff()` with `isConfirmedStaff()`, which is why it
  can render GREEN on a command that will still refuse: the footer reports both signals by design, and
  the index row still advertises `/wv check` on `isStaff()` alone (bug
  `help-advertises-wv-check-on-a-weaker-gate`). The return and motd footers colour gates their commands
  do not have, so their RED does not mean the command will refuse (bug
  `help-footers-name-gates-return-and-motd-lack`). The detail builders take an `Access` rather than
  reading `GuildStateManager`, for the same reason `buildHelp` takes its gates as parameters.

### `/wv check` and `/wv invite-force`

| Command | Parse gate | Runtime gate | Handler | What it does | Side effects | Bugs |
|---|---|---|---|---|---|---|
| `/wv check <playerName>` | `isConfirmedStaff()` | the same, so its refusal cannot fire through Brigadier | `CommandRegistry.check` | `UserInfoFetcher.checkUser`: name → UUID through the `PlayerLookup` cascade, then the Wynncraft profile, dazebot's membership snapshot and, once there is a profile, guild-roster cross-checks. Then `CautionCommands.runCheckCautions` for the caution history | HTTP: the name lookup cascade, the Wynncraft profile and guild rosters. WebSocket: `check_membership` and `caution_check` frames | `help-advertises-wv-check-on-a-weaker-gate`; the `wv-check-*` files; `check-membership-ack-drops-last-seen-observed`, `vets-snapshot-provider-makes-player-not-found-unreachable`, `user-is-in-vets-npe-on-missing-guild-uuid`, `user-info-fetcher-join-blocks-completing-thread`, `staff-action-queue-not-drained-on-socket-reconnect` |
| `/wv invite-force <playerName>` | `isConfirmedStaff()` | the same, so its refusal cannot fire through Brigadier | `CommandRegistry.inviteForce` → `InviteGate.forceDispatch` | Sends `/gu invite <playerName>` with every `InviteGate` check skipped, and prints a yellow "Dispatching /gu invite <name> (vetsmod gate bypassed)." `InviteGate`'s `[Invite anyway]` button runs it | in-game `/gu invite`, sent through `GuildChatDispatcher.sendCommandBypassed` | none |

`/wv check <playerName>` and `/wv invite-force <playerName>` read the name with
`StringArgumentType.string()`, so a name is one unquoted token or a quoted string. Which hook handles a
typed `/wv check` first, Fabric's or `GuildChatDispatcher`'s, is not settled; see
`help-advertises-wv-check-on-a-weaker-gate`.

### `/wv return`, `/wv list`, `/wv staff` and `/wv motd`

| Command | Parse gate | Runtime gate | Handler | What it does | Side effects | Bugs |
|---|---|---|---|---|---|---|
| `/wv return` | ungated | none | `CommandRegistry.returnInfo` → `ReturnFetcher.fetchReturn` | Prints the latest return-channel post, with its Discord timestamps rewritten and a link footer (below) | GETs `VetsApi.RETURN` | `help-footers-name-gates-return-and-motd-lack`, `return-footer-hover-says-it-opens-the-post` |
| `/wv list` | ungated | `isUnlocked()`: "You must be unlocked to use /wv list." | `CommandRegistry.list` → `ListFetcher.fetchList` | Prints "Looking up online members...", then the online members grouped by tier and VetsMod status: staff underlined, supporters with a glint | GETs `VetsApi.LIST`; Wynntils' Wynncraft API lookup of the Returners guild (`Models.Guild.getGuild`); for a Returners caller, a WebSocket `tablist` frame of the tab list's guild entries | `stale-registration-survives-eligibility-loss`, `staff-ranks-poll-swap-not-atomic`, `online-guild-cache-default-locale-lowercase`, `legacy-unlock-markers-still-grant-client-unlock` |
| `/wv list world` | ungated | `isStaff()`, after `refreshStaffStatusIfNeeded(!isStaff())`: "Checking staff permissions, please retry in a moment." while a refresh starts or runs, then "You must be staff to use /wv list world." | `CommandRegistry.listWorld` → `WorldListFetcher.fetchWorldList` | For staff: finds each online member with `/find` and groups them by server region (the GeoIP2 continent codes AF, AS, EU, NA, OC and SA, then Other and Private) | in-game `/gu rank` (forced for a non-staff caller), `/find` per member; GETs `VetsApi.LIST` and `VetsApi.STAFF`; Wynntils' Wynncraft API lookup of the Returners guild; a `PlayerLookup` name lookup for each member `/find` did not place; for a Returners caller, a WebSocket `tablist` frame | `wv-list-world-never-refuses-non-staff`, `staff-rank-cache-survives-leaving-guild`, `find-response-matches-username-as-substring`, `find-phase-does-not-yield-to-queued-msg`, `vets-snapshot-provider-skips-confirmed-staff-check`, `staff-action-queue-not-drained-on-socket-reconnect` |
| `/wv staff` | ungated | `isUnlocked()`: "You must be unlocked to use /wv staff." | `CommandRegistry.staff` → `StaffFetcher.fetchOnlineStaff` | Prints online staff, sorted by rank, then by name | GETs `VetsApi.STAFF` | `legacy-unlock-markers-still-grant-client-unlock` |
| `/wv motd` | ungated | none | `CommandRegistry.motd` | As the root `/motd` | as `/motd` | `help-footers-name-gates-return-and-motd-lack` |

`/wv list world`'s non-staff refusal is unreachable: the forced refresh puts every non-staff call on the
yellow "retry" line (bug `wv-list-world-never-refuses-non-staff`). It has no `isUnlocked()` check (filed
inside `staff-rank-cache-survives-leaving-guild`).

**`/wv return`'s timestamps and footer.** The post is authored in Discord, so the body arrives with
Discord's `<t:EPOCH:STYLE>` timestamp markup intact; temporary-server's markdown converter passes it
through untouched.
[`DiscordTimestamps.expand()`](../src/client/java/org/wynnvets/chat/DiscordTimestamps.java) rewrites it
into the reader's own timezone client-side (that is the point of the markup: the API cannot know the
zone), supporting every Discord style, with `f` as the default, and hangs a hover carrying the
absolute time, its zone and the relative offset off each expansion. Malformed or out-of-range epochs fall
through as the raw markup.
[`ReturnFetcher.announcementFooter()`](../src/client/java/org/wynnvets/fetcher/ondemand/ReturnFetcher.java)
then appends `For more info, click here for the announcement post!`, where `here` deep-links to the return
channel (`discord.com/channels/<guild>/<RETURN_CHANNEL_ID>`). The API serves the message body with no
message ID, so the link targets the channel rather than the post. The footer is skipped when the body is
blank (nothing cached upstream).

### `/wv anni`

| Command | Parse gate | Runtime gate | Handler | What it does | Side effects | Bugs |
|---|---|---|---|---|---|---|
| `/wv anni` | none | none | `CommandRegistry.anni` → `StampFetcher.fetchStampAndCreateAnniCommandMessage` | The anni status: the snapshot render (`AnniCommandRenderer`) when `vetsAnniEnabled` is on and a snapshot is cached or an on-demand pull brings one, else, or when the renderer declines, the legacy stamp countdown | WebSocket `anni_query` on a cold cache while `vetsAnniEnabled` is on; GETs `VetsApi.STAMP` on the legacy path | `vets-anni-enabled-to-be-retired`, `stamp-fallback-says-not-announced-on-cold-network-failure`, `anni-ack-clients-ortimeout-completes-exceptionally`, `anni-dates-print-in-the-default-format-locale`, `anni-roleless-registration-shows-the-unregistered-nudge`, `anni-rsvp-badge-hover-says-click-runs-the-rsvp-command` |
| `/wv anni silent`, `passive`, `aggressive` | none | the `/stream` mutex, for passive and aggressive: "Anni mode change refused: /stream is active. Stream is suboptimal for anni — try /toggle ghosts NONE instead." | `CommandRegistry.anniMode` → `AnniModeManager.transitionTo(mode, USER_COMMAND)` | Sets the anni mode and prints it; a refusal changes nothing | writes `vetsAnniMode`, `vetsAnniModeUserSet` and `vetsAnniUserMode` | none |
| `/wv anni rsvp hard`, `soft`, `revoke` | none | `isAuthenticatedThisSession()`: "Use \rsvp on discord — or run ~vetsmod first." | `AnniRsvpCommand.hard`, `soft`, `revoke` | Sends the RSVP and prints the result; on success, pulls a fresh snapshot | WebSocket `anni_rsvp`, then `anni_query` on success | `in-game-rsvp-revoke-of-nothing-says-withdrawn`, `anni-ack-clients-ortimeout-completes-exceptionally`, `ack-clients-timeout-has-zero-slack-over-the-server-forward` |

[vetsmod_mwe_anni.md](vetsmod_mwe_anni.md) owns all three: §"`/wv anni` render dispatch" has the branch
list and the not-announced, far-out and imminent forms; §Mode state has the mode keys, the mutex and
the automatic transitions; §"`/wv anni rsvp`" has the client, the auth chain and the locked decisions. The
imminent form's `[Silent] | [Passive] | [Aggressive]` buttons run the mode literals, and the far-out RSVP
line's `[Hard]` / `[Soft]` buttons suggest `/wv anni rsvp hard` and `soft`.

**RSVP, server side.** An RSVP goes through temporary-server, which stamps the session's `mc_uuid`, to
vets-anni, which reuses its Discord cog's helpers, so the RSVP row, the auto-placement and the public
confirmation post come from the same code as a Discord `\rsvp`'s. vetsmod never sees the private reply: a revoke with
no active RSVP still prints "Your RSVP has been withdrawn." (bug
`in-game-rsvp-revoke-of-nothing-says-withdrawn`). vets-anni closes RSVP 90 minutes before an anni,
server-side: vets-anni refuses a hard or soft RSVP inside that cutoff with "RSVP is closed (within 90 min
of anni)", and never refuses a revoke for it. The server-side detail, including the placeholder name a first-time player
gets, is in vets-anni's `.claude/snapshot_integration.md`, linked from vetsmod_mwe_anni.md §"`/wv anni
rsvp`".

### `/wv config`

| Command | Parse gate | Runtime gate | Handler | What it does | Side effects | Bugs |
|---|---|---|---|---|---|---|
| `/wv config` | none | none | `ConfigCommands.configList` | Prints every key in `VetsConfigSchema.USER_CONFIG_KEYS`, in order, with its value | chat only | none |
| `/wv config <key>` | none | none | `ConfigCommands.configGet` | Prints one key's value; "Unknown config key: <key>" for a key outside `USER_CONFIG_KEYS` | chat only | none |
| `/wv config <key> <value>` | none | none | `ConfigCommands.configSet` | Sets the key; string and int keys also take `reset`. Refuses an unknown key, and a value of the wrong type | persists the config | `config-set-rejects-role-style-and-flash-intensity`, `config-commands-anni-mode-suggestion-arm-unreachable` |

Every `key = value` line the three forms print, and the `/wv debug set` prints, is built by
`util/ConfigValueText`. `ConfigCommands.Kind.of(key)` picks which of `VetsConfig`'s typed accessors a key
uses, and the same enum drives both the read paths and the write dispatch, so the type ladder is
written once. A colour or sprite value is validated with `LegacyItemStyle.isValidColor()` and
`isValidSprite()`. [vetsmod_config.md](vetsmod_config.md) owns the keys themselves. Its suggestion
providers, `ConfigCommands.SUGGEST_CONFIG_KEYS` and `SUGGEST_CONFIG_VALUES`, are in §3.

### `/wv line`

| Command | Parse gate | Runtime gate | Handler | What it does | Side effects | Bugs |
|---|---|---|---|---|---|---|
| `/wv line church`, `scrap`, `bat`, `hegea`, `lighthouse` | none | `areFeaturesEnabled()`: "You must be a Returners guild member to use /wv line." | `CommandRegistry.lineToggle` → `TerritoryLineManager.toggle` | Toggles one territory's guild-XP boundary line and prints Enabled or Disabled | the first enable GETs `api.wynncraft.com/v3/guild/list/territory` | none |

The aliases map to territories in `TerritoryLineManager.LINE_ALIASES`:

- `church` → Forest of Eyes
- `scrap` → Corkus Sea Cove
- `bat` → Royal Barracks
- `hegea` → Fort Hegea
- `lighthouse` → Contested District

### `/wv debug`

Built by [`DebugCommands.buildCommandTree`](../src/client/java/org/wynnvets/debug/DebugCommands.java). No
node has a parse gate, and only the `tree anni` subtree has a runtime gate; the other refusals check an
argument, or that the state a dump reads (Wynntils, the tab list, the level, the boss-bar overlay) is
there.

| Command | Parse gate | Runtime gate | Handler | What it does | Side effects | Bugs |
|---|---|---|---|---|---|---|
| `/wv debug` | none | none | `DiagnosticsHandler.execute` | A diagnostics block in chat and a fuller report in the log | none | none |
| `/wv debug <true\|false>` | none | none | `DiagnosticsHandler.execute` | Turns `VetsLogger`'s debug flag on or off. Case-insensitive; any other word prints a usage line and changes nothing | writes `vetsDebugEnabledAt` | `anni-debug-chat-strings-omit-tree-literal` |
| `/wv debug set` | none | none | `DebugCommands.debugConfigList` | Lists the debug config keys (`itemDump` is the only one) | chat only | none |
| `/wv debug set <key> [<value>]` | none | none | `DebugCommands.debugConfigGet`, `debugConfigSet` | Prints or sets a debug key. Refuses an unknown key, and a value other than `true` or `false` | the set form persists the config | none |
| `/wv debug trigger <name>` | none | none | `DebugCommands` (see below) | One of the diagnostic dumps below | chat or log; `forceChecks` also queues `/gu rank` and `/gu stats` | `help-debug-trigger-documents-three-of-eight-leaves`, `nametags-dump-missing-original-column` |
| `/wv debug tree anni …` | none | `requireDebug` on every leaf; `requireStaffOrOrganiser` on the `scrollspot` leaves | `AnniDebugCommands`, `AnniSnapshotDebugCommands`, `AnniHighlightDebugCommands` | The anni debug harness (below) | see below | `anni-debug-chat-strings-omit-tree-literal`, `anni-poller-erases-injected-debug-state`, `anni-scrollspot-clear-frame-omits-scroll-spot-key`, `force-alert-reads-the-snapshot-cache-twice`, `anni-ack-clients-ortimeout-completes-exceptionally` |

`/wv debug true` records the time in `vetsDebugEnabledAt`, and `false` clears it. `VetsmodClient` reads that
timestamp only at startup, restoring the flag if it was set less than three days earlier and clearing an
older one, so a session left running keeps it on. The flag is not logging-only: it is also what
`requireDebug` tests.

The trigger leaves:

- `charDump` — renders the PUA glyphs U+E001–U+E040 in the `chat/prefix` font, 8 per line.
- `forceChecks` — `GuildDiagnostics.forceGuildRecheck()`: reports what Wynntils and vetsmod each hold
  about the player's guild and staff rank, then forces a staff-rank refresh and a `/gu stats` check, each
  skipped if one is already in flight. When Wynntils reports a guild, it also re-runs the registration send
  and, for a Returners player, the stamp print.
- `tabDump` — `TabDumpHandler.execute()`.
- `bossBarsDump` — a snapshot of vanilla's `BossHealthOverlay#events` map, read through
  `BossHealthOverlayAccessor`: a header (entry count, `VetsBossBarManager.isActive`), then, per bar, a chat
  line each for index / short UUID / `← OURS` marker, colour / overlay / progress / darken-music-fog flags,
  and the raw name component.
- `nametagsDump` — a header of `outlineSuppressionActive` / `vetsAnniNametagsEnabled` /
  `vetsAnniOutlinesEnabled` and a player count, then one line per player in `level.players()`:
  `AnniOutlineRegistry` hit/miss, tier, role, and the colour `NametagMixin`'s anni branch would resolve
  right now. It reads the registry directly; it does not observe the mixin or `state.nameTag`.
- `ghostsPromptDump` — `GhostsPromptHandler.debugDump()`: the aggressive gate, toggle, zone, stamp and
  per-player ghost state, plus whether the prompt would fire. Writes to the log, not chat.
- `zoneLinesDump` — the aggressive gate, the `vetsAnniZoneLines` toggle, the cold-cache flag, and every
  cached `AnniZone.Disc` with its horizontal (x/z) squared distance to the player.
- `rsvpDump` — `isAuthenticatedThisSession()`, the in-flight queue depth, `lastAttemptedNotice`, `lastAck`
  and the current snapshot's `rsvp` block, all read by `AnniRsvpClient.debugDump()`. Writes to the log, not
  chat. The diagnostic for "why did my `/wv anni rsvp` not land".

**The anni debug harness** under `/wv debug tree anni` has 11 literals (`snapshot`, `guess`, `external`,
`time`, `zone`, `flash`, `mode`, `scrollspot`, `alert`, `rsvp` and `registry`) and 24 executable leaves,
both pinned by `AnniDebugCommandsTest`. Every leaf refuses without debug logging ("anni debug commands require debug
logging — run /wv debug true first"). The `scrollspot` leaves also refuse a player who is neither staff nor
an organiser ("scrollspot is for party hosts / staff only."), and the ones that write to the server
(`set`, `here` and `clear`) also need an authenticated session. The leaves with effects outside the client's memory are `snapshot dump` (writes a
file under `vetsmod/dumps/anni/`), `snapshot refresh` and `guess` (`anni_query`), `scrollspot set`, `here`
and `clear` (`anni_scrollspot_set`), `rsvp hard`, `soft` and `revoke` (the same as `/wv anni rsvp`), and
`mode set` (writes `vetsAnniMode`, bypassing the `/stream` mutex). Each leaf is described in
[vetsmod_mwe_anni.md](vetsmod_mwe_anni.md) §Debug harness.

### `/wv distribute <name|@selector> <aspects|tomes|emeralds> <count>`

Tree built in [`DistributeCommands.buildCommandTree()`](../src/client/java/org/wynnvets/distribute/DistributeCommands.java).
It automates the Guild Management GUI to hand a resource to guild members.

| Command | Parse gate | Runtime gate | Handler | What it does | Side effects | Bugs |
|---|---|---|---|---|---|---|
| `/wv distribute <name\|@selector> <aspects\|tomes\|emeralds> <count>` | `isStaffOfAnyGuild()` | `isChiefOfAnyGuild()`, via `DistributeCommands.ensureChief`: "You must be a guild Chief or Owner to use /wv distribute." | `DistributeCommands.distribute` | Hands the resource out by pressing buttons in the Guild Management GUI, to one player or to a selector's recipient set | in-game `/guild manage` and GUI clicks; HTTP to the Wynncraft guild API (`NameResolver`) and the NoAspects opt-out list (`VetsApi.NO_ASPECTS`) | the `distribute-*` files, `no-aspects-opt-out-withholds-every-resource`, `all-opted-out-reported-as-roster-read-failure`, `close-members-screen-sends-no-close-packet`, `graids-log-never-reached-reported-as-empty-log`, `graids-log-walk-depends-on-wynntils-log-screen-feature`, `guild-log-walker-closes-whatever-screen-is-open`, `guild-log-walker-timers-reset-on-respawn`, `guild-manage-opener-arm-consumed-early`, `member-slot-presser-drops-completion`, `members-list-fast-path-binds-a-menu-about-to-be-replaced`, `members-list-searcher-retry-hop-not-token-guarded`, `members-list-walker-drops-completion`, `members-list-walker-rescan-double-clicks-next`, `members-list-walker-scans-half-updated-page`, `outbound-command-queue-check-accepts-non-deque`, `random-distributor-can-target-one-tile-twice` |

**Two gates, deliberately different tiers.** The parse gate, `isStaffOfAnyGuild()` (server-confirmed staff,
or captain+ by Wynntils' live rank), lets staff autocomplete the command (as the gate stood at the last
command-tree packet; see Gates); the runtime gate,
`ensureChief()` → `isChiefOfAnyGuild()`, is the only Chief/Owner check in vetsmod for this command. A
strategist sees the command and gets the red refusal on run. That is **not** the shape of `/wv check` and
`/wv invite-force`, which re-check the same predicate their `.requires` uses; here the two gates use
different predicates, so the red line is the normal path for staff below chief.

- `<name>` — `NameOrSelectorArgument`, a custom Brigadier `ArgumentType<String>` reading one
  space-delimited token. The built-in `StringArgumentType.string()` restricts unquoted input to
  `[0-9A-Za-z_.+-]` and would fail to parse `@random` at the `@`. Suggests the selectors plus current guild
  usernames from `Models.Guild.getGuildMembers()`.
- `<resource>` — `aspects` | `tomes` | `emeralds`, mapping to `MemberSlotPresser.Resource` hotbar buttons
  0/1/2. One press sends 1 Aspect, 1 Guild Tome, or 1024 Emeralds.
- `<count>` — `IntegerArgumentType.integer(1, 255)`. The upper bound is an unsigned-byte cap against
  typo'd bulk sends.

The `@`-selectors decide the recipient set, each one row of the `SELECTORS` table in
`DistributeCommands`, matched before the literal-name fan-out. What `<count>` *means* changes with the
selector:

| Selector | Recipients | `<count>` means |
|---|---|---|
| *(literal name)* | that one player, resolved current→legacy via wapi | presses sent to them |
| `@random` | `min(count, roster)` random members | number of picks, one each (see `vetsmod_distribute.md` §5 on tile-name collisions) |
| `@objectives` | members whose tile shows a completed guild objective | total rewards, split evenly, random +1 for the remainder |
| `@graids` | members appearing in the guild log's graid entries | total rewards, proportional to participation frequency |
| `@split` | all three pools in sequence | total rewards, thirds, remainder randomised |

Everything downstream of the executor — the menu routes, the pagination and press state machines, the
tick constants, the `NoAspects` opt-out and the failure/stall matrix — is in
[vetsmod_distribute.md](vetsmod_distribute.md).

## 2. Chat command mixins

**Gap:** the `/caution`, `/caution-go`, `/warn` and `/eject` chat-intercept commands, undocumented. They are not Brigadier-registered — they share `GuildChatDispatcher`'s intercept path. See `CautionCommands`.

**Gap:** nine of the twelve intercepted prefixes are undocumented — `/a`, `/encourage`, the chat-path `/wv check`, `/gu invite`, `/guild invite`, `/caution-go`, `/caution`, `/warn`, `/eject`. Only `/g`, `/wg` and `/v` are covered, in §2. See `GuildChatDispatcher.intercept`.

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

The legacy SHA-256 password matching has been removed. Users with stored `vetsWaitlistUnlockTime` / `vetsHonouraryUnlockTime` markers from before the migration get a session-start warning prompting them to run `/vetsmod` in Discord — see [vetsmod_guild_system.md §5](vetsmod_guild_system.md#5-sessionauthwarning). The markers are meant to do nothing else; today they also still unlock client-side (bug `legacy-unlock-markers-still-grant-client-unlock`).

### GuildChatCommandMixin
[GuildChatCommandMixin](../src/client/java/org/wynnvets/mixin/client/chat/GuildChatCommandMixin.java)

Routes `/g`, `/wg`, `/v` and nine more prefixes through `GuildChatDispatcher.intercept(command)` — **not exhaustive**, see `GuildChatDispatcher.intercept`, which matches 12. Staff `/v` goes through `DispatchCoordinator` → `MessageFanoutDispatcher` for fan-out to online staff with 🔐 lock prefix. `/msg` is **not** intercepted: vetsmod only ever emits it, via `Handlers.Command.queueCommand` in the fan-out and as a `SuggestCommand` click on `/wv list` entries.

## 3. Suggestion providers

`ConfigCommands.SUGGEST_CONFIG_KEYS` and `SUGGEST_CONFIG_VALUES` provide Brigadier tab completion. Tri-state keys suggest `default|true|false`. Int keys (opacity) suggest `reset|0|25|50|69|75|100`. String keys suggest `reset` plus names from `LegacyItemStyle.getColorNames()`, the 10 sprite names, or (for `vetsAnniRoleStyle`) the 3 role styles; `vetsAnniFlashIntensity` has no arm of its own and gets the colour names. A hand-typed `vetsAnniMode` key gets the three anni modes, which `/wv config` then refuses as an unknown key (bug `config-commands-anni-mode-suggestion-arm-unreachable`). Today setting either of those two anni keys validates the value as a colour name, so the suggested role styles are refused too (bug `config-set-rejects-role-style-and-flash-intensity`).

## 4. Things to know when adding commands

- Register new subcommand inside `CommandRegistry.register()` using Brigadier literal/argument pattern.
- Gate with a real predicate. `CommandRegistry`'s own `userIsCaptain`/`userIsVet` are all-true stubs; the genuinely-gated commands call a `GuildStateManager` predicate directly — `isConfirmedStaff()`, `isStaff()`, `areFeaturesEnabled()`, `isUnlocked()`, `isStaffOfAnyGuild()`, `isChiefOfAnyGuild()` and `isAuthenticatedThisSession()` are all in use.
- Avoid heavy work on the main thread; use `CompletableFuture` from `HttpClient`.
- Chat output: use `ChatUtils.sendLocalMessage(Component)` (or `sendLocalMessageNewBlock` for the first line of a new response). Both prepend the vetsmod badge and hand off to `LocalChatDispatch.dispatchToChat(Component, Style)`, which is thread-safe and marks the dispatch internal so the chat pipeline skips re-logging and the rewriter chain. Calling `dispatchToChat` directly skips that badge; some chat rewriters do so for lines they rebuild themselves. The badge's full or compact form is meant to follow the chat lines already shown. Today it counts only vetsmod's own lines, and it is chosen on the calling thread before `dispatchToChat` hops to the render thread, so a send from an HTTP completion thread can also race that count (bug `prepend-dedup-decided-off-thread-and-counts-only-mod-lines`).
- Tab completion: use Brigadier `SuggestionProvider`s, with `ConfigCommands.SUGGEST_CONFIG_KEYS` / `SUGGEST_CONFIG_VALUES` as templates.
- **A fixed set of choices does not need a `SuggestionProvider` at all.** Literal children give Brigadier its own suggestion list for free — `hard`/`soft`/`revoke` under `rsvp`, and `silent`/`passive`/`aggressive` under `anni`, are both spelled that way. Reach for `StringArgumentType.word()` plus a custom provider only when the value set is open or computed at runtime.
