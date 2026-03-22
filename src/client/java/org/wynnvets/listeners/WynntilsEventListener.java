package org.wynnvets.listeners;

import com.wynntils.core.WynntilsMod;
import com.wynntils.core.text.StyledText;
import com.wynntils.core.text.StyledTextPart;
import com.wynntils.handlers.chat.event.ChatMessageEvent;
import com.wynntils.handlers.chat.type.RecipientType;
import com.wynntils.models.guild.event.GuildEvent;
import com.wynntils.models.worlds.event.WorldStateEvent;
import com.wynntils.models.worlds.type.WorldState;
import com.wynntils.utils.mc.StyledTextUtils;
import com.wynntils.utils.type.Pair;
import net.minecraft.network.chat.HoverEvent;
import net.neoforged.bus.api.SubscribeEvent;
import org.wynnvets.api.V1ApiManager;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.logging.VetsLogger;

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

        StyledText styledMessage = event.getMessage();

        // Unwrap soft-wrapped lines from Wynncraft
        StyledText unwrapped = StyledTextUtils.unwrap(styledMessage).stripAlignment();
        String plain = unwrapped.getStringWithoutFormatting();

        // Extract "Username: message" from the plain text
        Matcher chatMatcher = GUILD_CHAT_PATTERN.matcher(plain);
        if (!chatMatcher.find()) {
            return;
        }

        String displayName = chatMatcher.group(1).trim();
        String messageContent = chatMatcher.group(2).trim();

        if (displayName.isEmpty() || messageContent.isEmpty()) {
            return;
        }

        // Resolve the true username from hover text — never send nicknames
        String trueUsername = extractTrueUsername(unwrapped, displayName);

        // Resolve the sender's rank from hover text or Models.Guild
        String rank = extractRankFromHover(unwrapped);

        V1ApiManager.sendInbound("guild", rank, trueUsername, messageContent);
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
     * Attempts to extract the guild rank from hover text on the message parts.
     * Guild messages often contain rank information in the hover tooltip.
     *
     * <p>Falls back to an empty string if no rank can be determined.</p>
     *
     * @param message the styled message to inspect
     * @return the rank name (e.g. "Chief", "Captain") or empty string
     */
    private static String extractRankFromHover(StyledText message) {
        for (StyledTextPart part : message) {
            HoverEvent hover = part.getPartStyle().getStyle().getHoverEvent();
            if (hover == null || hover.action() != HoverEvent.Action.SHOW_TEXT) {
                continue;
            }
            HoverEvent.ShowText showText = (HoverEvent.ShowText) hover;
            String hoverPlain = showText.value().getString();
            for (String rankName : new String[]{"Owner", "Chief", "Strategist", "Captain", "Recruiter", "Recruit"}) {
                if (hoverPlain.contains(rankName)) {
                    return rankName;
                }
            }
        }
        return "";
    }
}
