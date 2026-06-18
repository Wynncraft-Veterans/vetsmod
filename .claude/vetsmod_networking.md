---
name: vetsmod Networking (WebSocket + Fetchers + Polling)
description: V1ApiManager dual-WebSocket, WsClient reconnection/ping, on-demand HTTP fetchers, polling services (staff, supporters, guild roster)
type: project
originSessionId: dc63f47a-2d15-4f8d-9b6a-41d3049f0cc2
---
# vetsmod Networking Layer

## 1. V1ApiManager — dual-WebSocket orchestrator

[src/client/java/org/wynnvets/api/V1ApiManager.java:42-199](src/client/java/org/wynnvets/api/V1ApiManager.java#L42-L199)

Manages two `WsClient` instances:
- **Inbound:** `wss://api.wynnvets.org/v1/inbound` — client sends messages
- **Outbound:** `wss://api.wynnvets.org/v1/outbound` — server pushes to all clients

State:
- `inboundClient`, `outboundClient` (volatile `WsClient`)
- `pendingRegistration` (JsonObject cached)
- `outboundListeners` (`CopyOnWriteArrayList<Consumer<JsonObject>>`)

Key methods:
- `connect()` — initialize both; inbound gets reconnect callback that re-sends BOTH the cached `register` frame AND a fresh `auth` frame (using `vetsAuthKey` from `VetsConfig`)
- `disconnect()` — close both cleanly
- `sendRegistration(uuid, username, tier)` — sends `{type:"register", uuid, username, tier}`; cached for auto-retry
- `sendAuth(key)` — sends `{type:"auth", key}`; sets `expectingAuthAck = true` so the inbound message handler routes the next ack to `GuildStateManager.onAuthSuccess`/`onAuthFailure`
- `sendInbound(type, rank, username, message)` — `type` is one of `guild`/`waitlist`/`honourary`
- `sendTabList(entries)` — sends `{type:"tablist", entries:[{server, username},...]}`
- `addOutboundListener(listener)` — register consumer (note: `server_info` frames are intercepted before listeners and routed straight to `SessionAuthWarning.onServerInfo()`)

**Register frame fields:** `type`, `uuid`, `username`, `tier`.
**Auth frame fields:** `type:"auth"`, `key:"<43-char base64url>"`. Server replies `{status:"ok", tier, ws_tier, mc_uuid, mc_username}` or `{status:"error", detail:"auth rejected: <reason>"}`. Auth-success replies are discriminated from chat-success acks by the presence of the `tier` field (resilient to ack reordering).
**Message fields:** `uuid`, `type`, `timestamp`, `rank`, `username`, `message`.
**Server → client unsolicited:** `{type:"server_info", unauth_enabled: bool}` is pushed once on outbound connect so the mod knows which session-warning copy to show.

## 2. WsClient — low-level WebSocket wrapper

[src/client/java/org/wynnvets/api/WsClient.java:32-219](src/client/java/org/wynnvets/api/WsClient.java#L32-L219)

Constants:
- `RECONNECT_DELAY_MS = 3000`
- `PING_INTERVAL_MS = 30_000`

State:
- `wsRef` AtomicReference<WebSocket>
- `closed`, `connecting` atomic flags
- `textBuffer` accumulates multi-frame text messages (WS fragmentation)
- `scheduler` single-threaded executor for reconnect/ping

Listener methods: `onOpen`, `onText` (buffers fragments until last), `onPong`, `onClose`, `onError`.

On close/error → schedule reconnect 3s later. Ping scheduled on every connect via `schedulePing()`.

Silent drop if `send()` called while not connected (no queuing).

## 3. HTTP endpoint constants

[src/client/java/org/wynnvets/api/VetsApi.java](src/client/java/org/wynnvets/api/VetsApi.java):
| Constant | Path |
|----------|------|
| `MOTD` | `/v1/outbound/motd` |
| `GUILD_MOTD` | `/v1/outbound/guild_motd` |
| `RETURN` | `/v1/outbound/return` |
| `STAMP` | `/v1/outbound/stamp` |
| `STAFF` | `/v1/outbound/staff` |
| `SUPPORTERS` | `/v1/outbound/supporters` |
| `LIST` | `/v1/outbound/list` |
| `ROSTER` | `/v1/outbound/roster` |

Also: `GUILD_UUID = "a36bd64c-c053-4727-872d-b0d0729f474a"` (Returners).

[src/client/java/org/wynnvets/api/WynnCraftApi.java](src/client/java/org/wynnvets/api/WynnCraftApi.java):
- `playerInfo(UUID)` → `https://api.wynncraft.com/v3/player/{uuid}`
- `guildInfo(String name)` → `https://api.wynncraft.com/v3/guild/{url-encoded}`

[src/client/java/org/wynnvets/api/MojangApi.java](src/client/java/org/wynnvets/api/MojangApi.java):
- `getUserUUID(name)` → `https://api.mojang.com/users/profiles/minecraft/{name}`

## 4. On-demand fetchers

Package: [src/client/java/org/wynnvets/fetcher/ondemand/](src/client/java/org/wynnvets/fetcher/ondemand/)

| Class | Command | Endpoints | Output |
|-------|---------|-----------|--------|
| `MotdFetcher` | `/wv motd` | `VetsApi.MOTD` or `GUILD_MOTD` | `MutableComponent` |
| `StaffFetcher` | `/wv staff` | `VetsApi.STAFF` | Sorted coloured list |
| `ListFetcher` | `/wv list` | `VetsApi.LIST` + `STAFF` + `SUPPORTERS` + Wynntils | Tier-partitioned list |
| `WorldListFetcher` | `/wv list world` | Above + `/find` batch dispatch | Region-grouped list |
| `ReturnFetcher` | `/wv return` | `VetsApi.RETURN` | Component (JSON-serialized) |
| `StampFetcher` | `/wv anni`, auto | `VetsApi.STAMP` | Countdown or "not announced" |
| `UserInfoFetcher` | `/wv check <name>` | Mojang → WynnCraft → Returners roster | Profile component |
| `OnlineMemberService` | internal | Tab, `/list`, Wynntils | Merged roster |

### StaffFetcher details
Parses JSON array, extracts `{username, rank, world/server}`, filters online, sorts by rank priority (owner=0, chief=1, strategist=2, captain=3), then alpha.

### ListFetcher partitions
1. Guild with VetsMod (UUID in connected set)
2. Guild without VetsMod
3. Honourary (italic light purple)
4. Waitlist (italic dark aqua)

Styling: Staff underlined (via `StaffRanksPoller.confirmedRankFor()`); supporters gradient glint (via `SupportersPoller.isSupporter()`). Hover shows "Click to message X"; click sends `/msg X `.

### WorldListFetcher flow
"Looking up..." → gather players → fetch staff names → dispatch `/find` batch via `FindDispatcher.enqueueFindBatch()` → group by server → group by region (GeoIP2 prefix: EU→Europe, AS→Asia, etc.) → sort by count desc.

### StampFetcher formats
- <1 hour: `"Annihilation is in X mins!"` (red) + "Click here for more info" link
- ≥1 hour: `"Annihilation returns in X hours Y mins!"` (red/dark-red) + reminder + `VetsApi.ANNI` link
- Past: `null` (no display)

### OnlineMemberService merge strategy
[OnlineMemberService.java:157-263](src/client/java/org/wynnvets/fetcher/ondemand/OnlineMemberService.java#L157-L263)
1. Build UUID→username from Wynntils (authoritative)
2. Overlay `GuildRosterCache.getRoster()` (Mojang-resolved, takes precedence over stale API)
3. Supplement with VetsMod usernames for unresolved UUIDs
4. Merged guild online = Wynntils online ∪ VetsMod guild UUIDs ∪ tab list
5. Honourary + waitlist from VetsMod `/list`
6. Tab-only usernames added as guild tier
7. Apply grace-period cache (recently-seen players)

## 5. Polling services

Package: [src/client/java/org/wynnvets/fetcher/polling/](src/client/java/org/wynnvets/fetcher/polling/)

### StaffRanksPoller (2 min)
[StaffRanksPoller.java:56-75](src/client/java/org/wynnvets/fetcher/polling/StaffRanksPoller.java#L56-L75)
- `ConcurrentHashMap<String, String>` (lowercase name → rank)
- Runs every 2 minutes, scheduled initially immediate
- Fetches `VetsApi.STAFF`, replaces entire cache atomically
- Accepts only owner/chief/strategist/captain
- Used by `ListFetcher` for underline styling
- Why polling? No server event stream; cheap + simple

### SupportersPoller (5 min)
[SupportersPoller.java:48-67](src/client/java/org/wynnvets/fetcher/polling/SupportersPoller.java#L48-L67)
- Volatile `Set<String>` lowercase usernames
- Normalization: trim, strip NBSP, strip level tags `<N>`, lowercase
- Nickname mode: split on `/`, check both halves
- Fetches `VetsApi.SUPPORTERS` every 5 min
- Used by `ListFetcher` (gradient glint), `PillFormatter`, `NametagAnimator`, `ServerGuildChatRewriter`

### GuildRosterCache (5 min)
[GuildRosterCache.java:57-76](src/client/java/org/wynnvets/fetcher/polling/GuildRosterCache.java#L57-L76)
- Volatile `Map<String, String>` UUID → current username
- Fetches `VetsApi.ROSTER` (server-side Mojang-resolved)
- Overrides stale Wynncraft API usernames
- Used by `OnlineMemberService.merge()`

## 6. Listeners

[src/client/java/org/wynnvets/listeners/ServerConnectionListener.java:38](src/client/java/org/wynnvets/listeners/ServerConnectionListener.java)
- Fabric `ClientPlayConnectionEvents.JOIN` → connect WebSockets, register outbound handler, reset `newTooltipStylesAvailable`
- Fabric `ClientPlayConnectionEvents.DISCONNECT` → reset guild state, disconnect WebSockets, unregister handler

[src/client/java/org/wynnvets/listeners/WynntilsEventListener.java](src/client/java/org/wynnvets/listeners/WynntilsEventListener.java) — ~565 lines:
- `@SubscribeEvent WorldStateEvent` → on WORLD state, call `GuildStateManager.onEnteredWorld()`
- `@SubscribeEvent GuildEvent.Joined/.Left` → call `GuildStateManager.onGuildInfoUpdated()`
- `@SubscribeEvent ChatMessageEvent.Match` (GUILD type) → guild chat relay:
  1. Extract username + message via `^(.+?):\s+(.+)$`
  2. Strip PUA badge glyphs
  3. Extract real username from hover ("X's real name is Y")
  4. Bridge echo suppression
  5. Resolve rank from hover → pill glyph decode → Wynntils guild model
  6. Client-side dedup: fingerprint `username\0normalizedMsg`, 100-entry deque, 5s TTL
  7. URL repair (merge space-separated URL tokens when continuation looks fragmentary)
  8. `V1ApiManager.sendInbound("guild", rank, username, message)`

[src/client/java/org/wynnvets/listeners/LegacyHighlightEventListener.java](src/client/java/org/wynnvets/listeners/LegacyHighlightEventListener.java):
- `SlotRenderEvent.Pre` at `EventPriority.LOWEST` (runs AFTER Wynntils' `ItemHighlightFeature` at HIGH, overriding it)

[src/client/java/org/wynnvets/listeners/LegacyTooltipEventListener.java](src/client/java/org/wynnvets/listeners/LegacyTooltipEventListener.java):
- `ItemTooltipRenderEvent.Pre` at NORMAL — sets `LegacyItemHandler.currentItemStack` / `currentItemHasFoil` context fields

## 7. Message flow summaries

**Inbound (user types /g → server):**
1. Wynncraft shows guild chat message
2. `WynntilsEventListener.onGuildChat()` parses via regex
3. Dedup + rank resolution + URL repair
4. `V1ApiManager.sendInbound(type, rank, username, message)`

**Outbound (WebSocket → user sees it):**
1. Server pushes JSON via outbound WS
2. `WsClient.handleText()` → `V1ApiManager` → `OutboundDisplayHandler.onOutboundMessage()`
3. UUID dedup (10s TTL), self-suppression (30s TTL), bridge echo suppression (10s TTL)
4. `ChatUtils.sendGuildChatMessage()` formats + displays

## 7.1 MWE/anni frames

Two inbound / two outbound:

| Direction | Type | Sender | Receiver | Purpose |
|---|---|---|---|---|
| Inbound | `anni_query` | `V1ApiManager.sendAnniQuery` | temp-server `_handle_anni_query` | On-demand snapshot pull; ack as `anni_query_response`. |
| Outbound | `anni_query_response` | temp-server | `AnniQueryClient.onResponse` | Synchronous reply to `anni_query`; single-flight FIFO queue. |
| Inbound (S5) | `anni_scrollspot_set` | `V1ApiManager.sendAnniScrollspotSet` | temp-server `_handle_anni_scrollspot_set` | Host writes (or clears) party scroll-spot. **Authenticated only.** Server reads MC UUID from session — never from frame. |
| Outbound (S5) | `anni_scrollspot_response` | temp-server | `AnniScrollspotClient.onResponse` | Ack for `anni_scrollspot_set`. `{status: ok|error, detail}`; FIFO queue. |
| Outbound | `anni_state` | temp-server `anni_snapshot_poller` | `AnniWsHandler.onOutbound` → `AnniSnapshotCache.update` | Server-initiated snapshot push (per-uuid gated on the eligibility set). |

Both response futures (query, scrollspot) live in `org.wynnvets.mwe.anni.network` and time out at 5–8 s. `AnniWsHandler` is the single demux for all four types — its `onInbound`/`onOutbound` branches route to the right consumer.

## 8. Auth

Two layers:

1. **Connection-time headers** — `AuthProvider` interface with `getHeaders()`. Current impl is `NoOpAuthProvider` (empty map). Retained for hypothetical CDN tokens.
2. **Application-level bearer key** — `auth` frame sent after WS connect. The key is a 43-char URL-safe base64 token issued by dazebot's `/vetsmod` Discord command and stored in `vetsAuthKey`. Re-sent automatically by `V1ApiManager`'s `onConnect` callback on every reconnect; also re-sent immediately when the user runs `/unlock <key>` so feedback lands in the same session rather than only after the next reconnect.

The server validates each key by HTTP introspection against dazebot (`POST /api/auth/introspect`, 60s LRU cache, serve-stale-on-error during dazebot outages). The resolved tier (`member`/`waitlist`/`honourary`/`other`) drives a per-connection chat-type gate: see `../temporary-server/v1_protocol.md` §1.8 and §2.5.

## 9. Error handling

- WebSocket errors → `WsClient.onError()` logs, aborts, schedules reconnect
- HTTP errors in fetchers → `CompletableFuture.completeExceptionally()`, caller decides how to display
- No retry on HTTP failures; next polling tick re-attempts
