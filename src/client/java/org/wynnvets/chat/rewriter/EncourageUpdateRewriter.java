package org.wynnvets.chat.rewriter;

import com.wynntils.utils.mc.ComponentUtils;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.wynnvets.Vetsmod;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.chat.GuildChatLine;
import org.wynnvets.chat.NickResolver;
import org.wynnvets.fetcher.polling.StaffRanksPoller;
import org.wynnvets.logging.VetsLogger;

/**
 * Rewrites incoming guild chat "encourage update" messages from staff.
 *
 * <p>When a staff member sends {@code /encourage <version>}, it appears in guild
 * chat as {@code ⚠⚠⚠ If you are using vetsmod, it's outdated (current version <version>) ⚠⚠⚠}.
 * This rewriter intercepts that message and, for vetsmod users, replaces it with
 * a version-comparison result:</p>
 *
 * <ul>
 *   <li><b>Outdated</b> (local &lt; announced): Wynntils defective-style obfuscated red text</li>
 *   <li><b>Up to date</b> (local &ge; announced): Wynntils perfect-style rainbow text</li>
 * </ul>
 */
public final class EncourageUpdateRewriter {

    private static final Pattern ENCOURAGE_PATTERN =
            Pattern.compile(
                    "⚠⚠⚠ If you are using vetsmod, it's outdated \\(current version ([0-9]+(?:\\.[0-9]+)*)\\) ⚠⚠⚠");

    private EncourageUpdateRewriter() {}

    /**
     * Attempts to rewrite an encourage-update guild chat message.
     *
     * @param message       the original chat component (used to resolve nicknames via hover text)
     * @param messageString the raw chat line
     * @return {@code true} if the message was rewritten (caller should cancel)
     */
    public static boolean tryRewrite(Component message, String messageString) {
        GuildChatLine.Parsed parsed = GuildChatLine.parse(messageString);
        if (parsed == null) {
            return false;
        }

        // The parsed username may be a Wynncraft nickname; the real username is
        // attached as a hover event on the name span.
        String senderUsername = NickResolver.realUsernameOrFallback(message, parsed.username());

        if (!isCurrentStaffSender(senderUsername)) {
            return false;
        }

        String cleanedMessage = stripWrapArtifacts(parsed.message());
        Matcher matcher = ENCOURAGE_PATTERN.matcher(cleanedMessage);
        if (!matcher.matches()) {
            return false;
        }

        String announcedVersion = matcher.group(1);
        String localVersion = getLocalModVersion();

        VetsLogger.debug(
                "Encourage update: announced={}, local={}", announcedVersion, localVersion);

        String senderRank = resolveStaffRank(senderUsername);

        MutableComponent body;
        if (isVersionLessThan(localVersion, announcedVersion)) {
            body = ComponentUtils.makeObfuscated("Your vetsmod is outdated", 0.08f, 0.04f);
        } else {
            body = ComponentUtils.makeRainbowStyle("You have up to date vetsmod", true);
        }

        ChatUtils.sendGuildChatMessageWithBody(senderRank, senderUsername, body);
        return true;
    }

    /**
     * Compares two semantic version strings (e.g. "0.8.15" vs "0.9.0").
     *
     * @return {@code true} if {@code local} is strictly less than {@code announced}
     */
    static boolean isVersionLessThan(String local, String announced) {
        int[] localParts = parseVersionParts(local);
        int[] announcedParts = parseVersionParts(announced);

        int length = Math.max(localParts.length, announcedParts.length);
        for (int i = 0; i < length; i++) {
            int l = i < localParts.length ? localParts[i] : 0;
            int a = i < announcedParts.length ? announcedParts[i] : 0;
            if (l < a) return true;
            if (l > a) return false;
        }
        return false;
    }

    private static int[] parseVersionParts(String version) {
        String[] segments = version.split("\\.");
        int[] parts = new int[segments.length];
        for (int i = 0; i < segments.length; i++) {
            try {
                parts[i] = Integer.parseInt(segments[i]);
            } catch (NumberFormatException e) {
                parts[i] = 0;
            }
        }
        return parts;
    }

    private static String getLocalModVersion() {
        return FabricLoader.getInstance()
                .getModContainer(Vetsmod.MOD_ID)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("0.0.0");
    }

    /**
     * Strips server-injected wrap artifacts (newlines, continuation-prefix PUA
     * characters) from a message so that regex matching works on long messages
     * that Wynncraft splits across multiple visual lines.
     */
    private static String stripWrapArtifacts(String message) {
        StringBuilder sb = new StringBuilder(message.length());
        for (int i = 0; i < message.length(); ) {
            int cp = message.codePointAt(i);
            int charCount = Character.charCount(cp);
            if (cp == '\n') {
                i += charCount;
                continue;
            }
            int type = Character.getType(cp);
            if (type == Character.PRIVATE_USE || (type == Character.UNASSIGNED && cp > 0xFFFF)) {
                i += charCount;
                continue;
            }
            sb.appendCodePoint(cp);
            i += charCount;
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    private static String resolveStaffRank(String username) {
        if (username == null || username.isBlank()) {
            return "Captain";
        }

        String normalized = username.trim();
        return StaffRanksPoller.confirmedRankFor(normalized)
                .or(
                        () -> {
                            for (String variant : normalized.split("/")) {
                                String candidate = variant.trim();
                                if (!candidate.isEmpty()) {
                                    var rank = StaffRanksPoller.confirmedRankFor(candidate);
                                    if (rank.isPresent()) return rank;
                                }
                            }
                            return java.util.Optional.empty();
                        })
                .orElse("Captain");
    }

    private static boolean isCurrentStaffSender(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }

        String normalized = username.trim();
        if (StaffRanksPoller.confirmedRankFor(normalized).isPresent()) {
            return true;
        }

        String[] variants = normalized.split("/");
        for (String variant : variants) {
            String candidate = variant.trim();
            if (!candidate.isEmpty() && StaffRanksPoller.confirmedRankFor(candidate).isPresent()) {
                return true;
            }
        }

        return false;
    }
}
