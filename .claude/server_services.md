---
name: temporary-server Services Layer
description: AppState dataclass fields, StaffPoller (5min roster + 10s probe), GuildRosterPoller (5min UUID→username), username_cache (Mojang, 12h TTL), recorder (120s capture → DM)
type: project
originSessionId: dc63f47a-2d15-4f8d-9b6a-41d3049f0cc2
---
# temporary-server Services Layer

Package: `app/services/`. All services share `AppState` (passed at creation time).

## 1. AppState dataclass

[app/services/state.py:11-98](../../temporary-server/app/services/state.py)

Single shared-mutable container. All background tasks and route handlers access it via `request.app.state.state` (FastAPI).

Fields by subsystem:

**Discord channel state (lines 21-25):**
- `latest_return_message: dict` — `{content, timestamp, author}` of latest return-channel post
- `latest_webhook_timestamp: str` — raw Discord `<t:...>` string from stamp channel

**Staff roster (lines 27-49):**
- `staff_roster_by_uuid: dict` — UUID → full detail dict (username, rank, etc.)
- `online_staff_by_uuid: dict` — subset, only online
- `staff_probe_order: list` — sorted probe list (rank priority → username → UUID)
- `staff_probe_index: int` — current round-robin position
- `staff_roster_last_updated: float` — epoch timestamp
- `staff_priority_probes: deque` — tab-list hints to probe first
- `staff_wynn_name_to_uuid: dict` — stale Wynncraft username → UUID (for tab-list matching)

**Guild roster (lines 51-56):**
- `guild_roster_by_uuid: dict` — UUID → current username (Mojang-resolved)
- `guild_roster_last_updated: float` — epoch timestamp

**Outbound queue (lines 58-62):**
- `outbound_queue: asyncio.Queue` — FIFO message distribution to outbound WS clients

**Admin toggles (lines 64-68):**
- `disabled_components: set[str]` — names of disabled components (inbound/outbound/staff/bridge)

**Connected users (lines 70-76):**
- `connected_users: dict[WebSocket, dict]` — WS → `{uuid, username, tier}`
- `recently_seen_users: dict[str, dict]` — uuid → `{username, tier, last_seen}` for grace period

**Tab list (lines 85-90):**
- `latest_tablist: list` — entries from most recent tab-list frame
- `latest_tablist_timestamp: float` — when last received; stale threshold 60s

**Traffic recording (lines 92-97):**
- `recording_active: bool`
- `recording_buffer: list`
- `recording_requester_id: int` — Discord user ID to DM result

## 2. StaffPoller

[app/services/staff_poller.py](../../temporary-server/app/services/staff_poller.py)

**Run loop** (lines 35-56): `run()` ticks every 10s, catches exceptions.

**`_tick()`** (lines 62-83):
- Skip if `"staff"` in `disabled_components`
- Refresh full roster every 5 min
- Then probe one member

**`_refresh_roster()`** (lines 89-132, runs in thread pool):
- Fetch guild from Wynncraft API
- Build staff roster (owner/chief/strategist/captain only)
- Create `staff_wynn_name_to_uuid` — stale API username → UUID (for tab-list reconciliation)
- Sort probe order: rank priority → username → UUID
- Prune stale `online_staff_by_uuid` entries

**`_probe_next()`** (lines 134-202, runs in thread pool):
- Drain `staff_priority_probes` deque first (tab-list hints from clients)
- Fall back to round-robin through `probe_order`
- GET `https://api.wynncraft.com/v3/player/{uuid}`
- If online → update `online_staff_by_uuid`, sync username (API response preferred)
- If offline → remove from `online_staff_by_uuid`

**`_build_staff_roster()`** (lines 232-308):
- Iterates guild API rank sections (owner → captain)
- Extracts UUID (tries dict key, values, nested keys — Wynncraft API is inconsistent)
- Resolves current username via `get_cached_username()` (12h TTL)
- Returns `staff_roster`, `wynn_name_to_uuid`

## 3. GuildRosterPoller

[app/services/guild_roster_poller.py](../../temporary-server/app/services/guild_roster_poller.py)

**Run loop** (lines 35-54): refresh every 5 min.

**`_refresh_roster()`** (lines 56-77, runs in thread pool):
- Fetch guild from Wynncraft API
- Build roster via `_build_guild_roster()` (all roles: owner → recruit)
- Update `state.guild_roster_by_uuid`

**`_build_guild_roster()`** (lines 109-141):
- Same UUID extraction logic as StaffPoller
- Resolves each UUID via `get_cached_username()` (12h TTL)
- Returns UUID → username dict

Why separate from StaffPoller: covers all guild members (not just staff), supplies the `/v1/outbound/roster` endpoint and `OnlineMemberService` in the Minecraft client.

## 4. username_cache

[app/services/username_cache.py:25-105](../../temporary-server/app/services/username_cache.py)

Module-level `_cache: dict` (UUID → `{username, timestamp}`). Thread-safe for concurrent pollers.

**`get_cached_username(uuid, ttl_seconds)`** (lines 61-100):
1. Normalize UUID via `_normalize_uuid()` (validate, lowercase, hyphenated)
2. Check cache; return if fresh (< TTL)
3. Fetch from Minecraft Services API outside lock
4. On success: cache with current timestamp
5. On failure: keep stale value but update timestamp (throttle retry to once per TTL)

**`_fetch_username(uuid)`** (lines 41-54):
- GET `https://api.minecraftservices.com/minecraft/profile/lookup/{uuid_no_hyphens}`
- Returns username string or None

**`get_supporter_username(uuid)`** (lines 103-105): convenience wrapper with 1-hour TTL.

**Standard TTL:** 12 hours for roster pollers. 1 hour for supporter lookups.

**Why stale-on-failure:** Username lookups fail intermittently. Rather than returning None and showing blank names, serve the last known name until a fresh fetch succeeds.

## 5. Recorder

[app/services/recorder.py:27-160](../../temporary-server/app/services/recorder.py)

**`_RecordingLogHandler`** (lines 27-50): Python logging handler — when attached, all log records are appended to `state.recording_buffer` during active recording.

**`record_message(direction, data, client_version)`** (lines 53-79): Appends timestamped entry `{ts, direction, data, version}` to buffer if `state.recording_active`.

**`start_recording(state, requester_id, discord_client)`** (lines 82-160):
1. Reject if already recording
2. Set `state.recording_active = True`, attach log handler
3. `await asyncio.sleep(120)`
4. Snapshot buffer, reset state
5. Serialize to JSON, DM to `requester_id` via Discord
6. Remove log handler in `finally` block

Triggered by Discord admin command `!record`. Single recording at a time.

## 6. Processing pipeline (chat/processing.py)

[app/chat/processing.py](../../temporary-server/app/chat/processing.py)

**`validate_inbound(payload)`** (lines 57-87):
- Check 6 required fields: uuid, type, timestamp, rank, username, message
- type in `{guild, waitlist, honourary}`
- guild messages: rank must be in `VALID_GUILD_RANKS` (captain/strategist/chief/owner + recruiter + ?)

**`sanitize_inbound(payload)`** (lines 90-118):
- Strip C0 control chars: `[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]` (allows \t, \n, \r)
- Collapse whitespace: `" ".join(message.split())`
- Truncate to 256 chars
- Username: strip controls + PUA glyphs, strip() whitespace

**`transform_inbound(payload)`** (lines 121-139):
- Split `username/nickname` form on `/`
- Register nickname alias in deduplicator: `dedup.register_alias(nickname, real_name)`

**`process_inbound(payload)`** (lines 142-157): Chains validate → sanitize → transform.

## 7. App lifespan

[app/__init__.py:35-69](../../temporary-server/app/__init__.py)

**Startup** — 4 background tasks spawned:
1. `run_discord_client()` — Discord event loop
2. `StaffPoller.run()` — Wynncraft API staff roster polling
3. `GuildRosterPoller.run()` — Full guild UUID→username resolution
4. `outbound_broadcaster()` — WS FIFO queue drainer

**Shutdown** — tasks cancelled, exceptions awaited, Discord closed gracefully.

## 8. Config loader

[app/config/loader.py](../../temporary-server/app/config/loader.py)

Config file: `config.yml` at repo root.

```yaml
discord_bot_token: "<token>"
motd: "<minecraft-formatted string>"
guild_motd: "<optional>"
donatorList: ["uuid", ...]
```

Loading priority: env vars (`DISCORD_BOT_TOKEN`, `MOTD`) > file values > built-in defaults.

**Atomic write** (`update_config(key, value)`): Write to temp file in same dir → `os.replace()` (atomic rename). Thread-safe via `_CONFIG_WRITE_LOCK`.

Legacy: `config.json` and `motd.yml` migrated on first run to `config.yml`.
