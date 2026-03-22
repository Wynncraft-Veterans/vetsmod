package org.wynnvets.listeners;

import com.wynntils.core.WynntilsMod;
import com.wynntils.core.components.Models;
import com.wynntils.core.text.StyledText;
import com.wynntils.core.text.StyledTextPart;
import com.wynntils.handlers.chat.event.ChatMessageEvent;
import com.wynntils.handlers.chat.type.RecipientType;
import com.wynntils.models.guild.event.GuildEvent;
import com.wynntils.models.guild.type.GuildRank;
import com.wynntils.models.worlds.event.WorldStateEvent;
import com.wynntils.models.worlds.type.WorldState;
import com.wynntils.utils.mc.McUtils;
import com.wynntils.utils.mc.StyledTextUtils;
import com.wynntils.utils.type.Pair;
import net.minecraft.network.chat.HoverEvent;
import net.neoforged.bus.api.SubscribeEvent;
import org.wynnvets.api.V1ApiManager;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.chat.OutboundDisplayHandler;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.logging.VetsLogger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Subscribes to Wynntils events for world state, guild changes, and guild
 * chat relay to the v1 inbound WebSocket.
 */
public final class WynntilsEventListener {

    /**
     * Pattern to extract rank and message from a guild chat line.
     * The plain-text form (after stripping formatting) looks like:
     *     &lt;rank-glyph&gt; Username: message text
     * The rank glyphs have already been stripped by Wynntils so we match the
     * visible "Username: message" portion.
     */
    private static final Pattern GUILD_CHAT_PATTERN =
            Pattern.compile("^\\s*(.+?):\\s+(.+)$");

    /** Client-side dedup — prevents multiple sends when Wynntils fires the
     *  same guild message event more than once (e.g. PUA-encoded and decoded
     *  variants for item links). */
    private static final long SENT_DEDUP_TTL_MS = TimeUnit.SECONDS.toMillis(5);
    private static final int MAX_SENT_FINGERPRINTS = 100;
    private static final Deque<SentFingerprint> recentSentFingerprints = new ArrayDeque<>();
    private static final Object sentDedupLock = new Object();

    /** Singleton instance registered with the Wynntils event bus. */
    private static final WynntilsEventListener INSTANCE = new WynntilsEventListener();

    private WynntilsEventListener() {
    }

    /**
     * Registers this listener with the Wynntils event bus.
     * Should be called once during client initialization.
     */
    public static void register() {
        WynntilsMod.registerEventListener(INSTANCE);
        GuildStateManager.setWynntilsReady();
        VetsLogger.debug("Registered Wynntils event listeners");
    }

    /**
     * Unregisters this listener from the Wynntils event bus.
     */
    public static void unregister() {
        WynntilsMod.unregisterEventListener(INSTANCE);
    }

    /**
     * Handles world state transitions from Wynntils.
     *
     * <p>When the player enters a {@link WorldState#WORLD}, triggers
     * {@link GuildStateManager#onEnteredWorld()} to begin guild and staff
     * rank resolution.
     *
     * @param event the world state change event
     */
    @SubscribeEvent
    public void onWorldStateChanged(WorldStateEvent event) {
        if (event.getNewState() == WorldState.WORLD) {
            VetsLogger.debug("WorldStateEvent: entered WORLD (world={}, firstJoin={})",
                    event.getWorldName(), event.isFirstJoinWorld());
            GuildStateManager.onEnteredWorld();
        } else if (event.getOldState() == WorldState.WORLD
                && event.getNewState() != WorldState.WORLD) {
            VetsLogger.debug("WorldStateEvent: left WORLD → {}", event.getNewState());
        }
    }

    /**
     * Handles the player joining a guild.
     *
     * <p>Delegates to {@link GuildStateManager#onGuildInfoUpdated()} to
     * re-evaluate guild membership and feature gates.
     *
     * @param event the guild joined event
     */
    @SubscribeEvent
    public void onGuildJoined(GuildEvent.Joined event) {
        VetsLogger.debug("GuildEvent.Joined: {}", event.getGuildName());
        GuildStateManager.onGuildInfoUpdated();
    }

    /**
     * Handles the player leaving a guild.
     *
     * <p>Delegates to {@link GuildStateManager#onGuildInfoUpdated()} to
     * re-evaluate guild membership and feature gates.
     *
     * @param event the guild left event
     */
    @SubscribeEvent
    public void onGuildLeft(GuildEvent.Left event) {
        VetsLogger.debug("GuildEvent.Left: {}", event.getGuildName());
        GuildStateManager.onGuildInfoUpdated();
    }

    /**
     * Handles guild chat messages detected by Wynntils and relays them to the
     * v1 inbound WebSocket for bridge distribution.
     *
     * <p>Only fires for Returners members (guild inbound relay). Guildless
     * and honourary relay are handled separately by command mixins.</p>
     *
     * @param event the chat message match event
     */
    @SubscribeEvent
    public void onGuildChat(ChatMessageEvent.Match event) {
        if (event.getRecipientType() != RecipientType.GUILD) {
            return;
        }
        if (!GuildStateManager.isReturners()) {
            return;
        }

        // Skip messages dispatched locally by the mod (bridge display, rewritten
        // chat, etc.).  displayClientMessage → ChatListener.handleSystemMessage
        // → Wynntils event pipeline, so this guard prevents the feedback loop
        // where bridge messages are re-sent to the server as guild messages.
        if (ChatUtils.isInternalDispatch()) {
            VetsLogger.debug("onGuildChat: skipping internal dispatch");
            return;
        }

        StyledText styledMessage = event.getMessage();

        // Unwrap soft-wrapped lines from Wynncraft
        StyledText unwrapped = StyledTextUtils.unwrap(styledMessage).stripAlignment();
        String plain = unwrapped.getStringWithoutFormatting();
        VetsLogger.debug("onGuildChat plain [{}] codepoints [{}]", plain, toCodepoints(plain));

        // Extract "Username: message" from the plain text
        Matcher chatMatcher = GUILD_CHAT_PATTERN.matcher(plain);
        if (!chatMatcher.find()) {
            VetsLogger.debug("onGuildChat: GUILD_CHAT_PATTERN did not match");
            return;
        }

        String rawName = chatMatcher.group(1);
        VetsLogger.debug("onGuildChat rawName [{}] codepoints [{}]", rawName, toCodepoints(rawName));

        // Strip PUA characters (badge/pill glyphs) from the raw display name.
        // Wynncraft encodes rank badges and guild prepends as Private Use Area
        // characters that appear in plain text but are not part of the username.
        String displayName = stripPuaCharacters(rawName).trim();
        String messageContent = chatMatcher.group(2).trim();
        VetsLogger.debug("onGuildChat displayName [{}] message [{}]", displayName, messageContent);

        if (displayName.isEmpty() || messageContent.isEmpty()) {
            VetsLogger.debug("onGuildChat: empty displayName or message, dropping");
            return;
        }

        // Resolve the true username from hover text — never send nicknames
        String trueUsername = extractTrueUsername(unwrapped, displayName);
        VetsLogger.debug("onGuildChat trueUsername [{}] (displayName was [{}])", trueUsername, displayName);

        // Resolve the sender's rank: try hover text first, then pill glyph
        // decoding, and finally the Wynntils guild model for the current player.
        String rank = resolveRank(unwrapped, plain, trueUsername);
        // Client-side dedup: normalize message by stripping PUA (item encodings
        // produce multiple event variants with different PUA content but the same
        // surrounding text).  Only the first variant within the TTL window is sent.
        String normalizedMsg = stripPuaCharacters(messageContent).replaceAll("  +", " ").trim();
        if (wasSentRecently(trueUsername, normalizedMsg)) {
            VetsLogger.debug("onGuildChat: duplicate suppressed for [{}] [{}]", trueUsername, normalizedMsg);
            return;
        }

        VetsLogger.debug("onGuildChat SENDING rank=[{}] user=[{}] msg=[{}]", rank, trueUsername, messageContent);

        V1ApiManager.sendInbound("guild", rank, trueUsername, messageContent);
        recordSentFingerprint(trueUsername, normalizedMsg);

        // Record a PUA-stripped version so the outbound echo handler can match
        // the returning message even when the server-displayed version was
        // rewritten by Wynntils (e.g. item decoding changes PUA to text).
        OutboundDisplayHandler.recordServerGuildMessage(trueUsername, normalizedMsg);
    }

    /**
     * Resolves the true username for a chat sender by inspecting hover events
     * on the message parts for Wynntils' nickname annotation.
     *
     * <p>If a nickname pattern is found, the real username from the hover text
     * is returned. Otherwise falls back to the display name visible in chat.</p>
     *
     * @param message     the full styled message
     * @param displayName the name shown in chat (may be a nickname)
     * @return the true Minecraft username
     */
    private static String extractTrueUsername(StyledText message, String displayName) {
        Pair<String, String> nameAndNick = StyledTextUtils.extractNameAndNick(message);
        if (nameAndNick != null) {
            return nameAndNick.a();
        }
        return displayName;
    }

    /**
     * Resolves the sender's guild rank using multiple strategies:
     * <ol>
     *   <li>Hover text on the message parts (if Wynncraft provides it)</li>
     *   <li>Pill glyph character decoding from the plain text</li>
     *   <li>Wynntils {@code Models.Guild.getGuildRank()} for the current player</li>
     * </ol>
     *
     * @param message      the styled message to inspect for hover events
     * @param plainText    the plain-text form of the message (may contain pill PUA glyphs)
     * @param trueUsername the resolved username of the sender
     * @return the rank name (e.g. "Chief", "Captain") or empty string
     */
    private static String resolveRank(StyledText message, String plainText, String trueUsername) {
        // Strategy 1: hover text
        String hoverRank = extractRankFromHover(message);
        VetsLogger.debug("resolveRank strategy1:hover [{}]", hoverRank);
        if (!hoverRank.isEmpty()) {
            return hoverRank;
        }

        // Strategy 2: decode rank from pill PUA glyph letters
        String pillRank = decodePillRank(plainText);
        VetsLogger.debug("resolveRank strategy2:pill [{}]", pillRank);
        if (!pillRank.isEmpty()) {
            return pillRank;
        }

        // Strategy 3: for the current player, use Wynntils' guild model
        String localPlayer = McUtils.playerName();
        if (localPlayer != null && localPlayer.equalsIgnoreCase(trueUsername)) {
            GuildRank guildRank = Models.Guild.getGuildRank();
            VetsLogger.debug("resolveRank strategy3:wynntils model [{}]", guildRank);
            if (guildRank != null) {
                return guildRank.getName();
            }
        }

        VetsLogger.debug("resolveRank: all strategies failed for [{}]", trueUsername);
        return "";
    }

    /**
     * Attempts to extract the guild rank from hover text on the message parts.
     *
     * @param message the styled message to inspect
     * @return the rank name or empty string
     */
    private static String extractRankFromHover(StyledText message) {
        for (StyledTextPart part : message) {
            HoverEvent hover = part.getPartStyle().getStyle().getHoverEvent();
            if (hover == null || hover.action() != HoverEvent.Action.SHOW_TEXT) {
                continue;
            }
            HoverEvent.ShowText showText = (HoverEvent.ShowText) hover;
            String hoverPlain = showText.value().getString();
            VetsLogger.debug("extractRankFromHover hoverText [{}]", hoverPlain);
            for (String rankName : new String[]{"Owner", "Chief", "Strategist", "Captain", "Recruiter", "Recruit"}) {
                if (hoverPlain.contains(rankName)) {
                    return rankName;
                }
            }
        }
        return "";
    }

    /**
     * Decodes the guild rank from pill PUA glyph characters in the plain text.
     *
     * <p>Wynncraft renders rank badges as Private Use Area characters in the
     * {@code banner/pill} font. The "foreground" (dark) characters encode
     * rank letters at codepoints {@code \uE030} (A) through {@code \uE049} (Z).
     * This method scans for those codepoints, decodes the letters, and matches
     * against known rank names.</p>
     *
     * @param plainText the plain-text form of the guild message
     * @return the rank name or empty string if decoding fails
     */
    private static String decodePillRank(String plainText) {
        // Scan for pill foreground letter glyphs in the E030-E049 range
        // and also the E040-E059 range (used by the mod's own pill builder).
        // Try both ranges since the server and mod may use different offsets.
        String decoded = tryDecodeRange(plainText, '\uE030', '\uE049');
        VetsLogger.debug("decodePillRank E030-E049 decoded [{}]", decoded);
        if (decoded.isEmpty()) {
            decoded = tryDecodeRange(plainText, '\uE040', '\uE059');
            VetsLogger.debug("decodePillRank E040-E059 decoded [{}]", decoded);
        }
        if (decoded.isEmpty()) {
            return "";
        }

        String upper = decoded.toUpperCase();
        for (String rankName : new String[]{"OWNER", "CHIEF", "STRATEGIST", "CAPTAIN", "RECRUITER", "RECRUIT"}) {
            if (upper.equals(rankName)) {
                return rankName.charAt(0) + rankName.substring(1).toLowerCase();
            }
        }
        VetsLogger.debug("decodePillRank decoded [{}] did not match any known rank", decoded);
        return "";
    }

    /**
     * Extracts letters encoded as PUA characters in a contiguous range.
     */
    private static String tryDecodeRange(String text, char rangeStart, char rangeEnd) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= rangeStart && c <= rangeEnd) {
                sb.append((char) ('A' + (c - rangeStart)));
            }
        }
        return sb.toString();
    }

    /**
     * Formats a string as a space-separated list of U+XXXX codepoints for debug logging.
     */
    private static String toCodepoints(String text) {
        if (text == null || text.isEmpty()) return "(empty)";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("U+%04X", cp));
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    // ── Client-side dedup helpers ──────────────────────────────────────

    private static boolean wasSentRecently(String username, String normalizedMsg) {
        String fp = username.toLowerCase() + "\0" + normalizedMsg;
        synchronized (sentDedupLock) {
            long now = System.currentTimeMillis();
            pruneSentFingerprints(now);
            for (SentFingerprint recent : recentSentFingerprints) {
                if (recent.fingerprint.equals(fp)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void recordSentFingerprint(String username, String normalizedMsg) {
        String fp = username.toLowerCase() + "\0" + normalizedMsg;
        synchronized (sentDedupLock) {
            long now = System.currentTimeMillis();
            pruneSentFingerprints(now);
            if (recentSentFingerprints.size() >= MAX_SENT_FINGERPRINTS) {
                recentSentFingerprints.pollFirst();
            }
            recentSentFingerprints.addLast(new SentFingerprint(fp, now));
        }
    }

    private static void pruneSentFingerprints(long nowMs) {
        while (!recentSentFingerprints.isEmpty()) {
            SentFingerprint head = recentSentFingerprints.peekFirst();
            if (head == null || nowMs - head.createdAtMs <= SENT_DEDUP_TTL_MS) {
                return;
            }
            recentSentFingerprints.pollFirst();
        }
    }

    private static final class SentFingerprint {
        final String fingerprint;
        final long createdAtMs;

        SentFingerprint(String fingerprint, long createdAtMs) {
            this.fingerprint = fingerprint;
            this.createdAtMs = createdAtMs;
        }
    }

    /**
     * Strips all Private Use Area codepoints (BMP and supplementary) from a string.
     */
    private static String stripPuaCharacters(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int charCount = Character.charCount(cp);
            int type = Character.getType(cp);
            boolean isCustomGlyph = type == Character.PRIVATE_USE
                    || (type == Character.UNASSIGNED && cp > 0xFFFF);
            if (!isCustomGlyph) {
                sb.appendCodePoint(cp);
            }
            i += charCount;
        }
        return sb.toString();
    }
}
