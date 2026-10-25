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

**The outbound socket authenticates separately, and only for a member tier.** temporary-server keeps its authenticated session per socket, so `sendOutboundAuthIfReady()` sends the stored key on the outbound socket once two things hold: the outbound `server_info` carried `chat_tiers: "all"` (`outboundAuthOffered`), and the latest inbound auth ack confirmed a member tier, `member`, `waitlist` or `honourary` (`inboundMemberTier`, set by `InboundAckRouter` through `onInboundAuthOk` / `onInboundAuthFailed`). It runs on every `server_info` hello, on every ok inbound auth ack (so after the connect-time auth, `/unlock` and the mid-session Returners re-auth), and from the outbound socket's on-connect callback: the server sends the hello right after accepting, so it usually reaches the listener before the socket is installed, when the hello's own send finds no socket. Both flags are reset by `connect()` and `disconnect()`, and each inbound (re)connect clears `inboundMemberTier` before it re-sends the key, so after a server restart the outbound socket waits for the fresh ack. A lost outbound socket (`onOutboundSocketLost`) clears `outboundAuthOffered` and `outboundAuthenticated`, since the server's session and the hello's offer die with it, so a reconnect authenticates once, from whichever of its hello and its install comes second; the request-id and `unregister` capabilities are kept, since they gate inbound behaviour.

- **Why the offer gate:** a server with `chat_tiers: "all"` gives every member tier every chat type (temporary-server `v1_protocol.md` §2.4). An older server still accepts outbound auth but filters per tier (guild got no waitlist or honourary lines and vice versa), so authenticating against it would hide chat an unauthenticated socket receives.
- **Why the member-tier gate:** dazebot resolves `other` for a keyholder with no member role (hiatus, registered-only, or a new joiner before the role loop). An authenticated `other` socket receives no chat at all, so such a player stays unauthenticated here, exactly like a player with no key: chat while `unauth` is on, none with it off. A tier lost mid-session (an ok inbound ack with a non-member tier, or a non-transient inbound auth failure) ends an existing outbound session: `endOutboundSession()` sends `{type:"auth", key:""}`, which temporary-server refuses and treats as a logout (§1.8), so the socket falls back to the unauthenticated feed and stops counting as a staff session. A transient inbound failure leaves it, since it says nothing about the key.
- **The ack** is an untyped `{"status":...}` frame on the outbound socket. `handleOutboundFrame` routes it to `OutboundAckRouter`, which records whether the socket now holds a session with a chat channel (`outboundAuthenticated`), logs (a warning on rejection, a debug line for the answer to a deliberate logout; an info line when an ok ack carries a null `ws_tier`), and retries once, 30 s later, after a transient introspection failure, fenced by a generation reset on every connect and disconnect. On an ok ack it also resets the staff presence overlay and refetches the staff list (`StaffRanksPoller.onOutboundAuthenticated`, which calls `refreshNow`), since the server replays no presence to a new socket (§5). It never touches `SessionAuthState`, the unlock tier, the staff-chat eligibility cache or the MWE auto-enable: those stay inbound-ack effects.

State:
- `inboundClient`, `outboundClient` (volatile `WsClient`, both package-private for the tests)
- `outboundAuthOffered`, `inboundMemberTier`, `outboundAuthenticated` (volatile booleans, the outbound auth gate and session above)
- `pendingAcks` ([PendingAcks](../src/client/java/org/wynnvets/api/PendingAcks.java), the staff-action callbacks keyed by request id) and `helloEchoesReq` / `ackEchoedReq` (volatile booleans: whether the server echoes request ids), below
- `pendingRegistration` (JsonObject cached; cleared by `clearRegistration` and by `sendUnregistration`)
- `serverSupportsUnregister` (boolean; the outbound `server_info` hello's `unregister` key, reset by `connect()` and `disconnect()`)
- `outboundListeners` (`CopyOnWriteArrayList<Consumer<JsonObject>>`)
- the auth-ack reply state (`expectingAuthAck`, `confirmedStaff`, `confirmedStaffRank`) lives in [SessionAuthState](../src/client/java/org/wynnvets/api/SessionAuthState.java)

Key methods:
- `connect()` — initialize both, called only on a Wynncraft join (§6); inbound gets reconnect callback that re-sends BOTH the cached `register` frame AND a fresh `auth` frame (using `vetsAuthKey` from `VetsConfig`)
- `disconnect()` — close both for good, and answer every staff-action callback still pending with `Connection closed before reply.` (a pending relay gets the outcome-unknown ack instead, which it keeps quiet about, since its line was echoed and may have been delivered)
- `sendRegistration(uuid, username, tier)` — sends `{type:"register", uuid, username, tier, exact_text:true}`; cached for auto-retry. `exact_text` tells temporary-server this client sends exact chat text, so its wrapped-URL guess is skipped for the socket
- `sendUnregistration(reason)` — retracts the presence registration for a player who no longer qualifies: clears the cache first (so a reconnect does not re-send it), then sends `{type:"unregister"}` only if the inbound socket is up and `serverSupportsUnregister` is set. An older server answers an unknown frame type with a `Missing required fields` error that carries no request id and could be taken for a staff callback's reply or an auth failure, so against one the frame is deliberately not sent and the entry goes at the next reconnect. Debug-logs `Retracted presence registration (tier=…, reason=…, sent=…)`. `registeredTier()` returns the cached tier or `null`. Callers: `GuildStateManager.reconcileRegistration` ([vetsmod_guild_system.md](vetsmod_guild_system.md) §1)
- `sendAuth(key)` — called by `UnlockManager.tryUnlock` and, after a mid-session Returners join, `UnlockManager.reauth` ([vetsmod_guild_system.md](vetsmod_guild_system.md) §4). While the inbound socket is connected, sends `{type:"auth", key}` and sets `expectingAuthAck`; otherwise it returns without sending, and the `connect()` callback sends the stored key on the next (re)connect. An `ok` ack carrying `tier` reaches `GuildStateManager.onAuthSuccess` whether or not the flag is set; while it is set, a non-`ok` ack that carries no request id goes to `onAuthFailure` instead of a pending staff-action callback or the warning log (a staff-action-shaped ack, such as a `would_trigger` preflight, never goes there: without a request id it goes to the eldest callback, or is dropped when none is pending, and if the client believed the server echoes ids such an ack proves it does not; an ack carrying `req` goes to the callback registered under it, or is dropped when there is none, see below) (see `InboundAckRouter.route`)
- `sendInbound(type, rank, username, message)` — `type` is one of `guild`/`queue`/`waitlist`/`honourary`. `queue` is the one `GuildChatDispatcher` uses while the player is in a world queue and the game server is dropping `/g`. It drops the frame with a debug log while the inbound socket is down, so `GuildChatDispatcher`'s three relays check `isInboundConnected()` first and refuse in red instead of echoing. The queue relay also refuses in red while the guild rank is unknown, since temporary-server rejects a `queue` frame with an empty rank
- `sendInbound(type, rank, username, message, onAck)` — the relays' overload. Against a server that echoes request ids it sends the frame with a `req` and registers `onAck` in `pendingAcks` as a relay (15 s deadline), so `onAck` gets the server's ack or an outcome-unknown ack; against one that does not, it sends no `req` and `onAck` hears only a frame that could not be written (`Connection closed before reply.`). A socket that is down answers `Inbound WebSocket not connected.` at once. `GuildChatDispatcher.onRelayAck` turns a refusal into a red "Not delivered (<reason>)" line after the echo and drops the queued self-message; it stays quiet on ok and on an outcome-unknown ack. The 4-argument form, used by `onGuildChat`'s guild forwards, never carries `req`
- `sendTabList(entries)` — sends `{type:"tablist", entries:[{server, username},...]}`
- `addOutboundListener(listener)` — register consumer (note: `handleOutboundFrame` intercepts the untyped auth ack for `OutboundAckRouter` and the `server_info` hello, whose `unauth_enabled` goes to `SessionAuthWarning.onServerInfo()` and whose `chat_tiers` sets `outboundAuthOffered`, before the listeners)

**Every sender reads the socket field once.** `inboundClient` is a static that `disconnect()` on another thread can null between a check and a send, so each sender goes through `connectedInbound()`, which reads it once and returns the client only if it is connected, and then uses that local; `connect()` installs the on-connect callback against its own local client. `sendStaffActionFrame` also checks, after sending, that the field still holds the client it used: if not, a `disconnect()` drained the pending callbacks before its own was registered, so it takes it back by its request id (`pendingAcks.complete(req)`) and answers it with the drain's `Connection closed before reply.`; if the drain got it first, `complete` returns nothing and the callback has already been answered. A frame whose write fails is taken back and answered the same way. Each callback is answered once: every path removes the entry from `pendingAcks` first, and only the path that removed it calls it (`V1ApiManager.answer`).

**Staff-action acks are matched by request id.** Every staff-action frame carries `req`, eight random characters from `[A-Za-z0-9]` that `V1Frames.withReq` adds as the last key, and its callback waits in `pendingAcks` under that id. temporary-server (from `e93fda9`, protocol §1.2 and §1.3) echoes `req` as the last key of every reply to a frame that carried one, and its outbound `server_info` advertises `req_echo: true`. `InboundAckRouter` hands an ack carrying `req` to that id's callback, and drops it when there is none (answered already, or a frame sent without a callback, which carries an id for exactly this reason), so an error ack to some other frame, such as a guild line refused while inbound processing is disabled, can no longer answer a staff command. Against a server that does not echo (`serverEchoesReq()` false: no `req_echo` in the latest hello and no echoed ack on the current inbound socket), the router pairs staff-action acks with callbacks eldest first, as the protocol did before ids; a callback is registered and its frame handed to the socket under one lock, so that order is the wire order. `helloEchoesReq` is set from each hello; `ackEchoedReq` by any echoed ack and cleared by each inbound (re)connect; `connect()` and `disconnect()` clear both. An id-less staff-action ack while either is set proves the server does not echo (an echoing server puts `req` on every staff-action reply, and every staff-action frame carries one), as after a rollback to an older server before its hello arrives: the router clears both and pairs that ack eldest first. Staff-action frames always carry `req`. The waitlist, queue and honourary relays carry it only while `serverEchoesReq()`, since an older server forwards a chat frame's unknown keys to every outbound client; their entries are of kind relay, which the eldest-first pairing never answers. A relay's error ack that is an auth failure (`auth rejected…`, `Authentication required…`) also runs the router's auth-failure handling, as it did before relays had callbacks; a staff-action callback keeps such an error to itself. Guild forwards, register, auth, tablist, queue_status, rank_change and the anni frames never carry `req`.

**No staff-action callback waits forever.** Each entry in `pendingAcks` records the inbound socket it was sent on (`WsClient.generation()`, read at send time) and its kind: a read (`caution_check`, `check_membership`) or a commit (`caution_add`, `warn_add`, `eject_add`).
- **A lost socket.** The inbound `WsClient` reports a socket that closed or failed while current, or was replaced by a newer one, through its loss hook (§2), and `onInboundSocketLost(generation)` answers every callback sent on that socket or an older one with `{status:"error", detail:"Connection lost before reply.", outcome_unknown:"connection lost"}`: no ack to it can arrive on another socket. This needs no echo support. It also clears `ackEchoedReq` (the next socket may reach another server; `helloEchoesReq` waits for the next hello) and `expectingAuthAck`, whose ack cannot come either.
- **A silent server.** Each callback gets a deadline on a shared daemon scheduler, 15 s for a read or a relay and 45 s for a commit (a `caution_add` preflight and commit are two dazebot calls, behind whatever the server is still answering on that socket). `expire(req)` answers a callback still waiting with `{status:"error", detail:"No reply from the vets server.", outcome_unknown:"no reply"}`, and an ack that arrives later finds nothing and is dropped. The deadline is enforced for a callback registered while `serverEchoesReq()` held (each entry records it), and for any other callback if it holds when the deadline runs (checked once): without ids, the late ack would be paired with the next caller's callback. Recording it per entry means a callback sent in echo mode is still answered if the flags drop before its deadline, as after a server rollback.
- **What callers see.** `outcome_unknown` marks an ack where the server may have acted. `CautionCommands` prints its unknown-outcome line for a commit ([vetsmod_commands.md](vetsmod_commands.md) §2) instead of `[kind] failed: …`; the snapshot readers (`VetsSnapshotProvider.requestSnapshot`, and through it `/wv check` and the invite gate) complete empty, so `/wv check` renders "(snapshot unavailable)" and the invite gate its "Could not get enough info" line instead of waiting. `disconnect()` and a frame that was never written keep `Connection closed before reply.`, since there the server never acted or the player has left.

**Also public:** `clearRegistration`, `registeredTier`, `sendQueueStatus`, `sendRankChange`, `sendStaffActionFrame`, `isInboundConnected`, `sendInboundIfConnected` (checks and sends with one read of the socket, returning whether it was up; not for register, auth or staff-action frames), `isConfirmedStaff`, `confirmedStaffRank`, `addInboundListener`, `addInboundPostConnectListener` and `removeOutboundListener`, plus the `TabListEntry` record that `sendTabList` takes. The class's `public static` methods are the list to trust, since it changes: `grep -n 'public static' src/client/java/org/wynnvets/api/V1ApiManager.java`. §7.1's senders use `sendInboundIfConnected`, and its client-side receivers the inbound fan-out, except `anni_state`'s, an outbound listener (`AnniWsHandler.onOutbound`). `staff_online` / `staff_offline` are likewise intercepted before the outbound listeners.

**Register frame fields:** `type`, `uuid`, `username`, `tier`, `exact_text` (always `true`: this client joins wrapped URLs itself, so temporary-server from `1d257b5` skips its own wrapped-URL repair for the socket).
**Auth frame fields:** `type:"auth"`, `key:"<43-char base64url>"`. Server replies `{status:"ok", tier, ws_tier, mc_uuid, mc_username, is_staff, staff_rank, staff_rank_display}` or `{status:"error", detail:"auth rejected: <reason>"}`. `is_staff` and `staff_rank` are read by `InboundAckRouter` into `SessionAuthState`'s `confirmedStaff` / `confirmedStaffRank`, which `isConfirmedStaff()` / `confirmedStaffRank()` return. `staff_rank_display` is the additive 2026-07 label — the server still sends it, and it is `null` exactly when `staff_rank` is, but the client does not read it; display labels come from `RankDisplayMap.displayFor` at each render site. Auth-success replies are told apart from chat-success acks by the presence of the `tier` field, so a success is recognised whether or not `expectingAuthAck` is set.
**Message fields:** `uuid`, `type`, `timestamp`, `rank`, `username`, `message`.
**Server → client unsolicited:** `{type:"server_info", unauth_enabled: bool, chat_tiers: "all", req_echo: true, unregister: true}` is pushed once on outbound connect: `unauth_enabled` picks the session-warning copy, `chat_tiers` (absent on servers older than temporary-server `eacb4da`) gates the outbound auth, `req_echo` (absent before `e93fda9`) says the server echoes request ids, and `unregister` (absent on servers that predate the frame) is what lets `sendUnregistration` send the `unregister` frame.

### V1Frames — the frame builders

[V1Frames](../src/client/java/org/wynnvets/api/V1Frames.java) builds most of the inbound frames `V1ApiManager` sends, one static method each: `register`, `auth` (also used by the on-connect re-send), `queueStatus`, `inbound` (chat), `rankChange`, `tabList`, `staffAction` and `unregister` (`{"type":"unregister"}`), plus `withReq`, which adds a request id to a frame already built. Each returns a new `JsonObject` with its keys in wire order and does nothing else; the send methods and the on-connect callback keep the connection checks, the registration cache, `expectingAuthAck`, the staff-action enqueue and the logging. `inbound` and `rankChange` stamp a random `uuid` and the current time. The four anni frames are built in [AnniWsSender](../src/client/java/org/wynnvets/mwe/anni/network/AnniWsSender.java)'s send methods (§7.1), which hand them to `V1ApiManager.sendInboundIfConnected`, and the two synthetic error acks (`sendStaffActionFrame`'s not-connected ack and `disconnect()`'s drained ack) where they are handed to the callback.

### InboundAckRouter — the status-ack routing

[InboundAckRouter](../src/client/java/org/wynnvets/api/InboundAckRouter.java) routes every inbound frame that carries no `type`. `V1ApiManager`'s inbound handler fans typed frames out to the inbound listeners first and hands it everything else, so a typed frame never reaches it (every typed frame but `anni_query_response` also carries `status`). `route` ignores a frame with no `status`, hands an ack that echoes a request id to that id's callback in `V1ApiManager.pendingAcks` (dropping it when there is none), and, only against a server that does not echo ids, answers the eldest pending callback with a staff-action-shaped ack or an error ack while a callback is pending and no auth ack is awaited (a staff-action-shaped ack with nothing to answer is dropped; an id-less one while the client believes the server echoes proves it does not, clears both echo flags and is paired the same way), handles the auth-ok and auth-failure acks (writing `SessionAuthState` and `vetsAuthTier`/`vetsAuthVerifiedAt`; calling `StaffRanksPoller.refreshNow` on every ok one, `DispatchCoordinator.resetStaffChatEligibilityCache` when the ack ends confirmed staff, and `GuildStateManager.onAuthSuccess`/`onAuthFailure`), and logs any other ack whose `status` is not `ok`.

## 2. WsClient — low-level WebSocket wrapper

[WsClient](../src/client/java/org/wynnvets/api/WsClient.java)

Constants:
- `RECONNECT_DELAY_MS = 3000`
- `PING_INTERVAL_MS = 30_000`

State:
- `wsRef` AtomicReference<WebSocket>
- `generation` the current socket's number (1 for the first, one more per socket), read with `wsRef` under one lock; `generation()` returns it
- `onLostCallback` the loss hook (`setOnLostCallback(LongConsumer)`)
- `socketSender` the current socket and its `SerializedSender`, made the first time a send meets a new socket
- `closed`, `connecting`, `pingScheduled` atomic flags
- `textBuffer` accumulates multi-frame text messages (WS fragmentation)
- `scheduler` single-threaded executor for reconnect/ping
- `HTTP_CLIENT` a **static** field that every `WsClient` shares, and deliberately **not** the shared `HttpClients.standard()` client: it is a WebSocket factory, its 10 s connect timeout is re-declared on the WebSocket builder where it governs the whole handshake, and it pins no HTTP version. One for the session rather than one per `WsClient`, which ended the per-join accumulation of clients that `close()` never released, without touching `close()`. Never close or shut it down

Listener methods: `onOpen`, `onText` (buffers fragments until last), `onPong`, `onClose`, `onError`.

When the current socket closes or fails it is uninstalled, the loss hook runs with its generation, and a reconnect is scheduled 3 s later. A socket that a newer one replaced is aborted and reported lost too. A stale socket's late close or error (one already replaced, or taken by `close()`) changes nothing; it used to schedule a second connection, whose install aborted the healthy socket. `close()` never runs the hook (`V1ApiManager.disconnect()` drains instead). The success branch of a handshake is `installSocket(ws)`, package-private as the test seam. The JDK can deliver a socket's close or error before the handshake's future completes, when it is not current yet and so schedules nothing; `uninstall` remembers it, and `installSocket` then neither installs it nor runs the on-connect callback, but aborts it and schedules the reconnect (so does a socket whose input already reports closed). Frames such a socket delivered first, such as the outbound hello, were still handled, so when no other socket is current the loss hook runs too. `schedulePing()` starts the ping task once, on the first successful connect; it reads the live socket on each run, so a reconnect adds no task. A handshake that completes after `close()`, including one that finds the scheduler already shut down, has its socket uninstalled and aborted at once, without the ping or the on-connect callback.

**Sends are serialised per socket.** The JDK `WebSocket` allows one outstanding `sendText`, and vetsmod sends from several threads (the render thread, the party-roster scheduler, the socket's own threads), so `sendAsync(json)` queues each frame on the socket's [SerializedSender](../src/client/java/org/wynnvets/api/SerializedSender.java): a write starts only once the previous one has completed, succeeded or failed, so frames go out in submit order. The returned `CompletableFuture<Boolean>` completes `true` once the frame was handed to the socket (not a delivery receipt) and `false` when there is no socket, the write failed, or 64 writes are already queued (`MAX_IN_FLIGHT`, a warning, rather than a backlog behind a stalled socket). `send(json)` is `sendAsync` with the result ignored. A sender belongs to one socket, so writes still queued for a dead socket fail instead of replaying on its replacement. While not connected, a send is dropped (no queuing).

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
| `ALIASES` | `/v1/outbound/aliases`, read by `PolledJsonMap.WYNN_ALIASES` |
| `NO_ASPECTS` | `/v1/outbound/no-aspects`, read by `NoAspectsFilter` |

Also: `GUILD_UUID = "a36bd64c-c053-4727-872d-b0d0729f474a"` (Returners).

`ANNI` is the one external link: `https://wynnvets.org/anni`, the anni schedule page.

[WynnCraftApi](../src/client/java/org/wynnvets/api/WynnCraftApi.java):
- `playerInfo(UUID)` → `https://api.wynncraft.com/v3/player/{uuid}`
- `playerInfo(String nameOrUuid)` → the same endpoint by name (used by `PlayerLookup`'s Wynncraft provider)
- `guildInfo(String name)` → `https://api.wynncraft.com/v3/guild/{url-encoded}`

[MojangApi](../src/client/java/org/wynnvets/api/MojangApi.java):
- `getUserUUID(name)` → `https://api.mojang.com/users/profiles/minecraft/{name}`, the name URL-encoded

### The shared client

[HttpClients](../src/client/java/org/wynnvets/util/HttpClients.java) — one `HttpClient` for the whole mod, built `HTTP_1_1` with a 5 s connect timeout and **no** redirect, proxy, executor, authenticator, cookie-handler or SSL customisation of any kind. These classes hold it in their own `private static final HttpClient HTTP_CLIENT = HttpClients.standard()` field: `DispatchCoordinator`, `InviteGate`, `NameResolver`, `NoAspectsFilter`, `PlayerLookup`, every `fetcher/ondemand` class except those that make no request of their own (`ListFetcher`, which delegates to `OnlineMemberService`; `OnlineMemberService`'s two in-memory helpers, `TabListGuildParser` and `OnlineGuildCache`; `TimeLabels`, which only formats dates and ages; and `CheckReportRenderer`, which only renders), four of the six `fetcher/polling` classes (all but `AnniSnapshotPoller`, which goes over the WebSocket, and `PollingService`, which is transport-free by design) and `TerritoryLineManager`. `PlayerLookup` passes its copy down to five of its six lookup providers.

One shared client means one selector thread, one default executor and one connection pool, and the fields now reuse each other's keep-alive connections instead of each holding private idle ones. Reuse is per host, so what matters is that the pool spans **six**: `api.wynnvets.org` (every holder not named next), `api.wynncraft.com` (`InviteGate`, `NameResolver`, `UserInfoFetcher`, `ReturnersRosterProbe`, `TerritoryLineManager`, and `PlayerLookup` via `WynncraftProvider`), and — all five through `PlayerLookup`'s copy — `playerdb.co`, `api.ashcon.app`, `api.minecraftservices.com` and `api.mojang.com`. The saving concentrates on the first two, which is where the repeat traffic is; the four lookup hosts are cascade fallbacks and mostly cold.

Sharing the executor reaches less far than it sounds. In the JDK 21 implementation a `sendAsync` future completes on `CompletableFuture`'s default async pool, not on this client's executor, so continuations attached to it run there (or on the attaching thread, if the response is already in). A blocking `.join()` inside such a continuation therefore holds a thread of whatever completed its inputs — that pool, or the thread that delivers the inbound socket's frames when one input comes from `VetsSnapshotProvider` — and is worth noticing wherever one appears.

**Never call `close()`, `shutdown()` or `shutdownNow()` on it, and never put it in a try-with-resources.** Java 21 made `HttpClient` `AutoCloseable`; `close()` refuses new requests at once and then blocks until in-flight operations finish, and the client stays shut, taking the HTTP of every subsystem that shares it with it for the rest of the session.

**Two classes deliberately keep their own.** `AnniZone` (see [vetsmod_mwe_anni.md](vetsmod_mwe_anni.md)) and `WsClient` (§2) both use a 10 s connect timeout and pin no HTTP version, so neither can adopt the shared chain without a behaviour change. Their agreement on 10 s is coincidence rather than a shared requirement: one governs a WebSocket handshake, the other a 60 s poller's GET.

[Json](../src/client/java/org/wynnvets/util/Json.java) is the matching shared `Gson` — `new Gson()`, no builder. The three `GsonBuilder`-configured instances (`VetsConfig`, `ItemDumpHandler`, `AnniSnapshotDebugCommands`) are not residents and must not become ones; each depends on what it configured. **The accessors below do not weaken that**: they read an already-parsed `JsonObject` and touch no `Gson` at all.

**`Json` also owns the mod's JSON field accessors** — four entry points over one shape, added to replace the hand-rolled reads described below. Some classes reference `Json` only for these accessors, not for `GSON`.

```java
String optString(JsonObject obj, String key, String fallback)
String stringOrNull(JsonObject obj, String key)   // → optString(obj, key, null)
String stringOrEmpty(JsonObject obj, String key)  // → optString(obj, key, "")
int    optInt(JsonObject obj, String key, int fallback)
```

Three names for one string function is deliberate. The fallback is not an axis — `null` and `""` *are* fallbacks — and the two conveniences exist so that the call sites which take no fallback do not have to grow an argument — **36 of 67** (`stringOrNull` 22, `stringOrEmpty` 14), up from 23 of 44 when the accessors landed. `Json`'s own Javadoc carries the per-method figures and is the copy to trust.

**The policy, on all three axes: fallback.** A missing key or a JSON null is normal and falls back *silently*. A wrong-typed value or a `null` receiver falls back *and warns* (`VetsLogger.warn`, naming the key and the exception class). The warn is unconditional — no suppression cache, because that would put mutable state in the class, to throttle a condition that previously destroyed a whole payload, or everything after the failing read, wherever it threw. Ceiling: `OnlineMemberService.parseConnectedUsers` reads three fields per connected user, so a wholly malformed `/list` payload costs three warns per entry per `/wv list` or `/wv list world` call.

Six classes hand-rolled this read before — seven methods over the 44 call sites that existed **then**, a pre-accessor figure, not a current one — and they answered those three questions **four different ways**. What each one gave up by adopting the shared policy is the interesting half, because in every case the old answer destroyed *more*:

| Former site | Old wrong-type answer | What that cost |
|---|---|---|
| `OnlineMemberService.stringOrEmpty` ×3 | threw | `parseConnectedUsers`' own `catch` logged at **debug** and returned `List.of()` — the **entire** connected-user list gone, invisibly unless `/wv debug` was on |
| `WorldListFetcher` via that same method ×1 | threw | `parseStaffUsernames`' `catch` returned `Set.of()` — `/wv list world` marked **nobody** as staff |
| `NameResolver.legacyNameOf` / `uuidOf` ×5 | threw | `forEachGuildMember`'s `catch` logged at debug and abandoned the walk **mid-iteration** — a *silently truncated* member list, worse than an empty one |
| `OutboundDisplayHandler.getStringOrEmpty` ×7, and `WarningRewriter`'s (now `WarningFrameRenderer`) 3 `optString` + 1 `optInt` beneath it | threw | `V1ApiManager`'s outbound fan-out `catch` logged a generic WARN — the chat line or warning banner **never rendered** |
| `CautionCommands.optString` ×14 | threw | reached `BlockableEventLoop.doRunTask` via `Minecraft.execute` → FATAL-marker ERROR, then swallowed — the `/caution` readout **aborted mid-render**, header already on screen |
| `CommandDispatcher.stringOrNull` (now `DispatchCoordinator`) ×5, `StaffFetcher.stringOrNull` ×7 | swallowed to null | nothing; these two were already tolerant, and only gained the warn |
| `CautionCommands.optInt` ×4 | fallback | nothing — it was the model the other six converged onto, and gained only the warn and a narrowed `catch` |

So the change was not "loud failure → silent fallback". It was *most of a payload destroyed, half of it invisibly* → *one field defaulted, always named*. Note the mirror: had the shared body **thrown** instead (the 4-of-6 majority), `stringOrNull`'s 12 sites would have flipped from silent-skip to throw, and `StaffFetcher.parseOnlineStaff` sits inside a `catch` that prints "Error parsing steward data: …" in red — one junk `world` field would turn a working `/wv staff` into an error screen.

**Gson caveats the policy does not cover**, all of them pre-existing and all pinned in `JsonTest`:

- A **singleton array is unwrapped, not rejected** — Gson delegates `getAsString`/`getAsInt` on a one-element array to that element, so `["x"]` arrives as `"x"` and never reaches the fallback. A two-element array does throw, and does fall back. No accessor in the repo ever rejected the shape.
- **Primitives coerce**: `42` → `"42"`, `true` → `"true"`. Wrongly-but-primitively typed fields are accepted silently, because to Gson they are not wrongly typed.
- `optInt` is lenient three more ways: integer strings parse, decimals truncate toward zero (`3.9` → `3`), and an out-of-range literal **narrows** rather than failing (`4294967298` → `2`). None fires the fallback or the warn.

**30 inlined `isJsonNull()` reads elsewhere in the client have not adopted these**, and that is a boundary rather than a gap — most of them throw on a wrong type today, so each conversion is its own behaviour change and belongs in its own commit. The tolerant rewrite's scope was the seven declarations, plus three more reads (`NameResolver`'s `legacyName` and `uuid`, through two private helpers of its own, and `WarningRewriter`'s inline `points_after`).

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
| `UserInfoFetcher` | `/wv check <name>` | `PlayerLookup` cascade; WynnCraft `/v3/player`; `check_membership` over the WebSocket | What `CheckReportRenderer` renders |
| `OnlineMemberService` | internal | Tab, `/list`, Wynntils | Merged roster |
| `TabListGuildParser` | internal; `/wv debug trigger tabDump` | The tab list's guild column (no request) | `GuildEntry` list, truncated past ~19 online |
| `OnlineGuildCache` | internal | None (in memory) | Guild-tier names kept listed for a 30 s grace period |
| `TimeLabels` | internal | None (formats only) | Relative date and age labels for `/wv check` |
| `ReturnersRosterProbe` | internal (`/wv check`) | WynnCraft `/v3/guild` rosters | Returners membership, rank and legacy name; another guild's rank only. A roster call has three outcomes: lists the UUID, answered without it, or no usable answer (non-200, failure, unreadable body), which `CheckReportRenderer` shows as the payload's guild with a yellow `(cached)` tail, never as absence |
| `CheckReportRenderer` | internal (`/wv check`) | The tab list's guild column, through `OnlineMemberService` (no request) | Chat lines, sent one per `sendLocalMessage` |

### StaffFetcher details
Parses JSON array, extracts `{username, rank, world/server}`, filters online, sorts by rank priority (owner=0, chief=1, strategist=2, captain=3), then alpha.

### ListFetcher partitions
1. Guild with VetsMod, not queued
2. Guild - in Queue (VetsMod, queued)
3. Guild without VetsMod
4. Honourary
5. Waitlist

(Names AQUA, except LIGHT_PURPLE for in-queue and GRAY for without-VetsMod; italic for honourary and waitlist. The italic light-purple / dark-aqua honourary / waitlist styles are `/wv list world`'s.)

Styling: Staff underlined (via `StaffRanksPoller.confirmedRankFor()`); supporters gradient glint (via `SupportersPoller.isSupporter()`). Hover shows "Click to message X"; the click is a `ClickEvent.SuggestCommand` — it pre-fills the chat box with `/msg X ` (trailing space) rather than sending anything.

### WorldListFetcher flow
"Looking up..." → gather players and fetch staff names (concurrently) → dispatch `/find` batch via `FindDispatcher.enqueueFindBatch()` → re-query unplaced players under `PlayerLookup`'s canonical names → group by server → group by region (GeoIP2 prefix) → sort by count desc.

### StampFetcher formats
- <1 hour: `"Annihilation is in X mins!"` (red) + "Click here for more info" link
- ≥1 hour: `"Annihilation returns in X hours Y mins!"` (red/dark-red) + reminder + `VetsApi.ANNI` link
- Past: `null` (no display)

### OnlineMemberService merge strategy
[OnlineMemberService.merge()](../src/client/java/org/wynnvets/fetcher/ondemand/OnlineMemberService.java)
1. Build UUID→username from Wynntils (authoritative)
2. Overlay `PolledJsonMap.GUILD_ROSTER.snapshot()` (Mojang-resolved, takes precedence over stale API; `overlayRoster` canonicalises its keys through `Uuids.parse`, keeping a key that does not parse as it is)
3. Supplement with VetsMod usernames for unresolved UUIDs (`supplementConnected`: every tier gets a display name, but only guild-tier users can be resolved from a tab-list name, so a tab name equal to a connected honourary's or waitlister's stays unresolved and is skipped by step 6)
4. Merged guild online = Wynntils online ∪ VetsMod guild UUIDs ∪ tab list (`TabListGuildParser`, read only when `GuildStateManager.isReturners()`: `trustedTabEntries` returns an empty list otherwise, because the guild column is the viewer's own guild's)
5. Honourary + waitlist from VetsMod `/list`
6. Tab-only usernames (no UUID match, including no `PolledJsonMap.WYNN_ALIASES` hit) added as guild tier
7. Apply grace-period cache (`OnlineGuildCache`: recently-seen guild-tier players; `graceCandidates` keeps honourary and waitlist names out, since a grace name is re-added as guild tier with no UUID; `graceReadds` re-adds them only for a Returners viewer, since the cache lives for the whole game and a name remembered while the viewer was wrongly taken for a member must not come back)

## 5. Polling services

Package: [org.wynnvets.fetcher.polling](../src/client/java/org/wynnvets/fetcher/polling/)

Six fixed-rate schedules live in this package across five classes, all started back-to-back from `VetsmodClient.onInitializeClient`: `SupportersPoller` 5m, `StaffRanksPoller` 2m, `AnniStampPoller` 5m, `AnniSnapshotPoller` 30s, and `PolledJsonMap`'s two instances — `GUILD_ROSTER` 5m and `WYNN_ALIASES` 5m. `AnniSnapshotPoller` is the only gated one — its tick returns early unless the cached snapshot's `stamp_epoch` is in the future and within 90 minutes (it does not read `announced`), and while the debug harness holds it for an injected snapshot (`setDebugHold`). Four of the six have a subsection below; `AnniStampPoller` and `AnniSnapshotPoller` do not.

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

[PollingService](../src/client/java/org/wynnvets/fetcher/polling/PollingService.java) is the one place a poller's lifecycle is defined: a named daemon thread, an idempotence guard, a `scheduleAtFixedRate` call, and a `stop()` that drains before it cancels. Every schedule in this package goes through it; AnniZone's, outside the package, does not. It takes the `Runnable` as-is and adds no exception handling or logging of its own — each caller keeps its own `try`/`catch` and its own message — and it uses the thread name it is given verbatim, including the two that disagree with the class passing them (`VetsMod-StaffRanksFetcher`, `VetsMod-SupportersFetcher`) and the two that now name instances rather than classes (`VetsMod-GuildRosterCache`, `VetsMod-WynnAliasCache`). Those names are thread-dump identity; renaming one is a diagnostics change, not tidying.

`start()` returns a `boolean` saying whether this call started the schedule. That is what lets `AnniSnapshotPoller` keep its start-log line inside the idempotence guard without `PollingService` knowing anything about logging.

**Nothing stops any of them.** Every poller is started at mod init and never stopped: there is no `CLIENT_STOPPING` registration, no shutdown hook, and no call to `PollingService.stop()` outside its own test. The poller threads are daemons, so the JVM does not wait on them and the omission has never been visible. `PollingService.stop()` exists so that a teardown has somewhere to hook if the mod ever grows one, and it re-asserts the caller's interrupt rather than swallowing it. `AnniZone`, which schedules outside this package, has never had a `stop()` at all.

### PolledJsonMap (2 instances, 5 min each)

[PolledJsonMap](../src/client/java/org/wynnvets/fetcher/polling/PolledJsonMap.java) polls one flat `string → string` JSON endpoint and publishes it as an immutable snapshot behind a volatile field. Two instances:

- `GUILD_ROSTER` — `VetsApi.ROSTER` (server-side Mojang-resolved), UUID → current username. Overrides stale Wynncraft API usernames. Used by `OnlineMemberService.merge()` via `snapshot()`. Keys are stored exactly as the server spells them; `merge()` canonicalises them to the dashed lowercase spelling when it overlays them, so the server's spelling does not matter there.
- `WYNN_ALIASES` — `VetsApi.ALIASES`, stale tab-list username → UUID. Used by `OnlineMemberService.merge()` via `get()` to resolve tab entries no current username matches. Keys are folded to `Locale.ROOT`.

The key normalizer is one field applied at **both** ingest and lookup. That is the invariant the class exists to hold: applying it on one side only breaks alias resolution with no failed fetch and no log line. `PolledJsonMapTest` pins it for both instances, along with the cold-cache contract — empty map from `snapshot()`, `null` from `get()` — which `merge()` depends on and has no branch for.

### StaffRanksPoller (2 min)
[StaffRanksPoller.start()](../src/client/java/org/wynnvets/fetcher/polling/StaffRanksPoller.java)
- **Two** caches, both `ConcurrentHashMap`s keyed by lowercase name: `staffRanksByUsername` (replaced wholesale each poll) and `liveStaffRanksByUsername`, a push overlay fed by `staff_online` / `staff_offline` outbound frames, which arrive only on an authenticated outbound socket (§1). `confirmedRankFor` checks the live map first, so a pushed staff member is never evicted by a stale poll snapshot. Each overlay entry records when it was applied; a poll that **started after** the push and no longer lists the name drops it (the server's staff list covers everyone it announces, so the name's absence means a `staff_offline` was missed), while an entry pushed after the poll started survives it. A side map from each `staff_online`'s uuid lets a `staff_offline` with a null username, which temporary-server's §2.6 allows, find its entry. The overlay is cleared by `V1ApiManager.disconnect()` (`clearLiveOverlay`) and when the outbound socket authenticates (`onOutboundAuthenticated`, from `OutboundAckRouter`'s ok ack, which also calls `refreshNow()`), since the server replays no presence to a new socket
- Runs every 2 minutes, scheduled initially immediate
- Fetches `VetsApi.STAFF` (per temp-server, the staff currently online) and replaces the entire poll cache each time: it adds the response's entries, then drops the names the response lacks, so a name in both responses is never briefly absent. Fetches are `synchronized`, so the scheduled poll and a `refreshNow()` apply one response at a time
- `ALLOWED_RANKS` is strategist/chief/owner only — **captain is rejected**, retired in the 2026-07 permission restructure, and a stray captain is dropped and treated as a non-staff Returner client-side
- Read (`confirmedRankFor`) by `EncourageUpdateRewriter`, `StaffChannelMessageRewriter`, `StaffGuildAlertRewriter`, `GuildChatDispatcher` and `ListFetcher` (underline styling). Written by `V1ApiManager` through `applyLiveStaffEvent(username, uuid, rank, online)` (from `staff_online` / `staff_offline` frames), by `InboundAckRouter` through `refreshNow()` (an off-schedule fetch on every successful inbound auth ack), and by `OutboundAckRouter` through `onOutboundAuthenticated()`. Started by `VetsmodClient`
- Why poll as well as push? The push carries only changes; the poll supplies the full online-staff set at start and resyncs it every 2 min, and is the only source for a client whose outbound socket is not authenticated (no key, or a non-member tier)

### SupportersPoller (5 min)
[SupportersPoller.start()](../src/client/java/org/wynnvets/fetcher/polling/SupportersPoller.java)
- Volatile `Set<String>` lowercase usernames
- Normalization: trim, strip NBSP, strip level tags `<N>`, lowercase
- Nickname mode: split on `/`, check both halves
- Fetches `VetsApi.SUPPORTERS` every 5 min
- Read by `ChatUtils`, `ServerGuildChatRewriter`, `ListFetcher` (gradient glint), `NametagMixin` and `DiagnosticsHandler` (the `/wv debug` readout). ⚠️ **Not** `PillFormatter` or `NametagAnimator` — both take a `boolean isSupporter` from their caller and never touch the poller; `NametagAnimator`'s own Javadoc says so

## 6. Listeners

[ServerConnectionListener](../src/client/java/org/wynnvets/listeners/ServerConnectionListener.java)
- Fabric `ClientPlayConnectionEvents.JOIN` → connect WebSockets when `onWynncraft()` holds (Wynntils' `Managers.Connection` state is not `DISCONNECTED`: for a Wynncraft host it has already left `DISCONNECTED` when Fabric's join fires, and it stays out of it across a world transfer), register the outbound handler. That is all it does. On any other server, and in singleplayer, no socket opens and vetsmod sends no frame (its HTTP pollers still run, as they do from launch); no socket connects at launch either, so the first Wynncraft join waits one connect and auth round trip for the anni snapshot and the staff state. `GuildChatDispatcher.intercept` uses the same test.
- Fabric `ClientPlayConnectionEvents.DISCONNECT` → reset guild state, reset `newTooltipStylesAvailable`, disconnect WebSockets, unregister the outbound handler, and clear `OutboundDisplayHandler`'s three dedup/suppression caches

[WynntilsEventListener](../src/client/java/org/wynnvets/listeners/WynntilsEventListener.java):
- `@SubscribeEvent WorldStateEvent` → on WORLD state, call `GuildLifecycle.onEnteredWorld()`
- `@SubscribeEvent GuildEvent.Joined` → call `GuildLifecycle.onGuildJoined()`, which runs `onGuildInfoUpdated()` and, for Returners, the re-auth ([vetsmod_guild_system.md](vetsmod_guild_system.md) §4); `GuildEvent.Left` → call `GuildLifecycle.onGuildLeft()`, which clears the guild-check cache at once and, one tick later (Wynntils clears its guild name after posting the event), re-evaluates guild state and retracts the presence registration
- `@SubscribeEvent ChatMessageEvent.Match` (GUILD type) → guild chat relay:
  1. Extract username + message via `GUILD_CHAT_PATTERN` (`^\s*(.+?):\s+(.+)$`)
  2. Strip PUA badge glyphs
  3. Extract real username from hover ("X's real name is Y")
  4. Bridge echo suppression
  5. Resolve rank from hover → pill glyph decode → Wynntils guild model
  6. Client-side dedup: fingerprint `username\0normalizedMsg`, 100-entry deque, 5s TTL
  7. URL join: `UrlWrapJoiner.join(rawPlain, messageContent)` reads the line as it arrived, before Wynntils' unwrap. A break is a hard URL break (Wynncraft split a URL wider than a line in place) when the last token before the `\n` holds a URL (contains `http://` or `https://`, so `(https://…` counts, unless the bracket or quote before the scheme also closes at the token's end, as in `(https://…)`, which marks the URL complete) or continues a chain, no space precedes the `\n`, and the next line starts, after its wrap prefix, with a non-space; the fragments are glued in the unwrapped text, and a chain continues only while a fragment fills its line. A word wrap leaves the URL and the words after it alone. Known residue: a URL ending exactly at a wrap point where Wynncraft also dropped the space before the next word merges with that word
  8. `V1ApiManager.sendInbound("guild", rank, username, message)`

[LegacyHighlightEventListener](../src/client/java/org/wynnvets/listeners/LegacyHighlightEventListener.java):
- `SlotRenderEvent.Pre` at `EventPriority.LOWEST` (runs AFTER Wynntils' `ItemHighlightFeature` at HIGH, overriding it)

[LegacyTooltipEventListener](../src/client/java/org/wynnvets/listeners/LegacyTooltipEventListener.java):
- `ItemTooltipRenderEvent.Pre` at NORMAL — sets `LegacyItemHandler.currentItemStack` / `currentItemHasFoil` context fields

## 7. Message flow summaries

**Inbound (user types /g → server):**
1. Wynncraft shows guild chat message
2. `WynntilsEventListener.onGuildChat()` parses via regex
3. Dedup + rank resolution + URL join (`UrlWrapJoiner`)
4. `V1ApiManager.sendInbound(type, rank, username, message)`. A waitlist, queue or honourary relay comes from `GuildChatDispatcher` instead and uses the overload with an ack callback (§1)

**Outbound (WebSocket → user sees it):**
1. Server pushes JSON via outbound WS
2. `WsClient.handleText()` → `V1ApiManager` → `OutboundDisplayHandler.onOutboundMessage()`
3. A `warning` frame goes to `WarningFrameRenderer` and skips the rest (it arrives only on an authenticated outbound socket, §1). Any other frame: display gates, UUID dedup (10s TTL), self-suppression (30s TTL); Returners' `guild` frames drop unless queued, and `bridge` frames are recorded (10s TTL) for `WynntilsEventListener`'s echo check, which runs there, not in this flow
4. A staff `‼` alert on a `guild` or `queue` frame goes to `StaffGuildAlertRewriter`; otherwise `ChatUtils.sendHonouraryChatMessage()` (honourary-unlocked viewer) or `sendGuildChatMessage()` formats + displays

## 7.1 MWE/anni frames

| Direction | Type | Sender | Receiver | Purpose |
|---|---|---|---|---|
| Inbound | `anni_query` | `AnniWsSender.sendAnniQuery` | temp-server `_handle_anni_query` | On-demand snapshot pull; ack as `anni_query_response`. |
| Inbound | `anni_query_response` | temp-server | `AnniQueryClient.onResponse` | Reply to `anni_query`, on the socket that carried it; FIFO queue of per-call futures (nothing coalesces). |
| Inbound (S5) | `anni_scrollspot_set` | `AnniWsSender.sendAnniScrollspotSet` | temp-server `_handle_anni_scrollspot_set` | Host writes (or clears) party scroll-spot. **Authenticated only.** Server reads MC UUID from session — never from frame. |
| Inbound (S5) | `anni_scrollspot_response` | temp-server | `AnniScrollspotClient.onResponse` | Ack for `anni_scrollspot_set`. `{status: ok|error, detail}`; FIFO queue. |
| Inbound (S6) | `anni_rsvp` | `AnniWsSender.sendAnniRsvp` | temp-server `_handle_anni_rsvp` | In-game `/wv anni rsvp <hard|soft|revoke>`. **Authenticated only**; MC UUID from session. |
| Inbound (S6) | `anni_rsvp_response` | temp-server | `AnniRsvpClient.onResponse` | Ack for `anni_rsvp`. `{status: ok|error, detail, result}`, where `result` is `recorded`, `withdrawn` or `nothing_to_withdraw` on success and absent or null from an older server; FIFO queue. |
| Inbound (S7) | `anni_party_observation` | `AnniWsSender.sendAnniPartyObservation` | temp-server `_handle_anni_party_observation` | Vetsmod reports its local Wynncraft party roster when an organiser username is in the party. **Authenticated only**; observer UUID stamped from session. Names go over the wire (Wynncraft only exposes party members by username); vets-anni resolves via its roster + alias caches. |
| Inbound (S7) | `anni_party_observation_response` | temp-server | `AnniWsHandler` (debug log only) | Ack for `anni_party_observation`. No client-side queue — observation is fire-and-forget; debug-logged only. |
| Outbound | `anni_state` | temp-server `anni_snapshot_poller` | `AnniWsHandler.onOutbound` → `AnniSnapshotCache.update` | Server-initiated snapshot push (per-uuid gated on the subscription set, filled at inbound auth). Sent only to an authenticated outbound socket, so received by a confirmed member tier and never by a player with no key or tier `other` (§1). |

Response futures (query, scrollspot, rsvp) live in `org.wynnvets.mwe.anni.network` and time out at 8 s. `AnniWsHandler` is the single demux for all types — its `onInbound`/`onOutbound` branches route to the right consumer.

### S7 — Party back-report gate

`PartyRosterListener` no longer fires the legacy `party_status` frame. Instead, on the Wynntils `PartyEvent`s `PartyRosterListener` subscribes to, on every `WorldStateEvent`, AND on every snapshot update that changes the lowercased `organiser_usernames` set or is the first to find the anni stamp inside the `stamp ± 2 h` window since the last one that did not (`AnniPartyReporter` observes the change or the opening and calls `PartyRosterListener.requestRecapture()`, which is the listener's own method — the reporter is the trigger, not the owner), the listener:

1. Captures `Models.Party.getPartyLeader()` + `getPartyMembers()` on the render thread. A Wynntils event already arrives there. A snapshot-driven recapture is triggered on whichever thread called `AnniSnapshotCache.update` (the one delivering WebSocket frames, or the render thread for a debug injection), so `requestRecapture` hands the capture to the render thread through `Minecraft.execute`, which queues it for the next task drain, a debug command typed on the render thread included; the 300 ms send debounce absorbs that delay.
2. Debounces 300 ms (coalesces the `/party list` burst).
3. Gates on `stamp ± 2 h` AND any party member's username appears in `AnniSnapshotCache.latest().organiserUsernames()` (case-insensitive).
4. Fires `AnniWsSender.sendAnniPartyObservation(members, leader, world)` if the gate passes.
5. Heartbeat: after a flush that passed the gate (whether or not the send went out), schedules `requestRecapture` `HEARTBEAT_MS` = 30 s later into the same pending slot (`heartbeatDelayMs`), skipped if a newer capture has taken the slot since. The recapture re-reads `Models.Party` and flushes like any event, so it re-sends and re-arms while the gate holds. Any real event's capture cancels the pending heartbeat; a suppressed flush (left the party, window over, no organiser in the party) does not re-arm, so heartbeats stop by themselves.

vets-anni resolves names → UUIDs server-side and writes
`state.party_leader_by_uuid[member_uuid] = leader_uuid` for the presence
classifier's `ONLINE_WORLD → ONLINE_PARTY` upgrade. Per vets-anni (since its
`6a228c2`), each observation whose names all resolve **replaces that leader's
roster**: entries already pointing at the same leader whose member the new
roster does not list are dropped, and entries for other leaders are untouched.
An observation with a name vets-anni cannot resolve only adds, since that name
may be a member paired by their own report. Freshness is per member: vets-anni
stamps every member an observation lists (`state.party_seen_at_by_uuid`) and an
entry is stale `_PARTY_LEADER_TTL_SECONDS` = 90 s after its own member's stamp,
so another party's reports cannot keep it alive; its grace-wipe clears the map
and the stamps. vetsmod reports no leave (an empty capture never passes the
gate), so a member who leaves the host's party keeps their pairing only until
the next fully resolved observation of that party, from any client, or until
their entry expires 90 s after the last observation that listed them, or until
the leaver's own report pairs them with another leader. A quiet static party
stays fresh because vetsmod heartbeats (step 5): it re-sends every 30 s while in
an organiser's party, so 90 s outlasts one lost beat (beats land a little over 30 s apart, so a second lost one can let the pairing lapse until the next).

## 8. Auth

One layer, an **application-level bearer key** — `auth` frame sent after inbound WS connect. Nothing is attached to the WebSocket upgrade itself: `WsClient.connect()` builds the socket with a connect timeout and no headers. The key is a 43-char URL-safe base64 token issued by dazebot's `/vetsmod` Discord command and stored in `vetsAuthKey`. Re-sent automatically by the inbound client's `onConnect` callback (set in `V1ApiManager.connect`) on every inbound reconnect; also sent immediately when the user runs `/unlock <key>`, if the inbound socket is up, so feedback lands in the same session rather than only after the next reconnect.

The server validates each key by HTTP introspection against dazebot (`POST /api/auth/introspect`, 60s LRU cache, serve-stale-on-error during dazebot outages). The resolved tier (`member`/`waitlist`/`honourary`/`other`) drives a per-connection chat-type gate: see `../../temporary-server/v1_protocol.md` §1.8 and §2.4. The outbound socket authenticates separately with the same key, only for a member tier (§1).

## 9. Error handling

- WebSocket errors → `WsClient.onError()` logs, aborts, schedules reconnect
- HTTP errors are **absorbed, not propagated**, by two different mechanisms. The on-demand fetchers end their `CompletableFuture` chain in `.exceptionally(e -> …)` returning a fallback whose shape is per-fetcher: a red `Component` from `StaffFetcher`, an unstyled one from `MotdFetcher`/`ReturnFetcher`, `null` from `StampFetcher`, and domain values (`ReturnersMembership.unknown()`, `List.of()`) elsewhere. **Five** of the six polling schedules use no futures at all — each calls `HttpClient.send(...)` synchronously inside a `try`/`catch` that only logs, so a failed tick leaves the last successful cache in place and the next tick re-attempts. They are four source lines, not five, because `PolledJsonMap`'s two instances share one `fetch()`. `AnniSnapshotPoller` is the sixth schedule and is not one of them: it goes over the WebSocket via `AnniQueryClient` and builds no `HttpClient` at all. Nor is `polling/` the only home of a synchronous send — `DispatchCoordinator.isSelfListedInOnlineStaffFeed`, `DispatchCoordinator.fetchOnlineStaffUsernames` and `AnniZone.refresh` are three more. **Seven synchronous call sites in all.** Every one of them catches `Exception`, not `Throwable`; for the scheduled ones `PollingService.guard` (which `AnniZone`'s own schedule uses too) logs anything that escapes, and the schedule goes on; only a `VirtualMachineError` ends it, logged, and `isRunning()` then says so. No fetcher calls `completeExceptionally`; the repo's only use of it is `DispatchCoordinator`'s `/find` batch future
- No retry on HTTP failures; next polling tick re-attempts
