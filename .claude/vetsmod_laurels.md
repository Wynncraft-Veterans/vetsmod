---
name: vetsmod Laurels
description: The vets laurels drawn on Wynntils' nametag badge bar — the standings model and poller, the negative-uOffset encoding, LaurelBarMixin and the Wynntils gates it inherits, the failure modes and guard, the bump-time check, the sheet's asset notice
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
| Sheet | `src/main/resources/assets/vetsmod/textures/nametag/laurels.png`, with its notice beside it |

## 1. Model

`LaurelMetric` is `XP("xp", 0)`, `GRAIDS("graids", 1)`, `STREAK("streak", 2)` and `RETURN("return", 3)`: the server's wire name and the sheet column, the column kept as a field so reordering the constants cannot re-map the sheet. A `Laurel` is a metric and a place from 1 to `Laurel.PLACES` (3); `row()` is `place - 1`. `LaurelStandings` is `Map<UUID, List<Laurel>>` plus the `Set<LaurelMetric>` the server wants a game client to `collect`, deeply immutable so the poller can publish it through a volatile and the render thread can read it.

`LaurelPoller.standings()` is `null` until the first successful fetch, which an empty `collect` cannot say. The server answers 503 until it has its first guild data, which `PolledJson` treats as a failed fetch, so a cold start stays `null`. `parse` keeps what it can from a bad body: it skips a non-object entry, an unparseable uuid, an unknown metric, a place outside 1 to 3 and a repeat of a player and metric (the first wins), orders each player's laurels by column, and drops names in `collect` it does not know. A body that is not an object, or has no `laurels` array, yields `null` and keeps the previous snapshot. The response's `username`, `updated_at` and `sources` are not read. Nothing in the client acts on `collect` yet.

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

## 8. Tests

`LaurelPollerTest` pins `parse` (each skip rule, dashed and undashed uuids, duplicates, column order, `collect`, null bodies) and `LaurelMetricTest` the wire names and columns. `LaurelBadges` and the mixin are untestable by design: they need Wynntils' render types, which are absent at test runtime ([CLAUDE.md](CLAUDE.md), harness bullet).
