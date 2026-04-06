package org.wynnvets.chat;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.guild.GuildStateManager;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Processes Discord-style spoiler markers ({@code ||text||}) in chat messages,
 * replacing them with hoverable "[Spoiler - Hover to see]" labels.
 */
public final class SpoilerFormatter {

    private static final Pattern SPOILER_PATTERN = Pattern.compile("\\|\\|(.+?)\\|\\|");
    private static final Style SPOILER_LABEL_STYLE = Style.EMPTY.withColor(ChatFormatting.GREEN);

    private SpoilerFormatter() {}

    /**
     * Returns {@code true} if the text contains at least one spoiler marker.
     */
    public static boolean containsSpoilers(String text) {
        return text != null && SPOILER_PATTERN.matcher(text).find();
    }

    /**
     * Appends text to the parent component, replacing {@code ||spoiler||}
     * segments with hoverable spoiler labels.  Non-spoiler segments are
     * appended as plain literals with the given style.
     *
     * @param parent    the component to append to
     * @param text      the raw text that may contain spoiler markers
     * @param textStyle the style applied to non-spoiler text
     */
    public static void appendWithSpoilers(MutableComponent parent, String text, Style textStyle) {
        if (!VetsConfig.get(VetsConfig.HANDLE_SPOILERS) || !isEligible()) {
            parent.append(Component.literal(text).setStyle(textStyle));
            return;
        }

        Matcher matcher = SPOILER_PATTERN.matcher(text);
        int lastEnd = 0;

        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                parent.append(Component.literal(text.substring(lastEnd, matcher.start())).setStyle(textStyle));
            }

            String spoilerContent = matcher.group(1);
            Style hoverStyle = SPOILER_LABEL_STYLE.withHoverEvent(
                    new HoverEvent.ShowText(Component.literal(spoilerContent)));
            parent.append(Component.literal("[Spoiler - Hover to see]").setStyle(hoverStyle));

            lastEnd = matcher.end();
        }

        if (lastEnd == 0) {
            parent.append(Component.literal(text).setStyle(textStyle));
        } else if (lastEnd < text.length()) {
            parent.append(Component.literal(text.substring(lastEnd)).setStyle(textStyle));
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
