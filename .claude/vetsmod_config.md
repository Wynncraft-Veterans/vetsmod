---
name: vetsmod Config Reference
description: Complete config reference (VetsConfig, VetsConfigKeys, VetsConfigSchema) — every key (user-facing and internal), defaults, validation, persistence; JSON at vetsmod/storage/config.json
type: project
originSessionId: dc63f47a-2d15-4f8d-9b6a-41d3049f0cc2
---
# vetsmod Config Reference

Single-file JSON config at `vetsmod/storage/config.json` (resolved via `FabricLoader.getInstance().getGameDir()`).

Implementation: [VetsConfig](../src/client/java/org/wynnvets/config/VetsConfig.java), the store; [VetsConfigKeys](../src/client/java/org/wynnvets/config/VetsConfigKeys.java), the key names; [VetsConfigSchema](../src/client/java/org/wynnvets/config/VetsConfigSchema.java), the `/wv config` key lists and valid-value lists; [VetsConfigDefaults](../src/client/java/org/wynnvets/config/VetsConfigDefaults.java), the first-run defaults; and [ShippedConfig](../src/client/java/org/wynnvets/config/ShippedConfig.java), the shipped `config.yml` that `/wv config <key> default` restores from.

Four type-distinct backing maps: boolean, long, string, tri-state (Boolean-or-null). All coexist in one flat JSON object — no nesting, no version field, no migration logic.

## 1. Internal state keys (not user-facing, cache state only)

| Key | Type | Purpose |
|-----|------|---------|
| `vetsAutomessage` | bool | Global gate for auto-messages (MOTD, ANNI) |
| `vetsIsStaff` | bool | Cached staff status |
| `vetsLastStaffCheck` | long | Timestamp of last `/gu rank` check |
| `vetsAuthKey` | string | Bearer key from `/unlock <key>` (43-char base64url issued by dazebot's `/vetsmod`). Sent in `auth` frame on every (re)connect of the inbound WS. |
| `vetsAuthTier` | string | Tier (`member`/`waitlist`/`honourary`/`other`) the last successful auth ack reported, written by `InboundAckRouter`. Never cleared, so after a new `/unlock` key or a rejection it can belong to an earlier key. Read only by the diagnostics dump; nothing gates on it. |
| `vetsAuthVerifiedAt` | long | Epoch millis of the last successful auth-frame ack, written by `InboundAckRouter`. Display-only (diagnostics). |
| `vetsWaitlistUnlockTime` | long | **legacy SHA-256 unlock marker** — meant solely as a "this user used the old system" signal for the SessionAuthWarning. Today it still grants client-side unlock: `UnlockManager`'s tier predicates OR it in (bug `legacy-unlock-markers-still-grant-client-unlock`). |
| `vetsHonouraryUnlockTime` | long | Same — legacy marker, same caveat. |
| `vetsGuildCheckResult` | long | Cached `/gu stats` result enum (0=UNKNOWN,1=RETURNERS,2=OTHER_GUILD,3=GUILDLESS) |
| `vetsLastGuildCheck` | long | Timestamp of last `/gu stats` (3-day TTL) |
| `vetsDebugEnabledAt` | long | When `/wv debug true` set the debug flag (0 when off). Read only at client init, which restores the flag if under three days old and otherwise zeroes this key. The age is not rechecked later, so a running session keeps debug on |
| `vetsAnniModeUserSet` | bool | Set by a `/wv anni <mode>` pick (an `AnniModeManager.transitionTo` with `Source.USER_COMMAND`). Read by `AnniModeManager.preferredMode()` and `applyStartupDefaultIfNeeded()`; [vetsmod_mwe_anni.md](vetsmod_mwe_anni.md) §Mode state owns what it changes. Installs from before the key existed lack it, so their earlier `vetsAnniMode` is replaced by the default. |
| `vetsAnniUserMode` | string | The user's last explicit anni-mode pick, written with `vetsAnniModeUserSet` and kept apart from `vetsAnniMode`, so a `/stream`-forced silent does not overwrite it. Empty until the first pick. |
| `vetsAnniGhostsPromptShownForStamp` | string | The stamp for which the ghosts prompt last fired on its no-ghost-seen branch, persisted so a restart in the same window does not repeat that fire. Empty until that branch first fires. |

## 2. User-facing keys (USER_CONFIG_KEYS array, settable via /wv config)

### Booleans
| Key | Default | Purpose |
|-----|---------|---------|
| `legacyItemHighlighting` | true | Show legacy/enchanted/junk highlighting + tooltip rewrite |
| `legacyItemShowEnchantments` | true | Name the specific enchantment on legacy items the enchant branch already highlights: a LEGACY ENCHANTMENTS block on the powder page of paged tooltips, or in the tooltip text on items with no pages (`LegacyEnchantmentRenderer.showEnchantment` picks the form). No effect unless `legacyItemHighlighting` is on. |
| `printMOTD` | true | Auto-print MOTD on world join |
| `printANNI` | true | Auto-print annihilation timer on world join, for Returners guild members only |
| `vetsAnniEnabled` | false | Gates the snapshot-driven `/wv anni` renderer and anni-motd (through `StampFetcher.anniIntegrationActive`), and their cold-cache pulls; the boss bar and outlines don't read it — they follow `vetsAnniMode` instead. Meant to give vets-tier users the enriched view without a manual toggle; today a vets-tier (member/waitlist/honourary) auth ack sets it `true` whenever it finds it off, so a vets-tier user's opt-out lasts only until the next ack. Anyone can opt in manually, but the pull names no UUID, so per temporary-server (at ffd8c17) it resolves to no snapshot until an `/unlock` key has authenticated the inbound socket (bug `vets-anni-enabled-to-be-retired`). |
| `vetsAnniShowHoverDetails` | true | Populate descriptive hover tooltips on `/wv anni` and motd widgets (role chips, RSVP badge, attendance bar, party world chip). When off, lines render with no hover but keep click-to-open URLs. |
| `vetsAnniPromptRsvp` | true | Show the placement / RSVP status line under the countdown on the auto-displayed anni-motd; off means the countdown line alone. `/wv anni`'s RSVP widgets don't read this key. |
| `vetsAnniShowPrediction` | true | Show the `\guess`-style prediction line (the median, with a ± from the window) in `/wv anni` when no anni stamp is announced. The auto-motd never shows the prediction unsolicited regardless of this flag. |
| `vetsAnniBossbarEnabled` | true | Master kill-switch for the synthetic vets-anni boss bar (S3). Only consulted when `vetsAnniMode` is `passive`/`aggressive`; silent mode is a strict no-op regardless. Lets advanced users keep outline/waypoint behaviours while opting out of the boss bar specifically. With the bar off, a snapshot change plays no flash-sound ping (`vetsAnniFlashSound`) either. |
| `vetsAnniFlashSound` | true | Whether a role/party/RSVP/world change in the snapshot plays a two-ping sound: the Wynntils chat-mention ding (`SoundEvents.NOTE_BLOCK_PLING`), played twice, as spec §3.1.1 asks. The pings go with the boss bar's flashes, so they play only while the bar is up: silent mode, `vetsAnniBossbarEnabled` off, and being outside both the bar's window and the anni zone all stop them too. A debug `flash` pings whenever this is on. |
| `vetsAnniOutlinesEnabled` | true | Toggle for the S4 outline overlay: role-coloured glow on own-party members, light-grey (`§7`) glow on other-vets-party members, and suppression of native Wynncraft team outlines on outsiders. Separable from `vetsAnniNametagsEnabled` so you can take one half without the other. Counts toward the highlight gate (`vetsAnniMode != silent` AND at least one of this and `vetsAnniNametagsEnabled` AND T-2h..T+30m AND in the anni zone), which reads it right after the mode check, whether or not the rest of the gate then holds. Past the gate, it gates both halves of the outline overlay, the registry-tier glow and the outsider suppression. |
| `vetsAnniNametagsEnabled` | true | Master toggle for the S4 nametag overlay — role colour on own-party members, light-grey on other-vets-party members, dark-grey (`§8`) on outsiders. Same gate as `vetsAnniOutlinesEnabled`. Runs as a branch in `NametagMixin`'s TAIL injector before the supporter glint branch, so a supporter on a vets-anni party gets the role colour during the highlight gate and the supporter glint reverts afterwards. (`NametagMixin` has two injectors; with wynnmod present the TAIL path returns early and its `@WrapOperation` owns the glint — see [vetsmod_mixins.md](vetsmod_mixins.md).) |
| `vetsAnniZoneLines` | true | S5 — master toggle for the in-world zone-line renderer (the union of 48-block disc circumferences from `AnniZone`, drawn via `Gizmos.circle`). Gated on `vetsAnniMode == aggressive` AND within T-2h..T+30m. No zone gate, but `AnniZoneLineRenderer`'s distance cull means a ring appears on final approach rather than for the whole flight in (see `AnniAggressiveTicker`). |
| `vetsAnniScrollWaypoint` | true | S5 — master toggle for the Scroll Spot waypoint (Wynntils `MarkerProvider`). Pinned coord is sourced from `board.party.scroll_spot`; falls back to `345 45 -1315` when the host hasn't set one, but **only while the player is in a party** — `computeEntry` returns null on a null `board.party()` before it reads the spot. Renders a **dark-red beacon** (`ChatFormatting.DARK_RED`, §4) plus a `Texture.MAP` icon. Gated on aggressive + window. |
| `vetsAnniChatAlerts` | true | S5 — master toggle for diff-aware chat alerts. Fires on role / world / party / RSVP transitions (5s per-field cooldown, silent first-observation), plus T-10m world-mismatch and T-5m zone-absence readiness alerts (each at most once per stamp_epoch). Gated on aggressive + window. |
| `vetsAnniGhostsPrompt` | true | S5 — master toggle for the clickable `[We suggest: /toggle ghosts none]` prompt on a zone-entry rising edge. Detection uses `Models.Player.isPlayerGhost` — if any visible player is ghost-flagged, ghosts must be on and the prompt fires unconditionally; otherwise per-stamp_epoch sentinel (`vetsAnniGhostsPromptShownForStamp`) suppresses re-fires. Gated on aggressive + window. |
| `printBridgeMessages` | true | Display WebSocket-relayed chat: bridge messages and every other relayed type; server-pushed `warning` frames bypass it |
| `printSuccessfulAuth` | true | Show the `Authentication verified` ack. Latched to `false` after one config-driven render and reset to `true` by `onAuthFailure`, so the *reconnect* ack appears at most once per error→recovery cycle. A `/unlock <key>` run sets a separate one-shot flag that forces the next ack to render regardless of this key, and does not re-latch it. |
| `showSupporterGlints` | true | Animated gradient glints on nametags, pills, and `/wv list` roster names |
| `colorBlindMode` | false | Chat (pills and `/wv list` names): swap the supporter shimmer's cyan and grey colour pairs for higher-luminance-delta variants of the same families. Nametags: pulse between a darkened and a strongly lightened copy of the nametag's own colour. Either way the aim is a shimmer visible under protan/deutan CVD. |
| `moreReliableGuildCheck` | true | Run `/gu stats` about 5 s after world join for guild detection, instead of relying only on Wynntils' guild name, which is empty until Wynntils' own scan fills it |

### Strings (colour names, sprite names, or the values listed per key)
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

**`vetsAnniMode` is not in this list.** It is a persisted string key (default `silent`; values `silent` / `passive` / `aggressive`), but it is written only through `AnniModeManager.transitionTo` — by `/wv anni <mode>`, and by the automatic and debug sources its `Source` enum names — not by `/wv config`: it is absent from `USER_CONFIG_KEYS`. At T+30m `AnniWindowWatcher` resets it to `AnniModeManager.preferredMode()`, **not** to silent — silent is one of three possible targets, not the target. [vetsmod_mwe_anni.md](vetsmod_mwe_anni.md) §"Mode state" owns what `preferredMode()` resolves to; don't restate it here. While `/stream` is active, `transitionTo` refuses any target other than `silent` (unless the source is `DEBUG_BYPASS_MUTEX`) — a refused transition writes nothing and leaves the current mode alone.

## 3. API (static methods, all public except `save()`)

The first column names the class that declares each method. `VetsConfigKeys` holds only the key-name constants, and the colour and sprite rows are `LegacyItemStyle`'s, which `/wv config` calls directly. Every method in the table takes a key except `load()`, `save()` and `getColorNames()`, which take nothing, and `isValidColor(name)` / `isValidSprite(name)`, which take a value; `isFixedValue(key,value)` takes both, and `VetsConfigDefaults`' methods take the four maps, nothing, or the output path. The setters return false when the key is not registered in their map; none validates the value.

| Class | Method | Notes |
|-------|--------|-------|
| `VetsConfig` | `get(key)` / `set(key,value)` | boolean map |
| `VetsConfig` | `getLong(key)` / `setLong(key,value)` | long map |
| `VetsConfig` | `getTriState(key)` / `setTriState(key,value)` | tri-state map |
| `VetsConfig` | `getString(key)` / `setString(key,value)` | string map |
| `VetsConfigSchema` | `isUserConfigKey(key)` | scans `USER_CONFIG_KEYS` |
| `VetsConfigSchema` | `isTriStateKey(key)` / `isStringKey(key)` / `isIntKey(key)` | scan `TRISTATE_KEYS` / `STRING_CONFIG_KEYS` / `INT_CONFIG_KEYS` |
| `VetsConfigDefaults` | `seed(maps)` / `firstRunValues()` / `main(args)` | the first-run defaults `VetsConfig` seeds from, and the build's writer of `config.yml` (§5); `seed` and `firstRunValues` are package-private |
| `ShippedConfig` | `bool(key)` / `number(key)` / `string(key)` | the key's value in the shipped `config.yml`, or `null`; read once, from vetsmod's own mod container |
| `VetsConfigSchema` | `fixedValuesOf(key)` / `isFixedValue(key,value)` | `VALID_ROLE_STYLES` for `vetsAnniRoleStyle`, `VALID_FLASH_INTENSITIES` for `vetsAnniFlashIntensity`; `null` / false for every other key |
| `VetsConfig` | `registerDefault(key, defaultValue)` | for subsystems; boolean keys only, must run before `load()` |
| `VetsConfig` | `load()` | creates the file from defaults when absent; a file that can't be parsed is moved aside and the defaults are used (§6) |
| `VetsConfig` | `save()` | **private**; called by every setter and by `load()` |
| `LegacyItemStyle` | `isValidColor(name)` | delegates to `NamedColor.isValid`, which reads `NamedColor`'s private `COLORS` map |
| `LegacyItemStyle` | `isValidSprite(name)` | matches against the `VetsConfigSchema.VALID_SPRITES` array — there is no sprite enum |
| `LegacyItemStyle` | `getColorNames()` | for command suggestion |

## 4. USER_CONFIG_KEYS order (for /wv config listing)

Declared by [VetsConfigSchema.USER_CONFIG_KEYS](../src/client/java/org/wynnvets/config/VetsConfigSchema.java) — **30 entries**, in this order:

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

`VetsConfigDefaults` holds the defaults of the keys in `VetsConfigKeys`, and `ShippedConfig` reads the build's copy of them from the jar's `config.yml`; the debug keys get theirs from `DebugConfigManager.DEFAULTS` through `registerDefault` (§8).

`VetsConfigDefaults.seed` sets the first-run default of every key in `VetsConfigKeys`, and `VetsConfig`'s static initializer calls it. Booleans default there, and not all of them are true: `vetsIsStaff`, `vetsAnniEnabled`, `vetsAnniModeUserSet` and `colorBlindMode` default `false`. Timestamp/count longs default `0L`.
HANDLE_SPOILERS default: null.

**The shipped `config.yml`.** The build's `generateConfigYml` task runs `VetsConfigDefaults.main`, which writes `firstRunValues()` to `config.yml` in the jar: the pairs a first-run `config.json` holds, the debug keys `DebugConfigManager.DEFAULTS` registers included and an unset tri-state left out. Its one reader is `ShippedConfig`, behind `/wv config <key> default`, which every key takes (string and int keys keep `reset` as an alias; a tri-state's `default` clears it instead). `ShippedConfig` reads the file from vetsmod's own mod container, so another mod's `config.yml` cannot stand in for it, and a missing file makes `default` refuse with "There is no shipped default for <key>.". The file is generated from the same `seed`, so it cannot drift from the code; `ShippedConfigTest` checks the copy on the test classpath. SnakeYAML, bundled in the jar with Loom's `include`, writes and reads it.

## 6. Persistence

JSON pretty-printed via GSON. Parent dir created if missing. Missing keys use in-memory defaults. IO exceptions logged as `warn`.

**A file that can't be parsed** (malformed JSON, a number out of range, bytes that aren't UTF-8) no longer stops the game from starting. `load()` logs it at `warn`, moves it aside as `config.json.bad-<epoch>` before any setter's `save()` can overwrite it, and runs on the defaults, keeping any keys it had already applied. The player loses their settings and stored `/unlock` key until that file is repaired and moved back. `save()` is still neither atomic nor serialised.

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

**Debug config** — keys declared in [DebugConfigManager](../src/client/java/org/wynnvets/debug/DebugConfigManager.java) rather than among VetsConfigKeys' constants, but registered into VetsConfig's boolean map via `registerDefault` and persisted in the same `config.json`. Current debug keys: `itemDump` (bool) — when true, numpad `+` while hovering item dumps full Component tree JSON to `vetsmod/dumps/items/`.

Must call `DebugConfigManager.init()` before `VetsConfig.load()`: `load()` reads from `config.json` only the keys already registered, so a debug key registered after it would not pick up its persisted value.

## 9. Quirks / gotchas

- **No migration:** Adding new keys requires shipping with a sensible default; old configs just gain the new key on next save.
- **Tri-state `handleSpoilers`:** null/default means "on", and the user can explicitly set false to disable.
- **Integer opacity 0-100:** stored as long in JSON; multiplied by 2.55 and packed into ARGB alpha byte.
- **String validation:** `isValidColor()` / `isValidSprite()` and `VetsConfigSchema.isFixedValue()` accept lowercase names; keys should match exactly (case-sensitive in JSON).
