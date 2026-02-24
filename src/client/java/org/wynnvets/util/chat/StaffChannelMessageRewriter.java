package org.wynnvets.util.chat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.wynnvets.util.GuildInfoListener;
import org.wynnvets.util.StaffRanksFetcher;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites lock-prefixed private messages into staff-channel styled messages.
 */
public final class StaffChannelMessageRewriter {

    private static final String LOCK_PREFIX = "🔐";
    private static final String PRIVATE_SEPARATOR_GLYPH = "\uE003";
    private static final Pattern USERNAME_AT_END = Pattern.compile("([A-Za-z0-9_]{1,16})\\s*$");

    private StaffChannelMessageRewriter() {
    }

    /**
     * Rewrites incoming messages whose content starts with 🔐.
     *
     * @param component     original message component
     * @param messageString flattened plain text
     * @return true when rewritten (caller should cancel original display)
     */
    public static boolean tryRewrite(Component component, String messageString) {
        if (messageString == null || messageString.isEmpty()) {
            return false;
        }

        if (messageString.indexOf(PRIVATE_SEPARATOR_GLYPH) < 0) {
            return false;
        }

        int colonIndex = messageString.indexOf(':');
        if (colonIndex <= 0) {
            return false;
        }

        String content = messageString.substring(colonIndex + 1).trim();
        if (!content.startsWith(LOCK_PREFIX)) {
            return false;
        }

        String message = content.substring(LOCK_PREFIX.length()).stripLeading();
        String sender = extractSender(messageString, colonIndex);
        if (sender == null || sender.isEmpty()) {
            return false;
        }

        if (isSelfSender(sender)) {
            return true;
        }

        String selfName = GuildInfoListener.playerName();
        String knownRank = StaffRanksFetcher.confirmedRankFor(sender)
            .orElseGet(() -> (selfName != null && !selfName.isEmpty() && sender.equalsIgnoreCase(selfName))
                ? GuildInfoListener.selfStaffRank()
                : null);

        ChatUtils.sendStaffChannelMessage(sender, message, knownRank);
        return true;
    }

    private static String extractSender(String messageString, int colonIndex) {
        int separatorIndex = messageString.lastIndexOf(PRIVATE_SEPARATOR_GLYPH, colonIndex);
        if (separatorIndex <= 0) {
            return null;
        }

        String left = messageString.substring(0, separatorIndex).trim();

        Matcher matcher = USERNAME_AT_END.matcher(left);
        if (!matcher.find()) {
            return null;
        }

        return matcher.group(1);
    }

    private static boolean isSelfSender(String sender) {
        if (sender == null || sender.isEmpty()) {
            return false;
        }

        String normalizedSender = sender.trim();
        if (normalizedSender.isEmpty()) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;

        if (matchesIdentityVariant(normalizedSender, GuildInfoListener.playerName())) {
            return true;
        }

        if (player == null) {
            return false;
        }

        if (matchesIdentityVariant(normalizedSender, player.getGameProfile().name())) {
            return true;
        }

        return matchesIdentityVariant(normalizedSender, player.getName().getString());
    }

    private static boolean matchesIdentityVariant(String sender, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }

        String[] variants = candidate.split("/");
        for (String variant : variants) {
            String normalizedVariant = variant.trim();
            if (!normalizedVariant.isEmpty() && sender.equalsIgnoreCase(normalizedVariant)) {
                return true;
            }
        }

        return false;
    }
}
