---
name: Wynntils API Reference
description: Public API surface of Wynntils that vetsmod depends on — events (with field layouts), Handlers.Command, Models.Guild, StyledText, McUtils, event priority system
type: reference
originSessionId: dc63f47a-2d15-4f8d-9b6a-41d3049f0cc2
---
**Repo:** sibling clone at `../Wynntils/` — read-only reference, not edited. Upstream: <https://github.com/Wynntils/Wynntils>.
**Version used by vetsmod:** v4.1.4-fabric (via Modrinth Maven, `modCompileOnly`)
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

vetsmod registers `LegacyHighlightEventListener` at `EventPriority.LOWEST` on `.Pre` — runs AFTER Wynntils' `ItemHighlightFeature` (at HIGH), overriding its rarity highlight.

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

- `queueCommand(String command)` — sends with server rate limit (~350ms / 7 ticks between commands). Do NOT include leading `/`.
- `sendCommandImmediately(String command)` — bypasses queue; for urgent / user-initiated commands.

Used by vetsmod: `/gu stats`, `/gu rank`, `/find`, `/msg` — all via `queueCommand`.

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
File: `common/src/main/java/com/wynntils/utils/mc/McUtils.java`

- `mc(): Minecraft`
- `player(): LocalPlayer`
- `playerName(): String`
- `options(): Options`
- `inventory(): Inventory`
- `containerMenu(): AbstractContainerMenu`
- `screen(): Screen` / `setScreen(Screen): void`
- `window(): Window`
- `guiScale(): double`
- `getUserProfileUUID(): UUID`
- `getGameDirectory(): File`

## Event priority system

`EventPriority` (NeoForge): `HIGHEST → HIGH → NORMAL → LOW → LOWEST` (runs in this order).

vetsmod usage:
- `LegacyHighlightEventListener` at `LOWEST` — runs AFTER Wynntils' `ItemHighlightFeature` (HIGH), effectively overriding the stock rarity highlight without canceling it.
- `LegacyTooltipEventListener` at `NORMAL` — sets hover context fields before tooltip pipeline.

## Domain enums

- `ClassType` — Warrior, Ranger, Mage, Assassin, Shaman, NONE
- `GuildRank` — Recruit, Recruiter, Captain, Strategist, Chief, Owner
- `ElementType` — Fire, Water, Air, Earth, Thunder, Light, Dark
- `WorldState` — NOT_CONNECTED, CONNECTING, INTERIM, HUB, CHARACTER_SELECTION, WORLD

## Feature/Overlay system (not directly used by vetsmod)

Features extend `Feature`, use `@Persisted @Config` for settings.
Overlays extend `Overlay`, configurable position/size.
Both registered via `Managers.Feature` / `Managers.Overlay`.
Config via `@Persisted`, `@Config<T>`, `@HiddenConfig<T>` annotations, GSON-serialized.

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
import com.wynntils.mc.event.ItemTooltipRenderEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
```
