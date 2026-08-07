---
name: temporary-server Services Layer
description: AppState dataclass fields, StaffPoller (5min roster + 10s probe), GuildRosterPoller (5min UUID→username), username_cache (Mojang, 12h TTL), recorder (120s capture → DM)
type: project
originSessionId: dc63f47a-2d15-4f8d-9b6a-41d3049f0cc2
---
# temporary-server Services Layer

Package: `app/services/`. All services share `AppState` (passed at creation time).

## 1. AppState dataclass

`AppState` in [app/services/state.py](../../temporary-server/app/services/state.py).

Single shared-mutable container, organised into 21 commented groups. Route handlers reach it as `request.app.state.app_state`; background tasks are handed the object directly at construction.

Fields by subsystem:

**Discord channel state:**
- `latest_return_message: dict | None` — `{content, timestamp, author}` of latest return-channel post
- `latest_webhook_timestamp: str | None` — raw Discord `<t:...>` string from stamp channel

**Magbot presence:**
- `magbot_status: str | None` — drives `GET /magbot-health`; `None` until seeded on ready

**Staff roster:**
- `staff_roster_by_uuid: dict` — UUID → full detail dict (username, rank, etc.)
- `online_staff_by_uuid: dict` — subset, only online
- `staff_probe_order: list` — sorted probe list (rank priority → username → UUID)
- `staff_probe_index: int` — current round-robin position
- `staff_roster_last_updated: float` — epoch timestamp
- `staff_priority_probes: deque` — tab-list hints to probe first
- `staff_privacy_hidden_uuids: set[str]`, `staff_privacy_alerts: deque` — privacy opt-outs and their pending alerts
- `staff_wynn_name_to_uuid: dict` — stale Wynncraft username → UUID (for tab-list matching)
- `staff_roster_initialised: asyncio.Event` — lets auth wait for the first roster fetch

**Guild roster:**
- `guild_roster_by_uuid: dict` — UUID → current username (Mojang-resolved)
- `guild_roster_last_updated: float` — epoch timestamp
- `wynn_name_aliases: dict`, `wynn_aliases_last_updated: float` — backs `/v1/outbound/aliases`

**Glinted slots / donor pool:**
- `glinted_slots: list[dict | None]`, `glinted_last_updated: float | None`
- `donor_pool: list[dict]`, `donor_pool_last_updated: float | None`

**NoAspects:**
- `no_aspects_uuids: set[str]`, `no_aspects_entries: list[dict]` — hydrated from disk in `create_app`

**Outbound queue:**
- `outbound_queue: asyncio.Queue` — FIFO message distribution to outbound WS clients

**Admin toggles:**
- `disabled_components: set[str]` — **defaults to empty**; nothing is disabled at startup. The valid names are `TOGGLEABLE_COMPONENTS` in `discord/commands.py`: `inbound`, `outbound`, `staff`, `bridge`, `unauth`. ⚠️ `state.py`'s own inline comment lists only the first four and omits `unauth` — it is stale, and is the likely source of every doc that has done the same. `unauth` is enforced at three live call sites across `chat/inbound.py` and `chat/outbound.py`.

**Connected users / authenticated sessions:**
- `connected_users: dict[WebSocket, dict]` — WS → `{uuid, username, tier}`
- `authenticated_sessions: dict` — WS → `{disc_uuid, mc_uuid, mc_username, tier, ws_tier}`; **absence of a key means the connection is unauthenticated**
- `recently_seen_users: dict[str, dict]` — uuid → `{username, tier, last_seen}` for grace period
- `staff_visible_uuids: set[str]` — who the staff-presence push currently advertises

**Tab list:**
- `tablist_guild_entries: list[dict]` — entries from most recent tab-list frame
- `tablist_last_updated: float` — when last received; stale threshold 60s

**Traffic recording:**
- `recording_active: bool`
- `recording_buffer: list`
- `recording_requester_id: int | None` — Discord user ID to DM result

**Anni snapshot cache:**
- `anni_subscribed_uuids: set[str]`, `anni_snapshots_by_uuid: dict`, `anni_snapshots_fetched_at: float`, `anni_last_delta_pushed_at: dict`

**Late-bound collaborators** (all typed `object | None` to dodge circular imports, hence the `isinstance` guards at shutdown): `bridge_sender`, `rank_alert_dispatcher`, `staff_action_dispatcher`, `anni_snapshot_poller`, `pua_renderer`.

## 2. StaffPoller

[app/services/staff_poller.py](../../temporary-server/app/services/staff_poller.py)

**Run loop**: `run()` ticks every 10s, catches exceptions.

**`_tick()`**:
- Skip if `"staff"` in `disabled_components`
- Refresh full roster every 5 min
- Then probe one member

**`_refresh_roster()`** (runs in thread pool):
- Fetch guild from Wynncraft API
- Build staff roster from `STAFF_TARGET_ROLES` — owner/chief/strategist only, captain retired in the 2026-07 restructure
- Create `staff_wynn_name_to_uuid` — stale API username → UUID (for tab-list reconciliation)
- Sort probe order: rank priority → username → UUID
- Prune stale `online_staff_by_uuid` entries

**`_probe_next()`** (runs in thread pool):
- Drain `staff_priority_probes` deque first (tab-list hints from clients)
- Fall back to round-robin through `probe_order`
- GET `https://api.wynncraft.com/v3/player/{uuid}`
- If online → update `online_staff_by_uuid`, sync username (API response preferred)
- If offline → remove from `online_staff_by_uuid`

**`_build_staff_roster()`**:
- Iterates the guild API rank sections named by `STAFF_TARGET_ROLES` (owner, chief, strategist)
- Extracts UUID (tries dict key, values, nested keys — Wynncraft API is inconsistent)
- Resolves current username via `get_cached_username()` (12h TTL)
- Returns `staff_roster`, `wynn_name_to_uuid`

## 3. GuildRosterPoller

[app/services/guild_roster_poller.py](../../temporary-server/app/services/guild_roster_poller.py)

**Run loop**: refresh every 5 min.

**`_refresh_roster()`** (runs in thread pool):
- Fetch guild from Wynncraft API
- Build roster via `_build_guild_roster()` (all roles: owner → recruit)
- Update `state.guild_roster_by_uuid`

**`_build_guild_roster()`**:
- Same UUID extraction logic as StaffPoller
- Resolves each UUID via `get_cached_username()` (12h TTL)
- Returns UUID → username dict

Why separate from StaffPoller: covers all guild members (not just staff), supplies the `/v1/outbound/roster` endpoint and `OnlineMemberService` in the Minecraft client.

## 4. username_cache

[app/services/username_cache.py](../../temporary-server/app/services/username_cache.py)

Module-level `_cache: dict` (UUID → `{username, timestamp}`). Thread-safe for concurrent pollers.

**`get_cached_username(uuid, ttl_seconds)`**:
1. Normalize UUID via `_normalize_uuid()` (validate, lowercase, hyphenated)
2. Check cache; return if fresh (< TTL)
3. Fetch from Minecraft Services API outside lock
4. On success: cache with current timestamp
5. On failure: keep stale value but update timestamp (throttle retry to once per TTL)

**`_fetch_username(uuid)`**:
- GET `https://api.minecraftservices.com/minecraft/profile/lookup/{uuid_no_hyphens}`
- Returns username string or None

**`get_supporter_username(uuid)`**: convenience wrapper with 1-hour TTL.

**Standard TTL:** 12 hours for roster pollers. 1 hour for supporter lookups.

**Why stale-on-failure:** Username lookups fail intermittently. Rather than returning None and showing blank names, serve the last known name until a fresh fetch succeeds.

## 5. Recorder

[app/services/recorder.py](../../temporary-server/app/services/recorder.py)

**`_RecordingLogHandler`**: Python logging handler — when attached, all log records are appended to `state.recording_buffer` during active recording.

**`record_message(direction, data, client_version)`**: Appends timestamped entry `{ts, direction, data, version}` to buffer if `state.recording_active`.

**`start_recording(state, requester_id, discord_client)`**:
1. Reject if already recording
2. Set `state.recording_active = True`, attach log handler
3. `await asyncio.sleep(120)`
4. Snapshot buffer, reset state
5. Serialize to JSON, DM to `requester_id` via Discord
6. Remove log handler in `finally` block

Triggered by Discord admin command `!record`. Single recording at a time.

## 6. Processing pipeline (chat/processing.py)

[app/chat/processing.py](../../temporary-server/app/chat/processing.py)

**`validate_inbound(payload)`**:
- Check 6 required fields by key presence (not truthiness): uuid, type, timestamp, rank, username, message. All missing keys are collected and reported in one error.
- type in `VALID_INBOUND_TYPES` — **four** values: `guild`, `queue`, `waitlist`, `honourary`. ⚠️ `inbound.py`'s module docstring still lists only three and is stale; the constant is authoritative.
- The `rank` **key** is required for all four types, but the **value** is validated only for `guild` and `queue`. A waitlist message with `rank=""` passes.
- For those two, rank must be in `VALID_GUILD_RANKS` — **six** values, compared case-insensitively: `owner`, `chief`, `strategist`, `captain`, `recruiter`, `recruit`. (`recruit` is the one this doc used to leave as a literal `?`; it is easy to miss because it sits next to `recruiter` and reads as one item. `captain` is still valid here — see [server_discord_bot.md](server_discord_bot.md) §3 for why valid ≠ staff.)

**`sanitize_inbound(payload)`**:
- Strip C0 control chars: `[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]` (allows \t, \n, \r)
- Collapse whitespace: `" ".join(message.split())`
- Truncate to 256 chars
- Username: strip controls + PUA glyphs, strip() whitespace

**`transform_inbound(payload)`**:
- Split `username/nickname` form on `/`
- Register nickname alias in deduplicator: `dedup.register_alias(nickname, real_name)`

**`process_inbound(payload)`**: Chains validate → sanitize → transform.

## 7. App lifespan

`_lifespan` in [app/__init__.py](../../temporary-server/app/__init__.py).

**Startup** — nine background tasks, six unconditional and three env-gated.

Unconditional:
1. `run_discord_client()` — Discord event loop
2. `BridgeSender.start()` — the `bridge-sender` worker (constructed back in `create_app`)
3. `StaffPoller.run()` — Wynncraft API staff roster polling
4. `GuildRosterPoller.run()` — full guild UUID→username resolution
5. `WorldEventsPoller.run()` — Wynncraft world-events fallback for the anni stamp
6. `outbound_broadcaster()` — WS FIFO queue drainer

Env-gated:
7. `GlintedPoller.run()` — needs `DAZEBOT_BASE_URL` **and** `DAZEBOT_INTROSPECT_SECRET`
8. `DonorPoolPoller.run()` — same single gate as 7, so the pair is all-or-nothing
9. `AnniSnapshotPoller.run()` — needs `ANNI_INTROSPECT_SECRET` (base URL defaults)

⚠️ Don't re-derive this count from `app/__init__.py`'s module docstring — it still claims the lifespan starts three things and is the likely source of the "4 background tasks" this doc carried. Count the `asyncio.create_task` calls plus `BridgeSender`'s internal worker.

When the dazebot pair is disabled, `/v1/outbound/supporters` and `/v1/outbound/donor_pool` stay registered and serve empty lists — they do not 503.

**Shutdown** — deliberately ordered: cancel broadcaster → roster → world-events → the three optional pollers → staff poller → bot (last), then `await bridge_sender.stop()` for a graceful drain rather than a bare cancel, then gather with `return_exceptions=True`, then close the HTTP clients.

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

`donatorList` is still loaded, defaulted and normalised by the loader, but it
no longer feeds `/v1/outbound/supporters` — that endpoint now composes from
`state.glinted_slots`, which `GlintedPoller` fills from dazebot. The URL and
the wire format were kept; only the source of truth moved.

**Gap:** the loader's env-override table, its legacy-migration path and
`_normalize_donator_list` are not described beyond the above.

## 9. Modules this doc does not cover

`app/services/` holds 17 modules. The sections above describe five of them
(`state`, `staff_poller`, `guild_roster_poller`, `username_cache`,
`recorder`), plus the processing pipeline and config loader from sibling
packages. The rest are named here so their existence is discoverable, and
deliberately not expanded — they are server-internal, and vetsmod's behaviour
does not depend on their internals.

**Gap:** the following are named only. Read the source when you need them.

| Module | What it is |
|---|---|
| `staff_visibility.py` | `compose_online_staff` / `recompute_staff_visibility` — unions WAPI-probed and WS-authenticated staff; owns the `/v1/outbound/staff` sort |
| `staff_actions.py` | `StaffActionDispatcher` — caution/warn/eject/membership frames → dazebot's staff-action API, with `predict_trigger` for the preflight |
| `rank_alerts.py` | `RankAlertDispatcher` — `rank_change` frames, deduplicated across the N clients that saw the same broadcast |
| `glinted_poller.py` | `GlintedPoller` — dazebot's 8-slot glinted list |
| `donor_pool_poller.py` | `DonorPoolPoller` — dazebot's ranked donor candidates |
| `anni_snapshot_poller.py` | `AnniSnapshotPoller` — vets-anni MWE snapshots; also serves the `anni_*` inbound frames |
| `world_events_poller.py` | `WorldEventsPoller` — Wynncraft world-events fallback for the anni stamp |
| `no_aspects_store.py` | Disk-backed opt-out list (`load`/`add`/`remove`/`entries`) behind `!noaspects` |
| `webhook_timestamp_store.py` | Persists the anni stamp so `/v1/outbound/stamp` survives a restart |
| `pua_decoder.py` | `split_pua_items` — splits a multi-item PUA run |
| `pua_renderer.py` | `PuaRenderer` — HTTP client for the item-renderer sidecar |
| `dedup.py` | Documented separately in [server_dedup_engine.md](server_dedup_engine.md) |

## 10. Poller shape

Worth knowing before adding a seventh: **there is no shared base class.** All
six pollers are plain `class X:` with no base, no mixin, no ABC, and
`services/__init__.py` exports nothing. What they share is a copied
convention — the same hand-written `while True` / `try: await self._tick()` /
`except CancelledError: raise` / `except Exception: log` / `await
asyncio.sleep(...)` skeleton, six times over.

The convention has two dialects. The two older pollers (`StaffPoller`,
`GuildRosterPoller`) wrap blocking `urllib` calls in `asyncio.to_thread` and
duplicate a near-identical `_extract_member_uuid` helper between them — the
clearest evidence nothing was ever factored out. The four newer ones
(`Glinted`, `DonorPool`, `AnniSnapshot`, `WorldEvents`) each build a private
`httpx.AsyncClient` in `__init__` and close it in a `finally`.

`GlintedPoller` is the reference shape: both `DonorPoolPoller` and
`AnniSnapshotPoller` name it in their module docstrings as the thing they
mirror. It is *not* the most recently written one — `DonorPoolPoller` is
newer — so clone it because it is the cited template, not because it is
latest.

Only `StaffPoller` checks `disabled_components` (for `"staff"`), at tick time.
The three env-gated pollers have a startup gate instead: they are simply never
constructed when their secret is unset.
