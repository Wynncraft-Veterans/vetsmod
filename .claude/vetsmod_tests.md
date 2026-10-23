---
name: vetsmod Unit Tests
description: Every JUnit test class under src/test/java: its subject, the contract the suite pins down, and for each test method a summary plus exactly what each of its assertions checks
type: project
---
# vetsmod unit tests

What each test under [`src/test/java/org/wynnvets/`](../src/test/java/org/wynnvets/) checks, grouped by package. How the harness works (what a test can load, the seam rules, how to run it) is owned by the harness bullet in [CLAUDE.md](CLAUDE.md), and this doc does not restate it.

Each class section names the test file and its subject and says what the suite as a whole pins down. Where the class has assertion helpers, it says what each one checks. Then it lists every `@Test` method in file order: a one-line summary, and beneath it one bullet per assertion statement in source order, giving the expression, the relation and the expected value. Bullets that cover several parallel statements list every input and its expected result. A test with no assertion statement says what makes it pass.

When you add, rename or delete a test, or change what it asserts, update its entry here in the same commit.

## Packages

- [`org.wynnvets`](#orgwynnvets) — [`LocaleFoldTripwireTest`](#localefoldtripwiretest)
- [`org.wynnvets.api`](#orgwynnvetsapi) — [`InboundAckRouterTest`](#inboundackroutertest), [`V1ApiManagerFramesTest`](#v1apimanagerframestest)
- [`org.wynnvets.chat`](#orgwynnvetschat) — [`BannedCharsTest`](#bannedcharstest), [`GuildChatLineTest`](#guildchatlinetest), [`LegacyTextParserTest`](#legacytextparsertest), [`MessageBodyFormatterTest`](#messagebodyformattertest), [`NickResolverTest`](#nickresolvertest), [`PillCodecTest`](#pillcodectest), [`PrependTest`](#prependtest), [`RankDisplayMapTest`](#rankdisplaymaptest)
- [`org.wynnvets.chat.dispatcher`](#orgwynnvetschatdispatcher) — [`FindDispatcherTest`](#finddispatchertest), [`GuildChatDispatcherTest`](#guildchatdispatchertest), [`MessageFanoutDispatcherTest`](#messagefanoutdispatchertest)
- [`org.wynnvets.chat.spoiler`](#orgwynnvetschatspoiler) — [`SpoilerCodecTest`](#spoilercodectest)
- [`org.wynnvets.commands`](#orgwynnvetscommands) — [`HelpCommandsTest`](#helpcommandstest)
- [`org.wynnvets.config`](#orgwynnvetsconfig) — [`NamedColorTest`](#namedcolortest), [`ShippedConfigTest`](#shippedconfigtest), [`VetsConfigDefaultsTest`](#vetsconfigdefaultstest), [`VetsConfigKeysTest`](#vetsconfigkeystest), [`VetsConfigSchemaTest`](#vetsconfigschematest)
- [`org.wynnvets.datamodels`](#orgwynnvetsdatamodels) — [`UserTest`](#usertest)
- [`org.wynnvets.debug.anni`](#orgwynnvetsdebuganni) — [`AnniDebugCommandsTest`](#annidebugcommandstest)
- [`org.wynnvets.distribute`](#orgwynnvetsdistribute) — [`MembersGuiTest`](#membersguitest)
- [`org.wynnvets.distribute.command`](#orgwynnvetsdistributecommand) — [`NameOrSelectorArgumentTest`](#nameorselectorargumenttest)
- [`org.wynnvets.distribute.distributor`](#orgwynnvetsdistributedistributor) — [`GraidsDistributorTest`](#graidsdistributortest), [`MemberSlotPresserTest`](#memberslotpressertest), [`ObjectivesDistributorTest`](#objectivesdistributortest), [`RandomDistributorTest`](#randomdistributortest), [`SplitDistributorTest`](#splitdistributortest)
- [`org.wynnvets.distribute.recipients`](#orgwynnvetsdistributerecipients) — [`NameResolverTest`](#nameresolvertest), [`NoAspectsFilterTest`](#noaspectsfiltertest)
- [`org.wynnvets.fetcher.lookup`](#orgwynnvetsfetcherlookup) — [`MojangCooldownTest`](#mojangcooldowntest)
- [`org.wynnvets.fetcher.ondemand`](#orgwynnvetsfetcherondemand) — [`ReturnersRosterProbeTest`](#returnersrosterprobetest), [`TimeLabelsTest`](#timelabelstest)
- [`org.wynnvets.fetcher.polling`](#orgwynnvetsfetcherpolling) — [`PolledJsonMapTest`](#polledjsonmaptest), [`PollingServiceTest`](#pollingservicetest), [`StaffRanksPollerTest`](#staffrankspollertest)
- [`org.wynnvets.guild`](#orgwynnvetsguild) — [`GuildCheckResultTest`](#guildcheckresulttest), [`ReturnersReauthTest`](#returnersreauthtest), [`StaffRankCheckerTest`](#staffrankcheckertest)
- [`org.wynnvets.items`](#orgwynnvetsitems) — [`ItemDefinitionsTest`](#itemdefinitionstest), [`ScreenshotLatchTest`](#screenshotlatchtest)
- [`org.wynnvets.listeners`](#orgwynnvetslisteners) — [`PartyRosterListenerTest`](#partyrosterlistenertest), [`RankChangeListenerTest`](#rankchangelistenertest)
- [`org.wynnvets.logging`](#orgwynnvetslogging) — [`VetsLoggerTest`](#vetsloggertest)
- [`org.wynnvets.mwe.anni`](#orgwynnvetsmweanni) — [`AnniPartyReporterTest`](#annipartyreportertest)
- [`org.wynnvets.mwe.anni.aggressive`](#orgwynnvetsmweanniaggressive) — [`GhostsPromptHandlerTest`](#ghostsprompthandlertest)
- [`org.wynnvets.mwe.anni.bossbar`](#orgwynnvetsmweannibossbar) — [`VetsBossBarContentBuilderTest`](#vetsbossbarcontentbuildertest)
- [`org.wynnvets.mwe.anni.mode`](#orgwynnvetsmweannimode) — [`AnniModeTest`](#annimodetest)
- [`org.wynnvets.mwe.anni.outline`](#orgwynnvetsmweannioutline) — [`AnniOutlinePaletteTest`](#annioutlinepalettetest)
- [`org.wynnvets.mwe.anni.render`](#orgwynnvetsmweannirender) — [`AnniCommandRendererTest`](#annicommandrenderertest), [`AnniHoverBuilderTest`](#annihoverbuildertest), [`AnniOnboardingBlocksTest`](#annionboardingblockstest), [`AnniStatusSectionsTest`](#annistatussectionstest)
- [`org.wynnvets.mwe.anni.state`](#orgwynnvetsmweannistate) — [`AnniSnapshotsTest`](#annisnapshotstest), [`AnniWindowsTest`](#anniwindowstest)
- [`org.wynnvets.rendering.colors`](#orgwynnvetsrenderingcolors) — [`ColorMathTest`](#colormathtest)
- [`org.wynnvets.util`](#orgwynnvetsutil) — [`ConfigValueTextTest`](#configvaluetexttest), [`JsonTest`](#jsontest), [`TickDelaysTest`](#tickdelaystest), [`UuidsTest`](#uuidstest)

## `org.wynnvets`

### `LocaleFoldTripwireTest`

Test: [`LocaleFoldTripwireTest.java`](../src/test/java/org/wynnvets/LocaleFoldTripwireTest.java) · Subject: the mod's own source tree (`src/main/java` and `src/client/java`), scanned as text

This is a build tripwire, not a unit test of one class. Under a Turkish or Azeri default locale a no-argument `toLowerCase()` or `toUpperCase()` turns `I` into dotless `ı` and `i` into dotted `İ`, so every case fold in the mod must pass `Locale.ROOT`. The suite reads every `.java` file under the two source roots from disk, matches the line-level regex `.toLowerCase()` / `.toUpperCase()` with empty parentheses, and fails naming each `file:line`. Comments are scanned too, and test sources are not.

- **`noSourceFoldsCaseWithTheDefaultLocale`**: Walks `src/main/java` and `src/client/java` (asserting both directories exist relative to the project directory) and asserts that no line contains a bare `toLowerCase()` or `toUpperCase()` call.
  - For each `root` in `src/main/java` and `src/client/java`: `Files.isDirectory(Path.of(root))` is true, resolved against the working directory.
  - `hits` equals `List.of()`, where `hits` collects `file:line` for every line under those two roots (`.java` files only) that matches `.toLowerCase()` or `.toUpperCase()` with empty parentheses. Comments count.

## `org.wynnvets.api`

### `InboundAckRouterTest`

Test: [`InboundAckRouterTest.java`](../src/test/java/org/wynnvets/api/InboundAckRouterTest.java) · Subject: [`InboundAckRouter`](../src/client/java/org/wynnvets/api/InboundAckRouter.java)

`InboundAckRouter.route` handles the untyped `{"status": ...}` acks that arrive on the v1 inbound socket and decides whether each one resolves a pending staff-action callback, is an auth ack, or is only logged. This suite pins the non-auth routing: which acks pop the head of `V1ApiManager.staffActionCallbacks`, in what order, and which leave the queue alone. The auth-ok and auth-failure branches are not driven because they reach `VetsConfig`, which cannot initialise outside a game launch, so two tests rely on `assertDoesNotThrow` to show an ack stayed out of those branches. A recording callback stands in for the staff-action callbacks, and `reset` (run before and after each test) clears the static auth state and the callback queue.

- **`eachStaffActionShapeRoutesToTheHeadCallback`**: Acks carrying `kind`, `triggered`, `total_points`, `target_uuid`, or status `would_trigger` each hand the full ack to the single queued callback and empty the queue.
  - For each `shape` in the five JSON ack strings (`{"status":"ok","kind":"caution"}`, `{"status":"ok","triggered":false}`, `{"status":"ok","total_points":3}`, `{"status":"ok","target_uuid":"<TARGET>"}` with `TARGET` = `0a1b2c3d-1111-2222-3333-444455556666`, `{"status":"would_trigger"}`): after `InboundAckRouter.route` of that ack with one `head` callback queued, `calls` equals `List.of("head " + shape)`, i.e. the head callback received exactly that ack re-serialised.
  - For the same five shapes: `V1ApiManager.staffActionCallbacks` is empty after routing, i.e. the head callback was popped.
- **`twoPendingCallbacksResolveInSendOrder`**: Two queued callbacks receive two successive `total_points` acks first-in, first-out, each getting its own ack.
  - After queueing callbacks `first` then `second` and routing acks `{"status":"ok","total_points":1}` then `{"status":"ok","total_points":2}`, `calls` equals `["first {\"status\":\"ok\",\"total_points\":1}", "second {\"status\":\"ok\",\"total_points\":2}"]`: first ack went to `first`, second to `second`.
- **`anOrphanErrorPopsThePendingCallback`**: A `status:"error"` ack with no auth ack awaited and a callback pending is delivered to that callback, and the queue empties.
  - After routing `{"status":"error","detail":"no such player"}` with one `head` callback queued, `calls` equals `["head {\"status\":\"error\",\"detail\":\"no such player\"}"]`.
  - `V1ApiManager.staffActionCallbacks` is empty after that route.
- **`anAuthRejectedErrorGoesToAPendingCallbackWhenNoAuthAckIsAwaited`**: An `"auth rejected: x"` error goes to the pending callback when no auth ack is awaited, and the confirmed-staff flag and rank (`"chief"`) stay unchanged.
  - With `confirmedStaff` true, rank `"chief"`, no auth ack awaited and one `head` callback queued, routing `{"status":"error","detail":"auth rejected: x"}` leaves `calls` equal to `["head {\"status\":\"error\",\"detail\":\"auth rejected: x\"}"]`.
  - `SessionAuthState.confirmedStaff` is still true after that route.
  - `SessionAuthState.confirmedStaffRank` still equals `"chief"` after that route.
- **`aStaffShapedErrorWithAnEmptyQueueIsSwallowed`**: A staff-shaped error (has `target_uuid`, detail `"auth rejected: x"`) with an empty queue does not throw, showing it returns before reaching the auth-failure branch.
  - `InboundAckRouter.route(error)` does not throw, where `error` is `{"status":"error","detail":"auth rejected: x","target_uuid":"<TARGET>"}` routed with an empty callback queue and no auth ack awaited.
- **`aPlainErrorWithNothingPendingIsNotAnAuthFailure`**: A plain `"rate limited"` error with an empty queue does not throw and leaves confirmed staff state (`true`, `"chief"`) untouched, so it stays out of the auth-failure branch.
  - `InboundAckRouter.route` of `{"status":"error","detail":"rate limited"}` with an empty callback queue (and `confirmedStaff` true, rank `"chief"`) does not throw.
  - `SessionAuthState.confirmedStaff` is still true after that route.
  - `SessionAuthState.confirmedStaffRank` still equals `"chief"` after that route.
- **`aTierKeyKeepsAWouldTriggerAckOutOfTheQueue`**: A `would_trigger` ack that also carries `tier` is treated as auth-shaped: the pending callback is not called and stays queued.
  - After routing `{"status":"would_trigger","tier":"member"}` with one `head` callback queued, `calls` is empty (callback not invoked).
  - `V1ApiManager.staffActionCallbacks.size()` equals 1 (callback still queued).
- **`aFrameWithoutStatusIsIgnored`**: A frame with no `status` key does not invoke or remove the pending callback.
  - After routing `{"kind":"caution"}` (no `status`) with one `head` callback queued, `calls` is empty.
  - `V1ApiManager.staffActionCallbacks.size()` equals 1.
- **`aPlainOkAckLeavesAPendingCallbackQueued`**: A bare `{"status":"ok"}` ack matches no staff-action shape, so the pending callback is not called and stays queued.
  - After routing `{"status":"ok"}` with one `head` callback queued, `calls` is empty.
  - `V1ApiManager.staffActionCallbacks.size()` equals 1.

### `V1ApiManagerFramesTest`

Test: [`V1ApiManagerFramesTest.java`](../src/test/java/org/wynnvets/api/V1ApiManagerFramesTest.java) · Subject: [`V1ApiManager`](../src/client/java/org/wynnvets/api/V1ApiManager.java), `AnniWsSender`, `AnniQueryClient`, `AnniRsvpClient`, `AnniScrollspotClient`

`V1ApiManager` and the anni senders write JSON frames on the v1 inbound WebSocket. This suite pins the exact serialised text of each frame (key order included) and what each socket-checking send does when the socket is missing or disconnected: nothing is written, and the staff-action and anni clients report a not-connected outcome instead of waiting. A `RecordingWsClient` subclass of `WsClient` replaces the real socket: it never connects, records each frame as the real send would serialise it, and has a settable connected flag. For the frames with a random `uuid` and a clock `timestamp` (inbound chat and `rank_change`), a `masked` helper first checks the uuid is canonical and the timestamp is a fractional-seconds double between two sampled clock readings, then replaces both so the rest of the string is compared literally. `reset` removes the injected socket, clears the registration and the awaited-auth flag after each test.

Asserting helpers:

- **`assertNothingSentWhileDown`**: Run with the inbound socket down (null client, or `socket.connected` false): calls `sendRegistration`, `sendAuth` (after setting `expectingAuthAck` false), `sendQueueStatus`, `sendInbound`, `sendRankChange`, `sendTabList`, then asserts: `SessionAuthState.expectingAuthAck` is false after `sendAuth("k3y-Value_123")`; `AnniWsSender.sendAnniQuery()`, `sendAnniScrollspotSet(12, 64, -300)`, `sendAnniScrollspotSet(null, null, null)`, `sendAnniRsvp("hard")` and `sendAnniPartyObservation(List.of("Metrafish"), "Metrafish", "WC1")` each return false; `sendStaffActionFrame("caution_check", new JsonObject(), cb)` calls `cb` at once so `acks` equals `["{\"status\":\"error\",\"detail\":\"Inbound WebSocket not connected.\"}"]`; `sendStaffActionFrame("caution_check", null, null)` does not throw; `AnniQueryClient.query()` future `isDone()` is true and `join()` is null; `AnniRsvpClient.send("revoke").join()`, `AnniScrollspotClient.set(12, 64, -300).join()` and `AnniScrollspotClient.clear().join()` each equal `new Ack(false, "not connected")` (of their own client's `Ack` type); after `V1ApiManager.disconnect()`, `acks.size()` equals 1 (nothing queued) and `socket.wire` is empty.
- **`masked`**: For each recorded frame in `socket.wire`: its `uuid` string equals `UUID.fromString(uuid).toString()` (canonical UUID); `timestamp` is a JSON number; `timestamp` seconds (double) is between `t0/1000.0` and `t1/1000.0` inclusive; `Double.toString(seconds)` equals the timestamp's raw string (written as a double, not a whole number). Then replaces `uuid` with `"U"` and `timestamp` with 0 and returns the re-serialised frames.

Tests:

- **`registration`**: `sendRegistration(uuid, "Metrafish", "guild")` writes `{"type":"register","uuid":...,"username":"Metrafish","tier":"guild"}` in that key order.
  - After `sendRegistration("bb171c68-7080-4d02-be7b-cc2d5dcbcd82", "Metrafish", "guild")`, `socket.wire` equals the single frame `{"type":"register","uuid":"bb171c68-7080-4d02-be7b-cc2d5dcbcd82","username":"Metrafish","tier":"guild"}`.
- **`auth`**: `sendAuth` writes `{"type":"auth","key":...}` and marks an auth ack as awaited.
  - After `sendAuth("k3y-Value_123")`, `socket.wire` equals the single frame `{"type":"auth","key":"k3y-Value_123"}`.
  - `SessionAuthState.expectingAuthAck` is true after sending.
- **`queueStatus`**: `sendQueueStatus(true, "NA30")` and `(false, null)` write `queued` with the world, and a null world becomes the empty string.
  - After `sendQueueStatus(true, "NA30")` and `sendQueueStatus(false, null)`, `socket.wire` equals `{"type":"queue_status","queued":true,"world":"NA30"}` then `{"type":"queue_status","queued":false,"world":""}` (null world sent as empty string).
- **`inbound`**: Two `sendInbound` calls write the chat frame with a null rank sent as `""`, after masking a canonical uuid and a seconds-as-double timestamp within the sampled time window.
  - After `sendInbound("guild", "Chief", "Metrafish", "hello there")` and `sendInbound("waitlist", null, "Holidaze", "hi")`, `masked(t0, t1)` (uuid/timestamp masked, `t0`/`t1` = clock millis before/after) equals `{"uuid":"U","type":"guild","timestamp":0,"rank":"Chief","username":"Metrafish","message":"hello there"}` then `{"uuid":"U","type":"waitlist","timestamp":0,"rank":"","username":"Holidaze","message":"hi"}` (null rank sent as empty string; key order pinned).
- **`rankChange`**: `sendRankChange` writes `rank_change` with `actor`, `target`, `from_rank`, `to_rank` and `classification`, with uuid and timestamp validated and masked as for `inbound`.
  - After `sendRankChange("Metrafish", "Holidaze", "Captain", "Recruit", "mote")`, `masked(t0, t1)` equals the single frame `{"type":"rank_change","uuid":"U","timestamp":0,"actor":"Metrafish","target":"Holidaze","from_rank":"Captain","to_rank":"Recruit","classification":"mote"}`.
- **`tabList`**: `sendTabList` with two `TabListEntry` values writes a `tablist` frame whose `entries` array holds `{server, username}` objects in order.
  - After `sendTabList` with entries (`WC1`,`Metrafish`) and (`EU4`,`Holidaze`), `socket.wire` equals the single frame `{"type":"tablist","entries":[{"server":"WC1","username":"Metrafish"},{"server":"EU4","username":"Holidaze"}]}`.
- **`staffActionFrame_isSentAndItsCallbackQueued`**: `sendStaffActionFrame("caution_check", fields, cb)` writes the type followed by the fields and does not call the callback yet. Disconnecting then drains it with the ack `{"status":"error","detail":"Connection closed before reply."}`.
  - After `sendStaffActionFrame("caution_check", fields, cb)` with `fields` = `{target_username:"Holidaze", confirm:true}`, `socket.wire` equals the single frame `{"type":"caution_check","target_username":"Holidaze","confirm":true}`.
  - `acks` (callback results) is empty right after sending, i.e. the callback has not run.
  - After `V1ApiManager.disconnect()`, `acks` equals `["{\"status\":\"error\",\"detail\":\"Connection closed before reply.\"}"]`, i.e. the queued callback is drained with the synthetic error ack.
- **`anniQuery`**: `AnniWsSender.sendAnniQuery()` returns true and writes `{"type":"anni_query"}`.
  - `AnniWsSender.sendAnniQuery()` returns true.
  - `socket.wire` equals the single frame `{"type":"anni_query"}`.
- **`anniScrollspotSet_setAndClear`**: Setting a spot writes `scroll_spot` with `x`, `y`, `z`; clearing (all null) writes only the type, because the null member is dropped on serialisation, a gap against the server protocol doc noted in the test comment.
  - `AnniWsSender.sendAnniScrollspotSet(12, 64, -300)` returns true.
  - `AnniWsSender.sendAnniScrollspotSet(null, null, null)` returns true.
  - `socket.wire` equals `{"type":"anni_scrollspot_set","scroll_spot":{"x":12,"y":64,"z":-300}}` then `{"type":"anni_scrollspot_set"}` (clear frame has no `scroll_spot` key because the null member is dropped by Gson).
- **`anniRsvp`**: `sendAnniRsvp("hard")` returns true and writes `{"type":"anni_rsvp","notice":"hard"}`.
  - `AnniWsSender.sendAnniRsvp("hard")` returns true.
  - `socket.wire` equals the single frame `{"type":"anni_rsvp","notice":"hard"}`.
- **`anniPartyObservation`**: A member list containing a null and an empty string is filtered to `["Metrafish","Holidaze"]` alongside leader and world, and all-null arguments write an empty list with empty-string leader and world.
  - `AnniWsSender.sendAnniPartyObservation(Arrays.asList("Metrafish", null, "", "Holidaze"), "Metrafish", "WC1")` returns true.
  - `AnniWsSender.sendAnniPartyObservation(null, null, null)` returns true.
  - `socket.wire` equals `{"type":"anni_party_observation","party_member_usernames":["Metrafish","Holidaze"],"leader_username":"Metrafish","world":"WC1"}` (null and empty names filtered) then `{"type":"anni_party_observation","party_member_usernames":[],"leader_username":"","world":""}`.
- **`noSocket_sendsNothing`**: With no inbound client installed, every send is a no-op, the anni senders return false, and the staff-action and anni clients complete with not-connected results.
  - With `V1ApiManager.inboundClient` null, `assertNothingSentWhileDown()` passes (see HELPER).
- **`socketNotConnected_sendsNothing`**: With a socket that reports not connected, the same set of sends and results holds and nothing is recorded on the wire.
  - With the recording socket installed but `isConnected()` false, `assertNothingSentWhileDown()` passes (see HELPER).

## `org.wynnvets.chat`

### `BannedCharsTest`

Test: [`BannedCharsTest.java`](../src/test/java/org/wynnvets/chat/BannedCharsTest.java) · Subject: [`BannedChars`](../src/client/java/org/wynnvets/chat/BannedChars.java)

`BannedChars` strips characters that must not reach the screen from player-typed text: a hard set (U+00C1, U+E07E, U+E080, U+E086) that breaks the client, and a soft set (section sign, rank icons, banner pieces, negative-width spaces, pill letters) that spoofs Wynncraft badges. The suite pins which characters each strip removes, that neighbouring characters survive, and that text with nothing to strip is returned as the same instance. Every fixture is written as numeric escapes because a rendered private-use character can be lost silently.

- **`stripHard_removesEachHardCharAndKeepsNeighbours`**: Each of the four hard characters, placed between `ok` and `ok`, is removed to give `okok`, and a string of only hard characters strips to empty.
  - For each `c` in `HARD` (U+00C1, U+E07E, U+E080, U+E086): `stripHard("ok" + c + "ok")` equals `"okok"`.
  - `stripHard(HARD)` equals `""`.
- **`stripHard_keepsTheSoftSet`**: A sample with one character from each soft range or point passes through `stripHard` unchanged.
  - `stripHard(SOFT_SAMPLE)` equals `SOFT_SAMPLE`, where `SOFT_SAMPLE` is 17 code points: U+00A7, U+E014, U+E025, U+E00E, U+E011, U+E070, U+E07A, U+E085, U+E000, U+E012, U+E071, U+E07F, U+E087, U+E088, U+2064, U+E040, U+E06F.
- **`stripAll_removesBothSets`**: `stripAll` removes the hard set and the whole soft sample together, and each soft character individually between `ok` and `ok` gives `okok`.
  - `stripAll("a" + HARD + SOFT_SAMPLE + "b")` equals `"ab"`.
  - For each `c` in `SOFT_SAMPLE` (its 17 code points): `stripAll("ok" + c + "ok")` equals `"okok"`.
- **`stripAll_keepsTheGapsBetweenSoftRanges`**: Code points just outside the soft ranges (U+E001, U+E002, U+E003, U+E006, U+E00D, U+E013, U+E026, U+E03F, U+E072, and the spoiler codec's U+F600, U+F601, U+F6C3) are kept by `stripAll`.
  - `stripAll(kept)` equals `kept`, where `kept` is 12 code points: U+E001, U+E002, U+E003, U+E006, U+E00D, U+E013, U+E026, U+E03F, U+E072, U+F600, U+F601, U+F6C3.
- **`cleanText_returnsTheSameInstance`**: `"hello, world"` is returned by identity from both `stripHard` and `stripAll`.
  - `stripHard(clean)` is the same instance as `clean`, where `clean` is `"hello, world"`.
  - `stripAll(clean)` is the same instance as `clean`.
- **`nullAndEmpty_passThrough`**: Both strips return null for null, and `stripAll("")` returns the empty string.
  - `stripHard(null)` is null.
  - `stripAll(null)` is null.
  - `stripAll("")` equals `""`.

### `GuildChatLineTest`

Test: [`GuildChatLineTest.java`](../src/test/java/org/wynnvets/chat/GuildChatLineTest.java) · Subject: [`GuildChatLine`](../src/client/java/org/wynnvets/chat/GuildChatLine.java)

`GuildChatLine` splits a guild chat line of the form `<rank glyphs> <display name>: <body>` into rank indicator, display name and body, through two entry points: `parse` (name and trimmed body, null-safe) and `parseServerLine` (name, rank indicator and a body offset into the original string). The suite pins the header scan both share (name is the trimmed text between the last custom glyph and the first colon), then the four cells where the two parsers deliberately differ: null handling, return shape, rank-indicator retention, and whitespace skipping before the body. The fixtures use a private-use rank glyph, a supplementary unassigned code point (counts as a glyph) and a BMP unassigned code point (does not), and the file ties the glyph bound to `PillCodecTest`'s predicate test.

- **`bothReadTheNameBetweenTheLastGlyphAndTheColon`**: For a glyph, space, `Metrafish: hello there` line, `parse` gives username `Metrafish` and message `hello there`, and `parseServerLine` also gives username `Metrafish`.
  - `shared`, the result of `GuildChatLine.parse(line)` with `line` = `PUA + " Metrafish: hello there"` (`PUA` is U+E062, a private-use rank glyph), is not null.
  - `shared.username()` equals `"Metrafish"`.
  - `shared.message()` equals `"hello there"`.
  - `server`, the result of `GuildChatLine.parseServerLine(line)` for the same `line`, is not null.
  - `server.username()` equals `"Metrafish"`.
- **`theNameMayContainSpaces`**: A display name such as `Salted/Some Nickname` is returned whole by `parse`.
  - `shared` = `GuildChatLine.parse(PUA + " Salted/Some Nickname: hi")` is not null.
  - `shared.username()` equals `"Salted/Some Nickname"` (spaces inside the name are kept).
- **`theScanTakesTheLastGlyphNotTheFirst`**: With a glyph, `junk`, a second glyph, then `Metrafish: hi`, only the text after the final glyph is the name.
  - `shared` = `GuildChatLine.parse(PUA + "junk" + PUA + " Metrafish: hi")` is not null.
  - `shared.username()` equals `"Metrafish"` (the name starts after the last glyph, not the first).
- **`supplementaryUnassignedCountsAsAGlyphButBmpUnassignedDoesNot`**: A leading U+D0002 yields username `Metrafish`, while a leading unassigned BMP character (U+0378) makes `parse` return null.
  - `viaSupplementary` = `GuildChatLine.parse(SUPPLEMENTARY_UNASSIGNED + " Metrafish: hi")` is not null; `SUPPLEMENTARY_UNASSIGNED` is U+D0002, unassigned and above the BMP.
  - `viaSupplementary.username()` equals `"Metrafish"`.
  - `GuildChatLine.parse(BMP_UNASSIGNED + " Metrafish: hi")` is null; `BMP_UNASSIGNED` is U+0378, unassigned but inside the BMP, so it is not a rank glyph and no glyph precedes the colon.
- **`aLineWithNoGlyphBeforeTheColonIsRejected`**: `Metrafish: hi` returns null from both parsers.
  - `GuildChatLine.parse("Metrafish: hi")` is null.
  - `GuildChatLine.parseServerLine("Metrafish: hi")` is null.
- **`aGlyphAfterTheColonDoesNotCount`**: A glyph that appears only after the colon is ignored, so `parse` returns null.
  - `GuildChatLine.parse("Metrafish: " + PUA + " hi")` is null (the only glyph is after the colon).
- **`missingOrLeadingColonIsRejected`**: A line with no colon returns null from both parsers, and a line with a colon at index 0 returns null from `parse`.
  - `GuildChatLine.parse(PUA + " Metrafish hi")` is null (no colon in the line).
  - `GuildChatLine.parse(": " + PUA + " Metrafish")` is null (the colon is at index 0).
  - `GuildChatLine.parseServerLine(PUA + " Metrafish hi")` is null (no colon).
- **`aGlyphEndingExactlyAtTheColonLeavesNoRoomForAName`**: A glyph immediately followed by the colon returns null from both parsers.
  - `GuildChatLine.parse(PUA + ": hi")` is null (the glyph ends at the colon, so there is no name).
  - `GuildChatLine.parseServerLine(PUA + ": hi")` is null.
- **`aWhitespaceOnlyNameIsRejected`**: A name made only of spaces between the glyph and colon returns null from both parsers.
  - `GuildChatLine.parse(PUA + "   : hi")` (three spaces between the glyph and the colon) is null, because the name trims to empty.
  - `GuildChatLine.parseServerLine(PUA + "   : hi")` (same input) is null.
- **`theNameIsTrimmedOnBothSides`**: A name surrounded by spaces and tabs comes back as `Metrafish` from both parsers.
  - `shared` = `GuildChatLine.parse(PUA + " \t Metrafish \t : hi")` is not null.
  - `shared.username()` equals `"Metrafish"`.
  - `server` = `GuildChatLine.parseServerLine(PUA + " \t Metrafish \t : hi")` is not null.
  - `server.username()` equals `"Metrafish"` (same trimming as `parse`).
- **`nullIsRejectedByParseAndThrowsInParseServerLine`**: `parse(null)` returns null, while `parseServerLine(null)` throws `NullPointerException`.
  - `GuildChatLine.parse(null)` is null.
  - `GuildChatLine.parseServerLine(null)` throws `NullPointerException`.
- **`emptyInputIsRejectedByBothButByDifferentRoutes`**: The empty string returns null from both parsers.
  - `GuildChatLine.parse("")` is null.
  - `GuildChatLine.parseServerLine("")` is null.
- **`parseServerLineReturnsAnIndexAndKeepsTheRankIndicator`**: `parseServerLine` returns the rank indicator (the glyph) and a `bodyCharStart` offset whose substring is `hello`, while `parse` returns the message string `hello`.
  - `server` = `GuildChatLine.parseServerLine(line)` with `line` = `PUA + " Metrafish: hello"` is not null.
  - `server.rankIndicator()` equals `PUA` (everything up to and including the last glyph, here the single U+E062).
  - `line.substring(server.bodyCharStart())` equals `"hello"` (the body is an offset into `line`).
  - `shared` = `GuildChatLine.parse(line)` for the same `line` is not null.
  - `shared.message()` equals `"hello"`.
- **`bodyOffsetDivergesWhenTheCharacterAfterTheColonIsNotASpace`**: For a body of newline, space, `hello`, `parse` trims to `hello`, but the `parseServerLine` offset stops at the newline and the remainder is newline, space, `hello`. This is the case that guards against merging the two parsers.
  - `shared` = `GuildChatLine.parse(line)` with `line` = `PUA + " Metrafish: \n hello"` (a space, a newline, a space, then `hello` after the colon) is not null.
  - `shared.message()` equals `"hello"` (`trim()` removes the leading space, newline and space).
  - `server` = `GuildChatLine.parseServerLine(line)` for the same `line` is not null.
  - `line.substring(server.bodyCharStart())` equals `"\n hello"`: `parseServerLine` skips only literal spaces, so it stops at the newline and keeps it.
- **`bodyOffsetAlsoDivergesOnATab`**: A tab after the colon is trimmed by `parse` but kept in the `parseServerLine` remainder (`\thello`).
  - `shared` = `GuildChatLine.parse(line)` with `line` = `PUA + " Metrafish:\thello"` is not null.
  - `shared.message()` equals `"hello"`.
  - `server` = `GuildChatLine.parseServerLine(line)` for the same `line` is not null.
  - `line.substring(server.bodyCharStart())` equals `"\thello"` (the tab is not skipped).
- **`parseAlsoTrimsTheTrailingEndOfTheBodyAndAnIndexCannot`**: `parse` trims trailing spaces from the body, while the `parseServerLine` remainder keeps them (`hello` plus three spaces).
  - `shared` = `GuildChatLine.parse(line)` with `line` = `PUA + " Metrafish: hello   "` (three trailing spaces) is not null.
  - `shared.message()` equals `"hello"` (trailing spaces trimmed).
  - `server` = `GuildChatLine.parseServerLine(line)` for the same `line` is not null.
  - `line.substring(server.bodyCharStart())` equals `"hello   "` (trailing spaces kept).
- **`anEmptyBodyIsAnEmptyStringNotNull`**: With only spaces after the colon, `parse` returns an empty message (not null) and `parseServerLine`'s `bodyCharStart` equals the line length.
  - `shared` = `GuildChatLine.parse(PUA + " Metrafish:   ")` (three spaces after the colon) is not null.
  - `shared.message()` equals `""`.
  - `server` = `GuildChatLine.parseServerLine(line)` with `line` = `PUA + " Metrafish:   "` is not null.
  - `server.bodyCharStart()` equals `line.length()` (the space skip stops at the end of the string).

### `LegacyTextParserTest`

Test: [`LegacyTextParserTest.java`](../src/test/java/org/wynnvets/chat/LegacyTextParserTest.java) · Subject: [`LegacyTextParser`](../src/client/java/org/wynnvets/chat/LegacyTextParser.java)

`LegacyTextParser.literalWithUrls` builds a chat `Component` from text with legacy section-sign (`§`) colour and format codes, turning URLs into clickable links that carry the accumulated style. The suite pins how each code changes the running style, how an unknown, upper-case or trailing code behaves, and how `§r` resets to the base style. Each expected value is a golden `Component.toString()` string for Minecraft 1.21.11, so a Minecraft update that changes that format needs the goldens regenerated. Every input is codes plus a URL only, because plain text goes through the spoiler formatter, which needs `VetsConfig` and cannot initialise in a test.

- **`literalWithUrls_empty_isAnEmptyComponent`**: An empty string with an empty base style renders as `empty`.
  - `parse("", Style.EMPTY)`, which is `LegacyTextParser.literalWithUrls("", Style.EMPTY).toString()`, equals `"empty"`.
- **`literalWithUrls_null_throws`**: A null text throws `NullPointerException`.
  - `parse(null, Style.EMPTY)` throws `NullPointerException`.
- **`literalWithUrls_eachColourCode_setsItsColour`**: Codes `0` to `f` before a URL each give the matching named colour (black through white) on the linked URL literal.
  - For each index `i` of `"0123456789abcdef"` (sixteen colour codes), `parse("§" + code + URL, Style.EMPTY)` equals `linked("color=" + name)`, where `URL` is `"https://wynnvets.org/"` and `linked` builds the golden for the URL alone with a click event `OpenUrl[uri=https://wynnvets.org/]` and the given style prefix. The pairs are 0 black, 1 dark_blue, 2 dark_green, 3 dark_aqua, 4 dark_red, 5 dark_purple, 6 gold, 7 gray, 8 dark_gray, 9 blue, a green, b aqua, c red, d light_purple, e yellow, f white.
- **`literalWithUrls_eachFormatCode_setsItsFlag`**: Codes `k`, `l`, `m`, `n`, `o` give obfuscated, bold, strikethrough, underlined and italic respectively, and `§a§l` accumulates to green plus bold.
  - For each index `i` of `"klmno"`, `parse("§" + code + URL, Style.EMPTY)` equals `linked(flag)`. The pairs are k obfuscated, l bold, m strikethrough, n underlined, o italic.
  - `parse("§a§l" + URL, Style.EMPTY)` (codes `a` then `l`, then the URL) equals `linked("color=green,bold")`: the format code adds to the colour already set.
- **`literalWithUrls_unknownCode_isDroppedAndKeepsTheStyle`**: `§z` before a URL leaves the style empty, and `§a§z` keeps green, so an unknown code is dropped and does not reset to the base style.
  - `parse("§z" + URL, Style.EMPTY)` equals the golden for `https://wynnvets.org/` with an empty style apart from the click event `OpenUrl[uri=https://wynnvets.org/]` (no colour, no flags); the unknown code `z` is dropped.
  - `parse("§a§z" + URL, Style.EMPTY)` equals `linked("color=green")`: the unknown code keeps the style built so far.
- **`literalWithUrls_upperCaseCode_actsAsLowerCase`**: `§A` before a URL gives green, the same as `§a`.
  - `parse("§A" + URL, Style.EMPTY)` equals the golden for `https://wynnvets.org/` with style `color=green` and click event `OpenUrl[uri=https://wynnvets.org/]`, the same as lower-case `a`.
- **`literalWithUrls_colourCodeAfterAFormatCode_resetsToTheBaseStylePlusTheColour`**: `§l§a` gives green with no bold, and with a gold-italic base it gives green italic, so a colour code drops earlier format flags but keeps the base style's other fields.
  - `parse("§l§a" + URL, Style.EMPTY)` equals the golden for `https://wynnvets.org/` with style `color=green` and the click event, with no `bold`: the colour code resets the earlier format code.
  - `parse("§l§a" + URL, BASE)` equals the golden for `https://wynnvets.org/` with style `color=green,italic` and the click event, where `BASE` is `Style.EMPTY` with colour gold and italic: the colour overrides gold, italic from the base survives, bold is gone.
- **`literalWithUrls_resetAfterCodesThatChangedTheStyle_returnsToTheBaseStyle`**: `§a§l§r` gives an empty style for an empty base, and gold italic for a gold-italic base.
  - `parse("§a§l§r" + URL, Style.EMPTY)` equals the golden for `https://wynnvets.org/` with only the click event (no colour, no bold).
  - `parse("§a§l§r" + URL, BASE)` equals the golden for `https://wynnvets.org/` with style `color=gold,italic` and the click event: `r` returns to `BASE` (gold, italic).
- **`literalWithUrls_sectionSignWithNoCodeAfterIt_staysInTheText`**: A section sign at the very end of the text stays in the literal, so the linked URL text and target include it.
  - `parse(URL + "§", Style.EMPTY)` (the URL followed by a trailing U+00A7 with no code) equals the golden for a single literal whose text is `https://wynnvets.org/` followed by U+00A7 and whose style has only the click event, with uri `https://wynnvets.org/` followed by U+00A7; the section sign stays in the text and in the link target.

### `MessageBodyFormatterTest`

Test: [`MessageBodyFormatterTest.java`](../src/test/java/org/wynnvets/chat/MessageBodyFormatterTest.java) · Subject: [`MessageBodyFormatter`](../src/client/java/org/wynnvets/chat/MessageBodyFormatter.java)

`MessageBodyFormatter` turns a chat message body into a component: it drops the banned hard set, strips the server's wrap-continuation markers, draws an inline prefix marker as the separator glyph, and links URLs. The suite pins `formatMessageBody` with golden `Component.toString()` strings (Minecraft 1.21.11 format, so a Minecraft update may need them regenerated), the `isWrapStructure` predicate, and the package-private `stripServerContinuations` called directly, including an exhaustive property test. Bodies given to `formatMessageBody` contain only markers and URLs, because other text goes to the spoiler formatter, which needs `VetsConfig` and cannot initialise in a test.

- **`formatMessageBody_nullOrEmpty_isEmpty`**: A null body and an empty body both render as `empty`.
  - `format(null, AQUA)`, which is `MessageBodyFormatter.formatMessageBody(null, AQUA).toString()` with `AQUA` = `Style.EMPTY` coloured aqua, equals `"empty"`.
  - `format("", AQUA)` equals `"empty"`.
- **`formatMessageBody_dropsTheHardSetFirst`**: A body of only hard-set characters is empty, a hard character beside a marker leaves the marker alone, and a hard character between a newline and a continuation marker is dropped first so the continuation still strips.
  - `format(U+00C1 U+E07E U+E080 U+E086, AQUA)` (the four hard-set characters only) equals `"empty"`.
  - `format(SEP + U+00C1, AQUA)` equals `format(SEP, AQUA)`, where `SEP` is the single character U+E003: a hard character beside the separator marker is dropped.
  - `format("\n" + U+00C1 + COMPACT, AQUA)` equals `"empty"`, where `COMPACT` is the continuation marker U+CFFFC U+E001 U+D0006 (UTF-16 `DAFF DFFC E001 DB00 DC06`): the hard character is removed first, so the newline and marker then match as a continuation and both go.
- **`formatMessageBody_inlineMarker_becomesTheSeparatorInTheTextColour`**: The separator, the full prefix marker and the compact prefix marker each render as one U+E003 literal in the aqua text colour, shadow off, in the `chat/prefix` font.
  - `format(SEP, AQUA)` equals `separator`, a golden with one literal U+E003 whose style is `color=aqua`, `shadowColor=0`, `font=Resource[id=minecraft:chat/prefix]`.
  - `format(FULL, AQUA)` equals the same `separator` golden, where `FULL` is the marker U+CFFFC U+E006 U+CFFFF U+E002 U+CFFFE (UTF-16 `DAFF DFFC E006 DAFF DFFF E002 DAFF DFFE`).
  - `format(COMPACT, AQUA)` equals the same `separator` golden.
- **`formatMessageBody_inlineMarker_withNoTextColour_keepsThePlainPrefixStyle`**: With an empty text style, the separator literal has only the no-shadow and `chat/prefix` font fields, with no colour.
  - `format(SEP, Style.EMPTY)` equals the golden with one literal U+E003 whose style is `shadowColor=0` and `font=Resource[id=minecraft:chat/prefix]`, with no colour.
- **`formatMessageBody_url_isLinked`**: `https://wynnvets.org/a` renders as one literal with the text colour and an open-URL click event for the same address.
  - `format("https://wynnvets.org/a", AQUA)` equals the golden with one literal `https://wynnvets.org/a`, style `color=aqua` and click event `OpenUrl[uri=https://wynnvets.org/a]`.
- **`formatMessageBody_bareHost_isShownAsTypedAndLinkedOverHttps`**: `wynnvets.org/a` is displayed as typed but its click event opens `https://wynnvets.org/a`.
  - `format("wynnvets.org/a", AQUA)` equals the golden with one literal `wynnvets.org/a` (shown as typed), style `color=aqua` and click event `OpenUrl[uri=https://wynnvets.org/a]` (scheme added).
- **`formatMessageBody_upperCaseScheme_isLinkedAsTyped`**: `HTTPS://WYNNVETS.ORG/A` is linked with its upper-case form as the click target.
  - `format("HTTPS://WYNNVETS.ORG/A", AQUA)` equals the golden with one literal `HTTPS://WYNNVETS.ORG/A`, style `color=aqua` and click event `OpenUrl[uri=HTTPS://WYNNVETS.ORG/A]` (case kept).
- **`formatMessageBody_urlThatIsNotAValidUri_staysUnlinked`**: `https://wynnvets.org/%zz` renders as text in the text colour with no click event.
  - `format("https://wynnvets.org/%zz", AQUA)` equals the golden with one literal `https://wynnvets.org/%zz` whose style is only `color=aqua`, with no click event.
- **`formatMessageBody_markerBetweenUrls_splitsThem`**: Two URLs joined by the separator marker become three literals: link, separator, link.
  - `format("https://wynnvets.org/" + SEP + "https://wynnvets.org/b/", AQUA)` equals a golden with three literals in order: `https://wynnvets.org/` (`color=aqua`, click event `OpenUrl[uri=https://wynnvets.org/]`); U+E003 (`color=aqua`, `shadowColor=0`, `font=Resource[id=minecraft:chat/prefix]`); `https://wynnvets.org/b/` (`color=aqua`, click event `OpenUrl[uri=https://wynnvets.org/b/]`).
- **`formatMessageBody_leadingContinuations_areStripped`**: A body of a newline, compact marker, space, compact marker, space and a URL renders as the linked URL alone.
  - `format("\n" + COMPACT + " " + COMPACT + " https://wynnvets.org/", AQUA)` equals the golden with one literal `https://wynnvets.org/`, style `color=aqua` and click event `OpenUrl[uri=https://wynnvets.org/]`: the leading newline, both markers and their spaces are gone.
- **`isWrapStructure_newlineOrWholeMarker_isTrue`**: A lone newline, the full marker, the compact marker and the separator each count as wrap structure.
  - `MessageBodyFormatter.isWrapStructure("\n")` is true.
  - `MessageBodyFormatter.isWrapStructure(FULL)` is true.
  - `MessageBodyFormatter.isWrapStructure(COMPACT)` is true.
  - `MessageBodyFormatter.isWrapStructure(SEP)` is true.
- **`isWrapStructure_anythingElse_isFalse`**: The empty string, `x`, a space, two newlines, a marker plus a space, two separators and half a marker are not wrap structure.
  - For each `text` in `""`, `"x"`, `" "`, `"\n\n"`, `FULL + " "`, `SEP + SEP`, `"U+CFFFC"` (UTF-16 `DAFF DFFC`, the first part of a marker only): `MessageBodyFormatter.isWrapStructure(text)` is false. The message is the escaped text.
- **`stripServerContinuations_withNoContinuation_isUnchanged`**: Text with no newline-plus-marker, including a separator between spaces, comes back unchanged.
  - `strip("one two")`, which is `MessageBodyFormatter.stripServerContinuations`, equals `"one two"`.
  - `strip("one " + SEP + " two")` equals `"one " + SEP + " two"` (a separator not after a newline is left).
- **`stripServerContinuations_continuation_joinsTheLinesWithOneSpace`**: A newline followed by a compact marker between two words becomes a single space, whether or not a space followed the marker.
  - `strip("one\n" + COMPACT + " two")` equals `"one two"`.
  - `strip("one\n" + COMPACT + "two")` equals `"one two"` (a space is added when the marker has none after it).
- **`stripServerContinuations_continuationAtEitherEnd_addsNoSpace`**: A continuation at the start or end of the text is removed without leaving a space.
  - `strip("\n" + COMPACT + " two")` equals `"two"` (no leading space).
  - `strip("one\n" + COMPACT)` equals `"one"` (no trailing space).
- **`stripServerContinuations_newlineWithoutAMarker_isKept`**: A bare newline stays, and with two newlines before a marker only the last newline plus marker is stripped, leaving one newline.
  - `strip("one\ntwo")` equals `"one\ntwo"`.
  - `strip("one\n\n" + COMPACT + " two")` equals `"one\n two"`: the first newline stays, the second newline and marker are joined with one space.
- **`stripServerContinuations_markersRightAfterAContinuation_goWithIt`**: Further markers directly after a continuation, with or without spaces between them, are removed too.
  - `strip("one\n" + COMPACT + " " + COMPACT + " two")` equals `"one two"`.
  - `strip("one\n" + COMPACT + COMPACT + "two")` equals `"one two"`.
- **`stripServerContinuations_separatorOrFullMarkerAfterANewline_isAContinuationToo`**: A newline followed by the separator or by the full marker is stripped the same way as the compact marker.
  - `strip("one\n" + SEP + " two")` equals `"one two"`.
  - `strip("one\n" + FULL + " two")` equals `"one two"`.
- **`stripServerContinuations_markerAfterTextOnAJoinedLine_isLeft`**: A separator that follows ordinary text on the joined line is kept in the output.
  - `strip("one\n" + COMPACT + " two " + SEP + " three")` equals `"one two " + SEP + " three"`: the separator after text on the joined line is kept.
- **`stripServerContinuations_neverLeavesANewlineBeforeAMarker`**: Over every string of up to six pieces drawn from newline, space, letter, whole markers and marker fragments, no output newline is directly followed by a whole marker, and exactly 3,257,437 inputs are enumerated.
  - Fails if reached: inside the nested loops, for each output `out` of `strip(in)`, it is reached when a `"\n"` in `out` is directly followed by `FULL`, `COMPACT` or `SEP`. The inputs are every concatenation of 0 to 6 pieces drawn from twelve: `"\n"`, `" "`, `"x"`, `FULL`, `COMPACT`, `SEP`, U+CFFFC (`DAFF DFFC`), U+E001, U+D0006 (`DB00 DC06`), U+E006, the lone high surrogate U+DAFF, and the tail of `FULL` (`DAFF DFFF E002 DAFF DFFE`). The message is the escaped input and output.
  - The count `strings` of inputs tried equals `3_257_437`, the number of such concatenations (12^0 + 12^1 + ... + 12^6).

### `NickResolverTest`

Test: [`NickResolverTest.java`](../src/test/java/org/wynnvets/chat/NickResolverTest.java) · Subject: [`NickResolver`](../src/client/java/org/wynnvets/chat/NickResolver.java)

`NickResolver` flattens a chat `Component` tree into leaf parts with resolved styles and reads a player's real username from the hover text `"<nick>'s real name is <username>"`. The suite pins the style-merge rule of `flattenComponent` (a child's fields win and an ancestor fills gaps, for colour, hover and font), the username pattern (found anywhere, case-insensitive, flexible whitespace, at most 16 name characters), and the walk order (parent before child, siblings left to right). Several cases are written so they fail if the merge direction is inverted: the ancestors in them have a non-empty style or a decoy hover, since `Style.EMPTY.applyTo` short-circuits and would hide a wrong direction. The font tests matter because the pill-fragment selection compares each resolved style's font against `banner/pill`.

- **`realNameSpanStyleOrFallback_childColorOverridesParent`**: The style of the real-name hover span keeps its dark-aqua colour (over the aqua parent), its italic and its hover event.
  - `resolved.getColor()` is not null, where `resolved` is `NickResolver.realNameSpanStyleOrFallback(root, FALLBACK)`; `root` is a literal `"badge "` styled aqua with a child literal `Nickname` styled dark aqua, italic, with a hover `ShowText("Nickname's real name is RealUser")`; `FALLBACK` is a white style.
  - `resolved.getColor().getValue()` equals the dark-aqua colour value (`ChatFormatting.DARK_AQUA.getColor().intValue()`), so the child's colour beats the parent's aqua.
  - `resolved.isItalic()` is true.
  - `resolved.getHoverEvent()` is not null.
- **`realNameSpanStyleOrFallback_fallsBackWhenNoHover`**: With no hover-carrying span, the caller-supplied fallback style is returned unchanged.
  - `resolved` = `NickResolver.realNameSpanStyleOrFallback(root, FALLBACK)` equals `FALLBACK`, where `root` has an aqua `"badge "` and a dark-aqua italic `Nickname` child with no hover event.
- **`realUsernameOrFallback_findsHoverUsername`**: A nick child with a real-name hover under an aqua root resolves to `RealUser`.
  - `NickResolver.realUsernameOrFallback(root, "fallback-user")` equals `"RealUser"` (`REAL_NAME`), where `root` is an aqua `"badge "` with a `Nickname` child (dark aqua, italic) carrying the real-name hover for `RealUser`.
- **`realUsernameOrFallback_returnsFallbackWhenNoHover`**: A tree without any hover returns the fallback string.
  - `NickResolver.realUsernameOrFallback(root, "fallback-user")` equals `"fallback-user"`, where `root` has a `Nickname` child with no hover event.
- **`realUsernameFromHover_matchesCaseInsensitively`**: Hover text `Nickname's Real Name Is RealUser` returns `RealUser`.
  - `NickResolver.realUsernameFromHover(hover)` equals `"RealUser"`, where `hover` is a `ShowText` of `"Nickname's Real Name Is RealUser"`.
- **`realUsernameFromHover_returnsNullForNonMatchingText`**: Hover text `just a hover` returns null.
  - `NickResolver.realUsernameFromHover(hover)` is null, where `hover` is a `ShowText` of `"just a hover"`.
- **`flattenComponent_childFieldsOverrideParent`**: A root and a nick child flatten to two parts: the root part is aqua, and the child keeps dark aqua, italic and its own shadow colour.
  - `parts.size()` equals 2, where `parts` is filled by `NickResolver.flattenComponent(root, root.getStyle(), parts)` for an aqua `"badge "` root with a `Nickname` child styled dark aqua, italic, shadow colour 1.
  - `badge.text()` equals `"badge "`, where `badge` is `parts.get(0)`.
  - `badge.style().getColor().getValue()` equals the aqua colour value (`ChatFormatting.AQUA.getColor().intValue()`).
  - `nick.text()` equals `"Nickname"`, where `nick` is `parts.get(1)`.
  - `nick.style().getColor().getValue()` equals the dark-aqua colour value, so the child's colour beats the parent's aqua.
  - `nick.style().isItalic()` is true.
  - `nick.style().getShadowColor()` is not null (the child's shadow colour survives).
- **`flattenComponent_siblingWithoutColorInheritsParent`**: An empty-text root produces no part, and a child with only italic inherits the root's aqua colour. This holds under either merge direction and only fails if inheritance is dropped.
  - `parts.size()` equals 1, where `parts` comes from flattening an aqua-styled empty literal root with one child `child-no-color` styled italic only (the empty root adds no part).
  - `child.style().getColor().getValue()` equals the aqua colour value (`child` is `parts.get(0)`): the child inherits aqua from the root.
  - `child.style().isItalic()` is true.
- **`realUsernameOrFallback_ancestorHoverDoesNotMaskTheNameSpan`**: A root with a decoy hover does not hide the child's real-name hover, so `RealUser` is returned and not the nickname fallback.
  - `NickResolver.realUsernameOrFallback(root, NICK)` equals `"RealUser"`, where `NICK` is `"Nickname"` and `root` is a `"badge "` literal styled aqua with a decoy hover (`ShowText("Click to view profile")`) and a `Nickname` child styled dark aqua with the real-name hover for `RealUser`: the root's decoy hover does not replace the child's.
- **`realUsernameOrFallback_aNestedAncestorHoverDoesNotMaskTheLeafEither`**: The same holds one level deeper: a mid-level span with a decoy hover does not mask the leaf's real-name hover.
  - `NickResolver.realUsernameOrFallback(root, NICK)` equals `"RealUser"`, where `root` is an empty literal (empty style) holding `"mid "` (gold, decoy hover) holding the leaf `Nickname` with the real-name hover for `RealUser`.
- **`realUsernameOrFallback_matchesAnywhereInTheHoverAndFoldsCase`**: Hover text with surrounding words and extra spaces between `Real   Name  Is` still yields the username.
  - `NickResolver.realUsernameOrFallback(root, NICK)` equals `"RealUser"`, where the nick child's hover text is `"prefix Real   Name  Is  RealUser suffix"` (extra spaces between the words, text before and after).
- **`realUsernameOrFallback_capturesAtMostSixteenNameCharacters`**: A 17-character name after `real name is` is truncated to its first 16 characters, not rejected.
  - `NickResolver.realUsernameOrFallback(root, NICK)` equals the first 16 characters of `seventeen` (`"Abcdefghijklmnop"`), where `seventeen` is `"Abcdefghijklmnopq"` and the hover text is `"real name is "` + `seventeen`.
- **`realUsernameOrFallback_returnsTheFirstMatchingSpanInWalkOrder`**: When both the root and a child carry real-name hovers, the root's (`FirstOne`) wins.
  - `NickResolver.realUsernameOrFallback(root, NICK)` equals `"FirstOne"`, where the root `"badge "` has a hover `"real name is FirstOne"` and its `Nickname` child has the real-name hover for `RealUser`: the root is read first.
- **`realUsernameOrFallback_emptyRootTextContributesNoPartButStillPassesItsStyleDown`**: An empty-text root with a real-name hover yields no part itself, but its hover reaches the child, so `Ghost` is returned.
  - `NickResolver.realUsernameOrFallback(root, NICK)` equals `"Ghost"`, where the root is an empty literal carrying a hover `"real name is Ghost"` and its `Nickname` child has `Style.EMPTY`: the child inherits the root's hover.
- **`flattenComponent_childWithoutAFontInheritsTheParentFont`**: A child with only a colour under a root whose font is `banner/pill` resolves to the `banner/pill` font.
  - `parts.size()` equals 1, where `parts` comes from flattening an empty literal root with font `PILL_FONT` (`banner/pill`) and one child `letters` styled black with no font.
  - `parts.get(0).style().getFont()` equals `PILL_FONT`.
- **`flattenComponent_childFontOverridesTheParentFont`**: A child naming `chat/prefix` keeps that font over the root's `banner/pill`.
  - `parts.size()` equals 1, where `parts` comes from flattening an empty literal root with font `PILL_FONT` and one child `badge` with font `PREFIX_FONT` (`chat/prefix`).
  - `parts.get(0).style().getFont()` equals `PREFIX_FONT`.
- **`flattenComponent_anUnfontedSpanCarriesMinecraftsDefaultFontNotNull`**: A span with no font resolves to a non-null font that is not `banner/pill`. This pins vanilla `Style.getFont()` behaviour, not the walk, as the test's comment says.
  - `parts.size()` equals 1, where `parts` comes from flattening an aqua empty literal root with one child `Nickname` styled italic, with no font anywhere.
  - `resolved`, which is `parts.get(0).style().getFont()`, is not null.
  - `resolved` does not equal `PILL_FONT`.

### `PillCodecTest`

Test: [`PillCodecTest.java`](../src/test/java/org/wynnvets/chat/PillCodecTest.java) · Subject: [`PillCodec`](../src/client/java/org/wynnvets/chat/PillCodec.java)

`PillCodec` encodes and decodes the private-use-area "pill" sequences that draw rank badges in Wynncraft guild chat and in vetsmod's own output: `encodeRemote` (a string frame), `encodeLocal` (a two-tone component), `decodeServerPill`, and the predicates `isCustomGlyph` and `isEncoded`. The suite pins the wire format, in particular that `isCustomGlyph` accepts supplementary unassigned code points while `isEncoded` deliberately accepts only private-use ones, and that decoding fails closed on any unrecognised glyph. All code points are restated numerically in the test, not read from the subject's fields, so a renumbering fails the tests. The class-level `AfterEach` restores the JVM default locale after the Turkish-locale cases, and the encoders build real `Component` and `Style` objects.

- **`isCustomGlyph_acceptsPrivateUseInEveryPlane`**: Private-use code points in the BMP (U+E000, U+F8FF) and both supplementary planes (U+F0000, U+100000) count as custom glyphs.
  - `PillCodec.isCustomGlyph(LOWER_ALPHABET_BASE)` is true; `LOWER_ALPHABET_BASE` is 0xE000 (BMP private use).
  - `PillCodec.isCustomGlyph(0xF8FF)` is true.
  - `PillCodec.isCustomGlyph(0xF0000)` is true.
  - `PillCodec.isCustomGlyph(0x100000)` is true.
- **`isCustomGlyph_acceptsSupplementaryUnassignedButNotBmpUnassigned`**: The terminator U+D0002, width marker U+CFF00 and kern marker U+CFFFF count, while unassigned BMP points U+0378 and U+0379 do not.
  - `PillCodec.isCustomGlyph(SERVER_PILL_CLOSE)` is true; `SERVER_PILL_CLOSE` is 0xD0002.
  - `PillCodec.isCustomGlyph(SERVER_WIDTH_MIN)` is true; `SERVER_WIDTH_MIN` is 0xCFF00.
  - `PillCodec.isCustomGlyph(SERVER_KERN_MARKER)` is true; `SERVER_KERN_MARKER` is 0xCFFFF.
  - `PillCodec.isCustomGlyph(0x0378)` is false (unassigned, inside the BMP).
  - `PillCodec.isCustomGlyph(0x0379)` is false.
- **`isCustomGlyph_rejectsOrdinaryText`**: Letters, a digit, a space, U+2064 (a format character) and an assigned emoji are not custom glyphs.
  - `PillCodec.isCustomGlyph('a')` is false.
  - `PillCodec.isCustomGlyph('Z')` is false.
  - `PillCodec.isCustomGlyph('7')` is false.
  - `PillCodec.isCustomGlyph(' ')` is false.
  - `PillCodec.isCustomGlyph(0x2064)` is false (a format character).
  - `PillCodec.isCustomGlyph(0x1F600)` is false (an assigned supplementary codepoint).
- **`isCustomGlyph_andIsEncodedDisagreeOnTheServerMarkers`**: For each of the three server markers, `isCustomGlyph` is true and `isEncoded` of the same character is false.
  - For each `marker` in `SERVER_PILL_CLOSE` (0xD0002), `SERVER_WIDTH_MIN` (0xCFF00), `SERVER_KERN_MARKER` (0xCFFFF): `PillCodec.isCustomGlyph(marker)` is true.
  - For the same three markers: `PillCodec.isEncoded(chars(marker))` is false, where `chars(marker)` is the one-codepoint string.
- **`isEncoded_nullEmptyAndPlainAscii`**: Null, the empty string, `VET` and `[Vetsmod]` are not encoded.
  - `PillCodec.isEncoded(null)` is false.
  - `PillCodec.isEncoded("")` is false.
  - `PillCodec.isEncoded("VET")` is false.
  - `PillCodec.isEncoded("[Vetsmod]")` is false.
- **`isEncoded_anyBmpPuaCodepointCounts`**: A BMP private-use character counts at any position in the string, including the lower-block start, upper-block start, frame opener and U+F8FF.
  - `PillCodec.isEncoded(chars(LOWER_ALPHABET_BASE))` is true (the single character U+E000).
  - `PillCodec.isEncoded(chars(UPPER_ALPHABET_BASE))` is true; `UPPER_ALPHABET_BASE` is 0xE040.
  - `PillCodec.isEncoded("x" + REMOTE_FRAME_OPEN + "y")` is true; `REMOTE_FRAME_OPEN` is U+E06B, in the middle of the string.
  - `PillCodec.isEncoded(chars(0xF8FF))` is true.
- **`isEncoded_supplementaryPuaPlanesAlsoCount`**: Private-use code points in planes 15 and 16 are recognised as encoded.
  - `PillCodec.isEncoded(chars(0xF0000))` is true.
  - `PillCodec.isEncoded(chars(0x100000))` is true.
- **`isEncoded_ignoresTheServerPillMarkersBecauseTheyAreUnassignedNotPua`**: The terminator, width marker and kern marker are each reported as not encoded.
  - `PillCodec.isEncoded(chars(SERVER_PILL_CLOSE))` is false (0xD0002).
  - `PillCodec.isEncoded(chars(SERVER_WIDTH_MIN))` is false (0xCFF00).
  - `PillCodec.isEncoded(chars(SERVER_KERN_MARKER))` is false (0xCFFFF).
- **`encodeRemote_nullYieldsEmptyStringNotNull`**: `encodeRemote(null)` returns the empty string.
  - `PillCodec.encodeRemote(null)` equals `""`.
- **`encodeRemote_framesTheUppercaseGlyphs`**: `encodeRemote("VET")` is the remote frame opener, the upper-block glyphs for V, E, T, and the frame closer.
  - `PillCodec.encodeRemote("VET")` equals `REMOTE_FRAME_OPEN + upperGlyphs("VET") + REMOTE_FRAME_CLOSE`: U+E06B, then the glyphs U+E055, U+E044, U+E053 (U+E040 plus the letter's offset from `A`), then `REMOTE_FRAME_CLOSE` U+E06C.
- **`encodeRemote_upperCasesTheLabel`**: `vet` and `VeT` encode to the same string as `VET`.
  - `PillCodec.encodeRemote("vet")` equals `PillCodec.encodeRemote("VET")`.
  - `PillCodec.encodeRemote("VeT")` equals `PillCodec.encodeRemote("VET")`.
- **`encodeRemote_keepsAnIUnderATurkishDefaultLocale`**: With the default locale set to Turkish, `vip` and `VIP` both encode to the frame with V, I, P glyphs, so the I is not lost to a dotted capital.
  - With `Locale.setDefault(TURKISH)` (language tag `tr`), `PillCodec.encodeRemote("vip")` equals `REMOTE_FRAME_OPEN + upperGlyphs("VIP") + REMOTE_FRAME_CLOSE`, so the `i` is kept.
  - With the same Turkish default, `PillCodec.encodeRemote("VIP")` equals the same string.
- **`encodeLocal_keepsAnIUnderATurkishDefaultLocaleToo`**: With a Turkish default locale, `encodeLocal("vip", style)` produces the local frame with segment and glyph for V, I and P.
  - With `Locale.setDefault(TURKISH)`, `PillCodec.encodeLocal("vip", FRAME_STYLE).getString()` equals `LOCAL_FRAME_OPEN` (U+E010 U+2064), then `LOCAL_FRAME_SEGMENT` (U+E00F U+E012) plus `upperGlyphs("V")`, `LOCAL_FRAME_SEGMENT` plus `upperGlyphs("I")`, `LOCAL_FRAME_SEGMENT` plus `upperGlyphs("P")`, then `LOCAL_FRAME_CLOSE` (U+E011). `FRAME_STYLE` is an aqua style.
- **`encodeRemote_dropsEverythingOutsideAtoZ`**: `A1-B ` encodes with only the A and B glyphs, and both the empty string and `123` give a bare remote frame.
  - `PillCodec.encodeRemote("A1-B ")` equals `REMOTE_FRAME_OPEN + upperGlyphs("AB") + REMOTE_FRAME_CLOSE` (the digit, hyphen and space are dropped).
  - `PillCodec.encodeRemote("")` equals `emptyRemotePill()`, which is `REMOTE_FRAME_OPEN + REMOTE_FRAME_CLOSE` (U+E06B U+E06C).
  - `PillCodec.encodeRemote("123")` equals `emptyRemotePill()`.
- **`encodeRemote_passesPreEncodedInputThroughUnchanged`**: An already-encoded remote pill is returned as the same instance, not rebuilt.
  - `PillCodec.encodeRemote(already)` is the same instance as `already`, where `already` is `REMOTE_FRAME_OPEN + upperGlyphs("VET") + REMOTE_FRAME_CLOSE`.
- **`encodeRemote_reEncodesInputCarryingOnlyServerPillMarkers`**: Input of only a width marker and terminator is not treated as encoded, and is rebuilt as a bare remote frame.
  - `PillCodec.encodeRemote(markers)` equals `emptyRemotePill()`, where `markers` is `chars(SERVER_WIDTH_MIN, SERVER_PILL_CLOSE)` (U+CFF00 U+D0002); the markers are not passed through.
- **`encodeLocal_oneFrameSegmentPerLetter`**: `encodeLocal("AB", style)` has the text frame open, segment, A glyph, segment, B glyph, close, and exactly six sibling components.
  - `pill.getString()`, where `pill` is `PillCodec.encodeLocal("AB", FRAME_STYLE)`, equals `LOCAL_FRAME_OPEN + LOCAL_FRAME_SEGMENT + upperGlyphs("A") + LOCAL_FRAME_SEGMENT + upperGlyphs("B") + LOCAL_FRAME_CLOSE`.
  - `pill.getSiblings().size()` equals 6: the open frame, a segment and a letter for each of two letters, and the close frame.
- **`encodeLocal_lettersAreBlackAndTheFrameTakesTheCallerStyle`**: For `A`, the frame sibling carries the caller's aqua colour and the letter sibling is black.
  - `frame.getColor().getValue()` equals the aqua colour value (`ChatFormatting.AQUA.getColor().intValue()`), where `frame` is the style of sibling 0 of `PillCodec.encodeLocal("A", FRAME_STYLE)`.
  - `letter.getColor().getValue()` equals the black colour value (`ChatFormatting.BLACK.getColor().intValue()`), where `letter` is the style of sibling 2 of the same pill.
- **`encodeLocal_nullAndUnrepresentableLabelsYieldABareFrame`**: A null label and `12 -` both produce only the frame opener and closer.
  - `PillCodec.encodeLocal(null, FRAME_STYLE).getString()` equals `LOCAL_FRAME_OPEN + LOCAL_FRAME_CLOSE`.
  - `PillCodec.encodeLocal("12 -", FRAME_STYLE).getString()` equals `LOCAL_FRAME_OPEN + LOCAL_FRAME_CLOSE`.
- **`encodeLocal_hasNoPreEncodedPassthrough`**: Feeding an already-encoded remote pill to `encodeLocal` drops every character (none is A-Z) and yields a bare local frame.
  - `PillCodec.encodeLocal(already, FRAME_STYLE).getString()` equals `LOCAL_FRAME_OPEN + LOCAL_FRAME_CLOSE`, where `already` is `REMOTE_FRAME_OPEN + upperGlyphs("VET") + REMOTE_FRAME_CLOSE`: every character is dropped.
- **`decodeServerPill_nullAndNoOpener`**: Null, the empty string and plain text without the pill opener decode to null.
  - `PillCodec.decodeServerPill(null)` is null.
  - `PillCodec.decodeServerPill("")` is null.
  - `PillCodec.decodeServerPill("plain guild chat")` is null.
- **`decodeServerPill_readsTheLowercaseLabel`**: An opener, a width marker, lower-block glyphs for `owner` and a terminator decode to `owner`.
  - `PillCodec.decodeServerPill(serverPill(lowerGlyphs("owner"), 40))` equals `"owner"`; `serverPill` builds opener U+E062, width marker `chars(0xD0000 - 40)`, the glyphs, then terminator U+D0002.
- **`decodeServerPill_widthMarkerIsOptional`**: A pill with no width marker still decodes (`chief`).
  - `PillCodec.decodeServerPill(noMarker)` equals `"chief"`, where `noMarker` is opener U+E062, `lowerGlyphs("chief")`, terminator U+D0002 (no width marker).
- **`decodeServerPill_foldsUppercaseAndAcceptsDigits`**: Upper-block glyphs for `OWNER` decode to lower-case `owner`, and digit-block glyphs decode to `012`.
  - `PillCodec.decodeServerPill(serverPill(upperGlyphs("OWNER"), 40))` equals `"owner"` (uppercase glyphs fold to lowercase).
  - `PillCodec.decodeServerPill(serverPill(digits, 12))` equals `"012"`, where `digits` is U+E030, U+E031, U+E032.
- **`decodeServerPill_scansForTheOpenerPastPrecedingGlyphRuns`**: A hex-id run (with kern marker and letters from the upper and digit blocks) before the pill is ignored, and the label `strategist` is read after the opener.
  - `PillCodec.decodeServerPill("guild " + idRun + serverPill(lowerGlyphs("strategist"), 55))` equals `"strategist"`, where `idRun` is U+E060, the kern marker U+CFFFF, `upperGlyphs("AF")` and U+E037 (digit glyph 7).
- **`decodeServerPill_unterminatedOrEmptyLabelIsNull`**: A pill with no terminator, and one whose terminator follows an empty label, both decode to null.
  - `PillCodec.decodeServerPill(SERVER_PILL_OPEN + lowerGlyphs("owner"))` is null (no terminator); `SERVER_PILL_OPEN` is U+E062.
  - `PillCodec.decodeServerPill(serverPill("", 40))` is null (terminator reached with no label glyphs).
- **`decodeServerPill_unrecognisedGlyphAbortsTheWholeDecode`**: A frame-opener glyph in the middle of the label makes the whole decode return null, not skip the character.
  - `PillCodec.decodeServerPill(withStray)` is null, where `withStray` is `serverPill(lowerGlyphs("own") + REMOTE_FRAME_OPEN + lowerGlyphs("er"), 40)`: U+E06B sits inside the label and is not a letter or digit glyph.
- **`decodeServerPill_widthMarkerIsConsumedAtMostOnce`**: A second width-range code point after the first makes the decode return null.
  - `PillCodec.decodeServerPill(twice)` is null, where `twice` is opener U+E062, two width markers (`0xD0000 - 40` and `0xD0000 - 41`), `lowerGlyphs("owner")`, terminator U+D0002.
- **`decodeServerPill_widthMarkerRangeBoundaries`**: U+CFF00 is accepted as a width marker, while U+CFEFF (one below) and U+D0000 (the base, exclusive) make the decode return null.
  - `PillCodec.decodeServerPill(pillWithMarker(SERVER_WIDTH_MIN))` equals `"owner"`; `pillWithMarker(m)` is opener U+E062, codepoint `m`, `lowerGlyphs("owner")`, terminator U+D0002; `SERVER_WIDTH_MIN` is 0xCFF00.
  - `PillCodec.decodeServerPill(pillWithMarker(SERVER_WIDTH_MIN - 1))` (0xCFEFF) is null.
  - `PillCodec.decodeServerPill(pillWithMarker(SERVER_WIDTH_BASE))` (0xD0000) is null.
- **`decodeServerPill_stopsAtTheFirstTerminator`**: Two consecutive pills decode to the first label only (`owner`).
  - `PillCodec.decodeServerPill(two)` equals `"owner"`, where `two` is `serverPill(lowerGlyphs("owner"), 40)` followed by `serverPill(lowerGlyphs("chief"), 40)`.
- **`encodeRemote_outputIsNotAServerPillAndDoesNotDecode`**: `decodeServerPill(encodeRemote("VET"))` is null, since the two formats use different frames.
  - `PillCodec.decodeServerPill(PillCodec.encodeRemote("VET"))` is null.
- **`encodeRemote_recognisesItsOwnOutputAsEncoded`**: `isEncoded` is true for the output of `encodeRemote("VET")`.
  - `PillCodec.isEncoded(PillCodec.encodeRemote("VET"))` is true.

### `PrependTest`

Test: [`PrependTest.java`](../src/test/java/org/wynnvets/chat/PrependTest.java) · Subject: [`Prepend`](../src/client/java/org/wynnvets/chat/Prepend.java)

`Prepend` is the enum of chat badges. `get()` returns the full badge, or a compact block indicator when the same badge was drawn within the last 18 rendered lines, and `blockIndicatorStyle()` exposes the indicator's style without touching that state. The suite pins the dedup behaviour of `get()`, that `blockIndicatorStyle()` is read-only and returns the indicator's (not the full badge's) style, the 18-line threshold, and that `STAFF_CHANNEL` (GUILD's glyphs in red) dedups separately from `GUILD`. The dedup state is static, so `resetDedup()` runs before and after every test.

- **`blockIndicatorStyle_leavesTheNextGetAFullBadge`**: Calling `GUILD.blockIndicatorStyle()` first does not count as a drawn badge, so the next `GUILD.get()` is still the full badge text.
  - After `Prepend.GUILD.blockIndicatorStyle()`, `Prepend.GUILD.get().getString()` equals `GUILD_FULL`, where `GUILD_FULL` is U+CFFFC, U+E006, U+CFFFF, U+E002, U+CFFFE, U+0020.
- **`blockIndicatorStyle_isTheStyleOfTheIndicatorGetReturns`**: After one `GUILD.get()`, the second `get()` returns the compact indicator text, and its style equals `blockIndicatorStyle()`.
  - After two `Prepend.GUILD.get()` calls, the second result `indicator` has `getString()` equal to `GUILD_INDICATOR`, where `GUILD_INDICATOR` is U+CFFFC, U+E001, U+D0006, U+0020.
  - `indicator.getStyle()` equals `Prepend.GUILD.blockIndicatorStyle()`.
- **`blockIndicatorStyle_isTheIndicatorsStyleNotTheFullBadges`**: For `DEFAULT`, whose full badge root has no font but whose indicator has one, the indicator returned by the second `get()` has the same style as `blockIndicatorStyle()`.
  - After two `Prepend.DEFAULT.get()` calls, the second result `indicator` has `getStyle()` equal to `Prepend.DEFAULT.blockIndicatorStyle()`.
- **`get_returnsTheFullBadgeAgainAfter18Lines`**: After one `get()`, 17 added rendered lines still give the indicator, and one more line makes the next `get()` the full badge again.
  - After one `Prepend.GUILD.get()` and `Prepend.addRenderedLines(17)`, the next `Prepend.GUILD.get().getString()` equals `GUILD_INDICATOR`.
  - After a further `Prepend.addRenderedLines(1)` (18 lines in total), the next `Prepend.GUILD.get().getString()` equals `GUILD_FULL`.
- **`staffChannel_afterAGuildLine_getsItsFullRedBadge`**: A `STAFF_CHANNEL.get()` right after a `GUILD.get()` returns GUILD's full badge text, with GUILD's badge style recoloured red.
  - After one `Prepend.GUILD.get()`, `Prepend.STAFF_CHANNEL.get()` (`staff`) has `getString()` equal to `GUILD_FULL`.
  - `staff.getStyle()` equals `guildBadge.withColor(ChatFormatting.RED)`, where `guildBadge` is the style of that earlier `GUILD.get()` result.
- **`staffChannel_dedupsAgainstItself_andAGuildLineAfterItIsFull`**: A second consecutive `STAFF_CHANNEL.get()` returns the indicator in red, and a `GUILD.get()` after it returns the full badge in aqua.
  - After one earlier `Prepend.STAFF_CHANNEL.get()`, the next result `second` has `getString()` equal to `GUILD_INDICATOR`.
  - `second.getStyle()` equals `Prepend.GUILD.blockIndicatorStyle().withColor(ChatFormatting.RED)`.
  - The next `Prepend.GUILD.get()` (`guild`) has `getString()` equal to `GUILD_FULL`.
  - `guild.getStyle().getColor()` equals `TextColor.fromLegacyFormat(ChatFormatting.AQUA)`.

### `RankDisplayMapTest`

Test: [`RankDisplayMapTest.java`](../src/test/java/org/wynnvets/chat/RankDisplayMapTest.java) · Subject: [`RankDisplayMap`](../src/client/java/org/wynnvets/chat/RankDisplayMap.java)

`RankDisplayMap.displayFor` maps a raw Wynncraft guild rank to the label a pill shows. The queued Returner's local echo, `EncourageUpdateRewriter`'s sender pill and `OutboundDisplayHandler`'s fallback for a relayed line with no `pill_display` go through it, while the wire keeps the raw names. The suite pins the table, its case- and space-insensitivity, and that an unknown rank or null passes through.

- **`displayFor_collapsesTheStaffRanksToSteward`**: Strategist, Chief and Owner all read Steward.
  - `displayFor` returns `Steward` for `Strategist`, `Chief` and `Owner`.
- **`displayFor_collapsesCaptainAndRecruiterToReturnerAndKeepsRecruit`**: Captain and Recruiter read Returner; Recruit stays Recruit.
  - `displayFor` returns `Returner` for `Captain` and `Recruiter`, and `Recruit` for `Recruit`.
- **`displayFor_ignoresCaseAndSurroundingSpace`**: The poller's lowercase names, an all-caps name and a padded one map the same way.
  - `displayFor` returns `Steward` for `strategist` and `OWNER`, and `Returner` for ` captain `.
- **`displayFor_passesAnUnknownRankAndNullThrough`**: A rank outside the table, the empty string and null come back unchanged.
  - `displayFor("Moderator")` is `Moderator`, `displayFor("")` is the empty string, and `displayFor(null)` is null.

## `org.wynnvets.chat.dispatcher`

### `FindDispatcherTest`

Test: [`FindDispatcherTest.java`](../src/test/java/org/wynnvets/chat/dispatcher/FindDispatcherTest.java) · Subject: [`FindDispatcher`](../src/client/java/org/wynnvets/chat/dispatcher/FindDispatcher.java)

`FindDispatcher` batches `/find` lookups for guild members and matches the server's reply lines. This suite covers its two package-private pure leaves: the reply parser `parseFindReply` (cases at the end) and the response sanitiser, `stripFormattingAndPua`, which removes section-sign colour codes and invisible or private-use characters before the reply is compared as prose. It pins that the strip is deliberately wider than `PillCodec.isCustomGlyph`: it also removes surrogates, format characters, and unassigned code points inside the BMP. Fixtures are single-character constants built from code points (a PUA pill glyph, a BMP-unassigned character, a supplementary unassigned character, a zero-width space, a lone surrogate).

- **`strip_nullYieldsEmptyStringNotNull`**: `null` input returns `""`, not `null`.
  - `FindDispatcher.stripFormattingAndPua(null)` equals `""`.
- **`strip_leavesOrdinaryProseAlone`**: A plain sentence such as `"Metrafish is currently on server WC12"` comes back unchanged.
  - `stripFormattingAndPua("Metrafish is currently on server WC12")` equals `"Metrafish is currently on server WC12"`.
- **`strip_removesSectionSignCodesAsAPair`**: `"§agreen §rtext"` becomes `"green text"`: each section sign and the character after it are dropped, and the surrounding text survives.
  - `stripFormattingAndPua(U+00A7 + "agreen " + U+00A7 + "rtext")` equals `"green text"`.
- **`strip_keepsATrailingSectionSignBecauseThereIsNoCodeAfterIt`**: A trailing `§` with nothing after it is kept, because the pair-skip needs a following character and U+00A7 is not in a stripped category.
  - `stripFormattingAndPua("text" + U+00A7)` equals `"text" + U+00A7`, the trailing U+00A7 kept.
- **`strip_removesPrivateUseAndSupplementaryUnassigned`**: A PUA glyph and a supplementary-plane unassigned code point between `a` and `b` are both removed, giving `"ab"`; the canonical predicate would strip these too.
  - `stripFormattingAndPua("a" + U+E062 + "b")` equals `"ab"`, where U+E062 is a private-use rank glyph.
  - `stripFormattingAndPua("a" + U+D0002 + "b")` equals `"ab"`, where U+D0002 is an unassigned supplementary code point.
- **`strip_alsoRemovesBmpUnassignedFormatAndSurrogates`**: A BMP-unassigned character, a zero-width space and a lone surrogate each placed between `a` and `b` is removed, giving `"ab"`; these are the three cases the canonical predicate keeps, so this pins the wider strip.
  - `stripFormattingAndPua("a" + U+0378 + "b")` equals `"ab"`, where U+0378 is unassigned and in the BMP.
  - `stripFormattingAndPua("a" + U+200B + "b")` equals `"ab"`, where U+200B is a FORMAT character.
  - `stripFormattingAndPua("a" + U+D800 + "b")` equals `"ab"`, where U+D800 is an unpaired surrogate.
- **`reply_readsTheServerAfterTheGlyphRunsSpace`**: A reply whose glyph run leaves a leading space after sanitising still yields its server, lower-cased.
  - `parseFindReply(stripFormattingAndPua(U+E062 + " Metrafish is currently on server WC12"), "metrafish")` equals `"wc12"`.
- **`reply_readsPrivateAndOffline`**: The private-server and offline replies map to `PRIVATE_SERVER` and `""`.
  - `parseFindReply("Holidaze is currently on a private server.", "holidaze")` equals `FindDispatcher.PRIVATE_SERVER`.
  - `parseFindReply("Wenweia is not currently online", "wenweia")` equals `""`.
- **`reply_aLongerNameEndingWithTheTargetIsSomeoneElses`**: A reply about Metrafish is not a reply about `fish`.
  - `parseFindReply("Metrafish is currently on server WC12", "fish")` and `parseFindReply("Metrafish is not currently online", "fish")` are null.
- **`reply_aLineThatOnlyMentionsTheTargetIsNotAReply`**: A line that names the target mid-sentence is not a reply.
  - `parseFindReply("Nazzae says Metrafish is currently on server WC12", "metrafish")` is null.

### `GuildChatDispatcherTest`

Test: [`GuildChatDispatcherTest.java`](../src/test/java/org/wynnvets/chat/dispatcher/GuildChatDispatcherTest.java) · Subject: [`GuildChatDispatcher`](../src/client/java/org/wynnvets/chat/dispatcher/GuildChatDispatcher.java)

`GuildChatDispatcher.argsAfter` is the word match the staff intercepts (`/v`, `/a`, `/encourage`, `/wv check`, `/caution`, `/caution-go`, `/warn`, `/eject`) use, so a bare command, which typed chat delivers trimmed, still reaches its handler's usage line. The suite pins the bare word, the rest after one space, case-insensitivity, and that a longer word or a different command does not match. Only the pure helper runs; `intercept` itself reaches Wynntils' connection state.

- **`theBareWordHasNoArguments`**: The bare word gives the empty string.
  - `argsAfter("v", "v")` equals `""`; `argsAfter("wv check", "wv check")` equals `""`.
- **`theRestFollowsOneSpace`**: The arguments are everything after the word and one space.
  - `argsAfter("v hello there", "v")` equals `"hello there"`; `argsAfter("wv check Metrafish", "wv check")` equals `"Metrafish"`.
- **`matchingIgnoresCase`**: The word matches in any case.
  - `argsAfter("WARN Holidaze late", "warn")` equals `"Holidaze late"`; `argsAfter("Caution", "caution")` equals `""`.
- **`aLongerWordIsAnotherCommand`**: A word followed by anything but a space, or a different command, is null.
  - `argsAfter("caution-go Nazzae", "caution")`, `argsAfter("vote", "v")` and `argsAfter("g hello", "v")` are all null.

### `MessageFanoutDispatcherTest`

Test: [`MessageFanoutDispatcherTest.java`](../src/test/java/org/wynnvets/chat/dispatcher/MessageFanoutDispatcherTest.java) · Subject: [`MessageFanoutDispatcher`](../src/client/java/org/wynnvets/chat/dispatcher/MessageFanoutDispatcher.java)

`MessageFanoutDispatcher` sends the staff `/v` broadcast as one `/msg` per recipient and suppresses the server feedback for each. This suite covers the package-private string helpers of its echo matcher, which decide whether an incoming chat line is the echo of an outbound `/msg` payload. It pins the normalisation, tokenising and matching rules, including two deliberate false-positive surfaces: the recipient check is a substring test, and `*` in the message acts as a wildcard. Fixtures are plain text with PUA glyphs kept, using the U+E003 private-message separator; `shouldSuppressFeedback` is not covered.

- **`isOutboundRecipientTarget_matchesTheSegmentBetweenGlyphAndColon`**: `"you <SEP> holidaze: hi"` is accepted as an outbound message to `holidaze`.
  - `isOutboundRecipientTarget("you " + SEP + " holidaze: hi", "holidaze")` is true, where `SEP` is the one-char string U+E003.
- **`isOutboundRecipientTarget_requiresBothTheGlyphAndAColonAfterIt`**: Rejects a line with no separator glyph, a line with no colon after the glyph, and a line whose colon comes before the glyph.
  - `isOutboundRecipientTarget("you holidaze: hi", "holidaze")` is false (no `SEP` glyph).
  - `isOutboundRecipientTarget("you " + SEP + " holidaze hi", "holidaze")` is false (no colon after the glyph).
  - `isOutboundRecipientTarget("a: b " + SEP + " holidaze", "holidaze")` is false (the only colon comes before the glyph).
- **`isOutboundRecipientTarget_recipientIsASubstringTestSoPrefixesFalseMatch`**: A message to `holidazed` matches recipient `holidaze`, but a message to `holidaze` does not match recipient `holidazed`; containment runs one way.
  - `isOutboundRecipientTarget("you " + SEP + " holidazed: hi", "holidaze")` is true (segment `holidazed` contains `holidaze`).
  - `isOutboundRecipientTarget("you " + SEP + " holidaze: hi", "holidazed")` is false (segment `holidaze` does not contain `holidazed`).
- **`isOutboundRecipientTarget_looksOnlyAtTheRecipientSegment`**: A name that appears only in the message body (`"metrafish: tell holidaze hi"`) is not a match for `holidaze`.
  - `isOutboundRecipientTarget("you " + SEP + " metrafish: tell holidaze hi", "holidaze")` is false (`holidaze` appears only after the colon).
- **`isOfflineRecipientMessage_bothServerWordings`**: `"holidaze is not online"` and `"holidaze is not currently online"` are both recognised for `holidaze`.
  - `isOfflineRecipientMessage("holidaze is not online", "holidaze")` is true.
  - `isOfflineRecipientMessage("holidaze is not currently online", "holidaze")` is true.
- **`isOfflineRecipientMessage_isAnchoredOnTheNameNotJustThePhrase`**: `"metrafish is not online"` is not an offline message for `holidaze`; the name must be adjacent to the phrase.
  - `isOfflineRecipientMessage("metrafish is not online", "holidaze")` is false.
- **`isOfflineGuidanceMessage_bothFragments`**: Each of the two server guidance sentences (exact names and prediction; separate server) is recognised, and `"holidaze is not online"` is not.
  - `isOfflineGuidanceMessage("be sure to use exact names, prediction does not work if they are offline")` is true.
  - `isOfflineGuidanceMessage("or the user is on a separate server")` is true.
  - `isOfflineGuidanceMessage("holidaze is not online")` is false.
- **`normalize_stripsWhitespaceAndFoldsCase`**: `"Hello World"` normalises to `"helloworld"` and `"A\t\nB"` to `"ab"`.
  - `normalizeForEchoComparison("Hello World")` equals `"helloworld"`.
  - `normalizeForEchoComparison("A\t\nB")` equals `"ab"`.
- **`normalize_stripsPuaAndTheOtherInvisibleCategories`**: A private-use, control, format, lone-surrogate and supplementary-unassigned character between `a` and `b` are each removed, giving `"ab"`.
  - `normalizeForEchoComparison("a" + U+E003 + "b")` equals `"ab"` (private-use char stripped).
  - `normalizeForEchoComparison("a" + U+0007 + "b")` equals `"ab"` (control char stripped).
  - `normalizeForEchoComparison("a" + U+200B + "b")` equals `"ab"` (format char stripped).
  - `normalizeForEchoComparison("a" + U+D800 + "b")` equals `"ab"` (unpaired surrogate stripped).
  - `normalizeForEchoComparison("a" + U+D0002 + "b")` equals `"ab"` (unassigned supplementary code point, the plane-13 pill terminator, stripped).
- **`normalize_stripsUnassignedCodepointsInsideTheBmpToo`**: A BMP-unassigned character (U+0378) is also removed, which shows the unassigned clause has no supplementary-plane bound.
  - `normalizeForEchoComparison("a" + U+0378 + "b")` equals `"ab"` (U+0378 is unassigned and inside the BMP, so the unassigned strip is not limited to code points above U+FFFF).
- **`normalize_keepsEmojiBecauseTheyAreOtherSymbolNotPrivateUse`**: A padlock emoji prefix survives normalisation: `padlock + " Hi"` becomes `padlock + "hi"`.
  - With `padlock` the string for U+1F510, `normalizeForEchoComparison(padlock + " Hi")` equals `padlock + "hi"` (emoji kept, space dropped, case folded).
- **`normalize_punctuationSurvives`**: `"H***o!"` normalises to `"h***o!"`, so asterisks remain available to the censored-variant match.
  - `normalizeForEchoComparison("H***o!")` equals `"h***o!"`.
- **`extractEchoTokens_splitsOnEveryNonAlphanumeric`**: `"Hello, world!"` yields `["hello", "world"]` and `"a1-b2"` yields `["a1", "b2"]`: digits are token characters, the hyphen separates.
  - `extractEchoTokens("Hello, world!")` equals `["hello", "world"]`.
  - `extractEchoTokens("a1-b2")` equals `["a1", "b2"]` (digits are token chars, hyphen is a separator).
- **`extractEchoTokens_emptyAndPunctuationOnlyYieldNoTokens`**: An empty string and `"--- !!! ---"` both yield an empty token list.
  - `extractEchoTokens("")` equals the empty list.
  - `extractEchoTokens("--- !!! ---")` equals the empty list.
- **`containsTokenSubsequence_isASubsequenceNotAContiguousRun`**: `[a,x,b,y,c]` contains `[a,b,c]` with gaps allowed, but `[a,b,c]` does not contain `[a,c,b]`.
  - `containsTokenSubsequence(["a","x","b","y","c"], ["a","b","c"])` is true (gaps allowed).
  - `containsTokenSubsequence(["a","b","c"], ["a","c","b"])` is false (order must hold).
- **`containsTokenSubsequence_emptyPayloadIsVacuouslyContained`**: An empty payload is contained in any non-empty token list, and a non-empty payload is not contained in an empty list.
  - `containsTokenSubsequence(["a"], [])` is true.
  - `containsTokenSubsequence([], ["a"])` is false.
- **`containsCensoredVariant_starInTheMessageStandsForAnyCharacter`**: `"say h***o there"` is a censored variant of `hello`, and an exact `"hello"` also matches.
  - `containsCensoredVariant("say h***o there", "hello")` is true (each `*` in the message matches any payload char).
  - `containsCensoredVariant("hello", "hello")` is true.
- **`containsCensoredVariant_aRunOfStarsMatchesAnyPayloadOfThatLength`**: `"*****"` matches both `hello` and `world`, but `"****"` does not match the five-letter `hello`; the run must be at least as long.
  - `containsCensoredVariant("*****", "hello")` is true.
  - `containsCensoredVariant("*****", "world")` is true.
  - `containsCensoredVariant("****", "hello")` is false (4 stars are shorter than the 5-char payload).
- **`containsCensoredVariant_theWildcardIsOneDirectionalOnly`**: A `*` in the payload is literal: message `"hello"` does not match payload `"h***o"`.
  - `containsCensoredVariant("hello", "h***o")` is false (a `*` in the payload is a literal, not a wildcard).
- **`containsCensoredVariant_emptyPayloadIsFalseNotVacuouslyTrue`**: An empty payload returns `false`.
  - `containsCensoredVariant("anything", "")` is false.
- **`containsNormalizedPayload_nullAndEmptyOnEitherSide`**: A `null` or empty message or payload, on either side, returns `false`.
  - `containsNormalizedPayload(null, "x")` is false.
  - `containsNormalizedPayload("x", null)` is false.
  - `containsNormalizedPayload("", "x")` is false.
  - `containsNormalizedPayload("x", "")` is false.
- **`containsNormalizedPayload_payloadThatNormalisesAwayIsNotAMatch`**: A payload of only spaces and a glyph normalises to nothing and does not match, rather than matching every message.
  - `containsNormalizedPayload("anything", "  " + U+E003 + " ")` is false (payload is only spaces and a private-use char, so it normalises to empty).
- **`containsNormalizedPayload_matchesAcrossGlyphsSpacingAndCase`**: An echo with a separator glyph, upper-case letters and a glyph embedded in `"HELLO"` still matches payload `"hello"`.
  - `containsNormalizedPayload("you " + SEP + " holidaze: HEL" + U+E003 + "LO there", "hello")` is true (glyphs, spaces and case ignored).
- **`containsNormalizedPayload_fallsThroughToTheTokenSubsequence`**: `"hello, world, friend"` matches payload `"hello, friend"`, which only the token-subsequence step can accept.
  - `containsNormalizedPayload("hello, world, friend", "hello, friend")` is true (matched by the token-subsequence path).
- **`containsNormalizedPayload_tokenBoundariesComeFromPunctuationOnlyNotSpaces`**: Whitespace is stripped before tokenising, so `"hello world friend"` is one token `helloworldfriend` and does not match `"hello friend"`.
  - `extractEchoTokens(normalizeForEchoComparison("hello world friend"))` equals `["helloworldfriend"]` (normalisation strips spaces before tokenising, so one token).
  - `containsNormalizedPayload("hello world friend", "hello friend")` is false (space-separated words do not form separate tokens).

## `org.wynnvets.chat.spoiler`

### `SpoilerCodecTest`

Test: [`SpoilerCodecTest.java`](../src/test/java/org/wynnvets/chat/spoiler/SpoilerCodecTest.java) · Subject: [`SpoilerCodec`](../src/client/java/org/wynnvets/chat/spoiler/SpoilerCodec.java)

`SpoilerCodec` converts Discord-style `||spoiler||` text to and from a private-use encoding (start U+F600, end U+F601, direct chars at U+F602 and up, an escape at U+F700 followed by three base-254 digits) wrapped in a visible `[Spoiler: ...]` label. The suite pins detection, the encode/decode round trip for ASCII, high-codepoint and CJK content, and the hardening rules: banned characters are stripped on both encode and decode, and the decoder drops any escape that could not have come from the encoder. Several fixtures are written as raw PUA characters or `\u` escapes, and expectations are given on the encoded form where a round trip alone would pass without the encode-side strip.

- **`containsPipeSpoiler_nullEmptyAndNoMarker`**: `null`, `""`, plain text and an unterminated `"|| not a real spoiler"` all return `false`.
  - `SpoilerCodec.containsPipeSpoiler(null)` is false.
  - `SpoilerCodec.containsPipeSpoiler("")` is false.
  - `SpoilerCodec.containsPipeSpoiler("plain text")` is false.
  - `SpoilerCodec.containsPipeSpoiler("|| not a real spoiler")` is false (only one `||`, no closing pair).
- **`containsPipeSpoiler_singleAndMultiple`**: `"hello ||world||"` and `"||a|| and ||b||"` both return `true`.
  - `SpoilerCodec.containsPipeSpoiler("hello ||world||")` is true.
  - `SpoilerCodec.containsPipeSpoiler("||a|| and ||b||")` is true.
- **`containsEncodedSpoiler_requiresBothDelimiters`**: `null`, empty, plain text, start-only and end-only return `false`; a start marker followed by content and an end marker returns `true`.
  - `SpoilerCodec.containsEncodedSpoiler(null)` is false.
  - `SpoilerCodec.containsEncodedSpoiler("")` is false.
  - `SpoilerCodec.containsEncodedSpoiler("plain")` is false.
  - `containsEncodedSpoiler(onlyStart)` is false, where `onlyStart` is `"x" + SPOILER_START + "y"` (SPOILER_START is U+F600, no end delimiter).
  - `containsEncodedSpoiler(onlyEnd)` is false, where `onlyEnd` is `"x" + SPOILER_END + "y"` (SPOILER_END is U+F601, no start delimiter).
  - `containsEncodedSpoiler(both)` is true, where `both` is `SPOILER_START + 'A' + SPOILER_END`.
- **`encodeSpoilers_passesThroughWhenNoMarkers`**: `null` returns `null`, the empty string returns the same reference, and text without markers is returned equal.
  - `SpoilerCodec.encodeSpoilers(null)` is the same instance as null (returns null).
  - `encodeSpoilers(empty)` is the same instance as `empty`, the `""` literal passed in.
  - `encodeSpoilers(plain)` equals `plain`, where `plain` is `"no markers here"`.
- **`encodeSpoilers_wrapsContentWithVisiblePrefix`**: Encoding `"a ||x|| b"` yields output containing `WRAPPER_PREFIX` and `WRAPPER_SUFFIX` that `containsEncodedSpoiler` recognises.
  - `encoded` (= `encodeSpoilers("a ||x|| b")`) contains `SpoilerCodec.WRAPPER_PREFIX` (`"[Spoiler: "`) (is true).
  - `encoded` contains `SpoilerCodec.WRAPPER_SUFFIX` (`"]"`) (is true).
  - `SpoilerCodec.containsEncodedSpoiler(encoded)` is true.
- **`roundTrip_simpleAsciiSpoiler`**: `"||hello||"` decodes back to itself after `encodeSpoilers` then `decodeToDiscord`.
  - `decodeToDiscord(encodeSpoilers("||hello||"))` equals `"||hello||"`.
- **`roundTrip_multipleSpoilersPreservesSurroundingText`**: `"before ||a|| middle ||bb|| end"` round-trips unchanged, keeping the text between and around the blocks.
  - `decodeToDiscord(encodeSpoilers("before ||a|| middle ||bb|| end"))` equals `"before ||a|| middle ||bb|| end"`.
- **`decodeContent_emptyReturnsEmpty`**: `decodeContent("")` and `decodeContent(null)` both return `""`.
  - `SpoilerCodec.decodeContent("")` equals `""`.
  - `SpoilerCodec.decodeContent(null)` equals `""`.
- **`decodeContent_truncatedEscapeDoesNotThrow`**: A lone escape character (U+F700) with no following digits does not throw; only the absence of an exception is asserted.
  - `SpoilerCodec.decodeContent(truncated)` does not throw, where `truncated` is the one-char string U+F700 (a lone ESCAPE with no following digits).
- **`decodeToDiscord_stripsVisibleWrapper`**: Encoding `"plain ||spoil||"` and decoding gives exactly `"plain ||spoil||"`, with no `[Spoiler: ` wrapper left.
  - `decoded` (= `decodeToDiscord(encodeSpoilers("plain ||spoil||"))`) does not contain `SpoilerCodec.WRAPPER_PREFIX` (is false).
  - `decoded` equals `"plain ||spoil||"`.
- **`roundTrip_highCodepointViaEscapePath`**: `"||ÿ||"` (U+00FF, above the direct range) round-trips through the escape encoding.
  - `decodeToDiscord(encodeSpoilers("||" + U+00FF + "||"))` equals `"||" + U+00FF + "||"` (U+00FF is 255, above DIRECT_MAX 253, so uses the escape encoding).
- **`encodeSpoilers_dropsTheHardSetBeforeEncoding`**: Hard banned characters inside a spoiler leave no raw char, direct encoding or escape in the encoded output, and decode to `"||okok||"`.
  - `encoded` (= `encodeSpoilers("||ok" + U+00C1 + U+E07E + U+E080 + U+E086 + "ok||")`) does not contain U+00C1 (is false).
  - `encoded` does not contain U+F6C3 (the direct encoding of U+00C1) (is false).
  - `encoded` does not contain U+F700 (the ESCAPE char, which would encode the other hard chars) (is false).
  - `decodeToDiscord(encoded)` equals `"||okok||"`.
- **`encodeSpoilers_dropsTheSoftSetBeforeEncoding`**: Soft banned characters (a pill letter and `§`) are stripped on encode: the encoded string equals that of the clean input, not merely the decoded one.
  - `encodeSpoilers("||ok" + U+E041 + U+00A7 + "ok||")` equals `encodeSpoilers("||okok||")`, i.e. the soft-set chars are stripped before encoding.
  - `decodeToDiscord(encoded)` equals `"||okok||"`.
- **`encodeSpoilers_allBannedContentLeavesAnEmptyBlock`**: A spoiler containing only a banned char encodes to an adjacent start and end marker and decodes to `"||||"`.
  - `encoded` (= `encodeSpoilers("||" + U+00C1 + "||")`) contains `"" + SPOILER_START + SPOILER_END` (U+F600 immediately followed by U+F601), an empty encoded block (is true).
  - `decodeToDiscord(encoded)` equals `"||||"`.
- **`decodeContent_handWrittenBlocksCannotProduceAHardChar`**: Direct and escape encodings of hard banned characters, and printable ASCII after an escape that once wrapped to them, all decode to `""`.
  - `decodeContent(U+F6C3)` equals `""` (U+F6C3 is the direct encoding of hard char U+00C1, which is dropped).
  - `decodeContent(U+F700 U+F646 U+F6E3 U+F602)` equals `""` (escape with digits 68, 225, 0 = U+E080, a hard char).
  - `decodeContent` equals `""` for each of two hand-written strings: U+F700 followed by ASCII `d`, `"`, `Z`; and U+F700 followed by ASCII `!`, `|`, `(` (ASCII after the escape is outside the digit range, so the escape is dropped).
- **`decodeContent_dropsASoftGlyphReachedByEscape`**: An escape that decodes to the soft-banned U+E041 yields `""`.
  - `decodeContent(U+F700 U+F607 U+F6E3 U+F602)` equals `""` (escape with digits 5, 225, 0 = U+E041, a soft-set glyph, which is dropped).
- **`decodeContent_dropsAnEscapeWithAnOutOfRangeDigitWhole`**: An escape with a digit below U+F602 is dropped as four units and the following `A` survives.
  - `decodeContent(U+F700 "x" U+F602 U+F602 U+F643)` equals `"A"`: the escape and its out-of-range first digit `x` (below U+F602) are dropped, then U+F643 decodes to `A`.
- **`decodeContent_digitRangeEdges`**: Digits just outside the range (254 and -1) drop the escape whole while the trailing `A` stays; digit 253 is accepted and decodes to U+01FB.
  - `decodeContent(U+F700 U+F700 U+F602 U+F602 U+F643)` equals `"A"` (first digit U+F700 = digit 254, just out of range, escape dropped whole; trailing U+F643 decodes to `A`).
  - `decodeContent(U+F700 U+F601 U+F602 U+F602 U+F643)` equals `"A"` (first digit U+F601 = digit -1, just out of range, escape dropped whole; trailing U+F643 decodes to `A`).
  - `decodeContent(U+F700 U+F6FF U+F602 U+F602)` equals the one-char string U+01FB: digit 253 is the top of the range, 254 + 253 = U+01FB.
- **`decodeContent_dropsAnEscapeAboveUffffWholeNotUnitByUnit`**: An escape whose in-range digits sum past U+FFFF drops all four units, so only the following `A` remains rather than three stray U+00FD chars.
  - `decodeContent(U+F700 U+F6FF U+F6FF U+F6FF U+F643)` equals `"A"`: an escape with all three digits in range but value above U+FFFF is dropped as all four units, not leaving three U+00FD chars.
- **`roundTrip_highestBmpCharStillDecodes`**: A spoiler holding U+FFFF round-trips, showing the largest value an escape may carry still decodes.
  - `decodeToDiscord(encodeSpoilers("||" + U+FFFF + "||"))` equals `"||" + U+FFFF + "||"` (the largest value an escape can carry, digits 3, 3, 1).
- **`roundTrip_dropsASupplementaryUnassignedGlyph`**: A supplementary unassigned glyph (U+CFFFC) inside a spoiler is dropped by the encoder, and the round trip yields the clean text.
  - `encoded` = `encodeSpoilers("||a" + U+CFFFC + "b||")` (U+CFFFC written as the surrogate pair DAFF DFFC) equals `encodeSpoilers("||ab||")`: the encoder dropped it (the decoder would too, so only this pins the encode side).
  - `decodeToDiscord(encoded)` equals `"||ab||"`.
- **`roundTrip_keepsAnEmoji`**: An assigned supplementary character, the emoji U+1F600, round-trips unchanged.
  - `decodeToDiscord(encodeSpoilers("||ok " + U+1F600 + "||"))` equals the input.
- **`roundTrip_dropsALoneSurrogate`**: An unpaired high surrogate is dropped by the encoder, and the round trip yields the clean text.
  - `encoded` = `encodeSpoilers("||a" + U+D83D + "b||")` equals `encodeSpoilers("||ab||")`.
  - `decodeToDiscord(encoded)` equals `"||ab||"`.
- **`decodeContent_dropsAPrivateUseGlyphSpelledByTwoEscapes`**: Two escapes that spell the surrogate pair of U+F0000 (plane 15 private use), as another encoder could write them, decode to nothing; the `A` after them stays.
  - `decodeContent(ESC + digits 58, 220, 0 + ESC + digits 186, 220, 0 + "A"'s direct char)` equals `"A"` (ESC is U+F700; digit n is U+F602 + n).
- **`roundTrip_cjkCharacterViaEscapePath`**: `"||中||"` round-trips through the multi-digit escape path.
  - `decodeToDiscord(encodeSpoilers("||" + U+4E2D + "||"))` equals `"||" + U+4E2D + "||"` (CJK character U+4E2D, multi-digit base-254 escape path).

## `org.wynnvets.commands`

### `HelpCommandsTest`

Test: [`HelpCommandsTest.java`](../src/test/java/org/wynnvets/commands/HelpCommandsTest.java) · Subject: [`HelpCommands`](../src/client/java/org/wynnvets/commands/HelpCommands.java)

`HelpCommands` builds the chat components for the `/wv help` index and each per-command detail page, with gates and the viewer's access level passed in as parameters. The suite pins each page as a component tree: sibling count, each sibling's exact text, colour and bold flag, so that merging or splitting adjacent same-styled appends fails even when the rendered text is identical. It also pins which index rows each gate combination shows, and the colour of the `Requires:` footer for each `Access` state. Helper methods (`assertPage`, `rowsOf`, `expectedRows`, `assertRequirement`) rebuild rows from the tree and restate the expected row order independently of production code.

Asserting helpers:

- **`assertPage`**: Takes a built page (`MutableComponent`) and an ordered list of expected siblings, each (colour `ChatFormatting`, bold flag, text); `sib` is non-bold and `boldSib` is bold. Asserts the page has exactly that many siblings (`page.getSiblings().size()` equals the expected count), then for each index i: sibling i's `getString()` equals the expected text, its style colour value equals the expected `ChatFormatting`'s colour value, and its `getStyle().isBold()` equals the expected bold flag.
- **`assertRequirement`**: Takes a page, an expected `ChatFormatting` colour, an expected label and a context string. Asserts the last sibling of the page (`getSiblings().getLast()`) has `getString()` equal to the label and a style colour value equal to the expected colour's colour value.

Tests:

- **`helpPage_withEveryGateTrue_pinsItsThirtySiblings`**: With all six gates true, the index page matches the expected sibling tree, including bold gold heading, gray hint, yellow command and gold argument runs, and gray descriptions.
  - `HelpCommands.buildHelp(true, true, true, true, true, true)` has exactly 30 siblings, all non-bold except the first, in this order (colour: text; trailing `\n` is part of the text): GOLD bold `Commands\n`; GRAY `Use /wv help <command> for details on a specific command.\n\n`; then command rows, each a YELLOW command then GRAY description: `/wv help` / ` - Show this help message\n`; `/wv anni` / ` - Show anni timer; /wv help anni for modes & RSVP\n`; `/wv config ` + GOLD `[<key> [<value>]]` + GRAY ` - View or change mod settings\n`; `/wv motd` / ` - Show the message of the day\n`; `/wv list` / ` - Show online guild members\n`; `/wv list world` / ` - Show online members by world\n`; `/wv staff` / ` - Show online stewards\n`; `/wv distribute ` + GOLD `<name|@selector> <type> <count>` + GRAY ` - Send guild rewards (Chief/Owner)\n`; `/wv return` / ` - Show info about this week's event\n`; `/wv line ` + GOLD `<church|scrap|bat|hegea|lighthouse>` + GRAY ` - Toggle gxp boundary lines\n`; `/wv check ` + GOLD `<player>` + GRAY ` - Look up a potential recruit's info\n`; `/wv debug` / ` - Diagnostics & debug tools` (last row has no trailing newline).
- **`helpPage_withNoGate_pinsItsThirteenSiblings`**: With all gates false, only the heading, hint, `help`, `anni`, `config`, `motd` and `debug` rows remain, each with exact text and colour.
  - `HelpCommands.buildHelp(false, false, false, false, false, false)` has exactly 13 siblings: GOLD bold `Commands\n`; GRAY `Use /wv help <command> for details on a specific command.\n\n`; YELLOW `/wv help` + GRAY ` - Show this help message\n`; YELLOW `/wv anni` + GRAY ` - Show anni timer\n`; YELLOW `/wv config ` + GOLD `[<key> [<value>]]` + GRAY ` - View or change mod settings\n`; YELLOW `/wv motd` + GRAY ` - Show the message of the day\n`; YELLOW `/wv debug` + GRAY ` - Diagnostics & debug tools`. No list, list world, staff, distribute, return, line or check rows.
- **`gateMatrix_allSixtyFourCombinationsProduceTheirExactRowSet`**: For every combination of the six gates, the ordered command rows equal an independently restated list, so regrouping rows by gate fails.
  - For each `mask` from 0 to 63 (bits 1, 2, 4, 8, 16, 32 give `anniTools`, `unlocked`, `staff`, `staffOfAnyGuild`, `featuresEnabled`, `confirmedStaff`, in `buildHelp`'s parameter order, so all 64 combinations): `rowsOf(buildHelp(...))` equals `expectedRows(...)` of the last five; `anniTools` changes only a row's text. `rowsOf` collects each YELLOW sibling's text and appends a directly-following non-bold GOLD argument run to the same row. `expectedRows` is, in order: `/wv help`, `/wv anni`, `/wv config [<key> [<value>]]`, `/wv motd`, then `/wv list` if `unlocked`, `/wv list world` if `staff`, `/wv staff` if `unlocked`, `/wv distribute <name|@selector> <type> <count>` if `staffOfAnyGuild`, `/wv return` and `/wv line <church|scrap|bat|hegea|lighthouse>` if `featuresEnabled`, `/wv check <player>` if `confirmedStaff`, then `/wv debug`.
- **`anniRowPointsAtTheAnniPageOnlyWithAnniTools`**: The `/wv anni` row points at `/wv help anni` for the modes and RSVP exactly when `anniTools` is true, whatever the other gates say.
  - For each `mask` from 0 to 63 (as the gate matrix): the sibling after the YELLOW `/wv anni` head is ` - Show anni timer; /wv help anni for modes & RSVP\n` when bit 1 (`anniTools`) is set, else ` - Show anni timer\n`.
- **`wvListIsAdvertisedExactlyOnceWhenUnlockedAndNeverOtherwise`**: Across all gate combinations the `/wv list` row appears once when unlocked and not at all otherwise. Regression guard for a fix where it printed twice and keyed off the vet predicate.
  - For each `mask` from 0 to 63 (as the gate matrix): `advertised`, the count of rows equal to `"/wv list"` in `rowsOf(buildHelp(...))`, equals 1 when `unlocked` (bit 2) is set and 0 otherwise; the `/wv list world` row does not count.
- **`helpConfigPage_pinsItsNineteenSiblings`**: The config options page matches its exact tree, and its last row is a yellow key followed by two separate gray appends with no trailing newline.
  - `HelpCommands.buildHelpConfig()` has exactly 19 siblings: GOLD bold `Config Options\n`; GRAY `Toggle with /wv config <key> <default|true|false>.\nThese are the common toggles; /wv config lists every key.\n\n`; then seven YELLOW key (`legacyItemHighlighting\n`, `printMOTD\n`, `printANNI\n`, `printBridgeMessages\n`, `showSupporterGlints\n`, `colorBlindMode\n`, `handleSpoilers\n`), each followed by one GRAY description starting with two spaces and ending `\n` (highlights old items in inventory; auto-print the message of the day on join; auto-print the annihilation timer on join (Returners members only); show bridge messages incl. discord, queue, waitlist and honourary chats; supporter animated gradient glints on nametags, chat pills and /wv list; higher-contrast colour pair for supporter glints for red/green colour-blind users; encode ||spoiler|| markers in /g and /wg and render as hoverable labels); last row YELLOW `moreReliableGuildCheck\n` followed by two separate GRAY siblings, `  Run /gu stats on world join to detect your membership,` and ` instead of relying solely on Wynntils (it can lag on join)`, neither ending in a newline.
- **`helpCheckPage_pinsItsFiveSiblings`**: The `/wv check` page, built with `CONFIRMED` access, matches its tree and ends with an aqua `Staff` requirement.
  - `buildHelpCheck(Access.CONFIRMED)` has exactly 5 siblings: YELLOW `/wv check `; GOLD `<player>\n`; GRAY `Look up someone's vets membership and client status.\n`; GRAY `Requires: `; AQUA `Staff`.
- **`helpReturnPage_pinsItsFourSiblings`**: The `/wv return` page matches its tree and ends with an aqua `Returners guild member` requirement.
  - `buildHelpReturn(Access.CONFIRMED)` has exactly 4 siblings: YELLOW `/wv return\n`; GRAY `Display information about this week's event.\n`; GRAY `Requires: `; AQUA `Returners guild member`.
- **`helpStaffPage_pinsItsFourSiblings`**: The `/wv staff` page matches its tree and ends with an aqua `Unlocked` requirement.
  - `buildHelpStaff(Access.CONFIRMED)` has exactly 4 siblings: YELLOW `/wv staff\n`; GRAY `Show a list of currently online stewards.\n`; GRAY `Requires: `; AQUA `Unlocked`.
- **`helpListPage_pinsItsFourSiblings`**: The `/wv list` detail page matches its tree, headed `"/wv list\n"`, with an aqua veteran requirement.
  - `buildHelpList(Access.CONFIRMED)` has exactly 4 siblings: YELLOW `/wv list\n`; GRAY `Show online Returners members and some basic info about them.\n`; GRAY `Requires: `; AQUA `Veteran (Returners, waitlist, or honourary)`.
- **`helpMotdPage_pinsItsFourSiblings`**: The `/wv motd` page matches its tree, including the note that it is also a standalone `/motd`, with a white public requirement.
  - `buildHelpMotd()` has exactly 4 siblings: YELLOW `/wv motd\n`; GRAY `Show the message of the day. Also available as a standalone /motd command.\n`; GRAY `Requires: `; WHITE `None (public)`.
- **`helpAnniPage_unauthenticated_pinsItsSevenSiblings`**: Unauthenticated, the `/wv anni` page lists the timer and the three modes, with no rsvp row, and ends with a white `None (public)` requirement.
  - `buildHelpAnni(false)` has exactly 7 siblings: YELLOW `/wv anni\n`; GRAY `Show how long until the next annihilation event, if one has been announced.\n`; YELLOW `/wv anni ` + GOLD `<silent|passive|aggressive>\n` + GRAY `Set your anni mode: silent turns anni features off, passive suits folks experienced at attending vets-annis, and aggressive suits folks attending their first.\n`; GRAY `Requires: `; WHITE `None (public)`.
- **`helpAnniPage_authenticated_pinsItsTenSiblings`**: Authenticated, the rsvp row follows the modes and the footer stays public.
  - `buildHelpAnni(true)` has exactly 10 siblings: the first five of the unauthenticated page; YELLOW `/wv anni rsvp ` + GOLD `<hard|soft|revoke>\n` + GRAY `Send a hard or soft RSVP for the next anni, or revoke yours.\n`; GRAY `Requires: `; WHITE `None (public)`.
- **`helpLinePage_pinsItsFiveSiblings`**: The `/wv line` page matches its tree, including the long gray description naming each territory option, and an aqua `Returners guild member` requirement.
  - `buildHelpLine(Access.CONFIRMED)` has exactly 5 siblings: YELLOW `/wv line `; GOLD `<church|scrap|bat|hegea|lighthouse>\n`; one GRAY description ending `\n` (toggle rendering of territory boundary lines for the specified territory, naming "church" (Witness Church - Forest of Eyes), "scrap" (Scrapyard - Corkus Sea Cove), "bat" (Batcave - Royal Barracks), "hegea" (Training Grounds - Fort Hegea) and "lighthouse" (Lighthouse - Contested District), to pick which boundaries to show); GRAY `Requires: `; AQUA `Returners guild member`.
- **`helpDebugPage_pinsItsEighteenSiblings`**: The `/wv debug` page matches its tree, including the dark-gray pointer line that belongs to no row and a white public requirement.
  - `buildHelpDebug()` has exactly 18 siblings: YELLOW `/wv debug\n`; GRAY `Run diagnostics and dump mod state to chat and log.\n\n`; GOLD `Subcommands:\n`; four subcommand rows of YELLOW head + GOLD args + GRAY description: `/wv debug ` `<boolean>` ` - Toggle debug logging\n`; `/wv debug set ` `<key> <value>` ` - Manage debug config keys\n`; `/wv debug trigger ` `<action>` ` - Run a one-shot debug action\n`; `/wv debug tree ` `<subsystem>` ` - Open a subsystem-specific debug tree (e.g. anni)\n\n`; DARK_GRAY `Use /wv help debug <set|trigger> for details.\n`; GRAY `Requires: `; WHITE `None (public)`.
- **`helpDebugSetPage_pinsItsEighteenSiblings`**: The `/wv debug set` page matches its tree; the `itemDump` row is a yellow head, a gray line and a dark-gray continuation, not flattened to gray.
  - `buildHelpDebugSet()` has exactly 18 siblings: YELLOW `/wv debug set `; GOLD `[<key> [<value>]]\n`; GRAY `Manage debug-only configuration keys.\n\n`; GOLD `Usage:\n`; three usage rows: YELLOW `/wv debug set` + GRAY ` - List all debug config keys and their values\n`; YELLOW `/wv debug set ` + GOLD `<key>` + GRAY ` - Show the current value of a key\n`; YELLOW `/wv debug set ` + GOLD `<key> <true|false>` + GRAY ` - Set a debug key to true or false\n\n`; GOLD `Available keys:\n`; YELLOW `itemDump`; GRAY ` - Dump an item's full component tree\n`; DARK_GRAY `  When on, press numpad + while hovering it\n`; GRAY `Requires: `; WHITE `None (public)`.
- **`helpDebugTriggerPage_pinsItsFortySiblings`**: The `/wv debug trigger` page lists all eight actions; each has a gray description then dark-gray clarification, and long lines wrap into extra fragments of the same colour.
  - `buildHelpDebugTrigger()` has exactly 40 siblings: YELLOW `/wv debug trigger `; GOLD `<action>\n`; GRAY `Run a one-shot debug action.\n\n`; GOLD `Actions:\n`; then eight actions, each YELLOW name line then GRAY description then DARK_GRAY clarification, with wrapped lines as separate siblings: `charDump\n` / GRAY `  Render PUA characters U+E001`, an en dash (U+2013), then `U+E040 in chat.\n` / two DARK_GRAY (`  Drawn as badge-style sequences in the resource\n`, `  pack's chat/prefix font.\n`); `forceChecks\n` / two GRAY (`  Force a re-check of guild membership, rank,\n`, `  and staff status.\n`) / three DARK_GRAY (`  Reports guild and staff state to chat, then\n`, `  re-runs /gu rank and /gu stats, skipping\n`, `  either if one is already in flight.\n`); `tabDump\n` / GRAY `  Dump the raw tab list to chat.\n` / two DARK_GRAY (`  Split into labelled columns, then shows the\n`, `  parsed guild members.\n`); `bossBarsDump\n` / GRAY `  Dump the boss bars on screen to chat.\n` / two DARK_GRAY (`  Order, id, name, colour, overlay and progress\n`, `  of each, with vetsmod's own bar marked.\n`); `nametagsDump\n` / GRAY `  Dump each player's nametag diagnostics.\n` / two DARK_GRAY (`  Anni gate, registry hit, tier and role, the\n`, `  display name, and the colour it would get.\n`); `ghostsPromptDump\n` / GRAY `  Log the ghosts-prompt inputs.\n` / two DARK_GRAY (`  To the log, not chat: the gate, each player's\n`, `  ghost flag, and whether the prompt would fire.\n`); `zoneLinesDump\n` / GRAY `  Dump the zone-line renderer's inputs.\n` / two DARK_GRAY (`  Gate, toggle and cache state, then each zone\n`, `  disc and its distance from you.\n`); `rsvpDump\n` / GRAY `  Log the RSVP client's state.\n` / two DARK_GRAY (`  To the log, not chat: auth, queue depth, the\n`, `  last attempt and ack, and the rsvp block.\n`); then GRAY `Requires: ` and WHITE `None (public)`. Action heads are written `/wv debug trigger <name>\n`.
- **`requiresFooter_coloursEachAccessStateOnEveryGatedPage`**: For each of `DENIED`, `GRANTED` and `CONFIRMED`, the last sibling of the check, return, staff, list and line pages has the right label and red, green or aqua colour.
  - For each `access` in `ACCESS_COLOURS` (DENIED to RED, GRANTED to GREEN, CONFIRMED to AQUA; `expected` is that colour): the last sibling of `buildHelpCheck(access)` is `Staff` in the `expected` colour.
  - For the same three `access` values: the last sibling of `buildHelpReturn(access)` is `Returners guild member` in the `expected` colour.
  - For the same three `access` values: the last sibling of `buildHelpStaff(access)` is `Unlocked` in the `expected` colour.
  - For the same three `access` values: the last sibling of `buildHelpList(access)` is `Veteran (Returners, waitlist, or honourary)` in the `expected` colour.
  - For the same three `access` values: the last sibling of `buildHelpLine(access)` is `Returners guild member` in the `expected` colour.
- **`access_resolvesAllFourSignalPairs`**: `access(met, confirmed)` gives `DENIED` for (false, false), `GRANTED` for (true, false) and `CONFIRMED` for (true, true) and (false, true); a server confirmation outranks a stale local signal.
  - `HelpCommands.access(false, false)` equals `Access.DENIED`.
  - `HelpCommands.access(true, false)` equals `Access.GRANTED`.
  - `HelpCommands.access(true, true)` equals `Access.CONFIRMED`.
  - `HelpCommands.access(false, true)` equals `Access.CONFIRMED` (server-confirmed outranks a stale local signal).
- **`requiresFooter_staysWhiteOnTheFivePagesWithNoGate`**: The motd, anni, debug, debug set and debug trigger pages end with a white `None (public)` requirement. Its first assertion cannot fail: it looks `PUBLIC` up in the test's own `ACCESS_COLOURS` map, which has no `PUBLIC` key, so `getOrDefault` always returns the WHITE it is compared with.
  - `ACCESS_COLOURS.getOrDefault(Access.PUBLIC, ChatFormatting.WHITE)` equals `ChatFormatting.WHITE` (PUBLIC has no entry in the map, so the default is returned).
  - The last sibling of `buildHelpMotd()` is `None (public)` in WHITE.
  - The last sibling of `buildHelpAnni(false)` and of `buildHelpAnni(true)` is `None (public)` in WHITE.
  - The last sibling of `buildHelpDebug()` is `None (public)` in WHITE.
  - The last sibling of `buildHelpDebugSet()` is `None (public)` in WHITE.
  - The last sibling of `buildHelpDebugTrigger()` is `None (public)` in WHITE.

## `org.wynnvets.config`

### `NamedColorTest`

Test: [`NamedColorTest.java`](../src/test/java/org/wynnvets/config/NamedColorTest.java) · Subject: [`NamedColor`](../src/client/java/org/wynnvets/config/NamedColor.java)

`NamedColor` is a registry of named colours (including a custom `legacy_orange` and a `transparent` entry) used by the legacy item highlighting config, with lookups by name and an opacity-to-alpha helper. The suite pins the registry order and immutability, case-insensitive and locale-independent lookup, the null-on-miss and default-on-miss contracts, and the rounding, clamping and alpha-replacing behaviour of `withAlpha`. It temporarily switches the JVM default locale to Turkish in one test and restores it afterwards.

- **`getNames_hasFortyEntriesInInsertionOrder`**: The name set has 40 entries, with `black` first and `transparent` last.
  - `List.copyOf(NamedColor.getNames())` has size 40.
  - Element 0 of `names` equals `"black"`.
  - Element 39 (the last) of `names` equals `"transparent"`.
- **`getNames_isUnmodifiable`**: Adding to the returned name set throws `UnsupportedOperationException`.
  - `NamedColor.getNames().add("puce")` throws `UnsupportedOperationException`.
- **`isValid_knownNameCaseInsensitive`**: `gold`, `GOLD` and `GoLd` are all valid.
  - `isValid("gold")` is true.
  - `isValid("GOLD")` is true.
  - `isValid("GoLd")` is true.
- **`isValid_survivesATurkishDefaultLocale`**: With the default locale set to Turkish, `WHITE` is still valid and `getRgb("WHITE")` is `0xFFFFFF`, because case folding uses the root locale.
  - With the JVM default locale set to `tr`, `isValid("WHITE")` is true.
  - With the default locale set to `tr`, `getRgb("WHITE")` equals `0xFFFFFF`.
- **`isValid_doesNotTrim`**: `" gold "` and `"gold "` are not valid; surrounding whitespace is not stripped.
  - `isValid(" gold ")` is false.
  - `isValid("gold ")` is false.
- **`isValid_nullAndUnknown`**: `null`, the empty string and `puce` are not valid.
  - `isValid(null)` is false.
  - `isValid("")` is false.
  - `isValid("puce")` is false.
- **`getRgb_returnsTheStoredRgb`**: `gold` gives `0xFFAA00` in any case, `legacy_orange` gives `0xF0501E` and `mythic` gives `0x4C004C`.
  - `getRgb("gold")` equals `0xFFAA00`.
  - `getRgb("GOLD")` equals `0xFFAA00`.
  - `getRgb("legacy_orange")` equals `0xF0501E`.
  - `getRgb("mythic")` equals `0x4C004C`.
- **`getRgb_unknownIsNullNotADefault`**: An unknown name, `null`, and a name with surrounding spaces all return `null`.
  - `getRgb("puce")` is null.
  - `getRgb(null)` is null.
  - `getRgb(" gold ")` is null.
- **`getRgb_transparentIsIndistinguishableFromBlack`**: `transparent` maps to `0x000000`, equal to `black`; transparency is handled by the caller, not by this map.
  - `getRgb("transparent")` equals `0x000000`.
  - `getRgb("black")` equals `getRgb("transparent")`.
- **`getRgbOrDefault_hitIgnoresTheDefault`**: A known name returns its own RGB and not the supplied default.
  - `getRgbOrDefault("gold", 0xDC143C)` equals `0xFFAA00`.
- **`getRgbOrDefault_missAndNullTakeTheDefault`**: An unknown name or `null` returns the supplied default.
  - `getRgbOrDefault("puce", 0xDC143C)` equals `0xDC143C`.
  - `getRgbOrDefault(null, 0xDC143C)` equals `0xDC143C`.
- **`getRgbOrDefault_returnsBlackForTransparentNotTheDefault`**: `transparent` is a hit, so the result is `0x000000` rather than the default.
  - `getRgbOrDefault("transparent", 0xFFA500)` equals `0x000000`.
- **`withAlpha_endpointsOfTheOpacityRange`**: 0% opacity gives alpha `0x00` and 100% gives `0xFF`, with the RGB bits unchanged.
  - `withAlpha(0xFFAA00, 0)` equals `0x00FFAA00`.
  - `withAlpha(0xFFAA00, 100)` equals `0xFFFFAA00`.
- **`withAlpha_roundsHalfUp`**: 50% gives alpha `0x80` (127.5 rounds up), 1% gives `0x03`, and 69% gives `0xB0`.
  - `withAlpha(0xFFAA00, 50)` equals `0x80FFAA00`.
  - `withAlpha(0xFFAA00, 1)` equals `0x03FFAA00`.
  - `withAlpha(0xFFAA00, 69)` equals `0xB0FFAA00`.
- **`withAlpha_clampsOutOfRangeOpacity`**: Opacity below 0 (including `Integer.MIN_VALUE`) clamps to alpha `0x00`, and above 100 (including `Integer.MAX_VALUE`) clamps to `0xFF`, with no overflow.
  - For each `opacity` in -20, `Integer.MIN_VALUE`: `withAlpha(0xFFAA00, opacity)` equals `0x00FFAA00`.
  - For each `opacity` in 250, `Integer.MAX_VALUE`: `withAlpha(0xFFAA00, opacity)` equals `0xFFFFAA00`.
- **`withAlpha_stripsAlphaAlreadyPresentOnTheInput`**: An input that already carries alpha has it discarded: `0xFFFFAA00` at 0% becomes `0x00FFAA00`, and `0xAB123456` at 100% becomes `0xFF123456`.
  - `withAlpha(0xFFFFAA00, 0)` equals `0x00FFAA00`.
  - `withAlpha(0xAB123456, 100)` equals `0xFF123456`.

### `ShippedConfigTest`

Test: [`ShippedConfigTest.java`](../src/test/java/org/wynnvets/config/ShippedConfigTest.java) · Subject: [`ShippedConfig`](../src/client/java/org/wynnvets/config/ShippedConfig.java), and the `config.yml` resource the build generates from [`VetsConfigDefaults`](../src/client/java/org/wynnvets/config/VetsConfigDefaults.java)

`ShippedConfig` reads the `config.yml` bundled in the mod jar, whose values are the defaults that `/wv config <key> default` restores. The suite pins the YAML parse, the typed reads that return `null` for a missing key or a wrong type, and that the real `config.yml` on the test classpath holds exactly `VetsConfigDefaults.firstRunValues()`, which fails if the build's `generateConfigYml` wiring breaks. It bypasses the Fabric-dependent `load()` by assigning the package-private `values` field directly and clears it after each test; the last test reads the real resource from disk.

- **`parse_keepsEachValuesType`**: A YAML document with a boolean, an integer, an empty quoted string and a plain string parses to a `Boolean`, a number of 69, `""` and `"orange"`.
  - `parsed.get("flag")` equals `Boolean.TRUE`, where `parsed` is `ShippedConfig.parse` of the YAML lines `flag: true`, `opacity: 69`, `empty: ''`, `name: orange`.
  - `parsed.get("opacity")`, as a Number, has `longValue()` equal to `69L`.
  - `parsed.get("empty")` equals `""`.
  - `parsed.get("name")` equals `"orange"`.
- **`parse_readsNothingFromADocumentThatIsNotAMapping`**: A YAML list and an empty document both parse to an empty map.
  - Parsing the YAML sequence `- a` / `- b` gives `Map.of()`.
  - Parsing the empty string gives `Map.of()`.
- **`typedReads_returnNullForAMissingKeyOrTheWrongType`**: `bool`, `number` and `string` return the value for the matching type and `null` for a mismatched type or missing key; a quoted `'true'` is a string, not a boolean.
  - `ShippedConfig.bool("flag")` equals `Boolean.TRUE`, where `values` is the parse of `flag: true`, `opacity: 69`, `name: orange`, `quoted: 'true'`.
  - `ShippedConfig.number("opacity")` equals `69L`.
  - `ShippedConfig.string("name")` equals `"orange"`.
  - `ShippedConfig.string("quoted")` equals `"true"`, since the quoted value is a string.
  - For each `key` in `quoted`, `name`: `ShippedConfig.bool(key)` is null.
  - `ShippedConfig.number("flag")` is null.
  - `ShippedConfig.string("opacity")` is null.
  - `ShippedConfig.bool("missing")` is null.
- **`theShippedFileHoldsTheFirstRunValues`**: The bundled `/config.yml` starts with the generated-file header and has the same key set and values as `firstRunValues()`, with integers compared numerically.
  - The resource `/config.yml` from the test classpath is not null, so the stream opens.
  - The file text (UTF-8) starts with `# Written by the build from VetsConfigDefaults`.
  - The keys of `shipped` (the parsed file) equal the keys of `expected`, where `expected` is `VetsConfigDefaults.firstRunValues()`.
  - For each entry of `expected` whose value is a `Long`: the shipped value, as a Number, has `longValue()` equal to the expected value's `longValue()`.
  - For each other entry of `expected`: `shipped.get(key)` equals the expected value.

### `VetsConfigDefaultsTest`

Test: [`VetsConfigDefaultsTest.java`](../src/test/java/org/wynnvets/config/VetsConfigDefaultsTest.java) · Subject: [`VetsConfigDefaults`](../src/client/java/org/wynnvets/config/VetsConfigDefaults.java)

`VetsConfigDefaults` holds the values `config.json` starts from; they also feed the shipped `config.yml` and `/wv config <key> default`. The suite pins every user-facing default against a frozen literal table in the test, and checks that each user key in `VetsConfigSchema.USER_CONFIG_KEYS` has a default of the right Java type, with the tri-state `handleSpoilers` left unset. It needs no running client because `VetsConfigDefaults` has no static state and does not load `VetsConfig`.

- **`userDefaults_areTheFirstRunValues`**: Each entry in the frozen user-default table (for example `printMOTD` true, `colorBlindMode` false, `legacyItemBackgroundGradientTopOpacity` 69) equals the value in `firstRunValues()`.
  - For each `(key, expected)` in the test's 28-entry `USER_DEFAULTS` table, from `legacyItemHighlighting` (true) to `moreReliableGuildCheck` (true): `VetsConfigDefaults.firstRunValues().get(key)` equals `expected`. Non-true entries: `colorBlindMode` false, `legacyItemBackgroundGradientTopOpacity` 69L, `legacyItemBackgroundGradientBottomOpacity` 100L, `vetsAnniRoleStyle` `"descriptive"`, `vetsAnniFlashIntensity` `"normal"`, `legacyItemBackgroundGradientTop` `"orange"`, `legacyItemBackgroundGradientBottom` `"crimson"`, `legacyItemForegroundSprite` `"box_gradient_2"`, `legacyItemForegroundColor` `"orange"`.
- **`everyUserKeyHasADefaultOfItsKind_andTheTriStateIsLeftOut`**: The pinned keys plus `handleSpoilers` equal `USER_CONFIG_KEYS`; tri-state keys have no default entry, int keys are `Long`, string keys are `String`, and the rest are `Boolean`.
  - `Set.copyOf(List.of(VetsConfigSchema.USER_CONFIG_KEYS))` equals `pinned`, the 28 `USER_DEFAULTS` keys plus `handleSpoilers`: 29 names, so `USER_CONFIG_KEYS` holds exactly those and no others.
  - For each `key` in `USER_CONFIG_KEYS` where `isTriStateKey(key)` (only `handleSpoilers`): `values` does not contain `key`, where `values` is `firstRunValues()`.
  - For each `key` where `isIntKey(key)` (the two `legacyItemBackgroundGradient*Opacity` keys): `values.get(key)` is a `Long`.
  - For each `key` where `isStringKey(key)` (the keys of `STRING_CONFIG_KEYS`): `values.get(key)` is a `String`.
  - For each other `key` in `USER_CONFIG_KEYS` (the remaining boolean keys): `values.get(key)` is a `Boolean`.
- **`firstRunValues_includeTheDebugKeys`**: `firstRunValues()` contains the debug key `itemDump` with value `false`.
  - `firstRunValues().get("itemDump")` equals `Boolean.FALSE`.

### `VetsConfigKeysTest`

Test: [`VetsConfigKeysTest.java`](../src/test/java/org/wynnvets/config/VetsConfigKeysTest.java) · Subject: [`VetsConfigKeys`](../src/client/java/org/wynnvets/config/VetsConfigKeys.java)

`VetsConfigKeys` is the list of string constants naming each setting as stored in `config.json`. Those names are the file format: renaming one makes existing values unreadable and they are dropped at the next save. The suite compares the constants against a frozen constant-to-name table, read by reflection over all public static final `String` fields, so an added, removed or renamed key fails, and it checks the on-disk names are distinct.

- **`everyKeyConstant_hasItsOnDiskName`**: The reflected map of constant name to string value equals the frozen expected table, in both content and key set.
  - `keyConstants()`, the sorted map of every public static final `String` field of `VetsConfigKeys` (constant name to value), equals `new TreeMap<>(EXPECTED)`: the 42 frozen pairs from `VETS_AUTOMESSAGE` to `vetsAutomessage` through `LEGACY_ITEM_FOREGROUND_COLOR` to `legacyItemForegroundColor`.
- **`onDiskNames_areDistinct`**: No two constants share the same on-disk name.
  - For each `name` in the values of `keyConstants()`: `seen.add(name)` is true, meaning no earlier constant has the same on-disk name.

### `VetsConfigSchemaTest`

Test: [`VetsConfigSchemaTest.java`](../src/test/java/org/wynnvets/config/VetsConfigSchemaTest.java) · Subject: [`VetsConfigSchema`](../src/client/java/org/wynnvets/config/VetsConfigSchema.java)

`VetsConfigSchema` holds the key tables behind `/wv config`: which keys users may set, their print order, which keys are tri-state, string or int, and the valid-value lists for fixed-choice keys. The suite pins each table against a frozen literal and checks that the `is*Key` predicates and `fixedValuesOf`/`isFixedValue` agree with those arrays across every known key name plus a debug key and an unknown name. The string-key guard reads the live `STRING_CONFIG_KEYS`, so a new string key must be a colour key, the sprite key or declare a fixed list.

- **`userConfigKeys_inPrintOrder`**: `USER_CONFIG_KEYS` equals the expected array in exactly that order, which is the order `configList` prints.
  - `VetsConfigSchema.USER_CONFIG_KEYS` equals, in order, the 29 names from `legacyItemHighlighting` (first) to `moreReliableGuildCheck` (last). `handleSpoilers` is 28th.
- **`typeLists`**: `TRISTATE_KEYS` is `handleSpoilers`, `STRING_CONFIG_KEYS` is the four colour/sprite keys plus role style and flash intensity, and `INT_CONFIG_KEYS` is the two gradient opacity keys.
  - `TRISTATE_KEYS` equals `["handleSpoilers"]`.
  - `STRING_CONFIG_KEYS` equals, in order, `legacyItemBackgroundGradientTop`, `legacyItemBackgroundGradientBottom`, `legacyItemForegroundSprite`, `legacyItemForegroundColor`, `vetsAnniRoleStyle`, `vetsAnniFlashIntensity`.
  - `INT_CONFIG_KEYS` equals, in order, `legacyItemBackgroundGradientTopOpacity`, `legacyItemBackgroundGradientBottomOpacity`.
- **`validValueLists`**: `VALID_ROLE_STYLES`, `VALID_FLASH_INTENSITIES` and `VALID_SPRITES` equal their expected ordered arrays.
  - `VALID_ROLE_STYLES` equals, in order, `descriptive`, `short`, `formal`.
  - `VALID_FLASH_INTENSITIES` equals, in order, `subtle`, `normal`, `strong`.
  - `VALID_SPRITES` equals, in order, the 10 names from `wynn` (first) to `box_gradient_2` (last).
- **`fixedValuesOf_mapsOnlyRoleStyleAndFlashIntensity`**: For every known name, `fixedValuesOf` returns the role-style or flash-intensity list for those two keys and `null` for all others.
  - For `name` = `vetsAnniRoleStyle` in `ALL_NAMES` (44 names, `vetsAutomessage` first, `noSuchKey` last): `fixedValuesOf(name)` equals `descriptive`, `short`, `formal`.
  - For `name` = `vetsAnniFlashIntensity`: `fixedValuesOf(name)` equals `subtle`, `normal`, `strong`.
  - For every other name in `ALL_NAMES`: `fixedValuesOf(name)` is null.
- **`isFixedValue_acceptsTheListedLowercaseValuesOnly`**: Listed lowercase values pass for their key; wrong-list values, upper case `SHORT`, empty, `null`, colour keys and unknown keys fail.
  - For each `v` in `descriptive`, `short`, `formal`: `isFixedValue("vetsAnniRoleStyle", v)` is true.
  - For each `v` in `subtle`, `normal`, `strong`: `isFixedValue("vetsAnniFlashIntensity", v)` is true.
  - For each `value` in `red`, `SHORT`, `""`, null: `isFixedValue("vetsAnniRoleStyle", value)` is false.
  - `isFixedValue("vetsAnniFlashIntensity", "short")` is false.
  - `isFixedValue("legacyItemForegroundColor", "orange")` is false.
  - `isFixedValue("noSuchKey", "normal")` is false.
- **`everyStringKey_isAColourKeyTheSpriteKeyOrHasAFixedList`**: Each entry in `STRING_CONFIG_KEYS` is one of the colour or sprite keys or has a non-null fixed-value list.
  - For each `key` in `STRING_CONFIG_KEYS`: `key` is one of `legacyItemBackgroundGradientTop`, `legacyItemBackgroundGradientBottom`, `legacyItemForegroundColor`, `legacyItemForegroundSprite`, or `fixedValuesOf(key)` is not null.
- **`predicates_agreeWithTheirArrays`**: For every known key name, `isUserConfigKey`, `isTriStateKey`, `isStringKey` and `isIntKey` match membership in their respective arrays.
  - For each `name` in `ALL_NAMES`: `isUserConfigKey(name)` equals whether `name` is in `USER_CONFIG_KEYS`.
  - For each `name` in `ALL_NAMES`: `isTriStateKey(name)` equals whether `name` is in `TRISTATE_KEYS`.
  - For each `name` in `ALL_NAMES`: `isStringKey(name)` equals whether `name` is in `STRING_CONFIG_KEYS`.
  - For each `name` in `ALL_NAMES`: `isIntKey(name)` equals whether `name` is in `INT_CONFIG_KEYS`.

## `org.wynnvets.datamodels`

### `UserTest`

Test: [`UserTest.java`](../src/test/java/org/wynnvets/datamodels/UserTest.java) · Subject: [`User`](../src/client/java/org/wynnvets/datamodels/User.java)

`User` is the data model for a Wynncraft `/v3/player` payload. `isInVets()` reports whether the player's guild is the Returners guild: the suite pins that only a guild object whose `uuid` equals `VetsApi.GUILD_UUID` counts, and that a missing guild, a null guild, or a guild without a uuid return `false` rather than throwing. `getFirstJoinInstant()` and `getLastJoinInstant()` read the `firstJoin` and `lastJoin` timestamps as instants, `null` when absent or unparseable, while the string getters keep cutting at the `T`. Payloads are small hand-written JSON strings parsed with the shared `Json.GSON`, as the real fetchers parse them.

- **`isInVets_returnersGuild`**: A guild object whose uuid is `VetsApi.GUILD_UUID` returns `true`.
  - `parse(json).isInVets()` is true, where `json` is `{"guild": {"uuid": VetsApi.GUILD_UUID}}` and `parse` is `Json.GSON.fromJson(json, User.class)`.
- **`isInVets_otherGuild`**: A guild object with a different uuid returns `false`.
  - `parse(json).isInVets()` is false, where `json` is `{"guild": {"uuid": "bbbbbbbb-1111-2222-3333-444444444444"}}`.
- **`isInVets_noGuild`**: An empty payload and `"guild": null` both return `false`.
  - For each `json` in `{}`, `{"guild": null}`: `parse(json).isInVets()` is false.
- **`isInVets_guildWithoutUuid`**: A guild with only a name, and a guild with `"uuid": null`, return `false`. Regression guard: this case used to throw.
  - For each `json` in `{"guild": {"name": "Returners"}}`, `{"guild": {"uuid": null}}`: `parse(json).isInVets()` is false.
- **`joinInstants_parseTheApiTimestamps`**: A payload with millisecond `Z` timestamps gives both instants, and the string date getters still return the part before the `T`.
  - `getFirstJoinInstant()` equals `Instant.parse("2015-10-14T01:00:00Z")` and `getLastJoinInstant()` equals `Instant.parse("2026-10-22T01:07:00Z")`, for `firstJoin` `2015-10-14T01:00:00.000Z` and `lastJoin` `2026-10-22T01:07:00.000Z`.
  - `getFirstJoinDate()` equals `"2015-10-14"` and `getLastJoinDate()` equals `"2026-10-22"`.
- **`joinInstants_keepAnOffsetAsTheSameMoment`**: A timestamp with a UTC-3 offset parses to the same moment as its UTC form.
  - `getLastJoinInstant()` equals `Instant.parse("2026-10-22T01:07:00Z")`, for `lastJoin` `2026-10-21T22:07:00-03:00`.
- **`joinInstants_nullWhenAbsentOrUnparseable`**: Absent fields, and text that is not an instant, give `null`; the date getter still returns text without a `T` as it is.
  - For `{}`: `getFirstJoinInstant()` and `getLastJoinInstant()` are null.
  - For `firstJoin` `not a time` and `lastJoin` `2026-10-22`: both instant getters are null, and `getLastJoinDate()` equals `"2026-10-22"`.

## `org.wynnvets.debug.anni`

### `AnniDebugCommandsTest`

Test: [`AnniDebugCommandsTest.java`](../src/test/java/org/wynnvets/debug/anni/AnniDebugCommandsTest.java) · Subject: [`AnniDebugCommands`](../src/client/java/org/wynnvets/debug/anni/AnniDebugCommands.java)

`AnniDebugCommands` builds the Brigadier `anni` command tree mounted under `/wv debug tree`, and that tree is built when the client joins a server. The suite builds the tree and checks it does not throw and has the shape its Javadoc documents: root literal `anni`, a fixed number of top-level children, of handler leaves, and of inner nodes whose command prints a usage line. No command is run; building the tree initialises the suggestion providers but not the handler classes that need the game directory. A small recursive helper counts the nodes that carry a command, leaves or inner nodes.

- **`treeBuildsWithItsDocumentedShape`**: `buildCommandTree().build()` yields a root named `anni` with 11 children, 24 leaves that have a command (the handlers) and 18 inner nodes that have one (the usage lines).
  - `anni.getName()` equals `"anni"`, where `anni` is the `LiteralCommandNode` from `AnniDebugCommands.buildCommandTree().build()`.
  - `anni.getChildren().size()` equals 11, the direct children of the root.
  - `commandNodes(anni, true)` equals 24: leaves (no children) with a non-null command, over the whole tree.
  - `commandNodes(anni, false)` equals 18: inner nodes (with children) with a non-null command.

## `org.wynnvets.distribute`

### `MembersGuiTest`

Test: [`MembersGuiTest.java`](../src/test/java/org/wynnvets/distribute/MembersGuiTest.java) · Subject: [`MembersGui`](../src/client/java/org/wynnvets/distribute/MembersGui.java)

`MembersGui` describes the layout of Wynncraft's `<guild>: Members` menu: its title pattern, its two page-button slots and patterns, and which slots hold player-head tiles. The suite pins `isTileSlot` with a boundary sweep around the rows 0-4 by columns 2-8 tile area, including the two page-button slots, which sit one column outside it. It also pins the title, page-button patterns and page-button slot numbers as change-detectors for the claim that they mirror Wynntils' `GuildMemberListContainer`. It also pins `pageTurned`, the check the walker waits on after a Next or Previous click: true only when the new scan is non-empty and no tile slot still holds the name it held on the old page, so a name recurring in another slot still counts as turned (checked with Metrafish, Holidaze, Wenweia and Nazzae in slots 2 and 3). The tests call static members directly, with a small `assertTile` / `assertNotTile` helper that reports row and column in the failure message.

Asserting helpers:

- **`assertNotTile`**: `MembersGui.isTileSlot(slot)` is false for the given `slot`; the failure message names the slot's row and column.
- **`assertTile`**: `MembersGui.isTileSlot(slot)` is true for the given `slot`; the failure message names the slot's row and column.

Tests:

- **`slotsLeftOfTheTileAreaAreNotTiles`**: Slots 0 and 1 (row 0, columns 0 and 1) are not tiles.
  - `MembersGui.isTileSlot(0)` is false (slot 0, row 0, column 0).
  - `MembersGui.isTileSlot(1)` is false (slot 1, row 0, column 1, one column short of the first tile).
- **`theFirstTileOfTheTopRowIsATile`**: Slot 2 (row 0, column 2), the first tile, is a tile.
  - `MembersGui.isTileSlot(2)` is true (row 0, column 2, the first tile).
- **`theLastColumnOfTheTopRowIsATile`**: Slot 8 (row 0, column 8) is a tile.
  - `MembersGui.isTileSlot(8)` is true (row 0, column 8).
- **`theLastTileOfTheBottomRowIsATile`**: Slot 44 (row 4, column 8), the last tile, is a tile.
  - `MembersGui.isTileSlot(44)` is true (row 4, column 8, the last tile).
- **`slotsBelowTheTileAreaAreNotTiles`**: Slots 45 and 54 (column 0, rows 5 and 6) are not tiles.
  - `MembersGui.isTileSlot(slot)` is false for each `slot` in 45 (row 5, column 0) and 54 (row 6, column 0).
- **`theSlotDirectlyBelowTheLastTileIsNotATile`**: Slot 53 (row 5, column 8) is not a tile; it is the only one of the below-area slots that pins the bottom row bound.
  - `MembersGui.isTileSlot(53)` is false (row 5, column 8).
- **`thePreviousPageButtonIsNotATile`**: `PREVIOUS_PAGE_SLOT` (10, row 1 column 1) is not a tile, so the walker never collects a page button as a member.
  - `MembersGui.isTileSlot(MembersGui.PREVIOUS_PAGE_SLOT)` is false; `PREVIOUS_PAGE_SLOT` is 10 (row 1, column 1).
- **`theNextPageButtonIsNotATile`**: `NEXT_PAGE_SLOT` (28, row 3 column 1) is not a tile.
  - `MembersGui.isTileSlot(MembersGui.NEXT_PAGE_SLOT)` is false; `NEXT_PAGE_SLOT` is 28 (row 3, column 1).
- **`aNegativeSlotIsNotATileAndDoesNotThrow`**: Slot -1 returns false without throwing; pinned as undocumented behaviour that matches the three predicate copies it replaced, not as contract.
  - `MembersGui.isTileSlot(-1)` is false (row 0, column -1); the call does not throw.
- **`titlePatternMirrorsUpstream`**: `TITLE_PATTERN.pattern()` equals `.+: Members`.
  - `MembersGui.TITLE_PATTERN.pattern()` equals `".+: Members"`.
- **`pageButtonPatternsMirrorUpstream`**: The next and previous page patterns equal the literal strings `§a§lNext Page` and `§a§lPrevious Page`.
  - `MembersGui.NEXT_PAGE_PATTERN.pattern()` equals `"§a§lNext Page"`.
  - `MembersGui.PREVIOUS_PAGE_PATTERN.pattern()` equals `"§a§lPrevious Page"`.
- **`pageButtonSlotsMirrorUpstream`**: `NEXT_PAGE_SLOT` is 28 and `PREVIOUS_PAGE_SLOT` is 10, matching Wynntils' `getNextItemSlot()` and `getPreviousItemSlot()`.
  - `MembersGui.NEXT_PAGE_SLOT` equals `28`.
  - `MembersGui.PREVIOUS_PAGE_SLOT` equals `10`.
- **`pageTurned_falseWhileTheOldPageIsStillShowing`**: When the new scan is the old page itself (Metrafish in slot 2, Holidaze in slot 3), `pageTurned` is false.
  - `MembersGui.pageTurned(OLD_PAGE, OLD_PAGE)` is false, where `OLD_PAGE` is `Map.of(2, "Metrafish", 3, "Holidaze")`.
- **`pageTurned_trueOnceEveryTileIsNew`**: When both slots hold new names (Wenweia, Nazzae), `pageTurned` is true.
  - `MembersGui.pageTurned(OLD_PAGE, Map.of(2, "Wenweia", 3, "Nazzae"))` is true.
- **`pageTurned_falseOnAPartlyUpdatedPage`**: When slot 3 still holds Holidaze, `pageTurned` is false because the page has not finished turning.
  - `MembersGui.pageTurned(OLD_PAGE, Map.of(2, "Wenweia", 3, "Holidaze"))` is false.
- **`pageTurned_trueWhenATileNameRecursInAnotherSlot`**: When the old page's Metrafish reappears in a different slot (another member with the same tile name), `pageTurned` is still true.
  - `MembersGui.pageTurned(OLD_PAGE, Map.of(2, "Wenweia", 3, "Metrafish"))` is true.
- **`pageTurned_falseOnAnEmptyPage`**: An empty new scan is false, since an empty scan proves nothing while the tiles may be mid-update.
  - `MembersGui.pageTurned(OLD_PAGE, Map.of())` is false.

## `org.wynnvets.distribute.command`

### `NameOrSelectorArgumentTest`

Test: [`NameOrSelectorArgumentTest.java`](../src/test/java/org/wynnvets/distribute/command/NameOrSelectorArgumentTest.java) · Subject: [`NameOrSelectorArgument`](../src/client/java/org/wynnvets/distribute/command/NameOrSelectorArgument.java)

`NameOrSelectorArgument` is the Brigadier argument type for the `<name>` slot of `/wv distribute`; it reads one token so `@random` and plain usernames both parse. The suite pins its lexical rule: any non-empty run of non-space characters, with no selector whitelist and no quote handling, non-greedy so later arguments still parse. It drives the type through a real Brigadier `StringReader`, so no Minecraft client is needed.

- **`parse_plainUsername`**: `PlayerName` parses to itself.
  - `parse("PlayerName")` equals `"PlayerName"`. `parse(s)` is the local helper that returns `TYPE.parse(new StringReader(s))`, where `TYPE` is `NameOrSelectorArgument.nameOrSelector()`.
- **`parse_atSelector`**: `@random` parses to itself, which `StringArgumentType.string()` would reject.
  - `parse("@random")` equals `"@random"`.
- **`parse_anyAtTokenNotJustTheKnownSelectors`**: `@a`, `@anything` and `@@` all parse unchanged, since there is no selector whitelist.
  - For each input `"@a"`, `"@anything"`, `"@@"`: `parse(input)` equals `input`.
- **`parse_quoteIsAnOrdinaryCharacter`**: An unbalanced `"quoted` and a balanced `"a"` are returned with the quotes intact and no error.
  - `parse("\"quoted")` equals `"quoted` (leading double quote, unbalanced, kept as data).
  - `parse("\"a\"")` equals `"a"` (both quotes kept).
- **`parse_onlyTheLiteralSpaceTerminatesTheToken`**: A tab or newline between letters (a, tab, b and a, newline, b) does not end the token; each parses whole.
  - `parse("a` U+0009 `b")` equals `a` U+0009 `b` (the tab is kept inside the token).
  - `parse("a` U+000A `b")` equals `a` U+000A `b` (the newline is kept inside the token).
- **`parse_stopsAtTheFirstSpaceAndLeavesItUnconsumed`**: Parsing `@random emeralds 64` returns `@random`, leaves the cursor at 7 on the space, so Brigadier can consume the separator.
  - `TYPE.parse(reader)`, where `reader` is a `StringReader` over `@random emeralds 64`, equals `"@random"`.
  - `reader.getCursor()` equals `7` (the position of the space after `@random`).
  - `reader.peek()` equals `' '` (the space U+0020 is still unconsumed).
- **`parse_resumesOnTheNextTokenAfterTheSeparator`**: After `Metrafish` is read and the reader skips the space, a second parse returns `Holidaze`.
  - `TYPE.parse(reader)`, where `reader` is a `StringReader` over `Metrafish Holidaze`, equals `"Metrafish"`.
  - After `reader.skip()` consumes the one-character separator, `TYPE.parse(reader)` equals `"Holidaze"`.
- **`parse_emptyInputThrows`**: Empty input throws `CommandSyntaxException` with the raw message "Expected a member name or @selector".
  - `parse("")` throws `CommandSyntaxException`, and the exception is captured as `e`.
  - `e.getRawMessage().getString()` (the raw message text of `e`) equals `EMPTY_MESSAGE`, which is the constant `"Expected a member name or @selector"`.
- **`parse_leadingSpaceThrowsBecauseZeroCharactersWereRead`**: Input `" x"` throws the same exception, because the guard is "no characters read" rather than "input is empty".
  - `parse(" x")` (leading space) throws `CommandSyntaxException`, and the exception is captured as `e`.
  - `e.getRawMessage().getString()` equals `EMPTY_MESSAGE`.
- **`getExamples_areTheTwoDocumentedForms`**: `getExamples()` is `PlayerName` then `@random`.
  - `List.copyOf(TYPE.getExamples())` equals `List.of("PlayerName", "@random")`, in that order.
- **`nameOrSelector_returnsTheSharedInstance`**: Two calls to `nameOrSelector()` return the same instance.
  - The result of `NameOrSelectorArgument.nameOrSelector()` is the same instance as the result of a second `NameOrSelectorArgument.nameOrSelector()` call.

## `org.wynnvets.distribute.distributor`

### `GraidsDistributorTest`

Test: [`GraidsDistributorTest.java`](../src/test/java/org/wynnvets/distribute/distributor/GraidsDistributorTest.java) · Subject: [`GraidsDistributor`](../src/client/java/org/wynnvets/distribute/distributor/GraidsDistributor.java)

`GraidsDistributor` implements `@graids` distribution: it counts each member's guild-raid participation and splits the reward count in proportion, with the remainder handed out as random +1 bonuses. The suite pins `buildDistribution` (floor shares, queue order by frequency descending then name case-insensitively, total conserved, zero-share entries dropped, degenerate inputs yielding an empty queue) and `filterIndex` (the NoAspects opt-out applied to the name index by value). Both are package-private seams; `countGraidFrequencies` and `joinLore` are left out because they need Wynntils types. Helper methods extract names, counts and sums from the returned `Deque<DistributionQueue.Distribution>`, and the random bonus is handled by asserting invariants rather than exact counts where it matters.

- **`queueIsOrderedByFrequencyDescending`**: Frequencies low=1, high=3, mid=2 over 12 rewards queue the names as high, mid, low.
  - `names(queue)` (the `legacyName()` of each queued `Distribution` in iteration order) equals `["high", "mid", "low"]`, where `queue` is `GraidsDistributor.buildDistribution` over `low`=1, `high`=3, `mid`=2 with 12 rewards.
- **`equalFrequenciesTieBreakOnNameCaseInsensitively`**: `Metrafish` and `holidaze` tied at 2 queue as `holidaze`, `Metrafish`, so the tie-break ignores case (a plain ASCII compare would put `Metrafish` first).
  - `names(queue)` equals `["holidaze", "Metrafish"]`, where `queue` is `buildDistribution` over `Metrafish`=2, `holidaze`=2 with 4 rewards.
- **`sharesAreProportionalToParticipation`**: A 3:1 participation split of 8 rewards gives counts 6 and 2, not 4 and 4.
  - `names(queue)` equals `["a", "b"]`, where `queue` is `buildDistribution` over `a`=3, `b`=1 with 8 rewards.
  - `counts(queue)` (the `count()` of each queued `Distribution`, in order) equals `[6, 2]`.
- **`anExactDivisionLeavesNoRemainderAndIsFullyDeterministic`**: A 2:1 split of 6 gives exactly 4 and 2 in order a, b.
  - `names(queue)` equals `["a", "b"]`, where `queue` is `buildDistribution` over `a`=2, `b`=1 with 6 rewards.
  - `counts(queue)` equals `[4, 2]`.
- **`theRemainderIsHandedOutAsAPlusOneAndTheOrderStillHolds`**: Three equal participants over 4 rewards queue alphabetically, total 4, with sorted counts 1, 1, 2.
  - `names(queue)` equals `["a", "b", "c"]`, where `queue` is `buildDistribution` over `c`=1, `a`=1, `b`=1 with 4 rewards.
  - `sum(queue)` (the total of the queued counts) equals `4`.
  - `sorted` (a sorted copy of `counts(queue)`) equals `[1, 1, 2]`.
- **`everyParticipantDrawsAtMostOneBonus`**: Over 50 trials, three equal participants and 5 rewards never give anyone more than 2, and the total stays 5.
  - For each of 50 trials, for each `count` in `counts(queue)`, where `queue` is `buildDistribution` over `a`=1, `b`=1, `c`=1 with 5 rewards: `count` is at most `2`.
  - For each of the 50 trials: `sum(queue)` equals `5`.
- **`theTotalIsConservedAcrossAWideRange`**: For counts 1 to 120 across four participants with frequencies 5, 3, 2, 1, the queue total equals the count.
  - For each `count` 1 to 120: `sum(GraidsDistributor.buildDistribution(participants, count))` equals `count`, where `participants` is `a`=5, `b`=3, `c`=2, `d`=1.
- **`participantsWhoFloorToZeroAndMissTheBonusAreNotQueued`**: One reward across frequencies 10 and 1 queues a single entry totalling 1, either `a` or `b` depending on the random bonus.
  - `queue.size()` equals `1`, where `queue` is `buildDistribution` over `a`=10, `b`=1 with 1 reward.
  - `sum(queue)` equals `1`.
  - `names(queue)` is `["a"]` or `["b"]`, since the one reward goes to a random participant.
- **`aParticipantWithAZeroShareDisappearsWhileTheRestKeepTheirOrder`**: Three rewards across frequencies 11 and 1 total 3 and always put `a` first; it does not assert whether `b` is present.
  - `sum(queue)` equals `3`, where `queue` is `buildDistribution` over `a`=11, `b`=1 with 3 rewards.
  - `names(queue).get(0)` equals `"a"`.
- **`anEmptyFrequencyMapYieldsAnEmptyQueue`**: An empty frequency map yields an empty queue; this is guarded here, unlike `ObjectivesDistributor`.
  - `GraidsDistributor.buildDistribution(freq(), 5)` is empty, where `freq()` is an empty map.
- **`allZeroFrequenciesYieldAnEmptyQueue`**: Frequencies all 0 with 5 rewards yield an empty queue.
  - `GraidsDistributor.buildDistribution(freq("a", 0, "b", 0), 5)` is empty.
- **`zeroRewardsYieldAnEmptyQueue`**: A count of 0 yields an empty queue, since every share floors to 0 with no remainder.
  - `GraidsDistributor.buildDistribution(freq("a", 3, "b", 1), 0)` is empty.
- **`filterIndex_anEmptyExcludeSetHandsBackTheCallerSOwnMap`**: With an empty exclude set, `filterIndex` returns the very same map instance it was given.
  - `GraidsDistributor.filterIndex(idx, Set.of())` is the same instance as `idx`, where `idx` is `{"oldname": "OldName", "newname": "OldName"}`.
- **`filterIndex_anExcludeSetThatMatchesNothingStillCopies`**: An exclude set matching nothing returns an equal but distinct map, so the fast path depends on the set being empty, not on the outcome.
  - `out` (the result of `filterIndex(idx, Set.of("Nobody"))`, with `idx` = `{"solo": "Solo"}`) equals `idx`.
  - `out` is not the same instance as `idx`.
- **`filterIndex_excludingAMemberDropsBothOfTheirLookupKeys`**: Excluding canonical name `OldName` removes both its current and legacy lookup keys and leaves the other member's entry.
  - `GraidsDistributor.filterIndex(idx, Set.of("OldName"))` equals `{"other": "Other"}`, where `idx` is `{"oldname": "OldName", "newname": "OldName", "other": "Other"}`.
- **`filterIndex_theExcludeSetIsMatchedAgainstTheValueNotTheKey`**: Excluding the lookup key `oldname` filters nothing, because matching is against map values.
  - `GraidsDistributor.filterIndex(idx, Set.of("oldname"))` equals `idx`, where `idx` is `{"oldname": "OldName", "newname": "OldName"}`; excluding the key `oldname` removes nothing.
- **`filterIndex_excludingEveryMemberYieldsAnEmptyMap`**: Excluding both canonical names leaves an empty map.
  - `GraidsDistributor.filterIndex(index("a", "A", "b", "B"), Set.of("A", "B"))` is empty.
- **`filterIndex_anEmptyIndexYieldsAnEmptyMap`**: An empty index with a non-empty exclude set yields an empty map.
  - `GraidsDistributor.filterIndex(index(), Set.of("A"))` is empty, where `index()` is an empty map.

### `MemberSlotPresserTest`

Test: [`MemberSlotPresserTest.java`](../src/test/java/org/wynnvets/distribute/distributor/MemberSlotPresserTest.java) · Subject: [`MemberSlotPresser`](../src/client/java/org/wynnvets/distribute/distributor/MemberSlotPresser.java)

`MemberSlotPresser` drives the "press N to send X" interaction in the Members GUI, and its nested `Resource` enum names what can be sent (aspects, tomes, emeralds). The suite pins which resource the NoAspects opt-out list gates: aspects only, so tomes and emeralds are never withheld from opted-out members. It also fixes the number of enum values so a new resource must be given an answer here. The test touches only the nested enum, which loads without running the outer class's initializer.

- **`gatedByNoAspects_isTrueForAspectsOnly`**: `Resource` has exactly three values; `ASPECTS.gatedByNoAspects()` is true, `TOMES` and `EMERALDS` are false.
  - `MemberSlotPresser.Resource.values().length` equals `3`.
  - `MemberSlotPresser.Resource.ASPECTS.gatedByNoAspects()` is true.
  - `MemberSlotPresser.Resource.TOMES.gatedByNoAspects()` is false.
  - `MemberSlotPresser.Resource.EMERALDS.gatedByNoAspects()` is false.

### `ObjectivesDistributorTest`

Test: [`ObjectivesDistributorTest.java`](../src/test/java/org/wynnvets/distribute/distributor/ObjectivesDistributorTest.java) · Subject: [`ObjectivesDistributor`](../src/client/java/org/wynnvets/distribute/distributor/ObjectivesDistributor.java)

`ObjectivesDistributor` implements `@objectives` distribution: it reads each member tile's lore to decide who completed the guild objective, then splits the reward count evenly among the completers. The suite pins `hasCompletedObjective` (header-anchored, exact-match header, first non-blank line after it decides, `current >= total`) and `buildDistribution` (even split, random +1 remainder, zero-share recipients dropped, total conserved). Both are package-private seams taking plain strings or lists. Because `buildDistribution` shuffles, its assertions sort counts and names before comparing.

- **`header_isMatchedWithEqualsSoAnyDecorationDefeatsIt`**: The exact `Guild Objective:` header works; a trailing space, a `§7` prefix, or a missing colon each make the result false.
  - `ObjectivesDistributor.hasCompletedObjective(["Guild Objective:", "- Gather Ores: 200/200"])` is true, where `HEADER` is `"Guild Objective:"`.
  - `hasCompletedObjective(["Guild Objective: ", "- Gather Ores: 200/200"])` (header with a trailing space) is false.
  - `hasCompletedObjective(["§7Guild Objective:", "- Gather Ores: 200/200"])` (header with a `§7` colour-code prefix) is false.
  - `hasCompletedObjective(["Guild Objective", "- Gather Ores: 200/200"])` (header without the colon) is false.
- **`missingHeaderIsNotCompleted`**: An empty lore list, or a progress line with no header, is not completed.
  - `hasCompletedObjective([])` is false.
  - `hasCompletedObjective(["Rank: Recruit", "- Gather Ores: 200/200"])` is false; with no header line, the progress line is not read.
- **`headerWithNothingAfterItIsNotCompleted`**: A header alone, or a header followed only by blank lines, is not completed.
  - `hasCompletedObjective([HEADER])` is false.
  - `hasCompletedObjective([HEADER, "", " "])` is false; only empty and whitespace-only lines follow.
- **`blankSeparatorLinesAfterTheHeaderAreSkipped`**: Blank and whitespace-only lines between the header and `- Gather Ores: 200/200` do not stop the scan.
  - `hasCompletedObjective([HEADER, "", "   ", "- Gather Ores: 200/200"])` is true; empty and whitespace-only lines are skipped.
- **`aNonBlankNonMatchingLineAfterTheHeaderBailsImmediately`**: A non-matching line such as `- Streak: 76` after the header returns false even if a completed progress line follows.
  - `hasCompletedObjective([HEADER, "- Streak: 76", "- Gather Ores: 200/200"])` is false; the scan stops at `- Streak: 76` and never reads the next line.
  - `hasCompletedObjective([HEADER, "Objective complete!", "- Gather Ores: 200/200"])` is false.
- **`theStreakLineAloneIsNotProgress`**: A header followed only by `- Streak: 76` is not completed, since the line has no slash and fails the progress pattern.
  - `hasCompletedObjective([HEADER, "- Streak: 76"])` is false.
- **`completionIsGreaterOrEqualNotEqual`**: `200/200` and `240/200` are complete; `199/200` is not.
  - `hasCompletedObjective([HEADER, "- Ores: 200/200"])` is true.
  - `hasCompletedObjective([HEADER, "- Ores: 240/200"])` is true; overshooting still counts.
  - `hasCompletedObjective([HEADER, "- Ores: 199/200"])` is false.
- **`aZeroOfZeroObjectiveReadsAsComplete`**: `0/0` reads as complete, a consequence of the `>=` comparison.
  - `hasCompletedObjective([HEADER, "- Ores: 0/0"])` is true.
- **`anUnparseableNumberIsSwallowedRatherThanPropagated`**: `99999999999/1` overflows `parseInt` and yields false instead of throwing.
  - `hasCompletedObjective([HEADER, "- Ores: 99999999999/1"])` is false; the numerator overflows `int`, and the call returns without throwing.
- **`theFirstMatchingLineDecidesEvenIfALaterOneWouldDisagree`**: With `1/200` followed by `200/200`, the first progress line decides and the result is false.
  - `hasCompletedObjective([HEADER, "- Ores: 1/200", "- Logs: 200/200"])` is false; the first progress line decides.
- **`buildDistribution_evenSplitGivesEveryoneTheSameCount`**: Three completers and 6 rewards queue three entries, each count 2, each name once.
  - `queue.size()` equals `3`, where `queue` is `ObjectivesDistributor.buildDistribution(["a", "b", "c"], 6)`.
  - `sortedCounts(queue)` equals `[2, 2, 2]`, where `sortedCounts` is the sorted list of each queued `Distribution.count()`.
  - `names(queue)` (the sorted list of each queued `legacyName()`) equals `["a", "b", "c"]`.
- **`buildDistribution_remainderBecomesAPlusOneBonus`**: Three completers and 7 rewards give sorted counts 2, 2, 3, with all three names queued.
  - `queue.size()` equals `3`, where `queue` is `buildDistribution(["a", "b", "c"], 7)`.
  - `sortedCounts(queue)` equals `[2, 2, 3]`.
  - `names(queue)` equals `["a", "b", "c"]`.
- **`buildDistribution_recipientsWhoWouldGetZeroAreDropped`**: Five completers and 2 rewards queue only two entries, each with count 1.
  - `queue.size()` equals `2`, where `queue` is `buildDistribution(["a", "b", "c", "d", "e"], 2)`.
  - `sortedCounts(queue)` equals `[1, 1]`.
- **`buildDistribution_totalIsAlwaysConserved`**: For seven completers and totals 0 to 64, the queued counts always sum to the total.
  - For each `total` 0 to 64, with `completers` = `["a", "b", "c", "d", "e", "f", "g"]`: the sum of the counts in `buildDistribution(completers, total)` (named `sum`) equals `total`.
- **`buildDistribution_countsNeverDifferByMoreThanOneAmongQueuedRecipients`**: For seven completers and totals 1 to 64, the largest and smallest queued counts differ by at most 1.
  - For each `total` 1 to 64, with `completers` as above: `counts` (the sorted counts of `buildDistribution(completers, total)`) has last element minus first element `<= 1`.
- **`buildDistribution_zeroTotalYieldsAnEmptyQueue`**: A total of 0 yields an empty queue.
  - `ObjectivesDistributor.buildDistribution(["a", "b"], 0)` is empty.
- **`buildDistribution_dividesByZeroOnAnEmptyCompleterList`**: An empty completer list throws `ArithmeticException`; pinned as unguarded, with the caller's `isEmpty()` check as the only protection.
  - `ObjectivesDistributor.buildDistribution([], 5)` throws `ArithmeticException`.

### `RandomDistributorTest`

Test: [`RandomDistributorTest.java`](../src/test/java/org/wynnvets/distribute/distributor/RandomDistributorTest.java) · Subject: [`RandomDistributor`](../src/client/java/org/wynnvets/distribute/distributor/RandomDistributor.java)

`RandomDistributor` implements `@random` distribution, picking recipients from the wapi roster of legacy names. The suite covers its two pure helpers. `filterNames` applies the NoAspects opt-out to that roster before any picking; it is pinned for the identity return when nothing is excluded, order preservation, case-sensitive matching and the empty-result edges, using a fixed roster of Metrafish, Holidaze and Wenweia. `distinctIgnoringCase` keeps each tile name once, first spelling wins, and must fold case independently of the JVM default locale (checked under Turkish). Both are package-private seams.

- **`anEmptyExcludeSetHandsBackTheCallerSOwnList`**: With an empty exclude set, `filterNames` returns the same list instance it was given.
  - `RandomDistributor.filterNames(ROSTER, Set.of())` is the same instance as `ROSTER`, the fixture `["Metrafish", "Holidaze", "Wenweia"]`.
- **`anExcludeSetThatMatchesNothingStillCopies`**: Excluding `Nobody` returns an equal list that is a distinct instance, so the fast path depends on the set being empty.
  - `out` (the result of `filterNames(ROSTER, Set.of("Nobody"))`) equals `["Metrafish", "Holidaze", "Wenweia"]`.
  - `out` is not the same instance as `ROSTER`.
- **`anExcludedNameIsDroppedAndTheRestKeepTheirOrder`**: Excluding `Holidaze` leaves Metrafish, Wenweia in order.
  - `RandomDistributor.filterNames(ROSTER, Set.of("Holidaze"))` equals `["Metrafish", "Wenweia"]`.
- **`everyExcludedNameIsDropped`**: Excluding `Metrafish` and `Holidaze` leaves only Wenweia.
  - `RandomDistributor.filterNames(ROSTER, Set.of("Metrafish", "Holidaze"))` equals `["Wenweia"]`.
- **`matchingIsCaseSensitive`**: Excluding lowercase `holidaze` removes nothing, since matching is a plain `Set.contains`.
  - `RandomDistributor.filterNames(ROSTER, Set.of("holidaze"))` equals `["Metrafish", "Holidaze", "Wenweia"]`, so the lowercase `holidaze` removes nothing.
- **`excludingTheWholeRosterYieldsAnEmptyList`**: Excluding all three names yields an empty list.
  - `RandomDistributor.filterNames(ROSTER, Set.of("Metrafish", "Holidaze", "Wenweia"))` is empty.
- **`anEmptyRosterYieldsAnEmptyList`**: An empty roster with a non-empty exclude set yields an empty list.
  - `RandomDistributor.filterNames(List.of(), Set.of("Metrafish"))` is empty.
- **`distinctIgnoringCase_keepsTheFirstSpellingOfEachTileName`**: `distinctIgnoringCase` over Nazzae, Metrafish, nazzae, Holidaze, NAZZAE returns Nazzae, Metrafish, Holidaze: the first spelling of each name is kept and case-variant repeats are dropped.
  - `RandomDistributor.distinctIgnoringCase(List.of("Nazzae", "Metrafish", "nazzae", "Holidaze", "NAZZAE"))` equals `["Nazzae", "Metrafish", "Holidaze"]`: the first spelling of each name is kept, so `"nazzae"` and `"NAZZAE"` are dropped.
- **`distinctIgnoringCase_foldsAnIUnderATurkishDefaultLocale`**: With the JVM default locale set to Turkish (and restored afterwards), `METRAFISH` and `metrafish` still count as one name, so only `METRAFISH` is returned; a default-locale fold would turn the capital `I` into a dotless i and keep both.
  - With the default locale set to Turkish, `RandomDistributor.distinctIgnoringCase(List.of("METRAFISH", "metrafish"))` equals `["METRAFISH"]`: the capital `I` folds to an ASCII `i`, not a dotless one, so the two spellings are one name.

### `SplitDistributorTest`

Test: [`SplitDistributorTest.java`](../src/test/java/org/wynnvets/distribute/distributor/SplitDistributorTest.java) · Subject: [`SplitDistributor`](../src/client/java/org/wynnvets/distribute/distributor/SplitDistributor.java)

`SplitDistributor` runs the three distribution phases (`@graids`, `@objectives`, `@random`) back to back, and `splitCount` divides the total across those three pools. The suite pins that `splitCount` returns three pools, conserves the total, keeps the pools within one of each other, and hands the remainder to random distinct pools. It asserts exact arrays only where the shuffle does not run, and sweeps counts 0 to 255 (the Brigadier-reachable range). `splitCount` is a package-private seam.

- **`alwaysReturnsThreePools`**: For every count from 0 to 255, the result has length 3.
  - For each `count` 0 to 255: `SplitDistributor.splitCount(count).length` equals `3`.
- **`totalIsConservedAcrossTheWholeReachableRange`**: For every count from 0 to 255, the three pools sum to the count.
  - For each `count` 0 to 255: `sum(SplitDistributor.splitCount(count))` equals `count`, where `sum(pools)` is `pools[0] + pools[1] + pools[2]`.
- **`poolsNeverDifferByMoreThanOne`**: For every count from 0 to 255, the largest and smallest pool differ by at most 1.
  - For each `count` 0 to 255: `pools` (a sorted copy of `splitCount(count)`) has `pools[2] - pools[0] <= 1`.
- **`aMultipleOfThreeSplitsEvenlyAndDeterministically`**: Counts 0, 3 and 63 give exactly `{0,0,0}`, `{1,1,1}` and `{21,21,21}`.
  - For each `count` and expected array: `SplitDistributor.splitCount(count)` equals the array, in order: `0` gives `[0, 0, 0]`; `3` gives `[1, 1, 1]`; `63` gives `[21, 21, 21]`.
- **`oneLeftoverGoesToExactlyOneRandomPool`**: Count 7 gives sorted pools 2, 2, 3.
  - `sorted(SplitDistributor.splitCount(7))` equals `[2, 2, 3]`, where `sorted(pools)` is a sorted copy of `pools`.
- **`twoLeftoversGoToTwoDistinctPools`**: Count 8 gives sorted pools 2, 3, 3, so one pool cannot take both bonuses.
  - `sorted(SplitDistributor.splitCount(8))` equals `[2, 3, 3]`.
- **`countsBelowThreeGiveOneEachToThatManyPools`**: Count 1 gives sorted 0, 0, 1 and count 2 gives sorted 0, 1, 1.
  - For each `count` and expected array: `sorted(SplitDistributor.splitCount(count))` equals the array; `1` gives `[0, 0, 1]`; `2` gives `[0, 1, 1]`.
- **`theBonusReachesEveryPoolAcrossRepeatedDraws`**: Over 200 draws of count 1, each of the three pool indices receives the bonus at least once, ruling out a fixed-index remainder.
  - `seen[0] && seen[1] && seen[2]` is true, where `seen` (a `boolean[3]`) is set at index `i` whenever `pools[i]` equals `1` in any of 200 calls to `SplitDistributor.splitCount(1)`.
- **`negativeCountsSilentlyDropTheRemainder`**: Count -1 gives `{0,0,0}` (sum 0) and -4 gives `{-1,-1,-1}` (sum -3); pinned as undocumented, unreachable behaviour from truncating division.
  - For each `count` and expected array: `SplitDistributor.splitCount(count)` equals the array; `-1` gives `[0, 0, 0]`; `-4` gives `[-1, -1, -1]`.
  - For each `count` and expected sum: `sum(SplitDistributor.splitCount(count))` equals the value; `-1` gives `0`; `-4` gives `-3`.
- **`negativeMultiplesOfThreeAreConservedBecauseTheyHaveNoRemainder`**: Count -6 gives `{-2,-2,-2}` and the total is conserved.
  - `SplitDistributor.splitCount(-6)` equals `[-2, -2, -2]`.
  - `sum(SplitDistributor.splitCount(-6))` equals `-6`.

## `org.wynnvets.distribute.recipients`

### `NameResolverTest`

Test: [`NameResolverTest.java`](../src/test/java/org/wynnvets/distribute/recipients/NameResolverTest.java) · Subject: [`NameResolver`](../src/client/java/org/wynnvets/distribute/recipients/NameResolver.java)

`NameResolver` turns a wapi v3 guild payload into the names the Members GUI shows on its tiles (the legacy name where a member renamed, otherwise the current name). The suite pins its four string-in extractors: `extractAllLegacyNames`, `extractUuidToLegacyName`, `extractNameIndex` and `findLegacyName`. It fixes their deliberate asymmetries over malformed or uuid-less members, and that every parse failure is fail-open. All tests share one hand-written `ROSTER` JSON fixture covering a renamed member, a name collision, a uuid-less member and a non-object entry. An `@AfterEach` restores the default `Locale`, which one test sets to Turkish.

- **`extractAllLegacyNames_prefersLegacyAndFallsBackToCurrent`**: The roster yields Holidaze, Holidaze, Metrafish, OldNazzae, Broken, using the legacy name where present and the current name otherwise.
  - `names` (`NameResolver.extractAllLegacyNames(ROSTER)`, where `ROSTER` is the wapi v3 guild payload fixture) equals `["Holidaze", "Holidaze", "Metrafish", "OldNazzae", "Broken"]`, in payload order: Wenweia's legacy name, Holidaze (no legacy name, so the key), Metrafish (key), Nazzae's legacy name, Broken (key).
- **`extractAllLegacyNames_keepsMalformedEntriesUnderTheirCurrentName`**: The malformed `Broken` entry still contributes its name, unlike in `extractUuidToLegacyName`.
  - `NameResolver.extractAllLegacyNames(ROSTER)` contains `"Broken"`.
- **`extractAllLegacyNames_canProduceDuplicates`**: `Holidaze` appears twice, because two distinct members display that name and the list is not deduplicated.
  - The count of `"Holidaze"` in `names` (`extractAllLegacyNames(ROSTER)`) equals `2`.
- **`extractUuidToLegacyName_keysOnTheNormalisedUuid`**: Keys are lowercased, undashed UUIDs mapping to tile names: Wenweia's UUID to Holidaze, Holidaze's to Holidaze, Metrafish's to Metrafish.
  - `map.get("085d0e5829d444aa837992a4568a59d6")` equals `"Holidaze"`, where `map` is `NameResolver.extractUuidToLegacyName(ROSTER)` and the key is Wenweia's uuid with dashes removed and lowercased.
  - `map.get("fa8aa7004538485fbf91325263606995")` equals `"Holidaze"`.
  - `map.get("bb171c6870804d02be7bcc2d5dcbcd82")` equals `"Metrafish"`.
- **`extractUuidToLegacyName_skipsMalformedAndUuidlessEntries`**: The map has only three entries; `Broken` (no member object) and `Nazzae` (no uuid) are absent.
  - `map.size()` equals `3`.
  - `map` does not contain the value `"Broken"`.
  - `map` does not contain the value `"OldNazzae"`.
- **`extractNameIndex_registersBothTheCurrentAndTheLegacyForm`**: Lowercase keys `wenweia` and `holidaze` both map to Holidaze, and `metrafish` maps to Metrafish.
  - `index.get("wenweia")` equals `"Holidaze"`, where `index` is `NameResolver.extractNameIndex(ROSTER)`.
  - `index.get("holidaze")` equals `"Holidaze"`.
  - `index.get("metrafish")` equals `"Metrafish"`.
- **`extractNameIndex_keysAreLowercased`**: The key `wenweia` exists and the raw-case key `Wenweia` does not.
  - `index` contains the key `"wenweia"`.
  - `index` does not contain the key `"Wenweia"`.
- **`extractNameIndex_foldsWithLocaleRootSoATurkishClientIndexesTheSameKeys`**: With the default locale set to Turkish, the index has key `metrafish` and lacks the dotless-i key `metrafısh`. As written this cannot fail: `Metrafish` has no capital `I`, and the Turkish fold only changes a capital `I` (to U+0131), so even a default-locale fold would produce `metrafish`. The test comment claims the name contains a capital I, which it does not.
  - With the default locale set to Turkish (`tr`) for this test: `index.get("metrafish")` equals `"Metrafish"`. This cannot fail as written: `Metrafish` has no capital `I`, and the Turkish fold only changes a capital `I`, so the fold gives `metrafish` even without `Locale.ROOT`.
  - With the same locale: `index` does not contain the key `"metrafısh"` (the dotless-i form). This cannot fail as written either, since no capital `I` exists to fold to U+0131; the test comment wrongly says the name contains a capital I.
- **`extractNameIndex_malformedEntriesStillGetAnEntry`**: The index maps `broken` to `Broken` even though its payload value is not an object.
  - `NameResolver.extractNameIndex(ROSTER).get("broken")` equals `"Broken"`.
- **`findLegacyName_resolvesACurrentNameToItsLegacyName`**: Looking up `Wenweia` returns Holidaze.
  - `NameResolver.findLegacyName(ROSTER, "Wenweia")` equals `"Holidaze"`.
- **`findLegacyName_isCaseInsensitiveOnBothSides`**: `wenweia` and `WENWEIA` both resolve to Holidaze.
  - `NameResolver.findLegacyName(ROSTER, "wenweia")` equals `"Holidaze"`.
  - `NameResolver.findLegacyName(ROSTER, "WENWEIA")` equals `"Holidaze"`.
- **`findLegacyName_acceptsALegacyNameVerbatim`**: Looking up `metrafish` returns `Metrafish`, the payload's spelling.
  - `NameResolver.findLegacyName(ROSTER, "metrafish")` equals `"Metrafish"`.
- **`findLegacyName_payloadOrderDecidesACollisionBetweenALegacyAndACurrentName`**: Looking up `holidaze`, which is one member's legacy name and another's current name, returns Holidaze; both candidates give the same answer, so the result does not show which one won.
  - `NameResolver.findLegacyName(ROSTER, "holidaze")` equals `"Holidaze"`; the owner bucket's legacy match for Wenweia is reached before the chief bucket's current-name match for Holidaze.
- **`findLegacyName_skipsMalformedEntriesSoTheirNameFallsBackToTheInput`**: Looking up `broken` returns `broken` unchanged (input case), because the malformed entry is skipped.
  - `NameResolver.findLegacyName(ROSTER, "broken")` equals `"broken"`; the malformed `Broken` entry is never matched, so the input is returned unchanged.
- **`findLegacyName_unknownInputIsReturnedUnchanged`**: An unknown name `Nobody` is returned as given.
  - `NameResolver.findLegacyName(ROSTER, "Nobody")` equals `"Nobody"`.
- **`malformedPayloadsYieldEmptyResultsRatherThanThrowing`**: For six bad bodies (empty, non-JSON, `[]`, `{}`, `members` as a number, rank as a number), the extractors return empty results and `findLegacyName` returns its input.
  - For each `body` in `""`, `"not json at all"`, `"[]"`, `"{}"`, `{"members": 7}`, `{"members": {"owner": 3}}`: `NameResolver.extractAllLegacyNames(body)` equals `[]`.
  - For each `body` in the same six payloads: `NameResolver.extractUuidToLegacyName(body)` equals `{}`.
  - For each `body` in the same six payloads: `NameResolver.extractNameIndex(body)` equals `{}`.
  - For each `body` in the same six payloads: `NameResolver.findLegacyName(body, "Wenweia")` equals `"Wenweia"`.
- **`nullPayloadIsAlsoFailOpen`**: A null body gives an empty list from `extractAllLegacyNames` and returns the input from `findLegacyName`.
  - `NameResolver.extractAllLegacyNames(null)` equals `[]`.
  - `NameResolver.findLegacyName(null, "Wenweia")` equals `"Wenweia"`.
- **`theTotalBucketIsSkippedByName`**: The `total` bucket contributes no name, and a bucket literally named `total` is skipped whatever it contains.
  - `NameResolver.extractAllLegacyNames(ROSTER)` does not contain `"total"`.
  - `NameResolver.extractAllLegacyNames(...)` on `{"members": {"total": {"Metrafish": {"legacyName": "A"}}}}` equals `[]`.

### `NoAspectsFilterTest`

Test: [`NoAspectsFilterTest.java`](../src/test/java/org/wynnvets/distribute/recipients/NoAspectsFilterTest.java) · Subject: [`NoAspectsFilter`](../src/client/java/org/wynnvets/distribute/recipients/NoAspectsFilter.java)

`NoAspectsFilter` builds the set of tile names excluded from aspect distribution: it parses an opt-out list of UUIDs, then translates them to legacy names using the guild roster. The suite pins `parseUuids` and `buildExcludedNames`, plus the ungated arm of `fetchExcludedLegacyNames`, which makes no request. Both halves fail open, so an empty exclude set means nobody is filtered. The one sharp edge is that a single unusable `uuid` value discards the whole parsed list. The tests use package-private seams with hand-written JSON and dashed or normalised UUID constants, and never call the gated network arm.

- **`fetchExcludedLegacyNames_ungated_isAnEmptySetAlreadyComplete`**: With gating off, the returned future is already done and holds an empty set.
  - `excluded.isDone()` is true, where `excluded` is `NoAspectsFilter.fetchExcludedLegacyNames(false)`.
  - `excluded.join()` equals `Set.of()`.
- **`parseUuids_normalisesEveryEntry`**: A dashed uppercase UUID in the payload comes back as a lowercase undashed one.
  - `uuids` (`NoAspectsFilter.parseUuids` on a one-entry array whose `uuid` is `BB171C68-7080-4D02-BE7B-CC2D5DCBCD82`) equals `Set.of("bb171c6870804d02be7bcc2d5dcbcd82")`.
- **`parseUuids_dedupesAcrossSpellings`**: The same UUID listed dashed and undashed collapses to one entry.
  - `uuids.size()` equals `1`, for an input listing the same account dashed and undashed.
- **`parseUuids_skipsEntriesWithNoUsableUuid`**: Entries with a missing, null or empty uuid, plus a string and a number, are skipped while a valid neighbour is kept.
  - `uuids` equals `Set.of("fa8aa7004538485fbf91325263606995")`, for an input array holding `{"username": "NoUuid"}`, `{"uuid": null}`, `{"uuid": ""}`, the string `"not an object"`, the number `123`, and `{"uuid": "fa8aa700-4538-485f-bf91-325263606995"}`.
- **`parseUuids_coercesANumericUuidRatherThanRejectingIt`**: A numeric `uuid` of 123 yields the key `123` instead of being rejected.
  - `NoAspectsFilter.parseUuids([{"uuid": 123}])` equals `Set.of("123")`.
- **`parseUuids_oneUnusableUuidValueDiscardsTheEntireList`**: A valid entry followed by an object-valued uuid returns an empty set, because the catch covers the whole loop.
  - `uuids` equals `Set.of()`, for the input `[{"uuid": "BB171C68-7080-4D02-BE7B-CC2D5DCBCD82"}, {"uuid": {"nested": 1}}]`; the valid first entry is discarded along with the object-valued second one.
- **`parseUuids_failsOpenOnAnythingThatIsNotAJsonArray`**: Empty, non-JSON, `{}`, an object wrapping an empty array, `null`, a string, a number and a null argument all return an empty set.
  - For each `body` in `""`, `"not json"`, `"{}"`, `{"uuids": []}`, `"null"`, `"a string"` (a JSON string), `"7"`: `NoAspectsFilter.parseUuids(body)` equals `Set.of()`.
  - `NoAspectsFilter.parseUuids(null)` equals `Set.of()`.
- **`parseUuids_emptyArrayIsAnEmptySetNotAFailure`**: `[]` returns an empty set.
  - `NoAspectsFilter.parseUuids("[]")` equals `Set.of()`.
- **`buildExcludedNames_translatesUuidsToTileNames`**: Two opted-out UUIDs map to OldMetrafish and Holidaze, and a third roster member is not included.
  - `names` (`NoAspectsFilter.buildExcludedNames(uuids, map)`, with `uuids` = `{"bb171c6870804d02be7bcc2d5dcbcd82", "fa8aa7004538485fbf91325263606995"}` and `map` = those two keys mapped to `"OldMetrafish"` and `"Holidaze"`, plus `"085d0e5829d444aa837992a4568a59d6"` mapped to `"Wenweia"` and not in `uuids`) equals `Set.of("OldMetrafish", "Holidaze")`.
- **`buildExcludedNames_dropsUuidsThatAreNotInTheCurrentGuild`**: An opted-out UUID missing from the roster map is silently ignored.
  - `names` equals `Set.of("OldMetrafish")`, for `uuids` = `{"bb171c6870804d02be7bcc2d5dcbcd82", "deadbeefdeadbeefdeadbeefdeadbeef"}` and `map` = `{"bb171c6870804d02be7bcc2d5dcbcd82": "OldMetrafish"}`.
- **`buildExcludedNames_collapsesTwoUuidsSharingATileName`**: Two UUIDs mapping to the same name `Nazzae` produce a one-element set.
  - `names` equals `Set.of("Nazzae")`, for two uuids that both map to `"Nazzae"`.
- **`buildExcludedNames_failsOpenWhenEitherSideIsEmpty`**: An empty UUID set, an empty roster map, or both, yields an empty result.
  - `NoAspectsFilter.buildExcludedNames(Set.of(), {"bb171c6870804d02be7bcc2d5dcbcd82": "A"})` is empty.
  - `NoAspectsFilter.buildExcludedNames({"bb171c6870804d02be7bcc2d5dcbcd82"}, Map.of())` is empty.
  - `NoAspectsFilter.buildExcludedNames(Set.of(), Map.of())` is empty.
- **`buildExcludedNames_lookupIsExactSoBothSidesMustBeNormalised`**: A dashed UUID and an uppercase map key each fail to match the normalised entry, so the result is empty.
  - `NoAspectsFilter.buildExcludedNames({"BB171C68-7080-4D02-BE7B-CC2D5DCBCD82"}, {"bb171c6870804d02be7bcc2d5dcbcd82": "OldMetrafish"})` equals `Set.of()`; the dashed key does not match.
  - `NoAspectsFilter.buildExcludedNames({"bb171c6870804d02be7bcc2d5dcbcd82"}, {uppercase of "bb171c6870804d02be7bcc2d5dcbcd82": "OldMetrafish"})` equals `Set.of()`; the case-mismatched key does not match. The uppercase form is `NORMALISED.toUpperCase(Locale.ROOT)`.
- **`parseUuidsFeedsBuildExcludedNamesWithoutFurtherNormalisation`**: Parsing a dashed UUID and passing the result straight to `buildExcludedNames` finds `OldMetrafish` in a normalised-key map.
  - `NoAspectsFilter.buildExcludedNames(uuids, {"bb171c6870804d02be7bcc2d5dcbcd82": "OldMetrafish"})` equals `Set.of("OldMetrafish")`, where `uuids` is `parseUuids` of a one-entry array whose `uuid` is `BB171C68-7080-4D02-BE7B-CC2D5DCBCD82`.
- **`anEmptyResultIsTheSharedImmutableInstanceOnEveryFailurePath`**: Parsing `garbage`, `{}`, and a list with an object-valued uuid all return the same `Set.of()` instance, covering both the guard and the catch exit.
  - `NoAspectsFilter.parseUuids("garbage")` is the same instance as `NoAspectsFilter.parseUuids("{}")`.
  - `NoAspectsFilter.parseUuids("{}")` is the same instance as `NoAspectsFilter.parseUuids([{"uuid": {"nested": 1}}])`.

## `org.wynnvets.fetcher.lookup`

### `MojangCooldownTest`

Test: [`MojangCooldownTest.java`](../src/test/java/org/wynnvets/fetcher/lookup/MojangCooldownTest.java) · Subject: [`MojangCooldown`](../src/client/java/org/wynnvets/fetcher/lookup/MojangCooldown.java)

`MojangCooldown` is a static cooldown gate: after a 429 from the legacy Mojang endpoint, the lookup provider skips that host until the recorded cutoff passes. The suite pins the default state, the 60 second default, explicit durations, the rule that the longest cutoff wins, `clear`, and the debug force switch. It reads the real wall clock and uses bounded ranges rather than exact values, and an `@AfterEach` resets the force flag and cooldown because the state is static.

- **`defaultState_notCoolingDown_remainingMsZero`**: With nothing recorded, `isCoolingDown()` is false and `remainingMs()` is 0.
  - `MojangCooldown.isCoolingDown()` is false on a fresh state (no 429 recorded, no force override; the `@AfterEach` resets both).
  - `MojangCooldown.remainingMs()` equals `0L`.
- **`record429_nullDuration_appliesDefault60s`**: Recording a null duration puts the gate in cooldown, with remaining time positive and at most 60,000 ms.
  - `MojangCooldown.isCoolingDown()` is true after `MojangCooldown.record429(null)`.
  - `remaining` (`MojangCooldown.remainingMs()` read straight after the record) is greater than `0L`.
  - `remaining` is at most `60_000L` (the 60 s default for a null duration).
- **`record429_explicitDuration_applies`**: Recording 120 seconds gives remaining time above 60,000 and at most 120,000 ms.
  - `remaining` (after `record429(Duration.ofSeconds(120))`) is greater than `60_000L`.
  - `remaining` (same read) is at most `120_000L`.
- **`record429_longestWins_neverShortens`**: Recording 30 seconds then 5 seconds leaves more than 25,000 ms remaining, so the shorter record does not shrink the cutoff.
  - `remaining` (after `record429(30 s)` then `record429(5 s)`) is greater than `25_000L`, so the 5 s record did not shorten the 30 s cooldown.
- **`clear_resetsState`**: After a 60 second record, `clear()` returns the gate to not cooling down with 0 remaining.
  - `MojangCooldown.isCoolingDown()` is true after `record429(Duration.ofSeconds(60))`.
  - `MojangCooldown.isCoolingDown()` is false after `MojangCooldown.clear()`.
  - `MojangCooldown.remainingMs()` equals `0L` after `clear()`.
- **`forceCooldown_overridesActualState`**: Forcing the cooldown with nothing recorded makes `isCoolingDown()` true, `remainingMs()` `Long.MAX_VALUE`, and `isForceCooldown()` true.
  - `MojangCooldown.isCoolingDown()` is true after `setForceCooldown(true)`, with no 429 recorded.
  - `MojangCooldown.remainingMs()` equals `Long.MAX_VALUE` under the force override.
  - `MojangCooldown.isForceCooldown()` is true.
- **`forceCooldown_unset_restoresActualState`**: Setting force on then off returns to not cooling down with 0 remaining.
  - `MojangCooldown.isCoolingDown()` is false after `setForceCooldown(true)` then `setForceCooldown(false)`.
  - `MojangCooldown.remainingMs()` equals `0L` after that pair of calls.

## `org.wynnvets.fetcher.ondemand`

### `ReturnersRosterProbeTest`

Test: [`ReturnersRosterProbeTest.java`](../src/test/java/org/wynnvets/fetcher/ondemand/ReturnersRosterProbeTest.java) · Subject: [`ReturnersRosterProbe`](../src/client/java/org/wynnvets/fetcher/ondemand/ReturnersRosterProbe.java)

`ReturnersRosterProbe` checks whether a UUID is in a guild's wapi roster, and returns membership plus a best-effort rank and legacy name. The suite pins `analyzeRoster`: membership comes from a shape-agnostic walk of the whole payload, while rank (capitalised) and legacy name come only from the `members.<rank>.<name>` shape. The HTTP methods are not covered. Payloads are hand-written JSON parsed through the shared `Json.GSON`, some deliberately departing from wapi's real shape.

- **`member_rankIsCapitalisedAndLegacyNameRead`**: A member under `owner` with a `legacyName` is in the guild, with rank `Owner` and that legacy name.
  - `m.inGuild()` is true, where `m` = `analyzeRoster` of a payload with `members.owner` holding `Metrafish` (uuid `METRAFISH`, `legacyName` `OldMetrafish`) and `members.chief` holding `Holidaze`, looked up with `METRAFISH_KEY` (undashed Metrafish uuid).
  - `m.rank()` equals `"Owner"` (the bucket `owner`, capitalised).
  - `m.legacyName()` equals `"OldMetrafish"`.
- **`member_withoutLegacyName`**: A member under `chief` with no `legacyName` has rank `Chief` and a null legacy name.
  - `m.inGuild()` is true, where `m` = `analyzeRoster` of a payload with `members.chief.Metrafish` (uuid `METRAFISH`, no `legacyName`), looked up with `METRAFISH_KEY`.
  - `m.rank()` equals `"Chief"`.
  - `m.legacyName()` is null.
- **`member_withNullLegacyName`**: A JSON-null `legacyName` reads as none (null) and the rank is still `Chief`.
  - `m.inGuild()` is true, where `m` = `analyzeRoster` of a payload with `members.chief.Metrafish` (uuid `METRAFISH`, `"legacyName": null`), looked up with `METRAFISH_KEY`.
  - `m.rank()` equals `"Chief"`.
  - `m.legacyName()` is null.
- **`absent_isNotInGuild`**: A UUID that is not in the payload gives not-in-guild with null rank and legacy name.
  - `m.inGuild()` is false, where `m` = `analyzeRoster` of a payload listing only `Holidaze` under `members.chief`, looked up with `METRAFISH_KEY`.
  - `m.rank()` is null.
  - `m.legacyName()` is null.
- **`membershipComesFromTheWholePayload_rankOnlyFromTheMembersShape`**: A UUID appearing only as a key outside `members` counts as in the guild, with null rank and legacy name.
  - `m.inGuild()` is true, where `m` = `analyzeRoster` of `{"elsewhere": {METRAFISH: 1}}` (Metrafish's uuid as a key outside `members`), looked up with `METRAFISH_KEY`.
  - `m.rank()` is null.
  - `m.legacyName()` is null.
- **`anObjectValuedTotalBucketIsSkipped`**: An object-valued `total` bucket holding the member is skipped by name, so rank is `Recruit` and legacy name comes from the `recruit` bucket.
  - `m.rank()` equals `"Recruit"`, where `m` = `analyzeRoster` of a payload with an object-valued `members.total` (Metrafish, `legacyName` `FromTotal`) and `members.recruit` (Metrafish, `legacyName` `FromRecruit`), looked up with `METRAFISH_KEY`; the `total` bucket is skipped.
  - `m.legacyName()` equals `"FromRecruit"` (the `recruit` entry, not the skipped `total` one).
- **`nonObjectMembersAreSkipped_andUpperCaseDashedMatchesUndashed`**: A non-object `Broken` entry is skipped, and an uppercase dashed UUID in the payload matches the undashed lowercase key, giving rank `Owner`.
  - `m.inGuild()` is true, where `m` = `analyzeRoster` of a payload with `members.owner` holding `"Broken": 5` (a non-object, skipped) and Metrafish under the upper-cased dashed uuid `METRAFISH.toUpperCase(Locale.ROOT)`, looked up with the undashed lower-case `METRAFISH_KEY`.
  - `m.rank()` equals `"Owner"`.
- **`nullPayload_isNotInGuild`**: A null payload gives not-in-guild.
  - `ReturnersRosterProbe.analyzeRoster(null, METRAFISH_KEY).inGuild()` is false.

### `TimeLabelsTest`

Test: [`TimeLabelsTest.java`](../src/test/java/org/wynnvets/fetcher/ondemand/TimeLabelsTest.java) · Subject: [`TimeLabels`](../src/client/java/org/wynnvets/fetcher/ondemand/TimeLabels.java)

`TimeLabels` formats relative dates and ages: a day-level date label, a coarse years/months/days label, and a seconds-based age. The suite pins `humanizeAge` at each unit boundary, and the two date labels on inputs that cannot straddle a unit boundary on any calendar day. The String overloads of the date labels read `LocalDate.now()` themselves, so the `assertLabel` helper reads the date before and after the call and skips the assertion with `assumeTrue` if midnight passed in between. Inputs are date strings derived from the same default-zone date. The overloads that take a `LocalDate` and an explicit `today`, and `toLocalDate(Instant, ZoneId)`, read no clock: they are pinned with the fixed zone `BUENOS_AIRES` (America/Argentina/Buenos_Aires, UTC-3 all year) and a fixed `today`, including the evening on which the UTC date is a day ahead of the local one.

Asserting helpers:

- **`assertLabel`**: Given `want`, a date-shifting function `from` and a label function `label`: reads `before` = `LocalDate.now()`, computes `got` = `label` applied to `from(before).toString()`, then skips the test unless `LocalDate.now()` still equals `before` (no midnight crossing), then asserts `got` equals `want`.

Tests:

- **`humanizeAge_unitBoundaries`**: Seconds map to 0s, 59s, 1m, 59m, 1h, 23h, 1d, 29d, then 30 days gives "1 month", 60 days "2 months", 365 days minus a second "12 months", 365 days "1 year", 730 days "2 years".
  - `TimeLabels.humanizeAge(s)` equals the expected string, for each `s` in seconds (`DAY` = 86400): 0 -> `"0s"`; 59 -> `"59s"`; 60 -> `"1m"`; 3599 -> `"59m"`; 3600 -> `"1h"`; `DAY - 1` -> `"23h"`; `DAY` -> `"1d"`; `DAY * 30 - 1` -> `"29d"`; `DAY * 30` -> `"1 month"`; `DAY * 60` -> `"2 months"`; `DAY * 365 - 1` -> `"12 months"`; `DAY * 365` -> `"1 year"`; `DAY * 730` -> `"2 years"`.
- **`humanizeAge_negativeReadsAsZero`**: An age of -5 seconds reads as `0s`.
  - `TimeLabels.humanizeAge(-5)` equals `"0s"`.
- **`toRelativeDateLabel_todayPastAndFutureDays`**: Today, yesterday and 3 days ago give "today", "yesterday", "3 days ago"; tomorrow and 3 days ahead give "tomorrow" and "in 3 days".
  - `assertLabel` on `TimeLabels::toRelativeDateLabel`: for the date string of today shifted by an offset, the label equals the expected text. Today -> `"today"`; today minus 1 day -> `"yesterday"`; minus 3 days -> `"3 days ago"`; plus 1 day -> `"tomorrow"`; plus 3 days -> `"in 3 days"`.
- **`toCoarseRelativeDateLabel_yearsMonthsThenDays`**: 1 and 11 years back give "1 year ago" and "11 years ago", 1 and 5 months give "1 month ago" and "5 months ago", and 27 days and 1 day fall through to "27 days ago" and "yesterday".
  - `assertLabel` on `TimeLabels::toCoarseRelativeDateLabel`: for the date string of today shifted back, the label equals the expected text. Minus 1 year -> `"1 year ago"`; minus 11 years -> `"11 years ago"`; minus 1 month -> `"1 month ago"`; minus 5 months -> `"5 months ago"`; minus 27 days -> `"27 days ago"`; minus 1 day -> `"yesterday"`.
- **`toLocalDate_cutsTheInstantAtTheGivenZone`**: An instant is cut to the calendar date in the given zone, and local midnight in UTC-3 is the boundary.
  - `TimeLabels.toLocalDate(Instant.parse("2026-10-22T01:07:00Z"), zone)` equals 2026-10-22 for `UTC` and 2026-10-21 for `BUENOS_AIRES`.
  - For `BUENOS_AIRES`: `2026-10-22T02:59:59Z` gives 2026-10-21 and `2026-10-22T03:00:00Z` gives 2026-10-22.
- **`toRelativeDateLabel_explicitToday`**: With a fixed today of 2026-10-21, the same day, yesterday and 3 days back give "today", "yesterday", "3 days ago"; tomorrow and 3 days ahead give "tomorrow" and "in 3 days".
  - `TimeLabels.toRelativeDateLabel(date, today)` equals the expected text: `today` -> `"today"`; minus 1 day -> `"yesterday"`; minus 3 days -> `"3 days ago"`; plus 1 day -> `"tomorrow"`; plus 3 days -> `"in 3 days"`.
- **`toRelativeDateLabel_lateEveningJoinReadsToday`**: The in-game case. A join at `2026-10-22T01:07:00Z`, cut to the `BUENOS_AIRES` date and checked on local 2026-10-21, reads "today", and "yesterday" on the next local day.
  - `toRelativeDateLabel(toLocalDate(join, BUENOS_AIRES), 2026-10-21)` equals `"today"`.
  - The same date against 2026-10-22 equals `"yesterday"`.
- **`toCoarseRelativeDateLabel_explicitTodayYearsMonthsThenDays`**: With a fixed today of 2026-10-13, 1 and 11 years back give "1 year ago" and "11 years ago", 1 and 5 months give "1 month ago" and "5 months ago", 27 days gives "27 days ago", and today gives "today".
  - `TimeLabels.toCoarseRelativeDateLabel(date, today)` equals the expected text: minus 1 year -> `"1 year ago"`; minus 11 years -> `"11 years ago"`; minus 1 month -> `"1 month ago"`; minus 5 months -> `"5 months ago"`; minus 27 days -> `"27 days ago"`; the same day -> `"today"`.
- **`toCoarseRelativeDateLabel_localAnniversaryReadsTheFullYear`**: A first join at 22:00 UTC-3 on 13 October 2015 (`2015-10-14T01:00:00Z`), checked on 2026-10-13, reads 11 years at its local date and 10 years at its UTC date.
  - `toCoarseRelativeDateLabel(toLocalDate(firstJoin, BUENOS_AIRES), 2026-10-13)` equals `"11 years ago"`.
  - `toCoarseRelativeDateLabel(toLocalDate(firstJoin, UTC), 2026-10-13)` equals `"10 years ago"`.
- **`dateLabels_returnUnparseableTextAsIs`**: Both date-label methods return `not-a-date` unchanged.
  - `TimeLabels.toRelativeDateLabel("not-a-date")` equals `"not-a-date"`.
  - `TimeLabels.toCoarseRelativeDateLabel("not-a-date")` equals `"not-a-date"`.

## `org.wynnvets.fetcher.polling`

### `PolledJsonMapTest`

Test: [`PolledJsonMapTest.java`](../src/test/java/org/wynnvets/fetcher/polling/PolledJsonMapTest.java) · Subject: [`PolledJsonMap`](../src/client/java/org/wynnvets/fetcher/polling/PolledJsonMap.java)

`PolledJsonMap` is a flat string-to-string JSON map fetched from the VetsMod server on a schedule and published as an immutable snapshot, with two production instances: `WYNN_ALIASES` (keys case-folded) and `GUILD_ROSTER` (keys left as-is). The suite pins that the key normalizer applies at both ingest and lookup, the cold-cache contract (empty snapshot, null get), snapshot stability across polls, and parse-failure and value-filtering behaviour. Only `parse(String)` is exercised, with no network. The tests use the real production constants and reset both to empty after each case, plus one fresh instance for the cold case.

- **`aliasKeysAreFoldedOnBothSides`**: After parsing key `OldNamE`, the alias map resolves it under `OldNamE`, `oldname` and `OLDNAME`.
  - `PolledJsonMap.WYNN_ALIASES.get(k)` equals `"uuid-1"` for `k` = `"OldNamE"`, `"oldname"`, `"OLDNAME"`, after `WYNN_ALIASES.parse("{\"OldNamE\": \"uuid-1\"}")`.
- **`aliasKeysAreStoredFolded`**: The alias snapshot is exactly `{oldname: uuid-1}`, proving ingest folded the key.
  - `PolledJsonMap.WYNN_ALIASES.snapshot()` equals `Map.of("oldname", "uuid-1")` after parsing `{"OldNamE": "uuid-1"}` (the key is stored lower-cased).
- **`rosterKeysAreNotFolded`**: The roster keeps `A1B2-C3D4` in its snapshot, resolves it to `Metrafish` by exact key, and returns null for the lowercase spelling.
  - `PolledJsonMap.GUILD_ROSTER.snapshot()` equals `Map.of("A1B2-C3D4", "Metrafish")` after parsing `{"A1B2-C3D4": "Metrafish"}`.
  - `PolledJsonMap.GUILD_ROSTER.get("A1B2-C3D4")` equals `"Metrafish"`.
  - `PolledJsonMap.GUILD_ROSTER.get("a1b2-c3d4")` is null.
- **`aColdInstanceIsAnEmptyMapAndANullGet`**: A freshly constructed, never-parsed instance has an empty snapshot and returns null from `get`.
  - `cold.snapshot()` is empty, where `cold` is a new `PolledJsonMap` that has never been parsed.
  - `cold.get("anything")` is null.
- **`anEmptyPayloadPublishesAnEmptyMap`**: Parsing `{}` after a populated payload replaces the roster with an empty map and `get("uuid-1")` becomes null.
  - `PolledJsonMap.GUILD_ROSTER.snapshot()` is empty after parsing `{"uuid-1": "Metrafish"}` and then `{}`.
  - `PolledJsonMap.GUILD_ROSTER.get("uuid-1")` is null after that pair of parses.
- **`snapshotHandsBackOneStableReference`**: Two snapshots between polls are the same instance; a held snapshot is unchanged after the next parse, which publishes a new map.
  - `first` (the first `GUILD_ROSTER.snapshot()` read, after parsing `{"uuid-1": "Metrafish"}`) is the same instance as a second `snapshot()` read with no parse in between.
  - `first` still equals `Map.of("uuid-1", "Metrafish")` after the next parse of `{"uuid-2": "Holidaze"}`.
  - `GUILD_ROSTER.snapshot()` equals `Map.of("uuid-2", "Holidaze")` after that parse.
- **`anUnparseableBodyLeavesThePreviousSnapshotInPlace`**: Parsing `not json at all` after a valid payload leaves the earlier snapshot intact.
  - `GUILD_ROSTER.snapshot()` equals `Map.of("uuid-1", "Metrafish")` after parsing `{"uuid-1": "Metrafish"}` and then the non-JSON body `not json at all`.
- **`nullAndBlankKeysReturnNullRatherThanThrowing`**: `get(null)` and `get("")` return null on both the alias and roster maps.
  - `WYNN_ALIASES.get(k)` is null for `k` = null and `""`.
  - `GUILD_ROSTER.get(k)` is null for `k` = null and `""`.
- **`nonPrimitiveAndEmptyValuesAreDropped`**: Of four entries, only the non-empty string value survives; the empty string, nested object and array are dropped.
  - `GUILD_ROSTER.snapshot()` equals `Map.of("uuid-1", "Metrafish")` after parsing `{"uuid-1": "Metrafish", "uuid-2": "", "uuid-3": {"nested": 1}, "uuid-4": [1, 2]}`: the empty string, object and array values are dropped.

### `PollingServiceTest`

Test: [`PollingServiceTest.java`](../src/test/java/org/wynnvets/fetcher/polling/PollingServiceTest.java) · Subject: [`PollingService`](../src/client/java/org/wynnvets/fetcher/polling/PollingService.java)

`PollingService` owns a poller's lifecycle: a named daemon scheduled executor that runs a caller-supplied task at a fixed rate, with idempotent `start` and a `stop` that can be followed by a fresh `start`. The suite pins start and stop behaviour, the initial delay, interrupt handling in `stop`, the thread identity, and the guard that keeps an escaping `Error` from cancelling the schedule. These are the repo's concurrent tests, so every wait is bounded, periods are tens of milliseconds, and assertions rely on latches and tick counts rather than elapsed time. An `@AfterEach` clears the interrupt flag and stops the service. The negative waits (300 ms after a stop, 150 ms before a delayed start) can only fail if the property is actually broken.

- **`startSchedulesTheTask`**: `start()` returns true and the task runs within 2 seconds.
  - `service.start()` is true, for a `PollingService` named `VetsMod-Test-Start` whose task counts down latch `ran` (count 1), initial delay 0, period 20 ms.
  - `ran.await(2, TimeUnit.SECONDS)` is true: the task ran at least once within 2 s.
- **`secondStartIsANoOpAndLeavesNoSecondExecutorRunning`**: A second `start()` returns false; after `stop()`, the tick count stays unchanged for 300 ms, so no second executor leaked.
  - The first `service.start()` is true; the service (`VetsMod-Test-Idempotent`, delay 0, period 20 ms) has a task that increments `ticks` and counts down `firstTick` (count 1).
  - The second `service.start()` is false.
  - `firstTick.await(2, TimeUnit.SECONDS)` is true: the task ran within 2 s.
  - After `service.stop()`, `afterStop` = `ticks.get()` read right after stop, then a 300 ms sleep (`QUIET_MILLIS`): `ticks.get()` equals `afterStop`, so no tick happened after stop (no second executor left running).
- **`stopEndsTheScheduleAndClearsIsRunning`**: `isRunning()` is true after start and false after stop, and the tick count stops increasing for 300 ms.
  - `service.isRunning()` is true right after `service.start()` (service `VetsMod-Test-Stop`, delay 0, period 20 ms).
  - `firstTick.await(2, TimeUnit.SECONDS)` is true: the task ran within 2 s (the task increments `ticks` and counts down `firstTick`, count 1).
  - `service.isRunning()` is false after `service.stop()`.
  - After `afterStop` = `ticks.get()` read after stop and a 300 ms sleep (`QUIET_MILLIS`), `ticks.get()` equals `afterStop`: the task did not fire after stop.
- **`startAfterStopSchedulesOnAFreshExecutor`**: After `stop()`, `start()` returns true again and the task runs on the new schedule.
  - `ran.get().await(2, TimeUnit.SECONDS)` is true: the task ran within 2 s after the first `start()`; `ran` holds a latch of count 1 that the task counts down (service `VetsMod-Test-Restart`, delay 0, period 20 ms).
  - After `service.stop()` and swapping in a fresh latch of count 1, `service.start()` is true.
  - `ran.get().await(2, TimeUnit.SECONDS)` is true: the task ran within 2 s on the fresh latch after the restart.
- **`zeroInitialDelayFiresWellBeforeOnePeriod`**: With zero initial delay and a 5 second period, the task runs within 1 second.
  - `ran.await(1, TimeUnit.SECONDS)` is true: with initial delay 0 and period 5000 ms (service `VetsMod-Test-ZeroDelay`), the task counted down `ran` (count 1) within 1 s, well before the 5 s period.
- **`aNonZeroInitialDelayIsHonoured`**: With a 400 ms initial delay, the task has not run after 150 ms but does run within 2 seconds.
  - `ran.await(150, TimeUnit.MILLISECONDS)` is false: with initial delay 400 ms and period 400 ms (service `VetsMod-Test-DelayedStart`), the task had not counted down `ran` (count 1) within the first 150 ms.
  - `ran.await(2, TimeUnit.SECONDS)` is true: the task counted down `ran` within a further 2 s.
- **`stopReassertsAnInterruptTakenWhileDraining`**: With the caller's interrupt flag set and a task blocked, `stop()` leaves the flag set and interrupts the blocked task via `shutdownNow()`.
  - `taskStarted.await(2, TimeUnit.SECONDS)` is true: the blocking task (service `VetsMod-Test-Interrupt`, delay 0, period 20 ms, which waits up to 30 s on `neverReleased`) began within 2 s.
  - `Thread.interrupted()` is true after the test thread set its own interrupt flag and called `service.stop()`: `stop()` left the caller's interrupt flag set (this call also clears it).
  - `taskInterrupted.await(2, TimeUnit.SECONDS)` is true: the blocked task was interrupted (its catch block counted down `taskInterrupted`) within 2 s after `stop()`.
- **`theTaskRunsOnTheNamedDaemonThread`**: The task runs on a thread named exactly as passed in (`VetsMod-Test-ThreadIdentity`) and that thread is a daemon.
  - `ran.await(2, TimeUnit.SECONDS)` is true: the task ran within 2 s (service `VetsMod-Test-ThreadIdentity`, delay 0, period 20 ms); it records `Thread.currentThread()`'s name and daemon flag, then counts down `ran`.
  - `observedName.get()` equals `"VetsMod-Test-ThreadIdentity"`: the task's thread name is the name passed to the constructor.
  - `observedDaemon.get()` is true: the task's thread is a daemon.
- **`anErrorFromTheTaskDoesNotCancelTheSchedule`**: A task that throws a `NoClassDefFoundError` on its first tick still runs again, and `isRunning()` stays true.
  - The task (service `VetsMod-Test-ErrorGuard`, delay 0, period 20 ms) throws `NoClassDefFoundError` on its first run and counts down `ranAgain` (count 1) on the next; `ranAgain.await(2, TimeUnit.SECONDS)` is true.
  - `service.isRunning()` is true afterwards.
- **`aVirtualMachineErrorEndsTheScheduleAndIsRunningSaysSo`**: A task that throws a `StackOverflowError` ends the schedule, and `isRunning()` turns false.
  - The task (service `VetsMod-Test-VmError`, delay 0, period 20 ms) counts down `thrown` (count 1) and throws `StackOverflowError`; `thrown.await(2, TimeUnit.SECONDS)` is true.
  - Within a bounded 2 s poll, `service.isRunning()` becomes false.

### `StaffRanksPollerTest`

Test: [`StaffRanksPollerTest.java`](../src/test/java/org/wynnvets/fetcher/polling/StaffRanksPollerTest.java) · Subject: [`StaffRanksPoller`](../src/client/java/org/wynnvets/fetcher/polling/StaffRanksPoller.java)

`StaffRanksPoller` caches the staff the server reports online, keyed by lower-cased username, from a periodic `/v1/outbound/staff` fetch. The suite pins what one parsed response leaves in that poll cache, through the package-private `parseStaffRanks` and the public `confirmedRankFor`: a name the next response lacks is dropped, a repeated name is kept, an unparseable body changes nothing, and a retired captain is not kept. The swap's atomicity is not pinned. An `@AfterEach` parses `[]` to empty the cache.

- **`aNameTheNextResponseLacksIsDroppedAndARepeatedOneKept`**: After a response listing Metrafish and Holidaze, a response listing only Metrafish keeps Metrafish and drops Holidaze.
  - `confirmedRankFor("holidaze")` equals `Optional.of("strategist")` after parsing `[{"username": "Metrafish", "rank": "Chief"}, {"username": "Holidaze", "rank": "strategist"}]`.
  - `confirmedRankFor("METRAFISH")` equals `Optional.of("chief")` after then parsing `[{"username": "Metrafish", "rank": "chief"}]`.
  - `confirmedRankFor("Holidaze")` equals `Optional.empty()` after that second parse.
- **`anUnparseableBodyLeavesThePreviousResponseInPlace`**: A non-JSON body after a valid one leaves the valid one's entry.
  - `confirmedRankFor("Metrafish")` equals `Optional.of("owner")` after parsing `[{"username": "Metrafish", "rank": "owner"}]` and then `not json at all`.
- **`aRetiredCaptainIsNotKept`**: A captain entry is dropped by the allowed-ranks check.
  - `confirmedRankFor("Wenweia")` equals `Optional.empty()` after parsing `[{"username": "Wenweia", "rank": "captain"}]`.

## `org.wynnvets.guild`

### `GuildCheckResultTest`

Test: [`GuildCheckResultTest.java`](../src/test/java/org/wynnvets/guild/GuildCheckResultTest.java) · Subject: [`GuildChecker.GuildCheckResult`](../src/client/java/org/wynnvets/guild/GuildChecker.java)

`GuildCheckResult` is the nested enum that records the outcome of the guild check (unknown, Returners, other guild, guildless) and is stored in the player's config as a raw long. The suite pins that storage mapping: each constant's `persistedValue` is compared against a literal, never against `ordinal()`, and `fromPersistedValue` must invert it and degrade to `UNKNOWN` for anything unrecognised. It also pins `contradicts`, the test the recheck poll clears the cached result on. Only the nested enum is exercised, so the enclosing `GuildChecker` (which reaches `VetsConfig` and Wynntils) is never initialised.

- **`persistedValues_areTheFourStorageLiterals`**: `UNKNOWN`, `RETURNERS`, `OTHER_GUILD` and `GUILDLESS` carry `persistedValue` 0, 1, 2 and 3 respectively.
  - `persistedValue` equals `0L` for `UNKNOWN`, `1L` for `RETURNERS`, `2L` for `OTHER_GUILD`, `3L` for `GUILDLESS`.
- **`fromPersistedValue_roundTripsEveryConstant`**: `fromPersistedValue` of 0 to 3 returns the identical constant (`assertSame`) for each of the four.
  - `fromPersistedValue(v)` is the same instance as `UNKNOWN` for `v` = `0L`, `RETURNERS` for `1L`, `OTHER_GUILD` for `2L`, `GUILDLESS` for `3L`.
- **`fromPersistedValue_unrecognisedFallsBackToUnknown`**: 4, -1, `Long.MAX_VALUE` and `Long.MIN_VALUE` all map to `UNKNOWN` instead of throwing, so a config written by a newer build reads as "not checked yet".
  - `fromPersistedValue(v)` is the same instance as `UNKNOWN` for `v` = `4L`, `-1L`, `Long.MAX_VALUE`, `Long.MIN_VALUE`.
- **`contradicts_isFalseWhenWynntilsAgrees`**: Each stored result agrees with the Wynntils data it describes, so the recheck poll keeps it.
  - `contradicts` is false for `RETURNERS` with `(true, "Returners")`, `OTHER_GUILD` with `(true, "Some Other Guild")` and `GUILDLESS` with `(false, "")`.
- **`contradicts_isTrueWhenWynntilsDisagrees`**: A result from a guild change while disconnected disagrees, so the poll clears it.
  - `contradicts` is true for `RETURNERS` with `(false, "")` and `(true, "Some Other Guild")`, `OTHER_GUILD` with `(false, "")` and `(true, "Returners")`, and `GUILDLESS` with `(true, "Returners")`.
- **`contradicts_unknownDisagreesWithNothing`**: An unchecked result is never cleared as a contradiction.
  - `contradicts` is false for `UNKNOWN` with `(true, "Returners")` and with `(false, "")`.

### `ReturnersReauthTest`

Test: [`ReturnersReauthTest.java`](../src/test/java/org/wynnvets/guild/ReturnersReauthTest.java) · Subject: [`ReturnersReauth`](../src/client/java/org/wynnvets/guild/ReturnersReauth.java)

`ReturnersReauth` re-sends the auth frame when the player joins Returners mid-session. The suite pins its three pure parts: `shouldReauth`, the condition each attempt sends on; `isTransient`, which failure details count as temporary-server failing to reach dazebot's introspection rather than a verdict on the key; and the disconnect fence. `onJoinedReturners` and its attempts read `UnlockManager`, `V1ApiManager` and Wynntils' tick scheduler and are not exercised; `UnlockManager.TIER_MEMBER` is a compile-time constant, so `shouldReauth` does not load `UnlockManager`.

- **`shouldReauth_sendsForAStoredUnrejectedKeyOnALiveSocketBelowMember`**: With a stored, unrejected key and the socket up, every tier but `member` re-sends, including no tier at all.
  - `shouldReauth(true, true, tier, false)` is true for `tier` = `waitlist`, `honourary`, `other` and the empty string.
- **`shouldReauth_eachMissingConditionBlocksIt`**: No key, the socket down, a rejected key, or the `member` tier each stop it.
  - `shouldReauth(false, true, "waitlist", false)`, `shouldReauth(true, false, "waitlist", false)`, `shouldReauth(true, true, "waitlist", true)` and `shouldReauth(true, true, "member", false)` are each false.
- **`isTransient_holdsForEachIntrospectionFailure`**: Every introspection reason temporary-server's provider produces, as the auth handler wraps it, is transient.
  - `isTransient` is true for `auth rejected: introspection unavailable: ConnectTimeout`, `… introspection HTTP 502`, `… introspection bad payload`, `… introspection misconfigured (401)` and `… introspection disabled at dazebot`.
- **`isTransient_failsForAVerdictOnTheKeyAndForNonAuthDetails`**: A rejection of the key, a non-auth refusal and null are not transient.
  - `isTransient` is false for `auth rejected: revoked`, `auth rejected: unknown key`, `auth rejected: empty key`, `Authentication required` and `null`.
- **`fenced_runsWhenNoResetCameBetween`**: A fenced body runs when no reset came after its generation was read.
  - after `fenced(generation(), …).run()`, the counter equals 1.
- **`fenced_dropsTheRetryAfterAReset`**: A body fenced before a `reset()` does nothing after it.
  - after `fenced(generation(), …)`, `reset()`, then `run()`, the counter equals 0.

### `StaffRankCheckerTest`

Test: [`StaffRankCheckerTest.java`](../src/test/java/org/wynnvets/guild/StaffRankCheckerTest.java) · Subject: [`StaffRankChecker`](../src/client/java/org/wynnvets/guild/StaffRankChecker.java)

`StaffRankChecker` decides whether the player is guild staff by running `/gu rank` and classifying Wynncraft's reply. The suite covers its two package-private response predicates, `isStaffRankUnauthorizedResponse` (three required fragments) and `isStaffRankAuthorizedResponse` (two required fragments). It pins that both are unordered, case-insensitive substring tests that return false for null or empty input, that the two never both fire on the real server lines, and that case folding uses `Locale.ROOT` so a Turkish default locale does not break recognition. The locale tests set `Locale.setDefault` and an `@AfterEach` restores it.

- **`unauthorized_matchesTheRealServerLine`**: "You must be a Captain or higher to use this command!" is recognised as the unauthorized response.
  - `StaffRankChecker.isStaffRankUnauthorizedResponse(UNAUTHORIZED)` is true, where `UNAUTHORIZED` = `"You must be a Captain or higher to use this command!"`.
- **`unauthorized_foldsCase`**: The all-upper-case and title-case forms of the refusal still match, because the predicate does its own lower-casing.
  - `isStaffRankUnauthorizedResponse("YOU MUST BE A CAPTAIN OR HIGHER TO USE THIS COMMAND!")` is true.
  - `isStaffRankUnauthorizedResponse("You Must Be A Captain Or Higher To Use This Command!")` is true.
- **`unauthorized_requiresAllThreeFragments`**: Replacing "Captain" with "Chief", dropping the "to use this command" clause, or dropping the "you must be a" clause each make the predicate return false.
  - `isStaffRankUnauthorizedResponse("You must be a Chief or higher to use this command!")` is false (rank word not Captain).
  - `isStaffRankUnauthorizedResponse("You must be a Captain!")` is false (no trailing clause).
  - `isStaffRankUnauthorizedResponse("Captain Holidaze asked you to use this command")` is false (no opening clause).
- **`unauthorized_fragmentsAreUnorderedSoAnyLineCarryingAllThreeMatches`**: A line containing all three fragments in reverse order ("to use this command captain says you must be a member") still matches, pinning the false-positive surface.
  - `isStaffRankUnauthorizedResponse("to use this command captain says you must be a member")` is true (the three fragments in reverse order).
- **`unauthorized_nullAndEmpty`**: `null` and the empty string return false.
  - `isStaffRankUnauthorizedResponse(null)` is false.
  - `isStaffRankUnauthorizedResponse("")` is false.
- **`authorized_matchesTheRealServerLine`**: "Invalid arguments, try: /gu rank [name] [rank]" is recognised as the authorized response.
  - `StaffRankChecker.isStaffRankAuthorizedResponse(AUTHORIZED)` is true, where `AUTHORIZED` = `"Invalid arguments, try: /gu rank [name] [rank]"`.
- **`authorized_requiresBothFragments`**: "Invalid arguments, try: /gu list" and the bare "/gu rank [name] [rank]" both return false.
  - `isStaffRankAuthorizedResponse("Invalid arguments, try: /gu list")` is false (the rank arguments are missing).
  - `isStaffRankAuthorizedResponse("/gu rank [name] [rank]")` is false (the error prefix is missing).
- **`authorized_matchesTheLiteralBracketsNotAPattern`**: A usage line with the placeholders filled in ("/gu rank Metrafish Captain") does not match, since the square brackets are literal substring text.
  - `isStaffRankAuthorizedResponse("Invalid arguments, try: /gu rank Metrafish Captain")` is false (placeholders filled in, so the literal `[name] [rank]` is absent).
- **`authorized_nullAndEmpty`**: `null` and the empty string return false.
  - `isStaffRankAuthorizedResponse(null)` is false.
  - `isStaffRankAuthorizedResponse("")` is false.
- **`theTwoPredicatesDoNotBothFireOnEitherRealLine`**: The authorized predicate is false on the unauthorized line and the unauthorized predicate is false on the authorized line, so the else-branch in message processing cannot be shadowed.
  - `isStaffRankAuthorizedResponse(UNAUTHORIZED)` is false.
  - `isStaffRankUnauthorizedResponse(AUTHORIZED)` is false.
- **`authorized_survivesATurkishDefaultLocaleOnTheRealServerLine`**: Regression guard: with the default locale set to Turkish, the authorized line (capital I in "Invalid") still matches.
  - with the default locale set to `tr`, `isStaffRankAuthorizedResponse(AUTHORIZED)` is true.
- **`unauthorized_survivesATurkishDefaultLocaleEvenShouted`**: Under a Turkish default, the unauthorized line matches both as-is and upper-cased with `Locale.ROOT`.
  - with the default locale set to `tr`, `isStaffRankUnauthorizedResponse(UNAUTHORIZED)` is true.
  - with the default locale set to `tr`, `isStaffRankUnauthorizedResponse(UNAUTHORIZED.toUpperCase(Locale.ROOT))` is true.

## `org.wynnvets.items`

### `ItemDefinitionsTest`

Test: [`ItemDefinitionsTest.java`](../src/test/java/org/wynnvets/items/ItemDefinitionsTest.java) · Subject: [`ItemDefinitions`](../src/client/java/org/wynnvets/items/ItemDefinitions.java), [`definitions.yml`](../src/client/resources/definitions.yml)

`ItemDefinitions` loads per-category lists of item-name regexes (legacy, no-lore legacy, misc legacy, unenchanted, not-pedestal, not-junk, new-format override, blocked screen titles) from `definitions.yml` and answers name-matching queries against them. The suite pins behaviour that is invisible from the Java side: per-pattern case sensitivity, full-match semantics, the empty-pattern guard that applies to only some sections, the `isLegacy` veto in `isUnenchanted`, the inconsistent null handling, and how YAML quoting and comments reach the regex. It loads the real `definitions.yml` from the classpath in a static initialiser, appends small YAML fixtures through the package-private `parse(InputStream)`, and reloads the real file after every test, since `load()` clears all collections.

- **`isLegacy_caseSensitivityIsPerPatternNotPerCategory`**: "SANTA HAT" and "santa hat" match the `(?i)` Santa entry, while "Wolf Fang" and "Corrupted Treasure" match only in their exact case.
  - `ItemDefinitions.isLegacy("SANTA HAT")` is true (the real file's `(?i)^Santa.*$` entry folds case)
  - `ItemDefinitions.isLegacy("santa hat")` is true
  - `ItemDefinitions.isLegacy("Wolf Fang")` is true (real-file entry `^Wolf Fang$`, no `(?i)`)
  - `ItemDefinitions.isLegacy("wolf fang")` is false
  - `ItemDefinitions.isLegacy("WOLF FANG")` is false
  - `ItemDefinitions.isLegacy("Corrupted Treasure")` is true (real-file entry `^Corrupted Treasure$`, no `(?i)`)
  - `ItemDefinitions.isLegacy("corrupted treasure")` is false
- **`isNotJunk_sameSplitInADifferentCategory`**: "leather" and "Leather" both match the `(?i)` entry, but "Tanned Sunfish" matches and "tanned sunfish" does not.
  - `ItemDefinitions.isNotJunk("leather")` is true (real-file entry `(?i)^Leather$`)
  - `ItemDefinitions.isNotJunk("Leather")` is true
  - `ItemDefinitions.isNotJunk("Tanned Sunfish")` is true (real-file entry `^Tanned Sunfish$`, no `(?i)`)
  - `ItemDefinitions.isNotJunk("tanned sunfish")` is false
- **`predicatesRequireAFullMatchNotASubstring`**: With an unanchored fixture pattern "Anchorless", the exact name matches but "My Anchorless Probe" does not, showing `matches()` rather than `find()`.
  - After parsing a fixture whose `definitions` list holds the unanchored pattern `Anchorless`, `ItemDefinitions.isLegacy("Anchorless")` is true
  - With that same fixture, `ItemDefinitions.isLegacy("My Anchorless Probe")` is false (the pattern must match the whole name, not a substring)
- **`anchoredPatternsFromTheRealFileStillRejectSubstrings`**: "Ability Shard" is unenchanted, but "My Ability Shard" and "Ability Shards" are not.
  - `ItemDefinitions.isUnenchanted("Ability Shard")` is true (real-file entry `^Ability Shard$`)
  - `ItemDefinitions.isUnenchanted("My Ability Shard")` is false
  - `ItemDefinitions.isUnenchanted("Ability Shards")` is false
- **`isUnenchanted_isVetoedByIsLegacy`**: A name present in both `definitions` and `unenchanted` is legacy and not unenchanted, while a name only in `unenchanted` still answers true.
  - After parsing a fixture with `^VetoProbe$` in `definitions` and `unenchanted`, and `^VetoOnly$` in `unenchanted` only, `ItemDefinitions.isLegacy("VetoProbe")` is true
  - `ItemDefinitions.isUnenchanted("VetoProbe")` is false (a name that is legacy is not unenchanted, though it is in the unenchanted list)
  - `ItemDefinitions.isUnenchanted("VetoOnly")` is true (in the unenchanted list and not legacy)
- **`unguardedSectionsAcceptAnEmptyPatternAndGuardedOnesDropIt`**: An empty pattern added to `definitions` makes `isLegacy("")` true, but the same empty pattern in `not_pedestal` is dropped and `isNotPedestal("")` stays false.
  - Before any fixture is parsed, `ItemDefinitions.isLegacy("")` is false (the real file loads no empty pattern)
  - Before any fixture is parsed, `ItemDefinitions.isNotPedestal("")` is false
  - After parsing a fixture with an empty pattern `""` under both `definitions` and `not_pedestal`, `ItemDefinitions.isLegacy("")` is true (the `definitions` section compiles the empty pattern, which matches the empty name)
  - With that same fixture, `ItemDefinitions.isNotPedestal("")` is false (the `not_pedestal` section drops the empty pattern before compiling)
- **`anEmptyPatternMatchesOnlyTheEmptyName`**: After adding an empty `definitions` pattern, `isLegacy("")` is true and `isLegacy("x")` is false.
  - After parsing a fixture with an empty pattern `""` under `definitions`, `ItemDefinitions.isLegacy("")` is true
  - With that same fixture, `ItemDefinitions.isLegacy("x")` is false
- **`trailingCommentsAreStrippedFromQuotedAndPlainEntriesAlike`**: Entries with a trailing `#` comment, whether double-quoted or bare, compile without the comment and match "QuotedProbe" and "BareProbe".
  - After parsing a fixture with `"^QuotedProbe$" # this comment is discarded` and `^BareProbe$ # and so is this one` under `definitions`, `ItemDefinitions.isLegacy("QuotedProbe")` is true
  - With that same fixture, `ItemDefinitions.isLegacy("BareProbe")` is true (the unquoted entry's trailing comment is not compiled into the pattern)
- **`aRegexBackslashSurvivesInSingleQuotes`**: A single-quoted `'^Q\?$'` keeps its backslash, so it matches "Q?" and not "Q", and the real file's `???` entry matches `isUnenchanted("???")`.
  - After parsing a fixture with the single-quoted entry `'^Q\?$'` under `definitions`, `ItemDefinitions.isLegacy("Q?")` is true (the backslash is kept, so the regex matches a literal `?`)
  - With that same fixture, `ItemDefinitions.isLegacy("Q")` is false
  - `ItemDefinitions.isUnenchanted("???")` is true (the real file's single-quoted entry `'^\?\?\?$'`)
- **`theRealFileParsesWithoutError`**: Parsing the classpath `definitions.yml` directly, with nothing catching exceptions, completes without throwing.
  - Passes as long as `ItemDefinitions.parse` of the classpath resource `/definitions.yml` completes without throwing
- **`aDoubleQuotedBackslashThatIsNotAYamlEscapeFailsTheParse`**: A double-quoted `"^Q\?$"` makes `parse` throw `YAMLException`, since `\?` is not a YAML escape.
  - Parsing a fixture with the double-quoted entry `"^Q\?$"` under `definitions` throws `YAMLException` (`\?` is not a YAML escape)
- **`aYamlEscapeInDoubleQuotesIsDecodedBeforeTheRegexSeesIt`**: A double-quoted `\_` is decoded by YAML to a no-break space (matches "A" plus U+00A0 plus "B", not "A_B"), and the real file's Fierté entry matches both "Fierté" and "fierté".
  - After parsing a fixture with the double-quoted entry `"^A\_B$"` under `definitions`, `ItemDefinitions.isLegacy("A" + U+00A0 + "B")` is true (YAML decodes `\_` to U+00A0)
  - With that same fixture, `ItemDefinitions.isLegacy("A_B")` is false (the regex never saw `\_` as a plain underscore)
  - `ItemDefinitions.isNoLoreLegacy("Fierté")` is true (real-file `no_lore_legacy` entry `(?i)^Fierté$`, whose double-quoted YAML escape decodes to `é`)
  - `ItemDefinitions.isNoLoreLegacy("fierté")` is true (`(?i)` folds the ASCII letters)
- **`wholeLineCommentsAreSkippedByTheParserItself`**: A commented-out list entry is ignored while the next entry is loaded, so "DisabledProbe" does not match and "EnabledProbe" does.
  - After parsing a fixture with the commented-out entry `#- "^DisabledProbe$"` and the live entry `- "^EnabledProbe$"` under `definitions`, `ItemDefinitions.isLegacy("DisabledProbe")` is false
  - With that same fixture, `ItemDefinitions.isLegacy("EnabledProbe")` is true
- **`sectionsEndAtTheDocumentSeparator`**: An entry after a `---` document break that is a bare list names no category and is dropped, while the entry before the break is kept.
  - After parsing a fixture with `definitions: - "^BeforeSeparator$"`, then `---`, then a bare list `- "^AfterSeparator$"`, `ItemDefinitions.isLegacy("BeforeSeparator")` is true
  - With that same fixture, `ItemDefinitions.isLegacy("AfterSeparator")` is false (the bare-list document names no category and is dropped)
- **`anUnknownSectionNameIsSilentlyIgnored`**: A misspelled section header ("defnitions") loses every pattern under it without an error, so the probe name does not match.
  - After parsing a fixture whose section is misspelled `defnitions:` and holds `"^TypoProbe$"`, `ItemDefinitions.isLegacy("TypoProbe")` is false
- **`onlyIsBlockedScreenTitleGuardsNull`**: `isBlockedScreenTitle(null)` returns false, while the seven name predicates throw `NullPointerException` on null (relying on each real-file list being non-empty).
  - `ItemDefinitions.isBlockedScreenTitle(null)` is false
  - `ItemDefinitions.isLegacy(null)` throws `NullPointerException`
  - `ItemDefinitions.isNoLoreLegacy(null)` throws `NullPointerException`
  - `ItemDefinitions.isMiscLegacy(null)` throws `NullPointerException`
  - `ItemDefinitions.isUnenchanted(null)` throws `NullPointerException`
  - `ItemDefinitions.isNotPedestal(null)` throws `NullPointerException`
  - `ItemDefinitions.isNotJunk(null)` throws `NullPointerException`
  - `ItemDefinitions.isNewFormatOverride(null)` throws `NullPointerException`
- **`eachCategoryIsPopulatedAndAnswersFromTheRealFile`**: One known name per remaining category from the real file, such as "Rotten Flesh", "Gunpowder", "Cycle" and "Permissions for Bob", returns true from its predicate.
  - `ItemDefinitions.isNoLoreLegacy("Rotten Flesh")` is true
  - `ItemDefinitions.isMiscLegacy("Gunpowder")` is true
  - `ItemDefinitions.isUnenchanted("Cycle")` is true
  - `ItemDefinitions.isNotPedestal("Armour Merchant")` is true
  - `ItemDefinitions.isNotJunk("Rubble")` is true
  - `ItemDefinitions.isNewFormatOverride("Skeleton Key")` is true
  - `ItemDefinitions.isBlockedScreenTitle("Permissions for Bob")` is true
- **`categoriesAreIndependentOfEachOther`**: "Corrupted Treasure" is legacy but not no-lore legacy, misc legacy or not-junk.
  - `ItemDefinitions.isLegacy("Corrupted Treasure")` is true
  - `ItemDefinitions.isNoLoreLegacy("Corrupted Treasure")` is false
  - `ItemDefinitions.isMiscLegacy("Corrupted Treasure")` is false
  - `ItemDefinitions.isNotJunk("Corrupted Treasure")` is false
- **`load_isIdempotentAndDiscardsParsedInFixtures`**: After parsing a "ReloadProbe" fixture and calling `load()`, the probe no longer matches, "Wolf Fang" matches, and a second `load()` leaves that unchanged.
  - After parsing a fixture with `^ReloadProbe$` under `definitions`, `ItemDefinitions.isLegacy("ReloadProbe")` is true
  - After `ItemDefinitions.load()`, `ItemDefinitions.isLegacy("ReloadProbe")` is false (`load()` clears before refilling)
  - After that `load()`, `ItemDefinitions.isLegacy("Wolf Fang")` is true (the real file is back)
  - After a second `ItemDefinitions.load()`, `ItemDefinitions.isLegacy("Wolf Fang")` is true

### `ScreenshotLatchTest`

Test: [`ScreenshotLatchTest.java`](../src/test/java/org/wynnvets/items/ScreenshotLatchTest.java) · Subject: [`ScreenshotLatch`](../src/client/java/org/wynnvets/items/ScreenshotLatch.java)

`ScreenshotLatch` remembers a press of Wynntils' item-screenshot key until the next tooltip pass consumes it. The suite pins the three rules that stop a stale press firing on an unrelated tooltip: a press is consumed exactly once, it is live for `LIFETIME_MS` (1000 ms) after the press and no longer, and a consume past that window still clears it. Time is the `nowMs` parameter, so no test sleeps. The tests use a base time `T0` of 5000.

- **`aFreshLatchIsNotArmed`**: A latch nobody pressed does not report a press.
  - `new ScreenshotLatch().consume(T0)` is false.
- **`aPressIsConsumedExactlyOnce`**: One press is reported once, then the latch is empty.
  - `consume(T0 + 10)` is true after `press(T0)`.
  - `consume(T0 + 20)` is false straight after.
- **`aPressIsStillLiveAtTheEndOfItsLifetime`**: A consume exactly `LIFETIME_MS` after the press still counts.
  - `consume(T0 + LIFETIME_MS)` is true after `press(T0)`.
- **`aPressPastItsLifetimeIsDroppedAndNeverFiresLater`**: A consume one millisecond past the lifetime reports nothing and clears the press.
  - `consume(T0 + LIFETIME_MS + 1)` is false after `press(T0)`.
  - `consume(T0 + LIFETIME_MS + 2)` is false afterwards (the stale press was cleared, not kept).
- **`aSecondPressRestartsTheLifetime`**: A press 900 ms after the first starts a fresh window.
  - `consume(T0 + 900 + LIFETIME_MS)` is true after `press(T0)` then `press(T0 + 900)`, a time that is past the first press's lifetime.
- **`aPressAfterAnExpiredOneArmsAgain`**: After a press has expired and been dropped, a new press works.
  - `consume(T0 + 5000)` is false after `press(T0)`.
  - `consume(T0 + 6100)` is true after then `press(T0 + 6000)`.

## `org.wynnvets.listeners`

### `PartyRosterListenerTest`

Test: [`PartyRosterListenerTest.java`](../src/test/java/org/wynnvets/listeners/PartyRosterListenerTest.java) · Subject: [`PartyRosterListener`](../src/client/java/org/wynnvets/listeners/PartyRosterListener.java)

`PartyRosterListener` captures the player's Wynncraft party and reports it to the anni backend as an `anni_party_observation` frame. The suite covers its pure, package-private gate `shouldSend(Snapshot, AnniSnapshot, stamp, now)`: a frame is sent only if the anni stamp is within a window of plus or minus two hours of `now` (inclusive), which is the public `inActiveWindow(stamp, now)` that `AnniPartyReporter` also reads, and the snapshot's `organiser_usernames` share a name, ignoring case, with the party's leader or members. Time is injected as `now`; the anni snapshot is built through `AnniSnapshot.fromJson` with only `organiser_usernames` set, and `Snapshot` is the package-private party record with an `EMPTY` constant.

- **`stampZeroSuppresses`**: A stamp of 0 (no anni announced) returns false even with a matching organiser.
  - `shouldSend(snap("Organiser", "M1"), anni, 0L, NOW)` is false, where `snap(...)` is a party led by `Organiser` with member `M1`, `anni` lists `Organiser` as its organiser, and `NOW` = `1_700_000_000L`; the stamp `0L` is far outside the window.
- **`stampThreeHoursInFutureSuppresses`**: A stamp 3 hours after `now` is outside the window and returns false.
  - `shouldSend(snap("Organiser", "M1"), anni, NOW + 3h, NOW)` is false (the stamp is 3 h after `NOW`).
- **`stampThreeHoursInPastSuppresses`**: A stamp 3 hours before `now` is outside the window and returns false.
  - `shouldSend(snap("Organiser", "M1"), anni, NOW - 3h, NOW)` is false (the stamp is 3 h before `NOW`).
- **`stampExactlyAtWindowEdgeAllowed`**: A stamp exactly 2 hours after `now` is still inside the window and returns true.
  - `shouldSend(snap("Organiser", "M1"), anni, NOW + 2h, NOW)` is true (the stamp is exactly 2 h after `NOW`).
- **`stampInTrailingWindowAllowed`**: A stamp 30 minutes before `now` is inside the window and returns true.
  - `shouldSend(snap("Organiser", "M1"), anni, NOW - 30m, NOW)` is true (the stamp is 30 min before `NOW`).
- **`inActiveWindow_edgesAreInclusiveAndZeroIsNever`**: The window is `now` plus or minus two hours with both edges inclusive, and a stamp of 0 is never inside it.
  - `PartyRosterListener.inActiveWindow(stamp, NOW)` is true for `stamp` = `NOW` and for `NOW + 2h`.
  - `inActiveWindow(NOW + 2h + 1, NOW)` is false.
  - `inActiveWindow(NOW - 2h, NOW)` is true.
  - `inActiveWindow(NOW - 2h - 1, NOW)` is false.
  - `inActiveWindow(0L, NOW)` is false.
  - `inActiveWindow(0L, 0L)` is false (no stamp, even when `now` is also 0).
- **`nullSnapshotSuppresses`**: A null `AnniSnapshot` returns false even with an in-window stamp.
  - `shouldSend(snap("Organiser", "M1"), null, NOW + 1h, NOW)` is false (the `AnniSnapshot` is null).
- **`emptyOrganisersSuppresses`**: A snapshot whose `organiser_usernames` array is empty returns false.
  - `shouldSend(snap("Organiser", "M1"), anni, NOW + 1h, NOW)` is false, where `anni` has an empty `organiser_usernames` list.
- **`leaderMatchSends`**: When the party leader is one of the organisers, the gate returns true.
  - `shouldSend(snap("Organiser", "M1", "M2"), anni, NOW + 1h, NOW)` is true, where `anni` lists `Organiser` and `OtherLead` (the leader matches).
- **`memberMatchSends`**: When a non-leader party member is an organiser, the gate returns true.
  - `shouldSend(snap("RandomLead", "Organiser", "M2"), anni, NOW + 1h, NOW)` is true, where `anni` lists `Organiser` (a member matches).
- **`noOrganiserInPartySuppresses`**: A party with no leader or member in the organiser list returns false.
  - `shouldSend(snap("RandomLead", "M1", "M2"), anni, NOW + 1h, NOW)` is false, where `anni` lists `Organiser` and `OtherLead` (neither the leader nor any member matches).
- **`leaderMatchIsCaseInsensitive`**: Leader "organiser" matches organiser "ORGANISER".
  - `shouldSend(snap("organiser", "M1"), anni, NOW + 1h, NOW)` is true, where `anni` lists `ORGANISER`.
- **`memberMatchIsCaseInsensitive`**: Member "ORGANISER" matches organiser "organiser".
  - `shouldSend(snap("RandomLead", "ORGANISER"), anni, NOW + 1h, NOW)` is true, where `anni` lists `organiser`.
- **`emptyNamesDoNotFalseMatchEmptyOrganiser`**: A party whose leader and members are all empty strings does not match an organiser list containing an empty string, so the result is false.
  - `shouldSend(snap("", "", ""), anni, NOW + 1h, NOW)` is false, where `anni` lists `""` and `Organiser`.
- **`emptySnapshotPartyWithOrganisersSuppresses`**: `Snapshot.EMPTY` (no party) returns false even with an in-window stamp and a non-empty organiser list.
  - `shouldSend(Snapshot.EMPTY, anni, NOW + 1h, NOW)` is false, where `anni` lists `Organiser`.
- **`emptySnapshotOutsideWindowSuppressed`**: `Snapshot.EMPTY` with a stamp 4 hours out returns false.
  - `shouldSend(Snapshot.EMPTY, anni, NOW + 4h, NOW)` is false, where `anni` lists `Organiser`.

### `RankChangeListenerTest`

Test: [`RankChangeListenerTest.java`](../src/test/java/org/wynnvets/listeners/RankChangeListenerTest.java) · Subject: [`RankChangeListener`](../src/client/java/org/wynnvets/listeners/RankChangeListener.java)

`RankChangeListener` watches guild rank-change chat messages and forwards alerts for them. The suite covers two package-private pieces: `classify(fromRank, toRank)`, which labels a change as `kick`, `ban`, `mote` or nothing, and the duplicate-suppression helpers `wasRecent`, `recordRecent` and `prune`, which keep a bounded, expiring set of recent fingerprints (30-second TTL, 50 entries). `prune` takes `now` as a parameter so expiry is tested without sleeping, and an `@AfterEach` calls the test-only `clearForTesting()` to reset the shared dedup state.

- **`recruitToRecruitIsKick`**: `classify("Recruit", "Recruit")` returns "kick".
  - `RankChangeListener.classify("Recruit", "Recruit")` equals `"kick"`.
- **`recruiterToRecruitIsBan`**: `classify("Recruiter", "Recruit")` returns "ban", since any non-Recruit demotion to Recruit is treated as a ban request.
  - `RankChangeListener.classify("Recruiter", "Recruit")` equals `"ban"`.
- **`captainToRecruitIsBan`**: `classify("Captain", "Recruit")` returns "ban".
  - `RankChangeListener.classify("Captain", "Recruit")` equals `"ban"`.
- **`captainToCaptainIsMote`**: A same-rank, non-Recruit change (`Captain` to `Captain`) returns "mote".
  - `RankChangeListener.classify("Captain", "Captain")` equals `"mote"`.
- **`recruitToRecruiterReturnsNull`**: A real promotion away from Recruit matches no rule and returns null.
  - `RankChangeListener.classify("Recruit", "Recruiter")` is null.
- **`wasRecent_neverSeen_returnsFalse`**: A fingerprint that was never recorded is not reported as recent.
  - `RankChangeListener.wasRecent("captain", "metrafish", "Captain", "Captain")` is false with nothing recorded (the dedup state is cleared after each test).
- **`recordRecent_thenWasRecent_returnsTrue`**: After `recordRecent` on a four-field fingerprint, `wasRecent` with the same fields returns true.
  - `wasRecent("captain", "metrafish", "Captain", "Captain")` is true after `recordRecent` with the same four arguments.
- **`wasRecent_distinguishesByActor`**: After recording actor "captain", a lookup with actor "OTHER" and the same other fields returns false.
  - `wasRecent("OTHER", "metrafish", "Captain", "Captain")` is false after `recordRecent("captain", "metrafish", "Captain", "Captain")` (only the first argument differs).
- **`prune_removesEntriesPastTtl`**: An entry is recent, then `prune` called with `now` set 31 seconds ahead (past the 30-second TTL) removes it.
  - `wasRecent("captain", "metrafish", "Captain", "Captain")` is true after `recordRecent` with those arguments.
  - `wasRecent("captain", "metrafish", "Captain", "Captain")` is false after `prune(now)` with `now` = `System.currentTimeMillis()` + 30 s (the TTL) + 1 s.
- **`recordRecent_atCapacity_evictsOldest`**: After recording 50 distinct fingerprints and one more, the first one is evicted, the newest and a middle entry remain recent.
  - `wasRecent("a0", "t", "Captain", "Captain")` is true after 50 `recordRecent` calls for `"a0"` through `"a49"` (the other three arguments are `"t"`, `"Captain"`, `"Captain"`); the cap is 50.
  - `wasRecent("a0", "t", "Captain", "Captain")` is false after a 51st `recordRecent("a50", "t", "Captain", "Captain")` (the oldest entry is evicted).
  - `wasRecent("a50", "t", "Captain", "Captain")` is true after that 51st record.
  - `wasRecent("a25", "t", "Captain", "Captain")` is true after that 51st record (a mid-deque entry is kept).

## `org.wynnvets.logging`

### `VetsLoggerTest`

Test: [`VetsLoggerTest.java`](../src/test/java/org/wynnvets/logging/VetsLoggerTest.java) · Subject: [`VetsLogger`](../src/main/java/org/wynnvets/logging/VetsLogger.java)

`VetsLogger` is the mod's central logger, with a debug level that is off until switched on (for example by `/wv debug true`). The suite pins only the debug flag accessors: it is false by default, `setDebugEnabled(true)` turns it on, and `setDebugEnabled(false)` turns it off again. An `@AfterEach` resets the flag to false so the static state does not leak between tests.

- **`defaultIsFalse`**: `isDebugEnabled()` is false when nothing has enabled it.
  - `VetsLogger.isDebugEnabled()` is false on a fresh state (the `@AfterEach` resets the flag to false).
- **`setEnabledTogglesFlag`**: After `setDebugEnabled(true)`, `isDebugEnabled()` is true.
  - `VetsLogger.isDebugEnabled()` is true after `setDebugEnabled(true)`.
- **`setDisabledRestoresFalse`**: After enabling and then disabling, `isDebugEnabled()` is false.
  - `VetsLogger.isDebugEnabled()` is false after `setDebugEnabled(true)` then `setDebugEnabled(false)`.

## `org.wynnvets.mwe.anni`

### `AnniPartyReporterTest`

Test: [`AnniPartyReporterTest.java`](../src/test/java/org/wynnvets/mwe/anni/AnniPartyReporterTest.java) · Subject: [`AnniPartyReporter`](../src/client/java/org/wynnvets/mwe/anni/AnniPartyReporter.java)

`AnniPartyReporter` listens to anni snapshot updates and asks `PartyRosterListener` to recapture the party. The suite covers its pure, package-private decision `shouldRecapture(prev, next, wasInWindow, nowInWindow)`: it is true when the case-folded organiser set changed in any direction, or when the anni window opened (`wasInWindow` false and `nowInWindow` true), and false otherwise, so the window closing and updates that change neither recapture nothing. The sets are `NONE` (empty) and `ORGANISER` (`organiser`), compared as sets.

- **`setChangeRecapturesInAnyDirection`**: Empty to non-empty, a replaced organiser, an added organiser and non-empty back to empty each recapture while outside the window.
  - `AnniPartyReporter.shouldRecapture(NONE, ORGANISER, false, false)` is true.
  - `shouldRecapture(ORGANISER, Set.of("other"), false, false)` is true.
  - `shouldRecapture(ORGANISER, Set.of("organiser", "other"), false, false)` is true.
  - `shouldRecapture(ORGANISER, NONE, false, false)` is true.
- **`setChangeRecapturesInsideTheWindowToo`**: A set change recaptures when the window was already open.
  - `shouldRecapture(NONE, ORGANISER, true, true)` is true.
  - `shouldRecapture(ORGANISER, NONE, true, true)` is true.
- **`windowOpeningRecapturesWithAnUnchangedSet`**: The window opening recaptures even when the set did not change, whether it is empty or not.
  - `shouldRecapture(ORGANISER, ORGANISER, false, true)` is true.
  - `shouldRecapture(NONE, NONE, false, true)` is true.
- **`unchangedSetWithoutAnOpeningRecapturesNothing`**: An unchanged set recaptures nothing while the window stays closed, stays open, or closes.
  - `shouldRecapture(ORGANISER, ORGANISER, false, false)` is false.
  - `shouldRecapture(ORGANISER, ORGANISER, true, true)` is false.
  - `shouldRecapture(ORGANISER, ORGANISER, true, false)` is false.
  - `shouldRecapture(NONE, NONE, false, false)` is false.
- **`windowClosingWithAChangedSetStillRecaptures`**: A set change recaptures on the update where the window closes.
  - `shouldRecapture(ORGANISER, NONE, true, false)` is true.

## `org.wynnvets.mwe.anni.aggressive`

### `GhostsPromptHandlerTest`

Test: [`GhostsPromptHandlerTest.java`](../src/test/java/org/wynnvets/mwe/anni/aggressive/GhostsPromptHandlerTest.java) · Subject: [`GhostsPromptHandler`](../src/client/java/org/wynnvets/mwe/anni/aggressive/GhostsPromptHandler.java)

`GhostsPromptHandler` prints a clickable "/toggle ghosts none" suggestion when the player enters the anni zone while aggressive mode is active. The suite covers only its pure leaf `promptLead(stamp, now)`, the prompt's opening words, and pins that they say the anni is starting soon before the stamp, and underway from it on, since the aggressive gate's hot window runs past the start. An unknown stamp (0) says starting soon. All cases use `STAMP` = 1 800 000 000 (epoch seconds).

- **`beforeTheStampTheAnniIsStartingSoon`**: 10 minutes before the stamp, and 1 second before it, the lead is "Anni starting soon - ".
  - `promptLead(STAMP, STAMP - 600)` equals `"Anni starting soon - "`.
  - `promptLead(STAMP, STAMP - 1)` equals `"Anni starting soon - "`.
- **`fromTheStampOnTheAnniIsUnderway`**: At the stamp and 20 minutes after it, the lead is "Anni underway - ".
  - `promptLead(STAMP, STAMP)` equals `"Anni underway - "`.
  - `promptLead(STAMP, STAMP + 1200)` equals `"Anni underway - "`.
- **`anUnknownStampSaysStartingSoon`**: With no stamp (0), the lead is "Anni starting soon - ".
  - `promptLead(0L, STAMP)` equals `"Anni starting soon - "`.

## `org.wynnvets.mwe.anni.bossbar`

### `VetsBossBarContentBuilderTest`

Test: [`VetsBossBarContentBuilderTest.java`](../src/test/java/org/wynnvets/mwe/anni/bossbar/VetsBossBarContentBuilderTest.java) · Subject: [`VetsBossBarContentBuilder`](../src/client/java/org/wynnvets/mwe/anni/bossbar/VetsBossBarContentBuilder.java)

`VetsBossBarContentBuilder` builds the synthetic anni boss bar's text. The suite pins the seeking variant's RSVP chip for each notice the server can send, in both of its spellings (`rsvp.notice`'s bare `hard` / `soft` and `attendance.notice_effective`'s raw enum value), with its colour; the won't-be-assigned line that replaces the chip; and the assigned variant's all-TBD bar for a player registered for a specific role but not yet placed. Every test calls the public `build(snapshot, 4800, 0, 0)`: 80 minutes out, so the countdown gate short-circuits before `AnniZone.isInZone` and that class never loads. Snapshots are parsed from inline JSON with `AnniSnapshot.fromJson`; a chip's colour is read from the seeking line's first sibling.

- **`aHardRsvpIsBlueInBothSpellings`**: A hard RSVP shows `HrdRSVP` in blue whether it arrives as `rsvp.notice` `hard` or as `attendance.notice_effective` `rsvp_hard`.
  - for each of `{"rsvp":{"notice":"hard"}}` and `{"attendance":{"notice_effective":"rsvp_hard"}}`: `build(...).getString()` equals `"HrdRSVP; seeking FILL for anni.wynnvets.org in 80min"`, and the first sibling's colour is `TextColor.fromLegacyFormat(BLUE)`.
- **`aSoftRsvpIsGreenInBothSpellings`**: A soft RSVP shows `SftRSVP` in green in both spellings.
  - for each of `{"rsvp":{"notice":"soft"}}` and `{"attendance":{"notice_effective":"rsvp_soft"}}`: the text equals `"SftRSVP; seeking FILL for anni.wynnvets.org in 80min"`, and the chip is green.
- **`walkInsAreYellowOnTimeAndRedLate`**: `attend_early` shows `NotRSVP` in yellow and `attend_late` shows `NotRSVP` in red.
  - `{"attendance":{"notice_effective":"attend_early"}}` gives `"NotRSVP; seeking FILL for anni.wynnvets.org in 80min"` with a yellow chip.
  - `{"attendance":{"notice_effective":"attend_late"}}` gives `"NotRSVP; seeking FILL for anni.wynnvets.org in 80min"` with a red chip.
- **`noNoticeFromEitherBlockReadsAsNoRsvp`**: An empty snapshot shows `NotRSVP` in gray.
  - `{}` gives `"NotRSVP; seeking FILL for anni.wynnvets.org in 80min"` with a gray chip.
- **`aRevokedRsvpFallsBackToTheAttendanceNotice`**: A revoked hard RSVP is ignored in favour of the attendance notice.
  - `{"rsvp":{"notice":"hard","revoked":true},"attendance":{"notice_effective":"attend_late"}}` gives `"NotRSVP; seeking FILL for anni.wynnvets.org in 80min"` with a red chip.
- **`anUnknownNoticePassesThroughUpperCasedInGray`**: A notice no arm knows is shown upper-cased in gray.
  - `{"attendance":{"notice_effective":"walk_in"}}` gives `"WALK_IN; seeking FILL for anni.wynnvets.org in 80min"` with a gray chip.
- **`anUnassignedWalkInStillSeesTheChip`**: A player on the `unassigned` board state (which is also how vets-anni sends a volunteer to sit out) still sees the chip.
  - `{"board":{"state":"unassigned"},"attendance":{"notice_effective":"attend_early"}}` gives `"NotRSVP; seeking FILL for anni.wynnvets.org in 80min"` with a yellow chip.
- **`aSittingOutPlayerGetsTheReasonAndNoChip`**: On `wont_assign` with a reason, the bar shows the reason and no chip, even with a hard RSVP.
  - `{"board":{"state":"wont_assign","wont_reason":"Sitting out"},"rsvp":{"notice":"hard"}}` gives `"anni.wynnvets.org: You won't be assigned - Sitting out"`.
- **`wontAssignWithNoReasonSaysThisAnni`**: On `wont_assign` with no reason, the line ends "to this anni".
  - `{"board":{"state":"wont_assign"}}` gives `"anni.wynnvets.org: You won't be assigned to this anni"`.
- **`aSpecificRoleNotYetPlacedShowsTheAssignedVariantAllTbd`**: A TANK registration with an empty board takes the assigned variant and shows TBD for all three chips.
  - `{"registration":{"roles":[{"role":"TANK"}]},"rsvp":{"notice":"hard"}}` gives `"anni.wynnvets.org: Role: TBD | Party TBD | World: TBD"`.
  - that text does not contain `"seeking"`.
- **`aFillRegistrationStaysOnTheSeekingLine`**: A FILL registration does not count as a committed slot, so the seeking line shows.
  - `{"registration":{"roles":[{"role":"FILL"}]}}` gives text ending in `"; seeking FILL for anni.wynnvets.org in 80min"`.

## `org.wynnvets.mwe.anni.mode`

### `AnniModeTest`

Test: [`AnniModeTest.java`](../src/test/java/org/wynnvets/mwe/anni/mode/AnniModeTest.java) · Subject: [`AnniMode`](../src/client/java/org/wynnvets/mwe/anni/mode/AnniMode.java)

`AnniMode` is the three-value enum (silent, passive, aggressive) for how much anni functionality a user runs, persisted in config as a lowercase string. The suite covers its two pure string conversions, `toConfigValue` and `fromString`, and pins that both fold case with `Locale.ROOT`, so a Turkish default locale neither writes a dotless "passıve" nor fails to read the modes back. It also pins that unknown or null input reads as `SILENT`. `fromConfig` is not covered because it reaches `VetsConfig`; the locale tests set `Locale.setDefault` and an `@AfterEach` restores it.

- **`toConfigValue_isPlainLowercaseUnderATurkishDefaultLocale`**: With a Turkish default locale, `toConfigValue()` yields "silent", "passive" and "aggressive" for the three modes.
  - with the default locale set to `tr`, `toConfigValue()` equals `"silent"` for `SILENT`, `"passive"` for `PASSIVE`, `"aggressive"` for `AGGRESSIVE`.
- **`fromString_roundTripsEveryModeUnderATurkishDefaultLocale`**: With a Turkish default, every mode is recovered from both its config value and its upper-case enum name.
  - with the default locale set to `tr`, for each `mode` in `AnniMode.values()` (`SILENT`, `PASSIVE`, `AGGRESSIVE`): `fromString(mode.toConfigValue())` is `mode`.
  - with the default locale set to `tr`, for each `mode` in `AnniMode.values()`: `fromString(mode.name())` is `mode` (the upper-case constant name, e.g. `"PASSIVE"`).
- **`fromString_readsTheDotlessSpellingAnOlderBuildWrote`**: The dotless-i spellings an older build wrote ("passıve", "aggressıve", "sılent") read back as `PASSIVE`, `AGGRESSIVE` and `SILENT`.
  - `fromString("pass" + U+0131 + "ve")` is `PASSIVE` (the dotless i, U+0131, is the source's escape for the lower-case i).
  - `fromString("aggress" + U+0131 + "ve")` is `AGGRESSIVE`.
  - `fromString("s" + U+0131 + "lent")` is `SILENT`.
- **`fromString_nullAndUnknownAreSilent`**: `null` and the unknown string "loud" both return `SILENT`.
  - `fromString(null)` is `SILENT`.
  - `fromString("loud")` is `SILENT`.

## `org.wynnvets.mwe.anni.outline`

### `AnniOutlinePaletteTest`

Test: [`AnniOutlinePaletteTest.java`](../src/test/java/org/wynnvets/mwe/anni/outline/AnniOutlinePaletteTest.java) · Subject: [`AnniOutlinePalette`](../src/client/java/org/wynnvets/mwe/anni/outline/AnniOutlinePalette.java)

`AnniOutlinePalette.chatFormattingForRole` maps an anni role code (fill, tank, heal/healer, tertiary, secondary, primary) to the `ChatFormatting` colour the highlight overlay uses, falling back to gray. The suite pins the seven-arm table, the gray fallback for null, empty, unknown and untrimmed input, case-insensitive matching, and that the fold is independent of the JVM default locale. The palette declares no static state and no Wynntils types, which is what lets it load in a test; the locale test sets `Locale.setDefault` and an `@AfterEach` restores it.

- **`chatFormattingForRole_mapsEveryKnownRole`**: FILL is white, TANK aqua, HEAL and HEALER both green, TERTIARY light purple, SECONDARY yellow and PRIMARY red.
  - `AnniOutlinePalette.chatFormattingForRole(role)` is the same instance as the listed `ChatFormatting` for: `"FILL"` WHITE, `"TANK"` AQUA, `"HEAL"` GREEN, `"HEALER"` GREEN, `"TERTIARY"` LIGHT_PURPLE, `"SECONDARY"` YELLOW, `"PRIMARY"` RED.
- **`chatFormattingForRole_nullAndUnknownFallBackToGray`**: `null`, "", "SUPPORT" and " TANK " (with surrounding spaces, so no trimming) all return gray.
  - `chatFormattingForRole(role)` is the same instance as `ChatFormatting.GRAY` for `role` = `null`, `""`, `"SUPPORT"`, `" TANK "` (the padded string is not trimmed, so it matches no arm).
- **`chatFormattingForRole_foldsCase`**: "tank", "Tank" and "tertiary" resolve to aqua, aqua and light purple.
  - `chatFormattingForRole(role)` is the same instance as the listed `ChatFormatting` for: `"tank"` AQUA, `"Tank"` AQUA, `"tertiary"` LIGHT_PURPLE.
- **`chatFormattingForRole_isUnaffectedByATurkishDefaultLocale`**: Regression guard: under a Turkish default, "fill", "primary" and "tertiary" still return white, red and light purple instead of falling through to gray.
  - with the default locale set to `tr`, `chatFormattingForRole(role)` is the same instance as the listed `ChatFormatting` for: `"fill"` WHITE, `"primary"` RED, `"tertiary"` LIGHT_PURPLE.

## `org.wynnvets.mwe.anni.render`

### `AnniCommandRendererTest`

Test: [`AnniCommandRendererTest.java`](../src/test/java/org/wynnvets/mwe/anni/render/AnniCommandRendererTest.java) · Subject: [`AnniCommandRenderer`](../src/client/java/org/wynnvets/mwe/anni/render/AnniCommandRenderer.java)

`AnniCommandRenderer` builds the chat output of `/wv anni`. The suite pins `render`'s null contract (an external user with an announced anni gets `null` so the caller falls back to the legacy stamp text), plus string-form goldens for the mode-switch block, the prediction line and the countdown, and boundary tables for the two hour formatters. It forces the external branch with `setExternalOverride(TRUE)` (reset to `null` after each test) to avoid reaching `VetsConfig`, and compares `Component.toString()` goldens, which are tied to Minecraft 1.21.11's string form. The prediction tests read the real clock and compute the expected date and time in the JVM's default zone, and skip via `assumeTrue` if the clock moved 30 minutes or more during the call.

- **`render_returnsNullForAnExternalUserWithAnAnniMoreThanTwoHoursOut`**: With the external override on, `render` of an announced anni 8 hours away returns `null`.
  - `AnniCommandRenderer.render(announcedIn(8L * 3600L))` is null, with the external override set to `Boolean.TRUE`; `announcedIn(s)` is a snapshot whose event has `announced` true and `stamp_epoch` = now + `s` seconds, here 28800 s (8 h) out.
- **`render_returnsNullForAnExternalUserWithAnAnniWithinTwoHours`**: With the external override on, `render` of an announced anni 600 seconds away returns `null`.
  - `AnniCommandRenderer.render(announcedIn(600L))` is null, with the external override set to `Boolean.TRUE`; the announced anni is 600 s (10 min) out.
- **`modeSwitchBlock_offersTheThreeModes`**: `renderModeSwitchBlock()` equals a golden with a yellow bold "Change Anni Mode?" header and Silent, Passive and Aggressive buttons, each running `/wv anni <mode>` and carrying a hover description.
  - `AnniCommandRenderer.renderModeSwitchBlock().toString()` equals a golden `Component.toString()`: root text `Change Anni Mode?` (yellow, bold) with siblings in order: ` (Click:)` (gray, not bold); a `\n` literal; one empty component holding three bracketed buttons joined by ` | ` (dark_gray). `[Silent]`: gray not-bold brackets, `Silent` white not-bold, click RunCommand `/wv anni silent`, hover ShowText gray `Anni features off`. `[Passive]`: dark_green not-bold brackets, `Passive` green not-bold, click RunCommand `/wv anni passive`, hover ShowText gray `Anni features recommended for most folks experienced at attending vets-annis`. `[Aggressive]`: dark_red not-bold brackets, `Aggressive` red not-bold, click RunCommand `/wv anni aggressive`, hover ShowText gray `Anni features recommended for folks attending their first vets-anni`.
- **`predictionLine_saysHowFarOffTheMedianIsAndWhen`**: For a median 72 hours out and a 10.6-hour window, the line equals a golden reading "72h from now", the date, "@HH:mm" and "±~3h", with the listed colours and styles.
  - Skips the test unless the clock moved less than 1800 s during the call: `Math.abs(Instant.now().getEpochSecond() - before) < 1800L` is true, where `before` is the epoch second read before building the line.
  - `line` (the `toString()` of `AnniCommandRenderer.predictionLine(prediction(median, 10.6))`, with `median` = `before` + 72 h and window 10.6 h) equals the `PREDICTION_72H` golden with its date and time tokens filled in by `dateOf(median)` (pattern `MMM d`, system default zone) and `timeOf(median)` (pattern `HH:mm`, system default zone). The golden is root `Prediction: ` (gold) with siblings in order: `72h` (yellow); ` from now ` (gold); `|` (white, bold); ` <date> ` (yellow); `@<time>` (gold); `±~3h` (gray, italic).
- **`predictionLine_saysOverdueOnceTheMedianHasPassed`**: For a median 3 hours in the past, the plain text is "Prediction: 3h overdue", then the date and time, then "±~3h".
  - Skips the test unless the clock moved less than 1800 s during the call: `Math.abs(Instant.now().getEpochSecond() - before) < 1800L` is true, where `before` is the epoch second read before building the line.
  - `line` (the plain `getString()` of `AnniCommandRenderer.predictionLine(prediction(median, 10.6))`, with `median` = `before` - 3 h and window 10.6 h) equals `"Prediction: 3h overdue | " + dateOf(median) + " @" + timeOf(median) + "±~3h"`, where `dateOf` uses pattern `MMM d` and `timeOf` uses pattern `HH:mm`, both in the system default zone.
- **`countdown_picksItsResolutionByMagnitude`**: `appendCountdown` gives aqua digits with dark-aqua units: seconds only for 59, "1m 00s" for 60 and "1h 0m" for 3600, as component goldens.
  - `countdown(59L).toString()` equals `empty[siblings=[literal{59}[style={color=aqua}], literal{s}[style={color=dark_aqua}]]]`; `countdown(n)` appends `AnniCommandRenderer.appendCountdown` output for `n` seconds onto an empty root component. So 59 s is a seconds-only form: `59` aqua, `s` dark_aqua.
  - `countdown(60L).toString()` equals `empty[siblings=[literal{1}[style={color=aqua}], literal{m }[style={color=dark_aqua}], literal{00}[style={color=aqua}], literal{s}[style={color=dark_aqua}]]]`. So 60 s is a minutes-and-seconds form: `1` aqua, `m ` dark_aqua, `00` aqua, `s` dark_aqua.
  - `countdown(3600L).toString()` equals `empty[siblings=[literal{1}[style={color=aqua}], literal{h }[style={color=dark_aqua}], literal{0}[style={color=aqua}], literal{m}[style={color=dark_aqua}]]]`. So 3600 s is an hours-and-minutes form: `1` aqua, `h ` dark_aqua, `0` aqua, `m` dark_aqua.
- **`countdown_atEachBoundary`**: The countdown text is "0s" for -5 and 0, "59s", "1m 00s", "1m 01s", "59m 59s", "1h 0m" and "2h 1m" for 7261.
  - Plain text (`getString()`) of `countdown(s)` equals the expected string, for each `s` in seconds: -5 -> `"0s"`; 0 -> `"0s"`; 59 -> `"59s"`; 60 -> `"1m 00s"`; 61 -> `"1m 01s"`; 3599 -> `"59m 59s"`; 3600 -> `"1h 0m"`; 7261 -> `"2h 1m"`.
- **`coarseHours_atEachBoundary`**: `formatCoarseHours` gives "<1m" for 0 or less, "1m" up to 119 seconds, "2m" at 120, "59m" at 3599, "1h" at 3600 and 5399, "2h" at 5400, and "60664h" for 60664 hours.
  - `AnniCommandRenderer.formatCoarseHours(s)` equals the expected string, for each `s` in seconds: -5 -> `"<1m"`; 0 -> `"<1m"`; 1 -> `"1m"`; 119 -> `"1m"`; 120 -> `"2m"`; 3599 -> `"59m"`; 3600 -> `"1h"`; 5399 -> `"1h"`; 5400 -> `"2h"`; 60664 * 3600 -> `"60664h"`.
- **`uncertainty_atEachBoundary`**: `formatUncertainty` gives "0h" for 0 or less, "1min" for 0.001, "29min" for 0.49, "1h" for 0.5 and 1.49, "2h" for 1.5 and "3h" for 3.06.
  - `AnniCommandRenderer.formatUncertainty(h)` equals the expected string, for each `h` in hours (double): -1.0 -> `"0h"`; 0.0 -> `"0h"`; 0.001 -> `"1min"`; 0.49 -> `"29min"`; 0.5 -> `"1h"`; 1.49 -> `"1h"`; 1.5 -> `"2h"`; 3.06 -> `"3h"`.

### `AnniHoverBuilderTest`

Test: [`AnniHoverBuilderTest.java`](../src/test/java/org/wynnvets/mwe/anni/render/AnniHoverBuilderTest.java) · Subject: [`AnniHoverBuilder`](../src/client/java/org/wynnvets/mwe/anni/render/AnniHoverBuilder.java), [`AnniOutlinePalette`](../src/client/java/org/wynnvets/mwe/anni/outline/AnniOutlinePalette.java)

`AnniHoverBuilder.roleColor` is the chat surfaces' entry point for the role-to-colour mapping, and it delegates to `AnniOutlinePalette.chatFormattingForRole`. The suite pins the seven-arm table, the gray fallback, case-insensitive matching and locale independence through that delegation. It also holds that chat and outline agree on every key, checked under `Locale.ROOT` and a Turkish default over one shared key list. The tests that change the default locale are restored by an `@AfterEach`.

- **`roleColor_mapsEveryKnownRole`**: FILL is white, TANK aqua, HEAL and HEALER both green, TERTIARY light purple, SECONDARY yellow and PRIMARY red.
  - `AnniHoverBuilder.roleColor(r)` is the same instance as the expected `ChatFormatting`, for each role `r`: `"FILL"` -> `WHITE`; `"TANK"` -> `AQUA`; `"HEAL"` -> `GREEN`; `"HEALER"` -> `GREEN` (same arm as HEAL); `"TERTIARY"` -> `LIGHT_PURPLE`; `"SECONDARY"` -> `YELLOW`; `"PRIMARY"` -> `RED`.
- **`roleColor_nullAndUnknownFallBackToGray`**: `null`, "", "SUPPORT" and " TANK " (untrimmed) all return gray.
  - `AnniHoverBuilder.roleColor(r)` is the same instance as `ChatFormatting.GRAY`, for each `r`: `null`; `""`; `"SUPPORT"`; `" TANK "` (padding is not trimmed).
- **`roleColor_foldsCase`**: "tank", "Tank" and "tertiary" resolve to aqua, aqua and light purple.
  - `AnniHoverBuilder.roleColor(r)` is the same instance as the expected colour, for each `r`: `"tank"` -> `AQUA`; `"Tank"` -> `AQUA`; `"tertiary"` -> `LIGHT_PURPLE`.
- **`roleColor_isUnaffectedByATurkishDefaultLocale`**: Under a Turkish default, "fill", "primary" and "tertiary" still return white, red and light purple.
  - With the default locale set to Turkish (`tr`), `AnniHoverBuilder.roleColor(r)` is the same instance as the expected colour, for each `r`: `"fill"` -> `WHITE`; `"primary"` -> `RED`; `"tertiary"` -> `LIGHT_PURPLE`.
- **`roleColor_turkishFoldWouldDivergeWithoutLocaleRoot`**: Under a Turkish default, `"fill".toUpperCase()` is "FİLL" while `toUpperCase(Locale.ROOT)` is "FILL"; it asserts on `String` directly, not on `roleColor`.
  - With the default locale set to Turkish (`tr`), `"fill".toUpperCase()` equals `"F" + U+0130 + "LL"` (U+0130 is the capital I with dot above, in place of ASCII `I`). This asserts on `String.toUpperCase`, not on `roleColor`.
  - `"fill".toUpperCase(Locale.ROOT)` equals `"FILL"` (default locale still Turkish).
- **`roleColor_agreesWithTheOutlinePaletteUnderLocaleRoot`**: With the default locale set to `Locale.ROOT`, `roleColor` returns the same constant as `chatFormattingForRole` for each of 18 keys (upper and lower case codes, null, empty, unknown, untrimmed).
  - With the default locale set to `Locale.ROOT`, for each `key` in `EQUIVALENCE_KEYS` (18 entries: `FILL`, `TANK`, `HEAL`, `HEALER`, `TERTIARY`, `SECONDARY`, `PRIMARY`, their lower-case forms `fill`, `tank`, `heal`, `healer`, `tertiary`, `secondary`, `primary`, then `null`, `""`, `SUPPORT`, ` TANK `): `AnniHoverBuilder.roleColor(key)` is the same instance as `AnniOutlinePalette.chatFormattingForRole(key)`.
- **`roleColor_agreesWithTheOutlinePaletteUnderATurkishDefaultLocale`**: The same 18-key agreement check holds under a Turkish default, where lower-case "fill", "primary" and "tertiary" are the keys a default-locale fold would break.
  - With the default locale set to Turkish (`tr`), for each `key` in the same 18-entry `EQUIVALENCE_KEYS` (upper-case and lower-case role codes, `null`, `""`, `SUPPORT`, ` TANK `): `AnniHoverBuilder.roleColor(key)` is the same instance as `AnniOutlinePalette.chatFormattingForRole(key)`.

### `AnniOnboardingBlocksTest`

Test: [`AnniOnboardingBlocksTest.java`](../src/test/java/org/wynnvets/mwe/anni/render/AnniOnboardingBlocksTest.java) · Subject: [`AnniOnboardingBlocks`](../src/client/java/org/wynnvets/mwe/anni/render/AnniOnboardingBlocks.java)

`AnniOnboardingBlocks.vetsNotAnnouncedRegistrationBlock` builds the registration block shown by `/wv anni` when no anni is announced. The suite holds `Component.toString()` goldens for the two shapes a test can build: the nudge to open the anni registration page for an unregistered user, and the fill-only explainer for a registered user with no roles. The goldens include text, colour, flags, click events and links, and are tied to Minecraft 1.21.11's string form. The has-roles shape is not covered because its role chips reach `VetsConfig`. Snapshots are built from JSON strings via `AnniSnapshot.fromJson`.

- **`registrationBlock_withNoRegistration_isTheUnregisteredNudge`**: With an empty snapshot (`{}`), the block equals the golden nudge, with a clickable `anni.wynnvets.org/me` link and an italic dark-red explanation.
  - `AnniOnboardingBlocks.vetsNotAnnouncedRegistrationBlock(snapshot("{}")).toString()` equals the `UNREGISTERED_NUDGE` golden (snapshot with no registration). The golden is root `You have not yet opened ` (red, bold) with siblings in order: `anni.wynnvets.org/me` (yellow, bold, underlined, click OpenUrl `https://anni.wynnvets.org/me`); `!` (red, not bold); a `\n` literal; `Doing so unlocks all sorts of features, and helps us organise vets guild annis!` (dark_red, not bold, italic).
- **`registrationBlock_whenNotRegistered_isTheSameNudge`**: A snapshot with `registration.registered` false produces exactly the same nudge golden.
  - `AnniOnboardingBlocks.vetsNotAnnouncedRegistrationBlock(notRegistered).toString()` equals the same `UNREGISTERED_NUDGE` golden, for `notRegistered` = a snapshot parsed from `{"registration": {"registered": false}}`.
- **`registrationBlock_registeredWithNoRoles_isTheFillOnlyExplainer`**: A snapshot with `registered` true and an empty `roles` array produces the golden fill-only explainer, with the fill-builds docs link and a "here" link to the registration page.
  - `AnniOnboardingBlocks.vetsNotAnnouncedRegistrationBlock(fillOnly).toString()` equals a golden `Component.toString()`, for `fillOnly` = a snapshot parsed from `{"registration": {"registered": true, "roles": []}}`. Root `You have not yet indicated any role capabilities!` (yellow, bold, italic) with siblings in order: `\n`; `We will try our best to slot you in a ` (gold, not bold, italic); `fill slot` (gold, not bold, italic, underlined, click OpenUrl `https://www.wynnvets.org/docs/guild/anni/#fill-builds`); `, but our capacity to do this is unfortunately limited!` (gold, not bold, italic); `\n`; `In the likely event we are unable to offer enough fill slots, we will prioritise those who have attended the fewest Annis.` (colour `#7E7E7E`, not bold, italic); `\n`; `If you believe you are able to claim a role, please do so ` (white, bold, italic); `here` (aqua, bold, italic, underlined, click OpenUrl `https://anni.wynnvets.org/me`); `!` (white, bold, italic).

### `AnniStatusSectionsTest`

Test: [`AnniStatusSectionsTest.java`](../src/test/java/org/wynnvets/mwe/anni/render/AnniStatusSectionsTest.java) · Subject: [`AnniStatusSections`](../src/client/java/org/wynnvets/mwe/anni/render/AnniStatusSections.java)

`AnniStatusSections` builds the individual lines of the `/wv anni` status output: assigned role, attendance chance, party assignment, the RSVP upgrade prompt, and the own-role lookup. The suite holds `Component.toString()` goldens (text, colour, flags, click and hover) for the arms that do not reach `VetsConfig`, and checks `findOwnRole` directly. The goldens are tied to Minecraft 1.21.11's string form, and snapshots are built from JSON strings via `AnniSnapshot.fromJson`. Arms that need `VetsConfig`, such as the registration section, role chips, the RSVP section and party blocks with a party, are not covered.

- **`assignedRole_withNoRoleToShow_isTbd`**: Both an empty snapshot and one whose party does not contain the player's `mc_uuid` render "Assigned Role: TBD" in gray.
  - `AnniStatusSections.assignedRoleSection(snapshot("{}")).toString()` equals `literal{Assigned Role: }[style={color=gray}, siblings=[literal{TBD}[style={color=gray}]]]` (label `Assigned Role: ` gray, then `TBD` gray) for an empty snapshot.
  - `AnniStatusSections.assignedRoleSection(notInTheParty).toString()` equals the same `Assigned Role: ` / `TBD` (both gray) golden, for `notInTheParty` = a snapshot with `mc_uuid` `cccc-3` and a board in state `party` whose only member is uuid `aaaa-1` with role `TANK`.
- **`attendance_inAParty_isAssigned`**: Board state "party" gives "Attendance Chance: ASSIGNED" in bold light purple.
  - `AnniStatusSections.attendanceSection(board("{\"state\": \"party\"}")).toString()` equals `literal{Attendance Chance: }[style={color=gray}, siblings=[literal{ASSIGNED}[style={color=light_purple,bold}]]]`; `board(j)` is a snapshot whose `board` is `j`. Label gray, `ASSIGNED` light_purple bold.
- **`attendance_wontAssign_isCouldNotAssign`**: Board state "wont_assign" gives "COULD NOT ASSIGN" in bold red.
  - `AnniStatusSections.attendanceSection(board("{\"state\": \"wont_assign\"}")).toString()` equals `literal{Attendance Chance: }[style={color=gray}, siblings=[literal{COULD NOT ASSIGN}[style={color=red,bold}]]]`. Label gray, `COULD NOT ASSIGN` red bold.
- **`attendance_unassignedWithNoAttendance_isTheUnknownBar`**: Board state "unassigned" with no attendance data gives the gray "attendance: unknown" text.
  - `AnniStatusSections.attendanceSection(board("{\"state\": \"unassigned\"}")).toString()` equals `literal{Attendance Chance: }[style={color=gray}, siblings=[literal{attendance: unknown}[style={color=gray}]]]`. Label gray, `attendance: unknown` gray (board has no attendance).
- **`attendance_isDroppedForAnyOtherState`**: State "unplaced", a board with no state, and an empty snapshot all return `null`.
  - `AnniStatusSections.attendanceSection(board("{\"state\": \"unplaced\"}"))` is null.
  - `AnniStatusSections.attendanceSection(board("{}"))` is null (board with no state).
  - `AnniStatusSections.attendanceSection(snapshot("{}"))` is null (no board).
- **`board_withNoBoard_isEmpty`**: An empty snapshot and an empty board both render the `empty` component.
  - `boardLine(snapshot("{}"))` equals `"empty"`; `boardLine(s)` is `AnniStatusSections.boardSection(s).toString()`. A snapshot with no board gives an empty component.
  - `boardLine(board("{}"))` equals `"empty"` (a board with no state gives an empty component).
- **`board_unplaced`**: State "unplaced" renders a yellow "unplaced" followed by the gray "register & RSVP to join the placement queue" text.
  - `boardLine(board("{\"state\": \"unplaced\"}"))` equals `literal{Party Assignment: }[style={color=gray}, siblings=[literal{unplaced}[style={color=yellow}], literal{ - register & RSVP to join the placement queue}[style={color=gray}]]]`. Label gray, `unplaced` yellow, ` - register & RSVP to join the placement queue` gray.
- **`board_unassigned`**: State "unassigned" renders a gold "unassigned", a dark-gray separator and a gray "Awaiting placement".
  - `boardLine(board("{\"state\": \"unassigned\"}"))` equals `literal{Party Assignment: }[style={color=gray}, siblings=[literal{unassigned}[style={color=gold}], literal{ - }[style={color=dark_gray}], literal{Awaiting placement}[style={color=gray}]]]`. Label gray, `unassigned` gold, ` - ` dark_gray, `Awaiting placement` gray.
- **`board_wontAssign_withAReason`**: State "wont_assign" with `wont_reason` "no healer slots" renders a bold red "won't assign" followed by " - no healer slots".
  - `boardLine(...)` for a board `{"state": "wont_assign", "wont_reason": "no healer slots"}` equals `literal{Party Assignment: }[style={color=gray}, siblings=[literal{won't assign}[style={color=red,bold}], literal{ - no healer slots}[style={color=gray}]]]`. Label gray, `won't assign` red bold, ` - no healer slots` gray.
- **`board_wontAssign_withNoOrAnEmptyReason`**: State "wont_assign" with a missing or empty `wont_reason` renders only the bold red "won't assign".
  - `boardLine(board("{\"state\": \"wont_assign\"}"))` equals `literal{Party Assignment: }[style={color=gray}, siblings=[literal{won't assign}[style={color=red,bold}]]]` (no reason segment).
  - `boardLine(board("{\"state\": \"wont_assign\", \"wont_reason\": \"\"}"))` equals the same golden: label gray, `won't assign` red bold, no reason segment for an empty reason.
- **`board_anUnknownState_isPrintedLowerCased`**: An unrecognised state "Mystery" is printed lower-cased as gray "mystery".
  - `boardLine(board("{\"state\": \"Mystery\"}"))` equals `literal{Party Assignment: }[style={color=gray}, siblings=[literal{mystery}[style={color=gray}]]]`. State `Mystery` is printed lower-cased as `mystery`, gray.
- **`board_inAPartyWithNoPartyYet_isDetailsPending`**: State "party" with no party object renders gray "party (details pending)".
  - `boardLine(board("{\"state\": \"party\"}"))` equals `literal{Party Assignment: }[style={color=gray}, siblings=[literal{party (details pending)}[style={color=gray}]]]`. Label gray, `party (details pending)` gray.
- **`upgradePrompt_offersHardThenSoft`**: `rsvpUpgradePrompt()` equals a golden with a hover on the backslash-rsvp Discord command and clickable "Hard" and "Soft" buttons that suggest `/wv anni rsvp hard` and `/wv anni rsvp soft`, each with a requirement hover.
  - `AnniStatusSections.rsvpUpgradePrompt().toString()` equals a golden `Component.toString()`: an empty component (style gray) with siblings in order: `(Buttons to upgrade your ` (gray); `\rsvp` (gray, hover ShowText `\rsvp is a Discord command in vetscord's #annihilation channel.` newline `It does the same thing as /wv anni rsvp - entirely optional from in-game.`); `: ` (gray); an empty component (click SuggestCommand `/wv anni rsvp hard`, hover ShowText gray `HRSVP - confirms you WILL be there.` newline `Requirement: arrive at least 20 mins before anni starts, or your slot may be reassigned to a walk-in.` newline `Click to suggest /wv anni rsvp hard.`) holding `[` dark_aqua, `Hard` aqua, `]` dark_aqua; ` ` (gray); an empty component (click SuggestCommand `/wv anni rsvp soft`, hover ShowText gray `SRSVP - confirms you'll PROBABLY be there.` newline `Requirement: arrive at least 40 mins before anni starts, or your slot may be reassigned.` newline `Click to suggest /wv anni rsvp soft.`) holding `[` dark_green, `Soft` green, `]` dark_green; `)` (gray).
- **`findOwnRole_matchesTheUuidIgnoringCase`**: Looking up "aaaa-1" and "BBBB-2" in a party holding "AAAA-1" and "bbbb-2" returns "TANK" and "FILL".
  - `AnniStatusSections.findOwnRole(p, "aaaa-1")` equals `"TANK"`, for `p` = a party with members uuid `AAAA-1` role `TANK` and uuid `bbbb-2` role `FILL` (uuid compared ignoring case).
  - `AnniStatusSections.findOwnRole(p, "BBBB-2")` equals `"FILL"` for the same `p`.
- **`findOwnRole_isNullWhenAbsentOrUnknown`**: An absent UUID, a null UUID and a null party all return `null`.
  - `AnniStatusSections.findOwnRole(p, "cccc-3")` is null, for `p` = a party whose only member is uuid `aaaa-1` role `TANK`.
  - `AnniStatusSections.findOwnRole(p, null)` is null for the same `p`.
  - `AnniStatusSections.findOwnRole(party("null"), "aaaa-1")` is null, where the party's `members` JSON is `null`.

## `org.wynnvets.mwe.anni.state`

### `AnniSnapshotsTest`

Test: [`AnniSnapshotsTest.java`](../src/test/java/org/wynnvets/mwe/anni/state/AnniSnapshotsTest.java) · Subject: [`AnniSnapshots`](../src/client/java/org/wynnvets/mwe/anni/state/AnniSnapshots.java)

`AnniSnapshots` holds the four shared accessors for fields the anni diffing code reads from an `AnniSnapshot`: the assigned role, the party ordinal, the party world and the RSVP notice. The suite pins that each returns the field when present and `null` when any nested object is missing or the snapshot itself is `null`, which is a legal cache state. It also pins two arms that look redundant: party ordinal 0 reads as unassigned, and a revoked RSVP reads as absent. Fixtures are built from `JsonObject`s through `AnniSnapshot.fromJson`, with no mocking, and the null cases are one method per accessor so a single deleted guard shows up as a single failure.

- **`allFourReadTheirFieldOffAPopulatedSnapshot`**: A fully populated snapshot gives role "TANK", party ordinal 3, party world "EU5" and RSVP notice "hard".
  - `AnniSnapshots.role(snapshot)` equals `"TANK"`, where `snapshot` has `board.role` = `"TANK"`, `board.party` = {ordinal `3`, world `"EU5"`} and `rsvp` = {notice `"hard"`, revoked `false`}.
  - `AnniSnapshots.partyOrdinal(snapshot)` equals `Integer.valueOf(3)`.
  - `AnniSnapshots.partyWorld(snapshot)` equals `"EU5"`.
  - `AnniSnapshots.rsvpNotice(snapshot)` equals `"hard"`.
- **`allFourReturnNullOnAnEmptySnapshot`**: `fromJson({})` gives `null` from all four accessors.
  - `AnniSnapshots.role(empty)` is null, where `empty` = `AnniSnapshot.fromJson({})` (no `board`, no `rsvp`).
  - `AnniSnapshots.partyOrdinal(empty)` is null.
  - `AnniSnapshots.partyWorld(empty)` is null.
  - `AnniSnapshots.rsvpNotice(empty)` is null.
- **`partyAccessorsReturnNullWhenTheBoardHasNoParty`**: A board with a role but no party gives role "HEALER" while party ordinal and party world are `null`.
  - `AnniSnapshots.role(snapshot)` equals `"HEALER"`, where `snapshot` has a `board` with `role` `"HEALER"` and no `party`.
  - `AnniSnapshots.partyOrdinal(snapshot)` is null.
  - `AnniSnapshots.partyWorld(snapshot)` is null.
- **`roleReturnsNullOnANullSnapshot`**: `role(null)` returns `null`.
  - `AnniSnapshots.role(null)` is null.
- **`partyOrdinalReturnsNullOnANullSnapshot`**: `partyOrdinal(null)` returns `null`.
  - `AnniSnapshots.partyOrdinal(null)` is null.
- **`partyWorldReturnsNullOnANullSnapshot`**: `partyWorld(null)` returns `null`.
  - `AnniSnapshots.partyWorld(null)` is null.
- **`rsvpNoticeReturnsNullOnANullSnapshot`**: `rsvpNotice(null)` returns `null`.
  - `AnniSnapshots.rsvpNotice(null)` is null.
- **`partyOrdinalReturnsNullForOrdinalZeroRatherThanZero`**: A party object with no ordinal (which collapses to 0) yields `null` from `partyOrdinal`, while `partyWorld` still reads "EU5".
  - `AnniSnapshots.partyOrdinal(snapshot)` is null, where `snapshot` has a `board.party` with world `"EU5"` and no `ordinal` key (so the primitive reads as 0 but must not be returned as 0).
  - `AnniSnapshots.partyWorld(snapshot)` equals `"EU5"`.
- **`rsvpNoticeReturnsNullWhenTheRsvpIsRevoked`**: An RSVP with notice "hard" and `revoked` true gives `null`, while the populated fixture (revoked false) gives "hard".
  - `AnniSnapshots.rsvpNotice(revoked)` is null, where `revoked` has `rsvp` = {notice `"hard"`, revoked `true`}.
  - `AnniSnapshots.rsvpNotice(populated())` equals `"hard"` (the same notice with `revoked` `false`).

### `AnniWindowsTest`

Test: [`AnniWindowsTest.java`](../src/test/java/org/wynnvets/mwe/anni/state/AnniWindowsTest.java) · Subject: [`AnniWindows`](../src/client/java/org/wynnvets/mwe/anni/state/AnniWindows.java)

`AnniWindows` holds the shared anni timing constants and two predicates: `inHotWindow(secondsUntil)`, true from two hours before the anni stamp until 30 minutes after it, and `hotWindowClosed(anchorStamp, now)`, the watcher's inverted form of the close edge. The suite pins the three constants and checks both predicates at ten boundary values (-1801, -1800, -1799, 0, 5399, 5400, 5401, 7199, 7200, 7201), with both edges inclusive and the two predicates breaking at the same second. There is no clock: `now` is passed in.

- **`constantsHoldTheValuesTheCallSitesUsedToDeclare`**: `HOT_WINDOW_BEFORE_SECONDS` is 7200, `HOT_WINDOW_AFTER_SECONDS` is 1800 and `BAR_WINDOW_SECONDS` is 5400.
  - `AnniWindows.HOT_WINDOW_BEFORE_SECONDS` equals `7200L` (2 h, the open edge).
  - `AnniWindows.HOT_WINDOW_AFTER_SECONDS` equals `1800L` (30 min, the close edge after the stamp).
  - `AnniWindows.BAR_WINDOW_SECONDS` equals `5400L` (90 min).
- **`barWindowIsAConstantAndNotAPredicate`**: `BAR_WINDOW_SECONDS` equals 90 times 60; the window deliberately has no predicate to test.
  - `AnniWindows.BAR_WINDOW_SECONDS` equals `90L * 60L` (5400).
- **`inHotWindow_openEdgeIsInclusive`**: `inHotWindow` is true for 7199 and 7200 seconds until the anni and false for 7201.
  - `AnniWindows.inHotWindow(s)` for `s` = `7199L` is true, `7200L` is true, `7201L` is false.
- **`inHotWindow_closeEdgeIsInclusive`**: `inHotWindow` is false for -1801 and true for -1800 and -1799.
  - `AnniWindows.inHotWindow(s)` for `s` = `-1801L` is false, `-1800L` is true, `-1799L` is true.
- **`inHotWindow_acceptsEveryInteriorBoundaryValue`**: `inHotWindow` is true for 0, 5399, 5400 and 5401, so the 90-minute mark is interior to the window.
  - `AnniWindows.inHotWindow(s)` is true for `s` = `0L` (the stamp itself), `5399L`, `5400L`, `5401L`.
- **`inHotWindow_isFalseOnlyOutsideBothEdges`**: Across all ten boundary values, `inHotWindow` equals "at least -1800 and at most 7200".
  - for each `secondsUntil` in `-1801L, -1800L, -1799L, 0L, 5399L, 5400L, 5401L, 7199L, 7200L, 7201L`: `AnniWindows.inHotWindow(secondsUntil)` equals `secondsUntil >= -1800L && secondsUntil <= 7200L`; that is false for `-1801L` and `7201L`, true for the other eight.
- **`hotWindowClosed_agreesWithInHotWindowsCloseEdge`**: `hotWindowClosed` is false when the anchor is 1799 or 1800 seconds before `now` and true at 1801.
  - `AnniWindows.hotWindowClosed(NOW + s, NOW)` for `s` = `-1799L` is false, `-1800L` is false, `-1801L` is true, where `NOW` = `1_700_000_000L`.
- **`hotWindowClosed_isTheComplementOfTheCloseEdgeAtEveryBoundaryValue`**: Across all ten boundary values, `hotWindowClosed` is true exactly when the seconds-until value is below -1800.
  - for each `secondsUntil` in `-1801L, -1800L, -1799L, 0L, 5399L, 5400L, 5401L, 7199L, 7200L, 7201L`: `AnniWindows.hotWindowClosed(NOW + secondsUntil, NOW)` equals `secondsUntil < -1800L`; that is true only for `-1801L` and false for the other nine.
- **`hotWindowClosed_isFalseForAStampStillInTheFuture`**: An anchor 7200 or 7201 seconds ahead of `now` is not closed.
  - `AnniWindows.hotWindowClosed(NOW + 7200L, NOW)` is false.
  - `AnniWindows.hotWindowClosed(NOW + 7201L, NOW)` is false.

## `org.wynnvets.rendering.colors`

### `ColorMathTest`

Test: [`ColorMathTest.java`](../src/test/java/org/wynnvets/rendering/colors/ColorMathTest.java) · Subject: [`ColorMath`](../src/client/java/org/wynnvets/rendering/colors/ColorMath.java)

`ColorMath.interpolateRgb` linearly interpolates the three 8-bit channels of two `0xRRGGBB` colours and is the mod's single RGB lerp. The suite pins in-range behaviour (endpoints, midpoint rounding half up, independent channels, alpha discarded) and, deliberately, that `t` is not clamped: values outside 0 to 1 overshoot and bleed into the neighbouring channel or the sign bit, so the exact out-of-range literals are asserted. `ColorMath` has no static state or Minecraft imports, so the suite loads no Minecraft classes.

- **`endpointsReturnTheirInputColour`**: Black to white gives black at `t` 0 and white at `t` 1.
  - `ColorMath.interpolateRgb(BLACK, WHITE, 0f)` equals `0x000000`, where `BLACK` = `0x000000` and `WHITE` = `0xFFFFFF`.
  - `ColorMath.interpolateRgb(BLACK, WHITE, 1f)` equals `0xFFFFFF`.
- **`theMidpointRoundsHalfUp`**: At `t` 0.5, black to white gives 0x808080 and black to red gives 0x800000, since 127.5 rounds to 128.
  - `ColorMath.interpolateRgb(BLACK, WHITE, 0.5f)` equals `0x808080`.
  - `ColorMath.interpolateRgb(BLACK, 0xFF0000, 0.5f)` equals `0x800000`.
- **`channelsAreInterpolatedIndependently`**: Interpolating 0x102030 to 0x405060 at 0.5 gives 0x283848.
  - `ColorMath.interpolateRgb(0x102030, 0x405060, 0.5f)` equals `0x283848`.
- **`anAlphaByteOnTheInputIsDiscarded`**: ARGB inputs 0xFF123456 at `t` 0 give 0x123456, and 0xFF000000 at `t` 1 gives 0x000000, so no alpha bits reach the result.
  - `ColorMath.interpolateRgb(0xFF123456, 0xFF123456, 0f)` equals `0x123456`.
  - `ColorMath.interpolateRgb(0xFF000000, 0xFF000000, 1f)` equals `0x000000`.
- **`tAboveOneOverShootsAndBleedsIntoTheNeighbouringChannel`**: Black to white gives 0x017F7F7F at `t` 1.5 and 0x01FFFFFE at `t` 2, with no clamp.
  - `ColorMath.interpolateRgb(BLACK, WHITE, 1.5f)` equals `0x017F7F7F`.
  - `ColorMath.interpolateRgb(BLACK, WHITE, 2f)` equals `0x01FFFFFE`.
- **`tBelowZeroUnderShootsIntoNegativeChannelsAndTheSignBit`**: Black to white at `t` -0.5 gives 0xFFFFFF81, as negative channels collapse the whole word.
  - `ColorMath.interpolateRgb(BLACK, WHITE, -0.5f)` equals `0xFFFFFF81`.

## `org.wynnvets.util`

### `ConfigValueTextTest`

Test: [`ConfigValueTextTest.java`](../src/test/java/org/wynnvets/util/ConfigValueTextTest.java) · Subject: [`ConfigValueText`](../src/client/java/org/wynnvets/util/ConfigValueText.java)

`ConfigValueText` renders a "this config key is currently that value" chat line for boolean, int, tri-state and string values, with a `Form` (list or single, controlling indent) and a `Verb` (equals, set to, reset to). The suite pins that every line is a gray label run with exactly one value sibling, the value colour for each kind, and that form and verb vary independently. A shared `assertLine` helper checks the full text, the sibling count, the gray label and the value colour. It does not pin which form and verb any real call site passes.

Asserting helpers:

- **`assertLine`**: Given a line component, a label, a value text and a value `ChatFormatting`: the line's `getString()` equals `label + valueText`; the line has exactly 1 sibling; the line's own style colour value equals `ChatFormatting.GRAY`'s colour; the sibling's `getString()` equals `valueText`; the sibling's style colour value equals `valueColour`'s colour.

Tests:

- **`listFormIndentsAndSingleFormDoesNot`**: A boolean line in `LIST` form starts with two spaces and in `SINGLE` form does not, with a green `true` value either way.
  - `assertLine` on `ConfigValueText.booleanLine(Form.LIST, "printMOTD", Verb.EQUALS, true)` with label `"  printMOTD = "` (two leading spaces), value text `"true"`, value colour `GREEN`.
  - `assertLine` on `ConfigValueText.booleanLine(Form.SINGLE, "printMOTD", Verb.EQUALS, true)` with label `"printMOTD = "` (no indent), value text `"true"`, value colour `GREEN`.
- **`allThreeVerbsRender`**: An int line renders ` = `, ` set to ` and ` reset to ` separators for the three verbs, each with an aqua value.
  - `assertLine` on `ConfigValueText.intLine(Form.SINGLE, "glintOpacity", Verb.EQUALS, 69L)` with label `"glintOpacity = "`, value text `"69"`, value colour `AQUA`.
  - `assertLine` on `ConfigValueText.intLine(Form.SINGLE, "glintOpacity", Verb.SET_TO, 69L)` with label `"glintOpacity set to "`, value text `"69"`, value colour `AQUA`.
  - `assertLine` on `ConfigValueText.intLine(Form.SINGLE, "glintOpacity", Verb.RESET_TO, 69L)` with label `"glintOpacity reset to "`, value text `"69"`, value colour `AQUA`.
- **`formAndVerbCompose`**: A `LIST` form with the `RESET_TO` verb gives `"  printANNI reset to "` followed by a red `false`.
  - `assertLine` on `ConfigValueText.booleanLine(Form.LIST, "printANNI", Verb.RESET_TO, false)` with label `"  printANNI reset to "` (two leading spaces), value text `"false"`, value colour `RED`.
- **`booleanLine_isGreenWhenTrueAndRedWhenFalse`**: A true value renders green and a false value renders red.
  - `assertLine` on `ConfigValueText.booleanLine(Form.SINGLE, "handleSpoilers", Verb.SET_TO, true)` with label `"handleSpoilers set to "`, value text `"true"`, value colour `GREEN`.
  - `assertLine` on `ConfigValueText.booleanLine(Form.SINGLE, "handleSpoilers", Verb.SET_TO, false)` with label `"handleSpoilers set to "`, value text `"false"`, value colour `RED`.
- **`intLine_isAquaWhateverTheNumber`**: For 0, 50, 100, -1 and `Long.MAX_VALUE`, the value text is the number and the colour is always aqua.
  - For each `value` in `0L`, `50L`, `100L`, `-1L`, `Long.MAX_VALUE`: `assertLine` on `ConfigValueText.intLine(Form.SINGLE, "glintOpacity", Verb.EQUALS, value)` with label `"glintOpacity = "`, value text `String.valueOf(value)`, value colour `AQUA`.
- **`triStateLine_rendersNullAsAYellowDefault`**: A `null` tri-state renders the word `default` in yellow.
  - `assertLine` on `ConfigValueText.triStateLine(Form.LIST, "vetsAnniRsvp", Verb.EQUALS, null)` with label `"  vetsAnniRsvp = "` (two leading spaces), value text `"default"`, value colour `YELLOW`.
- **`triStateLine_matchesBooleanLineExactlyWhenSet`**: For true and false, the tri-state line has the same text, sibling count and value colour as the boolean line.
  - For each `value` in `true`, `false`: `tri.getString()` equals `bool.getString()`, where `tri` = `triStateLine(Form.SINGLE, "vetsAnniRsvp", Verb.SET_TO, value)` and `bool` = `booleanLine` with the same arguments.
  - For each `value` in `true`, `false`: `tri.getSiblings().size()` equals `bool.getSiblings().size()`.
  - For each `value` in `true`, `false`: the first sibling's style colour value of `tri` equals that of `bool`.
- **`stringLine_appendsANamedColourValueUntouched`**: A pre-styled aqua `diamond` value is appended as given after the list-form label.
  - `assertLine` on `ConfigValueText.stringLine(Form.LIST, "legacyItemForegroundSprite", Verb.EQUALS, sprite)`, where `sprite` is the literal `diamond` styled `AQUA`, with label `"  legacyItemForegroundSprite = "` (two leading spaces), value text `"diamond"`, value colour `AQUA`.
- **`stringLine_preservesAnArbitraryRgbAndDoesNotLeakTheLabelStyleIntoIt`**: A value styled with RGB `0x663399` keeps that colour, while the label stays gray, with one value sibling.
  - `line.getString()` equals `"vetsAnniGradientTop reset to rebeccapurple"`, where `line` = `ConfigValueText.stringLine(Form.SINGLE, "vetsAnniGradientTop", Verb.RESET_TO, colour)` and `colour` is the literal `rebeccapurple` styled with RGB `0x663399`.
  - `line.getSiblings().size()` equals 1.
  - `line.getStyle().getColor().getValue()` equals `ChatFormatting.GRAY`'s colour int (the label is gray).
  - The first sibling's style colour value equals `0x663399` (the supplied RGB is kept, not mapped to a named colour).
- **`everyKindProducesALabelRunPlusExactlyOneValueSibling`**: For boolean, int, tri-state and string lines, each has a gray label, exactly one sibling, and that sibling has no children of its own.
  - For each `line` in four lines (`booleanLine(Form.LIST, "k", Verb.EQUALS, true)`, `intLine(Form.SINGLE, "k", Verb.SET_TO, 1L)`, `triStateLine(Form.SINGLE, "k", Verb.RESET_TO, null)`, `stringLine(Form.LIST, "k", Verb.EQUALS, literal "v" styled AQUA)`): `line.getSiblings().size()` equals 1.
  - For each of the same four lines: `line.getStyle().getColor().getValue()` equals `ChatFormatting.GRAY`'s colour int.
  - For each of the same four lines: the first sibling has 0 siblings of its own (equals 0).

### `JsonTest`

Test: [`JsonTest.java`](../src/test/java/org/wynnvets/util/JsonTest.java) · Subject: [`Json`](../src/client/java/org/wynnvets/util/Json.java)

`Json` holds the mod's shared Gson instance and four tolerant field accessors over an already-parsed `JsonObject`: `optString`, `stringOrNull`, `stringOrEmpty` and `optInt`. The suite pins one policy: a missing key, JSON null, wrong-typed value or null receiver gives the caller's fallback, plus the Gson quirks that still pass through (primitives coerce, a one-element array is unwrapped, `optInt` truncates and narrows). It checks return values only: the warn logged on wrong-typed values and null receivers is not asserted because there is no log-capture seam.

- **`optString_readsAStringVerbatimAndIgnoresTheFallback`**: A present string is returned as is, and an empty string is a present value, not a miss.
  - `Json.optString(withString("value"), "k", "fb")` equals `"value"` (`withString(v)` builds a `JsonObject` with property `k` set to the string `v`)
  - `Json.optString(withString(""), "k", "fb")` equals `""` (an empty string is a present value, not a miss)
- **`optString_coercesPrimitivesRatherThanRejectingThem`**: A numeric `42` reads as `"42"` and a boolean `true` reads as `"true"`, with no fallback.
  - `Json.optString(number, "k", "fb")` equals `"42"` (`number` is an object with `k` set to the number 42)
  - `Json.optString(bool, "k", "fb")` equals `"true"` (`bool` is an object with `k` set to the boolean true)
- **`optString_missingKeyAndJsonNullBothGiveTheFallback`**: An absent key and a JSON null both return the fallback.
  - `Json.optString(new JsonObject(), "k", "fb")` equals `"fb"` (key missing)
  - `Json.optString(withValue(JsonNull.INSTANCE), "k", "fb")` equals `"fb"` (`withValue(e)` builds an object with `k` set to element `e`; here `k` is JSON null)
- **`optString_aWrongTypedValueGivesTheFallback`**: An object value and a two-element array both return the fallback.
  - `Json.optString(withValue(new JsonObject()), "k", "fb")` equals `"fb"` (`k` is an object)
  - `Json.optString(withValue(array("a", "b")), "k", "fb")` equals `"fb"` (`k` is a two-element array of strings)
- **`optString_aNullReceiverGivesTheFallback`**: A `null` object returns the fallback instead of throwing.
  - `Json.optString(null, "k", "fb")` equals `"fb"`
- **`optString_aSingletonArrayIsUnwrappedByGsonRatherThanRejected`**: A one-element array `["inner"]` reads as `"inner"`, so the fallback does not fire.
  - `Json.optString(withValue(array("inner")), "k", "fb")` equals `"inner"` (`k` is a one-element array, unwrapped to its element)
- **`optString_theFallbackIsReturnedVerbatimIncludingNull`**: A `null` fallback on a missing key returns `null`.
  - `Json.optString(new JsonObject(), "k", null)` is null (key missing, fallback is null)
- **`stringOrNull_isOptStringWithANullFallback`**: A present value is returned, and missing, JSON null, wrong-typed and null-receiver cases all return `null`.
  - `Json.stringOrNull(withString("value"), "k")` equals `"value"`
  - `Json.stringOrNull(new JsonObject(), "k")` is null (missing)
  - `Json.stringOrNull(withValue(JsonNull.INSTANCE), "k")` is null (JSON null)
  - `Json.stringOrNull(withValue(new JsonObject()), "k")` is null (wrong type)
  - `Json.stringOrNull(null, "k")` is null (null receiver)
- **`stringOrEmpty_isOptStringWithAnEmptyStringFallback`**: A present value is returned, and missing, JSON null, wrong-typed and null-receiver cases all return `""`.
  - `Json.stringOrEmpty(withString("value"), "k")` equals `"value"`
  - `Json.stringOrEmpty(new JsonObject(), "k")` equals `""` (missing)
  - `Json.stringOrEmpty(withValue(JsonNull.INSTANCE), "k")` equals `""` (JSON null)
  - `Json.stringOrEmpty(withValue(new JsonObject()), "k")` equals `""` (wrong type)
  - `Json.stringOrEmpty(null, "k")` equals `""` (null receiver)
- **`theThreeStringEntryPointsDifferOnlyOnWhatTheMissAnswers`**: The three accessors agree on present and present-but-empty values, and each returns only its own fallback on every failure mode.
  - With `present = withString("value")`: `Json.optString(present, "k", "fb")`, `Json.stringOrNull(present, "k")` and `Json.stringOrEmpty(present, "k")` each equal `"value"`
  - With `empty = withString("")`: `Json.optString(empty, "k", "fb")`, `Json.stringOrNull(empty, "k")` and `Json.stringOrEmpty(empty, "k")` each equal `""`
  - For each `miss` in an empty object, an object with `k` set to JSON null, and an object with `k` set to an object: `Json.optString(miss, "k", "fb")` equals `"fb"`, `Json.stringOrNull(miss, "k")` is null, and `Json.stringOrEmpty(miss, "k")` equals `""`
  - With a null receiver: `Json.optString(null, "k", "fb")` equals `"fb"`, `Json.stringOrNull(null, "k")` is null, and `Json.stringOrEmpty(null, "k")` equals `""`
- **`optInt_readsANumber`**: A numeric `42` reads as 42.
  - `Json.optInt(withNumber(42), "k", -1)` equals 42 (`withNumber(n)` builds an object with `k` set to the number `n`)
- **`optInt_fallsBackOnAllThreeAxes`**: Missing key, JSON null, object, two-element array and null receiver all return the fallback of -1.
  - `Json.optInt(new JsonObject(), "k", -1)` equals -1 (missing)
  - `Json.optInt(withValue(JsonNull.INSTANCE), "k", -1)` equals -1 (JSON null)
  - `Json.optInt(withValue(new JsonObject()), "k", -1)` equals -1 (`k` is an object)
  - `Json.optInt(withValue(array("a", "b")), "k", -1)` equals -1 (`k` is a two-element array)
  - `Json.optInt(null, "k", -1)` equals -1 (null receiver)
- **`optInt_parsesANumericStringAndFallsBackOnAnUnparseableOne`**: The string `"42"` reads as 42, and `"not a number"` returns the fallback.
  - `Json.optInt(withString("42"), "k", -1)` equals 42
  - `Json.optInt(withString("not a number"), "k", -1)` equals -1
- **`optInt_truncatesADecimalTowardZeroRatherThanRejectingIt`**: `3.9` reads as 3 and `-3.9` reads as -3.
  - `Json.optInt(withNumber(3.9), "k", -1)` equals 3
  - `Json.optInt(withNumber(-3.9), "k", -1)` equals -3
- **`optInt_anOverflowingLiteralNarrowsRatherThanFallingBack`**: The long `4294967298` is narrowed to an int (2) and does not return the fallback.
  - `Json.optInt(withNumber(4_294_967_298L), "k", -1)` equals `(int) 4_294_967_298L` (which is 2; the long is narrowed, not replaced by the fallback)
- **`optInt_aSingletonArrayIsUnwrappedToo`**: A one-element array `[7]` reads as 7.
  - `Json.optInt(withValue(array(7)), "k", -1)` equals 7 (`k` is a one-element array holding 7, unwrapped)

### `TickDelaysTest`

Test: [`TickDelaysTest.java`](../src/test/java/org/wynnvets/util/TickDelaysTest.java) · Subject: [`TickDelays`](../src/client/java/org/wynnvets/util/TickDelays.java)

`TickDelays` schedules delayed work on the client thread through Wynntils' tick scheduler. The suite covers its two package-private seams that need no scheduler: `ticksFor`, which converts milliseconds to 50 ms ticks rounding up (floored at 0 and clamped to `Integer.MAX_VALUE`), and `guarded`, which wraps a body in a new task that runs it and swallows anything it throws. `runAfter` itself is not exercised because it calls `Managers.TickScheduler`.

- **`ticksFor_roundsUpToWholeTicks`**: 0 ms gives 0 ticks, 1, 49 and 50 ms give 1 tick, and 51 ms gives 2.
  - `TickDelays.ticksFor(ms)` equals the listed int for `ms` = `0` -> `0`, `1` -> `1`, `49` -> `1`, `50` -> `1`, `51` -> `2`. Argument is milliseconds, one tick per 50 ms.
- **`ticksFor_theCallersDelays`**: 2000, 3000 and 5000 ms give 40, 60 and 100 ticks.
  - `TickDelays.ticksFor(ms)` equals the listed int for `ms` = `2_000` -> `40`, `3_000` -> `60`, `5_000` -> `100`.
- **`ticksFor_negativeIsZero`**: -1 and `Long.MIN_VALUE` both give 0 ticks.
  - `TickDelays.ticksFor(ms)` equals `0` for `ms` = `-1` and `Long.MIN_VALUE`.
- **`ticksFor_hugeIsClamped`**: `Long.MAX_VALUE` gives `Integer.MAX_VALUE`.
  - `TickDelays.ticksFor(Long.MAX_VALUE)` equals `Integer.MAX_VALUE`.
- **`guarded_sameBodyAndLabel_neverEqual`**: Two `guarded` calls with the same label and body return different objects that are also not `equals`, so the scheduler never merges them.
  - `a` is not the same instance as `b`, where `a` and `b` are two `TickDelays.guarded("label", body)` calls with the same no-op `body`.
  - `a` does not equal `b`.
- **`guarded_runsTheBody`**: Running the guarded task executes the body exactly once.
  - `runs` equals `1` after `TickDelays.guarded("label", runs::incrementAndGet).run()`.
- **`guarded_swallowsRuntimeException`**: A body that throws `IllegalStateException` runs once and `run()` does not propagate the exception.
  - `task::run` does not throw, where `task` wraps a body that increments `runs` then throws `IllegalStateException("boom")`.
  - `runs` equals `1` after that run.
- **`guarded_swallowsError`**: A body that throws `AssertionError` runs once and `run()` does not propagate the error.
  - `task::run` does not throw, where `task` wraps a body that increments `runs` then throws `AssertionError("boom")`.
  - `runs` equals `1` after that run.

### `UuidsTest`

Test: [`UuidsTest.java`](../src/test/java/org/wynnvets/util/UuidsTest.java) · Subject: [`Uuids`](../src/client/java/org/wynnvets/util/Uuids.java)

`Uuids` is the mod's UUID helper: it parses dashed and undashed text, normalises to undashed lowercase, and converts to and from Minecraft's four-int array form and its `[I;a,b,c,d]` text spelling. The suite pins lenient, null-returning parsing (never throwing), the null-to-empty-string rule for `normalizeUndashed`, a `Locale.ROOT` fold, and a strict parser for the int-array text that accepts only the compact and the fully spaced spellings. Fixtures include Notch's UUID, a golden vector, and a sign-boundary vector that catches a dropped 32-bit mask; `net.minecraft.core.UUIDUtil` serves as an independent oracle in the last test.

- **`parse_null_returnsNull`**: `parse(null)` returns `null`.
  - `Uuids.parse(null)` is null
- **`parse_empty_returnsNull`**: `parse("")` returns `null`.
  - `Uuids.parse("")` is null
- **`parse_blankWhitespace_returnsNull`**: Whitespace-only strings (spaces, tab and newline) return `null`.
  - `Uuids.parse` is null for each of: a string of three spaces, and `"\t\n"` (tab then newline)
- **`parse_dashedUuid_parses`**: A dashed UUID string parses to the expected `UUID`.
  - `Uuids.parse(DASHED)` equals `EXPECTED` (`DASHED` is the dashed spelling of Notch's UUID, `069a79f4-44e9-4726-a5be-fca90e38aaf5`; `EXPECTED` is `UUID.fromString(DASHED)`)
- **`parse_undashedUuid_parses`**: A 32-character undashed string parses to the same `UUID`.
  - `Uuids.parse(UNDASHED)` equals `EXPECTED` (`UNDASHED` is the same UUID without dashes, `069a79f444e94726a5befca90e38aaf5`)
- **`parse_undashedUuid_trimsWhitespace`**: An undashed UUID with leading spaces and a trailing newline still parses.
  - `Uuids.parse` of `UNDASHED` with two leading spaces and a trailing newline equals `EXPECTED`
- **`parse_invalidHex_returnsNull`**: A 32-character string of "z" returns `null` instead of throwing.
  - `Uuids.parse` of 32 `z` characters is null (does not throw)
- **`parse_wrongLength_returnsNull`**: Hex strings of 12, 30 and 34 characters return `null`.
  - `Uuids.parse` is null for each undashed hex string of the wrong length: `"069a79f444e9"` (12 chars), `"069a79f444e94726a5befca90e38aa"` (30 chars), `"069a79f444e94726a5befca90e38aaf500"` (34 chars)
- **`normalizeUndashed_stripsDashesAndLowercases`**: An upper-case dashed UUID becomes 32 lower-case characters with no dashes, and an already-normalised one is unchanged.
  - `Uuids.normalizeUndashed("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")` equals `"aaaaaaaabbbbccccddddeeeeeeeeeeee"`
  - `Uuids.normalizeUndashed("aaaaaaaabbbbccccddddeeeeeeeeeeee")` equals `"aaaaaaaabbbbccccddddeeeeeeeeeeee"` (an already-normalised value is unchanged)
- **`normalizeUndashed_nullBecomesEmptyStringNotNull`**: `normalizeUndashed(null)` returns the empty string.
  - `Uuids.normalizeUndashed(null)` equals `""`
- **`normalizeUndashed_lowercasesWithLocaleRoot`**: Under a Turkish default locale, "I-I" normalises to "ii", not to dotless-i characters.
  - With the default locale set to Turkish (`tr`), `Uuids.normalizeUndashed("I-I")` equals `"ii"` (not dotless i; the default locale is restored in `finally`)
- **`isUndashedLowercase_acceptsOnlyThirtyTwoLowercaseHexDigits`**: True only for the 32-character lower-case hex string; false for the dashed form, upper case, a 31-character string, non-hex and `null`.
  - `Uuids.isUndashedLowercase(UNDASHED)` is true
  - `Uuids.isUndashedLowercase(DASHED)` is false
  - `Uuids.isUndashedLowercase(UNDASHED.toUpperCase(Locale.ROOT))` is false
  - `Uuids.isUndashedLowercase(UNDASHED.substring(1))` is false (31 characters)
  - `Uuids.isUndashedLowercase` of 32 `z` characters is false
  - `Uuids.isUndashedLowercase(null)` is false
- **`toUndashed_dropsTheDashes`**: `toUndashed` of the expected `UUID` equals the undashed string.
  - `Uuids.toUndashed(EXPECTED)` equals `UNDASHED`
- **`intArray_goldenVector_bothDirections`**: For a UUID with all-non-zero ints, `fromIntArray`, `toIntArray`, `toIntArrayString`, `parseIntArray` and `toUndashed` all agree with the fixed golden values.
  - `Uuids.fromIntArray(GOLDEN_INTS)` equals `GOLDEN` (`GOLDEN` is `085d0e58-29d4-44aa-8379-92a4568a59d6`; `GOLDEN_INTS` is `{140316248, 701777066, -2089184604, 1451907542}`)
  - `Uuids.toIntArray(GOLDEN)` equals `GOLDEN_INTS` element for element
  - `Uuids.toIntArrayString(GOLDEN)` equals `GOLDEN_SNBT` (`"[I;140316248,701777066,-2089184604,1451907542]"`)
  - `Uuids.parseIntArray(GOLDEN_SNBT)` equals `GOLDEN`
  - `Uuids.toUndashed(GOLDEN)` equals `"085d0e5829d444aa837992a4568a59d6"`
- **`intArray_signBoundaryVector_keepsTheLowWords`**: A UUID whose low words are negative under a high word that is not -1 converts to and from the int array and the `[I;0,-2147483648,0,-2147483648]` string correctly.
  - `Uuids.fromIntArray(SIGN_INTS)` equals `SIGN` (`SIGN` is `00000000-8000-0000-0000-000080000000`; `SIGN_INTS` is `{0, -2147483648, 0, -2147483648}`)
  - `Uuids.toIntArray(SIGN)` equals `SIGN_INTS` element for element
  - `Uuids.toIntArrayString(SIGN)` equals `"[I;0,-2147483648,0,-2147483648]"`
- **`intArray_roundTrips`**: For five UUIDs, including all-zero and all-ones, array and string conversions both round-trip.
  - For each `u` in `new UUID(0L, 0L)`, `new UUID(-1L, -1L)`, `SIGN`, `GOLDEN`, `EXPECTED`: `Uuids.fromIntArray(Uuids.toIntArray(u))` equals `u`, and `Uuids.parseIntArray(Uuids.toIntArrayString(u))` equals `u`
- **`intArray_nullAndLengthRules`**: `fromIntArray` returns `null` for `null` and for arrays of length 3 and 5; `toIntArray` and `toIntArrayString` return `null` for `null`.
  - `Uuids.fromIntArray(null)` is null
  - `Uuids.fromIntArray(new int[3])` is null
  - `Uuids.fromIntArray(new int[5])` is null
  - `Uuids.toIntArray(null)` is null
  - `Uuids.toIntArrayString(null)` is null
- **`parseIntArray_acceptsBothMinecraftSpellings`**: Accepts the compact and the `[I; a, b, c, d]` spellings, surrounding whitespace, and zero-padded numbers like 00000000001.
  - `Uuids.parseIntArray(GOLDEN_SNBT)` equals `GOLDEN` (no spaces after the commas)
  - `Uuids.parseIntArray("[I; 140316248, 701777066, -2089184604, 1451907542]")` equals `GOLDEN` (spaces after `I;` and after each comma)
  - `Uuids.parseIntArray` of `GOLDEN_SNBT` with two leading spaces and a trailing newline equals `GOLDEN`
  - `Uuids.parseIntArray("[I;00000000001,0,-00000000001,0]")` equals `Uuids.fromIntArray(new int[] {1, 0, -1, 0})` (leading zeros accepted)
- **`parseIntArray_rejectsEverythingElse`**: `null`, blank, wrong marker, wrong element count, missing brackets, empty or sign-only elements, a plus sign, mixed or odd spacing, trailing commas, out-of-range values, a non-ASCII digit and a dashed UUID all return `null`.
  - For each `bad` in a list of 25 inputs, `Uuids.parseIntArray(bad)` is null. The inputs are: `null`, `""`, three spaces, `"junk"`, lowercase tag `"[i;1,2,3,4]"`, wrong tag `"[L;1,2,3,4]"`, 3 or 5 elements, missing `]`, missing `[`, an empty element (`"[I;1,,3,4]"`), a bare `-`, a leading `+`, misplaced or extra spaces (`"[I;  1,2,3,4]"`, `"[I;1 ,2,3,4]"`, `"[I; 1,2,3,4]"`, `"[I;1, 2,3,4]"`, `"[I; 1, 2,3, 4]"`), a trailing comma (`"[I;1,2,3,4,]"`, `"[I; 1, 2, 3, 4, ]"`), out-of-int-range values (`2147483648`, `-2147483649`, `99999999999`), a non-ASCII digit U+0661 (`"[I;" + U+0661 + ",2,3,4]"`), and `DASHED`
- **`parseAny_readsEverySpelling`**: `parseAny` reads dashed, undashed, compact int-array and spaced int-array text, and returns `null` for "junk" and `null`.
  - `Uuids.parseAny(DASHED)` equals `EXPECTED`
  - `Uuids.parseAny(UNDASHED)` equals `EXPECTED`
  - `Uuids.parseAny(GOLDEN_SNBT)` equals `GOLDEN`
  - `Uuids.parseAny("[I; 140316248, 701777066, -2089184604, 1451907542]")` equals `GOLDEN`
  - `Uuids.parseAny("junk")` is null
  - `Uuids.parseAny(null)` is null
- **`intArray_agreesWithMinecraftsUuidUtil`**: For five UUIDs, `Uuids.toIntArray` and `fromIntArray` match `UUIDUtil.uuidToIntArray` and `uuidFromIntArray` in both directions.
  - For each `u` in `new UUID(0L, 0L)`, `new UUID(-1L, -1L)`, `SIGN`, `GOLDEN`, `EXPECTED`: `UUIDUtil.uuidFromIntArray(Uuids.toIntArray(u))` equals `u`; `Uuids.fromIntArray(UUIDUtil.uuidToIntArray(u))` equals `u`; and `Uuids.toIntArray(u)` equals `UUIDUtil.uuidToIntArray(u)` element for element
