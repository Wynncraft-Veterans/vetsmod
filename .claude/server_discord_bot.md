---
name: temporary-server Discord Bot
description: Discord bot integration — bridge channel routing, role→rank mapping, admin commands (!status/!enable/!disable/!motd/!record/!config/!help), public commands (!list), staff alerts, mention resolution
type: project
originSessionId: dc63f47a-2d15-4f8d-9b6a-41d3049f0cc2
---
# temporary-server Discord Bot

Lives in [app/discord/](../../temporary-server/app/discord/). Runs as an async background task started by `app/__init__.py` lifespan.

**Library:** `discord.py>=2.7.1,<3` (a range in `requirements.txt`, with no lockfile — the resolved version floats at image build). The `2.7.1` floor is load-bearing: `bot.py` uses the Components V2 types `discord.TextDisplay`, `discord.Container`, `discord.SectionComponent` and `discord.LabelComponent`, and reads `message.flags.components_v2`.

## 1. Client setup

[app/discord/bot.py:35-62](../../temporary-server/app/discord/bot.py)

Enables intents: `message_content`, `members`, `guilds`. Registers `on_ready()` (loads initial state) and `on_message()` (routes). `run_discord_client()` starts non-blocking via asyncio.

## 2. Channel IDs (app/constants.py:12-15)

- Bridge channel — main chat bridge
- Return channel — `/v1/outbound/return` source
- Stamp channel — `/v1/outbound/stamp` source (webhook user)
- Webhook user — specific Discord user ID posting stamps

## 3. Role → rank mapping (constants.py:21-25)

`ROLE_MAPPING` — **7 entries**, mapping Discord role IDs to raw Wynn rank
names. Insertion order encodes priority: for an author holding several mapped
roles, the first match wins.

| Discord role ID | Rank | Note |
|-----------------|------|------|
| 1313778812361904188 | Chief | Chief Steward — active Owner-role holders |
| 1453417318443913226 | Strategist | inactive owner |
| 1412980079373320192 | Strategist | inactive founders |
| 1337993168502788216 | Strategist | Staff (Steward) |
| 1407078065137254563 | Recruiter | |
| 1450046372853055498 | Honourary | |
| 1474854046685855795 | Waitlist | |

Three distinct role IDs collapse to `Strategist`. Default (none of the above)
→ `"Recruiter"`, which is **not** a dict default — the variable is pre-seeded
in `_handle_message` before the priority loop, so `constants.py` has no
fallback to find.

**The 2026-07 permission restructure.** The standalone Strategist role and the
secondary Captain role were retired and their members merged into the Staff
(Steward) role `1337993168502788216`. The two retired IDs survive only as
prose in the comment above `ROLE_MAPPING` — neither is a named constant or a
dict key, so there is no `RETIRED_*` symbol to look for. The two
inactive-honour roles were demoted from Chief to Strategist in the same
change, since their bearers are honoured but no longer wield chief-tier
authority.

`1337993168502788216` is dual-purpose: it is both a `ROLE_MAPPING` key and
the value of `STAFF_PRIVACY_ALERT_ROLE_ID`. Same role, two jobs.

**`RANK_DISPLAY`** maps raw Wynn ranks to client-facing labels — 6 entries:
`recruit`→Recruit, `recruiter`→Returner, **`captain`→Returner** ("stray
captains treated as non-staff Returners"), `strategist`/`chief`/`owner`→Steward.
There is no `honourary` or `waitlist` key, so those fall through to the raw
value via `.get(raw, raw)`.

⚠️ Three captain facts coexist and collapsing them is the trap: the Discord
**Captain role** is retired; the Wynn **rank** `captain` is still in
`VALID_GUILD_RANKS` and is accepted on the wire; and a captain is **not
staff**. `inbound.py`'s own gate comment calls its captain check
"belt-and-suspenders", which is the cleanest framing of the split.

## 4. Message routing

[app/discord/bot.py:125-219](../../temporary-server/app/discord/bot.py) — `on_message()`:

1. **Stamp channel webhook posts** (lines 135-139): Update `state.latest_webhook_timestamp`
2. **Bot authors ignored** (lines 142-143): Skip to prevent echo loops
3. **Return channel posts** (lines 146-152): Cache content + metadata to `state.latest_return_message`
4. **Non-bridge channels** (lines 155+): Return (nothing else to do)
5. **Bridge channel:**
   - Check admin commands first (`try_handle_command()`) — if handled, return
   - Check `"bridge"` in disabled components → skip
   - Resolve rank from author's role IDs via `ROLE_MAPPING` (first match in
     insertion order; `"Recruiter"` if none match)
   - Check Administrator permission for `is_admin`
   - Resolve mentions (`<@ID>` → `@name`, `<@&ID>` → `@role`, `<#ID>` → `#channel`)
   - Sanitize: always strip U+E080 (dangerous PUA); non-admins also strip U+E014–E025 and `§`
   - Encode Discord `||spoilers||` to PUA via `encode_spoilers()`
   - Build the `type="bridge"` frame: `uuid` (uuid4), `type`, `timestamp`,
     `rank`, `pill_display`, `username` (the author's `display_name`),
     `message`, `is_admin`, `source` (`"discord"`)
   - Record traffic, enqueue to outbound broadcaster

**`pill_display` is an additive 2026-07 field**, computed as
`RANK_DISPLAY.get(rank.lower(), rank)` beside the raw `rank`. Old vetsmod
clients (0.14.x and earlier) ignore unknown keys and keep reading `rank`; new
clients (0.15.0+) prefer the display label so Strategist/Chief/Owner all
collapse to "Steward" in the in-game pill. The raw field is never removed —
that is what makes the change additive rather than breaking.

Note the two directions apply `RANK_DISPLAY` differently. Discord → game
carries **both** `rank` and `pill_display` and lets the client choose; game →
Discord ([§8](#8-game--discord-relay)) substitutes the display label directly
into the visible `**[prefix]**`, so a Chief shows in Discord as `[Steward]`.

## 5. On-ready initial state loading

[bot.py:69-119](../../temporary-server/app/discord/bot.py):

- Return channel: fetch latest non-bot message, cache content+timestamp
- Stamp channel: scan last 100 messages from webhook user (ID 1396669909077070007), extract `<t:...>` Discord timestamp, cache as `latest_webhook_timestamp`

## 6. Admin commands

[app/discord/commands.py](../../temporary-server/app/discord/commands.py)

Dispatcher: `try_handle_command()` at [commands.py:78-130](../../temporary-server/app/discord/commands.py).
- Public (no auth): `!list`, `!list staff`
- Admin (Administrator permission): all others

### `!status` (137-153)
Show components and their enabled/disabled state (✅/❌).

### `!enable <component>` / `!disable <component>` (156-191)
Toggleable components: `inbound`, `outbound`, `staff`, `bridge`, `unauth`.
- `inbound` disabled → reject chat messages from clients (control frames `auth`/`register`/`tablist`/`queue_status` still work)
- `outbound` disabled → drop messages from broadcast queue
- `staff` disabled → skip staff roster polling
- `bridge` disabled → skip Discord relay of game messages
- `unauth` disabled → reject chat from unauthenticated WS sessions and skip them in broadcast (default enabled during alpha; controls the migration cutover)

### `!motd [text]` (194-218)
No args → show current. `!motd clear` → reset to default. With text → update; persisted via `update_config()` atomic write.

### `!guild_motd [text]` (221-247)
Same for guild-specific MOTD.

### `!record` (250-261)
Start 120-second traffic recording (captures all inbound/outbound WS frames). Fire-and-forget background task. Result DM'd as JSON to requester.

### `!config` (264-289)
Show MOTD, guild MOTD, donator count, component enabled/disabled.

### `!help` (292-318)
List all commands with descriptions.

## 7. Public commands

### `!list` (default, lines 380-512)
Show guild members (online), honourary, waitlist.
- Merges 3 sources: Wynncraft API online + vetsmod-connected + tab-list hints
- Includes recently-seen grace period (30s after disconnect)
- Staff marked with underscores `__name__`
- Sorted by tier then name

Helper `_fetch_guild_online_members()` (343-377): synchronous Wynncraft API fetch, extracts online members from rank sections.

### `!list staff`
Show only staff grouped by rank.

## 8. Game → Discord relay

[app/discord/relay.py:42-85](../../temporary-server/app/discord/relay.py): `relay_to_bridge_channel()`:

1. Check client ready, resolve channel
2. Display prefix = rank if set, else message type (Guild/Waitlist/Honourary)
3. Decode PUA spoilers back to `||text||`
4. Staff alert detection (lines 64-76): If sender in staff roster AND message starts with `‼` (U+203C), send as red embed
5. Format: `**[Prefix]** username: message`
6. Send with `AllowedMentions.none()` — no unintended pings

Called from `app/chat/inbound.py` as fire-and-forget when type != "bridge" and bridge not disabled.

## 9. Mention resolution

[app/discord/utils.py:18-70](../../temporary-server/app/discord/utils.py): `resolve_mentions()`:
- `<@ID>` → `@member.display_name` (or global username; or `@unknown-user`)
- `<@&roleID>` → `@role.name` (or `@unknown-role`)
- `<#chanID>` → `#channel.name` (or `#unknown-channel`)
- `@everyone` / `@here` → neutralized form (text-only, no ping)

## 10. Admin-only sanitization

Normal users: strip `§` (Minecraft formatting codes) + U+E014–E025 PUA + U+E080.
Admins (Administrator permission): only strip U+E080. This lets admins intentionally send formatted/PUA content through the bridge.

## 11. Traffic recording

[app/services/recorder.py:27-160](../../temporary-server/app/services/recorder.py):

- `_RecordingLogHandler` — logging handler that appends to buffer
- `record_message(direction, data, client_version)` — called on every WS frame when `state.recording_active`
- `start_recording()` — set active flag, sleep 120s, serialize buffer to JSON, DM to requester, reset

Requester is tracked via `state.recording_requester_id`. Single recording at a time (second `!record` rejected).

## 12. Spoiler codec interop

[app/parsers/spoiler_codec.py](../../temporary-server/app/parsers/spoiler_codec.py) — must match [vetsmod SpoilerCodec](../src/client/java/org/wynnvets/chat/spoiler/SpoilerCodec.java). Same encoding scheme (PUA `\uF600` start, `\uF601` end, `\uF602-\uF6FF` direct, `\uF700` + 3 base-254 digits escape).

Discord → game: `encode_spoilers(||text||)` → PUA block + `[Spoiler: ]` wrapper.
Game → Discord: `decode_spoilers(PUA block)` → `||text||`.

## 13. Changing Discord config

- **Token:** `DISCORD_BOT_TOKEN` env var (preferred) or `discord_bot_token` in `config.yml`. Env wins.
- **Role IDs / channel IDs:** Hardcoded in `constants.py`; require redeploy.
- **MOTD / guild_motd:** Via `!motd` / `!guild_motd` command — persisted via `update_config()` atomic write.
- **Donator list:** `donatorList` in `config.yml` (list of UUIDs). No admin command to modify.

## 14. Testing / debugging

- `!record` captures 120s of WS traffic for debugging
- Logs at INFO level to stdout (uvicorn captures)
- `discord.*` logger muted at INFO level (too noisy)
- `server.log` file captures all output
- `debug/` directory contains recorded traffic samples for replay testing
