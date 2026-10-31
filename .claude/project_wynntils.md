---
name: Wynntils API Reference
description: Public API surface of Wynntils that vetsmod depends on — events (with field layouts, the action bar's segments among them), Handlers.Command, Models.Guild, Models.Container, Models.CharacterStats and Models.CombatXp, StyledText, McUtils, event priority system, and the feature settings retro writes
type: reference
originSessionId: dc63f47a-2d15-4f8d-9b6a-41d3049f0cc2
---
**Repo:** sibling clone at `../Wynntils/` — read-only reference, not edited. Upstream: <https://github.com/Wynntils/Wynntils>.
**Version used by vetsmod:** v4.1.17-fabric (via Modrinth Maven, `modCompileOnly`). `fabric.mod.json`'s `>=4.1.4` is the runtime floor, a separate and deliberately older number.
**Platform:** Architectury (Fabric + NeoForge), MC 1.21.11, Java 21, LGPL v3.0

## Component types (4)

- `Managers` — framework services (Feature, Config, Overlay, Download, Net, Command, etc.)
- `Models` — Wynncraft state (60+ models: Character, Guild, Player, Item, WorldState, etc.)
- `Handlers` — Minecraft↔Wynncraft bridges (Chat, Item, Container, Tooltip, ActionBar, Scoreboard, Label)
- `Services` — auxiliary (MapData, ItemFilter, Statistics, Leaderboard, Cosmetics, WynntilsAccount)

All accessed as singletons: `Models.Guild`, `Handlers.Command`, etc.

## WynntilsMod public API

```java
WynntilsMod.postEvent(Event)
WynntilsMod.postEventOnMainThread(Event)
WynntilsMod.registerEventListener(Object listener)   // scans @SubscribeEvent methods
WynntilsMod.unregisterEventListener(Object listener)
```

`@SubscribeEvent` (NeoForge `net.neoforged.bus.api.SubscribeEvent`): `public void foo(SomeEvent e)`. Optional: `priority = EventPriority.LOWEST, receiveCanceled = true`.

## Events — field layouts

### ChatMessageEvent
File: `common/src/main/java/com/wynntils/handlers/chat/event/ChatMessageEvent.java`

Base fields: `StyledText message`, `RecipientType recipientType`

**ChatMessageEvent.Match** (pre-display, cancellable):
- `getMessage(): StyledText`
- `getRecipientType(): RecipientType`
- `isChatCanceled(): boolean` / `cancelChat(): void`

**ChatMessageEvent.Edit** (rewrite opportunity):
- `getMessage(): StyledText` — original or already-edited
- `setMessage(StyledText): void`

**RecipientType enum** (`handlers/chat/type/RecipientType.java`):
`INFO, CLIENTSIDE, GLOBAL, LOCAL, GUILD, PARTY, PRIVATE, SHOUT, PETS, GAME_MESSAGE`
- `matchPattern(StyledText): boolean`
- `getName(): String`
- `fromName(String): RecipientType`

### WorldStateEvent
File: `common/src/main/java/com/wynntils/models/worlds/event/WorldStateEvent.java`

Fields: `WorldState newState`, `WorldState oldState`, `String worldName`, `boolean isFirstJoinWorld`

**WorldState enum** (`models/worlds/type/WorldState.java`):
`NOT_CONNECTED, CONNECTING, INTERIM, HUB, CHARACTER_SELECTION, WORLD`

vetsmod acts on `WORLD` state: `if (event.getNewState() == WorldState.WORLD) { ... }`

### GuildEvent
File: `common/src/main/java/com/wynntils/models/guild/event/GuildEvent.java`

**GuildEvent.Joined** — `getGuildName(): String`
**GuildEvent.Left** — `getGuildName(): String`

### SlotRenderEvent
File: `common/src/main/java/com/wynntils/mc/event/SlotRenderEvent.java`

Base: `GuiGraphics guiGraphics`, `Screen screen`, `Slot slot`

**SlotRenderEvent.Pre** (cancellable `ICancellableEvent`), **SlotRenderEvent.CountPre**, **SlotRenderEvent.Post**

vetsmod registers `LegacyHighlightEventListener` at `EventPriority.LOWEST` on `.Pre` — runs AFTER Wynntils' `ItemHighlightFeature` (at HIGH), overriding its rarity highlight. `RetroSlotListener` registers on `.Pre` too, at `HIGHEST` with `receiveCanceled = true`: retro's glass goes under both highlights, and onto a slot another `HIGHEST` listener cancels (Wynntils' `EnhancedStreamerModeFeature` cancels some). `.Pre` is posted at the head of `AbstractContainerScreen.renderSlot`, with the pose translated to the container, so `slot.x`/`slot.y` are the slot's own coordinates. `.Post` is posted at its return, after the item and its count; `RetroSlotIconListener` draws a gear box's or a tome's type there (at `NORMAL`), as does Wynntils' `UnidentifiedItemIconFeature` for gear boxes, and on `HotbarSlotRenderEvent.Post` (`GuiMixin`, at the return of `Gui.renderSlot`; `getItemStack()`, `getX()`, `getY()`) for the hotbar.

### ContainerLabelRenderEvent
File: `common/src/main/java/com/wynntils/mc/event/ContainerLabelRenderEvent.java`

Base (cancellable): `AbstractContainerScreen<?> screen`, `GuiGraphics guiGraphics`, `float x`, `float y`, `int color` (with `setColor`). Wynntils' `AbstractContainerScreenMixin` posts it from a `@WrapOperation` on each of `renderLabels`' two `drawString` calls, before the slots are drawn in the same frame.

**ContainerLabel** — `getContainerLabel()` / `setContainerLabel(Component)`: the container's title. **InventoryLabel** — `getInventoryLabel()` / `setInventoryLabel(Component)`: the player-inventory label. `InventoryScreen` overrides `renderLabels`, so neither is posted for the inventory.

vetsmod's `RetroContainerTitleListener` (default priority; no Wynntils feature listens to either) sets the `ContainerLabel` to retro's title and leaves the colour, which Wynntils passes on from vanilla (`-12566464`, `0xFF404040`). The label the event carries is the screen's own title (`this.title`, the first `drawString`), so retro decides a screen from `screen.getTitle()`; on the chest, hopper and mount screens that decision is already made in `renderBg`, which runs before `renderLabels` (`RetroContainerBackgroundMixin`, [vetsmod_mixins.md](vetsmod_mixins.md)). On the `InventoryLabel`, posted right after from the same `renderLabels`, it sets an empty label on a screen that keeps Wynncraft's: the retro pack's `lang` restores "Inventory", which would otherwise be drawn over Wynncraft's art. Both events, with these methods and both posts, are the same at 4.1.4, 4.1.17 and current `main`.

### ActionBarRenderEvent
File: `common/src/main/java/com/wynntils/handlers/actionbar/event/ActionBarRenderEvent.java`

`ActionBarHandler` splits each action bar Wynncraft sends (`SystemMessageEvent.GameInfoReceivedEvent`, on the main thread) into segments, one per registered `ActionBarSegmentMatcher` that finds its glyphs, plus fallback segments for the text none matched. It posts `ActionBarUpdatedEvent` when the text changed, where the models read their values (`runIfPresent(SegmentClass, ...)`), then `ActionBarRenderEvent` for every packet, and finally cuts the disabled segments out of the text that is drawn. Not cancellable.

- `getSegments()`, `getDisabledSegments()`: the segments, and the ones disabled so far.
- `setSegmentEnabled(Class<? extends ActionBarSegment>, boolean)`: sets the **first** segment that is an instance of the class (`findFirst`), so a class matched twice in one bar keeps its second. Disabling changes only what is drawn; the models have already read the segment.
- `setRenderCoordinates(boolean)`: whether the coordinates part is drawn (Wynntils' `CoordinatesOverlay` turns it off).

What the segments draw, in Wynncraft's action-bar font `minecraft:hud/gameplay/default/bottom_middle` (pack `a43ae4e6`), under `models/characterstats/actionbar/segments/` unless noted: `HealthBarSegment` and `ManaBarSegment` the two bars with their backgrounds; `HealthTextSegment` and `ManaTextSegment` their numbers above them; `HotbarSegment` the ornate wooden frame round the hotbar (one glyph); `CombatExperienceSegment` the thin green experience bar on vanilla's experience bar's place, and `ProfessionExperienceSegment` the same bar while gathering (both `ExperienceSegment`s, 53 steps); `LevelSegment` the level's digits, inside the hexagon `MeterBarSegment` draws above the hotbar's middle, whose rim shows sprint (green) and breath (blue), also the bigger ultimate meter; `MeterEdgeAnimationSegment`, `MeterTransitionSegment` and `UltimateReadyTransitionSegment` the hexagon's animations; `UltimateReadyActivateSegment` the "F" key prompt above it; `PowderSpecialSegment` the powder special bar; `models/spells/actionbar/segments/`' `UltimateSegment` an ultimate's charge in the hexagon, and `SpellInputsSegment` and `SpellCastSegment` the spell text. These classes, the event and `ActionBarRenderEvent`'s methods are the same at 4.1.4 and 4.1.17 (only `ActionBarHandler`'s handling of an empty bar changed).

vetsmod's `RetroActionBarListener` (default priority) has `RetroHud.hideWynncraftsBars` disable the bars, the frame, the experience bars and the hexagon with its digits, animations and charge while retro draws the HUD ([vetsmod_retro.md](vetsmod_retro.md) §7). Wynntils' own `CharacterStatsModel` and `SpellModel` disable segments from the same event for their hide settings.

### ItemTooltipRenderEvent
File: `common/src/main/java/com/wynntils/mc/event/ItemTooltipRenderEvent.java`

Base: `GuiGraphics guiGraphics`, `ItemStack itemStack`, `int mouseX`, `int mouseY`

**ItemTooltipRenderEvent.Pre** (cancellable):
- `getTooltips(): List<Component>` / `setTooltips(List<Component>): void`
- `setMouseX/Y(int): void` / `setItemStack(ItemStack): void`

**ItemTooltipRenderEvent.Post** — read-only, after render.

## Handlers

### Handlers.Command
File: `common/src/main/java/com/wynntils/handlers/command/CommandHandler.java`

- `queueCommand(String command)` — spaced by `TICKS_PER_EXECUTE` = 7 ticks (~350 ms; Wynntils' Javadoc says it respects the server rate limit): sends at once when the spacing has elapsed, otherwise appends to the queue. Do NOT include leading `/`.
- `sendCommandImmediately(String command)` — bypasses queue; for urgent / user-initiated commands.

Used by vetsmod: `/gu stats`, `/gu rank`, `/find`, `/msg` — all via `queueCommand` — and `guild manage` from `/wv distribute`, which `OutboundCommand.queueFront` puts at the **front** of the same queue by reflection (`addFirst`), with `queueCommand` only as its fallback.

## Models

### Models.Guild
File: `common/src/main/java/com/wynntils/models/guild/GuildModel.java`

- `getGuildName(): String` — empty string if not in guild
- `getGuildRank(): GuildRank` — null if not in guild
- `isInGuild(): boolean`
- `getGuildMembers(): Set<String>` — unmodifiable set of usernames
- `getGuildLevel(): int` (-1 if not in guild)
- `getGuild(String inputName): CompletableFuture<GuildInfo>` — async fetch by name/prefix/alias
- `isGuildMember(String username): boolean`
- `requestGuildMembers(): void`
- `getGuildProfile(String name): Optional<GuildProfile>`

**GuildRank enum:** `Recruit, Recruiter, Captain, Strategist, Chief, Owner`

### Models.Container
File: `common/src/main/java/com/wynntils/models/containers/ContainerModel.java`

- `getCurrentContainer(): Container` — the open screen's model, or `null`. Set at `MenuEvent.MenuOpenedEvent.Pre` by title (`Container.matchesTitle`), else at `ScreenInitEvent.Pre` by screen predicate; cleared at screen close. Compare `getContainerId()` with the screen's `menu.containerId` before trusting it for a given screen.
- A container's class is its kind: `AccountBankContainer`, `TradeMarketContainer`, `GuildMemberListContainer`, ... under `models/containers/containers/`. Retro's `container_titles` and `slot_fills` key on the simple class name (`getContainerName()` drops the `Container` suffix; vetsmod uses `getClass().getSimpleName()`).
- Properties: `BoundedContainerProperty.getBounds()` → `ContainerBounds(startRow, startCol, endRow, endCol)` with `getSlots()`, the container slots that hold items (banks `(0, 0, 4, 8)`; the Trade Market's `(0, 0, 5, 6)` is out of date); `FullscreenContainerProperty`, a marker for screens Wynncraft draws full-screen (content book, ability tree and its reset and aspects, character selection, ...). Wynncraft fills such a view's player-inventory part with its own buttons, so menu slots past the chest are not the player's there: the content book's scroll buttons are 65 and 69 (`getPreviousItemSlot`/`getNextItemSlot`), the ability tree's reset and aspects buttons 85 and 86 (`AbilityTreeContainerQueries`, `AspectContainerQueries`). `CharacterInfoContainer` is a plain `Container` (no bounds, not full-screen), a three-row chest: skill-point tomes 4, character 7, mastery tomes 8, ability tree 9, skill points 11-15 (`SkillPointModel`, `CharacterModel`).
- Fixed player slots, by index in the player's `Inventory` (not the menu index): `InventoryUtils.COMPASS_SLOT_NUM` 7, `CONTENT_BOOK_SLOT_NUM` 8, `INGREDIENT_POUCH_SLOT_NUM` 13 (compile-time constants), and `InventoryAccessory.getSlots()` 9-12.

### Models.CharacterStats and Models.CombatXp
Files: `common/src/main/java/com/wynntils/models/characterstats/CharacterStatsModel.java`, `CombatXpModel.java`

Read from the action bar's segments (`ActionBarUpdatedEvent`), so they move with Wynncraft's own HUD:

- `Models.CharacterStats.getHealth()` / `getMana()`: `Optional<CappedValue>` from the health and mana numbers; empty until the first bar is read, and kept, not cleared, on a world change. `getLevel()`: the combat level from `LevelSegment`, left alone while the bar shows a profession's. `getSprint()`: the hexagon's sprint, when the rim shows sprint.
- `Models.CombatXp.getCombatLevel()`: `CappedValue(level, 121)`. `getXp()`: `CappedValue.fromProgress(progress, xp the level needs)`, the progress from `CombatExperienceSegment`; `CappedValue.EMPTY` while the level is 0.
- `CappedValue` (`utils/type/CappedValue.java`): a record `(int current, int max)`; `getProgress()` is `current / max`, but 1.0 when `max` is 0.
- `Models.WorldState.onWorld()`: whether the state is `WORLD`, not a lobby or the character selection.

vetsmod's `RetroHud` draws vanilla's hearts from `getHealth()`, its food from `getMana()`, and its experience bar and level number from `Models.CombatXp`, only on a world ([vetsmod_retro.md](vetsmod_retro.md) §7). All of it is the same at 4.1.4 and 4.1.17.

### Models.Player — ghost detection

`Models.Player.isPlayerGhost(player)` is the canonical "is this player phased to another world" check. It is backed by `PlayerModel.ghosts`, a `Map<UUID, Integer>` Wynntils populates from `_<TIER><N>` team-assignment events and clears on a `WORLD` state change.

It doubles as a one-way presence detector for the local user's `/toggle ghosts` setting: if any visible player is ghost-flagged, ghosts cannot be off, because the server filters them out otherwise. The converse does not hold — no visible ghost means "ambiguous", not "off". `GhostsPromptHandler` uses it exactly that way.

## Init order between vetsmod and Wynntils

In production, Fabric Loader runs client entrypoints in mod-id order, so `VetsmodClient.onInitializeClient()` runs before Wynntils' entrypoint, while `WynntilsMod.eventBus` is still null. (A dev launch shuffles the mod order unless `-Dfabric.debug.disableModShuffle=true` is set; `build.gradle` sets it for Loom's dev runs.) Two things then fail. `WynntilsMod.registerEventListener` throws a `NullPointerException` at once. And a first touch of `Models.Marker`, or any other `Models.X`, runs `Models.<clinit>`, which constructs `ServerListModel`; its constructor schedules a server-list update at once on a pool thread. That thread runs `Managers.<clinit>`, whose `CrowdSourcedDataManager` registers on the null bus and throws, so `Managers` stays unusable and Wynntils' own init then dies with `NoClassDefFoundError`. It is a race, lost when the pool thread reaches `Managers` before Wynntils' entrypoint creates the bus, and it crashed the client on 2026-06-18. (`WynntilsMod.postEvent` catches `Throwable`, so posting events is not the mechanism.)

**Defer any init-time Wynntils bus registration or `Models.X.something(...)` call into `VetsmodClient.onClientStarted()`, the `ClientLifecycleEvents.CLIENT_STARTED` phase**, which runs after every mod's `onInitializeClient`. Per-tick calls are safe: ticks start after `CLIENT_STARTED`. Snapshot listeners subscribed in `onInitializeClient` are safe too, but only because snapshots arrive over the v1 sockets (or from a debug command, typed long after start-up), and the sockets open only at a Wynncraft join ([vetsmod_networking.md](vetsmod_networking.md) §6), which comes after `CLIENT_STARTED`. Moving `V1ApiManager.connect()` back into the initializer body would let a snapshot reach a listener on a WebSocket thread before Wynntils' entrypoint has run again. Saved as the `feedback_vetsmod_wynntils_init_order.md` memory.

## Utility classes

### StyledText
File: `common/src/main/java/com/wynntils/core/text/StyledText.java`

Wynntils rich text type (use instead of `Component`).

Creation: `fromComponent(Component)`, `fromString(String)` (with `§` codes), `fromUnformattedString(String)`, `fromParts(List<StyledTextPart>)`.

Text extraction:
- `getString(): String` — with default formatting codes
- `getStringWithoutFormatting(): String` — plain text
- `getComponent(): MutableComponent`

String operations: `length()`, `contains/startsWith/endsWith(String)`, `matches/find(Pattern)`, `split(String)`, `substring(int, int)`, `replaceAll(Pattern, String)`, `append/prepend(StyledText)`, `trim()`, `isEmpty()`, `isBlank()`, `getMatcher(Pattern)`.

Styling: `withoutFormatting()`, `stripAlignment()`.

### ComponentUtils
File: `common/src/main/java/com/wynntils/utils/mc/ComponentUtils.java`

- `stripDuplicateBlank(List<Component>): List<Component>`
- `getLastPartCodes(StyledText): Style`
- `formattedTextToComponent(FormattedText): Component`

### McUtils
File: `common/src/main/java/com/wynntils/utils/mc/McUtils.java`. A `final` class with no fields and no static
initializer. Each helper vetsmod calls is a one-line pass-through, with no caching and no null check;
`getUserProfileUUID` and `getGameDirectory` alone null-check `mc()`, for Wynntils' tests.

**vetsmod never calls `Minecraft.getInstance()`; it reaches the client through `McUtils`,** except where a Fabric
callback hands it the instance (`DebugKeyHandler`'s `END_CLIENT_TICK` `client`). Use the exact helper where one
exists at Wynntils 4.1.4, the floor `fabric.mod.json` declares, and `McUtils.mc()` for everything else (`execute`,
`font`, `gui`, `level`, and so on). A site that guards the instance against null, passes it on, or also reads a
member with no helper keeps a `McUtils.mc()` local and reads every field from it. Never call `McUtils` from a
static initializer or a static field. `tools/check-doc-counts.sh` pins `Minecraft.getInstance` at zero call sites
in `src/client/java`, outside static declarations, which its counter skips.

The helpers vetsmod calls, unchanged from 4.1.4 through 4.2.16:

- `mc(): Minecraft`
- `player(): LocalPlayer` (`mc().player`)
- `playerName(): String` (`player().getName().getString()`; throws when there is no player)
- `screen(): Screen` / `setScreen(Screen): void`
- `options(): Options`
- `inventory(): Inventory`

Also present at 4.1.4 and not called: `containerMenu`, `inventoryMenu`, `window`, `guiScale`,
`getUserProfileUUID`, `getGameDirectory`, `playSoundUI`, `playSoundMaster`, `playSoundAmbient`,
`sendMessageToClient`, `sendChat`, `sendPacket`, `sendSequencedPacket`, `openChatScreen`, `removeMessageFromChat`,
`sendErrorToClient`.

**Absent at the 4.1.4 floor, so never call them:** `displayToast` (from 4.1.9); `getChatWidth`,
`sendWynntilsPrefixMessage` and `sendWynntilsPillMessage` (from 4.1.10); `getChat` and `toastManager` (from
4.2.12). vetsmod compiles against 4.1.17, so the compiler accepts the first four. `sendWynntilsMessage` exists
through 4.1.9 and is gone after it.

## Event priority system

`EventPriority` (NeoForge): `HIGHEST → HIGH → NORMAL → LOW → LOWEST` (runs in this order).

vetsmod usage:
- `LegacyHighlightEventListener` at `LOWEST` — runs AFTER Wynntils' `ItemHighlightFeature` (HIGH), effectively overriding the stock rarity highlight without canceling it.
- `RetroSlotListener` at `HIGHEST`, `receiveCanceled = true` — draws retro's glass slots before anything else draws on a slot.
- `RetroContainerTitleListener` at `NORMAL` — sets retro's container title, and blanks the inventory label on a screen that keeps Wynncraft's and on a full-screen view.
- `RetroSlotIconListener` at `NORMAL` on `SlotRenderEvent.Post` and `HotbarSlotRenderEvent.Post` — draws a gear box's or tome's type, and the Ability Tree button's glint, over the slot's item.
- `LegacyTooltipEventListener` at `NORMAL` — sets hover context fields before tooltip pipeline.

## Domain enums

- `ClassType` — Warrior, Ranger, Mage, Assassin, Shaman, NONE
- `GuildRank` — Recruit, Recruiter, Captain, Strategist, Chief, Owner
- `ElementType` — Fire, Water, Air, Earth, Thunder, Light, Dark
- `WorldState` — NOT_CONNECTED, CONNECTING, INTERIM, HUB, CHARACTER_SELECTION, WORLD

## Feature/Overlay system and settings

Features extend `Feature`, use `@Persisted @Config` for settings.
Overlays extend `Overlay`, configurable position/size.
Both registered via `Managers.Feature` / `Managers.Overlay`.
Config via `@Persisted`, `@Config<T>`, `@HiddenConfig<T>` annotations, GSON-serialized.

vetsmod reads one feature's state (`GuildLogWalker`: `Managers.Feature.getFeatureInstance(CustomGuildLogScreenFeature.class).isEnabled()`) and, through retro's `WynntilsConfigOverrides`, writes settings. It finds a setting as `/wynntils config set <feature> <setting> <value>` (`ConfigCommand`) does, with the public API:

- `Managers.Feature.getFeatureFromString(shortName)` — the feature whose `getShortName()` (class simple name minus `Feature`, e.g. `ItemHighlight`, `CustomPlayerListOverlay`) equals it; then `feature.getConfigOptionFromString(fieldName)` (e.g. `highlightTexture`, or `userEnabled`, every feature's on/off switch).
- `Config<T>`: `get()`, `tryParseStringValue(String)` (an enum by its JSON name such as `tag`, otherwise the wrapper's `String` constructor; `null` when it cannot), `setValue(T)` (runs the owner's `updateConfigOption`, so `userEnabled` turns the feature on or off; marks it user-edited; **a `null` resets it to the default**), `reset()` (default, and clears `userEdited`, so the setting leaves the file), `userEdited()`.
- An enum setting's text is its JSON name: `EnumUtils.toJsonFormat` (Guava `UPPER_UNDERSCORE` to `LOWER_CAMEL`) writes it and `fromJsonFormat`, behind `tryParseStringValue`, reads it back, so `ShiftBehavior.ENABLED_IF_SHIFT_HELD` is `enabledIfShiftHeld`; an upper-case spelling does not parse.
- `WynntilsContentBookFeature` (short name `WynntilsContentBook`) holds the content book key bind and the Wynntils menu and guide key binds, and `shiftBehaviorConfig` (`ShiftBehavior`: `none`, `enabledIfShiftHeld`, `disabledIfShiftHeld`, the default), which decides when its own content book screen replaces Wynncraft's container (`WrappedScreenOpenEvent`), from whether shift was held at the last arm swing or item use. The same in 4.1.17 and 4.2.16. Retro sets `enabledIfShiftHeld` rather than turning the feature off, which would take the key binds with it.
- `Managers.Config.saveConfig()` — synchronous; writes only user-edited settings.
- Wynntils loads features and settings at the end of the game's first resource reload (`WynntilsMod.onResourcesFinishedLoading`, from `ResourceLoadStateTrackerMixin`), after `CLIENT_STARTED`; until then `Managers.Feature.getFeatures()` is empty.

## Import paths quick reference

```java
import com.wynntils.core.WynntilsMod;
import com.wynntils.core.components.Models;      // Models.Guild, etc.
import com.wynntils.core.components.Handlers;    // Handlers.Command
import com.wynntils.core.text.StyledText;
import com.wynntils.utils.mc.McUtils;
import com.wynntils.utils.mc.ComponentUtils;
import com.wynntils.handlers.chat.event.ChatMessageEvent;
import com.wynntils.handlers.chat.type.RecipientType;
import com.wynntils.models.worlds.event.WorldStateEvent;
import com.wynntils.models.worlds.type.WorldState;
import com.wynntils.models.guild.event.GuildEvent;
import com.wynntils.mc.event.SlotRenderEvent;
import com.wynntils.mc.event.ContainerLabelRenderEvent;
import com.wynntils.mc.event.ItemTooltipRenderEvent;
import com.wynntils.core.persisted.config.Config;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
```
