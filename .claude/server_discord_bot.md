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

`create_discord_client` in [app/discord/bot.py](../../temporary-server/app/discord/bot.py).

Enables intents: `message_content`, `members`, `guilds` and **`presences`** (the last also requires the Presence Intent toggle in the developer portal). Registers `on_ready()` (loads initial state), `on_message()` (routes) and `on_presence_update()` (the magbot probe, §15). `run_discord_client()` starts non-blocking via asyncio.

`on_ready` also runs `_purge_stale_app_commands`, which wipes leftover slash commands from older bot versions by syncing an intentionally-empty `CommandTree` globally and per-guild. A module-level flag keeps it to once per process across reconnects. This bot registers **no** application commands of its own — everything is `!`-prefix text.

## 2. Channel IDs

All in [app/constants.py](../../temporary-server/app/constants.py), and all
hardcoded — changing one needs a redeploy.

- `DISCORD_CHANNEL_ID` — the bridge channel. `_handle_message` returns early
  for any other channel, and `BridgeSender` is constructed against it.
- `RETURN_CHANNEL_ID` — the bot caches the latest non-bot post here into
  `state.latest_return_message`; source for `/v1/outbound/return`.
- `STAMP_CHANNEL_ID` — source for `/v1/outbound/stamp`.
- `WEBHOOK_USER_ID` — a **user** ID, paired with the stamp channel: only posts
  in that channel *by that author* update the timestamp.
- `MAGBOT_USER_ID` — also a user ID, and **not a bridge endpoint at all**: it
  is a probe target for the health check in §15.

## 3. Role → rank mapping

`ROLE_MAPPING`, in [app/constants.py](../../temporary-server/app/constants.py) —
**7 entries**, mapping Discord role IDs to raw Wynn rank
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

`on_message` is a thin event closure; all routing lives in `_handle_message`
([app/discord/bot.py](../../temporary-server/app/discord/bot.py)), in this
branch order:

1. **Stamp channel webhook posts**: update `state.latest_webhook_timestamp` and persist it via `webhook_timestamp_store`. This sits **before** the bot-author filter deliberately — the stamp poster is itself a bot and would otherwise be dropped.
2. **Bot authors ignored**: skip to prevent echo loops
3. **Return channel posts**: cache content + metadata to `state.latest_return_message`
4. **Non-bridge channels**: return (nothing else to do)
5. **Bridge channel:**
   - Try the command dispatcher first (`try_handle_command()`) — if handled, return. Note this consumes the *public* `!list` from anyone, not just admins (§6)
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
Discord (§8) substitutes the display label directly
into the visible `**[prefix]**`, so a Chief shows in Discord as `[Steward]`.

## 5. On-ready initial state loading

`_load_initial_state`, run from `on_ready`
([app/discord/bot.py](../../temporary-server/app/discord/bot.py)):

- Return channel: fetch latest non-bot message, cache content+timestamp
- Stamp channel: scan newest-first over a 6-day window for posts by `WEBHOOK_USER_ID`, extract the `<t:...>` Discord timestamp, cache as `latest_webhook_timestamp`. A persisted value that is already newer wins over the scan.

`_extract_timestamp_from_message` looks in three places in order: raw message content, every text-bearing embed slot, then every Components V2 text slot.

## 6. Admin commands

[app/discord/commands.py](../../temporary-server/app/discord/commands.py)

Dispatcher: `try_handle_command()`. It consults the public table **first**, with
no permission check at all, and only on a miss requires
`guild_permissions.administrator` before looking the name up in the admin table.

- Public (no auth): `!list`, `!list staff` — the *only* public command
- Admin (Administrator permission): the nine below

A non-admin who types an admin command gets **silent non-handling**, not an
error: the permission check returns False, so the message falls through and is
relayed into game chat as ordinary bridge text. That includes `!help`, which
is itself admin-only.

⚠️ `commands.py`'s own module docstring is titled "Administrator commands" and
omits `!list` from its list — reading it alone gives the wrong answer, and is
the likely source of the docs that called `!list` an admin command. `_cmd_help`
is the accurate inventory: it lists `!list` first and annotates it
*(available to everyone)*.

There are **9 admin commands**: `!help`, `!status`, `!enable`, `!disable`,
`!motd`, `!guild_motd`, `!record`, `!config`, `!noaspects`. Sub-forms
(`!list staff`, `!motd clear`, `!noaspects add|remove|list`) are argument
branches inside handlers, not separate registrations.

### `!status`
Show components and their enabled/disabled state (✅/❌).

### `!enable <component>` / `!disable <component>`
Toggleable components: `inbound`, `outbound`, `staff`, `bridge`, `unauth`.
- `inbound` disabled → reject chat messages from clients. Every control frame still works — not just `auth`/`register`/`tablist`/`queue_status` but `rank_change` and the five staff-action frames too, which are dispatched above the disabled gate
- `outbound` disabled → drop messages from broadcast queue
- `staff` disabled → skip staff roster polling
- `bridge` disabled → skip Discord relay of game messages
- `unauth` disabled → reject chat from unauthenticated WS sessions and skip them in broadcast (default enabled during alpha; controls the migration cutover)

### `!motd [text]`
No args → show current. `!motd clear` → reset to default. With text → update; persisted via `update_config()` atomic write.

### `!guild_motd [text]`
Same for guild-specific MOTD.

### `!record`
Start 120-second traffic recording (captures all inbound/outbound WS frames). Fire-and-forget background task. Result DM'd as JSON to requester.

### `!config`
Show MOTD, guild MOTD, donator count, component enabled/disabled.

### `!help`
List all commands with descriptions. Admin-only despite being a help command.

### `!noaspects [add|remove|list]`
Manages the NoAspects opt-out list consumed by vetsmod's `/wv distribute`,
persisted through `no_aspects_store` and mirrored into `state.no_aspects_uuids`
/ `state.no_aspects_entries`. Serves `GET /v1/outbound/no-aspects`.

`add`/`remove` resolve their argument via `_resolve_to_uuid`, which tries, in
order: direct UUID parse, case-insensitive live guild-roster scan, stale-name
alias lookup, then a Mojang API fallback. The `list` renderer marks entries
whose UUID has left the guild as `(left guild)`.

## 7. Public commands

### `!list` (default)
Show guild members (online), honourary, waitlist.
- Merges 3 sources: Wynncraft API online + vetsmod-connected + tab-list hints
- Includes recently-seen grace period (30s after disconnect)
- Staff marked with underscores `__name__`
- Sorted by tier then name

Helper `_fetch_guild_online_members()`: synchronous Wynncraft API fetch, extracts online members from rank sections.

### `!list staff`
Show only staff grouped by rank.

## 8. Game → Discord relay

`relay_to_bridge_channel` in [app/discord/relay.py](../../temporary-server/app/discord/relay.py):

1. Display prefix — when `rank` is non-empty, `RANK_DISPLAY.get(rank.lower(), rank)`, so a Chief renders as `[Steward]`; only when `rank` is empty does it fall back to the message type (`_TYPE_PREFIXES`: guild→Guild, **queue→Guild**, waitlist→Waitlist, honourary→Honourary — `queue` shares Guild's label because from Discord's side the two are indistinguishable)
2. Decode PUA spoilers back to `||text||`
3. Staff alert detection: if `_is_staff_sender` **and** the message starts with `‼` (U+203C), strip the marker, suppress the plain-text relay entirely, and enqueue a red `Alert` embed instead
4. Format: `**[Prefix]** username: message`
5. Enqueue to `BridgeSender` (§8a), which sends with `AllowedMentions.none()`

`relay_to_bridge_channel` is **synchronous and non-blocking** — it formats and hands off, returning immediately, and no-ops when `state.bridge_sender` is None. Called from `app/chat/inbound.py` when type != "bridge" and bridge is not disabled.

A second staff-only embed path exists: `_ENCOURAGE_PATTERN` matches vetsmod's whole triple-warning `/encourage` template and replaces it with a blurple *Vetsmod* info embed carrying a version footer. The regex is fully anchored so a mid-sentence quote of the text does not trigger it.

Relay also carries the PUA item-render path: when `state.pua_renderer` is configured and `_extract_pua_substrings` finds Wynncraft item encodings, it spawns a background `pua-render` task and returns, so the inbound WS handler is never blocked on the sidecar round-trip.

### 8a. BridgeSender

All bridge-channel traffic — chat relay and staff alerts alike — goes through one `BridgeSender` (`app/discord/bridge_sender.py`), a bounded queue drained by a single worker task named `bridge-sender`. It exists to prevent 429 storms: one outstanding `channel.send` at a time, so discord.py's internal retry/backoff never fans out across dozens of concurrent tasks and starves the event loop.

- Enqueue API: `enqueue_text`, `enqueue_embed`, `enqueue_files`, all non-blocking. Only text is coalesced; embeds and files are sent alone.
- Under backpressure it drops the **oldest text** first, deliberately preserving embeds — a dropped chat line costs less than a dropped staff alert.
- Constructed in `create_app` against `DISCORD_CHANNEL_ID`; `start()` is called in the lifespan immediately after the bot task, i.e. *before* login completes. That is safe: the worker's channel resolve returns None until the client is ready and it retries, so pre-login items are held rather than lost.

**Gap:** the queue cap, pacing interval and per-send timeout are constants in `bridge_sender.py` and are not restated here.

## 9. Mention resolution

`resolve_mentions`, the only public function in [app/discord/utils.py](../../temporary-server/app/discord/utils.py):
- `<@ID>` → `@member.display_name` (or global username; or `@unknown-user`)
- `<@&roleID>` → `@role.name` (or `@unknown-role`)
- `<#chanID>` → `#channel.name` (or `#unknown-channel`)
- `@everyone` / `@here` → neutralized form (text-only, no ping)

## 10. Admin-only sanitization

Normal users: strip `§` (Minecraft formatting codes) + U+E014–E025 PUA + U+E080.
Admins (Administrator permission): only strip U+E080. This lets admins intentionally send formatted/PUA content through the bridge.

## 11. Traffic recording

[app/services/recorder.py](../../temporary-server/app/services/recorder.py):

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

⚠️ `server.log`, `debug/`, `venv/` and `__pycache__/` all hold **stale copies**
of server values. When checking a fact against temporary-server, exclude them —
confirming a constant from a `.pyc` or an old log is how several of the errors
this doc used to carry got confirmed rather than caught.

## 15. Magbot health probe

Magbot is a third-party Discord bot the team does not control, tracked solely
so an external uptime monitor can tell whether it is up.

- `MAGBOT_USER_ID` is a **user** ID, not a channel — the one ID in
  `constants.py` that is a probe target rather than a bridge endpoint.
- `on_presence_update` early-returns unless the updated member is that user,
  then writes the status string into `state.magbot_status`.
- `_seed_magbot_status` back-fills that value from the cached member on
  `on_ready`, because `on_presence_update` fires only on *changes* — without
  the seed, a restart while Magbot was already online would leave the status
  `None` and the endpoint would report down.
- `GET /magbot-health` (`app/routes/magbot_health.py`) 503s when the status is
  `None`, `offline` or `invisible`, so a monitor reads it as down. The path is
  deliberately **not** `/health`, which is reserved for a future nazbot
  self-readiness probe.
