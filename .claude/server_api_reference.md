---
name: temporary-server API Reference
description: The vetsmod-side client contract for temporary-server — which frames the mod sends, which ack shapes it parses, REST endpoints, admin toggles, TTLs. The authoritative wire spec lives in the sibling repo.
type: reference
originSessionId: dc63f47a-2d15-4f8d-9b6a-41d3049f0cc2
---
# temporary-server API Reference

Base URL: `https://api.wynnvets.org` (REST + WSS).

**This doc is the client contract, not the wire spec.** The authoritative
per-frame specification is
[`v1_protocol.md`](../../temporary-server/v1_protocol.md) in the sibling repo —
roughly 1,300 lines, and the thing to read when you need exact field
semantics. What lives here is the vetsmod-facing half: which frames the mod
sends, which ack shapes it has to parse, and what the REST surface offers.
Duplicating the spec is what let this doc drift.

⚠️ Some things the spec does **not** cover, so they are documented here rather
than deferred: `tablist` / `queue_status`, which the spec names in passing but never gives a section.
Deferring those would produce a pointer to nothing. `check_membership` and the `server_info` hello
used to be on this list; the spec covers them since temporary-server `e540a01` (§1.10) and `eacb4da`
(§2.1), and this doc keeps their vetsmod-side detail.

## 1. WebSocket endpoints

Two endpoints, `/v1/inbound` (client → server) and `/v1/outbound`
(server → clients; which frames reach a socket depends on whether it has
authenticated, see §"Frame inventory" below). Inbound frames are JSON text; the payload guard is
65,536 — despite the constant's name it is compared against the decoded
string, so it caps characters, not bytes. Inbound acks are
`{"status": "ok"}` or `{"status": "error", "detail": "..."}`, except where a
frame family defines its own typed response below. An optional `?version=`
query param is logged for client-version tracking and never enforced.

**Request ids.** Any inbound frame may carry `req`, a string of 1 to 64
characters (temporary-server `a1ee659`, spec §1.2). The server pops it as soon
as the frame parses, so it is never recorded, bridged or broadcast, and every
reply sent after parsing, typed responses included, echoes it as its last key
(§1.3). A `req` that is not such a string is ignored. The outbound
`server_info` advertises the feature as `req_echo: true`. An older server does
not pop it and broadcasts a chat frame's unknown keys to every outbound client,
so vetsmod sends `req` only on the staff-action frames, which read named keys
only; see [vetsmod_networking.md](vetsmod_networking.md) §1.

### Frame inventory

Everything `/v1/inbound` accepts, with the handler that serves it and where the
spec documents it. vetsmod's own senders are in
[vetsmod_networking.md](vetsmod_networking.md) §1 (with its V1Frames
subsection), §7 and §7.1.

| Frame | Handler | Reply | Spec |
|---|---|---|---|
| chat (`guild`/`queue`/`waitlist`/`honourary`) | chat pipeline | generic ack | §1.2 (shape), §1.4 (pipeline) |
| `register` | `_handle_register` | `{"status":"ok"}` (sent even when an invalid registration is silently ignored) | §1.7 |
| `tablist` | `_handle_tablist` | `{"status":"ok"}` | **not specified** — named in passing only |
| `queue_status` | `_handle_queue_status` | `{"status":"ok"}` | **not specified** — named in passing only |
| `auth` | `_handle_auth` | auth ack (below) | §1.8 |
| `rank_change` | `_handle_rank_change` | `{"status":"ok"}` (even when a validation failure drops it); an "Authentication required" error when `unauth` is disabled and the session is unauthenticated | §1.9 |
| `caution_check` | `_handle_caution_check` | history ack | §1.10 |
| `caution_add` / `warn_add` / `eject_add` | `_handle_staff_commit` | commit ack, or a `would_trigger` preflight | §1.10 |
| `check_membership` | `_handle_check_membership` | membership ack, or a member's invite verdict | §1.10 |
| `anni_query` | `_handle_anni_query` | `anni_query_response` | §1.11 |
| `anni_scrollspot_set` | `_handle_anni_scrollspot_set` | `anni_scrollspot_response` | §1.12 |
| `anni_rsvp` | `_handle_anni_rsvp` | `anni_rsvp_response` | §1.13 |
| `anni_party_observation` | `_handle_anni_party_observation` | `anni_party_observation_response` | §1.14 |

**Clearing the scroll spot sends `"scroll_spot": null`.** `AnniWsSender` builds the clear with a
JSON null, and `WsClient` serialises frames with null members kept (`WsClient.toWire`), as §1.12 asks.
Clients before that change sent `{"type":"anni_scrollspot_set"}` with no key; `_handle_anni_scrollspot_set`
reads it with `data.get`, as null, and clears, and the server keeps accepting it for them.

Server-initiated frames on `/v1/outbound`: broadcast chat and `bridge`,
`anni_state` (§1.15), `staff_online` / `staff_offline` (§2.6), the targeted
`warning` frame, and `server_info`. Of these, a socket that has not sent its own
`auth` frame receives only `server_info` and the broadcast chat (the chat only while
`unauth` is on). vetsmod authenticates its outbound socket only for a member tier and
only against a server whose `server_info` carries `chat_tiers: "all"`
([vetsmod_networking.md](vetsmod_networking.md) §1); otherwise it stays such a socket.

`rank_change` acks `ok` even when the handler silently drops the frame on a
validation failure, so a client cannot tell accepted from discarded.

### `tablist` and `queue_status`

Two presence side-channels that the spec names but never gives a section. Each
is acked `{"status":"ok"}`.

`{"type":"tablist","entries":[{"server":"NA58","username":"Foo"}, …]}` carries the
guild entries parsed from the tab list. `_handle_tablist` keeps the latest one
as its snapshot for `!list`, after dropping an entry whose `server` or
`username` is empty or whose `username` is not 3 to 16 characters of
`[A-Za-z0-9_]`. A frame whose `entries` is not a list is dropped whole. vetsmod
sends it only for a Returners member, and only when the tab list yielded
entries.

`{"type":"queue_status","queued":true,"world":"NA30"}` reports the sender entering
(`queued` true) or leaving (`queued` false) a world queue; `queued` is a
boolean and `world` a string. vetsmod sends `""` as the world when leaving, and
for a null world. `_handle_queue_status` ignores the frame from a socket that
has not sent `register`, and stores the world only while `queued` is true.
vetsmod sends it only while `GuildStateManager.isUnlocked()`.

### `rank_change` — the client-side half

vetsmod sends this when it sees `"X has set Y guild rank from A to B"` in guild
chat, and it does the classification itself; the `classification` field is
already decided by the time the frame leaves. Only three cases are ever sent:

- `ban` — a demotion to Recruit from any higher rank
- `kick` — the `Recruit → Recruit` self-loop, a failed-onboarding signal
- `mote` — `from == to` on a rank that is not Recruit

Every other change between distinct ranks (a real promotion, or a demotion
that does not land on Recruit) is dropped client-side and never framed. The
auth gate matches chat (authenticated when `unauth` is disabled), but the
frame is **not** tier-gated — any tier may report. Server-side the
dispatcher deduplicates across the N clients that saw the same broadcast, so
one event yields one alert. Field semantics and dazebot's downstream
behaviour are in `v1_protocol.md` §1.9.

### The staff-action family

Five frame types, not the four the spec's §1.10 title implies —
`check_membership` was added later and documented at the end of §1.10. All
five share one gate, `_staff_session_or_error`, which is the extracted helper
the family is built around: it returns the session when the connection is both
authenticated *and* `is_staff`, otherwise the error payload to send back. The
one exception is `check_membership` from an authenticated non-staff session of
tier `member`, which skips that gate and gets the invite verdict below.

The gate is the same for all five: **auth always required** regardless of the
`unauth` toggle, `is_staff` required, the `inbound` disabled-toggle bypassed,
and the tier gate bypassed (these are not chat).

Two contract details vetsmod has to implement:

- `caution_add` **preflights**. Unconfirmed, when the target is at a
  threshold, the reply is `status: "would_trigger"` with a separate `trigger`
  field naming what would fire. The client resends with `confirm: true` to
  commit. `warn_add` and `eject_add` never preflight.
- On the commit ack, `kind` is the *internal* kind, not the frame name:
  `warn_add` returns `"warning"` and `eject_add` returns `"eject"`.

`check_membership` in full (the spec's own copy is at the end of §1.10). The request mirrors
`caution_check` — `{"type": "check_membership", "target_username": "<name or uuid>"}` —
and the ack returns the snapshot **inline at top level** rather than nested,
deliberately, so vetsmod's ack classifier can match it on `target_uuid`
exactly like a `caution_check` ack:

```json
{
  "status":             "ok",
  "target_uuid":        "<uuid>",
  "target_username":    "<canonical mc username>",
  "discord":            { },
  "stage_2_active":     false,
  "blocklisted":        false,
  "blocklist_reason":   null,
  "in_returners_guild": false,
  "waitlist_count":     0,
  "cult":               null,
  "last_seen_observed": null
}
```

`last_seen_observed` is `null` or `{"ts": <unix seconds>, "server_at_observation": <str|null>,
"observed_at": <int|null>}`: dazebot's best guess of the last activity it recorded for the
player, not necessarily a server change. An older temporary-server omits the key; vetsmod reads
that as `null`.

**The invite verdict.** A non-staff session of tier `member` (temporary-server `49d12e2`) gets only
what the `/gu invite` gate needs, so a Returners member can invite without seeing the blocklist:

```json
{
  "status":          "ok",
  "target_uuid":     "<uuid>",
  "target_username": "<canonical mc username>",
  "may_invite":      true,
  "waitlist_count":  0
}
```

`may_invite` is false exactly when the target is blocklisted. vetsmod reads it through
`MembershipSnapshot.getMayInvite` / `blocksInvite`; the cascade's Vets step does not send for a
non-staff session (`VetsSnapshotProvider.sendsLookup`), and `InviteGate` fetches the verdict itself.

### The MWE anni frames

Four inbound (§1.11–1.14) plus the `anni_state` push (§1.15). Each inbound
frame replies with its **own typed response** rather than the generic ack,
which is what lets vetsmod's single-flight queues match futures
deterministically. `anni_query` is the only one open to unauthenticated
sessions; the other three require a session with an `mc_uuid` and stamp the
actor's UUID from that session rather than trusting the frame.

See [vetsmod_mwe_anni.md](vetsmod_mwe_anni.md) for the vetsmod half and
`v1_protocol.md` §1.11–1.15 for the field-level spec.

### Auth frame and ack

`{"type": "auth", "key": "<43-char URL-safe base64 token>"}`. The server
validates by HTTP introspection against dazebot
(`POST /api/auth/introspect`, 60s LRU cache). Success:

```json
{
  "status":             "ok",
  "tier":               "member" | "waitlist" | "honourary" | "other",
  "ws_tier":            "guild"  | "waitlist" | "honourary" | null,
  "mc_uuid":            "<uuid>",
  "mc_username":        "<name>",
  "is_staff":           false,
  "staff_rank":         "strategist" | "chief" | "owner" | null,
  "staff_rank_display": "Steward" | null
}
```

Failure is `{"status":"error","detail":"auth rejected: <reason>"}`. A failed
`auth` clears any prior session on the connection (usable as logout); a
successful one overwrites it (rotation).

`is_staff` is decided by a hardcoded `("strategist", "chief", "owner")` test
against the roster entry — **not** by `VALID_GUILD_RANKS`, which still contains
`captain`. `staff_rank` is populated only when `is_staff`.

**`staff_rank_display` is an additive 2026-07 field**, computed as
`RANK_DISPLAY.get(staff_rank.lower())` with no default, so it is `null`
exactly when `staff_rank` is. Today it is always `"Steward"`, since the three
staff ranks all map there. Old clients ignore the key and keep reading
`staff_rank`; new clients prefer the label. This is the auth-ack twin of
`pill_display` on bridge frames — see
[server_discord_bot.md](server_discord_bot.md) §3.

Authenticated chat is tier-gated: `guild` may send `guild`+`queue`, `waitlist`
only `waitlist`, `honourary` only `honourary`. There is no `queue` tier — it is
a guild-tier privilege. The same `auth` frame is accepted on `/v1/outbound`, with
the same staff resolution and ack (temporary-server `app/chat/auth_session.py`, since
`eacb4da`), where the resulting tier filters what the client receives: every member
tier gets every chat type, and a null `ws_tier` gets none. vetsmod sends it there
only for a member tier (see "`server_info` hello" below).

### `server_info` hello

Documented in `v1_protocol.md` §2.1 since temporary-server `eacb4da` (undocumented before). Pushed once on `/v1/outbound` connect, before the
receive loop:

```json
{"type": "server_info", "unauth_enabled": true, "chat_tiers": "all"}
```

`unauth_enabled` is true when `unauth` is *not* in `disabled_components`.
vetsmod uses it to phrase its session-start warning correctly
(allowed-but-unauthenticated vs blocked-and-unauthenticated) and routes it
straight to `SessionAuthWarning.onServerInfo()` ahead of the outbound
listeners. `chat_tiers` (from temporary-server `eacb4da`) advertises the
all-tiers receive map; vetsmod authenticates its outbound socket only when it
is `"all"`. Old clients see a frame with no `username`/`message` and ignore it.

### `/v1/outbound` broadcast

Clients register in the outbound manager's connection set and receive processed
messages from the FIFO `state.outbound_queue`, filtered per socket: an authenticated
socket of a member tier gets every chat type (`guild`, `queue`, `waitlist`,
`honourary`, `bridge`) and one with a null `ws_tier` gets none, and an
unauthenticated one gets everything while `unauth` is on and nothing while it is off. Client `auth` frames are
honoured (see "Auth frame and ack"); every other client frame is read and silently
dropped. Dead
connections are pruned. The broadcaster loop dequeues, records traffic, checks
the disabled toggle, and broadcasts — messages are consumed even when
`outbound` is disabled, just not sent.

## 2. REST endpoints

No **HTTP** route requires authentication except one. (The two WebSocket
endpoints are a different case: they accept the connection unconditionally,
but honour an in-band `auth` frame and can refuse chat at runtime via the
`unauth` toggle.) Security is WSS/TLS plus network placement; the source docstrings treat this as a considered posture rather than
an oversight (`/donor_pool` says "No auth (TLS only)"; `/anni-snapshot` says
"intentionally readable by anyone… Don't put secrets in the snapshot"). Worth
knowing that `/roster` dumps the full guild UUID→username map and `/list` every
connected user with their tier and world.

**Twelve GETs under `/v1/outbound/`** (`app/routes/static.py`):

| Route | Returns |
|---|---|
| `/motd` | Plain-text MOTD from config |
| `/guild_motd` | Plain-text staff-editable MOTD |
| `/staff` | Online staff as `{uuid, username, rank, online, server}`. `get_staff` delegates to `compose_online_staff` (`services/staff_visibility.py`), which unions WAPI-probed and WS-authenticated staff and sorts by `STAFF_RANK_ORDER` → username → uuid |
| `/supporters` | Glinted list as `[{uuid, username}]`, from `state.glinted_slots` |
| `/donor_pool` | Ranked donor candidates as `[{uuid, username}]`; `[]` before first poll |
| `/anni-snapshot` | `[{uuid, snapshot}]` for eligible players; debug-only |
| `/return` | Latest return-channel message as an MC component, or `{"text": ""}` |
| `/stamp` | Latest Discord `<t:…>` stamp as plain text, empty when unset |
| `/list` | `{"connected": [...]}`, deduped by UUID, incl. the 30s grace window |
| `/roster` | Guild `{uuid: username}` map |
| `/aliases` | Stale-name → UUID map (`legacyName` from the v3.8 guild payload, lowercased) |
| `/no-aspects` | `[{uuid, username}]` opted out of aspect distribution |

Naming is inconsistent in source and is not normalised here: `no-aspects` and
`anni-snapshot` are hyphenated, `donor_pool` and `guild_motd` underscored.

**Four `/v0/outbound/` legacy routes:** `/staff`, `/supporters` and `/stamp`
serve the same live data through the same helpers as v1. `/motd` does **not**
— it returns a hardcoded "your vetsmod is too old" banner and ignores config
entirely. The other eight v1 routes have no v0 counterpart.

**`GET /magbot-health`** — 503 when the tracked Magbot status is `None`,
`offline` or `invisible`. Deliberately not `/health`. See
[server_discord_bot.md](server_discord_bot.md) §15.

**`POST /api/internal/anni-snapshot-delta`** — the only route requiring a
secret, and the only `/api/internal/` route temporary-server exposes. It
**fails closed**: an unset `ANNI_INTROSPECT_SECRET` is a 503 (`internal
endpoint disabled`), a wrong one a 401. The secret check runs *before* the
poller-absent branch, so an unauthenticated caller cannot use the 200 `noop`
to probe whether the poller is running. Body is `{"uuids": [...]}` or
`{"all": true}`; the reply is `{"status": "ok", "pushed": N}`, or
`{"status": "noop", "pushed": 0, "detail": "poller disabled"}` when the poller
is absent — deliberately not a 5xx.

FastAPI's auto-generated `/docs`, `/redoc` and `/openapi.json` are also served,
unauthenticated — the app is constructed without the overrides that would
disable them.

## 3. Admin toggles

Runtime disable via `!disable <component>`. `TOGGLEABLE_COMPONENTS` is five:

- `inbound` — reject chat frames with an error; control frames still work
- `outbound` — drop from broadcast queue (still dequeued)
- `staff` — skip staff roster polling
- `bridge` — skip Discord bridge relay
- `unauth` — **inverted relative to the others.** While *enabled* (the
  default) unauthenticated sessions may send and receive chat; *disabling* it
  forces every client to authenticate

State lives in `state.disabled_components`, which defaults to empty. Not
persisted — it resets on restart.

⚠️ When `DAZEBOT_INTROSPECT_URL`/`SECRET` are unset the server falls back to
`NoOpAuthProvider`, which accepts every key. Blind auth acceptance plus
`!disable unauth` is the dangerous combination, and the startup log warns about
exactly it.

## 4. Error responses

Errors are `{"status": "error", "detail": "..."}` and the connection stays
open. Detail strings, verbatim:

- `"Payload too large"`, `"Invalid JSON"` (also for a body that parses but is not a JSON object)
- `"Missing required fields: <names>"` — validation errors surface `str(exc)` verbatim
- `"Invalid message type '<x>'. Expected one of: guild, queue, waitlist, honourary"`
- `"<Type> message rejected: invalid rank '<r>'. Expected one of: <sorted set>"`
- `"Inbound processing is disabled"`, `"Server busy"`
- ``"Authentication required: send an `auth` frame first."``
- `"Your tier (<tier>) is not permitted to send '<type>' messages."`
- `"auth rejected: <reason>"`
- Staff-action: `"This frame is staff-only and your session is not confirmed-staff."`, `"missing 'target_username'"`, `"ejects require a 'message'"`, `"staff-action dispatcher not configured"`, `"session missing actor identity"`
- Anni: `"mc_uuid required"`, `"poller disabled"`, `"unreachable"`, `"auth required"`, `"anni snapshot integration disabled"`, `'notice must be "hard", "soft", or "revoke"'`, `"scroll_spot must be null or {x:int, y:int, z:int}"`, `"party_member_usernames must be a list of strings"`

## 5. Timing / TTLs

| Cache | TTL | Source |
|-------|-----|--------|
| Supporter username | 1 hour | `SUPPORTERS_CACHE_TTL_SECONDS` |
| Guild/staff username | 12 hours | `STAFF_USERNAME_CACHE_TTL_SECONDS` / `ROSTER_USERNAME_CACHE_TTL_SECONDS` |
| Recently-seen users | 30 seconds | Disconnect grace |
| Tablist staleness | 60 seconds | Client last-update |
| Dedup window (`DEDUP_WINDOW_SECONDS`) | 35 seconds | Per-message |
| Dedup alias (`DEDUP_ALIAS_TTL_SECONDS`) | 30 seconds | Nickname resolver |
| Anni query cache | 15 seconds | `ANNI_QUERY_CACHE_MAX_AGE_SECONDS` |

The username TTLs are **caller-supplied**, not properties of the cache — the
module's own default is the 1-hour supporter value, and the 12-hour figure
comes from the roster callers passing it.

## 6. Polling intervals

| Task | Interval |
|------|----------|
| StaffPoller (roster refresh) | 5 min |
| StaffPoller (per-player probe) | 10 sec |
| GuildRosterPoller | 5 min |
| GlintedPoller / DonorPoolPoller | 5 min |
| WorldEventsPoller | 2 min |
| AnniSnapshotPoller | 10 sec in the T-2h…T+30m hot window, else 5 min |
| Dedup cleanup | 60 sec, amortized onto the next call |
