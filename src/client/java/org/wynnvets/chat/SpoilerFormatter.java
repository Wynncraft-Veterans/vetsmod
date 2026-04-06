package org.wynnvets.chat;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.guild.GuildStateManager;

/**
 * Processes PUA-encoded spoiler blocks ({@code \uF600…\uF601}) in chat
 * messages, replacing them with hoverable "[Spoiler - Hover to see]" labels.
 */
public final class SpoilerFormatter {

    private static final Style SPOILER_LABEL_STYLE = Style.EMPTY.withColor(ChatFormatting.GREEN);

    private SpoilerFormatter() {}

    /**
     * Returns {@code true} if the text contains at least one PUA-encoded spoiler block.
     */
    public static boolean containsSpoilers(String text) {
        return SpoilerCodec.containsEncodedSpoiler(text);
    }

    /**
     * Appends text to the parent component, replacing PUA-encoded spoiler
     * blocks with hoverable spoiler labels.  Non-spoiler segments are
     * appended as plain literals with the given style.
     *
     * @param parent    the component to append to
     * @param text      the raw text that may contain PUA-encoded spoiler blocks
     * @param textStyle the style applied to non-spoiler text
     */
    public static void appendWithSpoilers(MutableComponent parent, String text, Style textStyle) {
        if (!VetsConfig.get(VetsConfig.HANDLE_SPOILERS) || !isEligible()) {
            parent.append(Component.literal(text).setStyle(textStyle));
            return;
        }

        if (!SpoilerCodec.containsEncodedSpoiler(text)) {
            parent.append(Component.literal(text).setStyle(textStyle));
            return;
        }

        int cursor = 0;
        while (cursor < text.length()) {
            int start = text.indexOf(SpoilerCodec.SPOILER_START, cursor);
            if (start < 0) {
                parent.append(Component.literal(text.substring(cursor)).setStyle(textStyle));
                break;
            }
            int end = text.indexOf(SpoilerCodec.SPOILER_END, start + 1);
            if (end < 0) {
                parent.append(Component.literal(text.substring(cursor)).setStyle(textStyle));
                break;
            }

            if (start > cursor) {
                parent.append(Component.literal(text.substring(cursor, start)).setStyle(textStyle));
            }

            String encoded = text.substring(start + 1, end);
            String decoded = SpoilerCodec.decodeContent(encoded);
            Style hoverStyle = SPOILER_LABEL_STYLE.withHoverEvent(
                    new HoverEvent.ShowText(Component.literal(decoded)));
            parent.append(Component.literal("[Spoiler - Hover to see]").setStyle(hoverStyle));

            cursor = end + 1;
        }
    }

    /**
     * Returns {@code true} when the local player is part of the Returners
     * ecosystem (guild member, waitlist-unlocked, or honourary-unlocked).
     */
    private static boolean isEligible() {
        return GuildStateManager.isReturners()
            || (GuildStateManager.isGuildless() && GuildStateManager.isWaitlistUnlocked())
            || GuildStateManager.isHonouraryUnlocked();
    }
}
