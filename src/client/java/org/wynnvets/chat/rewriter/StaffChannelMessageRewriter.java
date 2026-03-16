package org.wynnvets.chat.rewriter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.fetcher.polling.StaffRanksFetcher;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites lock-prefixed private messages into staff-channel styled messages.
 */
public final class StaffChannelMessageRewriter {

    private static final String LOCK_PREFIX = "🔐";
    private static final String PRIVATE_SEPARATOR_GLYPH = "\uE003";
    private static final Pattern USERNAME_AT_END = Pattern.compile("([A-Za-z0-9_]{1,16})\\s*$");
    private static final Pattern MSG_COMMAND_PATTERN =
        Pattern.compile("/msg\\s+([A-Za-z0-9_]{1,16})", Pattern.CASE_INSENSITIVE);
    private static final Pattern REAL_NAME_PATTERN =
        Pattern.compile("real\\s+name\\s+is\\s+([A-Za-z0-9_]{1,16})", Pattern.CASE_INSENSITIVE);

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
        String sender = extractSenderFromComponent(component);
        if (sender == null || sender.isEmpty()) {
            sender = extractSender(messageString, colonIndex);
        }
        if (sender == null || sender.isEmpty()) {
            return false;
        }

        if (isSelfSender(sender)) {
            return true;
        }

        String selfName = GuildStateManager.playerName();
        String resolvedSender = sender;
        String knownRank = StaffRanksFetcher.confirmedRankFor(resolvedSender)
            .orElseGet(() -> (selfName != null && !selfName.isEmpty() && resolvedSender.equalsIgnoreCase(selfName))
                ? GuildStateManager.selfStaffRank()
                : null);

        ChatUtils.sendStaffChannelMessage(resolvedSender, message, knownRank);
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

    /**
     * Walks the Component tree looking for the real Minecraft username attached
     * by Wynncraft to the sender name part (before the private separator glyph).
     * Wynncraft attaches {@code /msg <username>} as a SUGGEST_COMMAND ClickEvent
     * and {@code <nick>'s real name is <username>} as a SHOW_TEXT HoverEvent.
     */
    private static String extractSenderFromComponent(Component component) {
        if (component == null) {
            return null;
        }

        List<FlatPart> parts = new ArrayList<>();
        flattenParts(component, component.getStyle(), parts);

        for (FlatPart part : parts) {
            if (part.text.contains(PRIVATE_SEPARATOR_GLYPH)) {
                break;
            }

            Style style = part.style;

            String fromClick = extractUsernameFromClick(style.getClickEvent());
            if (fromClick != null) {
                return fromClick;
            }

            String fromHover = extractUsernameFromHover(style.getHoverEvent());
            if (fromHover != null) {
                return fromHover;
            }
        }

        return null;
    }

    private static String extractUsernameFromClick(ClickEvent click) {
        if (click instanceof ClickEvent.SuggestCommand sc) {
            Matcher matcher = MSG_COMMAND_PATTERN.matcher(sc.command());
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private static String extractUsernameFromHover(HoverEvent hover) {
        if (hover instanceof HoverEvent.ShowText st) {
            String hoverText = st.value().getString();
            Matcher matcher = REAL_NAME_PATTERN.matcher(hoverText);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private static void flattenParts(Component component, Style inherited, List<FlatPart> out) {
        Style resolved = inherited.applyTo(component.getStyle());
        StringBuilder sb = new StringBuilder();
        component.getContents().visit(s -> {
            sb.append(s);
            return java.util.Optional.empty();
        });
        String text = sb.toString();
        if (!text.isEmpty()) {
            out.add(new FlatPart(text, resolved));
        }
        for (Component sibling : component.getSiblings()) {
            flattenParts(sibling, resolved, out);
        }
    }

    private record FlatPart(String text, Style style) {
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

        if (matchesIdentityVariant(normalizedSender, GuildStateManager.playerName())) {
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
