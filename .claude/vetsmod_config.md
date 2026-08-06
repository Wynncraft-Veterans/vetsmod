---
name: vetsmod Config Reference
description: Complete VetsConfig reference — every key (user-facing and internal), defaults, validation, persistence; JSON at vetsmod/storage/config.json
type: project
originSessionId: dc63f47a-2d15-4f8d-9b6a-41d3049f0cc2
---
# vetsmod Config Reference

Single-file JSON config at `vetsmod/storage/config.json` (resolved via `FabricLoader.getInstance().getGameDir()`).

Implementation: [VetsConfig](../src/client/java/org/wynnvets/config/VetsConfig.java).

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
| `legacyItemShowEnchantments` | true | Draw the LEGACY ENCHANTMENTS block naming the specific enchantment on items the enchant branch already highlights. No effect unless `legacyItemHighlighting` is on. |
| `printMOTD` | true | Auto-print MOTD on world join |
| `printANNI` | true | Auto-print annihilation timer on world join |
| `vetsAnniEnabled` | false | Master MWE/anni toggle — the kill switch for the snapshot-driven `/wv anni` renderer, anni-motd, boss bar (S3+), outlines (S4+). Auto-set to `true` on the first auth ack whose tier ∈ {member, waitlist, honourary}; non-vets users can opt in manually. |
| `vetsAnniShowHoverDetails` | true | Populate descriptive hover tooltips on `/wv anni` and motd widgets (role chips, RSVP badge, attendance bar, party world chip). When off, lines render with no hover but keep click-to-open URLs. |
| `vetsAnniPromptRsvp` | true | Show the RSVP / registration nag pill on the auto-displayed anni-motd. `/wv anni` always shows the RSVP widget when applicable; this key only suppresses the auto-print nudge. |
| `vetsAnniShowPrediction` | true | Show the `\guess`-style prediction window (earliest/median/latest) in `/wv anni` when no anni stamp is announced. The auto-motd never shows the prediction unsolicited regardless of this flag. |
| `vetsAnniBossbarEnabled` | true | Master kill-switch for the synthetic vets-anni boss bar (S3). Only consulted when `vetsAnniMode` is `passive`/`aggressive`; silent mode is a strict no-op regardless. Lets advanced users keep outline/waypoint behaviours while opting out of the boss bar specifically. |
| `vetsAnniFlashSound` | true | Whether per-field change flashes (role/party/world/RSVP) also play the Wynntils-style name-ping sound twice (spec §3.1.1). Toggle off if audio cues get noisy during heavy snapshot churn. |
| `vetsAnniOutlinesEnabled` | true | Master toggle for the S4 outline overlay — role-coloured glow on own-party members, light-grey (`§7`) glow on other-vets-party members, native Wynncraft team outlines suppressed for outsiders. Gated on `vetsAnniMode != silent` AND within T-2h..T+30m AND in the anni zone. Separable from `vetsAnniNametagsEnabled` so you can take one half without the other. |
| `vetsAnniNametagsEnabled` | true | Master toggle for the S4 nametag overlay — role colour on own-party members, light-grey on other-vets-party members, dark-grey (`§8`) on outsiders. Same gate as `vetsAnniOutlinesEnabled`. Runs as a branch in `NametagMixin` before the supporter glint branch, so a supporter on a vets-anni party gets the role colour during the highlight gate and the supporter glint reverts afterwards. |
| `vetsAnniZoneLines` | true | S5 — master toggle for the in-world zone-line renderer (the union of 48-block disc circumferences from `AnniZone`, drawn via `Gizmos.circle`). Gated on `vetsAnniMode == aggressive` AND within T-2h..T+30m. No zone gate — visible whenever you're aggressive + in-window, so users flying in see the boundary. |
| `vetsAnniScrollWaypoint` | true | S5 — master toggle for the Scroll Spot waypoint (Wynntils `MarkerProvider`). Pinned coord is sourced from `board.party.scroll_spot`; falls back to `345 45 -1315` when the host hasn't set one. Icon-only — no beacon beam. Gated on aggressive + window. |
| `vetsAnniChatAlerts` | true | S5 — master toggle for diff-aware chat alerts. Fires on role / world / party / RSVP transitions (5s per-field cooldown, silent first-observation), plus T-10m world-mismatch and T-5m zone-absence readiness alerts (each at most once per stamp_epoch). Gated on aggressive + window. |
| `vetsAnniGhostsPrompt` | true | S5 — master toggle for the clickable `[Suggest: /toggle ghosts none]` prompt fired at most once per stamp_epoch on first zone entry per anni. Detection uses `Models.Player.isPlayerGhost` — if any visible player is ghost-flagged, ghosts must be on and the prompt fires unconditionally; otherwise per-stamp_epoch sentinel (`vetsAnniGhostsPromptShownForStamp`) suppresses re-fires. Gated on aggressive + window. |
| `printBridgeMessages` | true | Display bridge (Discord relay) messages |
| `printSuccessfulAuth` | true | Show the `✅ vetsmod authentication verified` ack. Latched to `false` after one successful render and reset to `true` by `onAuthFailure`, so the ack appears at most once per error→recovery cycle. |
| `showSupporterGlints` | true | Animated gradient glints on nametags + pills |
| `colorBlindMode` | false | Swap the supporter-glint colour pairs (chat + nametag) for a high-luminance-delta variant so the shimmer is visible under protan/deutan CVD. Still subtle; same cyan/blue family. |
| `moreReliableGuildCheck` | true | Run `/gu stats` on world join for guild detection |

### Strings (colour names or sprite names)
| Key | Default | Valid values |
|-----|---------|--------------|
| `legacyItemBackgroundGradientTop` | `orange` | CSS/Minecraft colour names (see `NamedColor.COLORS`) |
| `legacyItemBackgroundGradientBottom` | `crimson` | CSS/Minecraft colour names |
| `legacyItemForegroundColor` | `orange` | CSS/Minecraft colour names |
| `legacyItemForegroundSprite` | `box_gradient_2` | `wynn`, `tag`, `circle_transparent`, `circle_opaque`, `circle_outline_large`, `circle_outline_small`, `box_transparent`, `box_opaque`, `box_gradient_1`, `box_gradient_2` |
| `vetsAnniRoleStyle` | `descriptive` | `descriptive` (TANK/HEALER/SUNKILL/MOBKILL/BOSSKILL/FILL — action-flavoured), `short` (TANK/HEAL/SUNK/MOBK/PRIM/FILL — 4-char compact), `formal` (TANK/HEALER/SECONDARY/TERTIARY/PRIMARY/FILL — spec-canonical) |
| `vetsAnniFlashIntensity` | `normal` | `subtle` (5s flash window), `normal` (10s), `strong` (20s). Controls the per-field on-change flash duration; the bold↔underline pulse half-period (250ms) is fixed. |

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

**`vetsAnniMode` is not in this list.** It is a persisted string key (default `silent`; values `silent` / `passive` / `aggressive`), but it is set by `/wv anni <mode>` through `AnniModeManager.transitionTo`, not by `/wv config` — it is absent from `USER_CONFIG_KEYS`. It auto-resets to `silent` at T+30m via `AnniWindowWatcher`, and a transition is refused → silent while `/stream` is on.

## 3. API (static methods, all public except `save()`)

All methods keyed by string. Returns false when validation fails.

| Method | Notes |
|--------|-------|
| `get(key)` / `set(key,value)` | boolean map |
| `getLong(key)` / `setLong(key,value)` | long map |
| `getTriState(key)` / `setTriState(key,value)` | tri-state map |
| `getString(key)` / `setString(key,value)` | string map |
| `hasKey(key)` | checks the boolean, tri-state and string maps |
| `isUserConfigKey(key)` | scans `USER_CONFIG_KEYS` |
| `isTriStateKey(key)` / `isStringKey(key)` / `isIntKey(key)` | scan `TRISTATE_KEYS` / `STRING_CONFIG_KEYS` / `INT_CONFIG_KEYS` |
| `getIntDefault(key)` / `getStringDefault(key)` | from INT_DEFAULTS/STRING_DEFAULTS maps |
| `registerDefault(key, defaultValue)` | for subsystems; boolean keys only, must run before `load()` |
| `resetToDefaults()` | saves current in-memory; does NOT clear |
| `load()` | creates the file from defaults when absent |
| `save()` | **private**; called by every setter, by `load()`, and by `resetToDefaults()` |
| `isValidColor(name)` | delegated to `LegacyItemStyle.isValidColor`, which reads `NamedColor`'s private `COLORS` map |
| `isValidSprite(name)` | delegated to `LegacyItemStyle.isValidSprite`, which matches against the `VetsConfig.VALID_SPRITES` array — there is no sprite enum |
| `getColorNames()` | for command suggestion |

## 4. USER_CONFIG_KEYS order (for /wv config listing)

Declared by [VetsConfig.USER_CONFIG_KEYS](../src/client/java/org/wynnvets/config/VetsConfig.java) — **30 entries**, in this order:

1. `legacyItemHighlighting`
2. `legacyItemShowEnchantments`
3. `legacyItemBackgroundGradientTop`
4. `legacyItemBackgroundGradientTopOpacity`
5. `legacyItemBackgroundGradientBottom`
6. `legacyItemBackgroundGradientBottomOpacity`
7. `legacyItemForegroundSprite`
8. `legacyItemForegroundColor`
9. `printMOTD`
10. `printANNI`
11. `vetsAnniEnabled`
12. `vetsAnniShowHoverDetails`
13. `vetsAnniPromptRsvp`
14. `vetsAnniShowPrediction`
15. `vetsAnniRoleStyle`
16. `vetsAnniBossbarEnabled`
17. `vetsAnniFlashIntensity`
18. `vetsAnniFlashSound`
19. `vetsAnniOutlinesEnabled`
20. `vetsAnniNametagsEnabled`
21. `vetsAnniZoneLines`
22. `vetsAnniScrollWaypoint`
23. `vetsAnniChatAlerts`
24. `vetsAnniGhostsPrompt`
25. `printBridgeMessages`
26. `printSuccessfulAuth`
27. `showSupporterGlints`
28. `colorBlindMode`
29. `handleSpoilers`
30. `moreReliableGuildCheck`

## 5. Static defaults

INT_DEFAULTS: opacity values.
STRING_DEFAULTS: seven entries — the four colour/sprite keys plus `vetsAnniRoleStyle` (`descriptive`), `vetsAnniMode` (`silent`) and `vetsAnniFlashIntensity` (`normal`).
Booleans default in the static initializer, and not all of them are true: `vetsIsStaff`, `vetsAnniEnabled`, `vetsAnniModeUserSet` and `colorBlindMode` default `false`. Timestamp/count longs default `0L`.
HANDLE_SPOILERS default: null.

## 6. Persistence

JSON pretty-printed via GSON. Parent dir created if missing. Missing keys use in-memory defaults. IO exceptions logged as `warn`.

**Type strictness on load:** Only correctly-typed JSON values are accepted — a string where a boolean is expected is ignored.

**Tri-state serialization:** Omitted from JSON when null; present as JSON bool when true/false.

## 7. Colour resolution

[NamedColor](../src/client/java/org/wynnvets/config/NamedColor.java) — `NamedColor.COLORS` map contains:
- Minecraft formatting codes (dark_red, red, gold, yellow, dark_green, green, aqua, dark_aqua, blue, dark_blue, light_purple, dark_purple, white, gray, dark_gray, black)
- CSS-ish colours (crimson, orange, etc.)
- Wynncraft rarity colours
- Custom `legacy_orange` = `0xF0501E`
- `transparent`

[LegacyItemStyle](../src/client/java/org/wynnvets/config/LegacyItemStyle.java) reads config + NamedColor to produce ARGB int values with opacity packed into alpha byte.

## 8. Related config-adjacent state

**Debug config** — separate system at [DebugConfigManager](../src/client/java/org/wynnvets/debug/DebugConfigManager.java) (not merged into VetsConfig). Current debug keys: `itemDump` (bool) — when true, numpad `+` while hovering item dumps full Component tree JSON to `vetsmod/dumps/`.

Must call `DebugConfigManager.init()` before `VetsConfig.load()` (they share the same storage location).

## 9. Quirks / gotchas

- **No migration:** Adding new keys requires shipping with a sensible default; old configs just gain the new key on next save.
- **`resetToDefaults()` is misleading:** it saves current in-memory state, not reset in-memory values. Actual reset requires deleting the config file.
- **Tri-state `handleSpoilers`:** null/default means "on", and the user can explicitly set false to disable.
- **Integer opacity 0-100:** stored as long in JSON; multiplied by 2.55 and packed into ARGB alpha byte.
- **String validation:** `isValidColor()` / `isValidSprite()` accept lowercase names; keys should match exactly (case-sensitive in JSON).
