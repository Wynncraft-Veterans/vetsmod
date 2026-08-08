package org.wynnvets.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralized logging utility for VetsMod with a toggleable debug level.
 *
 * <p>All mod code should use this class instead of creating per-class SLF4J
 * loggers. Standard levels (info, warn, error) are always active. The
 * {@link #debug} level is silent by default and enabled via {@code /wv debug true}.</p>
 *
 * <p>Default (non-debug) logging is kept minimal and meaningful: potential
 * problems use {@code warn}, hard failures use {@code error}, and only
 * noteworthy lifecycle milestones use {@code info}. When debug mode is
 * enabled, verbose diagnostic output is emitted at {@code info} level so
 * it always appears in the player's log file regardless of the SLF4J root
 * level configuration.</p>
 */
public final class VetsLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger("vetsmod");
    private static volatile boolean debugEnabled = false;

    private VetsLogger() {
    }

    /** Returns whether debug-level logging is currently active. */
    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    /** Sets whether debug-level logging is active. */
    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
        LOGGER.info("[VetsMod] Debug logging {}", enabled ? "enabled" : "disabled");
    }

    // ── Standard levels (always active) ─────────────────────────────

    /** Log a noteworthy lifecycle milestone. Use sparingly. */
    public static void info(String msg) {
        LOGGER.info("[VetsMod] {}", msg);
    }

    public static void info(String format, Object arg) {
        LOGGER.info("[VetsMod] " + format, arg);
    }

    public static void info(String format, Object arg1, Object arg2) {
        LOGGER.info("[VetsMod] " + format, arg1, arg2);
    }

    public static void info(String format, Object... args) {
        LOGGER.info("[VetsMod] " + format, args);
    }

    /** Log a potential problem. Always visible in console. */
    public static void warn(String msg) {
        LOGGER.warn("[VetsMod] {}", msg);
    }

    public static void warn(String format, Object arg) {
        LOGGER.warn("[VetsMod] " + format, arg);
    }

    public static void warn(String format, Object arg1, Object arg2) {
        LOGGER.warn("[VetsMod] " + format, arg1, arg2);
    }

    public static void warn(String format, Object... args) {
        LOGGER.warn("[VetsMod] " + format, args);
    }

    /** Log a hard failure. */
    public static void error(String msg) {
        LOGGER.error("[VetsMod] {}", msg);
    }

    public static void error(String format, Object arg) {
        LOGGER.error("[VetsMod] " + format, arg);
    }

    public static void error(String format, Object... args) {
        LOGGER.error("[VetsMod] " + format, args);
    }

    // ── Debug level (only active when toggled on) ───────────────────

    /**
     * Log a diagnostic message. Only emitted when debug mode is enabled.
     * Written at SLF4J {@code info} level to ensure visibility in standard
     * log files that filter out {@code debug}.
     */
    public static void debug(String msg) {
        if (debugEnabled) {
            LOGGER.info("[VetsMod DEBUG] {}", msg);
        }
    }

    public static void debug(String format, Object arg) {
        if (debugEnabled) {
            LOGGER.info("[VetsMod DEBUG] " + format, arg);
        }
    }

    public static void debug(String format, Object arg1, Object arg2) {
        if (debugEnabled) {
            LOGGER.info("[VetsMod DEBUG] " + format, arg1, arg2);
        }
    }

    public static void debug(String format, Object arg1, Object arg2, Object arg3) {
        if (debugEnabled) {
            LOGGER.info("[VetsMod DEBUG] " + format, arg1, arg2, arg3);
        }
    }

    public static void debug(String format, Object... args) {
        if (debugEnabled) {
            LOGGER.info("[VetsMod DEBUG] " + format, args);
        }
    }
}
