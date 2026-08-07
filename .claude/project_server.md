---
name: temporary-server Architecture
description: FastAPI Python backend — WebSocket inbound/outbound, Discord bridge, dedup engine, REST endpoints, admin commands
type: project
---

**Stack:** Python 3.12 (the Dockerfile is `FROM python:3.12-slim`), FastAPI 0.115.0, Uvicorn, `discord.py>=2.7.1,<3`, PyYAML, httpx. Docker container, port 8000. discord.py is a floating range, not a pin, and the floor is load-bearing — `bot.py` reads Components V2 types (`discord.TextDisplay`, `discord.Container`, `discord.SectionComponent`) that older 2.x lacks.

**Entry point:** `server.py` → creates FastAPI app, runs uvicorn. App factory in `app/__init__.py` with async lifespan context manager.

**Three pillars:**
1. `/v1/inbound` WebSocket — receives messages from mod clients, validates/deduplicates/relays. Inbound chat types: `guild`, `queue`, `waitlist`, `honourary` (`queue` covers guild messages from a sender stuck in a Wynncraft world queue — see `v1_protocol.md` for why). Accepts control frames `register`, `tablist`, `queue_status`, `auth`.
2. `/v1/outbound` WebSocket — pushes all processed messages to all connected clients (FIFO queue, broadcast). Outbound types add `bridge` (Discord relay). Pushes a `server_info` hello frame on connect; accepts `auth` frames from the client.
3. Discord bot — bridges game↔Discord, handles admin commands (incl. `!disable unauth` toggle).
4. (auxiliary) HTTP introspection client → dazebot's `POST /api/auth/introspect` validates each `auth` frame's key. 60s LRU cache, serve-stale-on-error during dazebot outages.

**Key file structure:**
```
app/
  __init__.py          # App factory, lifespan, background task startup, AuthProvider install
  constants.py         # All magic numbers: Discord IDs, timing, API URLs
  auth/
    provider.py        # AuthProvider abstraction; NoOpAuthProvider + DazebotIntrospectionProvider (LRU + httpx)
  chat/
    inbound.py         # /v1/inbound WS — register/tablist/queue_status/auth control frames + chat pipeline + tier gate
    outbound.py        # /v1/outbound WS — server_info hello + auth frame + tier-filtered broadcast
    processing.py      # validate_inbound(), sanitize_inbound(), transform_inbound()
  config/loader.py     # config.yml I/O + env overrides, atomic writes
  discord/
    bot.py             # discord.py client, on_message routing
    commands.py        # Admin commands (!disable, !motd, !record, etc.) — incl. `unauth` toggle
    relay.py           # Game↔Discord message forwarding
    utils.py           # Mention resolution
  parsers/spoiler_codec.py  # ||spoiler|| ↔ PUA encoding (matches vetsmod SpoilerCodec)
  routes/static.py     # REST endpoints
  services/
    state.py           # AppState dataclass (incl. authenticated_sessions: dict[ws -> {disc_uuid, mc_uuid, mc_username, tier, ws_tier}])
    dedup.py           # Guild message deduplication engine
    staff_poller.py    # Wynncraft API staff polling (5min full, 10s per-player probe)
    guild_roster_poller.py  # Guild UUID→current-username resolution (Minecraft Services API)
    recorder.py        # Traffic capture for debugging
    username_cache.py  # Minecraft Services API username lookup + cache
```

**REST endpoints:**
- `GET /v1/outbound/motd` — MOTD text
- `GET /v1/outbound/guild_motd` — alternate staff-editable MOTD
- `GET /v1/outbound/staff` — online staff sorted by rank priority
- `GET /v1/outbound/supporters` — donator list with resolved usernames
- `GET /v1/outbound/return` — latest Discord return-channel message as MC chat component
- `GET /v1/outbound/stamp` — latest webhook timestamp
- `GET /v1/outbound/list` — connected VetsMod clients (registered presence)
- `GET /v1/outbound/roster` — guild UUID→current-username map
- `GET /v1/outbound/aliases` — stale-Wynncraft-username → current-username map (for reconciling Wynncraft API names that lag Mojang)

**Deduplication (`app/services/dedup.py`):**
- Fingerprint: `username_lower\x00message_stripped` (strips PUA glyphs)
- 35-second sliding window for exact matches (`DEDUP_WINDOW_SECONDS`)
- Also handles: prefix match (item-encoded dual events), truncation match (soft-wrap), cross-user alias (nickname vs real name)
- Alias TTL: 30s. Cleanup every 60s.

**REST endpoints have no auth** (security via WSS/TLS). The WS endpoints accept an optional `auth` frame; when the `unauth` admin toggle is enabled (default during alpha) unauthenticated chat works for back-compat. When disabled, only sessions that have completed an `auth` handshake can send/receive chat. Authenticated sessions are *also* tier-gated regardless of the `unauth` toggle.

**Required env vars for auth:** `DAZEBOT_INTROSPECT_URL` (defaults to `http://dazebot:${DAZEBOT_PORT}/api/auth/introspect` when running on the `verify` Docker network) and `DAZEBOT_INTROSPECT_SECRET` (shared 32-byte hex secret matching dazebot's `.env`). If either is missing the server falls back to `NoOpAuthProvider` and logs a warning.

**Discord admin authorization:** Administrator permission flag. Role mapping: Chief (1313778812361904188), Strategist (1313782599378010163), Captain (1337992726079213712).

**Admin commands:** `!status`, `!enable/disable <comp>`, `!motd [text]`, `!guild_motd [text]`, `!record`, `!config`, `!help`, `!list`. Components that can be toggled: `inbound`, `outbound`, `bridge`, `staff`, `unauth`.

**Config (`config.yml`):** `discord_bot_token`, `motd`, `guild_motd`, `donatorList` (UUIDs). Env vars override. Atomic writes via temp file+rename.

**Presence tracking:** `connected_users` dict (WS→{uuid,username,tier}). 30s grace period after disconnect (`recently_seen_users`). Distinct from `authenticated_sessions` (WS→{disc_uuid, mc_uuid, mc_username, tier, ws_tier}) which holds the auth-frame outcome.

**Traffic recording:** 120s capture to in-memory buffer → DM'd as JSON to Discord admin.

**Why "temporary":** The name implies this is a stopgap; the full replacement hasn't been built yet.

**How to apply:** When modifying the server, be mindful of dedup edge cases (nickname aliases, PUA stripping), the FIFO ordering guarantee on outbound, and Discord's mention-resolution/role-mapping logic.
