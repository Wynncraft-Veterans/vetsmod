---
name: temporary-server API Reference
description: Complete API surface — v1/inbound + v1/outbound WebSockets, all /v1/outbound/* REST endpoints, v0 legacy compat, admin toggles, protocol
type: reference
originSessionId: dc63f47a-2d15-4f8d-9b6a-41d3049f0cc2
---
# temporary-server API Reference

Base URL: `https://api.wynnvets.org` (REST + WSS). Protocol spec: [v1_protocol.md](../../temporary-server/v1_protocol.md).

## 1. WebSocket endpoints

### `/v1/inbound` — client → server
[app/chat/inbound.py:184-308](../../temporary-server/app/chat/inbound.py)

- Client sends JSON frames (max 64KB)
- Server ACKs `{"status": "ok"}` or `{"status": "error", "detail": "..."}`
- Optional query param `?version=...` for client version tracking

**Frame types accepted:**

#### Message frame
```json
{
  "uuid": "...",
  "type": "guild|queue|waitlist|honourary",
  "timestamp": 1234567890,
  "rank": "...",  // required for guild AND queue types (validated identically)
  "username": "name",
  "message": "text"
}
```

`queue` is semantically a guild message originated by a sender stuck in a Wynncraft world queue (the game server drops `/g` while queued, so vetsmod routes the message via the WS instead). Validated and sanitized identically to `guild`. **Not** deduplicated, since only the queued sender originates a copy. Discord-bridge prefix is `Guild` (indistinguishable from a regular guild message on the Discord side).

Processing pipeline:
1. `validate_inbound()` — all 6 fields present, type in `VALID_INBOUND_TYPES = (guild, queue, waitlist, honourary)`, guild/queue have valid rank
2. `sanitize_inbound()` — strip C0 controls, collapse whitespace, truncate to 256 chars, strip PUA from username
3. `transform_inbound()` — split `username/nickname` form on `/`, register alias
4. For `type="guild"` only: dedup via `MessageDeduplicator.is_duplicate()` → silently ACK if duplicate. (`queue`/`waitlist`/`honourary` skip dedup entirely.)
5. Enqueue to `state.outbound_queue`
6. Fire-and-forget async relay to Discord bridge (if bridge enabled)

#### Register frame
```json
{
  "type": "register",
  "uuid": "...",
  "username": "name",
  "tier": "guild|waitlist|honourary"
}
```

Validates: UUID hex format, username `^[a-zA-Z0-9_]{3,16}$`, tier in allowed set. Stored in `state.connected_users[ws]`. Silently ignores invalid registrations (no error ACK).

#### Tab list frame
```json
{
  "type": "tablist",
  "entries": [{"server": "WC1", "username": "name"}, ...]
}
```

#### Auth frame
```json
{"type": "auth", "key": "<43-char base64url bearer token>"}
```

#### Rank-change frame
```json
{
  "type":           "rank_change",
  "uuid":           "...",
  "timestamp":      1234567890.0,
  "actor":          "RealPuffy",
  "target":         "1xMelody",
  "from_rank":      "Recruiter",
  "to_rank":        "Recruiter",
  "classification": "ban|kick|mote"
}
```

Sent when vetsmod sees `"X has set Y guild rank from A to B"` in guild chat. Classification rules:

- `ban` — `from ∈ {Captain, Strategist, Chief, Owner}` and `to = Recruit` → forwarded to dazebot for staff-channel post + role @ping.
- `kick` — `from = Recruit` and `to = Recruit` (failed-onboarding signal) → forwarded to dazebot, no ping.
- `mote` — `from == to` and that rank is not Recruit → bridge-channel post `"**<target> got moted!**"`.

`Recruiter → Recruit` and real promotions across distinct ranks are intentionally not classified — they are dropped client-side and never frame'd. Server-side dedup window is 60s on `(actor.lower, target.lower, from_rank, to_rank)` so the N reporting clients only trigger one alert. Trust model: **single authenticated client report is authoritative**; dazebot's posted alerts schedule async WAPI verification (5min delay for cache turn-over) and edit the message with `[VERIFIED]` / `[UNVERIFIED — ...]`.

Auth gate is identical to chat: when `unauth` is disabled, only authenticated sessions may submit. Frames are **not** tier-gated — any tier may report.

See [v1_protocol.md §1.9](../../temporary-server/v1_protocol.md) for the authoritative spec.

---

Server validates the auth key by HTTP introspection against dazebot (`POST /api/auth/introspect`, 60s LRU cache). Reply on success:

```json
{
  "status":      "ok",
  "tier":        "member|waitlist|honourary|other",
  "ws_tier":     "guild|waitlist|honourary|null",
  "mc_uuid":     "<uuid>",
  "mc_username": "<name>"
}
```

Failure: `{"status":"error","detail":"auth rejected: <reason>"}`. A failed `auth` clears any prior session on the connection (logout); a successful one overwrites it (rotation). Authenticated chat is then tier-gated: `guild` may send `guild`+`queue`, `waitlist` only `waitlist`, `honourary` only `honourary`. Unauthenticated chat is allowed only when the `unauth` admin toggle is enabled.

The same `auth` frame is also accepted on `/v1/outbound`; the server uses the resulting tier to filter what the client receives.

Stored as fresh snapshot in `state.latest_tablist`. Expedites staff probes for visible names (matches stale Wynncraft names via `staff_wynn_name_to_uuid` and current usernames via `staff_roster_by_uuid`).

**Disabled gate:** If `"inbound"` in `state.disabled_components`, all messages rejected with error.

### `/v1/outbound` — server → all clients
[app/chat/outbound.py:36-141](../../temporary-server/app/chat/outbound.py)

- Clients connect, register in `OutboundManager._connections` set
- Server broadcasts to all connected clients via FIFO queue (`state.outbound_queue`)
- Client frames read and discarded (used only to detect disconnect)
- Dead connections pruned automatically

**Broadcaster loop** (`outbound_broadcaster()` in `app/chat/outbound.py:98-116`):
- Background coroutine started in app lifespan
- Infinite loop: dequeue → record traffic → check disabled → broadcast
- Messages consumed even when outbound disabled (but not sent)

**Disabled gate:** If `"outbound"` in `state.disabled_components`, broadcast skipped.

## 2. REST endpoints (v1)

All under `/v1/outbound/`. Defined in [app/routes/static.py](../../temporary-server/app/routes/static.py). No authentication — security via WSS/TLS only.

### `GET /v1/outbound/motd`
Plain text MOTD from config. Default is Minecraft-formatted welcome banner.

### `GET /v1/outbound/guild_motd`
Plain text guild-specific MOTD (separate from general MOTD).

### `GET /v1/outbound/staff`
JSON array of online staff. Fields per entry: username, rank, world/server.
Sort: rank priority (owner→chief→strategist→captain), then username alpha, then UUID.
Source: `state.online_staff_by_uuid` (populated by StaffPoller).

### `GET /v1/outbound/supporters`
JSON array of donators. Each entry: `{uuid, username}`.
Usernames resolved via `get_supporter_username()` (1-hour Mojang cache).
Source: `config.donatorList`.

### `GET /v1/outbound/return`
Minecraft chat Component for the latest return-channel Discord message.
Source: `state.latest_return_message`.

### `GET /v1/outbound/stamp`
Plain text annihilation timestamp (as Discord `<t:...>` format).
Source: `state.latest_webhook_timestamp`.

### `GET /v1/outbound/list`
JSON `{connected: [{uuid, username, tier}, ...]}` of registered VetsMod clients.
- Deduped by UUID (last registration wins)
- Includes recently-seen grace period (30s)
- Sorted by tier alpha, then username case-insensitive.
Source: `state.connected_users` + `state.recently_seen_users`.

### `GET /v1/outbound/roster`
JSON object `{uuid: username, ...}` for all Returners members.
Usernames resolved via Minecraft Services API (GuildRosterPoller, 5min refresh, 12h cache).
Source: `state.guild_roster_by_uuid`.

### `GET /v1/outbound/aliases`
JSON object `{stale_username: current_username}` mapping stale Wynncraft API usernames to the current Mojang-resolved name. Used by clients (and dazebot) to reconcile names returned by Wynncraft's guild endpoints (which lag) with the live names players actually go by. Defined in [app/routes/static.py](../../temporary-server/app/routes/static.py).

## 3. REST endpoints (v0 legacy)

Maintained for backwards compat. Same data as v1 except:

### `GET /v0/outbound/motd`
Returns a Minecraft-formatted "please update" warning instead of the real MOTD.

Other v0 endpoints (`/staff`, `/supporters`, `/stamp`) mirror v1.

## 4. Admin toggles

Runtime disable via `!disable <component>` Discord command. Components:
- `inbound` — reject all `/v1/inbound` chat frames with error (control frames `auth`/`register`/`tablist`/`queue_status` still work)
- `outbound` — drop from broadcast queue (still dequeued)
- `staff` — skip staff roster polling
- `bridge` — skip Discord bridge relay
- `unauth` — when disabled, unauthenticated WS sessions cannot send chat (rejected with `Authentication required: send an auth frame first.`) and cannot receive chat (broadcast skips them). Default during alpha is enabled.

State in `state.disabled_components` (Set[str]). Persisted? No — resets on restart.

## 5. Protocol notes

From [v1_protocol.md](../../temporary-server/v1_protocol.md):

- **Bridge messages** (Discord → game): `type="bridge"`, extra fields `is_admin`, `source`. NOT deduplicated. Visible to every authenticated tier.
- **FIFO broadcast:** All connected outbound clients receive same order.
- **Tier filtering:** Authenticated outbound connections only receive message types their tier permits (`guild`-tier sees `guild`+`queue`+`bridge`, etc.). Unauthenticated connections receive everything *unless* `unauth` is disabled.
- **Registration optional:** Clients without it still send/receive messages but don't appear in `/v1/outbound/list`.
- **`server_info` hello:** the server pushes `{type:"server_info", unauth_enabled: bool}` as the first outbound frame after connect so clients know the current toggle state.

## 6. Error responses

All errors from `/v1/inbound` return `{"status": "error", "detail": "..."}`. Detail strings:
- `"Invalid JSON"` — parse error
- `"Missing fields"` — required field absent
- `"Invalid message type 'foo'. Expected one of: guild, queue, waitlist, honourary"` — type not in allowed set
- `"Invalid rank"` — guild/queue message with bad rank
- `"Inbound processing is disabled"` — admin toggle
- `"Server busy"` — outbound queue full
- `"Authentication required: send an auth frame first."` — `unauth` disabled and connection has no auth session
- `"Your tier (<tier>) is not permitted to send '<type>' messages."` — tier gate
- `"auth rejected: <reason>"` — introspection failed, key revoked, etc.

Max payload: 65536 bytes.

## 7. Timing / TTLs

| Cache | TTL | Source |
|-------|-----|--------|
| Supporter username | 1 hour | Mojang |
| Guild/staff username | 12 hours | Mojang |
| Recently-seen users | 30 seconds | Disconnect grace |
| Tablist staleness | 60 seconds | Client last-update |
| Dedup window | 5 seconds | Per-message |
| Dedup alias | 30 seconds | Nickname resolver |

## 8. Polling intervals

| Task | Interval | Purpose |
|------|----------|---------|
| StaffPoller (full) | 5 min | Re-fetch guild roster for staff ranks |
| StaffPoller (probe) | 10 sec | Round-robin per-player online check |
| GuildRosterPoller | 5 min | Full guild UUID→username resolve |
| Dedup cleanup | 60 sec (amortized) | Prune expired entries |

## 9. Client version tracking

Inbound WS accepts query param `?version=X.Y.Z`. Logged but not enforced. Used for debugging compatibility.
