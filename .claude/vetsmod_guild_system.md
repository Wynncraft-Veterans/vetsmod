---
name: vetsmod Guild / Unlock / Staff-Rank System
description: GuildStateManager facade and GuildLifecycle (world join, disconnect reset) — GuildChecker (/gu stats, 3-day TTL), StaffRankChecker (/gu rank, 24h TTL), UnlockManager (vetsmod /unlock <key> bearer auth), SessionAuthWarning
type: project
originSessionId: dc63f47a-2d15-4f8d-9b6a-41d3049f0cc2
---
# vetsmod Guild State System

`GuildStateManager` is the central facade for the player's guild, staff and unlock status, over three collaborators: `GuildChecker` (guild membership), `StaffRankChecker` (captain+ detection) and `UnlockManager` (bearer-key auth). `GuildLifecycle` runs the world-join flow and the disconnect reset: it calls the three directly, schedules `SessionAuthWarning` (per-session nag for unauthenticated users), and starts `WorldJoinAnnouncer`'s MOTD and anni-stamp prints.

## 1. GuildStateManager facade

[GuildStateManager](../src/client/java/org/wynnvets/guild/GuildStateManager.java)

Key public methods (all package-level or client-facing):

| Method | Purpose |
|--------|---------|
| `isReturners()` | Check if in Returners guild |
| `isGuildless()` | Check player not in any guild |
| `isUnlocked()` | Any unlock (Returners or waitlist/honourary auth) |
| `isWaitlistUnlocked()` / `isHonouraryUnlocked()` | Delegated to UnlockManager — true when the auth frame succeeded with that tier this session |
| `isAuthenticatedThisSession()` | True from an ok auth-frame ack until an auth failure, a newly stored key, or a disconnect clears it |
| `hasStoredAuthKey()` | True when `vetsAuthKey` is non-empty (regardless of validation) |
| `tryUnlock(key)` | Store + dispatch auth frame for a `/unlock` key |
| `onAuthSuccess(tier)` / `onAuthFailure(detail)` | Auth-frame ack callbacks (called by InboundAckRouter) |
| `isStaff()` | Delegated to StaffRankChecker |
| `areFeaturesEnabled()` | Features gated to Returners only |
| `canExecuteCommands()` | World entered at least once |
| `playerName()` | Local player username |
| `selfStaffRank()` | "captain"/"strategist"/"chief"/"owner" |
| `loadPersistedState()` | Restore from `VetsConfig` on startup |
| `refreshStaffStatusIfNeeded(forceRefresh)` | Staff rank refresh |
| `sendRegistrationIfReady()` | Push presence frame on inbound WS |

The table omits the command gates `isConfirmedStaff()`, `isStaffOfAnyGuild()` and `isChiefOfAnyGuild()`;
[vetsmod_commands.md](vetsmod_commands.md) §1's gate table describes them with the other command gates.

[GuildLifecycle](../src/client/java/org/wynnvets/guild/GuildLifecycle.java) holds the world-join flow:

| Method | Purpose |
|--------|---------|
| `onEnteredWorld()` | World-join trigger; schedules SessionAuthWarning |
| `onGuildInfoUpdated()` | Wynntils `GuildEvent.Left` callback and the first half of `onGuildJoined()`: clears the `GuildChecker` cache, then re-evaluates guild state (the post-world-join recheck poll re-evaluates without the unconditional clear) |
| `onGuildJoined()` | Wynntils `GuildEvent.Joined` callback: `onGuildInfoUpdated()`, then, if the guild is Returners, `ReturnersReauth.onJoinedReturners()` (§4) |
| `reset()` | Disconnect reset of the per-connection state |

`/wv debug trigger forceChecks`'s readout is [GuildDiagnostics](../src/client/java/org/wynnvets/debug/diagnostics/GuildDiagnostics.java)`.forceGuildRecheck()`, in `org.wynnvets.debug.diagnostics`.

### Guild membership flow on world join

`isReturners()` / `isGuildless()` prefer a valid `GuildChecker` result (the persisted `/gu stats` cache, 3-day expiry), else read Wynntils' `Models.Guild` live (the name is empty, not null, until Wynntils' character-info scan or a guild-join message fills it). `GuildLifecycle.onEnteredWorld()` then schedules two follow-ups:
1. a `/gu stats` check about 5 s after world join, while `MORE_RELIABLE_GUILD_CHECK` is on (the default);
2. when Wynntils reports no guild yet, the recheck poll (`GUILD_RECHECK_*` constants), which re-evaluates guild state once a guild appears.

Each kind is numbered when scheduled, and a task that finds a newer number stops, so a quick world hop runs only the latest join's check and poll.

`GuildLifecycle.onGuildInfoUpdated()` invalidates the `GuildChecker` cache. It runs on Wynntils' `GuildEvent.Joined` / `.Left`. The recheck poll, where no event fired, clears it only when it contradicts Wynntils' guild data (`GuildChecker.clearResultIfContradicted`, through `GuildCheckResult.contradicts`), so a `/gu stats` answer that landed before Wynntils' scan survives, and one left from a guild change while disconnected does not.

## 2. GuildChecker

[GuildChecker](../src/client/java/org/wynnvets/guild/GuildChecker.java)

**Purpose:** Parse the multi-line `/gu stats` response into a cached guild result. While valid (3 days) it takes precedence over Wynntils' `Models.Guild` in `isReturners()` / `isGuildless()`; it runs after world join while `moreReliableGuildCheck` is on (the default).

**Result enum:**
| Value | persistedValue |
|-------|----------------|
| UNKNOWN | 0 |
| RETURNERS | 1 |
| OTHER_GUILD | 2 |
| GUILDLESS | 3 |

**Parsing approach:**
- Sends `/gu stats` via `Handlers.Command.queueCommand()`
- Listens for response lines, stores last non-empty candidate
- Detects the line starting with `"Guild Since:"` to confirm candidate guild name
- Detects guildless message: "you must be in a guild"
- Known output lines: `Guild Since:`, `Owner:`, `Guild Level:`, `Needed XP:`, `Guild Rank:`, `Total Members:`
- Suppresses responses during 2s grace period after completion

**Caching:**
- `GUILD_CHECK_EXPIRY_DAYS = 3`
- Persisted: `VETS_GUILD_CHECK_RESULT` (long) and `VETS_LAST_GUILD_CHECK` (long timestamp)
- `GUILD_CHECK_TIMEOUT_MS = 10_000L`
- Suppression grace: 2 seconds after completion; a new check started inside it ends it, so the old grace's pending clear can't end the new check's suppression

## 3. StaffRankChecker

[StaffRankChecker](../src/client/java/org/wynnvets/guild/StaffRankChecker.java)

**Purpose:** Detect captain+ rank via `/gu rank` command parsing.

**Parsing:**
- Sends `/gu rank` (no args) via `Handlers.Command.queueCommand()`
- Unauthorized response: "you must be a captain to use this command" → NOT staff
- Authorized response: "invalid arguments, try: rank [name] [rank]" → IS staff
- `STAFF_RANK_TIMEOUT_MS = 10_000L`

**Caching:**
- `STAFF_CHECK_COOLDOWN_MS = 24 * 60 * 60 * 1000` (24 hours)
- Persisted: `VETS_IS_STAFF` (boolean), `VETS_LAST_STAFF_CHECK` (long)
- Grace: 500ms after check to suppress trailing output
- A check that times out keeps the cached flag and still stamps `VETS_LAST_STAFF_CHECK`, so a slow command queue does not demote a real staff member and a reply nothing recognises is not re-requested at every world join
- The flag is not cleared on leaving the guild or on another guild's `OTHER_GUILD` result, so it can be true for a former member; `/wv list world` therefore also requires `GuildStateManager.isReturners()`
- Debug line: while a check waits, `processMessage` logs each chat line it sees (`Staff rank check saw reply line: [...]`). A player not in a guild gets a reply that neither predicate matches, so a debug log of such a check records the wording

On disconnect, `reset()` reloads persisted state from config (not in-memory clear).

## 4. UnlockManager (key-based, post-migration)

[UnlockManager](../src/client/java/org/wynnvets/guild/UnlockManager.java)

**Purpose:** Manage the player's vetsmod auth state. Owns the persisted bearer key and the transient session-auth flags.

The legacy SHA-256 password matching has been removed, and so have its two on-disk markers (`vetsWaitlistUnlockTime`, `vetsHonouraryUnlockTime`), so a pre-migration user with no key is a keyless player like any other. `VetsConfig.load` reads only the keys it knows, so an old `config.json`'s markers are ignored and dropped at the next save.

**Constants:**
- `MIN_KEY_LENGTH = 32`, `MAX_KEY_LENGTH = 200`
- Canonical tier strings: `member`, `waitlist`, `honourary`, `other` (kept in sync with dazebot's `lib/staff/verify_keys.py`)

**Persisted state (string/long via VetsConfig):**
- `vetsAuthKey` — the bearer key stored on `/unlock <key>`
- `vetsAuthTier` — the tier the last successful auth ack reported. Written by `InboundAckRouter` when it routes an ok auth ack, and never cleared, so after a new key or a rejection it can belong to an earlier key; read only by the diagnostics dump
- `vetsAuthVerifiedAt` — epoch millis of the last successful auth-frame ack; written by `InboundAckRouter`, display-only (diagnostics)

**Transient session state (volatile):**
- `currentTier` — populated by the server's auth-frame ack
- `authVerifiedThisSession` — boolean set true by each ok auth ack; cleared by an auth failure, a newly stored key, or a disconnect
- `lastAuthFailureReason` — set by `onAuthFailure`: an auth rejection, or another error ack `InboundAckRouter` classes as an auth failure, such as an "Authentication required" refusal of a non-auth frame. Cleared by a later ok auth ack, a newly stored key, or a disconnect. Used by SessionAuthWarning (case 2)
- `keyRejectedByAuthAck` — set by `onAuthFailure` when the detail starts with `auth rejected` (temporary-server's reply to a bad key), false for any other failure and for a quiet re-auth's transient one (below); cleared with `lastAuthFailureReason`. Used by SessionAuthWarning (case 2) to tell a rejected key from an unverified one

**Public API (package-level):**
- `tryUnlock(key)` returns `GuildStateManager.UnlockAttemptResult` (`MISSING_KEY` / `MALFORMED` / `STORED_VERIFYING`). Stores the key, clears stale tier state, and calls `V1ApiManager.sendAuth(key)`, which sends the auth frame now if the inbound WS is up or otherwise leaves it to go out on the next (re)connect.
- `onAuthSuccess(tier)` / `onAuthFailure(detail)` — reached from `InboundAckRouter.route`, which `V1ApiManager`'s inbound handler calls for every untyped frame; the direct caller is `GuildStateManager.onAuthSuccess` / `.onAuthFailure`, which delegate here when the auth ack arrives.
- `reauth(quiet)` — re-sends the stored key's auth frame while the inbound WS is up, for `ReturnersReauth` (below). With `quiet`, the ack's failure is logged only, without a chat line, the `printSuccessfulAuth` re-arm or a key rejection, when it is transient (`ReturnersReauth.isTransient`: temporary-server couldn't reach or read dazebot's introspection); a definitive rejection still prints. It is never set while an `/unlock` result is pending, and a newly stored key and either ack clear it.
- `isWaitlistUnlocked()` / `isHonouraryUnlocked()` — true when auth verified + matching tier this session.
- `loadPersistedState()`, `reset()`.

**What the player sees.** `onAuthSuccess` records the verdict on the thread the ack arrived on, then hands the rest to the client thread in one task (`showAuthSuccess`): it shows `Authentication verified - tier: <tier>` (green) when there is a local player and either `tryUnlock` has armed its one-shot `pendingUnlockNotify` flag since the last ack (a disconnect does not clear it, so a key typed off Wynncraft still shows its result at the next Wynncraft join), or `printSuccessfulAuth` is on, and clears the one-shot flag only when it shows the line; then it refreshes the presence registration and re-applies the default anni mode (`AnniModeManager.applyStartupDefaultIfNeeded`). A line that answers `/unlock` always goes to chat, as the failure line does: on Wynncraft the action bar is redrawn with the health and mana bar at once, so a toast there is unreadable. A config-driven line goes to chat while a screen is open and otherwise to the action bar. [vetsmod_config.md](vetsmod_config.md)'s `printSuccessfulAuth` row has how that setting latches. `onAuthFailure` prints a red `Authentication failed: <reason>.` line in chat, also from the client thread, which suggests the player try `~vetsmod` in #bot-commands for a new key; the one exception is a quiet re-auth's transient failure, below.

**Re-auth on joining Returners.** temporary-server fixes a session's tier when it handles the auth frame, which vetsmod sends when the inbound WS (re)connects, so a waitlister who joins Returners mid-session would keep the waitlist tier server-side until the next connection, and the server would refuse their world-queue `/g` (a refusal the relay reports with a red "Not delivered" line, [vetsmod_commands.md](vetsmod_commands.md) §2). [ReturnersReauth](../src/client/java/org/wynnvets/guild/ReturnersReauth.java) closes that gap: on a Returners `GuildEvent.Joined`, `GuildLifecycle.onGuildJoined()` has it re-send the frame through `reauth`, once at once (quiet) and once `RETRY_DELAY_MS` (330 s) later, past the slow end of dazebot's 2 to 5 minute role-sync loops, since dazebot re-resolves the tier from Discord roles on each auth. Each attempt sends only while there is a stored, unrejected key, the inbound WS is up, the player has entered a world and the tier is not `member`; the retry also needs `isReturners()` still. temporary-server overwrites the session on a good frame and drops it on a failed one, so only the immediate attempt is quiet: the retry reports any failure through the usual line. A success is an ordinary ack: no extra chat line unless `printSuccessfulAuth`'s latch or `pendingUnlockNotify` asks for one, the `Auth verified by server: tier=<tier>` log line, and the registration refresh. `GuildLifecycle.reset()` bumps `ReturnersReauth`'s generation, so a retry scheduled before a disconnect does nothing. SessionAuthWarning, latched once per session, is not re-run. The join also opens an in-progress window that lasts until 30 s after the retry runs (`ReturnersReauth.isInProgress`; `reset()` closes it), which the `/gu invite` gate reads through `GuildStateManager.isReturnersReauthDue` to tell an authenticated non-member to retry in a moment rather than that vetsmod is running unauthenticated.

## 5. SessionAuthWarning

[SessionAuthWarning](../src/client/java/org/wynnvets/guild/SessionAuthWarning.java)

**Purpose:** Once-per-session chat warning when the player's auth state diverges from what they probably expect.

**Cases:**
1. Authenticated this session → silent.
2. Stored key, not verified about 5 s after world join:
   - an auth reply rejected it (`keyRejectedByAuthAck`) → "Your stored vetsmod key was rejected (<reason>). Run ~vetsmod in #bot-commands to issue a new one." (red)
   - the inbound socket is down → "Couldn't verify your vetsmod key: Unable to reach the vets API, which may be down." (yellow, no Discord link)
   - connected, no verdict yet → silent; the auth reply prints its own line when it lands
3. In Returners, with no stored key:
   - if server's `unauth_enabled=true` → "vetsmod is running unauthenticated. Vets chat still works for now, but authentication will become mandatory soon. Run ~vetsmod in #bot-commands to /unlock." (yellow)
   - if server's `unauth_enabled=false` → "You aren't authenticated. Vetsmod cannot send or receive VETS chat or use guild-specific features until you /unlock. Run ~vetsmod in #bot-commands to get a key." (red)
4. Not in Returners, with no stored key → silent.

The clickable aqua `[https://wynnvets.org/discord]` suffix is built by the public `SessionAuthWarning.withDiscordLink(text, color)` (its `DISCORD_INVITE_URL` is public too); `InviteGate` reuses it for its unauthenticated line.

**`unauth_enabled` discovery:** The temporary-server pushes a `{type:"server_info", unauth_enabled: bool}` frame on outbound connect. `V1ApiManager` routes it directly to `SessionAuthWarning.onServerInfo()`. Defaults to `true` if the frame hasn't arrived yet (warning fires about 5 s after world join).

**Discord URL** (`https://wynnvets.org/discord`) is rendered as a clickable link via `ClickEvent.OpenUrl`.

## 6. Tier-to-protocol mapping

| Vetsmod tier (`vetsAuthTier`) | WS register `tier` / `ws_tier` | What grants it |
|-------------------------------|-------------------------------|----------------|
| `member` | `guild` | Returners role + linked MC account in dazebot |
| `waitlist` | `waitlist` | WAITLISTED Discord role or row in dazebot's Waitlist table |
| `honourary` | `honourary` | HONOURARY Discord role |
| `other` | _(none — chat-channel access denied)_ | Anything else (linked but no role match, or blocklisted) |

The tier is **resolved server-side**: temporary-server introspects the key against dazebot, whose `resolve_tier()` (`lib/staff/verify_keys.py`) re-derives it from the member's live roles on each introspection, falling back to the tier stored with the key when the member can't be looked up. A tier change reaches a session only at its next `auth` frame (the next inbound WS reconnect, or a new `/unlock`), and only once temporary-server's 60s LRU cache no longer holds the old introspection (it can serve a stale entry longer while dazebot is unreachable); cache expiry by itself changes nothing for a session that is already authenticated.

## 7. Persistence summary

All state persists in `vetsmod/storage/config.json` under the player's Minecraft game directory (resolved at runtime via `FabricLoader.getInstance().getGameDir()`). It's not part of the source tree.

| Key | Type | Managed by |
|-----|------|------------|
| `vetsGuildCheckResult` | long (enum) | GuildChecker |
| `vetsLastGuildCheck` | long (ts) | GuildChecker |
| `vetsIsStaff` | bool | StaffRankChecker |
| `vetsLastStaffCheck` | long (ts) | StaffRankChecker |
| `vetsAuthKey` | string | UnlockManager |
| `vetsAuthTier` | string | InboundAckRouter |
| `vetsAuthVerifiedAt` | long (ts) | InboundAckRouter |

## 8. Edge cases

- **Returners guild members need to /unlock** under the new system once the server's `unauth` toggle is off. While it is on, unauthenticated sessions are exempt from the tier gate (back-compat); when it is off, their chat frames are rejected. Authenticated sessions can only send the chat types their tier allows. Pre-migration users discover this via the SessionAuthWarning.
- **`GuildDiagnostics.forceGuildRecheck()`** from `/wv debug trigger forceChecks` clears **neither** cache — it prints diagnostics and re-runs both checks (`refreshStaffStatusIfNeeded(true)` and `GuildChecker.refreshGuildStatus()`). Not clearing is deliberate and commented twice in `GuildDiagnostics`: `GuildChecker` is cleared by `onGuildInfoUpdated()`, which fires from Wynntils' `GuildEvent.Joined`/`.Left` handlers, and by the post-world-join recheck poll only when its result contradicts Wynntils. It also does *not* re-auth; that happens automatically on every inbound WS reconnect.
- **Wynntils `GuildEvent`** is authoritative — when it fires, `GuildChecker` cache is invalidated.
- **`/gu stats` vs Wynntils** — a valid `/gu stats` result (`GuildChecker`) takes precedence; Wynntils' `Models.Guild` is the live fallback while there is none. Wynntils usually lands first in time. `MORE_RELIABLE_GUILD_CHECK` schedules the `/gu stats` check that refreshes the cache.
- **Rotating a leaked key:** a staff member runs dazebot's staff-only `/change rotate key <target>` (users cannot self-rotate); the old key fails introspection on the next WS connect (subject to temporary-server's 60 s introspection cache). The mod surfaces the failure via `onAuthFailure()` and the SessionAuthWarning prompts them to `/unlock` again.
