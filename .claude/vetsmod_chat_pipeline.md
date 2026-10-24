---
name: vetsmod Chat Pipeline
description: Complete chat pipeline — ChatLogMixin hook, rewriter chain, dispatcher system, OutboundDisplayHandler, PillFormatter, SpoilerCodec, state/caches
type: project
originSessionId: dc63f47a-2d15-4f8d-9b6a-41d3049f0cc2
---
# vetsmod Chat Pipeline — In-Depth Reference

The vetsmod chat system is a multi-stage pipeline that intercepts each chat message that reaches the single-argument `ChatComponent.addMessage(Component)` overload (the path vanilla uses for system and disguised chat; vanilla sends player chat to the three-argument overload instead, but Wynntils' Chat Tabs — enabled by default, and vetsmod hard-depends on Wynntils — re-adds each line through the single-argument overload on every matching tab's own `ChatComponent`, so player chat reaches this hook too), classifies it, suppresses mod-initiated echoes, rewrites through a chain, and dispatches formatted output. It is coupled to an outbound WebSocket handler for server-pushed messages, a dispatcher system for staff fanout, and a PUA-based spoiler codec.

## 1. High-level flow

**Incoming (server → client):** vanilla `ChatComponent.addMessage(Component)` (the single-argument overload; a `ClientboundPlayerChatPacket` reaches vanilla's three-argument overload instead, never this one — but Wynntils' Chat Tabs, on by default, re-adds *every* message handed to `mc.gui.chat`, player chat included, to each matching tab's own `ChatComponent` through the single-argument overload, so this hook can see player chat and can fire more than once for the same message) → `ChatLogMixin` HEAD → streamer-mode observation → log → guild state detection → suppression checks (/gu stats, /gu rank, /v, /find) → rewriter chain → fall through to vanilla (or cancelled if a rewriter consumed it).

**Outbound (user → server):** `ClientPacketListener.sendCommand()` → `GuildChatCommandMixin` HEAD → `GuildChatDispatcher.intercept()` for the prefixes in [vetsmod_commands.md](vetsmod_commands.md) §2's table, `/g`, `/wg` and `/v` among them. `/msg` is not among them.

**Remote push (WebSocket → client):** `V1ApiManager` outbound listener → `OutboundDisplayHandler.onOutboundMessage()` → display gates, UUID dedup, self suppression, Returners' own `guild` frames dropped outside a queue → `ChatUtils.sendHonouraryChatMessage()` when this client is honourary-unlocked, else `ChatUtils.sendGuildChatMessage()`. A `warning` frame branches off to `WarningFrameRenderer` before the gates (none arrives today: bug `outbound-socket-never-authenticated`), and a staff `‼` alert to `StaffGuildAlertRewriter` after them; `bridge` frames are only recorded here, for `WynntilsEventListener`'s echo check. Full order in §5.

## 2. ChatLogMixin — the single-argument addMessage hook

[ChatLogMixin](../src/client/java/org/wynnvets/mixin/client/chat/ChatLogMixin.java)

`@Mixin(ChatComponent.class) @Inject(method="addMessage", at=@At("HEAD"), cancellable=true)` — runs in order:

1. `StreamerModeChatDetector.observe(message)` — runs first, before any logging
2. `ChatLogger.logMessage(...)` unless `LocalChatDispatch.isInternalDispatch()` is true (the `INTERNAL_CHAT_DISPATCH` ThreadLocal is private to `LocalChatDispatch`; the mixin reads it through that accessor)
3. `GuildStateManager.processGuildCheckMessage(msg)` (pulls `/gu stats` responses out of chat)
4. Compute `isStaffRankCheck` = `GuildStateManager.isProcessingModStaffRankCheck()` AND the line looks like a staff-rank-check response
5. `GuildStateManager.processMessage(message, messageString)` — unconditional, and it runs *before* the suppression below, so a suppressed rank-check line is still processed
6. Suppress if `isStaffRankCheck`
7. Suppress via `DispatchCoordinator.shouldSuppressFeedback()` (`/v` echo)
8. Suppress via `DispatchCoordinator.shouldSuppressFindResponse()` (`/find` result)
9. Rewriter chain (only when NOT internally dispatched):
   1. `EncourageUpdateRewriter.tryRewrite()`
   2. `StaffGuildAlertRewriter.tryRewrite()`
   3. `StaffChannelMessageRewriter.tryRewrite()`
   4. `ServerGuildChatRewriter.tryRewrite()`
   5. `SpoilerRewriter.tryRewrite()`

Each rewriter returns `true` to cancel the vanilla event (consumed).

`WarningFrameRenderer` is not part of this chain: `OutboundDisplayHandler` calls it for server-pushed `warning` frames (§5).

## 3. Rewriters and their shared helpers

### EncourageUpdateRewriter
[EncourageUpdateRewriter](../src/client/java/org/wynnvets/chat/rewriter/EncourageUpdateRewriter.java)

Pattern: `⚠⚠⚠ If you had vetsmod, you would see a rainbow! For more info, use ~vetsmod in #bot-commands on vetscord. (v([0-9]+(?:\.[0-9]+)*)) ⚠⚠⚠`.
The earlier wording, `⚠⚠⚠ If you are using vetsmod, it's outdated (current version ([0-9]+(?:\.[0-9]+)*)) ⚠⚠⚠`, is still matched (`LEGACY_ENCOURAGE_PATTERN`), because staff on older builds still send it.
Parses staff-broadcast version, compares to local; outdated → obfuscated red; up-to-date → rainbow. It rewrites only a sender `StaffRanksPoller.confirmedRankFor` knows (the whole name, then each `/`-separated part), and the same lookup labels the remote pill, through `RankDisplayMap.displayFor`.

### StaffGuildAlertRewriter
[StaffGuildAlertRewriter](../src/client/java/org/wynnvets/chat/rewriter/StaffGuildAlertRewriter.java)

Triggers on a `‼` (U+203C) prefix from a sender `StaffRanksPoller.confirmedRankFor` knows as staff. Builds purple shout prefix + "ALERT" pill + body; the body is bold when a further `!` follows the prefix. Has `tryRewrite()` for in-game Component and `tryRewriteOutbound()` for WebSocket JSON. Resolves real usernames from hover text "X's real name is Y".

### StaffChannelMessageRewriter
[StaffChannelMessageRewriter](../src/client/java/org/wynnvets/chat/rewriter/StaffChannelMessageRewriter.java)

Triggers on `🔐` lock prefix in private messages (the `/v` fan-out marker).
Extracts sender from: click event (`/msg <name>`), hover text (via `NickResolver.flattenComponent` + `realUsernameFromHover`), or username regex at end. Displays via `ChatUtils.sendStaffChannelMessage()` only when `StaffRanksPoller.confirmedRankFor` knows the sender as staff (the sender's own echo is cancelled instead); a lock-prefixed message from anyone else returns `false` and shows as the plain private message it is (except under `/stream`, which drops every lock-prefixed message before the sender check), so a staff member who has just come online reads that way until the next staff poll.

### ServerGuildChatRewriter
[ServerGuildChatRewriter](../src/client/java/org/wynnvets/chat/rewriter/ServerGuildChatRewriter.java)

Rewrites VETS guild chat lines (gated on `GuildStateManager.isVetsGuildChat()`) whose sender's raw rank pill maps to a different display label (`RankDisplayMap.displayFor`), or whose sender is a supporter (via `SupportersPoller.isSupporter()`) while `showSupporterGlints` is on. The supporter path without a remap re-renders with animated gradient pill: flattens the component tree through `NickResolver.flattenComponent`, keeps the leaves whose resolved style uses the `banner/pill` font, marks background glyphs with animation sentinel color (replaced at render time by `AnimatedChatMixin`), preserves dark letters. Which span carries the font is what that selection turns on, so the inheritance direction is pinned in `NickResolverTest`, not assumed.

### SpoilerRewriter
[SpoilerRewriter](../src/client/java/org/wynnvets/chat/rewriter/SpoilerRewriter.java)

Flattens through `NickResolver.flattenComponent`, then takes one of two paths: fast (single fragment contains complete PUA spoiler block) or cross-fragment (spans fragments / server-wrapped). Cross-fragment path preserves pill+name+colon prefix, accumulates body, processes through `MessageBodyFormatter.formatMessageBody()` which strips continuation markers.

### GuildChatLine
[GuildChatLine](../src/client/java/org/wynnvets/chat/GuildChatLine.java)

Splits `<rank pill glyphs> <display name>: <body>`. One shared header scan —
find the last custom-font glyph before the first colon, the trimmed run up to
the colon is the name — and two public entry points over it:

| | `parse` | `parseServerLine` |
|---|---|---|
| callers | `EncourageUpdateRewriter`, `StaffGuildAlertRewriter` | `ServerGuildChatRewriter` |
| `null` input | returns `null` | throws, no guard |
| the body | trimmed substring | char offset the caller slices |
| rank indicator | discarded | returned, carries the pill |
| after the colon | `trim()`, so `<= U+0020` at both ends — not `strip()` | past literal `' '` only, leading only |

The four differences are deliberate, not drift, and the class Javadoc carries
the same table. `ServerGuildChatRewriter` rebuilds its line from the original
`Component` tree, so it needs an offset that still lines up with the tree's own
character positions — which a trimmed substring cannot give it. Pinned by
`GuildChatLineTest`; the body-offset row only shows up when the character after
the colon is a tab or a newline.

### NickResolver
[NickResolver](../src/client/java/org/wynnvets/chat/NickResolver.java)

The repo's real-name and component-flattening authority. **Five** rewriters call
it and none keeps a copy: all of §3's five. Three reach `flattenComponent`
directly (`ServerGuildChatRewriter`, `SpoilerRewriter`,
`StaffChannelMessageRewriter`); the other two arrive through
`realUsernameOrFallback`.

- `realUsernameOrFallback(root, fallback)` — first hover matching
  `REAL_NAME_PATTERN` wins, else the fallback. `EncourageUpdateRewriter`,
  `StaffGuildAlertRewriter`, `ServerGuildChatRewriter`.
- `realUsernameFromHover(hover)` — the same match against one hover.
  `StaffChannelMessageRewriter`, which walks the tree itself because it wants
  the click event too.
- `realNameSpanStyleOrFallback(root, fallback)` — the *style* of that span, so a
  rebuilt name span keeps the nick's italic, colour and hover.
- `flattenComponent(component, inherited, out)` — the tree walk, into
  `FlatPart(text, style)`. Resolution is `child.applyTo(inherited)`: child fields
  win, the ancestor fills gaps.

That orientation is load bearing in two directions and both are pinned in
`NickResolverTest`. A hover on an ancestor must not mask a `"real name is …"`
hover on a descendant. And a span with no font of its own must inherit one, or
`ServerGuildChatRewriter` selects no pill fragments at all.

This walk itself was never wrong. What was wrong was
`EncourageUpdateRewriter`'s **private copy** of it, which had receiver and
argument swapped — that is what made a nicked staff sender fail the staff gate,
and it affected only that one rewriter. The copy is gone.

## 4. Spoiler PUA codec

[SpoilerCodec](../src/client/java/org/wynnvets/chat/spoiler/SpoilerCodec.java) — uses the same scheme as [temporary-server/app/parsers/spoiler_codec.py](../../temporary-server/app/parsers/spoiler_codec.py), but encodes UTF-16 code units where the Python side encodes code points, strips the banned characters before encoding, and has a stricter decoder (see "Banned characters" below).

- `\uF600` block start, `\uF601` block end
- `\uF602–\uF6FF` = direct 1:1 encoding for chars 0-253 (base = 0xF602)
- `\uF700` = escape for chars ≥ 254, followed by 3 base-254 digits
- Wrapper format for vanilla clients: `[Spoiler: ]` + PUA block

Pipe regex: `\|\|(.+?)\|\|` (non-greedy). Predicates: `containsPipeSpoiler()`, `containsEncodedSpoiler()`.

`SpoilerFormatter.appendWithSpoilers()` renders PUA blocks as green `[Spoiler]` labels with hover text showing decoded content, banned characters dropped. Gated by `VetsConfigKeys.HANDLE_SPOILERS` (tri-state: null=default=on).

### Banned characters

[BannedChars](../src/client/java/org/wynnvets/chat/BannedChars.java) owns two sets, and this section owns where each is stripped.

- **Hard:** U+00C1, U+E07E, U+E080 and U+E086. Rendering any of them through Wynncraft's resource-pack fonts breaks the client. One constant, with no config or server override; an exemption is a hard-coded, commented change at the call site that needs it.
- **Soft:** `§`, the rank icons U+E014–E025, the banner pieces U+E00E–E011, E070, E07A and E085, the negative-width spaces U+E000, E012, E071, E07F, E087, E088 and U+2064, and the pill letters U+E040–E06F. In player text they spoof Wynncraft's badges, banners and pills. Built components and Wynncraft's own lines use them, so only player-typed text loses them.

| Site | Sets | Why |
|---|---|---|
| `GuildChatDispatcher`'s three relays (`/g` from a waitlister or a queued Returner, `/wg`), on the text after any spoiler encoding (raw when `handleSpoilers` is off), never the rank | both | the line skips Wynncraft's filter, and its local echo skips the server's; a line left blank is dropped silently |
| `GuildChatDispatcher.dispatchStaffChat` (`/v`) | hard | its echo renders locally |
| `SpoilerCodec`'s encoder, before encoding | both | encoded content passes Wynncraft's filter unseen |
| `SpoilerCodec.decodeContent`'s output | both | blocks vetsmod didn't write |
| `OutboundDisplayHandler`, the received `username` and `message` | both; a `bridge` line's `message` loses the hard set only | another player's or a Discord author's text, from a client or server that may not strip it. temporary-server strips the soft set from non-admin Discord text and lets Discord admins send it on purpose, so a bridge body keeps it. No Minecraft name can hold either set, and a Discord name loses only characters that would break or spoof the render; a name left empty drops the line |
| `MessageBodyFormatter.formatMessageBody` | hard | chat bodies only; names, pills, the MOTD and other server text don't pass through it |

`decodeContent` decodes an escape only when its three digits are in U+F602–U+F6FF and its value is at most U+FFFF; any other escape is dropped whole, all four code units, so a hand-written block can produce only what the encoder writes. The range and value checks make it stricter than temporary-server's Python mirror, which still decodes as clients before this change did. A newer client's decoded text is always a subsequence of what the old decoder gives, so the mirror stays a conservative check. temporary-server's encoder writes one escape per code point, so a character above U+FFFF in a Discord spoiler (most emoji) arrives as one escape above U+FFFF and decodes to nothing, while the rest of the spoiler still decodes. The exception is a block temporary-server's `strip_banned` re-encoded because it also held a hard-set character: that cuts the escape to its low 16 bits, so it decodes to a BMP character. vetsmod's own encoder writes a supplementary character as two surrogate escapes, which do decode, so both sides also drop a supplementary custom glyph (`PillCodec.isCustomGlyph`: private use, or unassigned above U+FFFF, where Wynncraft's markers live) and any unpaired surrogate; emoji and other assigned supplementary characters survive. Dropping more keeps the decoded text a subsequence of the mirror's. A spoiler whose content is all banned characters encodes as an empty block, which Discord shows as `[Spoiler: ]`.

## 5. OutboundDisplayHandler

[OutboundDisplayHandler](../src/client/java/org/wynnvets/chat/OutboundDisplayHandler.java)

Receives the outbound frames `V1ApiManager` fans out to its outbound listeners. It is one of them, and `V1ApiManager` routes `server_info` and `staff_online`/`staff_offline` away before the fan-out.

State:
- `pendingSelfMessages` Deque (max 50, 30s TTL) — echo-suppress messages user just sent
- `recentBridgeMessages` Deque (max 200, 10s TTL) — bridge-echo dedup via normalized-text compare (strips whitespace and every `PillCodec.isCustomGlyph` codepoint)
- `recentUuids` LinkedHashMap (max 200, 10s TTL) — UUID-based dedup

Flow: a `warning` frame → `WarningFrameRenderer.render`, skipping every step after it (today none arrives: bug `outbound-socket-never-authenticated`). Any other frame → `PRINT_BRIDGE_MESSAGES` gate → audience gate (`shouldDisplayMessages`) → UUID dedup → `username` and `message` lose the banned sets (§4; a `bridge` line's `message` only the hard set) → dropped if `username` is empty or `message` is blank → self suppression → Returners' `guild` frames dropped unless queued → `bridge` frames recorded for `WynntilsEventListener.onGuildChat`'s echo check (`wasBridgeEcho`) → for a `guild` or `queue` frame, a staff `‼` alert via `StaffGuildAlertRewriter.tryRewriteOutbound` → otherwise display via `ChatUtils.sendHonouraryChatMessage()` (honourary-unlocked viewer) or `ChatUtils.sendGuildChatMessage()`.

## 6. The formatting engine

### ChatUtils

[ChatUtils](../src/client/java/org/wynnvets/chat/ChatUtils.java)

Key methods:
- `sendGuildChatMessage()` — `<badge> <pill> <username>: <body>`
- `sendGuildChatMessageRed()` — red guild-chat styling with the `Prepend.STAFF_CHANNEL` badge; reached only through `sendStaffChannelMessage()` (the `/v` staff-channel look)
- `sendStaffChannelMessage()` — staff-style with special pill

Styles: `RANK_STYLE` = aqua, `NAME_STYLE` = dark aqua, `ADMIN_RANK_STYLE` = red.

### MessageBodyFormatter

[MessageBodyFormatter](../src/client/java/org/wynnvets/chat/MessageBodyFormatter.java) formats a message body for the senders above and for the rewriters that rebuild a server line.

Key methods (`stripServerContinuations` is a helper, package-private only for its test, not an entry point):
- `formatMessageBody()` — drops the banned hard set (§4), strips server continuation markers, makes URLs clickable, formats spoilers
- `stripServerContinuations()` — removes `\n + marker` sequences
- `isWrapStructure()` — whether a fragment is only wrap structure (a lone newline or a prefix marker)

URL regex (`URL_PATTERN`, case-insensitive): `(?<!§)(https?://\S+|[A-Za-z0-9][A-Za-z0-9-]*(?:\.[A-Za-z0-9][A-Za-z0-9-]*)+/\S*)` — it also matches schemeless `domain.tld/path`, and the lookbehind keeps it off section-sign colour codes.

`CHAT_PREFIX_STYLE` references custom `chat/prefix` font for the inline separator and the continuation markers.

`MessageBodyFormatterTest` pins `formatMessageBody` on bodies made only of markers, URLs and banned hard-set characters, because any other text reaches `VetsConfig` through the spoiler formatter, and pins `stripServerContinuations` directly, including a check over every short string of marker pieces that no newline is left before a marker.

### LegacyTextParser

[LegacyTextParser](../src/client/java/org/wynnvets/chat/LegacyTextParser.java)`.literalWithUrls()` builds a component from text carrying legacy `§` codes (the MOTD bodies `MotdFetcher` fetches): each code styles the text after it, and URLs and encoded spoilers go through `MessageBodyFormatter`'s URL and spoiler handling. A colour code resets to the base style plus the colour, where vanilla clears the format flags; the two agree when the base style carries none. `LegacyTextParserTest` pins every code on a body that is codes and a URL only.

### LocalChatDispatch

[LocalChatDispatch](../src/client/java/org/wynnvets/chat/LocalChatDispatch.java) puts a built line on the chat HUD: `ChatUtils`' senders hand their lines to it, and the rewriters that rebuild a whole line call it directly.

Key methods (`wrapBlockMessage` is a private helper, not an entry point):
- `wrapBlockMessage()` — word-wrap with continuation block markers
- `dispatchToChat()` — thread-safe dispatch
- `dispatchAnimatedChat()` — dispatch with gradient animation context

`INTERNAL_CHAT_DISPATCH` ThreadLocal marks mod-generated messages (skip re-logging + rewriter chain).

## 7. PillFormatter

[PillFormatter](../src/client/java/org/wynnvets/chat/PillFormatter.java)

Formats rank "pill" (badge) component from the caller's supporter determination. When the sender is a supporter and `SHOW_SUPPORTER_GLINTS` is on, the pill takes the animation sentinel color; otherwise it takes the flat base style.

- PUA pills (vetsmod's own encoded pill, from `PillCodec.encodeRemote` — every pill `formatPill` currently receives): whole component gets the single marker color, because a per-character gradient would break the composite glyphs. Renders in the default font, not `chat/prefix`.
- A plain-text label would get the marker color per character instead, but no current caller passes one.

## 8. Prepend (badge dedup)

[Prepend](../src/client/java/org/wynnvets/chat/Prepend.java)

Enum, five constants: `DEFAULT` (gold vetsmod badge), `GUILD` (aqua guild badge, compact block marker for consecutive messages within 18 lines), `GUILD_HONOURARY`, `STAFF_CHANNEL` (`GUILD`'s glyphs in red for `/v` lines, deduplicated apart from `GUILD`), `EMPTY`. Mirrors native Wynncraft behaviour. `get()` advances the dedup state, so it is called only for a badge that is drawn; `blockIndicatorStyle()` reads a badge's style without touching it, which is how `SpoilerRewriter` picks its dispatch style.

## 9. Dispatcher system (staff chat + /find)

Solves, for the three executor-backed classes below: serialize command dispatch on a single-threaded executor and wait for server feedback before next send.

`GuildChatDispatcher`, also in `chat/dispatcher/`, has no executor. It is the synchronous prefix router that `GuildChatCommandMixin` calls at the head of `ClientPacketListener.sendCommand`, for each command that reaches that hook (§1, outbound; a command another `HEAD` hook cancels first never does, and [vetsmod_mixins.md](vetsmod_mixins.md) owns the hooks), and it reaches this section only through `DispatchCoordinator`'s staff gate: `/v` via `dispatchStaffChatWithEligibilityGate`, `/a` and `/encourage` via `executeWithStaffEligibilityGate`.

### DispatchCoordinator
[DispatchCoordinator](../src/client/java/org/wynnvets/chat/dispatcher/DispatchCoordinator.java)

Gates `/v`, `/a` and `/encourage` on staff eligibility, and owns the single-threaded `DISPATCH_EXECUTOR` that serializes the `/v` fan-out (sent as `/msg`) and the `/find` batches (those are enqueued on `FindDispatcher` directly, not here). `/msg` takes priority: each pass handles queued `/msg` first, then every queued `/find` batch, including ones queued during that phase. A `/msg` arriving mid-`/find` waits for the next pass, by design: `/wv list world` queues one `/find` batch for every name, so a yield between batches would not shorten the wait. Provides `shouldSuppressFeedback()` and `shouldSuppressFindResponse()` used by ChatLogMixin.

### MessageFanoutDispatcher
[MessageFanoutDispatcher](../src/client/java/org/wynnvets/chat/dispatcher/MessageFanoutDispatcher.java)

Fans `/v` out as `/msg <recipient> 🔐<message>` (no space after the lock) to every online staff member in the feed except yourself. Constants:
- `DispatchCoordinator.LOCK_PREFIX = "🔐"` (the `/v` fan-out marker, and one of the echo-matching signals; declared there, not here)
- `SUPPRESSION_TTL_MS = 15_000`
- `OFFLINE_GUIDANCE_SUPPRESSION_WINDOW_MS = 4_000`
- `INTER_SEND_DELAY_MS = 600`
- `MAX_DISPATCH_ATTEMPTS = 3` (one send and up to two retries)

Multi-strategy feedback matching in `MessageFanoutDispatcher.shouldSuppressFeedback` (reached from ChatLogMixin; the direct caller is `DispatchCoordinator.shouldSuppressFeedback`):
1. Offline-guidance blanket-suppression (4s window after offline error)
2. Payload echo (plain match, then censored variant, then token subsequence; needs the recipient or the lock prefix too)
3. Lock-prefix + recipient fallback, when the payload test fails (e.g. Wynntils rewrote coordinates)
4. Offline-recipient error for the pending recipient (reports offline, opens the guidance window)

Steps 2–4 are tried against each pending send in turn, oldest first; the first match wins.

### FindDispatcher
[FindDispatcher](../src/client/java/org/wynnvets/chat/dispatcher/FindDispatcher.java)

Batch `/find <username>` dispatcher. `enqueueFindBatch(usernames, resultFuture)` completes the caller-supplied `CompletableFuture<Map<username, server>>`; `WorldListFetcher` calls it. Parses responses (`parseFindReply`), each of which must open with the awaited name, so a longer name ending with it is another player's reply: "currently on server XX##" (server stored lower-cased), "currently on a private server" (sentinel `"PRIVATE"`), "not currently online" (null); a lookup with no matching reply within `FIND_RESPONSE_WAIT_MS = 6_000` is null too.

## 10. AnimatedChatMixin

[AnimatedChatMixin](../src/client/java/org/wynnvets/mixin/client/chat/AnimatedChatMixin.java)

`@Mixin(ChatComponent.class)` on `addMessageToDisplayQueue` HEAD + RETURN. Snapshots the *identity* of the current first line at HEAD, then at RETURN walks forward to that same reference to count what was prepended and wraps those lines with `AnimatedGradientSequence`. The wrapper is built from `AnimatedGradientSequence`'s effective defaults, not from the `beginAnimation()` ThreadLocal — colours passed to `beginAnimation` do not reach it.

## 11. Custom fonts / PUA glyphs

| Font | Used for | Glyphs |
|------|----------|--------|
| `chat/prefix` | Guild badge, alerts, block markers | `\uDAFF\uDFFC` (guild badge) |
| `banner/pill` | Server-rendered rank pills | background (aqua) + foreground (dark) composite |
| Spoiler PUA | Encoded spoilers | `\uF600`/`\uF601` delimiters, `\uF602–\uF700` content |

Rank-letter glyphs aren't `chat/prefix`: vetsmod's own pills render in the default font (uppercase `\uE040–\uE059`), and the server's rank pill uses `banner/pill`. See [vetsmod_pua_pills.md](vetsmod_pua_pills.md).

`PillCodec` owns both halves of this: the pill sequences, and the predicate for whether a codepoint is glyph art at all — `isCustomGlyph(int)`, seven call sites across six classes, three deliberate non-callers. Both are documented in [vetsmod_pua_pills.md](vetsmod_pua_pills.md).

**Top-level `chat/` classes described elsewhere.** `DiscordTimestamps`, which expands Discord's `<t:EPOCH:STYLE>` markup client-side, is described with its one caller, `/wv return`, in [vetsmod_commands.md](vetsmod_commands.md) §1. `RankDisplayMap` maps a raw Wynncraft guild rank to what vetsmod shows for it: `displayFor` gives the display label from the 2026-07 permission restructure (Returner, Steward) and returns a rank it does not know unchanged, and `vTagFor` gives the tag on a `/v` staff-channel pill (`ChatUtils.buildStaffPillComponent`). [vetsmod_pua_pills.md](vetsmod_pua_pills.md) §"Where decoding happens" shows where `ServerGuildChatRewriter` applies it, after the pill decode that tries `ChatLogger`'s rank map first. `ChatLogger`'s logging is step 2 of §2's list, and `WarningFrameRenderer` is the `warning`-frame path in §5.

## 12. Regex quick reference

| Pattern | Constant | Purpose |
|---------|-----------|---------|
| `(?<!§)(https?://\S+\|[A-Za-z0-9][A-Za-z0-9-]*(?:\.[A-Za-z0-9][A-Za-z0-9-]*)+/\S*)` | `MessageBodyFormatter.URL_PATTERN` | URL detection (scheme or schemeless) |
| `\|\|(.+?)\|\|` | `SpoilerCodec.PIPE_SPOILER` | Pipe spoilers |
| `real\s+name\s+is\s+([A-Za-z0-9_]{1,16})` | `NickResolver.REAL_NAME_PATTERN` | Hover→real-name (one copy; `EncourageUpdateRewriter`'s private duplicate was deleted) |
| `/msg\s+([A-Za-z0-9_]{1,16})` | `StaffChannelMessageRewriter.MSG_COMMAND_PATTERN` | Click→recipient |
| `([A-Za-z0-9_]{1,16})\s*$` | `StaffChannelMessageRewriter.USERNAME_AT_END` | Username-at-end |
| `⚠⚠⚠ If you had vetsmod.*` (and the older `⚠⚠⚠ If you are using vetsmod.*`) | `EncourageUpdateRewriter.ENCOURAGE_PATTERN` | Version nag |

## 13. Thread safety

`ChatLogMixin` and its rewriter chain run on the render thread. `WarningFrameRenderer.render` and `StaffGuildAlertRewriter.tryRewriteOutbound` do not: `OutboundDisplayHandler` calls them on the thread that delivers the outbound socket's frames, and their output reaches chat only through `LocalChatDispatch`'s own `Minecraft.execute` bounce to the render thread. `DispatchCoordinator` serializes the `/v` fan-out and the `/find` lookups on a single-threaded executor; each command itself is queued from the render thread. Suppression state protected by `Object` locks (`SUPPRESSION_ACK_LOCK`, `FIND_RESPONSE_LOCK`). Dedup caches are plain collections read and written inside `synchronized` blocks (`OutboundDisplayHandler`'s three each have their own lock object).

## 14. Feature gates

- `PRINT_BRIDGE_MESSAGES` — display of all WebSocket-relayed chat, not only bridge; server-pushed `warning` frames bypass it (default true)
- `SHOW_SUPPORTER_GLINTS` — enable animated gradient pill (default true)
- `HANDLE_SPOILERS` — tri-state: null=default(on)/true/false
