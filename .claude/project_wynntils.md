---
name: Wynntils API Reference
description: Public API surface of Wynntils that vetsmod depends on — events (with field layouts), Handlers.Command, Models.Guild, StyledText, McUtils, event priority system
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
