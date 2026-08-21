---
name: vetsmod Networking (WebSocket + Fetchers + Polling)
description: V1ApiManager dual-WebSocket, WsClient reconnection/ping, on-demand HTTP fetchers, the six polling services
type: project
originSessionId: dc63f47a-2d15-4f8d-9b6a-41d3049f0cc2
---
# vetsmod Networking Layer

## 1. V1ApiManager — dual-WebSocket orchestrator

[V1ApiManager](../src/client/java/org/wynnvets/api/V1ApiManager.java)

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
- `sendInbound(type, rank, username, message)` — `type` is one of `guild`/`queue`/`waitlist`/`honourary`. `queue` is the one `GuildChatDispatcher` uses while the player is in a world queue and the game server is dropping `/g`
- `sendTabList(entries)` — sends `{type:"tablist", entries:[{server, username},...]}`
- `addOutboundListener(listener)` — register consumer (note: `server_info` frames are intercepted before listeners and routed straight to `SessionAuthWarning.onServerInfo()`)

**Not exhaustive** — `sendQueueStatus`, `sendRankChange`, `sendStaffActionFrame`, `addInboundListener` and `addInboundPostConnectListener` also exist; §7.1 depends on the inbound fan-out. `staff_online` / `staff_offline` are likewise intercepted before the outbound listeners.

**Register frame fields:** `type`, `uuid`, `username`, `tier`.
**Auth frame fields:** `type:"auth"`, `key:"<43-char base64url>"`. Server replies `{status:"ok", tier, ws_tier, mc_uuid, mc_username, is_staff, staff_rank, staff_rank_display}` or `{status:"error", detail:"auth rejected: <reason>"}`. `is_staff` and `staff_rank` are read by `V1ApiManager` into `confirmedStaff` / `confirmedStaffRank`. `staff_rank_display` is the additive 2026-07 label — the server still sends it, and it is `null` exactly when `staff_rank` is, but the client does not read it; display labels come from `RankDisplayMap.displayFor` at each render site. Auth-success replies are discriminated from chat-success acks by the presence of the `tier` field (resilient to ack reordering).
**Message fields:** `uuid`, `type`, `timestamp`, `rank`, `username`, `message`.
**Server → client unsolicited:** `{type:"server_info", unauth_enabled: bool}` is pushed once on outbound connect so the mod knows which session-warning copy to show.

## 2. WsClient — low-level WebSocket wrapper

[WsClient](../src/client/java/org/wynnvets/api/WsClient.java)

Constants:
- `RECONNECT_DELAY_MS = 3000`
- `PING_INTERVAL_MS = 30_000`

State:
- `wsRef` AtomicReference<WebSocket>
- `closed`, `connecting` atomic flags
- `textBuffer` accumulates multi-frame text messages (WS fragmentation)
- `scheduler` single-threaded executor for reconnect/ping
- `httpClient` an **instance** field, and deliberately **not** the shared `HttpClients.standard()` client: it is a WebSocket factory, its 10 s connect timeout is re-declared on the WebSocket builder where it governs the whole handshake, and it pins no HTTP version. One per `WsClient`, so two per `V1ApiManager.connect()`

Listener methods: `onOpen`, `onText` (buffers fragments until last), `onPong`, `onClose`, `onError`.

On close/error → schedule reconnect 3s later. Ping scheduled on every connect via `schedulePing()`.

Silent drop if `send()` called while not connected (no queuing).

## 3. HTTP endpoint constants

[VetsApi](../src/client/java/org/wynnvets/api/VetsApi.java):
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

**Not exhaustive** — `VetsApi` also declares `ALIASES` (`/v1/outbound/aliases`, read by `WynnAliasCache`), `NO_ASPECTS` (`/v1/outbound/no-aspects`, read by `NoAspectsFilter`) and the external `ANNI` link.

[WynnCraftApi](../src/client/java/org/wynnvets/api/WynnCraftApi.java):
- `playerInfo(UUID)` → `https://api.wynncraft.com/v3/player/{uuid}`
- `guildInfo(String name)` → `https://api.wynncraft.com/v3/guild/{url-encoded}`

[MojangApi](../src/client/java/org/wynnvets/api/MojangApi.java):
- `getUserUUID(name)` → `https://api.mojang.com/users/profiles/minecraft/{name}`

### The shared client

[HttpClients](../src/client/java/org/wynnvets/util/HttpClients.java) — one `HttpClient` for the whole mod, built `HTTP_1_1` with a 5 s connect timeout and **no** redirect, proxy, executor, authenticator, cookie-handler or SSL customisation of any kind. Eighteen classes hold it in their own `private static final HttpClient HTTP_CLIENT = HttpClients.standard()` field: `CommandDispatcher`, `InviteGate`, `NameResolver`, `NoAspectsFilter`, `PlayerLookup`, seven of the eight `fetcher/ondemand` fetchers (all but `ListFetcher`, which holds no client and delegates to `OnlineMemberService`), five of the six `fetcher/polling` pollers (all but `AnniSnapshotPoller`, which goes over the WebSocket) and `TerritoryLineManager`. `PlayerLookup` passes its copy down to five of its six lookup providers.

One shared client means one selector thread, one default executor and one connection pool, and the eighteen fields now reuse each other's keep-alive connections instead of each holding private idle ones. Reuse is per host, so what matters is that the pool spans **six**: `api.wynnvets.org` (thirteen of the eighteen), `api.wynncraft.com` (`InviteGate`, `NameResolver`, `UserInfoFetcher`, `TerritoryLineManager`, and `PlayerLookup` via `WynncraftProvider`), and — all five through `PlayerLookup`'s copy — `playerdb.co`, `api.ashcon.app`, `api.minecraftservices.com` and `api.mojang.com`. The saving concentrates on the first two, which is where the repeat traffic is; the four lookup hosts are cascade fallbacks and mostly cold.

Sharing the executor is the part with teeth: every asynchronous continuation off any of those eighteen fields now runs on one executor whose sizing the JDK does not specify, which is why a blocking `.join()` inside such a continuation is worth noticing wherever one appears.

**Never call `close()`, `shutdown()` or `shutdownNow()` on it, and never put it in a try-with-resources.** Java 21 made `HttpClient` `AutoCloseable`; `close()` blocks until in-flight operations finish and then permanently disables the client, taking the HTTP of all eighteen subsystems that share it with it for the rest of the session.

**Two classes deliberately keep their own.** `AnniZone` (see [vetsmod_mwe_anni.md](vetsmod_mwe_anni.md)) and `WsClient` (§2) both use a 10 s connect timeout and pin no HTTP version, so neither can adopt the shared chain without a behaviour change. Their agreement on 10 s is coincidence rather than a shared requirement: one governs a WebSocket handshake, the other a 60 s poller's GET.

[Json](../src/client/java/org/wynnvets/util/Json.java) is the matching shared `Gson` — `new Gson()`, no builder, read by twenty-one classes. The three `GsonBuilder`-configured instances (`VetsConfig`, `ItemDumpHandler`, `AnniDebugCommands`) are not residents and must not become ones; each depends on what it configured.

## 4. On-demand fetchers

Package: [org.wynnvets.fetcher.ondemand](../src/client/java/org/wynnvets/fetcher/ondemand/)

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

Styling: Staff underlined (via `StaffRanksPoller.confirmedRankFor()`); supporters gradient glint (via `SupportersPoller.isSupporter()`). Hover shows "Click to message X"; the click is a `ClickEvent.SuggestCommand` — it pre-fills the chat box with `/msg X ` (trailing space) rather than sending anything.

### WorldListFetcher flow
"Looking up..." → gather players → fetch staff names → dispatch `/find` batch via `FindDispatcher.enqueueFindBatch()` → group by server → group by region (GeoIP2 prefix: EU→Europe, AS→Asia, etc.) → sort by count desc.

### StampFetcher formats
- <1 hour: `"Annihilation is in X mins!"` (red) + "Click here for more info" link
- ≥1 hour: `"Annihilation returns in X hours Y mins!"` (red/dark-red) + reminder + `VetsApi.ANNI` link
- Past: `null` (no display)

### OnlineMemberService merge strategy
[OnlineMemberService.merge()](../src/client/java/org/wynnvets/fetcher/ondemand/OnlineMemberService.java)
1. Build UUID→username from Wynntils (authoritative)
2. Overlay `GuildRosterCache.getRoster()` (Mojang-resolved, takes precedence over stale API)
3. Supplement with VetsMod usernames for unresolved UUIDs
4. Merged guild online = Wynntils online ∪ VetsMod guild UUIDs ∪ tab list
5. Honourary + waitlist from VetsMod `/list`
6. Tab-only usernames added as guild tier
7. Apply grace-period cache (recently-seen players)

## 5. Polling services

Package: [org.wynnvets.fetcher.polling](../src/client/java/org/wynnvets/fetcher/polling/)

Six `scheduleAtFixedRate` pollers live in this package, started back-to-back from `VetsmodClient.onInitializeClient`: `SupportersPoller` 5m, `StaffRanksPoller` 2m, `AnniStampPoller` 5m, `AnniSnapshotPoller` 30s, `GuildRosterCache` 5m, `WynnAliasCache` 5m. Two are named `*Cache` but poll on a fixed schedule like the rest. `AnniSnapshotPoller` is the only gated one — its tick returns early unless an anni stamp is announced and within 90 minutes. The three subsections below cover three of the six.

A seventh scheduled fetcher, `mwe/anni/zone/AnniZone` (60s, Wynncraft world-events API), is started on the line above them but lives outside this package.

### StaffRanksPoller (2 min)
[StaffRanksPoller.start()](../src/client/java/org/wynnvets/fetcher/polling/StaffRanksPoller.java)
- **Two** `ConcurrentHashMap<String, String>` caches, both keyed by lowercase name: `staffRanksByUsername` (replaced wholesale each poll) and `liveStaffRanksByUsername`, a push overlay fed by `staff_online` / `staff_offline` outbound frames. `confirmedRankFor` checks the live map first, so a pushed staff member is never evicted by a stale poll snapshot
- Runs every 2 minutes, scheduled initially immediate
- Fetches `VetsApi.STAFF`, replaces entire cache atomically
- `ALLOWED_RANKS` is strategist/chief/owner only — **captain is rejected**, retired in the 2026-07 permission restructure, and a stray captain is dropped and treated as a non-staff Returner client-side
- Used by `ListFetcher` for underline styling
- Why polling? No server event stream; cheap + simple

**Gap:** the two entry points behind the behaviour described above are unnamed here — `applyLiveStaffEvent(username, rank, online)`, which writes the overlay from `staff_online`/`staff_offline` frames, and `refreshNow()`, which fires an off-schedule fetch on every successful auth ack to close the cold-start gap. See `StaffRanksPoller`.

### SupportersPoller (5 min)
[SupportersPoller.start()](../src/client/java/org/wynnvets/fetcher/polling/SupportersPoller.java)
- Volatile `Set<String>` lowercase usernames
- Normalization: trim, strip NBSP, strip level tags `<N>`, lowercase
- Nickname mode: split on `/`, check both halves
- Fetches `VetsApi.SUPPORTERS` every 5 min
- Used by `ListFetcher` (gradient glint), `PillFormatter`, `NametagAnimator`, `ServerGuildChatRewriter`

### GuildRosterCache (5 min)
[GuildRosterCache.start()](../src/client/java/org/wynnvets/fetcher/polling/GuildRosterCache.java)
- Volatile `Map<String, String>` UUID → current username
- Fetches `VetsApi.ROSTER` (server-side Mojang-resolved)
- Overrides stale Wynncraft API usernames
- Used by `OnlineMemberService.merge()`

## 6. Listeners

[ServerConnectionListener](../src/client/java/org/wynnvets/listeners/ServerConnectionListener.java)
- Fabric `ClientPlayConnectionEvents.JOIN` → connect WebSockets, register the outbound handler. That is all it does.
- Fabric `ClientPlayConnectionEvents.DISCONNECT` → reset guild state, reset `newTooltipStylesAvailable`, disconnect WebSockets, unregister the outbound handler, and clear `OutboundDisplayHandler`'s three dedup/suppression caches

[WynntilsEventListener](../src/client/java/org/wynnvets/listeners/WynntilsEventListener.java):
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

[LegacyHighlightEventListener](../src/client/java/org/wynnvets/listeners/LegacyHighlightEventListener.java):
- `SlotRenderEvent.Pre` at `EventPriority.LOWEST` (runs AFTER Wynntils' `ItemHighlightFeature` at HIGH, overriding it)

[LegacyTooltipEventListener](../src/client/java/org/wynnvets/listeners/LegacyTooltipEventListener.java):
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

| Direction | Type | Sender | Receiver | Purpose |
|---|---|---|---|---|
| Inbound | `anni_query` | `V1ApiManager.sendAnniQuery` | temp-server `_handle_anni_query` | On-demand snapshot pull; ack as `anni_query_response`. |
| Outbound | `anni_query_response` | temp-server | `AnniQueryClient.onResponse` | Synchronous reply to `anni_query`; single-flight FIFO queue. |
| Inbound (S5) | `anni_scrollspot_set` | `V1ApiManager.sendAnniScrollspotSet` | temp-server `_handle_anni_scrollspot_set` | Host writes (or clears) party scroll-spot. **Authenticated only.** Server reads MC UUID from session — never from frame. |
| Outbound (S5) | `anni_scrollspot_response` | temp-server | `AnniScrollspotClient.onResponse` | Ack for `anni_scrollspot_set`. `{status: ok|error, detail}`; FIFO queue. |
| Inbound (S6) | `anni_rsvp` | `V1ApiManager.sendAnniRsvp` | temp-server `_handle_anni_rsvp` | In-game `/wv anni rsvp <hard|soft|revoke>`. **Authenticated only**; MC UUID from session. |
| Outbound (S6) | `anni_rsvp_response` | temp-server | `AnniRsvpClient.onResponse` | Ack for `anni_rsvp`. `{status: ok|error, detail}`; FIFO queue. |
| Inbound (S7) | `anni_party_observation` | `V1ApiManager.sendAnniPartyObservation` | temp-server `_handle_anni_party_observation` | Vetsmod reports its local Wynncraft party roster when an organiser username is in the party. **Authenticated only**; observer UUID stamped from session. Names go over the wire (Wynncraft only exposes party members by username); vets-anni resolves via its roster + alias caches. |
| Outbound (S7) | `anni_party_observation_response` | temp-server | `AnniWsHandler` (debug log only) | Ack for `anni_party_observation`. No client-side single-flight queue — observation is fire-and-forget; debug-logged only. |
| Outbound | `anni_state` | temp-server `anni_snapshot_poller` | `AnniWsHandler.onOutbound` → `AnniSnapshotCache.update` | Server-initiated snapshot push (per-uuid gated on the eligibility set). |

Response futures (query, scrollspot, rsvp) live in `org.wynnvets.mwe.anni.network` and time out at 5–8 s. `AnniWsHandler` is the single demux for all types — its `onInbound`/`onOutbound` branches route to the right consumer.

### S7 — Party back-report gate

`PartyRosterListener` no longer fires the legacy `party_status` frame. Instead, on every Wynntils `PartyEvent` / `WorldStateEvent`, AND on every snapshot update that changes the lowercased `organiser_usernames` set (via `AnniPartyReporter.requestRecapture()`), the listener:

1. Captures `Models.Party.getPartyLeader()` + `getPartyMembers()` on the event thread.
2. Debounces 300 ms (coalesces the `/party list` burst).
3. Gates on `stamp ± 2 h` AND any party member's username appears in `AnniSnapshotCache.latest().organiserUsernames()` (case-insensitive).
4. Fires `V1ApiManager.sendAnniPartyObservation(members, leader, world)` if the gate passes.

vets-anni resolves names → UUIDs server-side and writes
`state.party_leader_by_uuid[member_uuid] = leader_uuid` for the presence
classifier's `ONLINE_WORLD → ONLINE_PARTY` upgrade. Entries are TTL-gated
(60 s) so a vetsmod disconnect mid-window degrades cleanly back to cyan.

## 8. Auth

One layer, an **application-level bearer key** — `auth` frame sent after WS connect. Nothing is attached to the WebSocket upgrade itself: `WsClient.connect()` builds the socket with a connect timeout and no headers. The key is a 43-char URL-safe base64 token issued by dazebot's `/vetsmod` Discord command and stored in `vetsAuthKey`. Re-sent automatically by `V1ApiManager`'s `onConnect` callback on every reconnect; also re-sent immediately when the user runs `/unlock <key>` so feedback lands in the same session rather than only after the next reconnect.

The server validates each key by HTTP introspection against dazebot (`POST /api/auth/introspect`, 60s LRU cache, serve-stale-on-error during dazebot outages). The resolved tier (`member`/`waitlist`/`honourary`/`other`) drives a per-connection chat-type gate: see `../../temporary-server/v1_protocol.md` §1.8 and §2.5.

## 9. Error handling

- WebSocket errors → `WsClient.onError()` logs, aborts, schedules reconnect
- HTTP errors are **absorbed, not propagated**, by two different mechanisms. The on-demand fetchers end their `CompletableFuture` chain in `.exceptionally(e -> …)` returning a fallback whose shape is per-fetcher: a red `Component` from `StaffFetcher`, an unstyled one from `MotdFetcher`/`ReturnFetcher`, `null` from `StampFetcher`, and domain values (`notInGuild()`, `Optional.empty()`, `List.of()`) elsewhere. **Five** of the six pollers use no futures at all — each calls `HttpClient.send(...)` synchronously inside a `try`/`catch` that only logs, so a failed tick leaves the last successful cache in place and the next tick re-attempts. `AnniSnapshotPoller` is the sixth and is not one of them: it goes over the WebSocket via `AnniQueryClient` and builds no `HttpClient` at all. Nor is `polling/` the only home of a synchronous send — `CommandDispatcher.isSelfListedInOnlineStaffFeed`, `CommandDispatcher.fetchOnlineStaffUsernames` and `AnniZone.refresh` are three more. **Eight synchronous call sites, not six.** No fetcher calls `completeExceptionally`; the repo's only use of it is `CommandDispatcher`'s `/find` batch future
- No retry on HTTP failures; next polling tick re-attempts
