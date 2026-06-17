---
name: vetsmod Mixins Reference
description: All 12 mixin classes — target, inject point, purpose, rationale. Organized by subpackage (chat, command, legacy, accessors) and the top-level mixins.
type: project
originSessionId: dc63f47a-2d15-4f8d-9b6a-41d3049f0cc2
---
# vetsmod Mixins Reference

12 mixins total, all client-side (under `src/client/java/org/wynnvets/mixin/client/`). Authoritative list: [src/client/resources/vetsmod.client.mixins.json](src/client/resources/vetsmod.client.mixins.json). Grouped by subpackage below.

## Chat (3)

### ChatLogMixin
[src/client/java/org/wynnvets/mixin/client/chat/ChatLogMixin.java:30-125](src/client/java/org/wynnvets/mixin/client/chat/ChatLogMixin.java#L30-L125)
- **Target:** `@Mixin(ChatComponent.class)`
- **Method:** `addMessage(Component)` at `@At("HEAD")`, `cancellable=true`
- **Purpose:** Main chat pipeline hook. Runs log → guild state detect → mod-initiated response suppression → rewriter chain
- **Why:** Centralizes chat interception; blocks mod-internal dispatch loops (ThreadLocal `INTERNAL_CHAT_DISPATCH`); suppresses `/gu stats`, `/gu rank`, `/v`, `/find` echo feedback; delegates to rewriters

### AnimatedChatMixin
[src/client/java/org/wynnvets/mixin/client/chat/AnimatedChatMixin.java:26-65](src/client/java/org/wynnvets/mixin/client/chat/AnimatedChatMixin.java#L26-L65)
- **Target:** `@Mixin(ChatComponent.class)`
- **Method:** `addMessageToDisplayQueue(GuiMessage)` — HEAD + RETURN injections
- **Purpose:** On HEAD, snapshot first visible line. On RETURN, calculate lines added and wrap them with `AnimatedGradientSequence`. Uses `ThreadLocal<AnimConfig>` from `AnimatedGradientSequence.beginAnimation()`
- **Why:** Enables smooth time-based gradient animation on newly-inserted chat lines without running a separate tick loop

### GuildChatCommandMixin
[src/client/java/org/wynnvets/mixin/client/chat/GuildChatCommandMixin.java:18-27](src/client/java/org/wynnvets/mixin/client/chat/GuildChatCommandMixin.java#L18-L27)
- **Target:** `@Mixin(ClientPacketListener.class)`
- **Method:** `sendCommand(String command)` at `@At("HEAD")`, `cancellable=true`
- **Purpose:** Routes `/g`, `/wg`, `/v`, `/msg` through `GuildChatDispatcher.intercept(command)`
- **Why:** Staff `/v` gets fanned out to all online staff via `MessageFanoutDispatcher`; Wynncraft natively has no multi-staff chat

## Command (1)

### UnlockCommandMixin
[src/client/java/org/wynnvets/mixin/client/command/UnlockCommandMixin.java](src/client/java/org/wynnvets/mixin/client/command/UnlockCommandMixin.java)
- **Target:** `@Mixin(ClientPacketListener.class)`
- **Method:** `sendCommand(String command)` at `@At("HEAD")`, `cancellable=true`
- **Purpose:** Intercepts `/unlock <key>` before server. Validates the key shape locally (32–200 char URL-safe base64), persists it to `vetsAuthKey`, and dispatches an `auth` frame on the inbound WS via `V1ApiManager.sendAuth(key)`.
- **Why:** The key is a bearer token issued by dazebot's `/vetsmod` Discord command; it must never be sent to the Wynncraft server. Server-side validation happens asynchronously via dazebot HTTP introspection — see [vetsmod_guild_system.md §4](vetsmod_guild_system.md) and [vetsmod_networking.md §8](vetsmod_networking.md).

## Legacy item (3)

### LegacyHighlightMixin
[src/client/java/org/wynnvets/mixin/client/legacy/LegacyHighlightMixin.java:25-54](src/client/java/org/wynnvets/mixin/client/legacy/LegacyHighlightMixin.java#L25-L54)
- **Target:** `@Mixin(AbstractContainerScreen.class)`
- **Method:** Two injections — `renderSlot(GuiGraphics, Slot, int, int)` HEAD + `renderTooltip(GuiGraphics, int, int)` HEAD
- **Purpose:** Captures hover context (`LegacyItemHandler.currentItemHasFoil`, `currentItemStack`). Sets `newTooltipStylesAvailable = true` when it sees a `tooltip_style` component
- **Why:** Tooltip rewriter needs hover state; new-server detection enables gold tooltip border without garish fallback on pre-update servers
- **Note:** Does NOT draw — drawing happens in `LegacyHighlightEventListener` (Wynntils `SlotRenderEvent.Pre`, LOWEST)

### LegacyHotbarMixin
[src/client/java/org/wynnvets/mixin/client/legacy/LegacyHotbarMixin.java:21-62](src/client/java/org/wynnvets/mixin/client/legacy/LegacyHotbarMixin.java#L21-L62)
- **Target:** `@Mixin(Gui.class)`
- **Method:** `renderSlot(GuiGraphics, int x, int y, DeltaTracker, Player, ItemStack, int seed)` at `@At("HEAD")`
- **Purpose:** Draws legacy-item highlight directly on hotbar slots (Wynntils `SlotRenderEvent.Pre` doesn't fire for vanilla hotbar)
- **Why:** Mirrors container-screen highlight behaviour on hotbar; gated by `LEGACY_ITEM_HIGHLIGHTING`

### LegacyItemTooltipMixin
[src/client/java/org/wynnvets/mixin/client/legacy/LegacyItemTooltipMixin.java:30-75](src/client/java/org/wynnvets/mixin/client/legacy/LegacyItemTooltipMixin.java#L30-L75)
- **Target:** `@Mixin(GuiGraphics.class)`
- **Method:** `setTooltipForNextFrame(Font, List<Component>, Optional<TooltipComponent>, int, int, Identifier)` at `@At("HEAD")`, `cancellable=true`
- **Purpose:** Runs `LegacyTooltipRenderer.processTooltip()` (8-branch cascade). If modified, cancels vanilla and re-invokes with mutable copy + optional gold border
- **Why:** Tooltip is the last render stage, after Wynntils events; reentry guard prevents loops

## Top-level (3)

These three live directly under `mixin/client/` rather than a subpackage. They're declared in `vetsmod.client.mixins.json` without a subpackage prefix.

### NametagMixin
[src/client/java/org/wynnvets/mixin/client/NametagMixin.java](src/client/java/org/wynnvets/mixin/client/NametagMixin.java)
- **Target:** `@Mixin(AvatarRenderer.class, priority = 900)` (fires before default 1000)
- **Method:** `submitNameTag(AvatarRenderState, PoseStack, SubmitNodeCollector, CameraRenderState)` at `@At("HEAD")`
- **Purpose:** Replaces static nametag component with `NametagAnimator.tryAnimate()` result for supporters
- **Why:** Animated gradient glint effect on usernames. Gated by `SHOW_SUPPORTER_GLINTS` config and `SupportersPoller.isSupporter()`
- **Data access:** Uses Wynntils `EntityRenderStateExtension` to get real `Player` entity + `GameProfile.getName()`, bypassing nametag text parsing

### CommandSuggestionsMixin
[src/client/java/org/wynnvets/mixin/client/CommandSuggestionsMixin.java](src/client/java/org/wynnvets/mixin/client/CommandSuggestionsMixin.java)
- **Target:** `@Mixin(CommandSuggestions.class)` (Brigadier client-side suggestion box)
- **Methods:** `renderUsage(GuiGraphics)` HEAD cancellable, `formatChat(String, int)` HEAD cancellable returning null
- **Purpose:** Suppresses Brigadier's "Unknown or incomplete command" red error text and red `UNPARSED_STYLE` highlight on the input box while [`QueueStateManager.isInQueue()`](src/client/java/org/wynnvets/queue/QueueStateManager.java) is true
- **Why:** The Wynncraft queue server registers almost no commands, so every typed command (`/g`, etc.) lights up red even though vetsmod is intercepting them client-side via `GuildChatCommandMixin`. Returning `null` from `formatChat` causes the input box to use the default white formatter.

### QueueTitleMixin
[src/client/java/org/wynnvets/mixin/client/QueueTitleMixin.java](src/client/java/org/wynnvets/mixin/client/QueueTitleMixin.java)
- **Target:** `@Mixin(value = ClientPacketListener.class, priority = 500)` — high priority (lower number) so we fire before other mods
- **Method:** `setTitleText(ClientboundSetTitleTextPacket)` at `@At("HEAD")`
- **Purpose:** Feeds the raw title text into [`QueueDetector.handleTitleText`](src/client/java/org/wynnvets/queue/QueueDetector.java) so we can detect the `Queueing for XX##.` queue title.
- **Why:** Reads the packet directly at the network handler — robust against other mods (e.g. WynnLimbo) that inject earlier and cancel Wynntils' `TitleSetTextEvent` before vetsmod would see it.

### BossHealthOverlayMixin
[src/client/java/org/wynnvets/mixin/client/BossHealthOverlayMixin.java](src/client/java/org/wynnvets/mixin/client/BossHealthOverlayMixin.java)
- **Target:** `@Mixin(value = BossHealthOverlay.class, priority = 500)`
- **Method:** `render(GuiGraphics)`; `@Redirect` on `Ljava/util/Map;values()Ljava/util/Collection;`
- **Purpose:** While `VetsBossBarManager.isActive()`, replace the `events.values()` iteration with a single-element collection holding only our synthetic bar (or empty if it isn't present); otherwise pass through the full collection. Vanilla render still iterates and positions normally — it just sees one entry.
- **Why:** Earlier S3 design cancelled `update(ClientboundBossEventPacket)` and called `events.clear()` on activation (Option B per `boss-bar.md` §3). That left the server's view inconsistent with the local map — subsequent UpdateProgress / UpdateName packets dereferenced `null` in vanilla's `events.get(uuid).setName(...)` and crashed the client (reproduced 2026-06-16). Filtering on the render side lets vanilla + Wynntils track bars normally; Wynntils' `Models.StreamerMode.isInStream()` works without the let-through hack.

## Accessors (1)

### accessors.BossHealthOverlayAccessor
[src/client/java/org/wynnvets/mixin/client/accessors/BossHealthOverlayAccessor.java](src/client/java/org/wynnvets/mixin/client/accessors/BossHealthOverlayAccessor.java)
- **Target:** `@Mixin(BossHealthOverlay.class)` (interface)
- **Field:** `@Accessor("events") Map<UUID, LerpingBossEvent> getEvents()`
- **Purpose:** Lets `VetsBossBarManager` insert and remove its synthetic `LerpingBossEvent` directly in the overlay's tracked map without going through the vanilla packet pipeline (which is cancelled by `BossHealthOverlayMixin`).
- **Why:** Wynntils already replaces the `events` field with a `ConcurrentHashMap` in its own mixin's `<init>` injector, so our reads/writes from the tick driver are thread-safe relative to vanilla and Wynntils render-thread access.

## Items (beyond legacy)

No non-legacy item mixins. All item behaviour lives in:
- `LegacyHighlightMixin` (container)
- `LegacyHotbarMixin` (hotbar)
- `LegacyItemTooltipMixin` (tooltip)
- `LegacyHighlightEventListener` (via Wynntils SlotRenderEvent.Pre)
- `LegacyTooltipEventListener` (via Wynntils ItemTooltipRenderEvent.Pre)

## Injection priorities

| Mixin | Priority |
|-------|----------|
| `QueueTitleMixin` | 500 (very high — fires before other title mixins) |
| `BossHealthOverlayMixin` | 500 (so we cancel before Wynntils sees the packet) |
| `NametagMixin` | 900 (before Wynntils default 1000) |
| All other mixins | Default 1000 |

For event-based integrations, vetsmod uses `@SubscribeEvent(priority=EventPriority.LOWEST)` on `LegacyHighlightEventListener` so it runs AFTER Wynntils' `ItemHighlightFeature` (registered at HIGH). Drawing at LOWEST effectively overwrites Wynntils' rarity highlight.

## Adding new mixins

Mixins are declared in [src/client/resources/vetsmod.client.mixins.json](src/client/resources/vetsmod.client.mixins.json) (client-side). Use subpackage dotted path (e.g. `chat.ChatLogMixin`) or just the class name for top-level mixins.

Common gotchas:
- `cancellable=true` is required if the mixin may cancel the vanilla call
- `INTERNAL_CHAT_DISPATCH` ThreadLocal must be honoured in any new chat-path mixin to avoid feedback loops
- Check Minecraft Yarn mappings — several intrinsic names changed in 1.21.11 (e.g. `submitNameTag`)
- For hot paths (renderSlot, addMessage), keep logic tight — runs every frame
