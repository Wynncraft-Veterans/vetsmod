package org.wynnvets.chat;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands Discord's {@code <t:EPOCH:STYLE>} timestamp markup into readable
 * local-time text inside an already-parsed {@link Component} tree.
 *
 * <p>Discord renders that markup client-side, so it survives the markdown →
 * chat-component conversion the backend does and arrives here verbatim. It has
 * to be expanded on the client rather than in the API: the whole point of the
 * markup is that every reader sees the instant in their own timezone, and the
 * API has no idea what that is.</p>
 *
 * <p>Styles follow Discord's table — {@code t} short time, {@code T} long time,
 * {@code d} short date, {@code D} long date, {@code f} (the default) short
 * date-time, {@code F} long date-time, {@code R} relative. Every expansion also
 * carries a hover with the absolute time, its zone, and the relative offset, so
 * an {@code R} still answers "when exactly?" and an {@code F} still answers
 * "how far away?".</p>
 */
public final class DiscordTimestamps {

    /** {@code <t:1786302000>} or {@code <t:1786302000:F>}. */
    private static final Pattern TIMESTAMP = Pattern.compile("<t:(\\d{1,12})(?::([tTdDfFR]))?>");

    /**
     * 2100-01-01. Anything past it is an authoring slip rather than a date —
     * a millisecond epoch pasted where seconds were wanted is the usual one,
     * and rendering it as the year 318857 is worse than showing the markup.
     */
    private static final long MAX_EPOCH_SECONDS = 4102444800L;

    private static final DateTimeFormatter SHORT_TIME = pattern("HH:mm");
    private static final DateTimeFormatter LONG_TIME = pattern("HH:mm:ss");
    private static final DateTimeFormatter SHORT_DATE = pattern("dd/MM/yyyy");
    private static final DateTimeFormatter LONG_DATE = pattern("d MMMM yyyy");
    private static final DateTimeFormatter SHORT_DATE_TIME = pattern("d MMMM yyyy HH:mm");
    private static final DateTimeFormatter LONG_DATE_TIME = pattern("EEEE, d MMMM yyyy HH:mm");
    private static final DateTimeFormatter HOVER = pattern("EEEE, d MMMM yyyy HH:mm z");

    private static final long MINUTE = 60L;
    private static final long HOUR = 60L * MINUTE;
    private static final long DAY = 24L * HOUR;
    private static final long MONTH = 30L * DAY;
    private static final long YEAR = 365L * DAY;

    private DiscordTimestamps() {}

    /**
     * Rebuilds {@code node} with every Discord timestamp expanded. Nodes with
     * no timestamp in them are copied through unchanged, style and all.
     */
    public static MutableComponent expand(Component node) {
        MutableComponent out = expandContents(node);
        for (Component sibling : node.getSiblings()) {
            out.append(expand(sibling));
        }
        return out;
    }

    /**
     * Expands the timestamps in this node's own contents, ignoring siblings.
     * A node containing markup is split into literal/timestamp segments under
     * an empty parent carrying the original style, so the expansions inherit
     * the surrounding formatting.
     */
    private static MutableComponent expandContents(Component node) {
        String text = ownText(node);
        Matcher matcher = TIMESTAMP.matcher(text);
        if (!matcher.find()) {
            return node.plainCopy().setStyle(node.getStyle());
        }

        MutableComponent out = Component.empty().setStyle(node.getStyle());
        int cursor = 0;
        do {
            if (matcher.start() > cursor) {
                out.append(Component.literal(text.substring(cursor, matcher.start())));
            }
            out.append(render(matcher.group(1), matcher.group(2), matcher.group()));
            cursor = matcher.end();
        } while (matcher.find());
        if (cursor < text.length()) {
            out.append(Component.literal(text.substring(cursor)));
        }
        return out;
    }

    /** Renders one timestamp, or the raw markup back if the epoch is nonsense. */
    private static Component render(String epochSeconds, String style, String raw) {
        // The pattern caps the digit count, so the parse itself can't overflow.
        long epoch = Long.parseLong(epochSeconds);
        if (epoch > MAX_EPOCH_SECONDS) {
            return Component.literal(raw);
        }
        Instant instant = Instant.ofEpochSecond(epoch);

        String shown = switch (style == null ? 'f' : style.charAt(0)) {
            case 't' -> format(instant, SHORT_TIME);
            case 'T' -> format(instant, LONG_TIME);
            case 'd' -> format(instant, SHORT_DATE);
            case 'D' -> format(instant, LONG_DATE);
            case 'F' -> format(instant, LONG_DATE_TIME);
            case 'R' -> relative(instant);
            default -> format(instant, SHORT_DATE_TIME);
        };

        MutableComponent hover = Component.literal(format(instant, HOVER))
                .append(Component.literal("\n" + relative(instant)).withStyle(ChatFormatting.GRAY));
        return Component.literal(shown)
                .setStyle(Style.EMPTY.withHoverEvent(new HoverEvent.ShowText(hover)));
    }

    /** Discord's {@code R} style: "in 3 days" / "2 hours ago". */
    private static String relative(Instant when) {
        long delta = when.getEpochSecond() - Instant.now().getEpochSecond();
        long abs = Math.abs(delta);
        String magnitude;
        if (abs < MINUTE) {
            magnitude = plural(abs, "second");
        } else if (abs < HOUR) {
            magnitude = plural(abs / MINUTE, "minute");
        } else if (abs < DAY) {
            magnitude = plural(abs / HOUR, "hour");
        } else if (abs < MONTH) {
            magnitude = plural(abs / DAY, "day");
        } else if (abs < YEAR) {
            magnitude = plural(abs / MONTH, "month");
        } else {
            magnitude = plural(abs / YEAR, "year");
        }
        return delta < 0 ? magnitude + " ago" : "in " + magnitude;
    }

    private static String plural(long count, String unit) {
        return count + " " + unit + (count == 1 ? "" : "s");
    }

    /**
     * The zone is resolved per call rather than baked into the formatter, so a
     * client that outlives a timezone change (or a laptop carried across one)
     * still renders in the zone the player is actually in.
     */
    private static String format(Instant instant, DateTimeFormatter formatter) {
        return formatter.withZone(ZoneId.systemDefault()).format(instant);
    }

    private static DateTimeFormatter pattern(String pattern) {
        return DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH);
    }

    /** This node's own text, excluding siblings. */
    private static String ownText(Component node) {
        StringBuilder sb = new StringBuilder();
        node.getContents().visit(part -> {
            sb.append(part);
            return Optional.empty();
        });
        return sb.toString();
    }
}
