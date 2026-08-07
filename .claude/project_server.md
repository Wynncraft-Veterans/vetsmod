---
name: temporary-server Architecture
description: FastAPI Python backend — WebSocket inbound/outbound, Discord bridge, dedup engine, REST endpoints, admin commands
type: project
---

**Stack:** Python 3.12 (the Dockerfile is `FROM python:3.12-slim`), FastAPI 0.115.0, Uvicorn, `discord.py>=2.7.1,<3`, PyYAML, httpx. Docker container, port 8000. discord.py is a floating range, not a pin, and the floor is load-bearing — `bot.py` reads Components V2 types (`discord.TextDisplay`, `discord.Container`, `discord.SectionComponent`) that older 2.x lacks.

**Entry point:** `server.py` → creates FastAPI app, runs uvicorn. App factory in `app/__init__.py` with async lifespan context manager.

**Three pillars:**
1. `/v1/inbound` WebSocket — receives messages from mod clients, validates/deduplicates/relays. Inbound chat types: `guild`, `queue`, `waitlist`, `honourary` (`queue` covers guild messages from a sender stuck in a Wynncraft world queue — see `v1_protocol.md` for why). Accepts fourteen control frame types — `register`, `tablist`, `queue_status`, `auth`, `rank_change`, the five staff-action frames and the four MWE anni frames. Full inventory in [server_api_reference.md](server_api_reference.md).
2. `/v1/outbound` WebSocket — pushes all processed messages to all connected clients (FIFO queue, broadcast). Outbound types add `bridge` (Discord relay). Pushes a `server_info` hello frame on connect; accepts `auth` frames from the client.
3. Discord bot — bridges game↔Discord, handles admin commands (incl. `!disable unauth` toggle).
4. (auxiliary) HTTP introspection client → dazebot's `POST /api/auth/introspect` validates each `auth` frame's key. 60s LRU cache, serve-stale-on-error during dazebot outages.

**Key file structure:**
```
app/
  __init__.py          # App factory, lifespan, background task startup, AuthProvider install
  constants.py         # All magic numbers: Discord IDs, timing, API URLs, ROLE_MAPPING/RANK_DISPLAY
  auth/
    provider.py        # AuthProvider abstraction; NoOpAuthProvider + DazebotIntrospectionProvider (LRU + httpx)
  chat/
    inbound.py         # /v1/inbound WS — every control frame + chat pipeline + tier gate
    outbound.py        # /v1/outbound WS — server_info hello + auth frame + tier-filtered broadcast
    processing.py      # validate_inbound(), sanitize_inbound(), transform_inbound()
    transport.py       # receive_text_or_disconnect() — turns a dead-peer RuntimeError into WebSocketDisconnect
  config/loader.py     # config.yml I/O + env overrides, atomic writes
  discord/
    bot.py             # discord.py client, on_message routing, on_presence_update (magbot probe)
    bridge_sender.py   # BridgeSender — single-consumer queue + worker for the bridge channel
    commands.py        # 9 admin commands + the one public command, !list
    relay.py           # Game→Discord forwarding, staff alerts, PUA item render
    utils.py           # Mention resolution
  parsers/
    formatting.py      # Discord inline markdown → Minecraft chat components
    markdown.py        # Block-level markdown (headings, quotes, code) → components
    spoiler_codec.py   # ||spoiler|| ↔ PUA encoding (matches vetsmod SpoilerCodec)
  routes/
    static.py          # Both REST routers: /v1/outbound/* and the /v0/outbound/* legacy set
    anni_delta.py      # POST /api/internal/anni-snapshot-delta — the only secret-gated route
    magbot_health.py   # GET /magbot-health — 503 when magbot is offline/invisible/unknown
  services/            # 17 modules; see server_services.md
    state.py           # AppState dataclass — the single shared mutable container
    dedup.py           # Dedup engine + the `guild_deduplicator` module-level singleton
    staff_poller.py    # Wynncraft API staff polling (5min roster, 10s per-player probe)
    staff_visibility.py     # Composes WAPI-probed + WS-authenticated staff; owns the /staff sort
    staff_actions.py        # caution/warn/eject/check_membership → dazebot's staff-action API
    guild_roster_poller.py  # Guild UUID→current-username resolution (Minecraft Services API)
    username_cache.py       # Minecraft Services username lookup + TTL cache, stale-on-failure
    glinted_poller.py       # dazebot's 8-slot glinted list → /v1/outbound/supporters
    donor_pool_poller.py    # dazebot's ranked donor candidates → /v1/outbound/donor_pool
    anni_snapshot_poller.py # vets-anni MWE snapshots; also serves the anni_* inbound frames
    world_events_poller.py  # Wynncraft world-events fallback for the anni stamp
    rank_alerts.py          # rank_change frames → dazebot (ban/kick) or bridge (mote)
    no_aspects_store.py     # Disk-backed NoAspects opt-out list behind !noaspects
    webhook_timestamp_store.py  # Persists the anni stamp so it survives a restart
    pua_decoder.py          # Splits a multi-item Wynncraft PUA run into individual items
    pua_renderer.py         # HTTP client for the item-renderer sidecar (PUA → PNG card)
    recorder.py             # Traffic capture for debugging
```

`pua_decoder.py` and `pua_renderer.py` read like parsers but live in
`services/`; `parsers/` holds only the three files above.

**REST endpoints:** twelve GETs under `/v1/outbound/`, plus four `/v0/outbound/` legacy routes, `GET /magbot-health` and one secret-gated POST. Full detail in [server_api_reference.md](server_api_reference.md).

- `GET /v1/outbound/motd` — MOTD text
- `GET /v1/outbound/guild_motd` — alternate staff-editable MOTD
- `GET /v1/outbound/staff` — online staff sorted by rank priority
- `GET /v1/outbound/supporters` — glinted list with resolved usernames
- `GET /v1/outbound/donor_pool` — ranked donation-recipient candidates from dazebot
- `GET /v1/outbound/anni-snapshot` — cached MWE snapshots for eligible players (debug-only)
- `GET /v1/outbound/return` — latest Discord return-channel message as MC chat component
- `GET /v1/outbound/stamp` — latest webhook timestamp
- `GET /v1/outbound/list` — connected VetsMod clients (registered presence)
- `GET /v1/outbound/roster` — guild UUID→current-username map
- `GET /v1/outbound/aliases` — stale-Wynncraft-username → current-username map (for reconciling Wynncraft API names that lag Mojang)
- `GET /v1/outbound/no-aspects` — members opted out of aspect distribution, read by vetsmod's `NoAspectsFilter`

Naming is inconsistent in source and is not normalised here: `no-aspects` and `anni-snapshot` are hyphenated while `donor_pool` and `guild_motd` are underscored.

**Deduplication (`app/services/dedup.py`):**
- Fingerprint: `username_lower\x00message_stripped` (strips PUA glyphs)
- 35-second sliding window for exact matches (`DEDUP_WINDOW_SECONDS`)
- Also handles: prefix match (item-encoded dual events), truncation match (soft-wrap), cross-user alias (nickname vs real name)
- Alias TTL: 30s. Cleanup every 60s, amortized onto the next `is_duplicate()` call rather than scheduled — on an idle bridge it simply does not run.

**Every REST endpoint is unauthenticated except one** — `POST /api/internal/anni-snapshot-delta`, which requires a shared secret and fails closed. Security for the rest is WSS/TLS plus network placement. The WS endpoints accept an optional `auth` frame; when the `unauth` admin toggle is enabled (default during alpha) unauthenticated chat works for back-compat. When disabled, only sessions that have completed an `auth` handshake can send/receive chat. Authenticated sessions are *also* tier-gated regardless of the `unauth` toggle.

**Required env vars for auth:** `DAZEBOT_INTROSPECT_URL` (defaults to `http://dazebot:${DAZEBOT_PORT}/api/auth/introspect` when running on the `verify` Docker network) and `DAZEBOT_INTROSPECT_SECRET` (shared 32-byte hex secret matching dazebot's `.env`). If either is missing the server falls back to `NoOpAuthProvider` and logs a warning.

**Discord admin authorization:** Administrator permission flag — independent of rank. Rank itself comes from `ROLE_MAPPING` (7 role IDs → Chief / Strategist / Recruiter / Honourary / Waitlist, first match in insertion order, defaulting to Recruiter). The 2026-07 permission restructure retired the standalone Strategist and secondary Captain roles into Staff (Steward) `1337993168502788216`; staff eligibility is now `STAFF_TARGET_ROLES = ("owner", "chief", "strategist")`. See [server_discord_bot.md](server_discord_bot.md) §3 for the full table.

**Admin commands (9, Administrator permission):** `!help`, `!status`, `!enable <comp>`, `!disable <comp>`, `!motd [text]`, `!guild_motd [text]`, `!record`, `!config`, `!noaspects [add|remove|list]`. Components that can be toggled: `inbound`, `outbound`, `bridge`, `staff`, `unauth`.

**Public command (1):** `!list` (and `!list staff`) — the *only* command with no permission check. `try_handle_command` consults the public table before testing for Administrator, so `!list` is reachable by anyone in the bridge channel. A non-admin who types an admin command gets no error: the permission miss returns False and the message falls through to be relayed into game chat as ordinary bridge text.

**Config (`config.yml`):** `discord_bot_token`, `motd`, `guild_motd`, `donatorList` (UUIDs). Env vars override. Atomic writes via temp file+rename.

**Presence tracking:** `connected_users` dict (WS→{uuid,username,tier}). 30s grace period after disconnect (`recently_seen_users`). Distinct from `authenticated_sessions` (WS→{disc_uuid, mc_uuid, mc_username, tier, ws_tier, is_staff, staff_rank}) which holds the auth-frame outcome.

**Traffic recording:** 120s capture to in-memory buffer → DM'd as JSON to Discord admin.

**Why "temporary":** The name implies this is a stopgap; the full replacement hasn't been built yet.

**How to apply:** When modifying the server, be mindful of dedup edge cases (nickname aliases, PUA stripping), the FIFO ordering guarantee on outbound, and Discord's mention-resolution/role-mapping logic.
