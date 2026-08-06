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
| `isAuthenticatedThisSession()` | True after the server's auth-frame ack returned ok |
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
| `onGuildInfoUpdated()` | Wynntils `GuildEvent` callback |
| `forceGuildRecheck()` | Debug forced recheck |
| `refreshStaffStatusIfNeeded(forceRefresh)` | Staff rank refresh |
| `sendRegistrationIfReady()` | Push presence frame on inbound WS |

### Guild membership flow on world join

`onEnteredWorld()` checks in order:
1. `GuildChecker.hasValidResult()` — cached `/gu stats` result, valid 3 days
2. Wynntils `Models.Guild.getGuildName()` — live but may be null
3. If `MORE_RELIABLE_GUILD_CHECK` config enabled, schedules a `/gu stats` 5s after world join

Wynntils `GuildEvent.Joined` / `.Left` invalidates the `GuildChecker` cache (authoritative source).

## 2. GuildChecker

[GuildChecker](../src/client/java/org/wynnvets/guild/GuildChecker.java)

**Purpose:** Parse multi-line `/gu stats` response to detect guild name when Wynntils data isn't available.

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

The legacy SHA-256 password matching has been removed. The two old hashes (`vetsWaitlistUnlockTime`, `vetsHonouraryUnlockTime`) survive on disk *only* as a "this user used the old system" signal for the session-start warning copy — they no longer grant access.

**Constants:**
- `MIN_KEY_LENGTH = 32`, `MAX_KEY_LENGTH = 200`
- Canonical tier strings: `member`, `waitlist`, `honourary`, `other` (kept in sync with dazebot's `lib/verify_keys.py`)

**Persisted state (string/long via VetsConfig):**
- `vetsAuthKey` — the bearer key stored on `/unlock <key>`
- `vetsAuthTier` — last server-confirmed tier (UX hint only; authoritative value comes from the server each session)
- `vetsAuthVerifiedAt` — epoch millis of the last successful auth-frame ack

**Transient session state (volatile):**
- `currentTier` — populated by the server's auth-frame ack
- `authVerifiedThisSession` — boolean flipped true on first successful ack
- `lastAuthFailureReason` — populated on rejection, used by SessionAuthWarning

**Public API (package-level):**
- `tryUnlock(key)` returns `GuildStateManager.UnlockAttemptResult` (`MISSING_KEY` / `MALFORMED` / `STORED_VERIFYING`). Stores the key, clears stale tier state, dispatches an auth frame on the existing inbound WS via `V1ApiManager.sendAuth(key)`.
- `onAuthSuccess(tier)` / `onAuthFailure(detail)` — called by `V1ApiManager`'s inbound message handler when the auth ack arrives.
- `isWaitlistUnlocked()` / `isHonouraryUnlocked()` — true when (auth verified + matching tier this session) OR (legacy marker present, for back-compat warning logic).
- `legacyWaitlistMarker()` / `legacyHonouraryMarker()` — raw read of the pre-migration timestamps.
- `loadPersistedState()`, `reset()`.

**Debug override:** `setDebugForceGuildlessUnlocked(boolean)` — forces guildless+unlocked for testing.

## 5. SessionAuthWarning

[SessionAuthWarning](../src/client/java/org/wynnvets/guild/SessionAuthWarning.java)

**Purpose:** Once-per-session chat warning when the player's auth state diverges from what they probably expect.

**Cases:**
1. Authenticated this session → silent.
2. Stored key but server rejected → "Your stored vetsmod key was rejected (<reason>). Run /vetsmod in #bot-commands to issue a new one." (red)
3. Plausible VETS user (in Returners or has legacy unlock marker) with no stored key:
   - if server's `unauth_enabled=true` → "vetsmod is running unauthenticated. Vets chat still works for now, but authentication will become mandatory soon. Run /vetsmod in #bot-commands to /unlock." (yellow)
   - if server's `unauth_enabled=false` → "You aren't authenticated, so vetsmod cannot send or receive VETS chat or use guild-specific features until you /unlock. Run /vetsmod in #bot-commands to get a key." (red)
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

The tier is **resolved server-side** by dazebot's `lib/verify_keys.resolve_tier()` on every introspection. Tier changes propagate to active sessions on the next WS reconnect (or whenever temporary-server's 60s LRU cache expires the cached introspection).

## 7. Persistence summary

All state persists in `vetsmod/storage/config.json` under the player's Minecraft game directory (resolved at runtime via `FabricLoader.getInstance().getGameDir()`). It's not part of the source tree.

| Key | Type | Managed by |
|-----|------|------------|
| `vetsGuildCheckResult` | long (enum) | GuildChecker |
| `vetsLastGuildCheck` | long (ts) | GuildChecker |
| `vetsIsStaff` | bool | StaffRankChecker |
| `vetsLastStaffCheck` | long (ts) | StaffRankChecker |
| `vetsAuthKey` | string | UnlockManager |
| `vetsAuthTier` | string | UnlockManager |
| `vetsAuthVerifiedAt` | long (ts) | UnlockManager |
| `vetsWaitlistUnlockTime` | long (ts) | **legacy** — kept only as a "previously unlocked under SHA-256 system" marker for warnings |
| `vetsHonouraryUnlockTime` | long (ts) | same — legacy marker |
| `vetsUnlockExpiryWarnings` | long (count) | **legacy** — unused, retained for rollback |

## 8. Edge cases

- **Returners guild members still need to /unlock** under the new system. Guild detection alone no longer grants chat access — the server's tier gate enforces that authenticated users can only send chat types their tier allows. Pre-migration users discover this via the SessionAuthWarning.
- **`forceGuildRecheck()`** from `/wv debug trigger forceChecks` clears **neither** cache — it prints diagnostics and re-runs both checks (`refreshStaffStatusIfNeeded(true)` and `GuildChecker.refreshGuildStatus()`). Not clearing is deliberate and commented twice in `GuildStateManager`: `GuildChecker` is cleared only by `onGuildInfoUpdated()`, i.e. only on a Wynntils `GuildEvent`. It also does *not* re-auth; that happens automatically on every WS reconnect.
- **Wynntils `GuildEvent`** is authoritative — when it fires, `GuildChecker` cache is invalidated.
- **`/gu stats` vs Wynntils** — Wynntils data is tried first; `/gu stats` is the reliable fallback (hence `MORE_RELIABLE_GUILD_CHECK` config).
- **Rotating a leaked key:** users run `/vetsmod rotate` in Discord; their old key fails introspection on the next WS connect. The mod surfaces the failure via `onAuthFailure()` and the SessionAuthWarning prompts them to `/unlock` again.
