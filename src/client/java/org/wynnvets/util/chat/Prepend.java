package org.wynnvets.util.chat;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/**
 * Manages badge/prepend components for chat messages.
 * Heavily inspired by pixlze's guild-api
 *
 * <p>Each enum value represents a distinct badge that can be prepended to messages.
 * When the same badge is used for consecutive messages (within {@value #MAX_LINES_BEFORE_REBADGE}
 * chat lines), a compact block indicator is shown instead of the full badge to reduce visual
 * clutter — mirroring Wynncraft's native guild chat behavior.</p>
 */
public enum Prepend {
    DEFAULT(
            Component.literal("[VetsMod] ").withStyle(ChatFormatting.GOLD)),
    GUILD(
            Component.literal("\uDAFF\uDFFC\uE006\uDAFF\uDFFF\uE002\uDAFF\uDFFE")
                    .append(" ")
                    .setStyle(Style.EMPTY
                            .withFont(new FontDescription.Resource(Identifier.parse("chat/prefix")))
                            .withColor(ChatFormatting.AQUA)
                            .withoutShadow()),
            Component.literal("\uDAFF\uDFFC\uE001\uDB00\uDC06")
                    .append(" ")
                    .setStyle(Style.EMPTY
                            .withFont(new FontDescription.Resource(Identifier.parse("chat/prefix")))
                            .withColor(ChatFormatting.AQUA)
                            .withoutShadow())),
    EMPTY(
            Component.empty());

    private static final int MAX_LINES_BEFORE_REBADGE = 18;

    private static Prepend lastPrepend = null;
    private static int linesSinceBadge = 0;

    private final MutableComponent badge;
    private final MutableComponent blockIndicator;

    Prepend(MutableComponent badge) {
        this.badge = badge;
        this.blockIndicator = badge;
    }

    Prepend(MutableComponent badge, MutableComponent blockIndicator) {
        this.badge = badge;
        this.blockIndicator = blockIndicator;
    }

    /**
     * Returns the appropriate badge component.
     *
     * <p>If this prepend was used recently (within the last {@value #MAX_LINES_BEFORE_REBADGE}
     * lines), returns the compact block indicator instead of the full badge.</p>
     *
     * @return a fresh copy of the badge or block indicator component
     */
    public MutableComponent get() {
        if (this == lastPrepend && linesSinceBadge < MAX_LINES_BEFORE_REBADGE) {
            return blockIndicator.copy();
        }
        lastPrepend = this;
        linesSinceBadge = 0;
        return badge.copy();
    }

    /**
     * Increments the line counter used for badge deduplication.
     * Should be called each time a new line is rendered in chat.
     *
     * @param lines number of rendered lines to add
     */
    public static void addRenderedLines(int lines) {
        linesSinceBadge += lines;
    }
}
