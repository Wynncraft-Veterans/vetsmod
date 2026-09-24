---
name: vetsmod Networking (WebSocket + Fetchers + Polling)
description: V1ApiManager dual-WebSocket, WsClient reconnection/ping, on-demand HTTP fetchers, the polling services and their shared lifecycle
type: project
originSessionId: dc63f47a-2d15-4f8d-9b6a-41d3049f0cc2
---
# vetsmod Networking Layer

## 1. V1ApiManager — dual-WebSocket orchestrator

[V1ApiManager](../src/client/java/org/wynnvets/api/V1ApiManager.java)

Manages two `WsClient` instances:
- **Inbound:** `wss://api.wynnvets.org/v1/inbound` — client sends messages; the server's replies to them (acks and the typed anni responses) come back on it
- **Outbound:** `wss://api.wynnvets.org/v1/outbound` — server pushes: relayed chat to every connected client (to an unauthenticated one only while `unauth` is on); `staff_online`/`staff_offline`, `anni_state` and the targeted `warning` frame only to a socket that has sent its own `auth` frame

⚠️ **vetsmod never sends that `auth` frame on the outbound socket.** `connect()` installs the auth-sending `onConnect` on `inboundClient` only, and temporary-server keeps its authenticated session per socket (read at temporary-server `ffd8c17`). So the authenticated-only pushes never arrive, and relayed chat arrives without the server's tier filter while `unauth` is on (and not at all with it off). Filed as `outbound-socket-never-authenticated`. The sections below describe what those pushes are for.

State:
- `inboundClient`, `outboundClient` (volatile `WsClient`)
- `pendingRegistration` (JsonObject cached)
- `outboundListeners` (`CopyOnWriteArrayList<Consumer<JsonObject>>`)

Key methods:
- `connect()` — initialize both; inbound gets reconnect callback that re-sends BOTH the cached `register` frame AND a fresh `auth` frame (using `vetsAuthKey` from `VetsConfig`)
- `disconnect()` — close both cleanly
- `sendRegistration(uuid, username, tier)` — sends `{type:"register", uuid, username, tier}`; cached for auto-retry
- `sendAuth(key)` — sends `{type:"auth", key}` and sets `expectingAuthAck`. An `ok` ack carrying `tier` reaches `GuildStateManager.onAuthSuccess` whether or not the flag is set; while it is set, an `error` ack goes to `onAuthFailure` instead of a pending staff-action callback or the warning log (a staff-action-shaped ack, such as a `would_trigger` preflight, still goes to its callback) (see `V1ApiManager.connect`)
- `sendInbound(type, rank, username, message)` — `type` is one of `guild`/`queue`/`waitlist`/`honourary`. `queue` is the one `GuildChatDispatcher` uses while the player is in a world queue and the game server is dropping `/g`
- `sendTabList(entries)` — sends `{type:"tablist", entries:[{server, username},...]}`
- `addOutboundListener(listener)` — register consumer (note: `server_info` frames are intercepted before listeners and routed straight to `SessionAuthWarning.onServerInfo()`)

**Not exhaustive** — `sendQueueStatus`, `sendRankChange`, `sendStaffActionFrame`, `addInboundListener` and `addInboundPostConnectListener` also exist; §7.1 depends on the inbound fan-out. `staff_online` / `staff_offline` are likewise intercepted before the outbound listeners.

**Register frame fields:** `type`, `uuid`, `username`, `tier`.
**Auth frame fields:** `type:"auth"`, `key:"<43-char base64url>"`. Server replies `{status:"ok", tier, ws_tier, mc_uuid, mc_username, is_staff, staff_rank, staff_rank_display}` or `{status:"error", detail:"auth rejected: <reason>"}`. `is_staff` and `staff_rank` are read by `V1ApiManager` into `confirmedStaff` / `confirmedStaffRank`. `staff_rank_display` is the additive 2026-07 label — the server still sends it, and it is `null` exactly when `staff_rank` is, but the client does not read it; display labels come from `RankDisplayMap.displayFor` at each render site. Auth-success replies are told apart from chat-success acks by the presence of the `tier` field, so a success is recognised whether or not `expectingAuthAck` is set.
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

Silent drop if `send()` called while not connected (no queuing), and also while another text send on the socket is still pending (bug `ws-client-send-ignores-send-pending-failure`).

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

**Not exhaustive** — `VetsApi` also declares `ALIASES` (`/v1/outbound/aliases`, read by `PolledJsonMap.WYNN_ALIASES`), `NO_ASPECTS` (`/v1/outbound/no-aspects`, read by `NoAspectsFilter`) and the external `ANNI` link.

[WynnCraftApi](../src/client/java/org/wynnvets/api/WynnCraftApi.java):
- `playerInfo(UUID)` → `https://api.wynncraft.com/v3/player/{uuid}`
- `guildInfo(String name)` → `https://api.wynncraft.com/v3/guild/{url-encoded}`

[MojangApi](../src/client/java/org/wynnvets/api/MojangApi.java):
- `getUserUUID(name)` → `https://api.mojang.com/users/profiles/minecraft/{name}`

### The shared client

[HttpClients](../src/client/java/org/wynnvets/util/HttpClients.java) — one `HttpClient` for the whole mod, built `HTTP_1_1` with a 5 s connect timeout and **no** redirect, proxy, executor, authenticator, cookie-handler or SSL customisation of any kind. Seventeen classes hold it in their own `private static final HttpClient HTTP_CLIENT = HttpClients.standard()` field: `CommandDispatcher`, `InviteGate`, `NameResolver`, `NoAspectsFilter`, `PlayerLookup`, seven of the eight `fetcher/ondemand` fetchers (all but `ListFetcher`, which holds no client and delegates to `OnlineMemberService`), four of the six `fetcher/polling` classes (all but `AnniSnapshotPoller`, which goes over the WebSocket, and `PollingService`, which is transport-free by design) and `TerritoryLineManager`. `PlayerLookup` passes its copy down to five of its six lookup providers.

One shared client means one selector thread, one default executor and one connection pool, and the seventeen fields now reuse each other's keep-alive connections instead of each holding private idle ones. Reuse is per host, so what matters is that the pool spans **six**: `api.wynnvets.org` (twelve of the seventeen), `api.wynncraft.com` (`InviteGate`, `NameResolver`, `UserInfoFetcher`, `TerritoryLineManager`, and `PlayerLookup` via `WynncraftProvider`), and — all five through `PlayerLookup`'s copy — `playerdb.co`, `api.ashcon.app`, `api.minecraftservices.com` and `api.mojang.com`. The saving concentrates on the first two, which is where the repeat traffic is; the four lookup hosts are cascade fallbacks and mostly cold.

Sharing the executor reaches less far than it sounds. In the JDK 21 implementation a `sendAsync` future completes on `CompletableFuture`'s default async pool, not on this client's executor, so continuations attached to it run there (or on the attaching thread, if the response is already in). A blocking `.join()` inside such a continuation therefore holds a thread of whatever completed its inputs — that pool, or the thread that delivers the inbound socket's frames when one input comes from `VetsSnapshotProvider` — and is worth noticing wherever one appears.

**Never call `close()`, `shutdown()` or `shutdownNow()` on it, and never put it in a try-with-resources.** Java 21 made `HttpClient` `AutoCloseable`; `close()` refuses new requests at once and then blocks until in-flight operations finish, and the client stays shut, taking the HTTP of all seventeen subsystems that share it with it for the rest of the session.

**Two classes deliberately keep their own.** `AnniZone` (see [vetsmod_mwe_anni.md](vetsmod_mwe_anni.md)) and `WsClient` (§2) both use a 10 s connect timeout and pin no HTTP version, so neither can adopt the shared chain without a behaviour change. Their agreement on 10 s is coincidence rather than a shared requirement: one governs a WebSocket handshake, the other a 60 s poller's GET.

[Json](../src/client/java/org/wynnvets/util/Json.java) is the matching shared `Gson` — `new Gson()`, no builder, read by twenty classes. The three `GsonBuilder`-configured instances (`VetsConfig`, `ItemDumpHandler`, `AnniDebugCommands`) are not residents and must not become ones; each depends on what it configured. **The accessors below do not weaken that**: they read an already-parsed `JsonObject` and touch no `Gson` at all.

**`Json` also owns the mod's JSON field accessors** — four entry points over one shape, added by 5g. Twenty-three classes reference `Json` in total; the twenty above are the ones that want `GSON`.

```java
String optString(JsonObject obj, String key, String fallback)
String stringOrNull(JsonObject obj, String key)   // → optString(obj, key, null)
String stringOrEmpty(JsonObject obj, String key)  // → optString(obj, key, "")
int    optInt(JsonObject obj, String key, int fallback)
```

Three names for one string function is deliberate. The fallback is not an axis — `null` and `""` *are* fallbacks — and the two conveniences exist so that the call sites which take no fallback do not have to grow an argument — **28 of 50** at `1592f7d` (`stringOrNull` 17, `stringOrEmpty` 11), up from 23 of 44 when 5g landed. `Json`'s own Javadoc carries the per-method figures and is the copy to trust.

**The policy, on all three axes: fallback.** A missing key or a JSON null is normal and falls back *silently*. A wrong-typed value or a `null` receiver falls back *and warns* (`VetsLogger.warn`, naming the key and the exception class). The warn is unconditional — no suppression cache, because that would put mutable state in the class and the condition it would throttle previously destroyed whole payloads. Ceiling: `OnlineMemberService`'s roster loop reads three fields per member, so a wholly malformed roster costs three warns per member per poll.

Six classes hand-rolled this read before — seven methods over the 44 call sites that existed **then**, a pre-5g figure, not a current one — and they answered those three questions **four different ways**. What each one gave up by adopting the shared policy is the interesting half, because in every case the old answer destroyed *more*:

| Former site | Old wrong-type answer | What that cost |
|---|---|---|
| `OnlineMemberService.stringOrEmpty` ×3 | threw | `parseConnectedUsers`' own `catch` logged at **debug** and returned `List.of()` — the **entire** connected-user list gone, invisibly unless `/wv debug` was on |
| `WorldListFetcher` via that same method ×1 | threw | `parseStaffUsernames`' `catch` returned `Set.of()` — `/wv world` marked **nobody** as staff |
| `NameResolver.legacyNameOf` / `uuidOf` ×5 | threw | `forEachGuildMember`'s `catch` logged at debug and abandoned the walk **mid-iteration** — a *silently truncated* member list, worse than an empty one |
| `OutboundDisplayHandler.getStringOrEmpty` ×7, and `WarningRewriter`'s 3 `optString` + 1 `optInt` beneath it | threw | `V1ApiManager`'s outbound fan-out `catch` logged a generic WARN — the chat line or warning banner **never rendered** |
| `CautionCommands.optString` ×14 | threw | reached `BlockableEventLoop.doRunTask` via `Minecraft.execute` → FATAL-marker ERROR, then swallowed — the `/caution` readout **aborted mid-render**, header already on screen |
| `CommandDispatcher.stringOrNull` ×5, `StaffFetcher.stringOrNull` ×7 | swallowed to null | nothing; these two were already tolerant, and only gained the warn |
| `CautionCommands.optInt` ×4 | fallback | nothing — it was the model the other six converged onto, and gained only the warn and a narrowed `catch` |

So the change was not "loud failure → silent fallback". It was *most of a payload destroyed, half of it invisibly* → *one field defaulted, always named*. Note the mirror: had the shared body **thrown** instead (the 4-of-6 majority), `stringOrNull`'s 12 sites would have flipped from silent-skip to throw, and `StaffFetcher.parseOnlineStaff` sits inside a `catch` that prints "Error parsing staff data: …" in red — one junk `world` field would turn a working `/wv staff` into an error screen.

**Gson caveats the policy does not cover**, all of them pre-existing and all pinned in `JsonTest`:

- A **singleton array is unwrapped, not rejected** — Gson delegates `getAsString`/`getAsInt` on a one-element array to that element, so `["x"]` arrives as `"x"` and never reaches the fallback. A two-element array does throw, and does fall back. No accessor in the repo ever rejected the shape.
- **Primitives coerce**: `42` → `"42"`, `true` → `"true"`. Wrongly-but-primitively typed fields are accepted silently, because to Gson they are not wrongly typed.
- `optInt` is lenient three more ways: numeric strings parse, decimals truncate toward zero (`3.9` → `3`), and an out-of-range literal **narrows** rather than failing (`4294967298` → `2`). None fires the fallback or the warn.

**31 inlined `isJsonNull()` reads elsewhere in the client have not adopted these**, and that is a boundary rather than a gap — most of them throw on a wrong type today, so each conversion is its own behaviour change and belongs in its own commit. The scope 5g took was the seven declarations plus three named inlines (`NameResolver`'s `legacyName` and `uuid`, `WarningRewriter`'s `points_after`).

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
2. Overlay `PolledJsonMap.GUILD_ROSTER.snapshot()` (Mojang-resolved, takes precedence over stale API)
3. Supplement with VetsMod usernames for unresolved UUIDs
4. Merged guild online = Wynntils online ∪ VetsMod guild UUIDs ∪ tab list
5. Honourary + waitlist from VetsMod `/list`
6. Tab-only usernames added as guild tier
7. Apply grace-period cache (recently-seen players)

## 5. Polling services

Package: [org.wynnvets.fetcher.polling](../src/client/java/org/wynnvets/fetcher/polling/)

Six fixed-rate schedules live in this package across five classes, all started back-to-back from `VetsmodClient.onInitializeClient`: `SupportersPoller` 5m, `StaffRanksPoller` 2m, `AnniStampPoller` 5m, `AnniSnapshotPoller` 30s, and `PolledJsonMap`'s two instances — `GUILD_ROSTER` 5m and `WYNN_ALIASES` 5m. `AnniSnapshotPoller` is the only gated one — its tick returns early unless an anni stamp is announced and within 90 minutes. Four of the six have a subsection below; `AnniStampPoller` and `AnniSnapshotPoller` do not.

**Initial delay, which no doc stated in full before** — `PollingService`'s own `@param` defines it, the `StaffRanksPoller` bullet below records "scheduled initially immediate" for one of the six, and `PollingServiceTest` pins both directions. What was missing is the column. `PollingService`'s constructor is `(threadName, task, initialDelay, period, unit)`, and the third argument is not the second:

| Schedule | Period | Initial delay |
|---|---|---|
| `SupportersPoller` | 5 min | `0` |
| `StaffRanksPoller` | 2 min | `0` |
| `AnniStampPoller` | 5 min | `0` |
| `PolledJsonMap.GUILD_ROSTER` | 5 min | `0` |
| `PolledJsonMap.WYNN_ALIASES` | 5 min | `0` |
| **`AnniSnapshotPoller`** | 30 s | **30 s — a full period** |
| `AnniZone` (outside the package) | 60 s | `0` |

`AnniSnapshotPoller` passes `POLL_INTERVAL_SECONDS` in *both* the `initialDelay` and `period` slots, so **it does nothing for the first 30 seconds of a session**. That is the only asymmetry in the family, and it is deliberate only insofar as nothing has ever written it down — re-derive from the constructor call, not from this table.

Another scheduled fetcher, `mwe/anni/zone/AnniZone` (60s, Wynncraft world-events API), is started on the line above them but lives outside this package and hand-rolls its own scheduler.

### PollingService — the shared lifecycle

[PollingService](../src/client/java/org/wynnvets/fetcher/polling/PollingService.java) is the one place a poller's lifecycle is defined: a named daemon thread, an idempotence guard, a `scheduleAtFixedRate` call, and a `stop()` that drains before it cancels. Every schedule above goes through it. It takes the `Runnable` as-is and adds no exception handling or logging of its own — each caller keeps its own `try`/`catch` and its own message — and it uses the thread name it is given verbatim, including the two that disagree with the class passing them (`VetsMod-StaffRanksFetcher`, `VetsMod-SupportersFetcher`) and the two that now name instances rather than classes (`VetsMod-GuildRosterCache`, `VetsMod-WynnAliasCache`). Those names are thread-dump identity; renaming one is a diagnostics change, not tidying.

`start()` returns a `boolean` saying whether this call started the schedule. That is what lets `AnniSnapshotPoller` keep its start-log line inside the idempotence guard without `PollingService` knowing anything about logging.

**Nothing stops any of them.** Every poller is started at mod init and never stopped: there is no `CLIENT_STOPPING` registration, no shutdown hook, and no call to `PollingService.stop()` outside its own test. The poller threads are daemons, so the JVM does not wait on them and the omission has never been visible. `PollingService.stop()` exists so that a teardown has somewhere to hook if the mod ever grows one, and it re-asserts the caller's interrupt rather than swallowing it. `AnniZone`, which schedules outside this package, has never had a `stop()` at all.

### PolledJsonMap (2 instances, 5 min each)

[PolledJsonMap](../src/client/java/org/wynnvets/fetcher/polling/PolledJsonMap.java) polls one flat `string → string` JSON endpoint and publishes it as an immutable snapshot behind a volatile field. Two instances:

- `GUILD_ROSTER` — `VetsApi.ROSTER` (server-side Mojang-resolved), UUID → current username. Overrides stale Wynncraft API usernames. Used by `OnlineMemberService.merge()` via `snapshot()`. Keys are stored exactly as the server spells them.
- `WYNN_ALIASES` — `VetsApi.ALIASES`, stale tab-list username → UUID. Used by `OnlineMemberService.merge()` via `get()` to resolve tab entries no current username matches. Keys are folded to `Locale.ROOT`.

The key normalizer is one field applied at **both** ingest and lookup. That is the invariant the class exists to hold: applying it on one side only breaks alias resolution with no failed fetch and no log line. `PolledJsonMapTest` pins it for both instances, along with the cold-cache contract — empty map from `snapshot()`, `null` from `get()` — which `merge()` depends on and has no branch for.

### StaffRanksPoller (2 min)
[StaffRanksPoller.start()](../src/client/java/org/wynnvets/fetcher/polling/StaffRanksPoller.java)
- **Two** `ConcurrentHashMap<String, String>` caches, both keyed by lowercase name: `staffRanksByUsername` (replaced wholesale each poll) and `liveStaffRanksByUsername`, a push overlay fed by `staff_online` / `staff_offline` outbound frames. `confirmedRankFor` checks the live map first, so a pushed staff member is never evicted by a stale poll snapshot. **The overlay is empty today**: those frames never reach vetsmod (§1)
- Runs every 2 minutes, scheduled initially immediate
- Fetches `VetsApi.STAFF` (per temp-server, the staff currently online) and replaces the entire poll cache each time. The swap is `clear()` then `putAll()`, which is not atomic (bug `staff-ranks-poll-swap-not-atomic`)
- `ALLOWED_RANKS` is strategist/chief/owner only — **captain is rejected**, retired in the 2026-07 permission restructure, and a stray captain is dropped and treated as a non-staff Returner client-side
- Read (`confirmedRankFor`) by `EncourageUpdateRewriter`, `StaffChannelMessageRewriter`, `StaffGuildAlertRewriter`, `GuildChatDispatcher` and `ListFetcher` (underline styling). Written by `V1ApiManager` through `applyLiveStaffEvent(username, rank, online)` (from `staff_online` / `staff_offline` frames) and `refreshNow()` (an off-schedule fetch on every successful auth ack). Started by `VetsmodClient`
- Why poll as well as push? The push carries only changes; the poll supplies the full online-staff set at start and resyncs it every 2 min. Today the poll is the only source that works, because the push never reaches vetsmod (§1)

### SupportersPoller (5 min)
[SupportersPoller.start()](../src/client/java/org/wynnvets/fetcher/polling/SupportersPoller.java)
- Volatile `Set<String>` lowercase usernames
- Normalization: trim, strip NBSP, strip level tags `<N>`, lowercase
- Nickname mode: split on `/`, check both halves
- Fetches `VetsApi.SUPPORTERS` every 5 min
- Read by `ChatUtils`, `ServerGuildChatRewriter`, `ListFetcher` (gradient glint), `NametagMixin` and `DiagnosticsHandler` (started by `VetsmodClient`). ⚠️ **Not** `PillFormatter` or `NametagAnimator` — both take a `boolean isSupporter` from their caller and never touch the poller; `NametagAnimator`'s own Javadoc says so

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
3. A `warning` frame goes to `WarningRewriter` and skips the rest (none arrives today: bug `outbound-socket-never-authenticated`, §1). Any other frame: display gates, UUID dedup (10s TTL), self-suppression (30s TTL); Returners' `guild` frames drop unless queued, and `bridge` frames are recorded (10s TTL) for `WynntilsEventListener`'s echo check, which runs there, not in this flow
4. A staff `‼` alert on a `guild` or `queue` frame goes to `StaffGuildAlertRewriter`; otherwise `ChatUtils.sendHonouraryChatMessage()` (honourary-unlocked viewer) or `sendGuildChatMessage()` formats + displays

## 7.1 MWE/anni frames

| Direction | Type | Sender | Receiver | Purpose |
|---|---|---|---|---|
| Inbound | `anni_query` | `V1ApiManager.sendAnniQuery` | temp-server `_handle_anni_query` | On-demand snapshot pull; ack as `anni_query_response`. |
| Inbound | `anni_query_response` | temp-server | `AnniQueryClient.onResponse` | Reply to `anni_query`, on the socket that carried it; FIFO queue of per-call futures (nothing coalesces). |
| Inbound (S5) | `anni_scrollspot_set` | `V1ApiManager.sendAnniScrollspotSet` | temp-server `_handle_anni_scrollspot_set` | Host writes (or clears) party scroll-spot. **Authenticated only.** Server reads MC UUID from session — never from frame. |
| Inbound (S5) | `anni_scrollspot_response` | temp-server | `AnniScrollspotClient.onResponse` | Ack for `anni_scrollspot_set`. `{status: ok|error, detail}`; FIFO queue. |
| Inbound (S6) | `anni_rsvp` | `V1ApiManager.sendAnniRsvp` | temp-server `_handle_anni_rsvp` | In-game `/wv anni rsvp <hard|soft|revoke>`. **Authenticated only**; MC UUID from session. |
| Inbound (S6) | `anni_rsvp_response` | temp-server | `AnniRsvpClient.onResponse` | Ack for `anni_rsvp`. `{status: ok|error, detail}`; FIFO queue. |
| Inbound (S7) | `anni_party_observation` | `V1ApiManager.sendAnniPartyObservation` | temp-server `_handle_anni_party_observation` | Vetsmod reports its local Wynncraft party roster when an organiser username is in the party. **Authenticated only**; observer UUID stamped from session. Names go over the wire (Wynncraft only exposes party members by username); vets-anni resolves via its roster + alias caches. |
| Inbound (S7) | `anni_party_observation_response` | temp-server | `AnniWsHandler` (debug log only) | Ack for `anni_party_observation`. No client-side queue — observation is fire-and-forget; debug-logged only. |
| Outbound | `anni_state` | temp-server `anni_snapshot_poller` | `AnniWsHandler.onOutbound` → `AnniSnapshotCache.update` | Server-initiated snapshot push (per-uuid gated on the eligibility set). Sent only to an authenticated outbound socket, so **never received today** (§1). |

Response futures (query, scrollspot, rsvp) live in `org.wynnvets.mwe.anni.network` and time out at 5–8 s. `AnniWsHandler` is the single demux for all types — its `onInbound`/`onOutbound` branches route to the right consumer.

### S7 — Party back-report gate

`PartyRosterListener` no longer fires the legacy `party_status` frame. Instead, on every Wynntils `PartyEvent` / `WorldStateEvent`, AND on every snapshot update that changes the lowercased `organiser_usernames` set (`AnniPartyReporter` observes the change and calls `PartyRosterListener.requestRecapture()`, which is the listener's own method — the reporter is the trigger, not the owner), the listener:

1. Captures `Models.Party.getPartyLeader()` + `getPartyMembers()` on the event thread.
2. Debounces 300 ms (coalesces the `/party list` burst).
3. Gates on `stamp ± 2 h` AND any party member's username appears in `AnniSnapshotCache.latest().organiserUsernames()` (case-insensitive).
4. Fires `V1ApiManager.sendAnniPartyObservation(members, leader, world)` if the gate passes.

vets-anni resolves names → UUIDs server-side and writes
`state.party_leader_by_uuid[member_uuid] = leader_uuid` for the presence
classifier's `ONLINE_WORLD → ONLINE_PARTY` upgrade. Entries are TTL-gated
(60 s) so a vetsmod disconnect mid-window degrades cleanly back to cyan.

## 8. Auth

One layer, an **application-level bearer key** — `auth` frame sent after inbound WS connect. Nothing is attached to the WebSocket upgrade itself: `WsClient.connect()` builds the socket with a connect timeout and no headers. The key is a 43-char URL-safe base64 token issued by dazebot's `/vetsmod` Discord command and stored in `vetsAuthKey`. Re-sent automatically by the inbound client's `onConnect` callback (set in `V1ApiManager.connect`) on every inbound reconnect; also re-sent immediately when the user runs `/unlock <key>` so feedback lands in the same session rather than only after the next reconnect.

The server validates each key by HTTP introspection against dazebot (`POST /api/auth/introspect`, 60s LRU cache, serve-stale-on-error during dazebot outages). The resolved tier (`member`/`waitlist`/`honourary`/`other`) drives a per-connection chat-type gate: see `../../temporary-server/v1_protocol.md` §1.8 and §2.5.

## 9. Error handling

- WebSocket errors → `WsClient.onError()` logs, aborts, schedules reconnect
- HTTP errors are **absorbed, not propagated**, by two different mechanisms. The on-demand fetchers end their `CompletableFuture` chain in `.exceptionally(e -> …)` returning a fallback whose shape is per-fetcher: a red `Component` from `StaffFetcher`, an unstyled one from `MotdFetcher`/`ReturnFetcher`, `null` from `StampFetcher`, and domain values (`notInGuild()`, `Optional.empty()`, `List.of()`) elsewhere. **Five** of the six polling schedules use no futures at all — each calls `HttpClient.send(...)` synchronously inside a `try`/`catch` that only logs, so a failed tick leaves the last successful cache in place and the next tick re-attempts. They are four source lines, not five, because `PolledJsonMap`'s two instances share one `fetch()`. `AnniSnapshotPoller` is the sixth schedule and is not one of them: it goes over the WebSocket via `AnniQueryClient` and builds no `HttpClient` at all. Nor is `polling/` the only home of a synchronous send — `CommandDispatcher.isSelfListedInOnlineStaffFeed`, `CommandDispatcher.fetchOnlineStaffUsernames` and `AnniZone.refresh` are three more. **Seven synchronous call sites in all.** Every one of them catches `Exception`, not `Throwable`; for the scheduled ones an escaping `Error` cancels that schedule for the session with nothing logged — see [`poll-task-error-permanently-cancels-the-schedule`](ephemeral/bugs-found-via-mellow-rain/poll-task-error-permanently-cancels-the-schedule.md). No fetcher calls `completeExceptionally`; the repo's only use of it is `CommandDispatcher`'s `/find` batch future
- No retry on HTTP failures; next polling tick re-attempts
