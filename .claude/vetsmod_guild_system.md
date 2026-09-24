---
name: vetsmod Guild / Unlock / Staff-Rank System
description: GuildStateManager facade — GuildChecker (/gu stats, 3-day TTL), StaffRankChecker (/gu rank, 24h TTL), UnlockManager (vetsmod /unlock <key> bearer auth), SessionAuthWarning
type: project
originSessionId: dc63f47a-2d15-4f8d-9b6a-41d3049f0cc2
---
# vetsmod Guild State System

`GuildStateManager` is the central facade. It owns four collaborators: `GuildChecker` (guild membership), `StaffRankChecker` (captain+ detection), `UnlockManager` (bearer-key auth + legacy unlock markers), `SessionAuthWarning` (per-session nag for unauthenticated users).

## 1. GuildStateManager facade

[GuildStateManager](../src/client/java/org/wynnvets/guild/GuildStateManager.java)

Key public methods (all package-level or client-facing):

| Method | Purpose |
|--------|---------|
| `isReturners()` | Check if in Returners guild |
| `isGuildless()` | Check player not in any guild |
| `isUnlocked()` | Any unlock (Returners or waitlist/honourary auth) |
| `isWaitlistUnlocked()` / `isHonouraryUnlocked()` | Delegated to UnlockManager — true when auth-frame succeeded with that tier OR legacy SHA-256 marker still present |
| `isAuthenticatedThisSession()` | True from an ok auth-frame ack until an auth failure, a newly stored key, or a disconnect clears it |
| `hasStoredAuthKey()` | True when `vetsAuthKey` is non-empty (regardless of validation) |
| `hasLegacyPasswordUnlock()` | True if any pre-migration `vets*UnlockTime` marker exists |
| `tryUnlock(key)` | Store + dispatch auth frame for a `/unlock` key |
| `onAuthSuccess(tier)` / `onAuthFailure(detail)` | Auth-frame ack callbacks (called by V1ApiManager) |
| `isStaff()` | Delegated to StaffRankChecker |
| `areFeaturesEnabled()` | Features gated to Returners only |
| `canExecuteCommands()` | World entered at least once |
| `playerName()` | Local player username |
| `selfStaffRank()` | "captain"/"strategist"/"chief"/"owner" |
| `loadPersistedState()` | Restore from `VetsConfig` on startup |
| `onEnteredWorld()` | World-join trigger; schedules SessionAuthWarning |
| `onGuildInfoUpdated()` | Wynntils `GuildEvent` callback; also called by the post-world-join recheck poll |
| `forceGuildRecheck()` | Debug forced recheck |
| `refreshStaffStatusIfNeeded(forceRefresh)` | Staff rank refresh |
| `sendRegistrationIfReady()` | Push presence frame on inbound WS |

### Guild membership flow on world join

`isReturners()` / `isGuildless()` prefer a valid `GuildChecker` result (the persisted `/gu stats` cache, 3-day expiry), else read Wynntils' `Models.Guild` live (the name is empty, not null, until Wynntils' character-info scan or a guild-join message fills it). `onEnteredWorld()` then schedules two follow-ups:
1. a `/gu stats` check 5 s after world join, while `MORE_RELIABLE_GUILD_CHECK` is on (the default);
2. when Wynntils reports no guild yet, the recheck poll (`GUILD_RECHECK_*` constants), which calls `onGuildInfoUpdated()` once a guild appears.

`onGuildInfoUpdated()` invalidates the `GuildChecker` cache. It runs on Wynntils' `GuildEvent.Joined` / `.Left` and from that recheck poll, where no event fired (bug `guild-recheck-poll-clears-gu-stats-cache`).

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
- Suppression grace: 2 seconds after completion

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

On disconnect, `reset()` reloads persisted state from config (not in-memory clear).

## 4. UnlockManager (key-based, post-migration)

[UnlockManager](../src/client/java/org/wynnvets/guild/UnlockManager.java)

**Purpose:** Manage the player's vetsmod auth state. Owns the persisted bearer key and the transient session-auth flags.

The legacy SHA-256 password matching has been removed. The two legacy markers (`vetsWaitlistUnlockTime`, `vetsHonouraryUnlockTime`: long timestamps from the old unlock, not hashes) are meant to survive on disk *only* as a "this user used the old system" signal for the session-start warning copy, and no longer grant access. **Today they still do**: `isWaitlistUnlocked()` / `isHonouraryUnlocked()` each OR their own positive marker in and nothing clears them, so a pre-migration user stays client-side unlocked (bug `legacy-unlock-markers-still-grant-client-unlock`). The §1 table and the bullets below describe the code as it is.

**Constants:**
- `MIN_KEY_LENGTH = 32`, `MAX_KEY_LENGTH = 200`
- Canonical tier strings: `member`, `waitlist`, `honourary`, `other` (kept in sync with dazebot's `lib/staff/verify_keys.py`)

**Persisted state (string/long via VetsConfig):**
- `vetsAuthKey` — the bearer key stored on `/unlock <key>`
- `vetsAuthTier` — the tier the last successful auth ack reported. Written by `V1ApiManager`'s auth-ack handler and never cleared, so after a new key or a rejection it can belong to an earlier key; read only by the diagnostics dump
- `vetsAuthVerifiedAt` — epoch millis of the last successful auth-frame ack; written by `V1ApiManager`, display-only (diagnostics)

**Transient session state (volatile):**
- `currentTier` — populated by the server's auth-frame ack
- `authVerifiedThisSession` — boolean set true by each ok auth ack; cleared by an auth failure, a newly stored key, or a disconnect
- `lastAuthFailureReason` — set by `onAuthFailure`: an auth rejection, or another error ack `V1ApiManager` classes as an auth failure, such as an "Authentication required" refusal of a non-auth frame. Cleared by a later ok auth ack, a newly stored key, or a disconnect. Used by SessionAuthWarning (case 2)

**Public API (package-level):**
- `tryUnlock(key)` returns `GuildStateManager.UnlockAttemptResult` (`MISSING_KEY` / `MALFORMED` / `STORED_VERIFYING`). Stores the key, clears stale tier state, and calls `V1ApiManager.sendAuth(key)`, which sends the auth frame now if the inbound WS is up or otherwise leaves it to go out on the next (re)connect.
- `onAuthSuccess(tier)` / `onAuthFailure(detail)` — reached from `V1ApiManager`'s inbound message handler; the direct caller is `GuildStateManager.onAuthSuccess` / `.onAuthFailure`, which delegate here when the auth ack arrives.
- `isWaitlistUnlocked()` / `isHonouraryUnlocked()` — true when (auth verified + matching tier this session) OR (legacy marker present; see the paragraph above).
- `legacyWaitlistMarker()` / `legacyHonouraryMarker()` — raw read of the pre-migration timestamps.
- `loadPersistedState()`, `reset()`.

**Debug override:** `setDebugForceGuildlessUnlocked(boolean)` — forces guildless+unlocked for testing.

## 5. SessionAuthWarning

[SessionAuthWarning](../src/client/java/org/wynnvets/guild/SessionAuthWarning.java)

**Purpose:** Once-per-session chat warning when the player's auth state diverges from what they probably expect.

**Cases:**
1. Authenticated this session → silent.
2. Stored key but server rejected → "Your stored vetsmod key was rejected (<reason>). Run ~vetsmod in #bot-commands to issue a new one." (red). Today this fires whenever a stored key has no ok ack 5 s after world join, rejected or not (bug `session-auth-warning-reports-rejection-without-one`).
3. Plausible VETS user (in Returners or has legacy unlock marker) with no stored key:
   - if server's `unauth_enabled=true` → "vetsmod is running unauthenticated. Vets chat still works for now, but authentication will become mandatory soon. Run ~vetsmod in #bot-commands to /unlock." (yellow)
   - if server's `unauth_enabled=false` → "You aren't authenticated, so vetsmod cannot send or receive VETS chat or use guild-specific features until you /unlock. Run ~vetsmod in #bot-commands to get a key." (red)
4. Plain non-VETS user → silent.

**`unauth_enabled` discovery:** The temporary-server pushes a `{type:"server_info", unauth_enabled: bool}` frame on outbound connect. `V1ApiManager` routes it directly to `SessionAuthWarning.onServerInfo()`. Defaults to `true` if the frame hasn't arrived yet (warning fires 5s after world join).

**Discord URL** (`https://wynnvets.org/discord`) is rendered as a clickable link via `ClickEvent.OpenUrl`.

## 6. Tier-to-protocol mapping

| Vetsmod tier (`vetsAuthTier`) | WS register `tier` / `ws_tier` | What grants it |
|-------------------------------|-------------------------------|----------------|
| `member` | `guild` | Returners role + linked MC account in dazebot |
| `waitlist` | `waitlist` | WAITLISTED Discord role or row in dazebot's Waitlist table |
| `honourary` | `honourary` | HONOURARY Discord role |
| `other` | _(none — chat-channel access denied)_ | Anything else (linked but no role match, or blocklisted) |

The tier is **resolved server-side**: temporary-server introspects the key against dazebot, whose `resolve_tier()` (`lib/staff/verify_keys.py`) re-derives it from the member's live roles on each introspection, falling back to the tier stored with the key when the member can't be looked up. Tier changes propagate to active sessions on the next WS reconnect (or whenever temporary-server's 60s LRU cache expires the cached introspection).

## 7. Persistence summary

All state persists in `vetsmod/storage/config.json` under the player's Minecraft game directory (resolved at runtime via `FabricLoader.getInstance().getGameDir()`). It's not part of the source tree.

| Key | Type | Managed by |
|-----|------|------------|
| `vetsGuildCheckResult` | long (enum) | GuildChecker |
| `vetsLastGuildCheck` | long (ts) | GuildChecker |
| `vetsIsStaff` | bool | StaffRankChecker |
| `vetsLastStaffCheck` | long (ts) | StaffRankChecker |
| `vetsAuthKey` | string | UnlockManager |
| `vetsAuthTier` | string | V1ApiManager (auth-ack handler) |
| `vetsAuthVerifiedAt` | long (ts) | V1ApiManager (auth-ack handler) |
| `vetsWaitlistUnlockTime` | long (ts) | **legacy** — meant only as a "previously unlocked under SHA-256 system" marker for warnings; today it still unlocks (§4) |
| `vetsHonouraryUnlockTime` | long (ts) | same — legacy marker; same caveat |

## 8. Edge cases

- **Returners guild members need to /unlock** under the new system once the server's `unauth` toggle is off. While it is on, unauthenticated sessions are exempt from the tier gate (back-compat); when it is off, their chat frames are rejected. Authenticated sessions can only send the chat types their tier allows. Pre-migration users discover this via the SessionAuthWarning.
- **`forceGuildRecheck()`** from `/wv debug trigger forceChecks` clears **neither** cache — it prints diagnostics and re-runs both checks (`refreshStaffStatusIfNeeded(true)` and `GuildChecker.refreshGuildStatus()`). Not clearing is deliberate and commented twice in `GuildStateManager`: `GuildChecker` is cleared only by `onGuildInfoUpdated()`, which fires from Wynntils' `GuildEvent.Joined`/`.Left` handlers and from the post-world-join recheck poll once guild info turns up. It also does *not* re-auth; that happens automatically on every inbound WS reconnect.
- **Wynntils `GuildEvent`** is authoritative — when it fires, `GuildChecker` cache is invalidated.
- **`/gu stats` vs Wynntils** — a valid `/gu stats` result (`GuildChecker`) takes precedence; Wynntils' `Models.Guild` is the live fallback while there is none. Wynntils usually lands first in time. `MORE_RELIABLE_GUILD_CHECK` schedules the `/gu stats` check that refreshes the cache.
- **Rotating a leaked key:** a staff member runs dazebot's staff-only `/change rotate key <target>` (users cannot self-rotate); the old key fails introspection on the next WS connect (subject to temporary-server's 60 s introspection cache). The mod surfaces the failure via `onAuthFailure()` and the SessionAuthWarning prompts them to `/unlock` again.
