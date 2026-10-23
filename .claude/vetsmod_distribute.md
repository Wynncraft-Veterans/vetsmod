---
name: vetsmod Guild Resource Distribution
description: The distribute/ package — /wv distribute's Members-GUI automation state machine, the five dispatch modes, the tick constants and menu routes that encode observed Wynncraft behaviour, and the NoAspects opt-out
type: project
---
# vetsmod `/wv distribute` — Guild GUI automation

`distribute/` drives the Wynncraft Guild Management GUI from a client
command: it sends `/guild manage`, clicks through to Manage Members or
Guild Log, paginates to a named player's tile, and synthesises the hotbar
presses that hand them Aspects, Tomes or Emeralds.

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
  table (§10). The executor refuses an `@` token that matches no row, with
  a red line, before any lookup or menu (§7).
- **`<resource>`** — three literals mapping to `MemberSlotPresser.Resource`
  hotbar button indices: `aspects`→0, `tomes`→1, `emeralds`→2. The
  server interprets one press as 1 Aspect, 1 Tome, or 1024
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
on it. A strategist sees the command in autocomplete and gets a red error
on execute; a Returners captain is visible only through the Wynntils
fallback, because captain is no longer confirmed staff at all —
the server retired it in the 2026-07 permission restructure and simply
never sends it, so the exclusion is a server contract, not client
enforcement.

## 2. Package layout

All of it lives under `org.wynnvets.distribute.*` —
[distribute/](../src/client/java/org/wynnvets/distribute/), 17 files
across 5 sub-packages:

```
distribute/
├── DistributeCommands.java           — brigadier tree, both gates, selector dispatch
├── DistributeRuns.java               — how a run stops: the one yellow stop line (§7)
├── MembersGui.java                   — the Members menu's shape: title, page buttons, tiles
├── command/
│   ├── NameOrSelectorArgument.java   — one-token argument type so `@x` parses
│   └── OutboundCommand.java          — front-of-queue send, via reflection (§8)
├── opener/
│   └── GuildManageOpener.java        — /guild manage → Manage Members | Guild Log tile
├── walker/
│   ├── MembersListSearcher.java      — bidirectional page search for one name
│   ├── MembersListWalker.java        — forward-only full-roster collect, with lore
│   └── GuildLogWalker.java           — piggybacks Wynntils' log auto-pagination, when Wynntils wraps the log
├── distributor/
│   ├── MemberSlotPresser.java        — refresh-gated hotbar presses on one slot
│   ├── DistributionQueue.java        — the send loop the three pool heads share
│   ├── RandomDistributor.java        — @random: N picks, one each (§5)
│   ├── ObjectivesDistributor.java    — @objectives: even split over completers
│   ├── GraidsDistributor.java        — @graids: proportional to log frequency
│   └── SplitDistributor.java         — @split: thirds, graids → objectives → random
└── recipients/
    ├── NameResolver.java             — wapi guild roster, read four different ways
    └── NoAspectsFilter.java          — opt-out UUIDs → legacy names, fail-open
```

`MembersGui` sits at the package root rather than in `walker/` because
both Members-menu classes in `walker/` (`MembersListSearcher`,
`MembersListWalker`) and
`distributor/`'s `MemberSlotPresser` read from it, and three `distributor/` classes close the menu
through its `closeIfOpen()`; a distributor should not import from the walker package to
learn the GUI's shape. (`opener/` does not — `GuildManageOpener` drives
the *Manage* menu and reads only `ContainerScreens`.) It describes the *layout* (mirroring
Wynntils' `GuildMemberListContainer`), not the subset ≥2 callers share,
so `PREVIOUS_PAGE_SLOT` / `PREVIOUS_PAGE_PATTERN` live there despite
having one caller. The tick constants stay on their own classes — §6.

`org.wynnvets.util.ContainerScreens`, outside the package, holds the id and title "which container
screen is open" predicates the package uses, because the id-matching one also drives the *Manage*
menu and so is not Members-specific. Its third predicate, `current()`, discriminates on nothing,
and nothing in this package uses it. See §6 point 2.

`DistributionQueue` is in `distributor/` rather than beside `MembersGui`
because the three heads that build its queues are there, and it, its
`Distribution` record and `processNext` are package-private to them (§10).
It owns the drive loop and nothing else: who is owed what
stays with each head, and so do the two things that look shared and are
not — the arming order, and whether a no-recipient early-out closes the
menu (§5, and the class Javadoc).

## 3. Bootstrap

Five of the classes are event-bus singletons with a static `register()`
that calls `WynntilsMod.registerEventListener(INSTANCE)`:
`GuildManageOpener`, `MembersListSearcher`, `MembersListWalker`,
`GuildLogWalker`, `MemberSlotPresser`. All five are registered
back-to-back in `VetsmodClient.onClientStarted()`, the bootstrap's
`ClientLifecycleEvents.CLIENT_STARTED` phase, alongside the mod's
other Wynntils-bus listeners. Two of those reach the bus indirectly —
`LegacyTooltipEventListener` and `LegacyHighlightEventListener` register
from inside `WynntilsEventListener.register()`, one line above. Nothing
in the mod unregisters any of them.

That method's Javadoc gives the crash rationale: nothing may touch
Wynntils' event bus or `Models.*` before Wynntils' own init. The S5
comment on `ScrollSpotMarkerProvider`'s line further down applies it to
that class. Four of the five record no reason of their own;
`GuildManageOpener.register`'s Javadoc states the after-Wynntils-init
requirement (`WynntilsMod.registerEventListener` needs the event bus that
`WynntilsMod.init` creates) — a requirement all five share, since each
makes that same call.

The remaining twelve files need no bus registration. Eleven are static-only
with private constructors; `NameOrSelectorArgument` is the exception — a
private-constructor singleton with instance methods, because Brigadier's
`ArgumentType` is an interface.

## 4. The shared tail

Every head converges on the same sequence once it has a recipient name
in **legacy form** (the name Wynncraft freezes onto the GUI tile, which
is what the searcher matches). Each step names the class that owns it:

1. **`GuildManageOpener.openManageMembers`** sets `target = MEMBERS`,
   stamped with the time, and sends `guild manage` through
   `OutboundCommand.queueFront` (§8). An arm older than `ARM_TTL_MS` is
   cleared, unused, when the next menu opens.
2. **`GuildManageOpener.onMenuOpenPre`** sees the `"<guild>: Manage"`
   title (any other menu passes through and leaves the arm in place), calls `event.setCanceled(true)` so the Manage GUI never
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
   runs about `SCAN_DELAY_TICKS` after the *last* packet of a burst.
6. **`MembersListSearcher.scanAndPaginate`** runs
   `scanVisiblePageForMatch` over the bounded tile area — rows 0–4 ×
   cols 2–8 of the 9-wide grid, mirroring Wynntils'
   `GuildMemberListContainer.getBounds()` — comparing each hover name
   case-insensitively against the armed name set. The bounds are
   `MembersGui.isTileSlot`, which is also what
   `MembersListWalker.collectVisiblePage` collects from and what the
   searcher's `onSetSlot` filters on, so the three cannot drift apart.
   On a miss it checks the page cap, then calls `advancePagination` (the
   step-by-step below).
7. On a hit it captures the handler, calls `stop()` to clear all search
   state, *then* invokes `SlotMatchHandler.onMatch(slot)`. The handler
   receives only the slot index, so the callee reads the container from
   the live screen, and must re-read it for anything it does later — a
   send's refresh can replace the container id.
8. **`MemberSlotPresser.fire`** announces the send in chat and calls
   `sendPressAndArm`, which reads the live container id and issues
   `ContainerUtils.pressKeyOnSlot` — a `ClickType.SWAP` packet carrying
   the resource's hotbar button index.
9. The press is **refresh-gated, not delay-gated**: `awaitingRefresh`
   goes true and the next press waits for either
   `MemberSlotPresser.onMenuOpenPre` (reopen refresh, new
   container id) or `.onSetContent` (in-place refresh, same id). A
   token-guarded `REFRESH_TIMEOUT_TICKS` timeout backs it.
10. **`MemberSlotPresser.onRefreshObserved`** waits `PRESS_DELAY_TICKS`,
    then either fires the next press or completes.
11. On completion `pendingOnComplete` runs — for the literal head that
    is `MembersGui.closeIfOpen`, which sends the close packet; for the three pool heads
    it is `DistributionQueue.processNext`, which all three share and
    none of them declares. `SplitDistributor` has none either: it chains
    whole phases, so under `@split` the callback is still that same
    loop. `processNext` arms the searcher for the next recipient with
    the menu left open — and on its terminal invocation, when the queue
    is empty, calls `MembersGui.closeIfOpen` itself. Each pop after the
    first checks that the Members menu is still open, and stops the run
    if it is not (§7).

**The searcher's scan, step by step.** `MembersListSearcher.scanAndPaginate` runs
`SCAN_DELAY_TICKS` after the last event that triggers it (§6 point 3) and takes the
first of these that applies:

1. **No screen carries the bound id** (`ContainerScreens.currentWithId` returns
   `null`). If a Members menu is open by title and fewer than `MAX_REBIND_ATTEMPTS`
   rebinds have been used, it rebinds to that menu's id and schedules another scan.
   Otherwise it gives up through `invokeNotFound`.
2. **A tile on the page matches an armed name.** It stops the search and calls the
   match handler with the slot.
3. **The pass has already made `MAX_PAGES` page clicks.** A yellow "Could not find
   <name> (reached page limit)." and `invokeNotFound`.
4. **A page click is possible** (`advancePagination`). Going forward, it clicks Next
   while there is one; when there is none it turns backward and clicks Previous.
   Going backward, it clicks Previous while there is one. The page update the click
   brings triggers the next scan.
5. **No click is possible**, so the sweep is exhausted: a forward run ended with no
   Previous to turn to, or a backward run reached page 1. If the retry is unused
   (`MAX_RETRY_ATTEMPTS`), it resets the page count and the direction and schedules a
   fresh scan about `RETRY_DELAY_TICKS` later, from the page it is on, unless that search
   has ended by then (the hop checks the watchdog token, as the watchdog does). Otherwise a
   yellow "Could not find <name> in members list." and `invokeNotFound`.

`invokeNotFound` stops the search and runs the not-found handler, if one was armed;
`DistributionQueue` arms one that moves on to the next recipient while the Members
menu is still up, and stops the run when it is not (§7). The watchdog
(`WATCHDOG_TICKS`, counted from `armSearch`) also ends a search through
`invokeNotFound`, after a yellow "Search for <name> timed out.", and so does a
server-sent close for the bound id (`onMenuClose`). A client-side close, such as the
player's Esc, posts no close event: `onScreenClosed` schedules a scan instead, and the
scan's first step ends the search.

## 5. The five heads

### Literal name

A token starting with `@` that matches no `SELECTORS` row never gets here: a Minecraft
username can't contain `@`, so it is refused as a mistyped selector, with no request
and no menu. For a real name, `DistributeCommands.distribute` fans out two lookups in parallel —
`NameResolver.resolveLegacyName` and
`NoAspectsFilter.fetchExcludedLegacyNames(resource.gatedByNoAspects())`, up to
three requests on an aspects run (§9) — and `thenCombine`s them. On a tomes or
emeralds run the opt-out set is empty and already complete, and no opt-out
request is made.
The combined callback usually runs on a background HTTP-completion thread, so
it marshals back onto the tick thread with a `scheduleLater(..., 0)` hop before
touching any menu or bus state. (When both futures are already complete, as on
a tomes or emeralds run whose wapi read short-circuited, it runs inline on the
command's thread and the hop is a same-thread deferral.) `dispatchSingleTarget`
then rejects the send with a red chat line if *either* the literal input or the
resolved name is on the opt-out list, arms the searcher with the literal input,
adds the resolved name via `MembersListSearcher.addAlternative` when a
rename is detected, and opens the menu.

`<count>` here means **presses to that one player**. This head arms with
`MembersGui.closeIfOpen` as the not-found handler, so a search that gives
up prints its chat line, if any, and closes the Members menu.

The wait-before-arming is deliberate and costs a round trip up front —
rejecting an opted-out target after the menu has already opened would be
worse UX than the delay.

### `@random` — `RandomDistributor`

Guards on `GuildStateManager.isWynntilsReady()`, then combines
`NameResolver.fetchAllLegacyNames` with the opt-out set (empty unless the run is
aspects), filters, hops
to the tick thread, and `beginPicks` shuffles and takes
`min(count, roster.size())`. `<count>` means **recipients**: one of the
resource per pick, picks being distinct roster entries — though two
members sharing a tile name can make one tile the target twice, giving
one member two and the other none
(`random-distributor-can-target-one-tile-twice`).

Picks come from the wapi legacy-name roster rather than
`Models.Guild.getGuildMembers()` on purpose: the former is already in
the name space the GUI tiles use, so no per-pick resolve is needed and
renamed members can't be silently skipped by a search that finishes
before their resolve does.

`beginPicks` calls `DistributionQueue.processNext` for the first pick
**before** `openManageMembers()` — the searcher must be armed by the time
`MenuOpenedEvent.Pre` fires, so that it can bind on that event (a Members
menu already open at arm time is bound by the fast path instead —
`members-list-fast-path-binds-a-menu-about-to-be-replaced`). Later picks re-arm through the fast path
while the menu is still open, and the searcher's bidirectional
pagination lets a pick on an earlier page be reached without reopening.

### `@objectives` — `ObjectivesDistributor`

Fires the opt-out fetch (aspects only; any other run gets an empty set at
once), arms `MembersListWalker` for a full forward
sweep, and opens the menu. The walker collects one `MemberEntry`
(legacy name + de-formatted lore lines) per bounded tile per page,
deduping by name, and calls back when the Next Page button is gone or
`MAX_PAGES` is hit. A walk that loses its menu, or makes no progress for
`STALL_TICKS`, stops the run instead (§7).

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
dropped, so no search is spent on a recipient owed nothing. `<count>` means
**total rewards**.

`onWalkComplete` calls `DistributionQueue.processNext` with the Members
menu already open — the walk ended there — so this head's **first** pop
takes the searcher's re-arm fast path instead of waiting for a menu-open
event. Later recipients in any head re-arm through the fast path too,
whenever the menu is still open at that moment — what is unique here is
the *entry* into the chain.

### `@graids` — `GraidsDistributor`

Combines `NameResolver.fetchNameIndex` with the opt-out set (empty unless the
run is aspects; excluded members are stripped from *both* index keys so their username token can
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

`onLogReady` calls `DistributionQueue.processNext` before
`openManageMembers()`, same as `@random`.

### `@split` — `SplitDistributor`

`splitCount` gives each pool `count / 3` and shuffles the three pool
indices to award the `count % 3` remainder, so the operator can't choose
which pool gets the extra. The three phases are then chained *backwards*
so each phase's `onComplete` already knows its successor, and a
zero-count pool collapses into its successor's runnable rather than
opening a menu to send nothing.

Order is fixed: **graids → objectives → random** (§6). Each non-empty
objectives or random phase is wrapped in `PHASE_DELAY_TICKS`, even when
an empty graids pool makes it the first to run; the graids phase is not.
Every underlying dispatcher fires its `onComplete` on the no-recipients
and roster-failure paths too, so an empty pool advances the chain rather
than ending it. A phase that stops (§7) does not fire it, so a stop in one
phase ends the whole chain.

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
   `MemberSlotPresser`'s class Javadoc records the observed case as the
   menu reopening under a **new container id**, which makes the stale
   id the mechanism. The code covers a second shape too, and does not
   claim which is more common: it accepts either
   `MenuOpenedEvent.Pre` (reopen, new id) *or*
   `ContainerSetContentEvent.Post` (in-place, **same** id) as proof the
   refresh landed. Either way the gate is an observed refresh, not a
   fixed cadence.

   This is also why "is the Members menu open?" has **two** answers in
   the package and they are not interchangeable.
   `MembersGui.currentByTitle()` ignores the container id, which is what
   survives the refresh — `MemberSlotPresser` goes by title throughout:
   every press and the `SetContent` refresh check find
   the screen via `currentByTitle()`, and the reopen check matches the
   event title; so does the close at the end of a run (`MembersGui.closeIfOpen`),
   and the searcher uses it in its re-arm fast path and its mid-search
   rebind, where there is no *usable* bound id — none yet in the fast
   path, a stale one in the rebind. `ContainerScreens.currentWithId(int)` takes the caller's bound id
   and nothing else, because for the scan that drives pagination a
   mismatch is the *signal* that the bound menu is gone — the searcher
   rebinds on it, the walker stops the run. `GuildManageOpener` uses the same
   id predicate on the Manage menu. Swapping either for the other changes
   behaviour on exactly the path it exists for. The full table is on
   `MembersGui`'s class Javadoc.
3. **A page's `SetSlot` packets can cross a tick boundary.** Wynncraft
   updates paginated views in place, and can fire the pagination-button
   update before the page's player-slot packets. Scanning then reads a
   half-updated page and misses a target that is present — a false
   negative. Two changes answer it: `MembersListSearcher.onSetSlot`
   triggers on any slot inside the tile bounds, not just the two
   pagination buttons, and `SCAN_DELAY_TICKS = 2` tail-debounces the
   burst into one scan, run once no triggering packet has arrived for
   about that long; a stream that pauses longer can still be scanned
   half-updated. `MembersListWalker` did not
   receive the same treatment — its `onSetSlot` still triggers only on
   `MembersGui.NEXT_PAGE_SLOT`, and its `scheduleScan` is a leading-edge
   boolean latch with a fixed 1-tick delay rather than a token debounce.
4. **Wynntils' `GuildLogHolder` gives no completion signal we can
   subscribe to.** It auto-paginates the log and accumulates the items,
   but the state driving its stop decision is private and no `Models`
   API surfaces the holder, so `GuildLogWalker` cannot ask whether the
   walk is done. It infers completion from a settle timer instead —
   and that timer is the one place in the package driven by
   `@SubscribeEvent onTick(TickEvent)` against
   `McUtils.player().tickCount`, not by `Managers.TickScheduler`.

   ⚠️ **The auto-pagination itself is conditional.** `GuildLogHolder` is
   on Wynntils' event bus only while Wynntils has *wrapped* the log
   screen, and `WrappedScreenHandler` wraps it only when a listener
   accepts `WrappedScreenOpenEvent` — for the log,
   `CustomGuildLogScreenFeature`. At v4.1.17 that feature is on by
   default only in the `DEFAULT` and `LITE` config profiles, and it is
   gated by its shift-behaviour setting, which it compares against a
   shift flag that only a click through the game's own container-click
   path updates; `GuildManageOpener`'s tile click is a raw packet and
   does not. Unwrapped, nothing auto-paginates, the walk ends on whatever
   has arrived, and `GuildLogWalker.finishWalk`'s `setScreen(null)` sends
   no close packet for the log. Filed as
   `graids-log-walk-depends-on-wynntils-log-screen-feature`.
5. **The guild log caps at roughly 100 most-recent entries.** Aspect
   sends write log entries of their own, so distributing anything before
   reading the log pushes graid records off the back of the window.
   That is why `@split`'s phase order is fixed with `@graids` first, and
   why the order is load-bearing rather than stylistic.
6. **`Handlers.Command.queueCommand` is FIFO with no priority API.** It
   sends at once when its 7-tick spacing (`TICKS_PER_EXECUTE`) has
   elapsed and otherwise appends, so a user-initiated `/guild manage`
   would wait behind whatever is already queued — vetsmod's own
   background commands and Wynntils' alike. Wynntils'
   `sendCommandImmediately` jumps the queue but skips the spacing too.
   Hence `OutboundCommand` (§8), which jumps only the order.

### Constants

Every "why" below is the claim made by that constant's own comment —
or, for two, by the comment it leans on: `COUNT_MAX` shares `COUNT_MIN`'s
"Bounds on `<count>`" Javadoc, and `MAX_REBIND_ATTEMPTS`' reason is on the
`rebindAttempts` field and in `scanAndPaginate`. None is an extrapolation
from behaviour observed elsewhere, and two, `STALL_TICKS` and `ARM_TTL_MS`,
were chosen rather than observed, as their comments say. Values are current.

| Constant | Class | Value | Why |
|---|---|---|---|
| `COUNT_MAX` | `DistributeCommands` | 255 | Unsigned-byte cap so a "500 aspects" typo can't spam the server. `COUNT_MIN` is 1 |
| `PRESS_DELAY_TICKS` | `MemberSlotPresser` | 4 | Settle buffer *after* every confirmed refresh — before the next press or, after the last, before `onComplete`; keeps the cadence humane |
| `REFRESH_TIMEOUT_TICKS` | `MemberSlotPresser` | 40 | Safety bound on the refresh wait; ~2 s, stated as covering a generous network RTT |
| `SCAN_DELAY_TICKS` | `MembersListSearcher` | 2 | Enough for Wynncraft to finish streaming a page's `SetSlot` packets when they cross a tick boundary — point 3 above |
| `MAX_PAGES` | `MembersListSearcher` | 60 | Runaway-loop bound per pass (the retry starts a new pass and resets the counter), sized for a full forward plus a full backward sweep on a max-size guild |
| `MAX_REBIND_ATTEMPTS` | `MembersListSearcher` | 3 | Caps rebinding to a refreshed container id so a thrashing refresh loop can't pin the searcher |
| `RETRY_DELAY_TICKS` | `MembersListSearcher` | 10 | Pause before re-sweeping from page 1; on a multi-page guild the re-click forces the server to re-stream fresh `SetSlot` data |
| `MAX_RETRY_ATTEMPTS` | `MembersListSearcher` | 1 | One retry catches the stale-data false negative; more would only delay the not-found verdict |
| `WATCHDOG_TICKS` | `MembersListSearcher` | 300 | ~15 s hard bound per search; a 4-page guild swept both ways with a retry is expected under 10 s |
| `MAX_PAGES` | `MembersListWalker` | 30 | Runaway-loop bound — that is the whole of its comment. **No sizing rationale is recorded** for 30, unlike the searcher's 60. Don't infer one from the walk being forward-only |
| `STALL_TICKS` | `MembersListWalker` | 200 | ~10 s with no progress (arming, binding or a page click) ⇒ the walk has stalled and stops the run. Chosen, not observed: well above a page turn's round trip, and the same bound as `OVERALL_TIMEOUT_TICKS` |
| `ARM_TTL_MS` | `GuildManageOpener` | 10 s | An open whose `/guild manage` went unanswered for this long has given up, so the next menu clears its arm instead of using it. Below the searcher's 15 s watchdog. Chosen, not observed; the only constant here in milliseconds, checked lazily against `System.nanoTime()` |
| `SETTLE_TICKS` | `GuildLogWalker` | 40 | No new log item for this long ⇒ done. The comment attributes the sizing to Wynntils' own `REQUEST_TIMEOUT = 5` plus `FORCED_LOAD_DELAY = 20` |
| `OVERALL_TIMEOUT_TICKS` | `GuildLogWalker` | 200 | ~10 s hard cap on the whole log walk |
| `PHASE_DELAY_TICKS` | `SplitDistributor` | 10 | Belt-and-braces settle gap before each objectives or random phase starts, on top of `queueCommand`'s 7-tick spacing. A phase that ends on the Members menu closes it through `MembersGui.closeIfOpen`, whose close packet the gap also gives time to land |

`distribute/` makes 12 of the repo's 13 `Managers.TickScheduler.scheduleLater`
calls, across 8 of its 17 files, and calls the scheduler directly. The thirteenth
is in `util.TickDelays`, the mod-wide helper for delayed work on the client
thread. Four of the 12 are delay-0 marshalling
hops back onto the tick thread, not delays — three off a
background HTTP-completion thread, and `ObjectivesDistributor.dispatch`'s whenever its
opt-out fetch outlasts the walk (when it does not, that call is a
same-thread deferral through the scheduler instead). A tomes or emeralds run
makes no opt-out fetch, so its `@objectives` hop is always that deferral, and the
other three are too when their wapi read short-circuited (§9). Treat
the counts as a snapshot rather than a design invariant; nothing stops
another package from scheduling.

## 7. Failure and stall matrix

Not every failure path advances the chain. A row whose callback column says
**no** is a stop: the run ends with one yellow "Distribution stopped: …"
line, by design (`DistributeRuns`), and nothing it armed calls back.

| Trigger | Detected by | Chat output | Callback fires? |
|---|---|---|---|
| Executor run by non-chief | `DistributeCommands.ensureChief` | red "You must be a guild Chief or Owner…" | n/a — nothing armed |
| `@` token matching no selector | `DistributeCommands.distribute` | red "Unknown selector @x. Choose from @graids, @objectives, @random, @split." | n/a — nothing armed |
| Target on the opt-out list (aspects only) | `DistributeCommands.dispatchSingleTarget` | red "… has opted out of receiving aspects…" | n/a — nothing armed |
| Wynntils not ready (`@random`) | `RandomDistributor.dispatch` | red "Wynntils isn't ready yet…" | yes |
| Empty wapi roster / name index | `RandomDistributor.beginPicks`, `GraidsDistributor.beginWalk` | yellow "Could not read guild roster from wapi…" | yes |
| No objective completers | `ObjectivesDistributor.onWalkComplete` | yellow "No members have completed…" | yes |
| No graid entries in the window | `GraidsDistributor.onLogReady` | yellow "No graid completions found…" | yes |
| Name absent after both sweeps and the retry | `MembersListSearcher.stopNotFound` | yellow "Could not find X in members list." | yes † |
| `MAX_PAGES` reached | `MembersListSearcher.scanAndPaginate` | yellow "Could not find X (reached page limit)." | yes † |
| Bound Members menu gone, and no rebind possible (none open, or rebinds exhausted) | `MembersListSearcher.scanAndPaginate` | none from the searcher | yes † |
| Search stalls with no event arriving | `MembersListSearcher`'s watchdog, 300 ticks | yellow "Search for X timed out." | yes † |
| Members menu closed by the server mid-search | `MembersListSearcher.onMenuClose` | none from the searcher | yes † |
| Members menu gone when the next recipient is popped | `DistributionQueue.processNext` | yellow "Distribution stopped: the Members menu is not open." | no, by design: the run stops, and any later `@split` phase never starts |
| Refresh never observed after a press | `MemberSlotPresser.armRefreshTimeout`, 40 ticks | yellow "Send timed out after N/M…" | yes |
| Guild log menu closed by the server mid-walk | `GuildLogWalker.onMenuClose` | none | yes, with whatever was collected |
| Log walk exceeds 200 ticks | `GuildLogWalker.onTick` | none | yes, possibly with an empty list |
| Log screen never reached — for example no Guild Log tile, `/guild manage` unanswered, or the tile click going nowhere (a case of the row above) | `GuildLogWalker.onTick`'s overall timeout | none from the walker; the empty list then reaches `GraidsDistributor.onLogReady`'s yellow "No graid completions found…", which reads as an empty log (`graids-log-never-reached-reported-as-empty-log`) | yes, with an empty list; a Manage menu may stay open |
| Members menu closed by the server mid-walk | `MembersListWalker.onMenuClose` | yellow "Distribution stopped: the Members menu closed." | no, by design: the run stops |
| Bound Members menu gone when the walker's scan runs | `MembersListWalker.scanAndPaginate` | yellow "Distribution stopped: the Members menu is not open." (a Members menu still on top is closed first) | no, by design: the run stops |
| Walk makes no progress for 200 ticks — for example a Members menu that never opens, or a Next Page click whose reply the walker never sees | `MembersListWalker`'s stall watchdog | yellow "Distribution stopped: the objectives walk timed out." | no, by design: the run stops, and the walker disarms, so a later Members menu cannot bind it |
| Members screen gone when the next press in a batch fires | `MemberSlotPresser.sendPressAndArm` | yellow "Send stopped after N/M: the Members menu is not open." | yes; for a multi-recipient run the pop that follows then stops the run |

† All five searcher rows end in `MembersListSearcher.invokeNotFound`,
which runs whatever `notFoundHandler` was armed. Every selector-run
search is armed by `DistributionQueue.processNext`, which always passes
one: it moves on to the next recipient if the Members menu is still up,
and otherwise stops the run (the `DistributionQueue.processNext` row). The
**literal-name head** passes `MembersGui.closeIfOpen` instead, so on those
rows it prints the row's chat line, if any, and closes a Members menu that
is still open.

**"Closed by the server" is literal.** Wynntils posts `MenuClosedEvent`
only from its `handleContainerClose` hook — a *clientbound* close — so
every `onMenuClose` in the package sees the server closing a menu, never
the player. A player's Esc sends only a serverbound close and posts no
`MenuClosedEvent`; Wynntils posts `ContainerCloseEvent` and
`ScreenClosedEvent` for it. `MembersListSearcher.onScreenClosed` and
`MembersListWalker.onScreenClosed` subscribe to the second: a bound search
or walk schedules a scan when any screen closes, and the scan finds the
menu gone — the search ends through the "Bound Members menu gone" row and
the run stops at its next pop, and the walk stops the run itself. Nothing
else in the package subscribes to either. A press batch has no such
listener: a press already scheduled finds no screen, says so and runs the
callback, so the run stops at its next pop (the presser's screen-gone
row); otherwise the batch waits on the refresh timeout. What the server
sends for a menu after an Esc — a close of its own, a reopen, slot
updates answering a click already in flight — is not verifiable from any
repo.

The searcher's and the walker's watchdogs both cover the stall where no
matching event arrives:

- **The searcher's** covers one where `stop()` is never called at all,
  because no matching event reaches the
  searcher. A Members menu that never opens, a dropped pagination click,
  a Members menu replaced by another screen, which posts neither close
  event
  — or a close event whose container id doesn't match the bound
  one, since `onMenuClose` returns early on that comparison and never
  reaches `stop()`. That last case is precisely why a close arriving
  before the searcher has bound still leaves the watchdog live to fire.
  It is *not* a reliable backstop for a scheduled task that throws:
  Wynntils' `TickSchedulerManager` does not catch, so a throwing task is
  not removed: it re-runs every tick until a run returns normally, and
  each throw aborts that tick's pass over the task map — whether the
  watchdog still counts down meanwhile depends on map order.
- **The walker's** restarts on every sign of progress — arming, binding,
  each page click — and so ends a walk whose menu never opens or whose
  click is never answered, then disarms it.

**One run at a time.** All of this machine's state is static, and
nothing refuses a second `/wv distribute` while a run is in flight. The
second run overwrites whatever shared state its path uses (searcher,
walkers, presser, opener); callbacks of the first run stored there are
dropped unrun, an `@split` chain included. One it leaves in place can
still fire later, into the second run
(`distribute-concurrent-runs-clobber-shared-state`).

## 8. `OutboundCommand` — the mod's only reflection

`OutboundCommand.queueFront(String)` puts a command at the head of
Wynntils' outbound queue. Wynntils declares that field as
`private final Queue<String>` on `CommandHandler` but instantiates a
`LinkedList`, which is also a `Deque`; the class reflects the field on
first use, caches the result, and, while the field holds a `Deque`,
`addFirst`s on every call, the first included. The 7-tick per-command
spacing is untouched — only the FIFO ordering is jumped. On that path
`queueFront` never sends from the call itself: even with the spacing
already elapsed, the command waits for the next on-world `TickEvent`. (The
`queueCommand` fallback below can send at once.)

The contract worth preserving is **cache the failure, warn, degrade**:

- `lookupField()` sanity-checks that the field's value is a `Deque`,
  which `addFirst` needs, so a swap to anything else (a `Queue` that is
  not a `Deque`, a non-`Queue` value, or a null) is caught once rather
  than on every call. `obtainDeque()`'s per-call `Deque` check stays as
  the backstop.
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

As of this writing, this is the only `java.lang.reflect` usage in the mod's
own code (`src/client`, `src/main`); `VetsConfigKeysTest` reads
`VetsConfigKeys`' constants by reflection too. A fact worth re-checking rather than assuming.

## 9. HTTP surface

Two endpoints, with no caching layer of any kind, each read fresh whenever a
run needs it: the wapi guild API by every run except a tomes or emeralds
`@objectives`, which makes no request at all, and the opt-out list by every
aspects run and never by a tomes or emeralds one:

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
the tile names the distributors actually filter on. Both sides key that
join through `org.wynnvets.util.Uuids.normalizeUndashed`.

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
distinguishable in the log; `resolveLegacyName` passes
`"resolveLegacyName [" + input + "]"` rather than a bare name, so its
failure line still reports which name failed, as its own pre-collapse
message did. Among the fetch and parse failures exactly one path logs
at **warn** — `NoAspectsFilter`'s `.exceptionally` handler; the others log
at debug or not at all. Separately, `NameResolver`'s per-member field
reads go through `Json.stringOrNull`, which warns, degrading only that
member, on a non-object roster entry in `fetchAllLegacyNames` /
`fetchNameIndex`, and on a `legacyName` or `uuid` it reads but cannot
take as a string (`Json`'s class Javadoc lists what Gson coerces
instead).

Request counts are **up to**, not exact: `NameResolver` short-circuits to
an already-completed future when Wynntils isn't ready or the guild name
is empty (that short-circuit now lives in `fetchGuildJson`, so one place
covers all four entry points; the counts themselves are unchanged), and
`@split` elides zero-count pools entirely. On an aspects run a single-target
send costs up to three requests (two wapi, one vets); `@random` and
`@graids` up to three each; `@objectives` up to two; `@split` up to the
sum of the phases it actually runs. A tomes or emeralds run skips the opt-out
lookup (one vets request and its wapi read): a single-target send costs up to
one request, `@random` and `@graids` up to one each, and `@objectives` none.

**`NameResolver`, written out.** Each entry point makes its own `fetchGuildJson` call, a
fresh `GET /v3/guild/<the player's guild>` that completes with the body or with the
`null` described above, so an aspects single-target send's two wapi requests are two of
these calls. `forEachGuildMember` walks
`members.<rank>.<currentName>`, skipping the `total` key and any rank bucket that is
not an object. It hands each member's current name and JSON object (`null` when the
value is missing or not an object) to a callback that can stop the walk, and a parse
error ends the walk quietly. A member's tile name is its `legacyName` when it has
one, else its current name. On `null`, or a body that does not parse, each entry
point falls back its own way:

| Entry point | Caller | Returns | On failure |
|---|---|---|---|
| `resolveLegacyName(input)` | `DistributeCommands`' literal-name head | the tile name of the first member whose current or legacy name equals the input, ignoring case; members with no object are skipped; the input unchanged when none matches | the input unchanged |
| `fetchAllLegacyNames()` | `RandomDistributor` | every member's tile name, not deduplicated | an empty list |
| `fetchNameIndex()` | `GraidsDistributor` | each member's current and tile names, lower-cased, mapped to the tile name | an empty map |
| `fetchUuidToLegacyName()` | `NoAspectsFilter` | each member's UUID, undashed and lower-cased, mapped to the tile name; members with no object or no `uuid` are skipped | an empty map |

**Not inventoried here: the package's user-facing chat strings.** They change with
the code, so the grep finds them, each string on the lines after its call:
`grep -rn 'sendLocalMessage' src/client/java/org/wynnvets/distribute`. §7 quotes the
failure-path ones. `NameOrSelectorArgument`'s Brigadier error, "Expected a member name
or @selector", is not a chat call; the chat screen's command hint shows it.

## 10. Adding a new selector

Two edits, both local:

1. A row in `DistributeCommands.SELECTORS`: the token, and a method
   reference to the head's `dispatch(int, Resource)`. Dispatch,
   tab-completion and the unknown-selector refusal's token list all read
   that one table, so the row covers all three. Row position carries no
   meaning — the refusal sorts its list, and dispatch matches a whole token
   case-insensitively, so no row can shadow another, and Brigadier's
   `Suggestions.create` HashSets and case-insensitively sorts the
   suggestion list before the client ever sees it. The selectors are
   offered regardless of Wynntils readiness (prefix-filtered only) — none
   of the dispatchers reads `Models.Guild.getGuildMembers()`, so they
   don't depend on the member cache the name suggestions need.
2. A distributor exposing `dispatch(int, Resource)` — plus the
   `dispatch(int, Resource, Runnable)` overload **if the new selector is
   to be chainable from `@split`** (plus a phase in `SplitDistributor`
   to call it), firing that callback on every exit
   the head itself owns. The three pool distributors all carry both
   overloads; `SplitDistributor` itself carries only the two-argument
   form, because nothing chains it. A path of the head's own that returns
   without invoking the callback stalls the whole chain. Code the head
   does not own still ends some runs without it — a stopped run, by
   design (§7).

If the new head visits more than one recipient it does not write a drive
loop: it builds a `Deque<DistributionQueue.Distribution>` and hands it to
`DistributionQueue.processNext` with its own log tag — which puts the head
in `distributor/`, since `DistributionQueue`, its `Distribution` record
and `processNext` are all package-private. What it does still
decide for itself is whether to arm before or after opening the menu, and
whether its own no-recipient exits close the Members screen — §5.

`NameOrSelectorArgument` needs no change — it fixes the lexical shape of
one token and deliberately doesn't know the selector set.

## Related

- [vetsmod_commands.md](vetsmod_commands.md) — the `/wv distribute`
  entry in the command tree, and the rest of `/wv`
- [vetsmod_networking.md](vetsmod_networking.md) — `VetsApi` constants
  and the mod's other HTTP surfaces, and (auth frame fields) where the
  confirmed-staff signals (`is_staff`, `staff_rank`) come from
- [vetsmod_guild_system.md](vetsmod_guild_system.md) — `GuildStateManager`
