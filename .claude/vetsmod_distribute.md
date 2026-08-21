---
name: vetsmod Guild Resource Distribution
description: The distribute/ package — /wv distribute's Members-GUI automation state machine, the five dispatch modes, the tick constants and menu routes that encode observed Wynncraft behaviour, and the NoAspects opt-out
type: project
---
# vetsmod `/wv distribute` — Guild GUI automation

`distribute/` drives the Wynncraft Guild Management GUI from a client
command: it sends `/guild manage`, clicks through to Manage Members or
Guild Log, paginates to a named player's tile, and synthesises the hotbar
presses that hand them Aspects, Guild Tomes or Emeralds.

It is one state machine with a shared tail and five heads, spread over
five sub-packages. This doc exists because the tick constants and the
menu routes are **not derivable from the code** — each was chosen against
observed Wynncraft server behaviour, and §6 is the record of which
behaviour forced which shape. Change a number there and the failure is
silent: a press the server drops, a page scanned half-updated, a member
skipped.

## 1. Entry point and gating

`CommandRegistry.register` attaches the subtree via
`DistributeCommands.buildCommandTree`, immediately after `DebugCommands`:

```
/wv distribute <name|@selector> <aspects|tomes|emeralds> <count>
```

- **`<name>`** — `NameOrSelectorArgument`, a Brigadier
  `ArgumentType<String>` reading one space-delimited token. Brigadier's
  `StringArgumentType.string()` restricts unquoted input to
  `[0-9A-Za-z_.+-]`, so `@random` fails to parse at the `@`. The type
  deliberately does **not** enforce the selector set — that lives in one
  executor branch (§10).
- **`<resource>`** — three literals mapping to `MemberSlotPresser.Resource`
  hotbar button indices: `aspects`→0, `tomes`→1, `emeralds`→2. The
  server interprets one press as 1 Aspect, 1 Guild Tome, or 1024
  Emeralds respectively.
- **`<count>`** — `IntegerArgumentType.integer(COUNT_MIN, COUNT_MAX)`,
  i.e. 1–255. What a count *means* differs per head (§5).

Two gates, reading two different confirmed-side signals:

| Gate | Predicate | Confirmed source | Wynntils fallback |
|---|---|---|---|
| Visibility (`.requires`) | `GuildStateManager.isStaffOfAnyGuild()` | `isConfirmedStaff()`, a boolean | `Models.Guild.getGuildRank()` ordinal ≥ `CAPTAIN` |
| Execution (`DistributeCommands.ensureChief`) | `GuildStateManager.isChiefOfAnyGuild()` | `confirmedStaffRank()`, a string matched against `"chief"`/`"owner"` | rank is `CHIEF` or `OWNER` |

As of this writing each predicate has exactly one call site, both in
`DistributeCommands` — nothing enforces that, so re-grep before relying
on it. A captain sees the command in autocomplete and gets a red error
on execute. Captain never reaches the confirmed-staff *rank* string at all —
the server retired it in the 2026-07 permission restructure and simply
never sends it, so the exclusion is a server contract, not client
enforcement.

## 2. Package layout

All of it lives under `org.wynnvets.distribute.*` —
[distribute/](../src/client/java/org/wynnvets/distribute/), 14 files
across 5 sub-packages:

```
distribute/
├── DistributeCommands.java           — brigadier tree, both gates, selector dispatch
├── command/
│   ├── NameOrSelectorArgument.java   — one-token argument type so `@x` parses
│   └── OutboundCommand.java          — front-of-queue send, via reflection (§8)
├── opener/
│   └── GuildManageOpener.java        — /guild manage → Manage Members | Guild Log tile
├── walker/
│   ├── MembersListSearcher.java      — bidirectional page search for one name
│   ├── MembersListWalker.java        — forward-only full-roster collect, with lore
│   └── GuildLogWalker.java           — piggybacks Wynntils' log auto-pagination
├── distributor/
│   ├── MemberSlotPresser.java        — refresh-gated hotbar presses on one slot
│   ├── RandomDistributor.java        — @random: N recipients, one each
│   ├── ObjectivesDistributor.java    — @objectives: even split over completers
│   ├── GraidsDistributor.java        — @graids: proportional to log frequency
│   └── SplitDistributor.java         — @split: thirds, graids → objectives → random
└── utils/
    ├── NameResolver.java             — wapi guild roster, read four different ways
    └── NoAspectsFilter.java          — opt-out UUIDs → legacy names, fail-open
```

## 3. Bootstrap

Five of the classes are event-bus singletons with a static `register()`
that calls `WynntilsMod.registerEventListener(INSTANCE)`:
`GuildManageOpener`, `MembersListSearcher`, `MembersListWalker`,
`GuildLogWalker`, `MemberSlotPresser`. All five are registered
back-to-back inside `VetsmodClient.onInitializeClient`'s
`ClientLifecycleEvents.CLIENT_STARTED` callback, alongside the mod's
other Wynntils-bus listeners. Two of those reach the bus indirectly —
`LegacyTooltipEventListener` and `LegacyHighlightEventListener` register
from inside `WynntilsEventListener.register()`, one line above. Nothing
in the mod unregisters any of them.

That callback carries a long crash-rationale comment about deferring
`Models.*` access until after Wynntils' own init — but it is attached to
`ScrollSpotMarkerProvider`'s line further down, and documents that
class's constraint. The five here inherit the placement, not a separately
recorded reason for it.

The remaining nine files need no bus registration. Eight are static-only
with private constructors; `NameOrSelectorArgument` is the exception — a
private-constructor singleton with instance methods, because Brigadier's
`ArgumentType` is an interface.

## 4. The shared tail

Every head converges on the same sequence once it has a recipient name
in **legacy form** (the name Wynncraft freezes onto the GUI tile, which
is what the searcher matches). Each step names the class that owns it:

1. **`GuildManageOpener.openManageMembers`** sets `target = MEMBERS` and
   sends `guild manage` through `OutboundCommand.queueFront` (§8).
2. **`GuildManageOpener.onMenuOpenPre`** sees the `"<guild>: Manage"`
   title, calls `event.setCanceled(true)` so the Manage GUI never
   visually flashes, synthesises an empty container from the event's
   menu type, and left-clicks `MEMBERS_SLOT` (0), the top-left "Manage
   Members" tile. Mirrors Wynntils' `GuildBankHotkeyFeature` approach.
3. Wynncraft opens `"<guild>: Members"`.
4. **`MembersListSearcher.onMenuOpenPre`** binds `membersContainerId`.
   It does *not* cancel — the menu must render so the player can see the
   result and so `getMenu().getItems()` reflects what the server sent.
   In a multi-recipient chain the menu is already open, and
   `armSearch`'s re-arm fast path binds the id and schedules a scan
   directly instead.
5. **`MembersListSearcher.onSetContent` / `.onSetSlot`** call
   `scheduleScan()`, which is tail-debounced through `scanToken`: every
   call posts its own task and bumps the token, and only the
   last-posted task survives the check at fire time. The scan therefore
   runs `SCAN_DELAY_TICKS` after the *last* packet of a burst.
6. **`MembersListSearcher.scanAndPaginate`** runs
   `scanVisiblePageForMatch` over the bounded tile area — rows 0–4 ×
   cols 2–8 of the 9-wide grid, mirroring Wynntils'
   `GuildMemberListContainer.getBounds()` — comparing each hover name
   case-insensitively against the armed name set. On a miss it calls
   `advancePagination`.
7. On a hit it captures the handler, calls `stop()` to clear all search
   state, *then* invokes `SlotMatchHandler.onMatch(slot)`. The handler
   receives only the slot index; container id and items are stale by
   then and must be re-read.
8. **`MemberSlotPresser.fire`** announces the send in chat and calls
   `sendPressAndArm`, which reads the live container id and issues
   `ContainerUtils.pressKeyOnSlot` — a `ClickType.SWAP` packet carrying
   the resource's hotbar button index.
9. The press is **refresh-gated, not delay-gated**: `awaitingRefresh`
   goes true and the next press waits for either
   `MemberSlotPresser.onMenuOpenPre` (close+reopen refresh, new
   container id) or `.onSetContent` (in-place refresh, same id). A
   token-guarded `REFRESH_TIMEOUT_TICKS` timeout backs it.
10. **`MemberSlotPresser.onRefreshObserved`** waits `PRESS_DELAY_TICKS`,
    then either fires the next press or completes.
11. On completion `pendingOnComplete` runs — for the literal head that
    is `MemberSlotPresser.closeMembersScreen`; for the selector heads it
    is a `processNext`, declared on `RandomDistributor`,
    `ObjectivesDistributor` and `GraidsDistributor` only.
    `SplitDistributor` has none: it chains whole phases, so under
    `@split` the callback is still one of those three. `processNext`
    arms the searcher for the next recipient with the menu left open —
    and on its terminal invocation, when the queue is empty, calls
    `MemberSlotPresser.closeMembersScreen` itself.

**Gap:** the searcher's rebind and sweep-retry sub-machine is summarised
by its constants (§6) but its state transitions are not written out
here. See `MembersListSearcher.scanAndPaginate` and
`MembersListSearcher.advancePagination`.

## 5. The five heads

### Literal name

`DistributeCommands.distribute` fans out two HTTP calls in parallel —
`NameResolver.resolveLegacyName` and
`NoAspectsFilter.fetchExcludedLegacyNames` — and `thenCombine`s them.
The combined callback runs on the shared `HttpClient` executor, so it
marshals
back onto the tick thread with a `scheduleLater(..., 0)` hop before
touching any menu or bus state. `dispatchSingleTarget` then rejects the
send with a red chat line if *either* the literal input or the resolved
name is on the opt-out list, arms the searcher with the literal input,
adds the resolved name via `MembersListSearcher.addAlternative` when a
rename is detected, and opens the menu.

`<count>` here means **presses to that one player**. This head arms with
the two-argument `armSearch`, so `notFoundHandler` stays null: a
not-found still prints its chat line, but nothing closes the Members
menu afterwards.

The wait-before-arming is deliberate and costs a round trip up front —
rejecting an opted-out target after the menu has already opened would be
worse UX than the delay.

### `@random` — `RandomDistributor`

Guards on `GuildStateManager.isWynntilsReady()`, then combines
`NameResolver.fetchAllLegacyNames` with the opt-out set, filters, hops
to the tick thread, and `beginPicks` shuffles and takes
`min(count, roster.size())`. `<count>` means **distinct recipients**,
each receiving exactly one of the resource.

Picks come from the wapi legacy-name roster rather than
`Models.Guild.getGuildMembers()` on purpose: the former is already in
the name space the GUI tiles use, so no per-pick resolve is needed and
renamed members can't be silently skipped by a search that finishes
before their resolve does.

`processNext` arms the searcher for the first pick **before**
`openManageMembers()` — the searcher must be bound by the time
`MenuOpenedEvent.Pre` fires. Later picks re-arm through the fast path
while the menu is still open, and the searcher's bidirectional
pagination lets a pick on an earlier page be reached without reopening.

### `@objectives` — `ObjectivesDistributor`

Fires the opt-out fetch, arms `MembersListWalker` for a full forward
sweep, and opens the menu. The walker collects one `MemberEntry`
(legacy name + de-formatted lore lines) per bounded tile per page,
deduping by name, and calls back when the Next Page button is gone or
`MAX_PAGES` is hit.

`hasCompletedObjective` scans forward from the `Guild Objective:` header
to the first **non-blank** line, matches it against
`^- .+?: (\d+)/(\d+)$` and returns `current >= total`. Blank lines
between the header and the progress line are skipped, not read as a
miss. A missing header, a header with nothing non-blank after it, and a
non-blank line that fails the pattern all read as not-completed. The
streak line (`- Streak: 76`) has no slash and is excluded by the
pattern.

`buildDistribution` gives each of `k` completers `total / k`, shuffles,
and awards `+1` to the first `total % k`. Recipients landing on zero are
dropped so the chain never opens a menu to send nothing. `<count>` means
**total rewards**.

This is the one head whose **first** `processNext` arms with the Members
menu already open — the walk ended there — so it takes the re-arm fast
path instead of waiting for a menu-open event. Later recipients in any
head re-arm through the fast path too, whenever the menu is still open
at that moment — what is unique here is the *entry* into the chain.

### `@graids` — `GraidsDistributor`

Combines `NameResolver.fetchNameIndex` with the opt-out set (excluded
members are stripped from *both* index keys so their username token can
never match), then arms `GuildLogWalker` and calls
`GuildManageOpener.openGuildLog()` — the Manage route, not `/guild log`
(§6).

Entries containing the literal substring `and claimed` are treated as
graid completions. Within one, every token matching
`[A-Za-z0-9_]{3,16}` is looked up in the index; hits are deduped
per-entry, so a member appearing twice in one line counts once. Because
the index is keyed on both current and legacy names, an entry written
before a rename still attributes correctly.

`buildDistribution` gives each participant
`floor(frequency * count / totalParticipations)`, then awards `+1` to
`count - flooredTotal` randomly-chosen participants, each at most once.
Visit order is by descending frequency with a case-insensitive
alphabetical tiebreak — presentation only, it does not affect totals.
`<count>` means **total rewards**.

`processNext` arms before `openManageMembers()`, same as `@random`.

### `@split` — `SplitDistributor`

`splitCount` gives each pool `count / 3` and shuffles the three pool
indices to award the `count % 3` remainder, so the operator can't choose
which pool gets the extra. The three phases are then chained *backwards*
so each phase's `onComplete` already knows its successor, and a
zero-count pool collapses into its successor's runnable rather than
opening a menu to send nothing.

Order is fixed: **graids → objectives → random** (§6). The objectives
and random phases are wrapped in `PHASE_DELAY_TICKS`; the graids phase
runs immediately, since nothing precedes it. Every underlying dispatcher
fires its `onComplete` on the no-recipients and roster-failure paths
too, so an empty pool advances the chain rather than ending it.

## 6. Why the numbers and routes are what they are

The whole section is the answer to "can I change this?". Each point is
observed Wynncraft behaviour first, then the code shape it forces.

1. **`/guild log` sent shortly after a menu close is dropped
   server-side** — even when the player types it in chat themselves. The
   server wants a "clean" session state that `/guild manage` does not
   require. So `GuildManageOpener.openGuildLog()` sends `guild manage`
   and navigates: `onMenuOpenPost` binds the Manage container,
   `onSetContent` scans every item for the hover name matching
   Wynntils' `CustomGuildLogScreenFeature.GUILD_LOG_ITEM_PATTERN`
   (`§7§lGuild Log`), and clicks that slot. The tile's index is not
   hardcoded anywhere in Wynntils and Wynncraft has shifted the Manage
   layout before, which is why this path can't use the Members path's
   cancel-and-click-slot-0 trick.
2. **Wynncraft refreshes the Members menu after every send.** A press
   issued before that refresh lands is dropped silently — the symptom is
   asking for `count=N` and seeing exactly one reward line.
   `MemberSlotPresser`'s class Javadoc records the observed case as a
   close-and-reopen under a **new container id**, which makes the stale
   id the mechanism. The code covers a second shape too, and does not
   claim which is more common: it accepts either
   `MenuOpenedEvent.Pre` (close+reopen, new id) *or*
   `ContainerSetContentEvent.Post` (in-place, **same** id) as proof the
   refresh landed. Either way the gate is an observed refresh, not a
   fixed cadence.
3. **A page's `SetSlot` packets can cross a tick boundary.** Wynncraft
   updates paginated views in place, and can fire the pagination-button
   update before the page's player-slot packets. Scanning then reads a
   half-updated page and misses a target that is present — a false
   negative. Two changes answer it: `MembersListSearcher.onSetSlot`
   triggers on any slot inside the tile bounds, not just the two
   pagination buttons, and `SCAN_DELAY_TICKS = 2` tail-debounces the
   burst into one scan of the settled page. `MembersListWalker` did not
   receive the same treatment — its `onSetSlot` still triggers only on
   `NEXT_PAGE_SLOT`, and its `scheduleScan` is a leading-edge boolean
   latch with a fixed 1-tick delay rather than a token debounce.
4. **Wynntils' `GuildLogHolder` gives no completion signal we can
   subscribe to.** It auto-paginates the log and accumulates the items,
   but the state driving its stop decision is private and no `Models`
   API surfaces the holder, so `GuildLogWalker` cannot ask whether the
   walk is done. It infers completion from a settle timer instead —
   and that timer is the one place in the package driven by
   `@SubscribeEvent onTick(TickEvent)` against
   `McUtils.player().tickCount`, not by `Managers.TickScheduler`.
5. **The guild log caps at roughly 100 most-recent entries.** Aspect
   sends write log entries of their own, so distributing anything before
   reading the log pushes graid records off the back of the window.
   That is why `@split`'s phase order is fixed with `@graids` first, and
   why the order is load-bearing rather than stylistic.
6. **`Handlers.Command.queueCommand` is FIFO with no priority API.** A
   user-initiated `/guild manage` queued behind a draining `/v` fanout
   waits seconds at the queue's 7-tick-per-command rate. Hence
   `OutboundCommand` (§8).

### Constants

Every "why" below is the claim made by that constant's own comment, not
an extrapolation from behaviour observed elsewhere. Values are current.

| Constant | Class | Value | Why |
|---|---|---|---|
| `COUNT_MAX` | `DistributeCommands` | 255 | Unsigned-byte cap so a "500 aspects" typo can't spam the server. `COUNT_MIN` is 1 |
| `PRESS_DELAY_TICKS` | `MemberSlotPresser` | 4 | Settle buffer *after* a confirmed refresh, before the next press; keeps the cadence humane |
| `REFRESH_TIMEOUT_TICKS` | `MemberSlotPresser` | 40 | Safety bound on the refresh wait; ~2 s, stated as covering a generous network RTT |
| `SCAN_DELAY_TICKS` | `MembersListSearcher` | 2 | Enough for Wynncraft to finish streaming a page's `SetSlot` packets when they cross a tick boundary — point 3 above |
| `MAX_PAGES` | `MembersListSearcher` | 60 | Runaway-loop bound, sized for a full forward plus a full backward sweep on a max-size guild |
| `MAX_REBIND_ATTEMPTS` | `MembersListSearcher` | 3 | Caps rebinding to a refreshed container id so a thrashing refresh loop can't pin the searcher |
| `RETRY_DELAY_TICKS` | `MembersListSearcher` | 10 | Pause before re-sweeping from page 1; the re-click forces the server to re-stream fresh `SetSlot` data |
| `MAX_RETRY_ATTEMPTS` | `MembersListSearcher` | 1 | One retry catches the stale-data false negative; more would only delay the not-found verdict |
| `WATCHDOG_TICKS` | `MembersListSearcher` | 300 | ~15 s hard bound per search; a 4-page guild swept both ways with a retry is expected under 10 s |
| `MAX_PAGES` | `MembersListWalker` | 30 | Runaway-loop bound — that is the whole of its comment. **No sizing rationale is recorded** for 30, unlike the searcher's 60. Don't infer one from the walk being forward-only |
| `SETTLE_TICKS` | `GuildLogWalker` | 40 | No new log item for this long ⇒ done. The comment attributes the sizing to Wynntils' own `REQUEST_TIMEOUT = 5` plus `FORCED_LOAD_DELAY = 20` |
| `OVERALL_TIMEOUT_TICKS` | `GuildLogWalker` | 200 | ~10 s hard cap on the whole log walk |
| `PHASE_DELAY_TICKS` | `SplitDistributor` | 10 | Lets the previous phase's `ServerboundContainerClosePacket` settle. `queueCommand`'s 7-tick spacing paces commands but knows nothing about close packets |

`distribute/` is currently the repo's only consumer of
`Managers.TickScheduler` — 11 `scheduleLater` calls across 8 of its 14
files, none elsewhere in `src/`. Four of the 11 are delay-0 hops off the
shared `HttpClient` executor back onto the tick thread, not delays. Treat the
exclusivity as a snapshot rather than a design invariant; nothing stops
another package from scheduling.

## 7. Failure and stall matrix

Not every failure path advances the chain. Three rows below say **no**;
they are real and observable, and a matrix claiming otherwise would be
false.

| Trigger | Detected by | Chat output | Callback fires? |
|---|---|---|---|
| Executor run by non-chief | `DistributeCommands.ensureChief` | red "You must be a guild Chief or Owner…" | n/a — nothing armed |
| Target on the opt-out list | `DistributeCommands.dispatchSingleTarget` | red "… is on the NoAspects list…" | n/a — nothing armed |
| Wynntils not ready (`@random`) | `RandomDistributor.dispatch` | red "Wynntils isn't ready yet…" | yes |
| Empty wapi roster / name index | `RandomDistributor.beginPicks`, `GraidsDistributor.beginWalk` | yellow "Could not read guild roster from wapi…" | yes |
| No objective completers | `ObjectivesDistributor.onWalkComplete` | yellow "No members have completed…" | yes |
| No graid entries in the window | `GraidsDistributor.onLogReady` | yellow "No graid completions found…" | yes |
| Name absent after both sweeps and the retry | `MembersListSearcher.stopNotFound` | yellow "Could not find X in members list." | yes † |
| `MAX_PAGES` reached | `MembersListSearcher.scanAndPaginate` | yellow "Could not find X (reached page limit)." | yes † |
| Bound container id gone, rebinds exhausted | `MembersListSearcher.scanAndPaginate` | none | yes † |
| Search stalls with no event arriving | `MembersListSearcher`'s watchdog, 300 ticks | yellow "Search for X timed out — advancing." | yes † |
| Refresh never observed after a press | `MemberSlotPresser.armRefreshTimeout`, 40 ticks | yellow "Send timed out after N/M…" | yes |
| Guild log menu closed mid-walk | `GuildLogWalker.onMenuClose` | none | yes, with whatever was collected |
| Log walk exceeds 200 ticks | `GuildLogWalker.onTick` | none | yes, possibly with an empty list |
| **Members menu closed mid-search** | `MembersListSearcher.onMenuClose` | none | **no** |
| **Members menu closed mid-walk** | `MembersListWalker.onMenuClose` | none | **no** |
| **Members screen gone when the next press in a batch fires** | `MemberSlotPresser.sendPressAndArm` | none | **no** |

† All four searcher rows end in `MembersListSearcher.invokeNotFound`,
which runs whatever `notFoundHandler` was armed. The four selector heads
always arm one. The **literal-name head does not** — it uses the
two-argument `armSearch` — so on those rows it prints its chat line,
clears state, and stops there with the Members menu still open.

The three `no` rows are three different shapes, and none is rescued:

- **The two `onMenuClose` rows are the same shape.** Both `stop()`
  without invoking the handler. In the searcher's case `stop()` also
  bumps `watchdogToken`, and its own comment says the bump exists so a
  pending watchdog task "fires into the void" — so the 300-tick backstop
  does **not** cover this path.
- **What the watchdog does cover** is the other stall shape: one where
  `stop()` is never called at all, because no matching event reaches the
  searcher. A dropped pagination click, an exception inside a scheduled
  task — or a close event whose container id doesn't match the bound
  one, since `onMenuClose` returns early on that comparison and never
  reaches `stop()`. That last case is precisely why a close arriving
  before the searcher has bound still leaves the watchdog live to fire.
- **`MemberSlotPresser.sendPressAndArm` is a third shape.** Finding the
  screen gone, it calls `clearPending()`, which nulls
  `pendingOnComplete` *and* bumps `timeoutToken` — cancelling, in the
  same breath, the refresh timeout that would otherwise have fired the
  callback. Contrast `MemberSlotPresser.armRefreshTimeout`, which
  captures the callback into a local **before** `clearPending()` and
  then runs it. Same class, two paths, one contract.

## 8. `OutboundCommand` — the repo's only reflection

`OutboundCommand.queueFront(String)` puts a command at the head of
Wynntils' outbound queue. Wynntils declares that field as
`private final Queue<String>` on `CommandHandler` but instantiates a
`LinkedList`, which is also a `Deque`; the class reflects the field
once, caches the handle, and `addFirst`s on every later call. The 7-tick
per-command spacing is untouched — only the FIFO ordering is jumped.

The contract worth preserving is **cache the failure, warn, degrade**:

- `lookupField()` sanity-checks that the field's value is actually a
  `Queue`, so a Wynntils type swap trips the fallback immediately rather
  than on every call.
- On either of its two failure paths `lookupField()` logs at **warn**
  and returns the `FIELD_LOOKUP_FAILED` sentinel — itself a
  self-reflected field of `OutboundCommand`, so it can never be confused
  with a real handle. (`obtainDeque()`'s own per-call failures are
  separate: they log at debug and return null without caching anything.)
- `queueFront` then falls through to the public
  `Handlers.Command.queueCommand`. Front-of-queue priority is lost;
  nothing breaks.

Two call sites, both in `GuildManageOpener`: `openManageMembers()` and
`openGuildLog()`, each queueing `guild manage`.

As of this writing, this is the only `java.lang.reflect` usage anywhere
in `src/` — a fact worth re-checking rather than assuming.

## 9. HTTP surface

Two endpoints, both read fresh on every command, with no caching layer
of any kind:

| Endpoint | Builder | Reader |
|---|---|---|
| `https://api.wynncraft.com/v3/guild/{url-encoded}` | `WynnCraftApi.guildInfo` | `NameResolver` |
| `/v1/outbound/no-aspects` | `VetsApi.NO_ASPECTS` | `NoAspectsFilter` |

`NameResolver` reads the wapi guild payload four ways:
`resolveLegacyName` (one name → its tile name), `fetchAllLegacyNames`
(every member's tile name), `fetchNameIndex` (both name forms →
tile name, lowercase-keyed) and `fetchUuidToLegacyName` (dashless
lowercase UUID → tile name). `NoAspectsFilter` calls the last of those
to translate the opt-out payload's UUIDs — the rename-proof key — into
the tile names the distributors actually filter on.

**Everything fails open.** An HTTP error, a parse error or a missing
wapi response yields an empty exclude set, which means nobody is
filtered, which is pre-opt-out behaviour. `NameResolver`'s failure
returns differ by entry point rather than being uniform — but the failure
*path* no longer does. All four entry points go through one private
`fetchGuildJson(String context)`, which yields `null` for every failure
there is (Wynntils not ready, no guild name, non-200, transport
exception) and lets each caller map that one `null` onto its own
fallback. `context` is the calling entry point's name, tagged into both
of `fetchGuildJson`'s log lines, so four reads sharing one fetcher stay
distinguishable in the log. Log levels are
almost uniform too: exactly one path logs at **warn** —
`NoAspectsFilter`'s `.exceptionally` handler. Its non-200 and
parse-error paths log at debug, as does every `NameResolver` failure.

Request counts are **up to**, not exact: `NameResolver` short-circuits to
an already-completed future when Wynntils isn't ready or the guild name
is empty (that short-circuit now lives in `fetchGuildJson`, so one place
covers all four entry points; the counts themselves are unchanged), and
`@split` elides zero-count pools entirely. A single-target
send costs up to three requests (two wapi, one vets); `@random` and
`@graids` up to three each; `@objectives` up to two; `@split` up to the
sum of the phases it actually runs.

**Gap:** the `members.<rank>.<currentName>` traversal shape and the four
entry points' differing failure returns are not written out here. See
`NameResolver.forEachGuildMember`.

**Gap:** the package's user-facing chat strings are not inventoried.
There are 21 `ChatUtils.sendLocalMessage` call sites under `distribute/`;
§7 quotes only the failure-path ones.

## 10. Adding a new selector

Four edits, all local:

1. A `*_SELECTOR` constant in `DistributeCommands`.
2. A branch in `DistributeCommands.distribute`, before the literal-name
   fan-out.
3. A line in `DistributeCommands.suggestGuildMembers`, which offers the
   selectors unconditionally — their dispatchers read the live roster, so
   they work with a cold Wynntils member cache.
4. A distributor exposing `dispatch(int, Resource)` — plus the
   `dispatch(int, Resource, Runnable)` overload **if the new selector is
   to be chainable from `@split`**, firing that callback on *every* exit
   path. The three pool distributors all carry both overloads;
   `SplitDistributor` itself carries only the two-argument form, because
   nothing chains it. A path that returns without invoking the callback
   stalls the whole chain.

`NameOrSelectorArgument` needs no change — it fixes the lexical shape of
one token and deliberately doesn't know the selector set.

## Related

- [vetsmod_commands.md](vetsmod_commands.md) — the `/wv distribute`
  entry in the command tree, and the rest of `/wv`
- [vetsmod_networking.md](vetsmod_networking.md) — `VetsApi` constants
  and the mod's other HTTP surfaces
- [vetsmod_guild_system.md](vetsmod_guild_system.md) — `GuildStateManager`
  and where the confirmed-staff signals come from
