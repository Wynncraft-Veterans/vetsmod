---
name: vetsmod Chat Pipeline
description: Complete chat pipeline — ChatLogMixin hook, rewriter chain, dispatcher system, OutboundDisplayHandler, PillFormatter, SpoilerCodec, state/caches
type: project
originSessionId: dc63f47a-2d15-4f8d-9b6a-41d3049f0cc2
---
# vetsmod Chat Pipeline — In-Depth Reference

The vetsmod chat system is a multi-stage pipeline that intercepts every chat message, classifies it, suppresses mod-initiated echoes, rewrites through a chain, and dispatches formatted output. It is coupled to an outbound WebSocket handler for server-pushed messages, a dispatcher system for staff fanout, and a PUA-based spoiler codec.

## 1. High-level flow

**Incoming (server → client):** vanilla `ChatComponent.addMessage()` → `ChatLogMixin` HEAD → streamer-mode observation → log → guild state detection → suppression checks (/gu stats, /gu rank, /v, /find) → rewriter chain → fall through to vanilla (or cancelled if a rewriter consumed it).

**Outbound (user → server):** `ClientPacketListener.sendCommand()` → `GuildChatCommandMixin` HEAD → `GuildChatDispatcher.intercept()` for `/g`, `/wg`, `/v` and nine more prefixes — **not exhaustive**, see `GuildChatDispatcher.intercept`, which matches 12. `/msg` is not among them.

**Remote push (WebSocket → client):** `V1ApiManager` outbound listener → `OutboundDisplayHandler.onOutboundMessage()` → UUID dedup + self + bridge echo suppression → `ChatUtils.sendGuildChatMessage()`.

## 2. ChatLogMixin — the chokepoint

[ChatLogMixin](../src/client/java/org/wynnvets/mixin/client/chat/ChatLogMixin.java)

`@Mixin(ChatComponent.class) @Inject(method="addMessage", at=@At("HEAD"), cancellable=true)` — runs in order:

1. `StreamerModeChatDetector.observe(message)` — runs first, before any logging
2. `ChatLogger.logMessage(...)` unless `ChatUtils.isInternalDispatch()` is true (the `INTERNAL_CHAT_DISPATCH` ThreadLocal is private to `ChatUtils`; the mixin reads it through that accessor)
3. `GuildStateManager.processGuildCheckMessage(msg)` (pulls `/gu stats` responses out of chat)
4. Compute `isStaffRankCheck` = `GuildStateManager.isProcessingModStaffRankCheck()` AND the line looks like a staff-rank-check response
5. `GuildStateManager.processMessage(message, messageString)` — unconditional, and it runs *before* the suppression below, so a suppressed rank-check line is still processed
6. Suppress if `isStaffRankCheck`
7. Suppress via `CommandDispatcher.shouldSuppressFeedback()` (`/v` echo)
8. Suppress via `CommandDispatcher.shouldSuppressFindResponse()` (`/find` result)
9. Rewriter chain (only when NOT internally dispatched):
   1. `EncourageUpdateRewriter.tryRewrite()`
   2. `StaffGuildAlertRewriter.tryRewrite()`
   3. `StaffChannelMessageRewriter.tryRewrite()`
   4. `ServerGuildChatRewriter.tryRewrite()`
   5. `SpoilerRewriter.tryRewrite()`

Each rewriter returns `true` to cancel the vanilla event (consumed).

**Gap:** `WarningRewriter` — the sixth rewriter on disk, invoked from `OutboundDisplayHandler` for server-pushed `warning` frames rather than from this chain. It has no section here; the one-line summary lives in [CLAUDE.md](CLAUDE.md) and [project_vetsmod.md](project_vetsmod.md).

## 3. Rewriters

### EncourageUpdateRewriter
[EncourageUpdateRewriter](../src/client/java/org/wynnvets/chat/rewriter/EncourageUpdateRewriter.java)

Pattern: `⚠⚠⚠ If you are using vetsmod, it's outdated (current version ([0-9]+(?:\.[0-9]+)*)) ⚠⚠⚠`.
Parses staff-broadcast version, compares to local; outdated → obfuscated red; up-to-date → rainbow.

### StaffGuildAlertRewriter
[StaffGuildAlertRewriter](../src/client/java/org/wynnvets/chat/rewriter/StaffGuildAlertRewriter.java)

Triggers on `‼` prefix. Builds purple shout prefix + "ALERT" pill + body (bold if `!!`). Has `tryRewrite()` for in-game Component and `tryRewriteOutbound()` for WebSocket JSON. Resolves real usernames from hover text "X's real name is Y".

### StaffChannelMessageRewriter
[StaffChannelMessageRewriter](../src/client/java/org/wynnvets/chat/rewriter/StaffChannelMessageRewriter.java)

Triggers on `🔐` lock prefix in private messages (the `/v` fanout discriminator).
Extracts sender from: click event (`/msg <name>`), hover text ("real name is"), or username regex at end. Displays via `ChatUtils.sendStaffChannelMessage()`.

### ServerGuildChatRewriter
[ServerGuildChatRewriter](../src/client/java/org/wynnvets/chat/rewriter/ServerGuildChatRewriter.java)

Detects server guild chat from supporters (via `SupportersPoller.isSupporter()`). Re-renders with animated gradient pill: walks component tree for `banner/pill` font fragments, marks background glyphs with animation sentinel color (replaced at render time by `AnimatedChatMixin`), preserves dark letters.

### SpoilerRewriter
[SpoilerRewriter](../src/client/java/org/wynnvets/chat/rewriter/SpoilerRewriter.java)

Two paths: fast (single fragment contains complete PUA spoiler block) and cross-fragment (spans fragments / server-wrapped). Cross-fragment path preserves pill+name+colon prefix, accumulates body, processes through `ChatUtils.formatMessageBody()` which strips continuation markers.

## 4. Spoiler PUA codec

[SpoilerCodec](../src/client/java/org/wynnvets/chat/spoiler/SpoilerCodec.java) — mirrors [temporary-server/app/parsers/spoiler_codec.py](../../temporary-server/app/parsers/spoiler_codec.py).

- `\uF600` block start, `\uF601` block end
- `\uF602–\uF6FF` = direct 1:1 encoding for chars 0-253 (base = 0xF602)
- `\uF700` = escape for chars ≥ 254, followed by 3 base-254 digits
- Wrapper format for vanilla clients: `[Spoiler: ]` + PUA block

Pipe regex: `\|\|(.+?)\|\|` (non-greedy). Predicates: `containsPipeSpoiler()`, `containsEncodedSpoiler()`.

`SpoilerFormatter.appendWithSpoilers()` renders PUA blocks as green `[Spoiler]` labels with hover text showing decoded content. Gated by `VetsConfig.HANDLE_SPOILERS` (tri-state: null=default=on).

## 5. OutboundDisplayHandler

[OutboundDisplayHandler](../src/client/java/org/wynnvets/chat/OutboundDisplayHandler.java)

Receives all server-pushed messages from `V1ApiManager`.

State:
- `pendingSelfMessages` Deque (max 50, 30s TTL) — echo-suppress messages user just sent
- `recentBridgeMessages` Deque (max 200, 10s TTL) — bridge-echo dedup via normalized-text compare (strips whitespace + PUA)
- `recentUuids` LinkedHashMap (max 200, 10s TTL) — UUID-based dedup

Flow: message → UUID dedup → self suppression → bridge echo check → display via `ChatUtils.sendGuildChatMessage()`.

## 6. ChatUtils — the formatting engine

[ChatUtils](../src/client/java/org/wynnvets/chat/ChatUtils.java)

Key methods (`stripServerContinuations` and `wrapBlockMessage` are private helpers, not entry points):
- `sendGuildChatMessage()` — `<badge> <pill> <username>: <body>`
- `sendGuildChatMessageRed()` — admin-locked red styling
- `sendStaffChannelMessage()` — staff-style with special pill
- `formatMessageBody()` — strips server continuation markers, makes URLs clickable, formats spoilers
- `stripServerContinuations()` — removes `\n + marker` sequences
- `wrapBlockMessage()` — word-wrap with continuation block markers
- `dispatchToChat()` — thread-safe dispatch
- `dispatchAnimatedChat()` — dispatch with gradient animation context

URL regex (`URL_PATTERN`, case-insensitive): `(?<!§)(https?://\S+|[A-Za-z0-9][A-Za-z0-9-]*(?:\.[A-Za-z0-9][A-Za-z0-9-]*)+/\S*)` — it also matches schemeless `domain.tld/path`, and the lookbehind keeps it off section-sign colour codes.

Styles: `RANK_STYLE` = aqua, `NAME_STYLE` = dark aqua, `ADMIN_RANK_STYLE` = red, `CHAT_PREFIX_STYLE` references custom `chat/prefix` font for glyphs.

`INTERNAL_CHAT_DISPATCH` ThreadLocal marks mod-generated messages (skip re-logging + rewriter chain).

## 7. PillFormatter

[PillFormatter](../src/client/java/org/wynnvets/chat/PillFormatter.java)

Formats rank "pill" (badge) component with supporter gradient.

- PUA pills (server-rendered `chat/prefix` font): whole component gets single marker color (gradient-per-character breaks composite font glyph)
- ASCII pills (bridge messages): each character gets marker color
- If `SHOW_SUPPORTER_GLINTS` enabled and supporter → animation sentinel color

## 8. Prepend (badge dedup)

[Prepend](../src/client/java/org/wynnvets/chat/Prepend.java)

Enum, four constants: `DEFAULT` (gold vetsmod badge), `GUILD` (aqua guild badge, compact block marker for consecutive messages within 18 lines), `GUILD_HONOURARY`, `EMPTY`. Mirrors native Wynncraft behaviour.

## 9. Dispatcher system (staff chat + /find)

Solves: serialize command dispatch on a single-threaded executor and wait for server feedback before next send.

### CommandDispatcher
[CommandDispatcher](../src/client/java/org/wynnvets/chat/dispatcher/CommandDispatcher.java)

Single-threaded `DISPATCH_EXECUTOR`. `/msg` batches drain first (priority), then `/find`. Provides `shouldSuppressFeedback()` and `shouldSuppressFindResponse()` used by ChatLogMixin.

### MessageFanoutDispatcher
[MessageFanoutDispatcher](../src/client/java/org/wynnvets/chat/dispatcher/MessageFanoutDispatcher.java)

Fans `/v` out as `/msg <recipient> 🔐 <message>` to every online staff member. Constants:
- `CommandDispatcher.LOCK_PREFIX = "🔐"` (unique `/v` discriminator; declared there, not here)
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
[FindDispatcher](../src/client/java/org/wynnvets/chat/dispatcher/FindDispatcher.java)

Batch `/find <username>` dispatcher. `enqueueFindBatch()` returns `CompletableFuture<Map<username,server>>`. Parses responses: "currently on server XX##", "currently on a private server" (sentinel `"PRIVATE"`), "not currently online" (null). `FIND_RESPONSE_WAIT_MS = 6_000`.

## 10. AnimatedChatMixin

[AnimatedChatMixin](../src/client/java/org/wynnvets/mixin/client/chat/AnimatedChatMixin.java)

`@Mixin(ChatComponent.class)` on `addMessageToDisplayQueue` HEAD + RETURN. Snapshots the *identity* of the current first line at HEAD, then at RETURN walks forward to that same reference to count what was prepended and wraps those lines with `AnimatedGradientSequence`. The wrapper is built from `AnimatedGradientSequence`'s effective defaults, not from the `beginAnimation()` ThreadLocal — colours passed to `beginAnimation` do not reach it.

## 11. Custom fonts / PUA glyphs

| Font | Used for | Glyphs |
|------|----------|--------|
| `chat/prefix` | Guild badge, alerts, block markers, rank letters | `\uDAFF\uDFFC` (guild badge), `\uE030–\uE059` (rank letters) |
| `banner/pill` | Server-rendered rank pills | background (aqua) + foreground (dark) composite |
| Spoiler PUA | Encoded spoilers | `\uF600`/`\uF601` delimiters, `\uF602–\uF700` content |

**Gap:** three top-level `chat/` classes go unmentioned in this reference — `PillCodec` (the PUA pill authority; see [vetsmod_pua_pills.md](vetsmod_pua_pills.md)), `DiscordTimestamps` and `RankDisplayMap`. A fourth, `NickResolver` — the shared real-name and component-flattening helpers — appears only in §12's regex table and has no section of its own. (Both `NickResolver` and `PillCodec` now have unit tests; an earlier version of this note called `NickResolver` the only tested class under `chat/`.)

## 12. Regex quick reference

| Pattern | Constant | Purpose |
|---------|-----------|---------|
| `(?<!§)(https?://\S+\|[A-Za-z0-9][A-Za-z0-9-]*(?:\.[A-Za-z0-9][A-Za-z0-9-]*)+/\S*)` | `ChatUtils.URL_PATTERN` | URL detection (scheme or schemeless) |
| `\|\|(.+?)\|\|` | `SpoilerCodec.PIPE_SPOILER` | Pipe spoilers |
| `real\s+name\s+is\s+([A-Za-z0-9_]{1,16})` | `NickResolver.REAL_NAME_PATTERN` | Hover→real-name (only `EncourageUpdateRewriter` keeps a private duplicate) |
| `/msg\s+([A-Za-z0-9_]{1,16})` | `StaffChannelMessageRewriter.MSG_COMMAND_PATTERN` | Click→recipient |
| `([A-Za-z0-9_]{1,16})\s*$` | `StaffChannelMessageRewriter.USERNAME_AT_END` | Username-at-end |
| `⚠⚠⚠ If you are using vetsmod.*` | `EncourageUpdateRewriter.ENCOURAGE_PATTERN` | Version nag |

## 13. Thread safety

All rewriters + ChatLogMixin run on the render thread. Dispatcher serializes outbound commands via a single-threaded executor. Suppression state protected by `Object` locks (`SUPPRESSION_ACK_LOCK`, `FIND_RESPONSE_LOCK`). Dedup caches use `ConcurrentLinkedQueue` + synchronized blocks.

## 14. Feature gates

- `PRINT_BRIDGE_MESSAGES` — enable bridge message display (default true)
- `SHOW_SUPPORTER_GLINTS` — enable animated gradient pill (default true)
- `HANDLE_SPOILERS` — tri-state: null=default(on)/true/false
