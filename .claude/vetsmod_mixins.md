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
- **Purpose:** Main chat pipeline hook. Runs streamer-mode observe → log → guild state detect → mod-initiated response suppression → five-rewriter chain. The observe call is **first**, before logging and before the internal-dispatch check; [vetsmod_chat_pipeline.md](vetsmod_chat_pipeline.md) §"ChatLogMixin — the chokepoint" owns the full step list
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
- **Purpose:** `renderTooltip` hook captures hover context (`LegacyItemHandler.currentItemHasFoil`, `currentItemStack`, plus `currentHoveredSlot` for the item-dump tool), clearing all three when no item is hovered. `renderSlot` hook sets `newTooltipStylesAvailable = true` when it sees a `tooltip_style` component
- **Why:** Tooltip rewriter needs hover state; new-server detection enables gold tooltip border without garish fallback on pre-update servers
- **Note:** Does NOT draw — drawing happens in `LegacyHighlightEventListener` (Wynntils `SlotRenderEvent.Pre`, LOWEST)

### LegacyHotbarMixin
[LegacyHotbarMixin](../src/client/java/org/wynnvets/mixin/client/legacy/LegacyHotbarMixin.java)
- **Target:** `@Mixin(Gui.class)`
- **Method:** `renderSlot(GuiGraphics, int x, int y, DeltaTracker, Player, ItemStack, int seed)` at `@At("HEAD")`
- **Purpose:** Draws legacy-item highlight directly on hotbar slots (Wynntils' container-screen `SlotRenderEvent.Pre` doesn't fire for the vanilla hotbar; Wynntils posts a separate `HotbarSlotRenderEvent.Pre` from its `GuiMixin` at the same `Gui.renderSlot` HEAD, and vetsmod does not subscribe to it)
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
- **Why TAIL of extractRenderState, not HEAD of submitNameTag (which it used to be):** the HEAD inject that dispatches `PlayerNametagRenderEvent` on `submitNameTag` is Wynntils' `AvatarRendererMixin` — the same `AvatarRenderer` target this mixin uses, at Wynntils' default priority 1000 against vetsmod's 900. Its subscriber, `CustomNametagRendererFeature.onPlayerNameTagRender`, is a Wynntils `Feature` with an `@SubscribeEvent` handler (not a mixin, and not itself positioned at an injection point); it **cancels** the event whenever it draws the nametag itself — when it adds gear-hover lines for the hovered player, or when the player is a Wynntils user (neither while Wynntils' own player-viewer screen is open on that player) — and also when its `hidePlayerNametags` option is on. The cancel propagates back through `AvatarRendererMixin` via the mixin processor's `if (ci.isCancelled()) return;` guard and skips every HEAD inject that runs after it on the same method — vetsmod's, at the lower priority number, being one. Moving to `extractRenderState` TAIL writes the override into `state.nameTag` *before* Wynntils' event handler reads it; Wynntils' prefixed-name component picks up our colour unchanged.
- **What to test the nametag path against.** `onPlayerNameTagRender` has several early exits before it reaches the gear/badge logic, and only the last of them is the cancel this mixin was fighting. In source order: `nameTagAttachment == null` (returns); the entity is not an `AbstractClientPlayer` (returns); `Models.Player.isNpc(player)` (returns, so vanilla runs); the local user's `hidePlayerNametags` config (**unconditional** `setCanceled(true)`); an open `PlayerViewerScreen` on that same player (returns). Only then come `addGearNametags` (raycast hit, and `showGearOnHover` on, and Wynntils has a Hades record) and `addAccountTypeNametag` (any Wynntils user record — with `showWynntilsMarker` on, the logo-prefixed name alone is enough), each of which can cancel. Separately, `hideAllNametags` cancels wholesale through the `EntityNameTagRenderEvent` path rather than the player path. The historical bug surfaced only on hovered Wynntils-tracked players; non-tracked players appeared to work, masking it. Use `/wv debug trigger nametagsDump` to check the anni branch's inputs even when in-world rendering looks normal: the gate flags, each player's `AnniOutlineRegistry` hit/miss, and the colour the anni branch *would* resolve. It recomputes that colour itself from the gate and the registry; it does not observe the mixin running and does not read `state.nameTag`. Anni-branch behaviour itself (registry tiers, the highlight gate) is in [vetsmod_mwe_anni.md](vetsmod_mwe_anni.md) §"Player highlights".
- **Why anni branch first:** A supporter who is also in a vets-anni party shows their role colour for the duration of the highlight gate, and reverts to the animated glint after the gate closes.
- **Data access:** the TAIL injector receives the entity as its first parameter. The `@WrapOperation` path has only the render state and reads the entity back through Wynntils' `EntityRenderStateExtension.getEntity()`, which Wynntils' `EntityRendererMixin` populates. Both then go through `applyOverride`'s `instanceof AbstractClientPlayer` check (which also rejects a `null` entity) + `getGameProfile().name()` for the username key.

### CommandSuggestionsMixin
[CommandSuggestionsMixin](../src/client/java/org/wynnvets/mixin/client/CommandSuggestionsMixin.java)
- **Target:** `@Mixin(CommandSuggestions.class)` (Brigadier client-side suggestion box)
- **Methods:** `renderUsage(GuiGraphics)` HEAD cancellable, `formatChat(String, int)` HEAD cancellable returning null
- **Purpose:** Suppresses Brigadier's command-error text (e.g. "Unknown or incomplete command") and grey argument-usage hints (everything `renderUsage` draws from the `commandUsage` list) and the red `UNPARSED_STYLE` highlight on the input box while [`QueueStateManager.isInQueue()`](../src/client/java/org/wynnvets/queue/QueueStateManager.java) is true
- **Why:** The Wynncraft queue server registers almost no commands, so most typed commands (`/g`, etc.) light up red even though vetsmod is intercepting them client-side via `GuildChatCommandMixin`. Returning `null` from `formatChat` causes the input box to use the default white formatter.

### QueueTitleMixin
[QueueTitleMixin](../src/client/java/org/wynnvets/mixin/client/QueueTitleMixin.java)
- **Target:** `@Mixin(value = ClientPacketListener.class, priority = 500)` — **not** "high priority": 500 is *below* the default 1000, so this mixin is applied first and its `HEAD` callback therefore runs **last**. See §"Injection priorities".
- **Method:** `setTitleText(ClientboundSetTitleTextPacket)` at `@At("HEAD")`
- **Purpose:** Feeds the raw title text into [`QueueDetector.handleTitleText`](../src/client/java/org/wynnvets/queue/QueueDetector.java) so we can detect the `Queueing for XX##.` queue title.
- **Why:** Vanilla enters `setTitleText` twice per packet — network thread, then render thread. Wynntils' own `HEAD` inject returns early on the network-thread pass (before its event ever fires), and this one runs there regardless; that is what makes it robust against a mod (e.g. WynnLimbo) cancelling the *event*, which can only happen on the later render-thread pass. ⚠️ It is **not** robust against a mod cancelling the *method* at `HEAD`: at 500 vetsmod runs after every inject applied later, so a cancel at **any** priority above 500 skips it (1000 is just the default, not the threshold). Filed as `queue-title-mixin-priority-inverts-its-own-goal`.

### BossHealthOverlayMixin
[BossHealthOverlayMixin](../src/client/java/org/wynnvets/mixin/client/BossHealthOverlayMixin.java)
- **Target:** `@Mixin(value = BossHealthOverlay.class, priority = 500)`
- **Method:** `render(GuiGraphics)`; `@Redirect` on `Ljava/util/Map;values()Ljava/util/Collection;`
- **Purpose:** While `VetsBossBarManager.isActive()`, replace the `events.values()` iteration with a single-element collection holding only our synthetic bar (or empty if it isn't present); otherwise pass through the full collection. Vanilla render still iterates and positions normally — it just sees one entry.
- **Why:** Earlier S3 design cancelled `update(ClientboundBossEventPacket)` and called `events.clear()` on activation (Option B per `boss-bar.md` §3). That left the server's view inconsistent with the local map — subsequent UpdateProgress / UpdateName packets dereferenced `null` in vanilla's `events.get(uuid).setProgress(...)` / `.setName(...)` and crashed the client (reproduced 2026-06-16). Filtering on the render side lets vanilla + Wynntils track bars normally; Wynntils' `Models.StreamerMode.isInStream()` works without the let-through hack.

### EntityGlowingMixin
[EntityGlowingMixin](../src/client/java/org/wynnvets/mixin/client/EntityGlowingMixin.java)
- **Target:** `@Mixin(Entity.class)` (default priority)
- **Method:** `isCurrentlyGlowing()` at `@At("HEAD")`, `cancellable=true`
- **Purpose:** Returns `true` whenever the entity's Wynntils glow colour is non-`NONE`. Written to force vanilla's outline-render path to fire for players the `AnniOutlineTicker` enrolled, even when Wynncraft never put them in a relationship team (no native glow flag). Whether 1.21.11 still needs it is unconfirmed; see the ⚠️ under **Why**.
- **Why:** Per outlines.md §3 Option C "Cons" — Wynntils' `EntityRendererMixin` will happily override `state.outlineColor` from the glow-colour field, but vanilla won't TRIGGER outline rendering without `isCurrentlyGlowing()` returning true. Without this six-liner, "other vets party" players in light-grey would silently render no outline. ⚠️ That mechanism is not what vanilla 1.21.11's source shows. `isCurrentlyGlowing()` reaches the render path only through `Minecraft.shouldEntityAppearGlowing`, whose one caller is the `outlineColor` ternary in `EntityRenderer.extractRenderState`. Outline rendering keys on `EntityRenderState.appearsGlowing()` (`outlineColor != 0`), which Wynntils' TAIL write already satisfies. Whether this mixin is still needed has not been tested in-game; recorded, not resolved.

### EntityOutlineColorMixin
[EntityOutlineColorMixin](../src/client/java/org/wynnvets/mixin/client/EntityOutlineColorMixin.java)
- **Target:** `@Mixin(EntityRenderer.class)` (default priority)
- **Method:** `extractRenderState(Entity, EntityRenderState, F)` at `@At("TAIL")`
- **Purpose:** Sets `state.outlineColor = 0` for `AbstractClientPlayer` outsiders while `AnniOutlineTicker.isOutlineSuppressionActive()` AND `vetsAnniOutlinesEnabled`. Registry members fall through (Wynntils' own `EntityRendererMixin` TAIL inject overrides `state.outlineColor` from `EntityExtension.getGlowColor()` which the ticker has set).
- **Why this and not a getTeamColor mixin (first try):** Earlier draft was `EntityTeamColorMixin` — HEAD-cancellable on `Entity.getTeamColor()`, returning `0` for outsiders. It rendered every outsider with an **opaque black** outline because vanilla 1.21.11's `extractRenderState` body does `state.outlineColor = ARGB.opaque(getTeamColor())` and `ARGB.opaque(0)` = `0xFF000000`. The outline buffer happily renders that as a solid black glow. Skipping the wrap entirely by clobbering `state.outlineColor` at TAIL of extract sidesteps the issue. Bonus: tab-list colour for outsiders is **not** affected — only `state.outlineColor` is touched. (A `getTeamColor` filter would not have reached the tab list either: in vanilla 1.21.11 that `extractRenderState` assignment is the only call site of `Entity.getTeamColor()` outside `Display`'s own override, and `PlayerTabOverlay` colours names through `PlayerTeam.formatNameForTeam`.)
- **Why packet-side never happened:** The original plan called for an `EntityTeamPacketMixin` mutating `ClientboundSetPlayerTeamPacket.color`, requiring an in-dev packet capture to discover Wynncraft's relationship-team patterns. Skipped entirely — the render-side approach needs zero packet inspection and behaves correctly when toggled on/off mid-window (no lingering scoreboard state, no pre-existing-membership leak-through).

## Accessors (1)

### accessors.BossHealthOverlayAccessor
[BossHealthOverlayAccessor](../src/client/java/org/wynnvets/mixin/client/accessors/BossHealthOverlayAccessor.java)
- **Target:** `@Mixin(BossHealthOverlay.class)` (interface)
- **Field:** `@Accessor("events") Map<UUID, LerpingBossEvent> getEvents()`
- **Purpose:** Lets `VetsBossBarManager` insert and remove its synthetic `LerpingBossEvent` directly in the overlay's tracked map without going through the vanilla packet pipeline. That pipeline is deliberately left intact — `BossHealthOverlayMixin` above records the 2026-06-16 crash that cancelling it caused, and filters on the render side instead.
- **Why:** Wynntils already replaces the `events` field with a `ConcurrentHashMap` in its own mixin's `<init>` injector, though none of the access is concurrent: our tick-driver writes, vanilla's render-path reads (including the `events.values()` iteration that `BossHealthOverlayMixin`'s `@Redirect` filters), and vanilla's packet-handler writes all run on the client game thread.

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
| `QueueTitleMixin` | 500 — applied first, so at `HEAD` it runs *after* default-priority injects. Being non-cancellable protects *other mods from us*, not *us from them*: a third-party cancel at any priority above 500 still skips this inject. Nothing known-broken; filed as `queue-title-mixin-priority-inverts-its-own-goal` |
| `BossHealthOverlayMixin` | 500 (not load-bearing — it is a `@Redirect`, so there is no HEAD cancellation order to win; inherited from `QueueTitleMixin` for symmetry). Not headroom: two redirects on one instruction collide at apply time rather than one losing gracefully, so being early buys nothing (exact failure mode unverified — no second redirect exists to observe) |
| `NametagMixin` | 900 — load-bearing: it puts vetsmod's wrap inside wynnmod's |
| All other mixins | Default 1000 (priority is not load-bearing there — S4 nametag work moved off priority-based HEAD ordering to TAIL-of-earlier-method to avoid Wynntils' cancel) |

✅ **Retired by Phase 5.5a.** `QueueTitleMixin`'s class Javadoc, this file's two `QueueTitleMixin` bullets and both table rows all argued or implied the inverted ordering; all five now describe what 500 actually does. The mixin still sees every title packet no other mod cancels, so nothing is known-broken — whether the defence is wanted is a behaviour question, filed as `queue-title-mixin-priority-inverts-its-own-goal`.

⚠️ **The two priority systems in this repo run in opposite senses, and nothing else says so.** Mixin: ascending application order, so a numerically *higher* number is applied later and wins at `HEAD`. NeoForge `EventPriority`: `HIGHEST` runs *first*, `LOWEST` last. A sentence that is true of one is false of the other, and the two appear in adjacent paragraphs here — which is the likeliest origin of the inverted `QueueTitleMixin` wording 5.5a retired above. Check which system a claim is about before trusting its direction.

For event-based integrations, vetsmod uses `@SubscribeEvent(priority=EventPriority.LOWEST)` on `LegacyHighlightEventListener` so it runs AFTER Wynntils' `ItemHighlightFeature` (registered at HIGH). Drawing at LOWEST effectively overwrites Wynntils' rarity highlight.

## Adding new mixins

Mixins are declared in [vetsmod.client.mixins.json](../src/client/resources/vetsmod.client.mixins.json) (client-side). Use subpackage dotted path (e.g. `chat.ChatLogMixin`) or just the class name for top-level mixins.

Common gotchas:
- `cancellable=true` is required if the mixin may cancel the vanilla call
- `INTERNAL_CHAT_DISPATCH` ThreadLocal must be honoured in any new chat-path mixin to avoid feedback loops
- Check the Mojang mappings (`loom.officialMojangMappings()` in `build.gradle`; the `yarn_mappings` property in `gradle.properties` is read by nothing) — several intrinsic names changed in 1.21.11 (e.g. `submitNameTag`)
- For hot paths keep logic tight — `renderSlot` runs per slot every frame, and `addMessage` once per chat message
- **Mixin disallows non-private statics on a mixin class** — both methods and fields. `vetsmod$resetLoggedNametags` crashed mod load with `InvalidMixinException` ("contains non-private static method"); `MixinPreProcessorStandard.validateField` throws the same exception class for a non-private, non-`@Shadow`, non-synthetic static *field*. If you need a public static helper, put it in a sibling helper class. This is plain SpongePowered Mixin, not MixinSquared — vetsmod's only mixin-helper dependency is MixinExtras (`@WrapOperation` in `NametagMixin`); MixinSquared appears only in archived runtime mod-list dumps, shipped by some other installed mod.
- **Prefer render-side / read-side filtering to packet-side / write-side mutation.** Three working precedents in this file: `BossHealthOverlayMixin` (`@Redirect` on `events.values()`), `EntityOutlineColorMixin` and `NametagMixin` (both TAIL on `extractRenderState`). A fourth, `EntityGlowingMixin` (HEAD-cancellable on `isCurrentlyGlowing`), is read-side in shape, but whether it is still load-bearing in 1.21.11 is unconfirmed — see its entry above. Cancelling has burned us twice, in two directions — cancelling vanilla's `BossHealthOverlay#update` crashed the client, and *being* cancelled by Wynntils short-circuited the nametag override. Keep vanilla bookkeeping intact; intercept what comes out, not what goes in.
- **Don't trust `state.<field>` to mean what you think** — vanilla often wraps or coerces right before assignment. `state.outlineColor = ARGB.opaque(getTeamColor())` turns a `0` from the getter into opaque black, not transparent (see `EntityOutlineColorMixin` above). Check the assignment site, not just the source value.
