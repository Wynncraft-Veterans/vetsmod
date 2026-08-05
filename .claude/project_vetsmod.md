---
name: vetsmod Architecture
description: Fabric client mod structure, key packages, Wynntils integration, WebSocket protocol, config system
type: project
originSessionId: 879c1502-cda3-4f6b-836d-36b1515ba02c
---
**Stack:** Fabric Loader 0.18.4, Fabric API 0.141.3+1.21.11, Java 21, Wynntils v4.1.4-fabric (modCompileOnly)

**Entry points:**
- Server-side: `org.wynnvets.Vetsmod` (minimal, just logs)
- Client-side: `org.wynnvets.VetsmodClient` — initializes everything

**Key packages and responsibilities:**
- `org.wynnvets.api` — `V1ApiManager` (dual WebSocket), `WsClient`, `VetsApi`, `WynnCraftApi`, `MojangApi`
- `org.wynnvets.chat` — `OutboundDisplayHandler`, `ChatLogger`, `ChatUtils`, `PillFormatter`
- `org.wynnvets.chat.dispatcher` — `CommandDispatcher`, `MessageFanoutDispatcher`, `FindDispatcher`
- `org.wynnvets.chat.rewriter` — chat message transformers: spoiler, staff guild alert, staff channel, server guild, encourage-update
- `org.wynnvets.chat.spoiler` — `SpoilerCodec`, `SpoilerFormatter` (PUA encoding)
- `org.wynnvets.commands` — `CommandRegistry`, `/wv` command tree
- `org.wynnvets.config` — `VetsConfig` (JSON-backed) at `vetsmod/storage/config.json`
- `org.wynnvets.datamodels` — Lightweight DTOs: `Guild`, `User`, `UserUUID` (used by fetchers/list services to thread typed records around)
- `org.wynnvets.guild` — `GuildStateManager` (facade), `GuildChecker` (`/gu stats`), `StaffRankChecker` (`/gu rank`), `UnlockManager` (bearer-key auth + legacy markers), `SessionAuthWarning` (per-session unauth nag)
- `org.wynnvets.items` — `ItemDefinitions` (YAML regex patterns), `LegacyItemHandler`, renderers
- `org.wynnvets.queue` — `QueueStateManager`, `QueueDetector`, `QueueStateListener`. Tracks Wynncraft world-queue state from the queue title + world-state signals; consumers (`OutboundDisplayHandler`, `GuildChatDispatcher`) take the queue-aware path that routes guild chat through the WS as `type:"queue"` since the in-game `/g` is dropped while queued.
- `org.wynnvets.fetcher.ondemand` — HTTP fetchers: MOTD, staff, list, return, stamp, world list, user info
- `org.wynnvets.fetcher.polling` — `StaffRanksPoller` (2min), `SupportersPoller` (5min), `GuildRosterCache`
- `org.wynnvets.listeners` — Wynntils event subscriptions (WorldStateEvent, GuildEvent, ChatMessageEvent)
- `org.wynnvets.mixin.client` — 11 mixins: chat (3), legacy items (3), commands (2), plus `NametagMixin`, `CommandSuggestionsMixin`, `QueueTitleMixin`. Authoritative list lives in `src/client/resources/vetsmod.client.mixins.json`.
- `org.wynnvets.rendering` — `TerritoryLineRenderer`, `NametagAnimator`, gradient text
- `org.wynnvets.logging` (under `src/main/`, shared with server stub) — `VetsLogger` thin wrapper used everywhere else

**Networking:**
- Inbound WS: `wss://api.wynnvets.org/v1/inbound` — client sends guild/waitlist/honourary messages
- Outbound WS: `wss://api.wynnvets.org/v1/outbound` — server pushes messages to all clients
- Both auto-reconnect (3s delay), 30s ping keepalive
- Registration frame (`type="register"`) AND `auth` frame (`type="auth", key="<43-char base64url>"`) re-sent on every reconnect
- Outbound server pushes a `{type:"server_info", unauth_enabled: bool}` frame on connect so the mod knows which session-warning copy to use

**WebSocket message fields:** `uuid`, `type`, `timestamp`, `rank`, `username`, `message`. Inbound `type` ∈ `{guild, queue, waitlist, honourary}`. Outbound `type` adds `bridge` (Discord relay).

**Wynntils integration points:**
- `WynntilsMod.registerEventListener()` + `@SubscribeEvent`
- `Models.Guild.getGuildName()`, `Models.Guild.isInGuild()`
- `Handlers.Command` for rate-limited command queueing (`/gu stats`, `/gu rank`, `/find`, `/msg`)
- `ChatMessageEvent.Match` / `ChatMessageEvent.Edit` for chat interception
- `WorldStateEvent` for world join trigger
- `StyledText`, `ComponentUtils`, `McUtils`

**Guild detection:** Hybrid — Wynntils `Models.Guild` first, falls back to `/gu stats` parser. Cached 3 days. Retries 3x with 2s intervals on world join.

**User tiers:** `member` (`guild` on the wire) | `waitlist` | `honourary` | `other`. Tier is resolved server-side by dazebot from the user's Discord roles + linked MC account; the mod gets it back in the `auth` frame ack. Authentication is via a 43-char URL-safe base64 bearer key issued by dazebot's `/vetsmod` Discord command and supplied by the user via `/unlock <key>`. The legacy SHA-256 password unlock has been retired (markers retained on disk only for warning copy). Staff detected via `/gu rank` (captain+), cached 24h, orthogonal to tier.

**Config:** JSON at `~/.minecraft/vetsmod/storage/config.json`. User-facing keys via `/wv config`. Debug logging opt-in, 3-day TTL.

**Item definitions:** YAML at `src/client/resources/definitions.yml`. Regex categories: `definitions`, `no_lore_legacy`, `misc_definitions`, `unenchanted`, `notjunk`, `new_format_override`, `enchant_excluded_items`.

**No unit tests** (typical for Fabric mods due to tight Minecraft coupling).

**Unicode PUA reservation:** vetsmod has reserved BMP PUA range **U+F600–U+F850** for its own purposes. We should not use BMP PUA outside of this range since it may conflict with other Wynn projects. Major current usage is our `SpoilerCodec`. Wynncraft/Wynntils use separate PUA ranges (including supplementary plane > U+10000).
