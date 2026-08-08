package org.wynnvets.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wynntils.utils.render.Texture;
import net.fabricmc.loader.api.FabricLoader;
import org.wynnvets.logging.VetsLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Configuration manager for VetsMod toggleable features.
 *
 * <p>Provides an expandable system for managing boolean and long configuration
 * options, persisted as JSON to {@code vetsmod/storage/config.json}.  Keys fall
 * into two categories:</p>
 * <ul>
 *   <li><b>Internal</b> — used by the mod to cache transient state (e.g.
 *       {@link #VETS_IS_STAFF}, {@link #VETS_LAST_STAFF_CHECK}).  These are
 *       <em>not</em> exposed to the {@code /wv config} command.</li>
 *   <li><b>User-facing</b> — toggleable by the player via {@code /wv config
 *       &lt;key&gt; &lt;value&gt;}.  Listed in {@link #USER_CONFIG_KEYS}.</li>
 * </ul>
 */
public class VetsConfig {
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final Path CONFIG_FILE = FabricLoader.getInstance().getGameDir().resolve("vetsmod/storage/config.json");

  // Store configuration values by type
  private static final Map<String, Boolean> config = new ConcurrentHashMap<>();
  private static final Map<String, Long> longConfig = new ConcurrentHashMap<>();
  private static final Map<String, Boolean> triStateConfig = Collections.synchronizedMap(new HashMap<>());
  private static final Map<String, String> stringConfig = new ConcurrentHashMap<>();

  // ── Internal configuration keys (not user-facing) ───────────────────────
  public static final String VETS_AUTOMESSAGE = "vetsAutomessage";
  public static final String VETS_IS_STAFF = "vetsIsStaff";
  public static final String VETS_LAST_STAFF_CHECK = "vetsLastStaffCheck";
  public static final String VETS_WAITLIST_UNLOCK_TIME = "vetsWaitlistUnlockTime";
  public static final String VETS_HONOURARY_UNLOCK_TIME = "vetsHonouraryUnlockTime";
  public static final String VETS_UNLOCK_EXPIRY_WARNINGS = "vetsUnlockExpiryWarnings";
  public static final String VETS_GUILD_CHECK_RESULT = "vetsGuildCheckResult";
  public static final String VETS_LAST_GUILD_CHECK = "vetsLastGuildCheck";
  public static final String VETS_DEBUG_ENABLED_AT = "vetsDebugEnabledAt";

  /** Master toggle for the MWE (Major World Event / anni) integration —
   *  the snapshot listener, anni-aware {@code /wv anni} renderer, boss bar
   *  (S3), outlines (S4), etc. Off by default. Auto-set to {@code true}
   *  on the first successful auth ack whose tier ∈ {@code {member,
   *  waitlist, honourary}}, so vets players just get the enriched view
   *  without a manual toggle. Non-vets users can opt in via
   *  {@code /wv config vetsAnniEnabled true} if they want the on-demand
   *  pull (Hard Rule #3: pulls are open to anyone). */
  public static final String VETS_ANNI_ENABLED = "vetsAnniEnabled";

  // ── Vetsmod /unlock <key> auth state ─────────────────────────────────
  /** Bearer key issued by dazebot's /vetsmod command and stored on disk so
   *  it survives mod restarts. Sent in an `auth` frame on every WebSocket
   *  (re)connect. Empty string when the user hasn't run /unlock yet. */
  public static final String VETS_AUTH_KEY = "vetsAuthKey";
  /** Last server-confirmed tier (member/waitlist/honourary/other) for the
   *  stored key. Refreshed on every auth-frame success. Persisted only as
   *  a UX hint — the authoritative value comes from the server each session. */
  public static final String VETS_AUTH_TIER = "vetsAuthTier";
  /** Epoch millis of the last successful auth-frame response, or 0. Used
   *  for staleness display and to gate "previously authenticated" warnings. */
  public static final String VETS_AUTH_VERIFIED_AT = "vetsAuthVerifiedAt";

  // ── User-facing configuration keys (toggled via /wv config) ─────────────

  /** Whether legacy/enchanted/junk item highlighting is shown in tooltips and inventory slots. */
  public static final String LEGACY_ITEM_HIGHLIGHTING = "legacyItemHighlighting";

  /** Whether the LEGACY ENCHANTMENTS block naming the specific enchantment is
   *  drawn on the powder page of items the enchant branch already highlights.
   *  Has no effect unless {@link #LEGACY_ITEM_HIGHLIGHTING} is on. */
  public static final String LEGACY_ITEM_SHOW_ENCHANTMENTS = "legacyItemShowEnchantments";

  /** Whether the MOTD is automatically printed on world join. */
  public static final String PRINT_MOTD = "printMOTD";

  /** Whether the annihilation stamp is automatically printed on world join. */
  public static final String PRINT_ANNI = "printANNI";

  /** Whether the snapshot-driven {@code /wv anni} and anni-motd populate
   *  hover tooltips on segments (role chips, RSVP badges, attendance bar,
   *  party world chip). When {@code false}, the same lines render with no
   *  hover — keeps the click-to-open URLs but strips the descriptive
   *  hover text. */
  public static final String VETS_ANNI_SHOW_HOVER_DETAILS = "vetsAnniShowHoverDetails";

  /** Whether the snapshot-driven anni-motd nags an un-RSVP'd vets-anni user
   *  with the "RSVP via /wv anni rsvp …" prompt. The {@code /wv anni}
   *  manual invocation always shows the RSVP widget when applicable; this
   *  key only suppresses the auto-print nag for users who find it noisy. */
  public static final String VETS_ANNI_PROMPT_RSVP = "vetsAnniPromptRsvp";

  /** Whether {@code /wv anni} shows the {@code \\guess}-style prediction
   *  window (earliest/median/latest) when the stamp is past or unknown.
   *  The anni-motd auto-print never shows the prediction unsolicited (per
   *  spec §"For external users"); this key only affects the manual
   *  {@code /wv anni} invocation. */
  public static final String VETS_ANNI_SHOW_PREDICTION = "vetsAnniShowPrediction";

  /** Active anni mode — {@code silent}, {@code passive}, or
   *  {@code aggressive}. Set by {@code /wv anni <mode>} and read by
   *  the boss-bar (S3+), outline (S4+), and waypoint (S5+) subsystems.
   *  Absent from {@link #USER_CONFIG_KEYS}, so {@code /wv config} cannot
   *  read or write it. When the anni window closes (T+30 min after
   *  stamp_epoch) {@code AnniWindowWatcher} restores
   *  {@code AnniModeManager.preferredMode()} — which is {@code silent}
   *  only for a user who never set a mode and is not
   *  enrichment-eligible, not unconditionally. */
  public static final String VETS_ANNI_MODE = "vetsAnniMode";

  /** Valid values for {@link #VETS_ANNI_MODE}. */
  public static final String[] VALID_ANNI_MODES = {
      "silent", "passive", "aggressive",
  };

  /** Internal flag — {@code true} once the user has explicitly chosen an
   *  anni mode via {@code /wv anni <mode>} (or the mode-switch buttons
   *  under {@code /wv anni}). Set only for {@code Source.USER_COMMAND}
   *  transitions in {@code AnniModeManager}; never touched by internal
   *  transitions (window-close, stream, startup). When {@code false},
   *  {@link #VETS_ANNI_MODE} is treated as an unremembered default and
   *  gets overwritten to the eligibility-based default (PASSIVE for
   *  enrichment-eligible users, SILENT otherwise) at the next restore
   *  moment. Also serves as the pre-0.14.5 install boundary — old
   *  installs lack this key, so their pre-existing {@code vetsAnniMode}
   *  is discarded and the new default applies. */
  public static final String VETS_ANNI_MODE_USER_SET = "vetsAnniModeUserSet";

  /** Internal — snapshot of the user's most recent explicit anni-mode
   *  pick. Written alongside {@link #VETS_ANNI_MODE_USER_SET}. Kept
   *  separate from {@link #VETS_ANNI_MODE} so that internal transitions
   *  (a {@code /stream}-forced SILENT, in particular) don't clobber the
   *  user's preferred value — on stream-off we restore from this key. */
  public static final String VETS_ANNI_USER_MODE = "vetsAnniUserMode";

  /** Role-naming style for {@code /wv anni} role chips. Three values:
   *  <ul>
   *    <li>{@code descriptive} (default) — TANK, HEALER, SUNKILL,
   *        MOBKILL, BOSSKILL, FILL. Action-flavoured names that map to
   *        what each role actually does in the fight.</li>
   *    <li>{@code short} — TANK, HEAL, MOBK, SUNK, PRIM, FILL.
   *        Compact 4-char codes for tight chat layouts.</li>
   *    <li>{@code formal} — TANK, HEALER, PRIMARY, SECONDARY,
   *        TERTIARY, FILL. The spec-canonical role names verbatim, for
   *        users who prefer the dashboard's terminology.</li>
   *  </ul>
   *  Unknown values fall through to the raw role code (uppercase),
   *  same as if the role isn't recognised in any style table. */
  public static final String VETS_ANNI_ROLE_STYLE = "vetsAnniRoleStyle";

  /** Valid values for {@link #VETS_ANNI_ROLE_STYLE}, in suggest-completion
   *  order. */
  public static final String[] VALID_ROLE_STYLES = {
      "descriptive", "short", "formal",
  };

  /** Master kill-switch for the synthetic vets-anni boss bar (S3).
   *  Default {@code true}; only honoured when {@link #VETS_ANNI_MODE}
   *  is {@code passive} or {@code aggressive} — silent is a strict
   *  no-op regardless. Lets advanced users keep the rest of the
   *  passive/aggressive subsystem (outlines, waypoint, alerts) while
   *  opting out of the boss bar specifically. */
  public static final String VETS_ANNI_BOSSBAR_ENABLED = "vetsAnniBossbarEnabled";

  /** Boss-bar pulse intensity controlling the {@code &l ↔ &n&l} flash
   *  duration on a per-field change (role / party / RSVP — the world
   *  chip has no timed window; it flashes while the mismatch holds).
   *  {@code subtle} = 5 s, {@code normal} = 10 s (default),
   *  {@code strong} = 20 s. The pulse half-period (250 ms) is fixed —
   *  this knob only affects the on-change flash duration, not the
   *  rate at which the bold/underline alternates. */
  public static final String VETS_ANNI_FLASH_INTENSITY = "vetsAnniFlashIntensity";

  /** Whether per-field change flashes also play the Wynntils-style
   *  name-ping sound twice (per spec §3.1.1). On by default; toggle
   *  off if the audio cue becomes spammy during heavy snapshot churn. */
  public static final String VETS_ANNI_FLASH_SOUND = "vetsAnniFlashSound";

  /** S4 — Master toggle for the per-player outline overlay (role-coloured
   *  glow on own-party members, light-grey glow on other-vets-party
   *  members, native Wynncraft team outlines suppressed for outsiders).
   *  Only consulted while the highlight gate is held (mode != silent,
   *  within T-2h..T+30m, in the anni zone). Default {@code true}.
   *  Separable from {@link #VETS_ANNI_NAMETAGS_ENABLED} so users who
   *  want nametag recolouring without outlines (or vice versa) can pick
   *  exactly one half. */
  public static final String VETS_ANNI_OUTLINES_ENABLED = "vetsAnniOutlinesEnabled";

  /** S4 — Master toggle for the per-player nametag overlay (role colour
   *  on own-party members, light-grey on other-vets-party members, dark-
   *  grey on outsiders). Gated on the same window+zone+mode predicate as
   *  {@link #VETS_ANNI_OUTLINES_ENABLED}. Default {@code true}. The
   *  branch added to {@code NametagMixin} runs before the supporter
   *  glint branch — so an own-party supporter shows the role colour
   *  for the duration of the highlight gate and reverts to the
   *  animated supporter glint afterwards. */
  public static final String VETS_ANNI_NAMETAGS_ENABLED = "vetsAnniNametagsEnabled";

  /** S5 — Master toggle for the zone-line renderer (the union of 48-block
   *  disc circumferences drawn in-world). Honoured only when
   *  {@link #VETS_ANNI_MODE} is {@code "aggressive"} and the snapshot's
   *  countdown is in the T-2h..T+30m window. Default {@code true}. */
  public static final String VETS_ANNI_ZONE_LINES = "vetsAnniZoneLines";

  /** S5 — Master toggle for the Scroll Spot waypoint (Wynntils
   *  {@code MarkerProvider} for the party's pinned scroll spot). Default
   *  {@code true}; honoured only in aggressive mode + window. */
  public static final String VETS_ANNI_SCROLL_WAYPOINT = "vetsAnniScrollWaypoint";

  /** S5 — Master toggle for the diff-aware chat-alert dispatcher (role /
   *  world / party assignment / RSVP transitions plus the time-triggered
   *  T-10m world-mismatch and T-5m zone-absence readiness alerts). Default
   *  {@code true}; honoured only in aggressive mode + window. */
  public static final String VETS_ANNI_CHAT_ALERTS = "vetsAnniChatAlerts";

  /** S5 — Master toggle for the {@code [Suggest: /toggle ghosts none]}
   *  prompt fired at most once per stamp_epoch on first zone entry per
   *  anni. Suppressed when {@code Models.Player.isPlayerGhost} confirms
   *  every visible player is non-ghost (= user already toggled ghosts off).
   *  Default {@code true}. */
  public static final String VETS_ANNI_GHOSTS_PROMPT = "vetsAnniGhostsPrompt";

  /** S5 — Persisted "stamp_epoch of the anni for which the ghosts prompt
   *  last fired". Internal sentinel — not a user-facing knob; ensures a
   *  client restart inside the same window doesn't re-prompt. Empty string
   *  means "never fired in any session". */
  public static final String VETS_ANNI_GHOSTS_PROMPT_SHOWN_FOR_STAMP =
      "vetsAnniGhostsPromptShownForStamp";

  /** Valid values for {@link #VETS_ANNI_FLASH_INTENSITY}. */
  public static final String[] VALID_FLASH_INTENSITIES = {
      "subtle", "normal", "strong",
  };

  /** Whether bridge (guild chat relay) messages are displayed in chat. */
  public static final String PRINT_BRIDGE_MESSAGES = "printBridgeMessages";

  /** Whether supporter animated gradient glints are shown on nametags and pills. */
  public static final String SHOW_SUPPORTER_GLINTS = "showSupporterGlints";

  /** Whether to render glints (and other future colour-coded UI) with a colour-vision-deficiency
   *  friendly palette: the supporter glint's two-colour shimmer keeps the cyan/blue family but
   *  widens the luminance delta so the alternation is visible to protan/deutan users for whom
   *  the default {@code 0x55FFFF ↔ 0xAADDFF} pair flattens to a single tone. Off by default. */
  public static final String COLOR_BLIND_MODE = "colorBlindMode";

  /** Whether {@code ||spoiler||} markers are rendered as hoverable spoiler labels. */
  public static final String HANDLE_SPOILERS = "handleSpoilers";

  /** Whether the mod runs its own {@code /gu stats} check on world join instead of
   *  relying solely on Wynntils' guild detection (which can remain null). */
  public static final String MORE_RELIABLE_GUILD_CHECK = "moreReliableGuildCheck";

  /** Whether to show the {@code ✅ vetsmod authentication verified — tier: …}
   *  notification on the next successful auth-frame ack. The auth flag is
   *  re-sent on every WebSocket (re)connect, so without gating the message
   *  would fire repeatedly. {@link org.wynnvets.guild.UnlockManager} flips
   *  this to {@code false} after rendering once and resets it to {@code true}
   *  whenever an auth failure occurs, so a single confirmation is shown after
   *  each error→success transition. The user can also manually re-enable it
   *  via {@code /wv config printSuccessfulAuth true} to force the next ack
   *  to display. */
  public static final String PRINT_SUCCESSFUL_AUTH = "printSuccessfulAuth";

  /** CSS/Minecraft colour name for the top of the gradient drawn behind legacy item icons.
   *  Defaults to {@code orange}. */
  public static final String LEGACY_ITEM_BACKGROUND_GRADIENT_TOP = "legacyItemBackgroundGradientTop";

  /** CSS/Minecraft colour name for the bottom of the gradient drawn behind legacy item icons.
   *  Defaults to {@code crimson}. */
  public static final String LEGACY_ITEM_BACKGROUND_GRADIENT_BOTTOM = "legacyItemBackgroundGradientBottom";

  /** Opacity (0–100%) for the top of the legacy-item background gradient.
   *  Defaults to 69 (~69%, matching the old 0xB0 alpha). */
  public static final String LEGACY_ITEM_BACKGROUND_GRADIENT_TOP_OPACITY = "legacyItemBackgroundGradientTopOpacity";

  /** Opacity (0–100%) for the bottom of the legacy-item background gradient.
   *  Defaults to 100 (fully opaque); it is the *top* stop that defaults to 69. */
  public static final String LEGACY_ITEM_BACKGROUND_GRADIENT_BOTTOM_OPACITY = "legacyItemBackgroundGradientBottomOpacity";

  /** Which Wynntils highlight sprite to draw over the gradient.
   *  One of: wynn, tag, circle_transparent, circle_opaque, circle_outline_large,
   *  circle_outline_small, box_transparent, box_opaque, box_gradient_1, box_gradient_2.
   *  Defaults to {@code box_gradient_2}. */
  public static final String LEGACY_ITEM_FOREGROUND_SPRITE = "legacyItemForegroundSprite";

  /** CSS/Minecraft colour name used to tint the foreground sprite on legacy item slots.
   *  Defaults to {@code orange}. */
  public static final String LEGACY_ITEM_FOREGROUND_COLOR = "legacyItemForegroundColor";

  /**
   * Ordered list of configuration keys that can be toggled by the player via
   * {@code /wv config <key> <value>}.  Internal keys (staff status, timestamps,
   * etc.) are intentionally excluded.
   */
  public static final String[] USER_CONFIG_KEYS = {
      LEGACY_ITEM_HIGHLIGHTING,
      LEGACY_ITEM_SHOW_ENCHANTMENTS,
      LEGACY_ITEM_BACKGROUND_GRADIENT_TOP,
      LEGACY_ITEM_BACKGROUND_GRADIENT_TOP_OPACITY,
      LEGACY_ITEM_BACKGROUND_GRADIENT_BOTTOM,
      LEGACY_ITEM_BACKGROUND_GRADIENT_BOTTOM_OPACITY,
      LEGACY_ITEM_FOREGROUND_SPRITE,
      LEGACY_ITEM_FOREGROUND_COLOR,
      PRINT_MOTD,
      PRINT_ANNI,
      VETS_ANNI_ENABLED,
      VETS_ANNI_SHOW_HOVER_DETAILS,
      VETS_ANNI_PROMPT_RSVP,
      VETS_ANNI_SHOW_PREDICTION,
      VETS_ANNI_ROLE_STYLE,
      VETS_ANNI_BOSSBAR_ENABLED,
      VETS_ANNI_FLASH_INTENSITY,
      VETS_ANNI_FLASH_SOUND,
      VETS_ANNI_OUTLINES_ENABLED,
      VETS_ANNI_NAMETAGS_ENABLED,
      VETS_ANNI_ZONE_LINES,
      VETS_ANNI_SCROLL_WAYPOINT,
      VETS_ANNI_CHAT_ALERTS,
      VETS_ANNI_GHOSTS_PROMPT,
      PRINT_BRIDGE_MESSAGES,
      PRINT_SUCCESSFUL_AUTH,
      SHOW_SUPPORTER_GLINTS,
      COLOR_BLIND_MODE,
      HANDLE_SPOILERS,
      MORE_RELIABLE_GUILD_CHECK,
  };

  /**
   * Subset of user-facing keys that support tri-state values: {@code true},
   * {@code false}, or {@code null} (meaning "use default behaviour").
   */
  public static final String[] TRISTATE_KEYS = {
      HANDLE_SPOILERS,
  };

  /**
   * Subset of user-facing keys that store a string value.
   */
  public static final String[] STRING_CONFIG_KEYS = {
      LEGACY_ITEM_BACKGROUND_GRADIENT_TOP,
      LEGACY_ITEM_BACKGROUND_GRADIENT_BOTTOM,
      LEGACY_ITEM_FOREGROUND_SPRITE,
      LEGACY_ITEM_FOREGROUND_COLOR,
      VETS_ANNI_ROLE_STYLE,
      VETS_ANNI_MODE,
      VETS_ANNI_FLASH_INTENSITY,
  };

  /**
   * Subset of user-facing keys that store an integer value (persisted as long).
   */
  public static final String[] INT_CONFIG_KEYS = {
      LEGACY_ITEM_BACKGROUND_GRADIENT_TOP_OPACITY,
      LEGACY_ITEM_BACKGROUND_GRADIENT_BOTTOM_OPACITY,
  };

  /** Valid sprite names for {@link #LEGACY_ITEM_FOREGROUND_SPRITE}, matching
   *  the Wynntils {@code HighlightTexture} enum order (parallel to
   *  {@code LegacyItemStyle.FOREGROUND_TEXTURES}). */
  public static final String[] VALID_SPRITES = {
      "wynn", "tag", "circle_transparent", "circle_opaque",
      "circle_outline_large", "circle_outline_small",
      "box_transparent", "box_opaque", "box_gradient_1", "box_gradient_2",
  };

  // ── Colour / sprite helpers ───────────────────────────────────────────
  // Delegated to LegacyItemStyle.  Thin wrappers kept here for backward
  // compatibility with mixin FQN callsites.

  /** @see LegacyItemStyle#getColorNames() */
  public static Set<String> getColorNames() {
    return LegacyItemStyle.getColorNames();
  }

  /** @see LegacyItemStyle#isValidColor(String) */
  public static boolean isValidColor(String value) {
    return LegacyItemStyle.isValidColor(value);
  }

  /** @see LegacyItemStyle#isValidSprite(String) */
  public static boolean isValidSprite(String value) {
    return LegacyItemStyle.isValidSprite(value);
  }

  /** @see LegacyItemStyle#getBackgroundGradientTopColor() */
  public static int getLegacyBackgroundGradientTopColor() {
    return LegacyItemStyle.getBackgroundGradientTopColor();
  }

  /** @see LegacyItemStyle#getBackgroundGradientBottomColor() */
  public static int getLegacyBackgroundGradientBottomColor() {
    return LegacyItemStyle.getBackgroundGradientBottomColor();
  }

  /** @see LegacyItemStyle#getForegroundColor() */
  public static int getLegacyForegroundColor() {
    return LegacyItemStyle.getForegroundColor();
  }

  /** @see LegacyItemStyle#getColorRgb(String) */
  public static int getColorRgb(String name) {
    return LegacyItemStyle.getColorRgb(name);
  }

  /** @see LegacyItemStyle#getForegroundTexture() */
  public static Texture getLegacyForegroundTexture() {
    return LegacyItemStyle.getForegroundTexture();
  }

  // ── Int config defaults (for reset) ─────────────────────────────────

  private static final Map<String, Long> INT_DEFAULTS = Map.of(
      LEGACY_ITEM_BACKGROUND_GRADIENT_TOP_OPACITY, 69L,
      LEGACY_ITEM_BACKGROUND_GRADIENT_BOTTOM_OPACITY, 100L
  );

  /**
   * Returns the default value for an int config key, or {@code null} if
   * the key is not an int config key.
   */
  public static Long getIntDefault(String key) {
    return INT_DEFAULTS.get(key);
  }

  // ── String config defaults (for reset) ──────────────────────────────

  private static final Map<String, String> STRING_DEFAULTS = Map.of(
      LEGACY_ITEM_BACKGROUND_GRADIENT_TOP, "orange",
      LEGACY_ITEM_BACKGROUND_GRADIENT_BOTTOM, "crimson",
      LEGACY_ITEM_FOREGROUND_SPRITE, "box_gradient_2",
      LEGACY_ITEM_FOREGROUND_COLOR, "orange",
      VETS_ANNI_ROLE_STYLE, "descriptive",
      VETS_ANNI_MODE, "silent",
      VETS_ANNI_FLASH_INTENSITY, "normal"
  );

  /**
   * Returns the default value for a string config key, or {@code null} if
   * the key is not a string config key.
   */
  public static String getStringDefault(String key) {
    return STRING_DEFAULTS.get(key);
  }

  // Default values
  static {
    // Internal defaults
    config.put(VETS_AUTOMESSAGE, true);
    config.put(VETS_IS_STAFF, false);
    longConfig.put(VETS_LAST_STAFF_CHECK, 0L);
    longConfig.put(VETS_WAITLIST_UNLOCK_TIME, 0L);
    longConfig.put(VETS_HONOURARY_UNLOCK_TIME, 0L);
    longConfig.put(VETS_UNLOCK_EXPIRY_WARNINGS, 0L);
    longConfig.put(VETS_GUILD_CHECK_RESULT, 0L);
    longConfig.put(VETS_LAST_GUILD_CHECK, 0L);
    longConfig.put(VETS_DEBUG_ENABLED_AT, 0L);
    config.put(VETS_ANNI_ENABLED, false);
    config.put(VETS_ANNI_MODE_USER_SET, false);

    // /unlock <key> auth state — keys are persisted strings, the timestamp
    // is a long. Defaults to empty key + epoch=0 = "never authenticated".
    stringConfig.put(VETS_AUTH_KEY, "");
    stringConfig.put(VETS_AUTH_TIER, "");
    longConfig.put(VETS_AUTH_VERIFIED_AT, 0L);

    // User-facing BOOLEAN defaults — all true except colorBlindMode.
    // (moreReliableGuildCheck is true, despite what this comment used to say.)
    // This block holds 20 of the 30 USER_CONFIG_KEYS. The other ten default
    // outside it: vetsAnniEnabled (false, with the internal keys above), the
    // tri-state handleSpoilers (null = on) and the six string plus two int
    // user-facing keys, all just below.
    config.put(LEGACY_ITEM_HIGHLIGHTING, true);
    config.put(LEGACY_ITEM_SHOW_ENCHANTMENTS, true);
    config.put(PRINT_MOTD, true);
    config.put(PRINT_ANNI, true);
    config.put(VETS_ANNI_SHOW_HOVER_DETAILS, true);
    config.put(VETS_ANNI_PROMPT_RSVP, true);
    config.put(VETS_ANNI_SHOW_PREDICTION, true);
    config.put(VETS_ANNI_BOSSBAR_ENABLED, true);
    config.put(VETS_ANNI_FLASH_SOUND, true);
    config.put(VETS_ANNI_OUTLINES_ENABLED, true);
    config.put(VETS_ANNI_NAMETAGS_ENABLED, true);
    config.put(VETS_ANNI_ZONE_LINES, true);
    config.put(VETS_ANNI_SCROLL_WAYPOINT, true);
    config.put(VETS_ANNI_CHAT_ALERTS, true);
    config.put(VETS_ANNI_GHOSTS_PROMPT, true);
    config.put(PRINT_BRIDGE_MESSAGES, true);
    config.put(PRINT_SUCCESSFUL_AUTH, true);
    config.put(SHOW_SUPPORTER_GLINTS, true);
    config.put(COLOR_BLIND_MODE, false);
    config.put(MORE_RELIABLE_GUILD_CHECK, true);

    // Tri-state defaults (null = use default behaviour)
    triStateConfig.put(HANDLE_SPOILERS, null);

    // String defaults
    stringConfig.put(LEGACY_ITEM_BACKGROUND_GRADIENT_TOP, "orange");
    stringConfig.put(LEGACY_ITEM_BACKGROUND_GRADIENT_BOTTOM, "crimson");
    stringConfig.put(LEGACY_ITEM_FOREGROUND_SPRITE, "box_gradient_2");
    stringConfig.put(LEGACY_ITEM_FOREGROUND_COLOR, "orange");
    stringConfig.put(VETS_ANNI_ROLE_STYLE, "descriptive");
    stringConfig.put(VETS_ANNI_MODE, "silent");
    stringConfig.put(VETS_ANNI_FLASH_INTENSITY, "normal");
    stringConfig.put(VETS_ANNI_GHOSTS_PROMPT_SHOWN_FOR_STAMP, "");
    stringConfig.put(VETS_ANNI_USER_MODE, "");

    // Int defaults (stored as long)
    longConfig.put(LEGACY_ITEM_BACKGROUND_GRADIENT_TOP_OPACITY, 69L);
    longConfig.put(LEGACY_ITEM_BACKGROUND_GRADIENT_BOTTOM_OPACITY, 100L);
  }

  /**
   * Get the value of a configuration option
   *
   * @param key The configuration key
   * @return The current value, or false if the key doesn't exist
   */
  public static boolean get(String key) {
    return config.getOrDefault(key, false);
  }

  /**
   * Set the value of a configuration option
   *
   * @param key   The configuration key
   * @param value The new value
   * @return true if the key exists and was updated, false otherwise
   */
  public static boolean set(String key, boolean value) {
    if (config.containsKey(key)) {
      config.put(key, value);
      save();
      return true;
    }
    return false;
  }

  /**
   * Get the tri-state value of a configuration option.
   *
   * @param key The configuration key
   * @return {@code Boolean.TRUE}, {@code Boolean.FALSE}, or {@code null} (default)
   */
  public static Boolean getTriState(String key) {
    if (!triStateConfig.containsKey(key)) return null;
    return triStateConfig.get(key);
  }

  /**
   * Set the tri-state value of a configuration option.
   *
   * @param key   The configuration key
   * @param value {@code true}, {@code false}, or {@code null} for default
   * @return true if the key exists and was updated, false otherwise
   */
  public static boolean setTriState(String key, Boolean value) {
    if (!triStateConfig.containsKey(key)) return false;
    triStateConfig.put(key, value);
    save();
    return true;
  }

  /**
   * Get the value of a long configuration option.
   *
   * @param key The configuration key
   * @return The current value, or 0 if the key doesn't exist
   */
  public static long getLong(String key) {
    return longConfig.getOrDefault(key, 0L);
  }

  /**
   * Set the value of a long configuration option.
   *
   * @param key   The configuration key
   * @param value The new value
   * @return true if the key exists and was updated, false otherwise
   */
  public static boolean setLong(String key, long value) {
    if (longConfig.containsKey(key)) {
      longConfig.put(key, value);
      save();
      return true;
    }
    return false;
  }

  /**
   * Registers a boolean configuration key with its default value.
   * If the key already exists, this is a no-op.  Intended for use by
   * subsystems that own their own config keys (e.g. debug utilities).
   * Must be called <em>before</em> {@link #load()} so that the key is
   * present when persisted values are read from disk.
   *
   * @param key          The configuration key
   * @param defaultValue The default value
   */
  public static void registerDefault(String key, boolean defaultValue) {
    config.putIfAbsent(key, defaultValue);
  }

  /**
   * Check if a configuration key is user-facing (togglable via {@code /wv config}).
   *
   * @param key The configuration key to check
   * @return true if it is a user-configurable key
   */
  public static boolean isUserConfigKey(String key) {
    for (String userKey : USER_CONFIG_KEYS) {
      if (userKey.equals(key)) return true;
    }
    return false;
  }

  /**
   * Check if a configuration key is a tri-state key (supports true/false/default).
   *
   * @param key The configuration key to check
   * @return true if it is a tri-state key
   */
  public static boolean isTriStateKey(String key) {
    for (String tsKey : TRISTATE_KEYS) {
      if (tsKey.equals(key)) return true;
    }
    return false;
  }

  /**
   * Check if a configuration key is a string key (stores one of a fixed set
   * of string values).
   *
   * @param key The configuration key to check
   * @return true if it is a string config key
   */
  public static boolean isStringKey(String key) {
    for (String sk : STRING_CONFIG_KEYS) {
      if (sk.equals(key)) return true;
    }
    return false;
  }

  /**
   * Check if a configuration key is an integer key (stored as long).
   *
   * @param key The configuration key to check
   * @return true if it is an int config key
   */
  public static boolean isIntKey(String key) {
    for (String ik : INT_CONFIG_KEYS) {
      if (ik.equals(key)) return true;
    }
    return false;
  }

  /**
   * Get the string value of a configuration option.
   *
   * @param key The configuration key
   * @return The current value, or {@code null} if the key doesn't exist
   */
  public static String getString(String key) {
    return stringConfig.get(key);
  }

  /**
   * Set the string value of a configuration option.
   *
   * @param key   The configuration key
   * @param value The new value (must be one of the valid options for this key)
   * @return true if the key exists and was updated, false otherwise
   */
  public static boolean setString(String key, String value) {
    if (!stringConfig.containsKey(key)) return false;
    stringConfig.put(key, value);
    save();
    return true;
  }

  /**
   * Load configuration from file
   * Should be called during mod initialization
   */
  public static void load() {
    if (!Files.exists(CONFIG_FILE)) {
      VetsLogger.debug("Config file not found, creating with defaults");
      save(); // Create the file with defaults
      return;
    }

    try {
      String json = Files.readString(CONFIG_FILE);
      VetsLogger.debug("Loading config from: {}", CONFIG_FILE);
      JsonObject loadedConfig = GSON.fromJson(json, JsonObject.class);

      if (loadedConfig != null) {
        // Load booleans
        for (String key : config.keySet()) {
          JsonElement element = loadedConfig.get(key);
          if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()) {
            boolean value = element.getAsBoolean();
            config.put(key, value);
            VetsLogger.debug("Config: {} = {}", key, value);
          }
        }

        // Load longs
        for (String key : longConfig.keySet()) {
          JsonElement element = loadedConfig.get(key);
          if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            long value = element.getAsLong();
            longConfig.put(key, value);
            VetsLogger.debug("Config: {} = {}", key, value);
          }
        }

        // Load tri-state values (absent or null in JSON → default)
        for (String key : triStateConfig.keySet()) {
          JsonElement element = loadedConfig.get(key);
          if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()) {
            Boolean value = element.getAsBoolean();
            triStateConfig.put(key, value);
            VetsLogger.debug("Config: {} = {}", key, value);
          } else {
            triStateConfig.put(key, null);
            VetsLogger.debug("Config: {} = default", key);
          }
        }

        // Load string values
        for (String key : stringConfig.keySet()) {
          JsonElement element = loadedConfig.get(key);
          if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String value = element.getAsString();
            stringConfig.put(key, value);
            VetsLogger.debug("Config: {} = {}", key, value);
          }
        }

        VetsLogger.debug("Configuration loaded");
      }
    } catch (IOException e) {
      VetsLogger.warn("Failed to load config: {}", e.getMessage());
    }
  }

  /**
   * Save configuration to file
   */
  private static void save() {
    try {
      // Ensure the config directory exists
      Files.createDirectories(CONFIG_FILE.getParent());

      // Write the config to file
      JsonObject serialized = new JsonObject();
      for (Map.Entry<String, Boolean> entry : config.entrySet()) {
        serialized.addProperty(entry.getKey(), entry.getValue());
      }
      for (Map.Entry<String, Long> entry : longConfig.entrySet()) {
        serialized.addProperty(entry.getKey(), entry.getValue());
      }
      for (Map.Entry<String, Boolean> entry : triStateConfig.entrySet()) {
        if (entry.getValue() != null) {
          serialized.addProperty(entry.getKey(), entry.getValue());
        }
      }
      for (Map.Entry<String, String> entry : stringConfig.entrySet()) {
        serialized.addProperty(entry.getKey(), entry.getValue());
      }

      String json = GSON.toJson(serialized);
      Files.writeString(CONFIG_FILE, json);
      VetsLogger.debug("Configuration saved");
    } catch (IOException e) {
      VetsLogger.warn("Failed to save config: {}", e.getMessage());
    }
  }
}
