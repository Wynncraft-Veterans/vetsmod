package org.wynnvets.chat;

/**
 * The characters vetsmod must not put on screen from player-typed text, and the one place that
 * strips them.
 *
 * <p><b>Hard set</b>: U+00C1, U+E07E, U+E080 and U+E086. Rendering any of them through
 * Wynncraft's resource-pack fonts breaks the client. There is no config or server override. A
 * feature that ever needs one of these glyphs adds a hard-coded, commented exemption at its own
 * call site, so the exception is a reviewed commit, not data.</p>
 *
 * <p><b>Soft set</b>: the section sign U+00A7, the rank icons U+E014–U+E025, the chat-banner
 * pieces U+E00E–U+E011, U+E070, U+E07A and U+E085, the negative-width spaces U+E000, U+E012,
 * U+E071, U+E07F, U+E087, U+E088 and U+2064, and the pill letters U+E040–U+E06F. They don't break
 * the client, but in player text they spoof Wynncraft's own badges, banners and pills. Built
 * components and Wynncraft's own lines use them legitimately, so only player-typed text is
 * stripped of them.</p>
 *
 * <p>{@code vetsmod_chat_pipeline.md} §4 lists where each set is stripped.</p>
 */
public final class BannedChars {

    // Written as escapes: a rendered literal would silently lose its three private-use chars.
    private static final String HARD = "\u00C1\uE07E\uE080\uE086";

    private BannedChars() {}

    /**
     * Returns {@code text} without the hard set, or the same instance when it holds none.
     * {@code null} stays {@code null}.
     */
    public static String stripHard(String text) {
        return strip(text, false);
    }

    /**
     * Returns {@code text} without the hard or the soft set, or the same instance when it holds
     * neither. {@code null} stays {@code null}.
     */
    public static String stripAll(String text) {
        return strip(text, true);
    }

    private static String strip(String text, boolean soft) {
        if (text == null) {
            return null;
        }
        StringBuilder kept = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean banned = HARD.indexOf(c) >= 0 || (soft && isSoft(c));
            if (banned) {
                if (kept == null) {
                    kept = new StringBuilder(text.length());
                    kept.append(text, 0, i);
                }
            } else if (kept != null) {
                kept.append(c);
            }
        }
        return kept == null ? text : kept.toString();
    }

    private static boolean isSoft(char c) {
        return c == '\u00A7'
                || c == '\u2064'
                || c == '\uE000'
                || (c >= '\uE00E' && c <= '\uE012')
                || (c >= '\uE014' && c <= '\uE025')
                || (c >= '\uE040' && c <= '\uE06F')
                || c == '\uE070'
                || c == '\uE071'
                || c == '\uE07A'
                || c == '\uE07F'
                || c == '\uE085'
                || c == '\uE087'
                || c == '\uE088';
    }
}
