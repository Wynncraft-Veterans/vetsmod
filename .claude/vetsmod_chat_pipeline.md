---
name: vetsmod Chat Pipeline
description: Complete chat pipeline — ChatLogMixin hook, rewriter chain, dispatcher system, OutboundDisplayHandler, PillFormatter, SpoilerCodec, state/caches
type: project
originSessionId: dc63f47a-2d15-4f8d-9b6a-41d3049f0cc2
---
# vetsmod Chat Pipeline — In-Depth Reference

The vetsmod chat system is a multi-stage pipeline that intercepts every chat message, classifies it, suppresses mod-initiated echoes, rewrites through a chain, and dispatches formatted output. It is coupled to an outbound WebSocket handler for server-pushed messages, a dispatcher system for staff fanout, and a PUA-based spoiler codec.

## 1. High-level flow

**Incoming (server → client):** vanilla `ChatComponent.addMessage()` → `ChatLogMixin` HEAD → log → guild state detection → suppression checks (/g, /gu rank, /find) → rewriter chain → fall through to vanilla (or cancelled if a rewriter consumed it).

**Outbound (user → server):** `ClientPacketListener.sendCommand()` → `GuildChatCommandMixin` HEAD → `GuildChatDispatcher.intercept()` for `/g`, `/wg`, `/v`, `/msg`.

**Remote push (WebSocket → client):** `V1ApiManager` outbound listener → `OutboundDisplayHandler.onOutboundMessage()` → UUID dedup + self + bridge echo suppression → `ChatUtils.sendGuildChatMessage()`.

## 2. ChatLogMixin — the chokepoint

[src/client/java/org/wynnvets/mixin/client/chat/ChatLogMixin.java:30-125](src/client/java/org/wynnvets/mixin/client/chat/ChatLogMixin.java#L30-L125)

`@Mixin(ChatComponent.class) @Inject(method="addMessage", at=@At("HEAD"), cancellable=true)` — runs in order:

1. `ChatLogger.logMessage(message)` unless `ChatUtils.INTERNAL_CHAT_DISPATCH` ThreadLocal is set
2. `GuildStateManager.processGuildCheckMessage(msg)` (pulls `/gu stats` responses out of chat)
3. Suppress if `GuildStateManager.isProcessingModGuildCheck()` or staff-rank-check is active
4. Suppress via `CommandDispatcher.shouldSuppressFeedback()` (`/v` echo)
5. Suppress via `CommandDispatcher.shouldSuppressFindResponse()` (`/find` result)
6. Rewriter chain (only when NOT internally dispatched):
   1. `EncourageUpdateRewriter.tryRewrite()`
   2. `StaffGuildAlertRewriter.tryRewrite()`
   3. `StaffChannelMessageRewriter.tryRewrite()`
   4. `ServerGuildChatRewriter.tryRewrite()`
   5. `SpoilerRewriter.tryRewrite()`

Each rewriter returns `true` to cancel the vanilla event (consumed).

## 3. Rewriters

### EncourageUpdateRewriter
[src/client/java/org/wynnvets/chat/rewriter/EncourageUpdateRewriter.java:32-269](src/client/java/org/wynnvets/chat/rewriter/EncourageUpdateRewriter.java#L32-L269)

Pattern: `⚠⚠⚠ If you are using vetsmod, it's outdated (current version ([0-9]+(?:\.[0-9]+)*)) ⚠⚠⚠`.
Parses staff-broadcast version, compares to local; outdated → obfuscated red; up-to-date → rainbow.

### StaffGuildAlertRewriter
[src/client/java/org/wynnvets/chat/rewriter/StaffGuildAlertRewriter.java:21-255](src/client/java/org/wynnvets/chat/rewriter/StaffGuildAlertRewriter.java#L21-L255)

Triggers on `‼` prefix. Builds purple shout prefix + "ALERT" pill + body (bold if `!!`). Has `tryRewrite()` for in-game Component and `tryRewriteOutbound()` for WebSocket JSON. Resolves real usernames from hover text "X's real name is Y".

### StaffChannelMessageRewriter
[src/client/java/org/wynnvets/chat/rewriter/StaffChannelMessageRewriter.java:21-218](src/client/java/org/wynnvets/chat/rewriter/StaffChannelMessageRewriter.java#L21-L218)

Triggers on `🔐` lock prefix in private messages (the `/v` fanout discriminator).
Extracts sender from: click event (`/msg <name>`), hover text ("real name is"), or username regex at end. Displays via `ChatUtils.sendStaffChannelMessage()`.

### ServerGuildChatRewriter
[src/client/java/org/wynnvets/chat/rewriter/ServerGuildChatRewriter.java:28-312](src/client/java/org/wynnvets/chat/rewriter/ServerGuildChatRewriter.java#L28-L312)

Detects server guild chat from supporters (via `SupportersPoller.isSupporter()`). Re-renders with animated gradient pill: walks component tree for `banner/pill` font fragments, marks background glyphs with animation sentinel color (replaced at render time by `AnimatedChatMixin`), preserves dark letters.

### SpoilerRewriter
[src/client/java/org/wynnvets/chat/rewriter/SpoilerRewriter.java:27-242](src/client/java/org/wynnvets/chat/rewriter/SpoilerRewriter.java#L27-L242)

Two paths: fast (single fragment contains complete PUA spoiler block) and cross-fragment (spans fragments / server-wrapped). Cross-fragment path preserves pill+name+colon prefix, accumulates body, processes through `ChatUtils.formatMessageBody()` which strips continuation markers.

## 4. Spoiler PUA codec

[src/client/java/org/wynnvets/chat/spoiler/SpoilerCodec.java:23-194](src/client/java/org/wynnvets/chat/spoiler/SpoilerCodec.java#L23-L194) — mirrors [temporary-server/app/parsers/spoiler_codec.py](../../temporary-server/app/parsers/spoiler_codec.py).

- `\uF600` block start, `\uF601` block end
- `\uF602–\uF6FF` = direct 1:1 encoding for chars 0-253 (base = 0xF602)
- `\uF700` = escape for chars ≥ 254, followed by 3 base-254 digits
- Wrapper format for vanilla clients: `[Spoiler: ]` + PUA block

Pipe regex: `\|\|(.+?)\|\|` (non-greedy). Predicates: `containsPipeSpoiler()`, `containsEncodedSpoiler()`.

`SpoilerFormatter.appendWithSpoilers()` renders PUA blocks as green `[Spoiler]` labels with hover text showing decoded content. Gated by `VetsConfig.HANDLE_SPOILERS` (tri-state: null=default=on).

## 5. OutboundDisplayHandler

[src/client/java/org/wynnvets/chat/OutboundDisplayHandler.java](src/client/java/org/wynnvets/chat/OutboundDisplayHandler.java)

Receives all server-pushed messages from `V1ApiManager`.

State:
- `pendingSelfMessages` Deque (max 50, 30s TTL) — echo-suppress messages user just sent
- `recentBridgeMessages` Deque (max 200, 10s TTL) — bridge-echo dedup via normalized-text compare (strips whitespace + PUA)
- `recentUuids` LinkedHashMap (max 200, 10s TTL) — UUID-based dedup

Flow: message → UUID dedup → self suppression → bridge echo check → display via `ChatUtils.sendGuildChatMessage()`.

## 6. ChatUtils — the formatting engine

[src/client/java/org/wynnvets/chat/ChatUtils.java:34-660](src/client/java/org/wynnvets/chat/ChatUtils.java#L34-L660)

Key entry points:
- `sendGuildChatMessage()` line 127 — `<badge> <pill> <username>: <body>`
- `sendGuildChatMessageRed()` line 166 — admin-locked red styling
- `sendStaffChannelMessage()` line 255 — staff-style with special pill
- `formatMessageBody()` line 333 — strips server continuation markers, makes URLs clickable, formats spoilers
- `stripServerContinuations()` line 406 — removes `\n + marker` sequences
- `wrapBlockMessage()` line 539 — word-wrap with continuation block markers
- `dispatchToChat()` line 641 — thread-safe dispatch
- `dispatchAnimatedChat()` line 607 — dispatch with gradient animation context

URL regex: `https?://\S+` (line 64).

Styles: `RANK_STYLE` = aqua, `NAME_STYLE` = dark aqua, `ADMIN_RANK_STYLE` = red, `CHAT_PREFIX_STYLE` references custom `chat/prefix` font for glyphs.

`INTERNAL_CHAT_DISPATCH` ThreadLocal marks mod-generated messages (skip re-logging + rewriter chain).

## 7. PillFormatter

[src/client/java/org/wynnvets/chat/PillFormatter.java:24-127](src/client/java/org/wynnvets/chat/PillFormatter.java#L24-L127)

Formats rank "pill" (badge) component with supporter gradient.

- PUA pills (server-rendered `chat/prefix` font): whole component gets single marker color (gradient-per-character breaks composite font glyph)
- ASCII pills (bridge messages): each character gets marker color
- If `SHOW_SUPPORTER_GLINTS` enabled and supporter → animation sentinel color

## 8. Prepend (badge dedup)

[src/client/java/org/wynnvets/chat/Prepend.java:19-124](src/client/java/org/wynnvets/chat/Prepend.java#L19-L124)

Enum: `DEFAULT` (gold vetsmod badge), `GUILD` (aqua guild badge, compact block marker for consecutive messages within 18 lines), `EMPTY`. Mirrors native Wynncraft behaviour.

## 9. Dispatcher system (staff chat + /find)

Solves: serialize command dispatch on a single-threaded executor and wait for server feedback before next send.

### CommandDispatcher
[src/client/java/org/wynnvets/chat/dispatcher/CommandDispatcher.java:101-150](src/client/java/org/wynnvets/chat/dispatcher/CommandDispatcher.java#L101-L150)

Single-threaded `DISPATCH_EXECUTOR`. `/msg` batches drain first (priority), then `/find`. Provides `shouldSuppressFeedback()` and `shouldSuppressFindResponse()` used by ChatLogMixin.

### MessageFanoutDispatcher
[src/client/java/org/wynnvets/chat/dispatcher/MessageFanoutDispatcher.java:27-532](src/client/java/org/wynnvets/chat/dispatcher/MessageFanoutDispatcher.java#L27-L532)

Fans `/v` out as `/msg <recipient> 🔐 <message>` to every online staff member. Constants:
- `LOCK_PREFIX = "🔐"` (unique `/v` discriminator)
- `SUPPRESSION_TTL_MS = 15_000`
- `OFFLINE_GUIDANCE_SUPPRESSION_WINDOW_MS = 4_000`
- `INTER_SEND_DELAY_MS = 600`
- `MAX_DISPATCH_RETRIES = 3`

Multi-strategy feedback matching (called from ChatLogMixin on incoming messages):
1. Offline-guidance blanket-suppression (4s window after offline error)
2. Direct echo match (exact normalized payload + recipient or lock prefix)
3. Lock-prefix + recipient fallback (Wynntils rewrote coordinates)
4. Censored variant (non-`*` chars align with payload)
5. Token subsequence (last resort)

### FindDispatcher
[src/client/java/org/wynnvets/chat/dispatcher/FindDispatcher.java:26-273](src/client/java/org/wynnvets/chat/dispatcher/FindDispatcher.java#L26-L273)

Batch `/find <username>` dispatcher. `enqueueFindBatch()` returns `CompletableFuture<Map<username,server>>`. Parses responses: "currently on server XX##", "currently on a private server" (sentinel `"PRIVATE"`), "not currently online" (null). `FIND_RESPONSE_WAIT_MS = 6_000`.

## 10. AnimatedChatMixin

[src/client/java/org/wynnvets/mixin/client/chat/AnimatedChatMixin.java:25-65](src/client/java/org/wynnvets/mixin/client/chat/AnimatedChatMixin.java#L25-L65)

`@Mixin(ChatComponent.class)` on `addMessageToDisplayQueue` HEAD + RETURN. Snapshots line count before insertion, wraps newly inserted lines with `AnimatedGradientSequence` when `AnimatedGradientSequence.beginAnimation()` is in effect on the current thread.

## 11. Custom fonts / PUA glyphs

| Font | Used for | Glyphs |
|------|----------|--------|
| `chat/prefix` | Guild badge, alerts, block markers, rank letters | `\uDAFF\uDFFC` (guild badge), `\uE030–\uE059` (rank letters) |
| `banner/pill` | Server-rendered rank pills | background (aqua) + foreground (dark) composite |
| Spoiler PUA | Encoded spoilers | `\uF600`/`\uF601` delimiters, `\uF602–\uF700` content |

## 12. Regex quick reference

| Pattern | File:line | Purpose |
|---------|-----------|---------|
| `https?://\S+` | ChatUtils.java:64 | URL detection |
| `\|\|(.+?)\|\|` | SpoilerCodec.java:51 | Pipe spoilers |
| `real\s+name\s+is\s+([A-Za-z0-9_]{1,16})` | multiple rewriters | Hover→real-name |
| `/msg\s+([A-Za-z0-9_]{1,16})` | StaffChannelMessageRewriter.java:26 | Click→recipient |
| `([A-Za-z0-9_]{1,16})\s*$` | StaffChannelMessageRewriter.java:25 | Username-at-end |
| `⚠⚠⚠ If you are using vetsmod.*` | EncourageUpdateRewriter.java:34 | Version nag |

## 13. Thread safety

All rewriters + ChatLogMixin run on the render thread. Dispatcher serializes outbound commands via a single-threaded executor. Suppression state protected by `Object` locks (`SUPPRESSION_ACK_LOCK`, `FIND_RESPONSE_LOCK`). Dedup caches use `ConcurrentLinkedQueue` + synchronized blocks.

## 14. Feature gates

- `PRINT_BRIDGE_MESSAGES` — enable bridge message display (default true)
- `SHOW_SUPPORTER_GLINTS` — enable animated gradient pill (default true)
- `HANDLE_SPOILERS` — tri-state: null=default(on)/true/false
