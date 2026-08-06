---
name: vetsmod Mixins Reference
description: All 14 registered entries (13 mixins + 1 accessor) — target, inject point, purpose, rationale. Organized by subpackage (chat, command, legacy, accessors) and the top-level mixins.
type: project
originSessionId: dc63f47a-2d15-4f8d-9b6a-41d3049f0cc2
---
# vetsmod Mixins Reference

14 registered entries — 13 mixins plus one accessor interface — all client-side (under `src/client/java/org/wynnvets/mixin/client/`). Authoritative list: [vetsmod.client.mixins.json](../src/client/resources/vetsmod.client.mixins.json). Grouped by subpackage below.

## Chat (3)

### ChatLogMixin
[ChatLogMixin](../src/client/java/org/wynnvets/mixin/client/chat/ChatLogMixin.java)
- **Target:** `@Mixin(ChatComponent.class)`
- **Method:** `addMessage(Component)` at `@At("HEAD")`, `cancellable=true`
- **Purpose:** Main chat pipeline hook. Runs log → guild state detect → mod-initiated response suppression → rewriter chain
- **Why:** Centralizes chat interception; blocks mod-internal dispatch loops (ThreadLocal `INTERNAL_CHAT_DISPATCH`); suppresses `/gu stats`, `/gu rank`, `/v`, `/find` echo feedback; delegates to rewriters

### AnimatedChatMixin
[AnimatedChatMixin](../src/client/java/org/wynnvets/mixin/client/chat/AnimatedChatMixin.java)
- **Target:** `@Mixin(ChatComponent.class)`
- **Method:** `addMessageToDisplayQueue(GuiMessage)` — HEAD + RETURN injections
- **Purpose:** On HEAD, snapshot the *identity* of the current first line; on RETURN, walk forward to that same reference to count what was prepended and wrap those lines with `AnimatedGradientSequence`. It does **not** read the `ThreadLocal<AnimConfig>` that `beginAnimation()` sets — every wrapper is built from `AnimatedGradientSequence.effectiveDefaultStart()`/`effectiveDefaultEnd()`/`DEFAULT_CYCLE_TIME_MS`. Custom colours passed to `beginAnimation` are therefore ignored; the only caller happens to pass exactly those defaults.
- **Why:** Enables smooth time-based gradient animation on newly-inserted chat lines without running a separate tick loop

### GuildChatCommandMixin
[GuildChatCommandMixin](../src/client/java/org/wynnvets/mixin/client/chat/GuildChatCommandMixin.java)
- **Target:** `@Mixin(ClientPacketListener.class)`
- **Method:** `sendCommand(String command)` at `@At("HEAD")`, `cancellable=true`
- **Purpose:** Routes `/g`, `/wg`, `/v` and nine more prefixes through `GuildChatDispatcher.intercept(command)` — **not exhaustive**, see `GuildChatDispatcher.intercept`, which matches 12. `/msg` is not one of them
- **Why:** Staff `/v` gets fanned out to all online staff via `MessageFanoutDispatcher`; Wynncraft natively has no multi-staff chat

## Command (1)

### UnlockCommandMixin
[UnlockCommandMixin](../src/client/java/org/wynnvets/mixin/client/command/UnlockCommandMixin.java)
- **Target:** `@Mixin(ClientPacketListener.class)`
- **Method:** `sendCommand(String command)` at `@At("HEAD")`, `cancellable=true`
- **Purpose:** Intercepts `/unlock <key>` before server. Validates the key shape locally (32–200 char URL-safe base64), persists it to `vetsAuthKey`, and dispatches an `auth` frame on the inbound WS via `V1ApiManager.sendAuth(key)`.
- **Why:** The key is a bearer token issued by dazebot's `/vetsmod` Discord command; it must never be sent to the Wynncraft server. Server-side validation happens asynchronously via dazebot HTTP introspection — see [vetsmod_guild_system.md §4](vetsmod_guild_system.md) and [vetsmod_networking.md §8](vetsmod_networking.md).

## Legacy item (3)

### LegacyHighlightMixin
[LegacyHighlightMixin](../src/client/java/org/wynnvets/mixin/client/legacy/LegacyHighlightMixin.java)
- **Target:** `@Mixin(AbstractContainerScreen.class)`
- **Method:** Two injections — `renderSlot(GuiGraphics, Slot, int, int)` HEAD + `renderTooltip(GuiGraphics, int, int)` HEAD
- **Purpose:** Captures hover context (`LegacyItemHandler.currentItemHasFoil`, `currentItemStack`). Sets `newTooltipStylesAvailable = true` when it sees a `tooltip_style` component
- **Why:** Tooltip rewriter needs hover state; new-server detection enables gold tooltip border without garish fallback on pre-update servers
- **Note:** Does NOT draw — drawing happens in `LegacyHighlightEventListener` (Wynntils `SlotRenderEvent.Pre`, LOWEST)

### LegacyHotbarMixin
[LegacyHotbarMixin](../src/client/java/org/wynnvets/mixin/client/legacy/LegacyHotbarMixin.java)
- **Target:** `@Mixin(Gui.class)`
- **Method:** `renderSlot(GuiGraphics, int x, int y, DeltaTracker, Player, ItemStack, int seed)` at `@At("HEAD")`
- **Purpose:** Draws legacy-item highlight directly on hotbar slots (Wynntils `SlotRenderEvent.Pre` doesn't fire for vanilla hotbar)
- **Why:** Mirrors container-screen highlight behaviour on hotbar; gated by `LEGACY_ITEM_HIGHLIGHTING`

### LegacyItemTooltipMixin
[LegacyItemTooltipMixin](../src/client/java/org/wynnvets/mixin/client/legacy/LegacyItemTooltipMixin.java)
- **Target:** `@Mixin(GuiGraphics.class)`
- **Method:** `setTooltipForNextFrame(Font, List<Component>, Optional<TooltipComponent>, int, int, Identifier)` at `@At("HEAD")`, `cancellable=true`
- **Purpose:** Calls `LegacyItemHandler.processTooltip()`, a one-line delegate to `LegacyTooltipRenderer.processTooltip()` (9-branch cascade; `LegacyTooltipRenderer` is package-private, so the mixin cannot call it directly). If modified, cancels vanilla and re-invokes with mutable copy + optional gold border
- **Why:** Tooltip is the last render stage, after Wynntils events; reentry guard prevents loops

## Top-level (6)

These six live directly under `mixin/client/` rather than a subpackage. They're declared in `vetsmod.client.mixins.json` without a subpackage prefix.

### NametagMixin
[NametagMixin](../src/client/java/org/wynnvets/mixin/client/NametagMixin.java)
- **Target:** `@Mixin(value = AvatarRenderer.class, priority = 900)` — the only vetsmod mixin at 900, and it is load-bearing: it makes vetsmod the inner wrap under wynnmod's outer wrap.
- **Methods:** two injectors. `@Inject` on `extractRenderState(Avatar, AvatarRenderState, F)` at `@At("TAIL")` (`vetsmod$rewriteNameTag`), plus a `@WrapOperation` around the `submitNameTag` call (`vetsmod$reapplyAfterWrap`) that re-applies the override after wynnmod's PRE handler has rebuilt `state.nameTag`. A `WYNNMOD_PRESENT` flag additionally skips the supporter branch at TAIL when wynnmod is loaded.
- **Purpose:** Two-branch nametag overlay. **Anni branch (S4)** runs first: while `AnniOutlineTicker.isOutlineSuppressionActive()` AND `vetsAnniNametagsEnabled`, registry hits get the tier `ChatFormatting` colour and outsiders get `DARK_GRAY`. **Crucially:** the inject calls `ChatFormatting.stripFormatting(state.nameTag.getString())` before building the literal — Wynncraft embeds the team colour as a legacy `§<code>` prefix INSIDE the string content (`§awonderkas`, etc.), and without the strip, vanilla's text renderer parses it at draw time and overrides our `.withStyle(...)` colour silently. `stripFormatting` returns `null` for a null input and `""` for a name that is nothing but § codes, so `applyOverride` falls back to the original string rather than emitting a blank nametag. **Supporter branch** runs only if the anni branch didn't fire: replaces static nametag with `NametagAnimator.tryAnimate()` result for supporters.
- **Why TAIL of extractRenderState, not HEAD of submitNameTag (which it used to be):** the HEAD inject that dispatches `PlayerNametagRenderEvent` on `submitNameTag` is Wynntils' `AvatarRendererMixin` — the same `AvatarRenderer` target this mixin uses, at Wynntils' default priority 1000 against vetsmod's 900. Its subscriber, `CustomNametagRendererFeature.onPlayerNameTagRender`, is a Wynntils `Feature` with an `@SubscribeEvent` handler (not a mixin, and not itself positioned at an injection point); it **cancels** the event whenever it adds gear-hover lines or a Wynntils account-type badge. The cancel propagates back through `AvatarRendererMixin` via the mixin processor's `if (ci.isCancelled()) return;` guard and skips every HEAD inject that runs after it on the same method — vetsmod's, at the lower priority number, being one. Moving to `extractRenderState` TAIL writes the override into `state.nameTag` *before* Wynntils' event handler reads it; Wynntils' prefixed-name component picks up our colour unchanged.
- **What to test the nametag path against.** `onPlayerNameTagRender` has several early exits before it reaches the gear/badge logic, and only the last of them is the cancel this mixin was fighting. In source order: `nameTagAttachment == null` (returns); the entity is not an `AbstractClientPlayer` (returns); `Models.Player.isNpc(player)` (returns, so vanilla runs); the local user's `hidePlayerNametags` config (**unconditional** `setCanceled(true)`); an open `PlayerViewerScreen` on that same player (returns). Only then come `addGearNametags` (raycast hit, and `showGearOnHover` on, and Wynntils has a Hades record) and `addAccountTypeNametag` (any Wynntils user record — with `showWynntilsMarker` on, the logo-prefixed name alone is enough), each of which can cancel. Separately, `hideAllNametags` cancels wholesale through the `EntityNameTagRenderEvent` path rather than the player path. The historical bug surfaced only on hovered Wynntils-tracked players; non-tracked players appeared to work, masking it. Use `/wv debug trigger nametagsDump` to verify the mixin is *resolving* even when in-world rendering looks normal. Anni-branch behaviour itself (registry tiers, the highlight gate) is in [vetsmod_mwe_anni.md](vetsmod_mwe_anni.md) §"Player highlights".
- **Why anni branch first:** A supporter who is also in a vets-anni party shows their role colour for the duration of the highlight gate, and reverts to the animated glint after the gate closes.
- **Data access:** Entity is passed as the first parameter; `instanceof AbstractClientPlayer` check + `getGameProfile().name()` for the username key.

### CommandSuggestionsMixin
[CommandSuggestionsMixin](../src/client/java/org/wynnvets/mixin/client/CommandSuggestionsMixin.java)
- **Target:** `@Mixin(CommandSuggestions.class)` (Brigadier client-side suggestion box)
- **Methods:** `renderUsage(GuiGraphics)` HEAD cancellable, `formatChat(String, int)` HEAD cancellable returning null
- **Purpose:** Suppresses Brigadier's "Unknown or incomplete command" red error text and red `UNPARSED_STYLE` highlight on the input box while [`QueueStateManager.isInQueue()`](../src/client/java/org/wynnvets/queue/QueueStateManager.java) is true
- **Why:** The Wynncraft queue server registers almost no commands, so every typed command (`/g`, etc.) lights up red even though vetsmod is intercepting them client-side via `GuildChatCommandMixin`. Returning `null` from `formatChat` causes the input box to use the default white formatter.

### QueueTitleMixin
[QueueTitleMixin](../src/client/java/org/wynnvets/mixin/client/QueueTitleMixin.java)
- **Target:** `@Mixin(value = ClientPacketListener.class, priority = 500)` — high priority (lower number) so we fire before other mods
- **Method:** `setTitleText(ClientboundSetTitleTextPacket)` at `@At("HEAD")`
- **Purpose:** Feeds the raw title text into [`QueueDetector.handleTitleText`](../src/client/java/org/wynnvets/queue/QueueDetector.java) so we can detect the `Queueing for XX##.` queue title.
- **Why:** Reads the packet directly at the network handler — robust against other mods (e.g. WynnLimbo) that inject earlier and cancel Wynntils' `TitleSetTextEvent` before vetsmod would see it.

### BossHealthOverlayMixin
[BossHealthOverlayMixin](../src/client/java/org/wynnvets/mixin/client/BossHealthOverlayMixin.java)
- **Target:** `@Mixin(value = BossHealthOverlay.class, priority = 500)`
- **Method:** `render(GuiGraphics)`; `@Redirect` on `Ljava/util/Map;values()Ljava/util/Collection;`
- **Purpose:** While `VetsBossBarManager.isActive()`, replace the `events.values()` iteration with a single-element collection holding only our synthetic bar (or empty if it isn't present); otherwise pass through the full collection. Vanilla render still iterates and positions normally — it just sees one entry.
- **Why:** Earlier S3 design cancelled `update(ClientboundBossEventPacket)` and called `events.clear()` on activation (Option B per `boss-bar.md` §3). That left the server's view inconsistent with the local map — subsequent UpdateProgress / UpdateName packets dereferenced `null` in vanilla's `events.get(uuid).setName(...)` and crashed the client (reproduced 2026-06-16). Filtering on the render side lets vanilla + Wynntils track bars normally; Wynntils' `Models.StreamerMode.isInStream()` works without the let-through hack.

### EntityGlowingMixin
[EntityGlowingMixin](../src/client/java/org/wynnvets/mixin/client/EntityGlowingMixin.java)
- **Target:** `@Mixin(Entity.class)` (default priority)
- **Method:** `isCurrentlyGlowing()` at `@At("HEAD")`, `cancellable=true`
- **Purpose:** Returns `true` whenever the entity's Wynntils glow colour is non-`NONE`. Forces vanilla's outline-render path to fire for players the `AnniOutlineTicker` enrolled, even when Wynncraft never put them in a relationship team (no native glow flag).
- **Why:** Per outlines.md §3 Option C "Cons" — Wynntils' `EntityRendererMixin` will happily override `state.outlineColor` from the glow-colour field, but vanilla won't TRIGGER outline rendering without `isCurrentlyGlowing()` returning true. Without this six-liner, "other vets party" players in light-grey would silently render no outline.

### EntityOutlineColorMixin
[EntityOutlineColorMixin](../src/client/java/org/wynnvets/mixin/client/EntityOutlineColorMixin.java)
- **Target:** `@Mixin(EntityRenderer.class)` (default priority)
- **Method:** `extractRenderState(Entity, EntityRenderState, F)` at `@At("TAIL")`
- **Purpose:** Sets `state.outlineColor = 0` for `AbstractClientPlayer` outsiders while `AnniOutlineTicker.isOutlineSuppressionActive()` AND `vetsAnniOutlinesEnabled`. Registry members fall through (Wynntils' own `EntityRendererMixin` TAIL inject overrides `state.outlineColor` from `EntityExtension.getGlowColor()` which the ticker has set).
- **Why this and not a getTeamColor mixin (first try):** Earlier draft was `EntityTeamColorMixin` — HEAD-cancellable on `Entity.getTeamColor()`, returning `0` for outsiders. It rendered every outsider with an **opaque black** outline because vanilla 1.21.11's `extractRenderState` body does `state.outlineColor = ARGB.opaque(getTeamColor())` and `ARGB.opaque(0)` = `0xFF000000`. The outline buffer happily renders that as a solid black glow. Skipping the wrap entirely by clobbering `state.outlineColor` at TAIL of extract sidesteps the issue. Bonus: tab-list colour for outsiders is **not** affected (the read-side `getTeamColor` filter would have neutralised tab list too).
- **Why packet-side never happened:** The original plan called for an `EntityTeamPacketMixin` mutating `ClientboundSetPlayerTeamPacket.color`, requiring an in-dev packet capture to discover Wynncraft's relationship-team patterns. Skipped entirely — the render-side approach needs zero packet inspection and behaves correctly when toggled on/off mid-window (no lingering scoreboard state, no pre-existing-membership leak-through).

## Accessors (1)

### accessors.BossHealthOverlayAccessor
[BossHealthOverlayAccessor](../src/client/java/org/wynnvets/mixin/client/accessors/BossHealthOverlayAccessor.java)
- **Target:** `@Mixin(BossHealthOverlay.class)` (interface)
- **Field:** `@Accessor("events") Map<UUID, LerpingBossEvent> getEvents()`
- **Purpose:** Lets `VetsBossBarManager` insert and remove its synthetic `LerpingBossEvent` directly in the overlay's tracked map without going through the vanilla packet pipeline. That pipeline is deliberately left intact — `BossHealthOverlayMixin` above records the 2026-06-16 crash that cancelling it caused, and filters on the render side instead.
- **Why:** Wynntils already replaces the `events` field with a `ConcurrentHashMap` in its own mixin's `<init>` injector, so our reads/writes from the tick driver are thread-safe relative to vanilla and Wynntils render-thread access.

## Items (beyond legacy)

No non-legacy item mixins. All item behaviour lives in:
- `LegacyHighlightMixin` (container)
- `LegacyHotbarMixin` (hotbar)
- `LegacyItemTooltipMixin` (tooltip)
- `LegacyHighlightEventListener` (via Wynntils SlotRenderEvent.Pre)
- `LegacyTooltipEventListener` (via Wynntils ItemTooltipRenderEvent.Pre)

## Injection priorities

**Which way `priority` runs, stated once.** Mixin applies mixins in ascending `priority` order, so a *numerically higher* value is applied *later*. For an `@Inject` at `HEAD` that means the later-applied mixin's callback is prepended in front of the already-applied ones — the **numerically higher priority runs FIRST at HEAD**, and its `ci.cancel()` skips everyone below it. The in-repo evidence is the S4 nametag failure: vetsmod's `NametagMixin` at **900** lost the HEAD race to Wynntils' `AvatarRendererMixin` at the default **1000**, which is why that work moved to TAIL of an earlier method instead. Default is 1000; the numbers below are read on that scale.

| Mixin | Priority |
|-------|----------|
| `QueueTitleMixin` | 500 — applied first, so at `HEAD` it runs *after* default-priority injects. Not a cancel race: the inject is non-cancellable and only reads the packet |
| `BossHealthOverlayMixin` | 500 (not load-bearing — it is a `@Redirect`, so there is no HEAD cancellation order to win; kept for symmetry with `QueueTitleMixin` and headroom if another render-path mixin lands) |
| `NametagMixin` | 900 — load-bearing: it puts vetsmod's wrap inside wynnmod's |
| All other mixins | Default 1000 (priority is not load-bearing there — S4 nametag work moved off priority-based HEAD ordering to TAIL-of-earlier-method to avoid Wynntils' cancel) |

⚠️ `QueueTitleMixin`'s own class Javadoc argues the opposite ordering ("by reading the packet ourselves at `HEAD` with high priority, we guarantee vetsmod sees the title text regardless of other mods' injection order"). The mixin does still see every title packet no other mod cancels, so nothing is known-broken; but the *reason* given is inverted. Left as-is here — correcting source comments beyond the seven this phase touched is Phase 5.5's job.

For event-based integrations, vetsmod uses `@SubscribeEvent(priority=EventPriority.LOWEST)` on `LegacyHighlightEventListener` so it runs AFTER Wynntils' `ItemHighlightFeature` (registered at HIGH). Drawing at LOWEST effectively overwrites Wynntils' rarity highlight.

## Adding new mixins

Mixins are declared in [vetsmod.client.mixins.json](../src/client/resources/vetsmod.client.mixins.json) (client-side). Use subpackage dotted path (e.g. `chat.ChatLogMixin`) or just the class name for top-level mixins.

Common gotchas:
- `cancellable=true` is required if the mixin may cancel the vanilla call
- `INTERNAL_CHAT_DISPATCH` ThreadLocal must be honoured in any new chat-path mixin to avoid feedback loops
- Check Minecraft Yarn mappings — several intrinsic names changed in 1.21.11 (e.g. `submitNameTag`)
- For hot paths (renderSlot, addMessage), keep logic tight — runs every frame
- **Mixin disallows non-private statics on a mixin class** — both methods and fields. `vetsmod$resetLoggedNametags` crashed mod load with `InvalidMixinException`; the same exception guards fields, unless they are `@Shadow` or synthetic. If you need a public static helper, put it in a sibling helper class.
- **Prefer render-side / read-side filtering to packet-side / write-side mutation.** Four working precedents in this file: `BossHealthOverlayMixin` (`@Redirect` on `events.values()`), `EntityOutlineColorMixin` and `NametagMixin` (both TAIL on `extractRenderState`), `EntityGlowingMixin` (HEAD-cancellable on `isCurrentlyGlowing`). Cancelling has burned us twice, in two directions — cancelling vanilla's `BossHealthOverlay#update` crashed the client, and *being* cancelled by Wynntils short-circuited the nametag override. Keep vanilla bookkeeping intact; intercept what comes out, not what goes in.
- **Don't trust `state.<field>` to mean what you think** — vanilla often wraps or coerces right before assignment. `state.outlineColor = ARGB.opaque(getTeamColor())` turns a `0` from the getter into opaque black, not transparent (see `EntityOutlineColorMixin` above). Check the assignment site, not just the source value.
