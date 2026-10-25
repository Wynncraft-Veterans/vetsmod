package org.wynnvets.config;

/**
 * The names of VetsMod's configuration keys. Each constant's value is the name the setting is
 * stored under in {@code vetsmod/storage/config.json}, so changing a value is a file-format
 * change. {@link VetsConfig} holds the values and reads and writes the file,
 * {@link VetsConfigSchema} the key lists and valid-value lists that describe them, and
 * {@link VetsConfigDefaults} their first-run defaults, whose shipped copy
 * {@link ShippedConfig} reads for {@code /wv config <key> default}.
 *
 * <p>Keys fall into these categories:</p>
 * <ul>
 *   <li><b>Internal</b> — used by the mod to cache transient state (e.g.
 *       {@link #VETS_IS_STAFF}, {@link #VETS_LAST_STAFF_CHECK}).  These are
 *       <em>not</em> exposed to the {@code /wv config} command.</li>
 *   <li><b>User-facing</b> — toggleable by the player via {@code /wv config
 *       &lt;key&gt; &lt;value&gt;}.  Listed in {@link VetsConfigSchema#USER_CONFIG_KEYS}.</li>
 *   <li><b>Debug</b> — declared in
 *       {@link org.wynnvets.debug.DebugConfigManager DebugConfigManager} rather
 *       than here, and registered as boolean keys through
 *       {@link VetsConfig#registerDefault(String, boolean)}.  Not in
 *       {@link VetsConfigSchema#USER_CONFIG_KEYS}; set through {@code /wv debug set}.</li>
 * </ul>
 */
public final class VetsConfigKeys {
    // ── Internal configuration keys (not user-facing) ────
    public static final String VETS_AUTOMESSAGE = "vetsAutomessage";
    public static final String VETS_IS_STAFF = "vetsIsStaff";
    public static final String VETS_LAST_STAFF_CHECK = "vetsLastStaffCheck";
    public static final String VETS_GUILD_CHECK_RESULT = "vetsGuildCheckResult";
    public static final String VETS_LAST_GUILD_CHECK = "vetsLastGuildCheck";

    /** Username of the Minecraft account that made the stored guild check result, written
     *  with it. Empty for a result stored before the key existed, or none. The result is kept
     *  per install, so this lets a world join by a different account discard it
     *  ({@link org.wynnvets.guild.GuildChecker GuildChecker}). */
    public static final String VETS_GUILD_CHECK_OWNER = "vetsGuildCheckOwner";

    public static final String VETS_DEBUG_ENABLED_AT = "vetsDebugEnabledAt";

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

    /** Whether the annihilation stamp (the anni-motd, when a cached
     *  snapshot allows it) is
     *  printed automatically on world join. vetsmod makes that print only
     *  for Returners guild members; see
     *  {@link org.wynnvets.guild.GuildLifecycle GuildLifecycle}. */
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
     *  line (the median, with a ± from the window) when the stamp is past or unknown.
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
     *  <b>No waypoint class reads the mode</b> &mdash;
     *  {@link org.wynnvets.mwe.anni.ScrollSpotMarkerProvider ScrollSpotMarkerProvider}
     *  gates on
     *  {@link org.wynnvets.mwe.anni.aggressive.AnniAggressiveTicker#isAggressiveActive()
     *  AnniAggressiveTicker#isAggressiveActive()} instead, which is where the S5 reading
     *  comes from. Absent from {@link VetsConfigSchema#USER_CONFIG_KEYS}, so {@code /wv
     *  config} cannot read or write it. When the anni window closes (T+30 min after stamp_epoch)
     *  {@link org.wynnvets.mwe.anni.mode.AnniWindowWatcher AnniWindowWatcher} restores
     *  {@link org.wynnvets.mwe.anni.mode.AnniModeManager#preferredMode()
     *  AnniModeManager#preferredMode()}, which is not an unconditional reset to
     *  {@code silent}: an explicit pick survives the window close. */
    public static final String VETS_ANNI_MODE = "vetsAnniMode";

    /** Internal flag — {@code true} once the user has explicitly chosen an
     *  anni mode via {@code /wv anni <mode>} (or the mode-switch buttons under
     *  {@code /wv anni}). Set only for {@code Source.USER_COMMAND} transitions in
     *  {@link org.wynnvets.mwe.anni.mode.AnniModeManager AnniModeManager}; never touched by
     *  internal transitions (window-close, stream, startup). When {@code false},
     *  {@link #VETS_ANNI_MODE} is treated as an unremembered default and gets overwritten to the
     *  eligibility-based default
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

    /** Master kill-switch for the synthetic vets-anni boss bar (S3).
     *  Default {@code true}; only honoured when {@link #VETS_ANNI_MODE}
     *  is {@code passive} or {@code aggressive} — silent is a strict
     *  no-op regardless. Lets advanced users keep the rest of the
     *  passive/aggressive subsystem (outlines, waypoint, alerts) while
     *  opting out of the boss bar specifically. With the bar off, a
     *  snapshot change plays no flash-sound ping ({@link #VETS_ANNI_FLASH_SOUND})
     *  either. */
    public static final String VETS_ANNI_BOSSBAR_ENABLED = "vetsAnniBossbarEnabled";

    /** Boss-bar pulse intensity controlling the {@code &l ↔ &n&l} flash
     *  duration on a per-field change (role / party / RSVP — the world
     *  chip has no timed window; it flashes while the mismatch holds).
     *  {@code subtle} = 5 s, {@code normal} = 10 s (default),
     *  {@code strong} = 20 s. The pulse half-period (250 ms) is fixed —
     *  this knob only affects the on-change flash duration, not the
     *  rate at which the bold/underline alternates. */
    public static final String VETS_ANNI_FLASH_INTENSITY = "vetsAnniFlashIntensity";

    /** Whether a role, party, RSVP or world change in the snapshot plays a two-ping sound: the
     *  Wynntils chat-mention ding ({@code SoundEvents.NOTE_BLOCK_PLING}), played twice, as spec
     *  §3.1.1 asks. The pings go with the boss bar's flashes, so they play only while the bar is
     *  up: {@code silent} mode, {@link #VETS_ANNI_BOSSBAR_ENABLED} off, and being outside both the
     *  bar's window and the anni zone all stop them too. A debug {@code flash} pings whenever this
     *  is on. On by default. */
    public static final String VETS_ANNI_FLASH_SOUND = "vetsAnniFlashSound";

    /** S4 — Toggle for the per-player outline overlay: role-coloured glow on own-party members,
     *  light-grey glow on other-vets-party members, and suppression of native Wynncraft team
     *  outlines on outsiders. Separable from {@link #VETS_ANNI_NAMETAGS_ENABLED} so users who want
     *  nametag recolouring without outlines (or vice versa) can pick exactly one half.
     *  <p>Counts toward the highlight gate in
     *  {@link org.wynnvets.mwe.anni.outline.AnniOutlineTicker AnniOutlineTicker} (mode not silent,
     *  at least one of this and {@link #VETS_ANNI_NAMETAGS_ENABLED} on, T-2h..T+30m, in the anni
     *  zone), which reads it right after the mode check, whether or not the rest of the gate then
     *  holds. Past the gate, it gates both halves of the outline overlay: the registry-tier glow,
     *  which the ticker applies only while this is on, and the outsider-outline suppression.
     *  Default {@code true}.</p> */
    public static final String VETS_ANNI_OUTLINES_ENABLED = "vetsAnniOutlinesEnabled";

    /** S4 — Master toggle for the per-player nametag overlay (role colour
     *  on own-party members, light-grey on other-vets-party members, dark-grey on outsiders).
     *  Gated on the same window+zone+mode predicate as
     *  {@link #VETS_ANNI_OUTLINES_ENABLED}. Default {@code true}. The branch added to
     *  {@link org.wynnvets.mixin.client.NametagMixin NametagMixin} runs before the supporter glint
     *  branch — so an own-party supporter shows the role colour for the
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

    /** S5 — Toggle for the {@code [Suggest: /toggle ghosts none]} prompt shown on a rising edge
     *  of being in the anni zone while aggressive mode is active (the cached snapshot's stamp
     *  inside T-2h..T+30m): a zone entry, or that gate opening with the player already in the
     *  zone. If its scan of visible players finds a ghost it fires on every such edge;
     *  otherwise it fires at
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

    private VetsConfigKeys() {}
}
