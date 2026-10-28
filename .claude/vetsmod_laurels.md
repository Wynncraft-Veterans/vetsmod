---
name: vetsmod Laurels
description: The vets laurels drawn on Wynntils' nametag badge bar — the standings model and poller, the negative-uOffset encoding, LaurelBarMixin and the Wynntils gates it inherits, the failure modes and guard, the bump-time check, the sheet's asset notice, the /wv laurels command, the temporary in-game streak fallback and how to retire it
type: project
---
# vetsmod Laurels

The top 3 Returners in each of four metrics get a laurel above their nametag, seen by every vetsmod user. Wynntils calls the same thing a **leaderboard badge** (the word "laurel" appears nowhere in its code): vetsmod appends its own badges to Wynntils' badge bar and swaps in its own sheet when one is drawn, so the laurels get Wynntils' layout, rotation and gates for free.

| Piece | Where |
|---|---|
| Standings, computed and served | temporary-server, `GET /v1/outbound/laurels`. The response, `updated_at` and `collect` are owned by [`v1_protocol.md` §5.2](../../temporary-server/v1_protocol.md) |
| Model | [`org.wynnvets.laurels`](../src/client/java/org/wynnvets/laurels/): `LaurelMetric`, `Laurel`, `LaurelStandings` |
| Fetch | [`LaurelPoller`](../src/client/java/org/wynnvets/fetcher/polling/LaurelPoller.java), 5 min, through `PolledJson` ([vetsmod_networking.md](vetsmod_networking.md) §5) |
| Badges and the encoding | [`LaurelBadges`](../src/client/java/org/wynnvets/laurels/LaurelBadges.java), the only class that touches Wynntils' badge types |
| Hook | [`LaurelBarMixin`](../src/client/java/org/wynnvets/mixin/client/LaurelBarMixin.java), in `vetsmod.laurels.mixins.json` ([vetsmod_mixins.md](vetsmod_mixins.md)) |
| Staff command | [`LaurelCommands`](../src/client/java/org/wynnvets/laurels/LaurelCommands.java): `/wv laurels update` and `return` (§8) |
| Streak fallback | [`StreakCrawl`](../src/client/java/org/wynnvets/laurels/StreakCrawl.java), temporary (§9) |
| Sheet | `src/main/resources/assets/vetsmod/textures/nametag/laurels.png`, with its notice beside it |

## 1. Model

`LaurelMetric` is `XP("xp", 0)`, `GRAIDS("graids", 1)`, `STREAK("streak", 2)` and `RETURN("return", 3)`: the server's wire name and the sheet column, the column kept as a field so reordering the constants cannot re-map the sheet. A `Laurel` is a metric and a place from 1 to `Laurel.PLACES` (3); `row()` is `place - 1`. `LaurelStandings` is `Map<UUID, List<Laurel>>` plus the `Set<LaurelMetric>` the server wants a game client to `collect`, deeply immutable so the poller can publish it through a volatile and the render thread can read it.

`LaurelPoller.standings()` is `null` until the first successful fetch, which an empty `collect` cannot say. The server answers 503 until it has its first guild data, which `PolledJson` treats as a failed fetch, so a cold start stays `null`. `parse` keeps what it can from a bad body: it skips a non-object entry, an unparseable uuid, an unknown metric, a place outside 1 to 3 and a repeat of a player and metric (the first wins), orders each player's laurels by column, and drops names in `collect` it does not know. A body that is not an object, or has no `laurels` array, yields `null` and keeps the previous snapshot. The response's `username`, `updated_at` and `sources` are not read. `collect` is read by `/wv laurels update` (§8).

## 2. The sheet

`laurels.png` is 76×54: **4 columns × 3 rows of 19×18 cells**, the size of Wynntils' own cells (`LeaderboardBadge.WIDTH` and `HEIGHT`).

| | col 0 | col 1 | col 2 | col 3 |
|---|---|---|---|---|
| Metric | `xp` (guild XP) | `graids` (guild raids) | `streak` (objective streak) | `return` (weekly return) |

Row 0 is gold (place 1), row 1 silver (place 2), row 2 bronze (place 3). Wynntils' sheet is a different size in every version it ships (722, 760 and 855 pixels wide at v4.1.4, v4.1.17 and v4.2.16), which is why the draw hook passes our own width and height.

## 3. The encoding

A Wynntils `LeaderboardBadge` is `(uOffset, vOffset)` into its own sheet and nothing else: it names no texture. Wynntils' offsets are never negative, so ours are marked by a negative `uOffset`:

- `uOffset = -(1 + column)`, `vOffset = row * LeaderboardBadge.HEIGHT`, built in `LaurelBadges`.
- The draw hook sees `isLaurel(uOffset)` (`< 0`), turns it back with `sheetU(uOffset) = (-uOffset - 1) * LeaderboardBadge.WIDTH`, and substitutes `LaurelBadges.TEXTURE`, `TEXTURE_WIDTH` and `TEXTURE_HEIGHT` for Wynntils' texture and size.

`LaurelBadges` keeps a `UUID → List<LeaderboardBadge>` map, rebuilt on the render thread whenever `LaurelPoller.standings()` returns a new reference (one volatile read per call). It is render-thread-only state, so none of it is volatile.

## 4. The hook

`LaurelBarMixin` targets Wynntils' `CustomNametagRendererFeature.drawBadges` (`remap = false`, since the class is not Mojang's) with two `@WrapOperation`s, both `require = 0`:

1. Around `LeaderboardService.getPlayerBadges(UUID)`: `LaurelBadges.withLaurels(original, uuid)`. A null uuid or a player with no laurels gets Wynntils' list back; an empty list gets ours; otherwise a new list of theirs then ours. Ours therefore rotate with theirs once a player holds more than `badgeCount`.
2. Around `RenderUtils.renderLeaderboardBadge`: calls `LaurelBadges.onBadgeDrawn()`, then rewrites the texture, `uOffset` and size for one of ours before the original call. Name-only target, because the descriptor holds Minecraft types that `remap = false` would not remap.

**Inherited gates.** Our laurels draw only where Wynntils' badges would: its "Nametag Rendering" feature on, `showLeaderboardBadges` on, `badgeCount` above 0, the player not an NPC, and the nametag not hidden. They dim while the player sneaks and sit above any gear lines. A user who turns Wynntils' badges off loses ours. There is deliberately no vetsmod config key.

**Per-frame cost.** `withLaurels` runs once per visible player per frame: a volatile read and a map lookup, and an allocation only for a player who holds both kinds.

## 5. Failure modes

The config is `required: false` with `defaultRequire: 0` and both wraps carry `require = 0`, because users update Wynntils on their own and a refactor of `drawBadges` must cost the laurels and not the game. Two distinct failures follow, and one of them is caught in code.

| | What happens | Outcome |
|---|---|---|
| **Handler-signature mismatch**, e.g. Wynntils changes `renderLeaderboardBadge`'s parameters | A WARN in the log; the class loads **untransformed** | No laurels. Any other mod's mixins into that class are dropped too. No crash |
| **A wrap-2 no-match**, e.g. the call is renamed or moved | Wrap 1 stays applied: our badges reach Wynntils' draw call with negative offsets | Garbage drawn. Caught by the guard below |

**The half-applied guard.** `withLaurels` sets `awaitingDraw` whenever the list it returns contains any of ours, alone or merged; `onBadgeDrawn()` clears it. Finding it still set on the next call means wrap 2 never ran, so `disabled` is set, one warning is logged, and Wynntils' list is returned from then on. A `LinkageError` while building the map (Wynntils changed `LeaderboardBadge`) disables the same way and leaves `builtFrom` unchanged. Both last for the session.

**Check the log on the production jar.** The non-required config turns mistakes into log lines, and a dev run uses Mojang names on both sides, so a target-string mistake only shows in production. In `latest.log` there should be no "orphaned mixins", no "FAILED during" and no `LaurelBarMixin` warning.

## 6. Bump-time check

Run this in the Wynntils clone whenever the Wynntils compile version moves (see `../Wynntils` in [CLAUDE.md](CLAUDE.md); `git pull --ff-only` first, the clone is usually stale). The two hashes are the bodies of `drawBadges` and `renderLeaderboardBadge`; they were identical from v4.1.4 to v4.2.16 (`5dcb28b7` and `9d4d9ac0`), the whole range `fabric.mod.json` accepts.

```bash
F=common/src/main/java/com/wynntils/features/players/CustomNametagRendererFeature.java
R=common/src/main/java/com/wynntils/utils/render/RenderUtils.java
h(){ git show "$1:$2" | awk "/$3/{f=1} f{print} f&&/^    }\$/{exit}" | md5sum | cut -c1-8; }
for t in v4.1.17 <new-tag>; do echo "$t $(h $t $F 'private void drawBadges') $(h $t $R 'public static void renderLeaderboardBadge')"; done
```

Also compare the two lines the hash does not cover, at both tags:

- `public record LeaderboardBadge(int uOffset, int vOffset) {`, in `services/leaderboard/type/LeaderboardBadge.java`;
- `public List<LeaderboardBadge> getPlayerBadges(UUID id) {`, in `services/leaderboard/LeaderboardService.java`.

A mismatch is not automatically a break (the mixin is written to fail safe), but it is the cue to re-read the call sites and the handler signature.

## 7. The asset notice

`laurels_notice.txt` sits beside the PNG (lower case: a resource path may not hold capitals) and the README carries the same credit line. It says only what is factual: the wreath frames in `laurels.png` are adapted from Wynntils' leaderboard badge sheet, © Wynntils, and the icons inside the wreaths are the Wynncraft Veterans' own.

**The licence basis is not confirmed, and nothing here claims one.** Wynntils' README puts its code under LGPL-3.0 but says that, unless specified otherwise, all its assets are © Wynntils, and `leaderboard_badges.png` carries no separate grant. So the wreaths are **not** described as LGPL anywhere. Before the sheet ships, the basis (for example Wynntils' permission) must be confirmed and the notice reworded to say it.

## 8. The `/wv laurels` command

Two things only a person can supply, both gated to **confirmed staff** (`isConfirmedStaff()`: strategist, chief or owner; a captain is not). There is no chief-only rule anywhere, here or on the server.

| Command | Does |
|---|---|
| `/wv laurels update` | Collects what only a game client can see, for the metrics the server's `collect` list names. Today that is the objective streak (§9). Also needs `isReturners()` |
| `/wv laurels return <1\|2\|3> <playerName>` | Puts a player on a place of the weekly-return podium |
| `/wv laurels return clear` | Empties the podium |

The `laurels` node has `.requires(isConfirmedStaff())`, and every handler opens with the same re-check (`/wv laurels is restricted to authenticated stewards.`), as `/wv check`'s does. A bare node prints a red usage line through `CommandUsage`, so an unfinished command never reaches the server.

**`update` is a [`DistributeRuns`](vetsmod_distribute.md) run**, noun `"laurels update"`, because it drives the same Members menu as `/wv distribute` and must not overlap one: started during a distribution it queues behind it, and the other way round. It runs `STEPS` filtered by `collect` (`selectSteps`; a `null` `collect`, before the first fetch, keeps every step), one after another, and the run ends when `runSteps` runs off the last step and calls `DistributeRuns.finished()`. A step whose walk times out or loses its menu ends the run through the walker's `DistributeRuns.stop` instead, and the steps after it never start. With no step left it prints "Nothing for this client to collect in-game." and logs the unhandled `collect` names at debug.

**`return` takes no part in a run.** It sends one `laurels_set` frame (`LaurelCommands.upload`, through `V1ApiManager.sendStaffActionFrame`, the default 45 s commit wait) and prints the ack on the client thread. The request and ack shapes are owned by [`v1_protocol.md` §1.16](../../temporary-server/v1_protocol.md); the client's reading of them is `LaurelCommands.ackLines`:

- an ack carrying `outcome_unknown` (the socket was lost, or no reply came in time) is red and says it is safe to run again, because both writes replace what they set;
- an error ack is red, `[laurels] failed: <detail>`;
- a `return` set answers `accepted: 0` for a name the server cannot resolve, which prints red "No player named X was found." and changes nothing;
- otherwise the caller's lines: green for a placement or a clear, and for a streak upload the matched count, the unmatched names in yellow and a gray note (§9).

## 9. The streak fallback (temporary)

The Wynncraft API's member `weekly.streak` is empty today (a verified, low-priority Wynncraft bug), so until it is fixed the objective streak comes from the Members menu. The server switches source by itself: it serves `sources.streak` `"in_game"` and `collect: ["streak"]` while fewer than half the members have an API streak, and `"api"` with an empty `collect` once they do. Nothing schedules a crawl: the streaks are as fresh as the last `/wv laurels update`.

`StreakCrawl.run` is `update`'s one step. It prints a gray line, arms `MembersListWalker.armWalk("the streak walk timed out", …)`, opens the Members menu, and on completion closes it and reads each tile's `- Streak: N` lore line (`parseStreak`: the first match on any line, anchored, so `- Streak: 7 weeks` is not one). It uploads `{"metric":"streak","entries":[{"name","streak"}…]}`, one entry per tile with a streak, then calls `next`. **An empty read prints a yellow line and uploads nothing**, because the server replaces its stored streaks wholesale. The server also keeps the previous upload when fewer than half the members matched (`replaced: false`, shown in gray), and stores an upload made while the API has taken over without showing it (`active: false`, also gray).

**Known limitation: tile-name collisions.** The upload names members by tile name (`legacyName`). `MembersListWalker` keeps only the first of two tiles whose names collide, so one of the two members' streaks can be lost, and the wholesale replace then leaves that member without a streak laurel until a crawl reads them.

**Pending check: a UUID on the tile.** Nobody has yet dumped a Members tile to see whether its head carries a profile UUID. Do it in game: `/wv debug set itemDump true`, hover a tile, press numpad `+`. If it does, add the UUID to `MemberEntry` (a walker improvement in its own right), upload `{uuid, streak}` (the server takes both forms), and the collision limitation goes away. Until it is done the crawl uploads names.

## 10. Retiring the in-game fallback

1. **Automatic, no release.** Once the API serves streaks the server flips to `"api"`, `collect` empties, and `/wv laurels update` reports that there is nothing to collect.
2. **Cleanup, once the API has held for a few weeks.**
   - **vetsmod.** Delete `StreakCrawl`, its test and the `STEPS` entry. With `STEPS` empty, also delete `update`, `StepBody`, `Step`, `selectSteps` and `runSteps`, the `isReturners` check, `LaurelStandings.collect`, `LaurelPoller.parse`'s reading of `collect` and their test cases, and `LaurelCommandsTest`'s `selectSteps` cases.
   - **Help.** The page loses its `update` row (9 siblings to 7) and the index row becomes `/wv laurels return …`. Re-pin `HelpCommandsTest` and fix the node's `CommandUsage` text.
   - **Revert to the one-caller forms.** `MembersListWalker.armWalk(Completion)` with its fixed reason, and `DistributeRuns.submit(Runnable)` with the fixed "distribution" lines. `DistributeRunsTest` keeps its byte-for-byte pins and loses the mixed-queue case.
   - **Then.** Fix the doclint-forced `StreakCrawl` links (`MembersListWalker`'s class Javadoc, `MembersGui.closeIfOpen`), set `MembersListWalker.armWalk` back to 1 in `tools/check-doc-counts.sh`, recount the `Json` rows with `--count`, and update the docs that describe the second caller and the noun-aware lines: this file, [vetsmod_distribute.md](vetsmod_distribute.md), [vetsmod_commands.md](vetsmod_commands.md), [server_api_reference.md](server_api_reference.md) and [vetsmod_tests.md](vetsmod_tests.md).
   - **temporary-server.** Delete the `streak` branch of `laurels_set`, `replace_streaks` and the `"in_game"` side of `streak_source`. The stored `streak` key is dropped on the next write (the loader ignores unknown keys). Keep `collect` always empty until no released vetsmod reads it. Drop the fallback's entries from the protocol docs.

## 11. Tests

`LaurelPollerTest` pins `parse` (each skip rule, dashed and undashed uuids, duplicates, column order, `collect`, null bodies) and `LaurelMetricTest` the wire names and columns. `LaurelCommandsTest` pins `selectSteps`, the frame fields and the ack lines, `StreakCrawlTest` the lore parse, the entries and the streak ack lines, and `DistributeRunsTest` the run lines both kinds of run print. `LaurelBadges` and the mixin are untestable by design: they need Wynntils' render types, which are absent at test runtime ([CLAUDE.md](CLAUDE.md), harness bullet).
