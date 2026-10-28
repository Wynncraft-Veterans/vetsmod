---
name: vetsmod /wv Command Reference
description: Index of vetsmod's command surface — the /wv tree and root /motd, the intercepted chat commands, the dump key and click buttons; gates, handlers, side effects, bugs
type: project
originSessionId: dc63f47a-2d15-4f8d-9b6a-41d3049f0cc2
---
# vetsmod Commands Reference

This is the index of vetsmod's command surface. §1 is the Brigadier tree: the `/wv` command and a
standalone `/motd`. §2 is everything else a player can type, press or click that vetsmod acts on: the
commands its mixins intercept before they reach the server, the item-dump key, and vetsmod's own chat
buttons. Each §1 entry gives the command's path, its gates, its handler, what it does, its side effects
and its known bugs. §2's prefix table gives each intercepted prefix its gate, handler, behaviour and known
bugs, and its other entries say what triggers each input and what it does.
Where another doc owns the detail, the entry links it rather than restating it.

## 1. The `/wv` tree

### Registration

[`CommandRegistry.register`](../src/client/java/org/wynnvets/commands/CommandRegistry.java) registers the
roots `/motd` and `/wv` on the dispatcher it is given. `/wv`'s `debug`, `distribute` and `laurels` subtrees
are built elsewhere and mounted with `.then(…)`: `/wv debug` by
[`DebugCommands.buildCommandTree`](../src/client/java/org/wynnvets/debug/DebugCommands.java), whose `tree`
literal mounts
[`AnniDebugCommands.buildCommandTree`](../src/client/java/org/wynnvets/debug/anni/AnniDebugCommands.java),
`/wv distribute` by
[`DistributeCommands.buildCommandTree`](../src/client/java/org/wynnvets/distribute/DistributeCommands.java),
and `/wv laurels` by
[`LaurelCommands.buildCommandTree`](../src/client/java/org/wynnvets/laurels/LaurelCommands.java).
Those classes are the only builders. The classes their nodes call into, such as `HelpCommands`,
`ConfigCommands`, `AnniRsvpCommand`, `AnniScrollspotCommand`, `AnniSnapshotDebugCommands`,
`AnniHighlightDebugCommands` and `AnniDebugSuggestions`, register nothing.

`VetsmodClient.onInitializeClient` only adds `register` as a `ClientCommandRegistrationCallback`. Fabric
calls it on every login, with a new dispatcher, so the whole tree is rebuilt on each server join. It also
registers `CommandCompletionSync`'s tick listener, which keeps tab completion in step with the tree's
gates (see Gates).

A node with children but no command of its own goes to the server when typed bare, which answers
"Invalid command". `/wv distribute`, `/wv laurels` and the anni debug harness give each such node
[`CommandUsage.usage`](../src/client/java/org/wynnvets/commands/CommandUsage.java) as its command, a red
"Usage: …" line instead. The rest of the tree does not: a bare `/wv line` or `/wv anni rsvp` still goes
to the server.

### Gates

The tables name these kinds of gate:

- **Parse gate:** a node's `.requires(…)`. Brigadier tests it live when it parses a typed command:
  typing a refused child gets Fabric's red "Incorrect argument for command" (Brigadier's
  `dispatcherUnknownArgument`, which Fabric shows instead of forwarding), and nothing reaches the
  handler or the server. So a handler that re-tests its own parse gate refuses only if the gate's state
  changes between parse and run.
  Tab completion reads a copy instead: Fabric copies the client tree into the vanilla dispatcher only
  when a `minecraft:commands` packet arrives, leaving out each node whose `.requires` fails at that
  moment. A join's packet arrives before the auth ack, so on its own the copy would hide
  `/wv distribute`, `/wv check` and `/wv laurels` from confirmed staff (the chat box marks them red as an incorrect
  argument) until the next packet.
  [`CommandCompletionSync`](../src/client/java/org/wynnvets/commands/CommandCompletionSync.java)
  checks the `/wv` copy against every gate each client tick and calls
  `ClientCommandManager.refreshCommandCompletions()` when they disagree, so completion follows a gate
  within a tick of its change. A typed `/wv check` can also meet
  `GuildChatDispatcher`'s intercept before Fabric's hook sees it (below).
- **Runtime gate:** the handler's own test, which prints a refusal and returns.

`CommandRegistry.userIsVet` and `userIsCaptain` are all-true stubs ("TODO: Replace with correct permission
checks once available"). A node whose only parse gate is one of them is ungated, and the tables below say
so rather than naming the stub.

What each gate checks:

| Gate | Checks | Home |
|---|---|---|
| `isStaff()` | `StaffRankChecker`'s cached `/gu rank` answer: Captain+ in whatever guild the player was in when it last answered. Persisted, refreshed at most daily unless a caller forces it, as `/wv list world` does; cleared on leaving the guild or a guildless guild check | `GuildStateManager` |
| `isConfirmedStaff()` | `is_staff` from the last ok auth ack, which temporary-server resolves against the staff roster. An auth failure or leaving the server clears it | `GuildStateManager`, reading `V1ApiManager` |
| `isStaffOfAnyGuild()` | `isConfirmedStaff()`, or Captain+ in the current guild by Wynntils' live rank | `GuildStateManager` |
| `isChiefOfAnyGuild()` | the auth ack's confirmed rank is chief or owner, or Wynntils' live rank is Chief or Owner | `GuildStateManager` |
| `isUnlocked()` | `isReturners()`, or waitlist- or honourary-unlocked: an authenticated tier this session | `GuildStateManager` |
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
| `/motd` | none | none | `CommandRegistry.motd` | Prints the guild MOTD for a Returners, guildless-and-waitlist-unlocked or honourary-unlocked player, else the plain MOTD. An empty guild MOTD falls back to the plain one | GETs `VetsApi.GUILD_MOTD` for those players, and `VetsApi.MOTD` for everyone else or as the fallback | none |

`/wv motd` runs the same handler.

### `/wv help`

| Command | Parse gate | Runtime gate | Handler | What it does | Side effects | Bugs |
|---|---|---|---|---|---|---|
| `/wv help` | none | none | `HelpCommands.help` → `buildHelp` | The index page. `/wv help`, `/wv anni`, `/wv config`, `/wv motd` and `/wv debug` always; `/wv list` and `/wv staff` when `isUnlocked()`; `/wv list world` when `isStaff()`; `/wv distribute` when `isStaffOfAnyGuild()`, its node's gate (the row adds "(Chief/Owner)", which running it needs); `/wv return` and `/wv line` when `areFeaturesEnabled()`; `/wv check` and `/wv laurels <update|return>` when `isConfirmedStaff()` (the laurels row has no suffix: running either needs no more than seeing it, bar `update`'s Returners check). The `/wv anni` row points at `/wv help anni` for the modes and RSVP when the player is a vet (`isEligibleForEnrichment()`) or authenticated, and is the plain timer row otherwise. `/wv invite-force` is deliberately not listed | chat only | none |
| `/wv help config`, `check`, `laurels`, `return`, `staff`, `list`, `motd`, `anni`, `line`, `debug`, `debug set`, `debug trigger` | none | none | `HelpCommands.helpX` → `buildHelpX` | One detail page each. Most are a usage line, a line of prose and a `Requires:` footer; the laurels page lists its three commands above the footer; the config page lists its keys with no footer, the anni page adds the three modes and, when `isAuthenticatedThisSession()`, `rsvp hard`/`soft`/`revoke` under the timer line, and the debug pages list their subcommands, keys or actions above the footer | chat only | none |

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
  DENIED on a permission the server has granted. `access` never returns `PUBLIC`; the motd, anni,
  debug, debug set and debug trigger pages pass it as a constant. GREEN means *this should work*; AQUA means
  *the server agrees*. Four of the six gated pages take `sessionBacked(met)`, whose confirmation is
  `met && isAuthenticatedThisSession()` (so an authenticated viewer who fails the local gate still sees
  DENIED). `/wv check` and `/wv laurels` are the exceptions: their gate is `isConfirmedStaff()`, the
  server's roster answer, which each passes as both signals, so their footers are AQUA or RED and never
  GREEN, and the index rows advertise them on the same gate. The detail builders take an `Access` rather than reading
  `GuildStateManager`, for the same reason `buildHelp` takes its gates as parameters.

### `/wv check` and `/wv invite-force`

| Command | Parse gate | Runtime gate | Handler | What it does | Side effects | Bugs |
|---|---|---|---|---|---|---|
| `/wv check <playerName>` | `isConfirmedStaff()` | the same, so its refusal fires only if the state changes between parse and run | `CommandRegistry.check` | `UserInfoFetcher.checkUser`: name → UUID through the `PlayerLookup` cascade, then the Wynncraft profile, dazebot's membership snapshot and, once there is a profile, guild-roster cross-checks. Then `CautionCommands.runCheckCautions` for the caution history | HTTP: the name lookup cascade, the Wynncraft profile and guild rosters. WebSocket: `check_membership` and `caution_check` frames | none |
| `/wv invite-force <playerName>` | `isConfirmedStaff()` | the same, so its refusal fires only if the state changes between parse and run | `CommandRegistry.inviteForce` → `InviteGate.forceDispatch` | Sends `/gu invite <playerName>` with every `InviteGate` check skipped, and prints "Dispatching /gu invite <name> (vetsmod gate bypassed)." in gold, with the command itself in yellow. `InviteGate`'s `[Invite anyway]` button runs it | in-game `/gu invite`, sent through `GuildChatDispatcher.sendCommandBypassed` | none |

`/wv check <playerName>` and `/wv invite-force <playerName>` read the name with
`StringArgumentType.string()`, so a name is one unquoted token or a quoted string. Fabric's hook handles a
typed `/wv check` before `GuildChatDispatcher`'s (§2, after the table).

### `/wv return`, `/wv list`, `/wv staff` and `/wv motd`

| Command | Parse gate | Runtime gate | Handler | What it does | Side effects | Bugs |
|---|---|---|---|---|---|---|
| `/wv return` | none | `areFeaturesEnabled()`: "/wv return is restricted to vets members." | `CommandRegistry.returnInfo` → `ReturnFetcher.fetchReturn` | Prints the latest return-channel post, with its Discord timestamps rewritten and a link footer (below) | GETs `VetsApi.RETURN` | none |
| `/wv list` | ungated | `isUnlocked()`: "/wv list is restricted to vets members." | `CommandRegistry.list` → `ListFetcher.fetchList` | Prints "Looking up online members...", then the online members grouped by tier and VetsMod status: staff underlined, supporters with a glint | GETs `VetsApi.LIST`; Wynntils' Wynncraft API lookup of the Returners guild (`Models.Guild.getGuild`); for a Returners caller only, the tab list's guild entries (other callers' tab column is another guild's, so it is never read), merged into the list and sent as a WebSocket `tablist` frame | none |
| `/wv list world` | ungated | `isStaff()` and `isReturners()`, after a `refreshStaffStatusIfNeeded` forced for a non-steward unless a staff check finished in the last minute: "We are still checking your permissions, please retry in a moment." while a refresh starts or runs, then "/wv list world is restricted to stewards." (the cached staff flag is cleared on leaving the guild but is not tracked per guild, so it alone does not pass) | `CommandRegistry.listWorld` → `WorldListFetcher.fetchWorldList` | For staff: finds each online member with `/find` and groups them by server region (the GeoIP2 continent codes AF, AS, EU, NA, OC and SA, then Other and Private) | in-game `/gu rank` (forced for a non-staff caller unless a staff check finished in the last minute), `/find` per member; GETs `VetsApi.LIST` and `VetsApi.STAFF`; Wynntils' Wynncraft API lookup of the Returners guild; a `PlayerLookup` name lookup for each member `/find` did not place; for a Returners caller only, the tab list's guild entries (merged into the list, and sent as a WebSocket `tablist` frame) | none |
| `/wv staff` | ungated | `isUnlocked()`: "/wv staff is restricted to vets members." | `CommandRegistry.staff` → `StaffFetcher.fetchOnlineStaff` | Prints online staff, sorted by rank, then by name | GETs `VetsApi.STAFF` | none |
| `/wv motd` | none | none | `CommandRegistry.motd` | As the root `/motd` | as `/motd` | none |

A non-steward's first `/wv list world` forces a staff re-check, so a fresh promotion is caught, and gets the
yellow "retry" line; a call within a minute of a finished check gets the red refusal instead. It has no
`isUnlocked()` check: the staff-and-Returners test is the whole gate.

**`/wv return`'s timestamps and footer.** The post is authored in Discord, so the body arrives with
Discord's `<t:EPOCH:STYLE>` timestamp markup intact; temporary-server's markdown converter passes it
through untouched.
[`DiscordTimestamps.expand()`](../src/client/java/org/wynnvets/chat/DiscordTimestamps.java) rewrites it
into the reader's own timezone client-side (that is the point of the markup: the API cannot know the
zone), supporting every Discord style, with `f` as the default, and hangs a hover carrying the
absolute time, its zone and the relative offset off each expansion. Malformed or out-of-range epochs fall
through as the raw markup.
[`ReturnFetcher.announcementFooter()`](../src/client/java/org/wynnvets/fetcher/ondemand/ReturnFetcher.java)
then appends `For more info, see the original announcement post in this discord channel!`, where `this` deep-links to the return
channel (`discord.com/channels/<guild>/<RETURN_CHANNEL_ID>`). The API serves the message body with no
message ID, so the link targets the channel rather than the post, and its hover says "Open the return
channel in Discord". The footer is skipped when the body is
blank (nothing cached upstream).

### `/wv anni`

| Command | Parse gate | Runtime gate | Handler | What it does | Side effects | Bugs |
|---|---|---|---|---|---|---|
| `/wv anni` | none | none | `CommandRegistry.anni` → `StampFetcher.fetchStampAndCreateAnniCommandMessage` | The anni status: the snapshot render (`AnniCommandRenderer`) when a snapshot is cached or, for a vet, an on-demand pull brings one, else, or when the renderer declines, the legacy stamp countdown | WebSocket `anni_query` on a cold cache, for a vet (`isEligibleForEnrichment()`) only; GETs `VetsApi.STAMP` on the legacy path | none |
| `/wv anni silent`, `passive`, `aggressive` | none | the `/stream` mutex, for passive and aggressive: "You have /stream, which is incompatible with vetsmod anni features, active. We would strongly prefer you use /toggle ghosts NONE instead, which does the same thing without hiding you from organisers!" | `CommandRegistry.anniMode` → `AnniModeManager.transitionTo(mode, USER_COMMAND)` | Sets the anni mode and prints it; a refusal changes nothing | writes `vetsAnniMode`, `vetsAnniModeUserSet` and `vetsAnniUserMode` | none |
| `/wv anni rsvp hard`, `soft`, `revoke` | none | `isAuthenticatedThisSession()`: "You aren't authenticated (~vetsmod on vetscord). For now, use the discord \rsvp on discord (or use ~vetsmod)." | `AnniRsvpCommand.hard`, `soft`, `revoke` | Sends the RSVP and prints the result; on success, pulls a fresh snapshot (not after a revoke that found no RSVP to withdraw) | WebSocket `anni_rsvp`, then `anni_query` on success | none |

[vetsmod_mwe_anni.md](vetsmod_mwe_anni.md) owns all three: §"`/wv anni` render dispatch" has the branch
list and the not-announced, far-out and imminent forms; §Mode state has the mode keys, the mutex and
the automatic transitions; §"`/wv anni rsvp`" has the client, the auth chain and the locked decisions. The
imminent form's `[Silent] | [Passive] | [Aggressive]` buttons run the mode literals, and the far-out RSVP
line's `[Hard]` / `[Soft]` buttons suggest `/wv anni rsvp hard` and `soft`.

**RSVP, server side.** An RSVP goes through temporary-server, which stamps the session's `mc_uuid`, to
vets-anni, which reuses its Discord cog's helpers: the RSVP row and the auto-placement come from the same
code as a Discord `\rsvp`'s, and the public confirmation line, built in the cog's format, goes out through
the same `_post_public`. vetsmod never sees the private reply, but the ack carries a `result`
(`recorded`, `withdrawn` or `nothing_to_withdraw`): a revoke with no active RSVP prints a gray "You had no
active RSVP to withdraw." and still pulls the snapshot, and a server that predates the field sends none, so
its revoke still prints "Your RSVP has been withdrawn.". vets-anni closes RSVP 90 minutes before an anni,
server-side: vets-anni refuses a hard or soft RSVP inside that cutoff with "RSVP is closed (within 90 min
of anni)", and never refuses a revoke for it. The server-side detail, including the placeholder name a first-time player
gets, is in vets-anni's `.claude/snapshot_integration.md`, linked from vetsmod_mwe_anni.md §"`/wv anni
rsvp`".

### `/wv config`

| Command | Parse gate | Runtime gate | Handler | What it does | Side effects | Bugs |
|---|---|---|---|---|---|---|
| `/wv config` | none | none | `ConfigCommands.configList` | Prints every key in `VetsConfigSchema.USER_CONFIG_KEYS`, in order, with its value | chat only | none |
| `/wv config <key>` | none | none | `ConfigCommands.configGet` | Prints one key's value; "Unknown config key: <key>" for a key outside `USER_CONFIG_KEYS` | chat only | none |
| `/wv config <key> <value>` | none | none | `ConfigCommands.configSet` | Sets the key. Every key also takes `default`, which writes its value from the shipped `config.yml` (a tri-state's `default` clears it instead); string and int keys also take `reset`, its older alias. Refuses an unknown key, a value of the wrong type, and a role-style or flash-intensity value outside its list | persists the config | none |

Every `key = value` line the three forms print, and the `/wv debug set` prints, is built by
`util/ConfigValueText`. `ConfigCommands.Kind.of(key)` picks which of `VetsConfig`'s typed accessors a key
uses, and the same enum drives both the read paths and the write dispatch, so the type ladder is
written once. A colour or sprite value is validated with `LegacyItemStyle.isValidColor()` and
`isValidSprite()`, and a `vetsAnniRoleStyle` or `vetsAnniFlashIntensity` value with
`VetsConfigSchema.isFixedValue()` against that key's list, folded with `Locale.ROOT`.
[vetsmod_config.md](vetsmod_config.md) owns the keys themselves. Its suggestion
providers, `ConfigCommands.SUGGEST_CONFIG_KEYS` and `SUGGEST_CONFIG_VALUES`, are in §3.

### `/wv line`

| Command | Parse gate | Runtime gate | Handler | What it does | Side effects | Bugs |
|---|---|---|---|---|---|---|
| `/wv line church`, `scrap`, `bat`, `hegea`, `lighthouse` | none | `areFeaturesEnabled()`: "/wv line is restricted to vets members." | `CommandRegistry.lineToggle` → `TerritoryLineManager.toggle` | Toggles one territory's guild-XP boundary line and prints Enabled or Disabled | the first enable GETs `api.wynncraft.com/v3/guild/list/territory` | none |

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
| `/wv debug <true\|false>` | none | none | `DiagnosticsHandler.execute` | Turns `VetsLogger`'s debug flag on or off. Case-insensitive; any other word prints a usage line and changes nothing | writes `vetsDebugEnabledAt` | none |
| `/wv debug set` | none | none | `DebugCommands.debugConfigList` | Lists the debug config keys (`itemDump` is the only one) | chat only | none |
| `/wv debug set <key> [<value>]` | none | none | `DebugCommands.debugConfigGet`, `debugConfigSet` | Prints or sets a debug key. Refuses an unknown key, and a value other than `true` or `false` | the set form persists the config | none |
| `/wv debug trigger <name>` | none | none | `DebugCommands` (see below) | One of the diagnostic dumps below | chat or log; `forceChecks` also queues `/gu rank` and `/gu stats` and, when Wynntils reports a guild, can send a WebSocket `register` frame and re-run the stamp print, which can send an `anni_query` and GET `VetsApi.STAMP` (below) | none |
| `/wv debug tree anni …` | none | `requireDebug` on every leaf; `requireStaffOrOrganiser` on the `scrollspot` leaves | `AnniDebugCommands`, `AnniSnapshotDebugCommands`, `AnniHighlightDebugCommands` | The anni debug harness (below) | see below | none |

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
  `AnniOutlineRegistry` hit/miss, tier, role, the display name as `original=` (each `§` shown as `&`, so
  Wynncraft's embedded team-colour code is visible), and the colour `NametagMixin`'s anni branch would
  resolve right now. It reads the registry and `getDisplayName()` directly; it does not observe the mixin
  or `state.nameTag`.
- `ghostsPromptDump` — `GhostsPromptHandler.debugDump()`: the aggressive gate, toggle, zone, stamp and
  per-player ghost state, plus whether the prompt would fire. Writes to the log, not chat.
- `zoneLinesDump` — the aggressive gate, the `vetsAnniZoneLines` toggle, the cold-cache flag, and every
  cached `AnniZone.Disc` with its horizontal (x/z) squared distance to the player.
- `rsvpDump` — `isAuthenticatedThisSession()`, the in-flight queue depth, `lastAttemptedNotice`, `lastAck`
  and the current snapshot's `rsvp` block, all read by `AnniRsvpClient.debugDump()`. Writes to the log, not
  chat. The diagnostic for "why did my `/wv anni rsvp` not land".

**The anni debug harness** under `/wv debug tree anni` has 11 literals (`snapshot`, `guess`, `external`,
`time`, `zone`, `flash`, `mode`, `scrollspot`, `alert`, `rsvp` and `registry`) and 24 executable leaves,
both pinned by `AnniDebugCommandsTest`, as are the 18 nodes above a required argument that print a red
"Usage: /wv debug tree anni …" line when typed bare (rather than going to the server as "Invalid command"). Every leaf refuses without debug logging ("anni debug commands require debug
logging - run /wv debug true first"). The `scrollspot` leaves also refuse a player who is neither staff nor
an organiser ("scrollspot is for party hosts / staff only."), and the ones that write to the server
(`set`, `here` and `clear`) also need an authenticated session. The leaves with effects outside the client's memory are `snapshot dump` (writes a
file under `vetsmod/dumps/anni/`), `snapshot refresh` and `guess` (`anni_query`), `scrollspot set`, `here`
and `clear` (`anni_scrollspot_set`), `rsvp hard`, `soft` and `revoke` (the same as `/wv anni rsvp`), and
`mode set` (writes `vetsAnniMode`, bypassing the `/stream` mutex). Each leaf is described in
[vetsmod_mwe_anni.md](vetsmod_mwe_anni.md) §Debug harness.

### `/wv laurels`

Tree built in [`LaurelCommands.buildCommandTree()`](../src/client/java/org/wynnvets/laurels/LaurelCommands.java).
It feeds the vets laurels what the server cannot compute; [vetsmod_laurels.md](vetsmod_laurels.md) §8 owns the
behaviour.

| Command | Parse gate | Runtime gate | Handler | What it does | Side effects | Bugs |
|---|---|---|---|---|---|---|
| `/wv laurels update` | `isConfirmedStaff()` (on the `laurels` node) | `isConfirmedStaff()` again, "/wv laurels is restricted to authenticated stewards.", then `isReturners()`, "/wv laurels update is restricted to the Returners guild." | `LaurelCommands.update` | Submits a `DistributeRuns` run (noun "laurels update") that runs the steps the server's `collect` list asks for: today the Members-menu streak crawl. Prints "Nothing for this client to collect in-game." when no step is left | in-game `/guild manage` and Members-menu clicks; a `laurels_set` frame | none |
| `/wv laurels return <1\|2\|3> <playerName>` | `isConfirmedStaff()` | `isConfirmedStaff()` again, the same red line | `LaurelCommands.setPlace` | Puts the player on that place of the weekly-return podium | a `laurels_set` frame | none |
| `/wv laurels return clear` | `isConfirmedStaff()` | `isConfirmedStaff()` again, the same red line | `LaurelCommands.clear` | Empties the podium | a `laurels_set` frame | none |

The places are literal children (`1`, `2`, `3`), so completion offers them, and `<playerName>` is read with
`StringArgumentType.string()` like `/wv check`'s. Each node short of a finished command prints a red usage
line (`/wv laurels <update|return>`, then the `return` forms). The gate is `isConfirmedStaff()` on both
sides because the frame is refused from any session the server does not treat as staff.

### `/wv distribute <name|@selector> <aspects|tomes|emeralds> <count>`

Tree built in [`DistributeCommands.buildCommandTree()`](../src/client/java/org/wynnvets/distribute/DistributeCommands.java).
It automates the Guild Management GUI to hand a resource to guild members.

| Command | Parse gate | Runtime gate | Handler | What it does | Side effects | Bugs |
|---|---|---|---|---|---|---|
| `/wv distribute <name\|@selector> <aspects\|tomes\|emeralds> <count>` | `isStaffOfAnyGuild()` | `isChiefOfAnyGuild()`, via `DistributeCommands.ensureChief`: "You must be a guild Chief or Owner to use /wv distribute." | `DistributeCommands.distribute` | Hands the resource out by pressing buttons in the Guild Management GUI, to one player or to a selector's recipient set | in-game `/guild manage` and GUI clicks; HTTP to the Wynncraft guild API (`NameResolver`) and, on an aspects run, the NoAspects opt-out list (`VetsApi.NO_ASPECTS`) | none |

**Two gates, deliberately different tiers.** The parse gate, `isStaffOfAnyGuild()` (server-confirmed staff,
or captain+ by Wynntils' live rank), lets staff autocomplete the command (see Gates for how completion
follows it); the runtime gate,
`ensureChief()` → `isChiefOfAnyGuild()`, is the only Chief/Owner check in vetsmod for this command. A
strategist sees the command and gets the red refusal on run. That is **not** the shape of `/wv check` and
`/wv invite-force`, which re-check the same predicate their `.requires` uses; here the two gates use
different predicates, so the red line is the normal path for staff below chief.

- `<name>` — `NameOrSelectorArgument`, a custom Brigadier `ArgumentType<String>` reading one
  space-delimited token. The built-in `StringArgumentType.string()` restricts unquoted input to
  `[0-9A-Za-z_.+-]` and would fail to parse `@random` at the `@`. Suggests the selectors plus current guild
  usernames from `Models.Guild.getGuildMembers()`.
- `<resource>` — `aspects` | `tomes` | `emeralds`, mapping to `MemberSlotPresser.Resource` hotbar buttons
  0/1/2. One press sends 1 Aspect, 1 Tome, or 1024 Emeralds.
- `<count>` — `IntegerArgumentType.integer(1, 255)`. The upper bound is an unsigned-byte cap against
  typo'd bulk sends.

Typed short of `<count>`, the command prints a red usage line rather than going to the server:
"Usage: /wv distribute <name|@selector> <aspects|tomes|emeralds> <count>" for a bare `distribute` or
`<name>`, and the same with the resource filled in after a resource literal.

The `@`-selectors decide the recipient set, each one row of the `SELECTORS` table in
`DistributeCommands`, matched before the literal-name fan-out. An `@` token matching no row is refused in
red ("Unknown selector @x. Choose from …"), before any lookup. What `<count>` *means* changes with the
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

## 2. Intercepted commands and other inputs

Everything here, except the chat buttons that run or fill in a §1 command, reaches vetsmod outside
Brigadier. vetsmod's mixins on `ClientPacketListener.sendCommand` inject at its `HEAD`, cancellable:
[`UnlockCommandMixin`](../src/client/java/org/wynnvets/mixin/client/command/UnlockCommandMixin.java) for
`/unlock`, and
[`GuildChatCommandMixin`](../src/client/java/org/wynnvets/mixin/client/chat/GuildChatCommandMixin.java),
which hands each command that reaches it to
[`GuildChatDispatcher.intercept`](../src/client/java/org/wynnvets/chat/dispatcher/GuildChatDispatcher.java)
(a command that an earlier `HEAD` hook cancels never reaches it; see the `/wv check` note below).
Fabric's command API injects at the same point to run §1's client commands, and Wynntils does too.
[vetsmod_mixins.md](vetsmod_mixins.md) owns the hooks.

These rules hold for everything below:

- **Typed input is normalised first.** The chat screen trims a typed line and collapses its runs of spaces
  before it calls `sendCommand`, so a typed `/v ` arrives as `v`.
- **vetsmod's own sends pass through.** `GuildChatDispatcher.sendCommandBypassed` sets a `BYPASS` flag
  around its `sendCommand` call, and `intercept` passes everything through while it is set. That is how
  `/wv invite-force`, the invite gate's allow path and the eject dispatch send their in-game commands
  without re-running the intercept. The commands vetsmod queues through Wynntils (`/msg`, `/find`,
  `/gu rank`, `/gu stats`, and distribute's `/guild manage`) re-enter the hooks too, and match no prefix.
- **A click takes another path.** A chat button that runs a command calls `sendUnattendedCommand`, which
  no vetsmod mixin hooks; [vetsmod_mixins.md](vetsmod_mixins.md) has where such a click goes. A button
  that names one of §1's client commands works. A button that names an intercepted command would not reach
  `GuildChatDispatcher`, so the caution buttons fill the chat box instead and staff press Enter.

### `/unlock`

`/unlock <key>` stores the player's vetsmod key. The key is a 43-character URL-safe base64 bearer token
(32 bytes of entropy via `secrets.token_urlsafe(32)`) issued by dazebot's `/vetsmod` Discord command.

1. `UnlockCommandMixin` matches a command that starts with `unlock` in any letter case, as
   `GuildChatDispatcher`'s prefixes do, followed by a space or the end, and cancels the send. So
   `/Unlock <key>` is caught too. A bare `/unlock` prints a red usage line.
2. It calls `GuildStateManager.tryUnlock(key)` → `UnlockManager.tryUnlock(key)`.
3. Local checks: length ∈ [32, 200], charset URL-safe base64 (`a-zA-Z0-9_-`). A failure prints a red
   `That doesn't look like a vetsmod key. …` and stores nothing.
4. It persists the key to `vetsAuthKey`, clears the session's tier, verified flag and failure reason, arms
   a one-shot flag that makes the next auth ack show its result, even one that arrives after a later
   connection (a disconnect does not clear it), and calls
   `V1ApiManager.sendAuth(key)`. That sends an `auth` frame on the inbound WebSocket, or, while the socket
   is down (always, off a Wynncraft server, where no socket opens), returns without sending; the key then
   goes out when the socket next connects.
5. The player sees `Saved your key. Verifying with the server…` (yellow), whether or not the
   frame went out.
6. The server's ack arrives asynchronously. An ok ack reaches `UnlockManager.onAuthSuccess`, which shows
   `Authentication verified - tier: <tier>` (green) when step 4's flag is armed or
   `printSuccessfulAuth` is on. A rejection reaches `onAuthFailure`, which always prints
   `Authentication failed: <reason>. …` (red) in chat. Where the success line renders is in
   [vetsmod_guild_system.md](vetsmod_guild_system.md) §4, and how the setting latches is in
   [vetsmod_config.md](vetsmod_config.md)'s `printSuccessfulAuth` row.

The legacy SHA-256 password matching and its on-disk markers have been removed. A player with no stored
key gets the session-start warning to get a key only in Returners — see
[vetsmod_guild_system.md §5](vetsmod_guild_system.md#5-sessionauthwarning), case 3.

Bugs: none.

### The intercepted prefixes

`GuildChatDispatcher.intercept` matches twelve prefixes, case-insensitively. The eight staff commands also
match their bare word, through `argsAfter` (the word alone, or the word and a space), since typed chat arrives
trimmed. `/g`, `/wg`, `/gu invite` and `/guild invite` keep a trailing space that is part of the prefix.
Off Wynncraft (another server, or singleplayer),
none of them is intercepted: every command passes through to that server. On Wynncraft, `/g` is checked
first; for every other prefix, a player who fails `isUnlocked()` has the command passed through to the
server as typed. "Passes through" below means the same.

| Typed | Gate | Handler | What happens | Bugs |
|---|---|---|---|---|
| `/g <message>` | none | `handleGuildChat` | A guildless waitlist-unlocked player's line goes to the bridge as `waitlist`, and a Returners player's line goes as `queue` while they sit in a world queue; both are cancelled and echoed locally, with the banned characters removed ([vetsmod_chat_pipeline.md](vetsmod_chat_pipeline.md) §4), or refused with a red "Not sent" line while the inbound socket is down (a `queue` line also while Wynntils doesn't know the guild rank). A Returners line with a `\|\|` spoiler is re-sent spoiler-encoded, or refused in red if that passes 253 characters. Anything else passes through. The relays and the encoding honour `handleSpoilers` | `url-repair-merges-following-non-lowercase-token` |
| `/wg <message>` | `isUnlocked()` | `handleHonouraryChat` | Always cancelled. An honourary-unlocked player's line goes to the bridge as `honourary` and is echoed locally, with the banned characters removed, or is refused like `/g`'s relays while the inbound socket is down; anyone else gets a red "This feature is restricted to honourary users. If you think you should have /wg, follow ~vetsmod in #bot-commands to fix your perms." | none |
| `/v <message>` | `isUnlocked()`, then `isStaff()`; a non-staff command passes through, and a staff-rank refresh starts | `handleStaffChat` | Refused in red during `/stream` and above 234 characters. Otherwise, once the staff-presence check passes, echoed locally and sent to each other online staff member as `/msg <name> 🔐<message>`, with the banned hard set removed from both | none |
| `/a <message>` | as `/v` | `handleAnnounce` | Once the staff-presence check passes, re-sent as `g ‼<message>` | none |
| `/encourage <version>` | as `/v` | `handleEncourage` | Once the staff-presence check passes, re-sent as `g ⚠⚠⚠ If you had vetsmod, you would see a rainbow! For more info, use ~vetsmod in #bot-commands on vetscord. (v<version>) ⚠⚠⚠` | none |
| `/wv check <playerName>` | `isUnlocked()`, then `isConfirmedStaff()`: "/wv check is restricted to authenticated stewards." | `handleWvCheck` | As §1's `/wv check`, with the whole rest of the line as the name | §1's `/wv check` row |
| `/gu invite <name>` | `isUnlocked()`, then `isReturners()` and a non-empty name | `InviteGate.checkInvite` | The invite gate, below | none |
| `/guild invite <name>` | as `/gu invite` | `InviteGate.checkInvite` | As `/gu invite`; an allowed invite is sent as `gu invite` | as `/gu invite` |
| `/caution-go <name> [message]` | `isUnlocked()`, then `isConfirmedStaff()` | `CautionCommands.runCautionGo` | Commits a caution with no preflight (`caution_add` with `confirm`) | none |
| `/caution <name> [message]` | as `/caution-go` | `CautionCommands.runCaution` | Asks for a preflight (`caution_add`); commits at once if the caution crosses no threshold | as `/caution-go` |
| `/warn <name> [message]` | as `/caution-go` | `CautionCommands.runWarn` | Commits a formal warning (`warn_add`) | none |
| `/eject <name> <message>` | as `/caution-go` | `CautionCommands.runEject` | Commits an eject (`eject_add`); refuses a missing message | none |

The commit commands and `/wv check` wait for the server's ack. If the inbound socket is lost before it
arrives, or, against a server that echoes request ids, none comes within 45 s (15 s for `/wv check`'s two
reads), a commit prints a red "[<kind>] Outcome unknown (connection lost) - run /wv check <name> before
retrying", with "(no reply)" for the deadline, since the server may have recorded it and a retry would add
points and send another DM. An eject's line ends "; if it went through, kick or demote them yourself" instead,
and nothing is dispatched in game. `/wv check` then shows "(snapshot unavailable)" and a gray "Caution history
lookup failed: Connection lost before reply." (or "No reply from the vets server."). A disconnect, or a frame
that was never written, still gets "[<kind>] failed: Connection closed before reply.".

`/v`, `/a` and `/encourage` share `DispatchCoordinator`'s staff-presence check, and a player it cannot confirm
as staff gets a yellow notice naming why, and nothing is sent: "Message not deliverable (permission verification
in progress)" while another check is running or the feed does not list them yet, or "Message not deliverable
(permission verification unsuccessful)" when the check itself failed; [vetsmod_chat_pipeline.md](vetsmod_chat_pipeline.md) §9 owns that check and the `/v` fan-out.
The waitlist, queue and honourary relays check `V1ApiManager.isInboundConnected()` first. While the socket is
down they echo nothing and print a red "Not sent: vetsmod is not connected to the vets API right now. Try again in
a few seconds.", since `V1ApiManager.sendInbound` would drop the line silently. The queue relay also refuses, with
a red "Not sent: your guild rank isn't known yet. Try again in a few seconds.", while Wynntils doesn't know the
player's guild rank, since the server rejects a queue frame without one. A line the server refuses after the echo
(inbound processing disabled, the tier gate, a validation error, a lost session) is followed by a red "Not delivered
(<reason>)" line, with the reason "relay paused", "not authenticated", "not allowed for your tier", "server busy",
"connection closed" or the server's own detail; this needs a server that echoes request ids, and an older server's
refusal goes unreported. A lost socket or no reply within 15 s prints nothing, since the line may well have been
delivered. A waitlister who joins Returners mid-session re-authenticates, so the
server's tier follows within a few minutes ([vetsmod_guild_system.md](vetsmod_guild_system.md) §4).

A bare `/v`, `/a`, `/encourage`, `/caution`, `/caution-go`, `/warn`, `/eject` or `/wv check` reaches its
handler with no arguments and prints its usage line; `/eject <name>` with no message prints it too.

The chat-path `/wv check` and Fabric's §1 node both hook a typed `/wv check`, and whichever runs first and
acts on it cancels the send, so the other never sees it. Fabric's runs first: both `HEAD` injects are at the
default priority, and Fabric's config is applied first, which an in-game check confirmed on 2026-10-23
([vetsmod_mixins.md](vetsmod_mixins.md) §"Injection priorities"). So a correctly spelled `/wv check` always
goes to the §1 node: a confirmed steward gets the lookup, and an unlocked player who is not confirmed staff
gets Fabric's red "Incorrect argument for command", never this row's refusal. The chat branch is reached
only by a spelling Fabric rejects at the root, such as `/WV check X`.

`/msg` is not intercepted. vetsmod emits it: the `/v` fan-out queues one per recipient, and the names in
`/wv list` and `/wv list world` are buttons that fill the chat box with `/msg <name> `.

**The caution family.** `/caution`, `/caution-go`, `/warn` and `/eject` each send a staff-action frame over
the inbound WebSocket (`V1ApiManager.sendStaffActionFrame`) and print the server's answer; temporary-server
checks that the session is authenticated staff, and dazebot records the action. A `/caution` that would
cross a threshold comes back as `would_trigger`, and `CautionCommands` prints the threshold and its buttons.
With a typed reason there is one, the green `[Confirm and send]`; without one there are two, the green
`[Send generic warning]` and the aqua `[Type a custom warning]`. Every button fills the chat box with
`/caution-go …` (the reason carried over, or a bare target for the generic warning), and the player presses
Enter, which sends it through `GuildChatDispatcher`. None runs the command on click, since a click would skip
that intercept. A commit from any of the four whose ack reports `triggered="eject"`
dispatches `/gu kick <name>` or `/gu rank <name> recruit`, whichever the ack's `suggested_dispatch` names.
For a player who is not confirmed staff, the four commands pass through to Wynncraft as typed, as
`GuildChatDispatcher`'s comment there intends. `/wv check` appends the target's caution history
(`caution_check`).

**The invite gate.** For a Returners player, `/gu invite <name>` and `/guild invite <name>` are cancelled
and handed to [`InviteGate.checkInvite`](../src/client/java/org/wynnvets/commands/InviteGate.java), which
checks the target and then sends `/gu invite` itself, through `sendCommandBypassed`. Only Returners
players reach the gate: everyone else (waitlist, honourary, other guilds, guildless) is passed through to
Wynncraft untouched, with no vetsmod output. Only the first word after `invite` is kept. A player who is
not confirmed staff is stopped at once unless they have an authenticated session of tier `member`, since
temporary-server answers the invite verdict only to such a session (`InviteGate.entryVerdict`): with no key
stored, or a stored key an auth ack rejected, in red with "Unable to handle /gu invite: vetsmod is running
unauthenticated. For more details, use ~vetsmod in #bot-commands on vetscord." followed by the clickable
aqua `[https://wynnvets.org/discord]` link (`SessionAuthWarning.withDiscordLink`); with a stored key still
being checked, in yellow with "We are still checking your permissions, please retry in a moment."; an
authenticated session of another tier (waitlist, honourary, other) gets the yellow line while a Returners
join's re-auth is still in progress (`GuildStateManager.isReturnersReauthDue`: until 30 s after the join's
330 s retry runs, and while `ReturnersReauth.shouldReauth` still holds, so a fresh Returners member waits
for the member tier), else the red one, so a player who was in Returners at login, or whose retry and its
30 s have passed, is not told to retry. The typed command stays cancelled and nothing is sent. It then resolves the
name. If no lookup source can be reached, it prints a yellow
notice and sends the invite; a name that no source knows is refused, in red, with "Unable to find a player
named '<name>', check spelling?" (a Mojang host saying no such profile decides that on its own, whatever the
third-party sources answered; the Vets snapshot step abstains rather than miss transiently). Otherwise it gathers the target's Wynncraft
profile, dazebot's membership snapshot and, for a player who is not confirmed staff, the Returners guild's
size and level, and decides, in this order:

- **the snapshot lookup fails:** cancelled, in red, with a pointer to `/wv invite-force` for confirmed
  staff. Confirmed staff get the full snapshot, which must carry the Discord blob; a member who is not
  staff gets only temporary-server's invite verdict (`may_invite`, `waitlist_count`), never the blocklist
  itself;
- **the profile cannot be fetched:** a blocklist check alone (`blocksInvite`: the staff snapshot's
  `blocklisted`, or a `may_invite: false` verdict). A blocked target is refused in red with no button:
  staff see "'<name>' does not appear to meet vets' recruitment criteria. Please ask a steward.", anyone
  else the line below. Anyone else is invited after a yellow notice that the invite is going out anyway;
- **the target is blocklisted, joined Wynncraft on or after 2020-01-01, or hides their first join:**
  confirmed staff get a gold warning and an `[Invite anyway]` button that runs `/wv invite-force <name>`;
  anyone else gets a red "This user is not eligible to join the guild, ask a Steward." (the user's
  wording), the same whichever check failed, so it does not reveal the blocklist;
- **a player who is not confirmed staff, with the guild near capacity against its waitlist:** a yellow
  notice ("The guild has more waitlisted users (N) than open slots. …") asking them to point the target at the waitlist;
- **otherwise:** the invite is sent.

### Other inputs

| Input | Gate | Handler | What happens |
|---|---|---|---|
| Numpad `+` | the `itemDump` debug key (`/wv debug set itemDump true`), a focused window, the key's rising edge | `DebugKeyHandler` → `ItemDumpHandler.dump` | Writes the hovered item to a new JSON file under `vetsmod/dumps/items/`, or prints a yellow "No item under cursor. Hover over an item in an inventory." `DebugKeyHandler` polls the key each client tick and returns first while `itemDump` is off |
| Wynntils' screenshot keybind, in an inventory | none | `LegacyTooltipEventListener` → `LegacyItemHandler.notifyScreenshotKeyPressed` | Tells the legacy-item code the screenshot key was pressed over a legacy item; see [project_legacy_items.md](project_legacy_items.md) |
| Typing in the chat box while in a world queue | none | `CommandSuggestionsMixin` | Suppresses the chat box's usage hint and its red parse error; see [vetsmod_mixins.md](vetsmod_mixins.md) |

vetsmod's own chat buttons:

| Button | Where | Click | Result |
|---|---|---|---|
| `[Invite anyway]` | the invite gate's staff warning | runs `/wv invite-force <name>` | works: a §1 client command |
| `[Silent]` / `[Passive]` / `[Aggressive]` | `/wv anni`'s imminent form | runs `/wv anni <mode>` | works: a §1 client command |
| `[Confirm and send]` / `[Send generic warning]` | the `/caution` preflight | fills the chat box with `/caution-go …` | works after Enter |
| `[Type a custom warning]` | the `/caution` preflight, when no reason was typed | fills the chat box with `/caution-go <name> ` | works after Enter |
| `[Hard]` / `[Soft]` | `/wv anni`'s far-out RSVP line | fills the chat box with `/wv anni rsvp hard` or `soft` | works after Enter |
| player names | `/wv list`, `/wv list world` | fill the chat box with `/msg <name> ` | a Wynncraft command |
| `[We suggest: /toggle ghosts none]` | the anni ghosts prompt | fills the chat box with it | a Wynncraft command |

Links in chat (Discord, the anni site, URLs in messages) open a browser and run no command.

## 3. Suggestion providers

Every suggestion provider in the tree, by builder. A node with no provider and only literal children gets its
suggestion list from Brigadier (§4).

| Provider | On | Offers |
|---|---|---|
| `ConfigCommands.SUGGEST_CONFIG_KEYS` | `/wv config <key>` | the keys in `VetsConfigSchema.USER_CONFIG_KEYS` |
| `ConfigCommands.SUGGEST_CONFIG_VALUES` | `/wv config <key> <value>` | values by key type (below) |
| `DebugCommands.SUGGEST_DEBUG_CONFIG_KEYS` | `/wv debug set <key>` | the keys in `DebugConfigManager.DEBUG_CONFIG_KEYS` |
| `DebugCommands.SUGGEST_BOOLEAN_VALUES` | `/wv debug set <key> <value>` | `true`, `false` |
| `AnniDebugSuggestions.SUGGEST_DUMP_FILES` | `snapshot inject file <name>` | the `.json` files in `vetsmod/dumps/anni/`, without the extension |
| `AnniDebugSuggestions.SUGGEST_PRESETS` | `snapshot inject preset <name>` | the bundled preset names |
| `AnniDebugSuggestions.SUGGEST_EXTERNAL_MODES` | `external <mode>` | `auto`, `true`, `false` |
| `AnniDebugSuggestions.SUGGEST_TIME_OFFSETS` | `time <seconds>` | offsets from one minute to twelve hours, and `-60` for the not-announced form |
| `AnniDebugSuggestions.SUGGEST_ZONE_ACTIONS` | `zone <action>` | `enter`, `exit` |
| `AnniDebugSuggestions.SUGGEST_FLASH_FIELDS` | `flash <field>` | `role`, `world`, `party`, `rsvp` |
| `AnniDebugSuggestions.SUGGEST_MODES` | `mode set <mode>` | `silent`, `passive`, `aggressive` |
| `AnniDebugSuggestions.SUGGEST_ALERT_FIELDS` | `alert <field>` | `role`, `world`, `party`, `rsvp`, `zone`, `world_ready` |
| `AnniDebugSuggestions.SUGGEST_REGISTRY_ROLES` | `registry set <username> <role>` | the role codes and `other` |
| `DistributeCommands.suggestGuildMembers` | `/wv distribute <name>` | the `@`-selectors and, once Wynntils' member cache is filled, the guild members from `Models.Guild.getGuildMembers()` |
| `DistributeCommands.suggestCounts` | `/wv distribute … <count>` | `DistributeCommands.COMMON_COUNTS` |

The anni rows are under `/wv debug tree anni`.

**`SUGGEST_CONFIG_VALUES` by key.** Boolean and tri-state keys suggest `default|true|false`.
Int keys (opacity) suggest
`default|reset|0|25|50|69|75|100`. String keys suggest `default` and `reset` plus the colour names from
`LegacyItemStyle.getColorNames()`, the sprite names (`VetsConfigSchema.VALID_SPRITES`), or, for
`vetsAnniRoleStyle` and `vetsAnniFlashIntensity`, the key's own list from
`VetsConfigSchema.fixedValuesOf(key)`.
A key `/wv config` does not accept, such as a hand-typed `vetsAnniMode`, falls through to `true|false`.

## 4. Things to know when adding commands

- Register a new `/wv` subcommand in `CommandRegistry.register()` with Brigadier's literal and argument builders, or give a whole subtree its own `buildCommandTree()` and mount it with `.then(…)`, as `/wv debug`, `/wv distribute` and `/wv laurels` are.
- Gate with a real predicate. `CommandRegistry`'s own `userIsCaptain`/`userIsVet` are all-true stubs; the gated commands call one of the gates in §1's table directly. A `.requires` is a parse gate, so a handler that re-tests it refuses only if the gate's state changes between parse and run: a refusal that should explain itself belongs in the handler.
- Keep a `.requires` predicate under `/wv` cheap and free of side effects: `CommandCompletionSync` tests every one of them each client tick. A gate whose state changes needs nothing more for tab completion to follow it.
- Give a node that needs an argument after it `.executes(CommandUsage.usage("/wv …"))`, so typing it bare prints a usage line instead of going to the server.
- Avoid heavy work on the main thread; use `CompletableFuture` from `HttpClient`.
- Chat output: use `ChatUtils.sendLocalMessage(Component)` (or `sendLocalMessageNewBlock` for the first line of a new response). Both prepend the vetsmod badge by handing the `Prepend` to `LocalChatDispatch.dispatchBadged(Prepend, Component, boolean, boolean)`, which is thread-safe, chooses the badge on the render thread and marks the dispatch internal so the chat pipeline skips re-logging and the rewriter chain. Calling `dispatchToChat` directly skips that badge; some chat rewriters do so for lines they rebuild themselves. The badge's full or compact form follows the chat lines already shown: vetsmod's own lines are counted when they are dispatched, and server lines are counted by `ChatLogMixin` through `LocalChatDispatch.countServerLine`.
- Tab completion: use Brigadier `SuggestionProvider`s, with `ConfigCommands.SUGGEST_CONFIG_KEYS` / `SUGGEST_CONFIG_VALUES` as templates.
- **A fixed set of choices does not need a `SuggestionProvider` at all.** Literal children give Brigadier its own suggestion list for free — `hard`/`soft`/`revoke` under `rsvp`, and `silent`/`passive`/`aggressive` under `anni`, are both spelled that way. Reach for `StringArgumentType.word()` plus a custom provider only when the value set is open or computed at runtime. The tree does not always follow this. `/wv debug set <key> <value>` and the anni harness's `snapshot inject preset`, `external`, `zone`, `flash`, `mode set` and `alert` take a fixed set through `word()` and a provider, and `/wv debug <true|false>` takes one through `word()` with no provider. Each handler checks the word except `alert`'s, which passes any word to `AggressiveAlertDispatcher.forceAlert` and prints "anni alert <field> triggered" either way; `forceAlert` only logs a field it does not know.
