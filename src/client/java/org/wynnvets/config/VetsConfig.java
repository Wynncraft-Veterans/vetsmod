package org.wynnvets.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wynntils.utils.render.Texture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import org.wynnvets.logging.VetsLogger;

/**
 * Configuration manager for VetsMod toggleable features.
 *
 * <p>Provides boolean, long, string and tri-state configuration options,
 * persisted as JSON to {@code vetsmod/storage/config.json}.  Keys fall
 * into three categories:</p>
 * <ul>
 *   <li><b>Internal</b> — used by the mod to cache transient state (e.g.
 *       {@link #VETS_IS_STAFF}, {@link #VETS_LAST_STAFF_CHECK}).  These are
 *       <em>not</em> exposed to the {@code /wv config} command.</li>
 *   <li><b>User-facing</b> — toggleable by the player via {@code /wv config
 *       &lt;key&gt; &lt;value&gt;}.  Listed in {@link #USER_CONFIG_KEYS}.</li>
 *   <li><b>Debug</b> — declared in
 *       {@link org.wynnvets.debug.DebugConfigManager DebugConfigManager} rather
 *       than here, and registered as boolean keys through
 *       {@link #registerDefault(String, boolean)}.  Not in
 *       {@link #USER_CONFIG_KEYS}; set through {@code /wv debug set}.</li>
 * </ul>
 */
public class VetsConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_FILE =
            FabricLoader.getInstance().getGameDir().resolve("vetsmod/storage/config.json");

    // Store configuration values by type
    private static final Map<String, Boolean> config = new ConcurrentHashMap<>();
    private static final Map<String, Long> longConfig = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> triStateConfig =
            Collections.synchronizedMap(new HashMap<>());
    private static final Map<String, String> stringConfig = new ConcurrentHashMap<>();

    // ── Internal configuration keys (not user-facing, except VETS_ANNI_ENABLED) ────
    public static final String VETS_AUTOMESSAGE = "vetsAutomessage";
    public static final String VETS_IS_STAFF = "vetsIsStaff";
    public static final String VETS_LAST_STAFF_CHECK = "vetsLastStaffCheck";
    public static final String VETS_WAITLIST_UNLOCK_TIME = "vetsWaitlistUnlockTime";
    public static final String VETS_HONOURARY_UNLOCK_TIME = "vetsHonouraryUnlockTime";
    public static final String VETS_GUILD_CHECK_RESULT = "vetsGuildCheckResult";
    public static final String VETS_LAST_GUILD_CHECK = "vetsLastGuildCheck";
    public static final String VETS_DEBUG_ENABLED_AT = "vetsDebugEnabledAt";

    /** Gates the snapshot-driven render paths in
     *  {@link org.wynnvets.fetcher.ondemand.StampFetcher StampFetcher}: with it off, the world-join
     *  anni-motd and {@code /wv anni} take the legacy stamp path, and each skips its own cold-cache
     *  {@link org.wynnvets.mwe.anni.network.AnniQueryClient#query() AnniQueryClient#query()} pull.
     *  It does not gate the boss bar, the highlights or the aggressive components (those key off
     *  {@link #VETS_ANNI_MODE}), and it is independent of the anni mode itself
     *  ({@link org.wynnvets.mwe.anni.mode.AnniModeManager AnniModeManager} never reads it).
     *  {@link org.wynnvets.mwe.anni.network.AnniWsHandler AnniWsHandler}'s post-connect re-pull and
     *  {@link org.wynnvets.fetcher.polling.AnniSnapshotPoller AnniSnapshotPoller} read neither key.
     *  {@link #PRINT_ANNI} suppresses the world-join line earlier, before {@code StampFetcher}
     *  runs. Off by default.
     *  <p>Meant to give vets players the enriched view without a manual toggle. Today
     *  {@link org.wynnvets.api.V1ApiManager V1ApiManager}'s auth-ack handler sets it {@code true}
     *  whenever an ok auth ack with tier member, waitlist or honourary finds it {@code false}, so
     *  a vets-tier opt-out lasts only until the next ack. Anyone can opt in with
     *  {@code /wv config vetsAnniEnabled true}, but the pull it enables asks for the authenticated
     *  session's own snapshot
     *  ({@link org.wynnvets.api.V1ApiManager#sendAnniQuery() V1ApiManager#sendAnniQuery()} names
     *  no UUID), so until an {@code /unlock} key has authenticated the inbound socket it resolves
     *  to no snapshot and both callers fall back to the legacy path. Slated for retirement
     *  ({@code vets-anni-enabled-to-be-retired}). */
    public static final String VETS_ANNI_ENABLED = "vetsAnniEnabled";

    // ── Vetsmod /unlock <key> auth state ─────────────────────────────────
    /** Bearer key issued by dazebot's /vetsmod command and stored on disk so
     *  it survives mod restarts. Sent in an {@code auth} frame on every (re)connect of the
     *  inbound v1 WebSocket, and straight away when {@code /unlock <key>} stores a new key
     *  while that socket is up. Empty string when the user hasn't run /unlock yet. */
    public static final String VETS_AUTH_KEY = "vetsAuthKey";

    /** Tier (member/waitlist/honourary/other) that the last successful auth ack reported,
     *  refreshed on every such ack. A new {@code /unlock} key or a rejection does not clear
     *  it, so it can belong to an earlier key. Nothing gates on it; it is read only for the
     *  {@code /wv debug} diagnostics log
     *  ({@link org.wynnvets.debug.diagnostics.DiagnosticsHandler DiagnosticsHandler}). */
    public static final String VETS_AUTH_TIER = "vetsAuthTier";

    /** Epoch millis of the last successful auth-frame ack, or 0. Only displayed, by the
     *  diagnostics dump
     *  ({@link org.wynnvets.debug.diagnostics.DiagnosticsHandler DiagnosticsHandler}); no
     *  warning gates on it. */
    public static final String VETS_AUTH_VERIFIED_AT = "vetsAuthVerifiedAt";

    // ── User-facing configuration keys (toggled via /wv config) ─────────────
    // Not every key declared below is user-facing: USER_CONFIG_KEYS is the
    // list /wv config reaches.

    /** Whether legacy/enchanted/junk item highlighting is shown in tooltips and inventory slots. */
    public static final String LEGACY_ITEM_HIGHLIGHTING = "legacyItemHighlighting";

    /** Whether legacy items the enchant branch already highlights get their
     *  specific enchantment named: as a LEGACY ENCHANTMENTS block on the
     *  powder page of paged tooltips, or in the tooltip text on items that
     *  have no pages (see {@link
     *  org.wynnvets.items.LegacyEnchantmentRenderer#showEnchantment
     *  LegacyEnchantmentRenderer#showEnchantment}).
     *  Has no effect unless {@link #LEGACY_ITEM_HIGHLIGHTING} is on. */
    public static final String LEGACY_ITEM_SHOW_ENCHANTMENTS = "legacyItemShowEnchantments";

    /** Whether the MOTD is automatically printed on world join. */
    public static final String PRINT_MOTD = "printMOTD";

    /** Whether the annihilation stamp (the anni-motd, when
     *  {@link #VETS_ANNI_ENABLED} and a cached snapshot allow it) is
     *  printed automatically on world join. vetsmod makes that print only
     *  for Returners guild members; see
     *  {@link org.wynnvets.guild.GuildStateManager GuildStateManager}. */
    public static final String PRINT_ANNI = "printANNI";

    /** Whether the snapshot-driven {@code /wv anni} and anni-motd populate
     *  hover tooltips on segments (role chips, RSVP badges, attendance bar,
     *  party world chip). When {@code false}, the same lines render with no
     *  hover — keeps the click-to-open URLs but strips the descriptive
     *  hover text. */
    public static final String VETS_ANNI_SHOW_HOVER_DETAILS = "vetsAnniShowHoverDetails";

    /** Whether the snapshot-driven anni-motd prints its second line, the
     *  player's placement / RSVP status, under the countdown. When
     *  {@code false} the motd is the countdown line alone. The RSVP widgets
     *  of {@code /wv anni} do not read this key. */
    public static final String VETS_ANNI_PROMPT_RSVP = "vetsAnniPromptRsvp";

    /** Whether {@code /wv anni} shows the {@code \guess}-style prediction
     *  window (earliest/median/latest) when the stamp is past or unknown.
     *  The anni-motd auto-print never shows the prediction unsolicited (per
     *  spec §"For external users"); this key only affects the manual
     *  {@code /wv anni} invocation. */
    public static final String VETS_ANNI_SHOW_PREDICTION = "vetsAnniShowPrediction";

    /** Active anni mode — {@code silent}, {@code passive}, or
     *  {@code aggressive}. Set only through
     *  {@link org.wynnvets.mwe.anni.mode.AnniModeManager#transitionTo
     *  AnniModeManager#transitionTo}, whose {@code Source} enum names every trigger:
     *  {@code /wv anni <mode>} (and its buttons), the automatic transitions, and a
     *  debug override. Outside the mode package it is read through
     *  {@code AnniModeManager.current()}, by
     *  {@link org.wynnvets.mwe.anni.bossbar.VetsBossBarManager VetsBossBarManager} (S3+),
     *  {@link org.wynnvets.mwe.anni.outline.AnniOutlineTicker AnniOutlineTicker} (S4+) and
     *  {@link org.wynnvets.mwe.anni.aggressive.AnniAggressiveTicker AnniAggressiveTicker} (S5);
     *  {@link org.wynnvets.mwe.anni.mode.AnniModeManager AnniModeManager}'s class Javadoc lists
     *  the readers inside it.
     *  <b>No waypoint class reads the mode</b> &mdash; {@link org.wynnvets.mwe.anni.waypoint.ScrollSpotMarkerProvider ScrollSpotMarkerProvider} gates on
     *  {@link org.wynnvets.mwe.anni.aggressive.AnniAggressiveTicker#isAggressiveActive() AnniAggressiveTicker#isAggressiveActive()} instead, which is where the S5 reading
     *  comes from. Absent from {@link #USER_CONFIG_KEYS}, so {@code /wv
     *  config} cannot read or write it. When the anni window closes (T+30 min after stamp_epoch)
     *  {@link org.wynnvets.mwe.anni.mode.AnniWindowWatcher AnniWindowWatcher} restores
     *  {@link org.wynnvets.mwe.anni.mode.AnniModeManager#preferredMode()
     *  AnniModeManager#preferredMode()}, which is not an unconditional reset to
     *  {@code silent}: an explicit pick survives the window close. */
    public static final String VETS_ANNI_MODE = "vetsAnniMode";

    /** Valid values for {@link #VETS_ANNI_MODE}. */
    public static final String[] VALID_ANNI_MODES = {
        "silent", "passive", "aggressive",
    };

    /** Internal flag — {@code true} once the user has explicitly chosen an
     *  anni mode via {@code /wv anni <mode>} (or the mode-switch buttons under {@code /wv anni}). Set
     *  only for {@code Source.USER_COMMAND} transitions in
     *  {@link org.wynnvets.mwe.anni.mode.AnniModeManager AnniModeManager}; never touched by internal
     *  transitions (window-close, stream, startup). When {@code false}, {@link #VETS_ANNI_MODE} is
     *  treated as an unremembered default and gets overwritten to the eligibility-based default
     *  (PASSIVE for enrichment-eligible users, SILENT otherwise) at the next restore moment. Also
     *  serves as the pre-0.14.5 install boundary — old installs lack this key, so their
     *  pre-existing {@code vetsAnniMode} is discarded and the new default applies. */
    public static final String VETS_ANNI_MODE_USER_SET = "vetsAnniModeUserSet";

    /** Internal — snapshot of the user's most recent explicit anni-mode
     *  pick. Written alongside {@link #VETS_ANNI_MODE_USER_SET}. Kept
     *  separate from {@link #VETS_ANNI_MODE} so that internal transitions
     *  (a {@code /stream}-forced SILENT, in particular) don't clobber the
     *  user's preferred value — on stream-off we restore from this key. */
    public static final String VETS_ANNI_USER_MODE = "vetsAnniUserMode";

    /** Role-naming style for role names rendered through {@link
     *  org.wynnvets.mwe.anni.render.AnniHoverBuilder#displayRole(String)
     *  AnniHoverBuilder#displayRole}, such as {@code /wv anni}'s role chips and
     *  the anni-motd's assigned-party line. Three values:
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
     *  Unknown or unset style values render with the {@code descriptive}
     *  table. A role code that no table knows renders as the raw code,
     *  uppercased. */
    public static final String VETS_ANNI_ROLE_STYLE = "vetsAnniRoleStyle";

    /** Valid values for {@link #VETS_ANNI_ROLE_STYLE}; the first is the
     *  default. Brigadier sorts suggestions alphabetically, so
     *  tab-completion does not show this order. Today {@code /wv config}
     *  suggests these values but refuses to set any of them
     *  ({@code config-set-rejects-role-style-and-flash-intensity}). */
    public static final String[] VALID_ROLE_STYLES = {
        "descriptive", "short", "formal",
    };

    /** Master kill-switch for the synthetic vets-anni boss bar (S3).
     *  Default {@code true}; only honoured when {@link #VETS_ANNI_MODE}
     *  is {@code passive} or {@code aggressive} — silent is a strict
     *  no-op regardless. Lets advanced users keep the rest of the
     *  passive/aggressive subsystem (outlines, waypoint, alerts) while
     *  opting out of the boss bar specifically. Today neither this key nor
     *  silent mode stops the flash-sound pings ({@link #VETS_ANNI_FLASH_SOUND}),
     *  which still play on a snapshot change while the bar is down
     *  ({@code anni-flash-pings-play-while-bar-is-down}). */
    public static final String VETS_ANNI_BOSSBAR_ENABLED = "vetsAnniBossbarEnabled";

    /** Boss-bar pulse intensity controlling the {@code &l ↔ &n&l} flash
     *  duration on a per-field change (role / party / RSVP — the world
     *  chip has no timed window; it flashes while the mismatch holds).
     *  {@code subtle} = 5 s, {@code normal} = 10 s (default),
     *  {@code strong} = 20 s. The pulse half-period (250 ms) is fixed —
     *  this knob only affects the on-change flash duration, not the
     *  rate at which the bold/underline alternates. */
    public static final String VETS_ANNI_FLASH_INTENSITY = "vetsAnniFlashIntensity";

    /** Whether a role, party, RSVP or world change in the snapshot plays a two-ping sound. Spec
     *  §3.1.1 asked for the Wynntils name-mention sound, played twice; today the ping is
     *  {@code SoundEvents.EXPERIENCE_ORB_PICKUP}
     *  ({@code anni-flash-ping-is-not-the-wynntils-mention-sound}). The pings are meant to go with
     *  the boss bar's flashes, but today their queueing ignores the mode,
     *  {@link #VETS_ANNI_BOSSBAR_ENABLED} and the bar window, so they still play while the bar is
     *  down, {@code silent} included ({@code anni-flash-pings-play-while-bar-is-down}); of the
     *  config keys, only this one stops them. On by default. */
    public static final String VETS_ANNI_FLASH_SOUND = "vetsAnniFlashSound";

    /** S4 — Toggle for the per-player outline overlay: role-coloured glow on own-party members,
     *  light-grey glow on other-vets-party members, and suppression of native Wynncraft team
     *  outlines on outsiders. Separable from {@link #VETS_ANNI_NAMETAGS_ENABLED} so users who want
     *  nametag recolouring without outlines (or vice versa) can pick exactly one half.
     *  <p>Counts toward the highlight gate in
     *  {@link org.wynnvets.mwe.anni.outline.AnniOutlineTicker AnniOutlineTicker} (mode not silent,
     *  at least one of this and {@link #VETS_ANNI_NAMETAGS_ENABLED} on, T-2h..T+30m, in the anni
     *  zone), which reads it right after the mode check, whether or not the rest of the gate then
     *  holds. Past the gate, today it gates only the outsider-outline suppression: the
     *  registry-tier glow is applied whenever the gate holds, so with this off and nametags on,
     *  registry members still glow and the nametags-only half can't be had
     *  ({@code outlines-toggle-does-not-gate-registry-glow}). Default {@code true}. */
    public static final String VETS_ANNI_OUTLINES_ENABLED = "vetsAnniOutlinesEnabled";

    /** S4 — Master toggle for the per-player nametag overlay (role colour
     *  on own-party members, light-grey on other-vets-party members, dark- grey on outsiders). Gated
     *  on the same window+zone+mode predicate as {@link #VETS_ANNI_OUTLINES_ENABLED}. Default {@code
     *  true}. The branch added to {@link org.wynnvets.mixin.client.NametagMixin NametagMixin} runs
     *  before the supporter glint branch — so an own-party supporter shows the role colour for the
     *  duration of the highlight gate and reverts to the animated supporter glint afterwards.
     *  That ordering is the TAIL injector's; that mixin has two, and when wynnmod is
     *  present the TAIL path returns before the supporter branch and its {@code @WrapOperation}
     *  owns the glint instead. */
    public static final String VETS_ANNI_NAMETAGS_ENABLED = "vetsAnniNametagsEnabled";

    /** S5 — Master toggle for the zone-line renderer (the union of 48-block
     *  disc circumferences drawn in-world). Honoured only when
     *  {@link #VETS_ANNI_MODE} is {@code "aggressive"} and the snapshot's
     *  countdown is in the T-2h..T+30m window. Default {@code true}. */
    public static final String VETS_ANNI_ZONE_LINES = "vetsAnniZoneLines";

    /** S5 — Master toggle for the Scroll Spot waypoint (a Wynntils
     *  {@code MarkerProvider} for the party's scroll spot: the host-pinned
     *  one, or a fixed default until the host pins one; no marker outside a
     *  party). Default {@code true}; honoured only in aggressive mode and
     *  inside the hot window (T-2h .. T+30m). */
    public static final String VETS_ANNI_SCROLL_WAYPOINT = "vetsAnniScrollWaypoint";

    /** S5 — Master toggle for the diff-aware chat-alert dispatcher (role /
     *  world / party assignment / RSVP transitions plus the time-triggered
     *  T-10m world-mismatch and T-5m zone-absence readiness alerts). Default
     *  {@code true}; honoured only in aggressive mode and inside the
     *  hot window (T-2h .. T+30m). */
    public static final String VETS_ANNI_CHAT_ALERTS = "vetsAnniChatAlerts";

    /** S5 — Toggle for the {@code [Suggest: /toggle ghosts none]} prompt shown on a zone-entry
     *  rising edge while aggressive mode is on and the cached stamp is inside T-2h..T+30m. If its
     *  scan of visible players finds a ghost it fires on every such entry; otherwise it fires at
     *  most once per stamp_epoch, tracked in {@link #VETS_ANNI_GHOSTS_PROMPT_SHOWN_FOR_STAMP}. See
     *  {@link org.wynnvets.mwe.anni.aggressive.GhostsPromptHandler GhostsPromptHandler}. Default
     *  {@code true}. */
    public static final String VETS_ANNI_GHOSTS_PROMPT = "vetsAnniGhostsPrompt";

    /** S5 — Internal sentinel, not a user-facing knob: the stamp_epoch for which the ghosts prompt
     *  last fired on its no-ghost-seen branch, persisted so a restart inside the same window
     *  doesn't repeat that fire. A fire on the ghost-seen branch does not write it. Empty until the
     *  no-ghost-seen branch first fires. */
    public static final String VETS_ANNI_GHOSTS_PROMPT_SHOWN_FOR_STAMP =
            "vetsAnniGhostsPromptShownForStamp";

    /** Valid values for {@link #VETS_ANNI_FLASH_INTENSITY}. Today nothing reads
     *  this array, and {@code /wv config} refuses to set any of these values
     *  ({@code config-set-rejects-role-style-and-flash-intensity}). */
    public static final String[] VALID_FLASH_INTENSITIES = {
        "subtle", "normal", "strong",
    };

    /** Whether WebSocket-relayed chat is displayed: bridge messages and every other relayed
     *  chat type {@link org.wynnvets.chat.OutboundDisplayHandler OutboundDisplayHandler}
     *  renders. Server-pushed {@code warning} frames bypass it. */
    public static final String PRINT_BRIDGE_MESSAGES = "printBridgeMessages";

    /** Whether this client draws the supporter glint wherever vetsmod shows
     *  one: nametags, chat pills, and names in the {@code /wv list} roster. */
    public static final String SHOW_SUPPORTER_GLINTS = "showSupporterGlints";

    /** Whether to render glints (and other future colour-coded UI) with a
     *  colour-vision-deficiency friendly palette. In the chat glint (pills,
     *  and {@code /wv list} names) the cyan {@code 0x55FFFF ↔ 0xAADDFF}
     *  pair, and the grey pair {@code /wv list} uses, switch to
     *  wider-luminance variants of the same families. The nametag glint
     *  pulses the nametag's own colour rather than the cyan pair; this mode
     *  widens it to a swing from a darker to a much lighter copy of that
     *  colour. Off by default. */
    public static final String COLOR_BLIND_MODE = "colorBlindMode";

    /** Whether vetsmod handles {@code ||spoiler||} markers: it encodes the
     *  ones you type in {@code /g} or {@code /wg} messages vetsmod sends or
     *  relays, and renders received encoded spoilers as hoverable labels.
     *  Tri-state; {@code null} (the default) means on. */
    public static final String HANDLE_SPOILERS = "handleSpoilers";

    /** Whether the mod schedules its own {@code /gu stats} check after world join. A valid
     *  cached result (persisted, 3 days) takes precedence over Wynntils' guild detection in
     *  {@link org.wynnvets.guild.GuildStateManager GuildStateManager}'s {@code isReturners()} /
     *  {@code isGuildless()}; Wynntils' guild name
     *  is empty, not null, until its character-info scan or a guild-join message fills it. */
    public static final String MORE_RELIABLE_GUILD_CHECK = "moreReliableGuildCheck";

    /** Whether to show the {@code ✅ vetsmod authentication verified — tier: …}
     *  notification on the next successful auth-frame ack. The auth frame is
     *  re-sent on every (re)connect of the inbound v1 WebSocket, so without
     *  gating the message would fire repeatedly.
     *  {@link org.wynnvets.guild.UnlockManager} flips
     *  this to {@code false} after rendering once and resets it to {@code true}
     *  whenever an auth failure occurs, so a single confirmation is shown after
     *  each error→success transition. The user can also manually re-enable it
     *  via {@code /wv config printSuccessfulAuth true} to force the next ack
     *  to display. A {@code /unlock <key>} run forces the next ack to render
     *  regardless of this key, and does not re-arm it when it is already
     *  {@code false}. */
    public static final String PRINT_SUCCESSFUL_AUTH = "printSuccessfulAuth";

    /** CSS/Minecraft colour name for the top of the gradient drawn behind legacy item icons.
     *  Defaults to {@code orange}. */
    public static final String LEGACY_ITEM_BACKGROUND_GRADIENT_TOP =
            "legacyItemBackgroundGradientTop";

    /** CSS/Minecraft colour name for the bottom of the gradient drawn behind legacy item icons.
     *  Defaults to {@code crimson}. */
    public static final String LEGACY_ITEM_BACKGROUND_GRADIENT_BOTTOM =
            "legacyItemBackgroundGradientBottom";

    /** Opacity (0–100%) for the top of the legacy-item background gradient.
     *  Defaults to 69 (~69%, matching the old 0xB0 alpha). */
    public static final String LEGACY_ITEM_BACKGROUND_GRADIENT_TOP_OPACITY =
            "legacyItemBackgroundGradientTopOpacity";

    /** Opacity (0–100%) for the bottom of the legacy-item background gradient.
     *  Defaults to 100 (fully opaque); it is the *top* stop that defaults to 69. */
    public static final String LEGACY_ITEM_BACKGROUND_GRADIENT_BOTTOM_OPACITY =
            "legacyItemBackgroundGradientBottomOpacity";

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
     * String-valued keys for {@code /wv config}'s type dispatch: the
     * user-facing string keys, plus {@link #VETS_ANNI_MODE}, which is not
     * user-facing (bug
     * {@code config-commands-anni-mode-suggestion-arm-unreachable}; see
     * {@code vetsmod_config.md}, the "vetsAnniMode is not in this list"
     * note). Not a list of every string key — persistence types a key by
     * its backing map, not by this array.
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
        LEGACY_ITEM_BACKGROUND_GRADIENT_TOP_OPACITY, LEGACY_ITEM_BACKGROUND_GRADIENT_BOTTOM_OPACITY,
    };

    /** Valid sprite names for {@link #LEGACY_ITEM_FOREGROUND_SPRITE}, matching
     *  the Wynntils {@code HighlightTexture} enum order (parallel to
     *  {@link LegacyItemStyle#FOREGROUND_TEXTURES}). */
    public static final String[] VALID_SPRITES = {
        "wynn",
        "tag",
        "circle_transparent",
        "circle_opaque",
        "circle_outline_large",
        "circle_outline_small",
        "box_transparent",
        "box_opaque",
        "box_gradient_1",
        "box_gradient_2",
    };

    // ── Colour / sprite helpers ───────────────────────────────────────────
    // Thin delegates to LegacyItemStyle, kept from when mixins called them by
    // FQN; since the legacy highlight draws moved to LegacyHighlightPainter,
    // no mixin calls them any more.

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

    private static final Map<String, Long> INT_DEFAULTS =
            Map.of(
                    LEGACY_ITEM_BACKGROUND_GRADIENT_TOP_OPACITY, 69L,
                    LEGACY_ITEM_BACKGROUND_GRADIENT_BOTTOM_OPACITY, 100L);

    /**
     * Returns the default value for an int config key, or {@code null} if
     * the key is not an int config key.
     */
    public static Long getIntDefault(String key) {
        return INT_DEFAULTS.get(key);
    }

    // ── String config defaults (for reset) ──────────────────────────────

    private static final Map<String, String> STRING_DEFAULTS =
            Map.of(
                    LEGACY_ITEM_BACKGROUND_GRADIENT_TOP, "orange",
                    LEGACY_ITEM_BACKGROUND_GRADIENT_BOTTOM, "crimson",
                    LEGACY_ITEM_FOREGROUND_SPRITE, "box_gradient_2",
                    LEGACY_ITEM_FOREGROUND_COLOR, "orange",
                    VETS_ANNI_ROLE_STYLE, "descriptive",
                    VETS_ANNI_MODE, "silent",
                    VETS_ANNI_FLASH_INTENSITY, "normal");

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

    public static boolean isUserConfigKey(String key) {
        for (String userKey : USER_CONFIG_KEYS) {
            if (userKey.equals(key)) return true;
        }
        return false;
    }

    public static boolean isTriStateKey(String key) {
        for (String tsKey : TRISTATE_KEYS) {
            if (tsKey.equals(key)) return true;
        }
        return false;
    }

    public static boolean isStringKey(String key) {
        for (String sk : STRING_CONFIG_KEYS) {
            if (sk.equals(key)) return true;
        }
        return false;
    }

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
     * @param value The new value; not validated here, so callers own validation
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
                    if (element != null
                            && element.isJsonPrimitive()
                            && element.getAsJsonPrimitive().isBoolean()) {
                        boolean value = element.getAsBoolean();
                        config.put(key, value);
                        VetsLogger.debug("Config: {} = {}", key, value);
                    }
                }

                // Load longs
                for (String key : longConfig.keySet()) {
                    JsonElement element = loadedConfig.get(key);
                    if (element != null
                            && element.isJsonPrimitive()
                            && element.getAsJsonPrimitive().isNumber()) {
                        long value = element.getAsLong();
                        longConfig.put(key, value);
                        VetsLogger.debug("Config: {} = {}", key, value);
                    }
                }

                // Load tri-state values (absent or null in JSON → default)
                for (String key : triStateConfig.keySet()) {
                    JsonElement element = loadedConfig.get(key);
                    if (element != null
                            && element.isJsonPrimitive()
                            && element.getAsJsonPrimitive().isBoolean()) {
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
                    if (element != null
                            && element.isJsonPrimitive()
                            && element.getAsJsonPrimitive().isString()) {
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
