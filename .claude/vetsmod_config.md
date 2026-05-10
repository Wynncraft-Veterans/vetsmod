---
name: vetsmod Config Reference
description: Complete VetsConfig reference — every key (user-facing and internal), defaults, validation, persistence; JSON at vetsmod/storage/config.json
type: project
originSessionId: dc63f47a-2d15-4f8d-9b6a-41d3049f0cc2
---
# vetsmod Config Reference

Single-file JSON config at `vetsmod/storage/config.json` (resolved via `FabricLoader.getInstance().getGameDir()`).

Implementation: [src/client/java/org/wynnvets/config/VetsConfig.java](src/client/java/org/wynnvets/config/VetsConfig.java) (~563 lines).

Four type-distinct backing maps: boolean, long, string, tri-state (Boolean-or-null). All coexist in one flat JSON object — no nesting, no version field, no migration logic.

## 1. Internal state keys (not user-facing, cache state only)

| Key | Type | Purpose |
|-----|------|---------|
| `vetsAutomessage` | bool | Global gate for auto-messages (MOTD, ANNI) |
| `vetsIsStaff` | bool | Cached staff status |
| `vetsLastStaffCheck` | long | Timestamp of last `/gu rank` check |
| `vetsAuthKey` | string | Bearer key from `/unlock <key>` (43-char base64url issued by dazebot's `/vetsmod`). Sent in `auth` frame on every WS reconnect. |
| `vetsAuthTier` | string | Last server-confirmed tier (`member`/`waitlist`/`honourary`/`other`). UX hint only — authoritative value comes from the server each session. |
| `vetsAuthVerifiedAt` | long | Epoch millis of the last successful auth-frame ack. |
| `vetsWaitlistUnlockTime` | long | **legacy SHA-256 unlock marker** — kept solely as a "this user used the old system" signal for the SessionAuthWarning. No longer grants access. |
| `vetsHonouraryUnlockTime` | long | Same — legacy marker, no longer grants access. |
| `vetsUnlockExpiryWarnings` | long | **legacy** — unused after the migration; retained only so a rollback would not need a schema bump. |
| `vetsGuildCheckResult` | long | Cached `/gu stats` result enum (0=UNKNOWN,1=RETURNERS,2=OTHER_GUILD,3=GUILDLESS) |
| `vetsLastGuildCheck` | long | Timestamp of last `/gu stats` (3-day TTL) |
| `vetsDebugEnabledAt` | long | Debug logging enable timestamp (3-day TTL) |

## 2. User-facing keys (USER_CONFIG_KEYS array, settable via /wv config)

### Booleans
| Key | Default | Purpose |
|-----|---------|---------|
| `legacyItemHighlighting` | true | Show legacy/enchanted/junk highlighting + tooltip rewrite |
| `printMOTD` | true | Auto-print MOTD on world join |
| `printANNI` | true | Auto-print annihilation timer on world join |
| `printBridgeMessages` | true | Display bridge (Discord relay) messages |
| `showSupporterGlints` | true | Animated gradient glints on nametags + pills |
| `moreReliableGuildCheck` | true | Run `/gu stats` on world join for guild detection |

### Strings (colour names or sprite names)
| Key | Default | Valid values |
|-----|---------|--------------|
| `legacyItemBackgroundGradientTop` | `orange` | CSS/Minecraft colour names (see `NamedColor.COLORS`) |
| `legacyItemBackgroundGradientBottom` | `crimson` | CSS/Minecraft colour names |
| `legacyItemForegroundColor` | `orange` | CSS/Minecraft colour names |
| `legacyItemForegroundSprite` | `box_gradient_2` | `wynn`, `tag`, `circle_transparent`, `circle_opaque`, `circle_outline_large`, `circle_outline_small`, `box_transparent`, `box_opaque`, `box_gradient_1`, `box_gradient_2` |

### Integers (0–100 opacity)
| Key | Default |
|-----|---------|
| `legacyItemBackgroundGradientTopOpacity` | 69 |
| `legacyItemBackgroundGradientBottomOpacity` | 100 |

### Tri-state (null = default, true, false)
| Key | Default |
|-----|---------|
| `handleSpoilers` | null (treated as on) |

Default 69 for top opacity approximates the old `0xB0` alpha byte.

## 3. API (public static methods)

All methods keyed by string. Returns false when validation fails.

| Method | Lines |
|--------|-------|
| `get(key)` / `set(key,value)` | 276-294 (boolean) |
| `getLong(key)` / `setLong(key,value)` | 327-345 |
| `getTriState(key)` / `setTriState(key,value)` | 302-319 |
| `getString(key)` / `setString(key,value)` | 430-446 |
| `hasKey(key)` | 367 |
| `isUserConfigKey(key)` | 371 |
| `isTriStateKey(key)` / `isStringKey(key)` / `isIntKey(key)` | 384, 398, 412 |
| `getIntDefault(key)` / `getStringDefault(key)` | from INT_DEFAULTS/STRING_DEFAULTS maps |
| `registerDefault(key, defaultValue)` | 357 (for subsystems) |
| `resetToDefaults()` | 460 (saves current in-memory; does NOT clear) |
| `load()` | 468 |
| `save()` | 534 |
| `isValidColor(name)` | delegated to `NamedColor.COLORS` |
| `isValidSprite(name)` | delegated to sprite enum |
| `getColorNames()` | for command suggestion |

## 4. USER_CONFIG_KEYS order (for /wv config listing)

Defined at [VetsConfig.java:109-123](src/client/java/org/wynnvets/config/VetsConfig.java#L109-L123).

Approximate order (check the source for authoritative):
1. `legacyItemHighlighting`
2. `legacyItemBackgroundGradientTop`
3. `legacyItemBackgroundGradientTopOpacity`
4. `legacyItemBackgroundGradientBottom`
5. `legacyItemBackgroundGradientBottomOpacity`
6. `legacyItemForegroundSprite`
7. `legacyItemForegroundColor`
8. `printMOTD`
9. `printANNI`
10. `printBridgeMessages`
11. `showSupporterGlints`
12. `handleSpoilers`
13. `moreReliableGuildCheck`

## 5. Static defaults

INT_DEFAULTS (line 205): opacity values.
STRING_DEFAULTS (line 220): colour + sprite.
Booleans default in static initializer (lines 236-268) — all true except timestamp/count longs (0L).
HANDLE_SPOILERS default: null.

## 6. Persistence

JSON pretty-printed via GSON. Parent dir created if missing. Missing keys use in-memory defaults. IO exceptions logged as `warn`.

**Type strictness on load:** Only correctly-typed JSON values are accepted — a string where a boolean is expected is ignored.

**Tri-state serialization:** Omitted from JSON when null; present as JSON bool when true/false.

## 7. Colour resolution

[src/client/java/org/wynnvets/config/NamedColor.java](src/client/java/org/wynnvets/config/NamedColor.java) — `NamedColor.COLORS` map contains:
- Minecraft formatting codes (dark_red, red, gold, yellow, dark_green, green, aqua, dark_aqua, blue, dark_blue, light_purple, dark_purple, white, gray, dark_gray, black)
- CSS-ish colours (crimson, orange, etc.)
- Wynncraft rarity colours
- Custom `legacy_orange` = `0xF0501E`
- `transparent`

[src/client/java/org/wynnvets/config/LegacyItemStyle.java](src/client/java/org/wynnvets/config/LegacyItemStyle.java) reads config + NamedColor to produce ARGB int values with opacity packed into alpha byte.

## 8. Related config-adjacent state

**Debug config** — separate system at [src/client/java/org/wynnvets/debug/DebugConfigManager.java:15-46](src/client/java/org/wynnvets/debug/DebugConfigManager.java#L15-L46) (not merged into VetsConfig). Current debug keys: `itemDump` (bool) — when true, numpad `+` while hovering item dumps full Component tree JSON to `vetsmod/dumps/`.

Must call `DebugConfigManager.init()` before `VetsConfig.load()` (they share the same storage location).

## 9. Quirks / gotchas

- **No migration:** Adding new keys requires shipping with a sensible default; old configs just gain the new key on next save.
- **`resetToDefaults()` is misleading:** it saves current in-memory state, not reset in-memory values. Actual reset requires deleting the config file.
- **Tri-state `handleSpoilers`:** null/default means "on", and the user can explicitly set false to disable.
- **Integer opacity 0-100:** stored as long in JSON; multiplied by 2.55 and packed into ARGB alpha byte.
- **String validation:** `isValidColor()` / `isValidSprite()` accept lowercase names; keys should match exactly (case-sensitive in JSON).
