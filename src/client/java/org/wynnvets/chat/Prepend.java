package org.wynnvets.chat;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;

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
        buildDefaultBadge()),
    GUILD(
            Component.literal("\uDAFF\uDFFC\uE006\uDAFF\uDFFF\uE002\uDAFF\uDFFE")
                    .append(" ")
                    .setStyle(Style.EMPTY
                            .withFont(ResourceLocation.parse("chat/prefix"))
                            .withColor(ChatFormatting.AQUA)
                            .withShadowColor(0)),
            Component.literal("\uDAFF\uDFFC\uE001\uDB00\uDC06")
                    .append(" ")
                    .setStyle(Style.EMPTY
                            .withFont(ResourceLocation.parse("chat/prefix"))
                            .withColor(ChatFormatting.AQUA)
                            .withShadowColor(0))),
    EMPTY(
            Component.empty());

    private static final int MAX_LINES_BEFORE_REBADGE = 18;
    private static final String DEFAULT_PREFIX = "\uDAFF\uDFFC\uE008\uDAFF\uDFFF\uE002\uDAFF\uDFFE";
    private static final String DEFAULT_PILL_FRAME_OPEN = "\uE010\u2064";
    private static final String DEFAULT_PILL_FRAME_SEGMENT = "\uE00F\uE012";
    private static final String DEFAULT_PILL_FRAME_CLOSE = "\uE011";

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

    private static MutableComponent buildDefaultBadge() {
        Style rootStyle = Style.EMPTY
                .withColor(ChatFormatting.GOLD)
                .withShadowColor(0);
        Style prefixStyle = Style.EMPTY
                .withFont(ResourceLocation.parse("chat/prefix"))
                .withColor(ChatFormatting.GOLD)
                .withShadowColor(0);
        Style pillFrameStyle = Style.EMPTY
                .withColor(ChatFormatting.GOLD)
                .withShadowColor(0);
        Style pillTextStyle = Style.EMPTY
                .withColor(ChatFormatting.DARK_GRAY)
                .withShadowColor(0);

        MutableComponent badge = Component.empty().setStyle(rootStyle);
        badge.append(Component.literal(DEFAULT_PREFIX).setStyle(prefixStyle));
        badge.append(Component.literal(" ").setStyle(pillFrameStyle));
        badge.append(Component.literal(DEFAULT_PILL_FRAME_OPEN).setStyle(pillFrameStyle));

        appendDefaultPillGlyph(badge, '\uE055', pillFrameStyle, pillTextStyle);
        appendDefaultPillGlyph(badge, '\uE044', pillFrameStyle, pillTextStyle);
        appendDefaultPillGlyph(badge, '\uE053', pillFrameStyle, pillTextStyle);
        appendDefaultPillGlyph(badge, '\uE052', pillFrameStyle, pillTextStyle);
        appendDefaultPillGlyph(badge, '\uE04C', pillFrameStyle, pillTextStyle);
        appendDefaultPillGlyph(badge, '\uE04E', pillFrameStyle, pillTextStyle);
        appendDefaultPillGlyph(badge, '\uE043', pillFrameStyle, pillTextStyle);

        badge.append(Component.literal(DEFAULT_PILL_FRAME_CLOSE).setStyle(pillFrameStyle));
        badge.append(Component.literal(" ").setStyle(pillFrameStyle));
        return badge;
    }

    private static void appendDefaultPillGlyph(MutableComponent target, char glyph, Style frameStyle, Style textStyle) {
        target.append(Component.literal(DEFAULT_PILL_FRAME_SEGMENT).setStyle(frameStyle));
        target.append(Component.literal(String.valueOf(glyph)).setStyle(textStyle));
    }
}
