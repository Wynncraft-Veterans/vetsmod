package org.wynnvets.retro;

import com.wynntils.utils.mc.McUtils;
import java.io.IOException;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.config.VetsConfigKeys;
import org.wynnvets.listeners.ServerConnectionListener;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.retro.manifest.RetroManifest;
import org.wynnvets.retro.manifest.RetroPart;
import org.wynnvets.retro.pack.RetroPackInjector;

/**
 * The retro mode's state: whether the player turned it on, whether the retro pack is drawn right
 * now, and whether a failure has stopped it for the session.
 *
 * <p>The setting is {@link VetsConfigKeys#RETRO_TEXTURES}, and only {@link #setEnabled} writes it,
 * because a change shows only after a resource reload, which that method starts. The pack itself
 * is added to each reload by
 * {@link org.wynnvets.retro.pack.RetroPackInjector RetroPackInjector}, which reports back here:
 * when a reload starts, when one with a non-empty retro pack in it has finished
 * ({@link #isPackLive()}), and when a generation, or a reload with such a pack in it, failed
 * ({@link #latch}).</p>
 *
 * <p>The latch is the guard against a retro failure costing the player their resource packs.
 * Minecraft answers a failed reload by deselecting every pack and saving {@code options.txt}, so
 * once anything retro has gone wrong this session, no further reload gets a retro pack, and the
 * Wynntils settings retro changed go back ({@link WynntilsConfigOverrides}). A retro whose Wynntils
 * listeners could not register latches too
 * ({@link org.wynnvets.listeners.WynntilsEventListener#register() WynntilsEventListener.register}),
 * since it would draw only some of what it means to. It holds until the game restarts; the setting
 * is left as it was, so the next launch tries again.</p>
 *
 * <p>The fields are read on the render thread and written from the reload's threads too, so each
 * is volatile. The live flag and the reload count change together, under the class lock, so a
 * reload that finishes late cannot mark the next one live.</p>
 *
 * <p>It also keeps what the code that draws reads: the rule files, parsed once a session
 * ({@link #manifest()}), the latest generation's report ({@link #lastReport()}), which
 * {@link LivePack} answers from while the pack is live ({@link #liveReport()}), and the parts of
 * retro that are on ({@link #partsOn()}).</p>
 */
public final class RetroModeManager {

    /** Whether the last finished reload included a retro pack that had files in it. */
    private static volatile boolean packLive;

    /** How many reloads have started this session; guarded by the class lock. */
    private static int reloads;

    /** Why retro stopped for the session, or {@code null} while it has not. */
    private static volatile String latchReason;

    /** The latest generation's report, or {@code null} before the first this session. */
    private static volatile RetroReport lastReport;

    /** The rule files as {@link #manifest()} parsed them, or {@code null} before its first call. */
    private static volatile RetroManifest manifest;

    /** The parts that are on, or {@code null} before the first ask; written under the class lock. */
    private static volatile Set<RetroPart> partsOn;

    private RetroModeManager() {}

    /** What {@link #setEnabled} or {@link #setPartsOff} did about the resources. */
    public enum Outcome {
        /** The pack list changes, so a resource reload has been queued. */
        RELOADING,
        /** The loaded resources already match the setting. */
        UNCHANGED,
        /** On, but no Wynncraft pack is loaded for the retro pack to sit above yet. */
        NO_WYNNCRAFT_PACK,
        /** On, but a failure has stopped retro until the game restarts. */
        LATCHED
    }

    /**
     * Whether the player has turned retro textures on.
     *
     * @return {@link VetsConfigKeys#RETRO_TEXTURES}
     */
    public static boolean isEnabled() {
        return VetsConfig.get(VetsConfigKeys.RETRO_TEXTURES);
    }

    /**
     * Whether the next reload gets a retro pack: on, and not stopped for the session.
     *
     * @return {@code true} when enabled and not latched
     */
    public static boolean isActive() {
        return isEnabled() && latchReason == null;
    }

    /**
     * Whether retro art is drawn right now: the last reload to finish included a retro pack with
     * files in it. A reload clears this as it starts, so it is {@code false} while one runs.
     * Anything retro draws on top of Wynncraft's own art gates on this, so it never paints over
     * art the pack did not replace.
     *
     * @return {@code true} while a non-empty retro pack is loaded
     */
    public static boolean isPackLive() {
        return packLive;
    }

    /**
     * Whether retro art is drawn right now and the game is on Wynncraft. The pack can stay live
     * elsewhere: Wynntils' preloaded copy of Wynncraft's pack, and the retro pack above it, stay
     * loaded in singleplayer. A mixin that runs on every server asks this before it acts, as
     * Wynntils' events do not reach it there. It is cheap, and safe before the client has started,
     * when the answer is {@code false}.
     *
     * @return {@code true} while {@link #isPackLive()} and Wynntils says the game is on Wynncraft
     */
    public static boolean isLiveOnWynncraft() {
        if (!packLive) return false;
        try {
            return ServerConnectionListener.onWynncraft();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * The parts of retro that are on: every part but those {@link VetsConfigKeys#RETRO_PARTS_OFF}
     * names. It is read from the setting once, and after that only {@link #setPartsOff} changes
     * it, because a slot asks every frame. A setting that cannot be read leaves every part on.
     *
     * @return an unmodifiable set
     */
    public static Set<RetroPart> partsOn() {
        Set<RetroPart> known = partsOn;
        if (known != null) return known;
        synchronized (RetroModeManager.class) {
            if (partsOn == null) partsOn = RetroPart.allBut(RetroPart.parseOff(storedPartsOff()));
            return partsOn;
        }
    }

    private static String storedPartsOff() {
        try {
            return VetsConfig.getString(VetsConfigKeys.RETRO_PARTS_OFF);
        } catch (RuntimeException | LinkageError e) {
            return "";
        }
    }

    /**
     * Whether a part of retro is on, whether or not retro is.
     *
     * @param part the part
     * @return whether it is in {@link #partsOn()}
     */
    public static boolean isPartOn(RetroPart part) {
        return partsOn().contains(part);
    }

    /**
     * {@link #isPackLive()} for what one part draws.
     *
     * @param part the part
     * @return {@code true} while a non-empty retro pack is loaded and the part is on
     */
    public static boolean isPartLive(RetroPart part) {
        return packLive && isPartOn(part);
    }

    /**
     * {@link #isLiveOnWynncraft()} for what one part draws.
     *
     * @param part the part
     * @return {@code true} while the part is on, a non-empty retro pack is loaded and the game is
     *     on Wynncraft
     */
    public static boolean isPartLiveOnWynncraft(RetroPart part) {
        return isPartOn(part) && isLiveOnWynncraft();
    }

    /**
     * The rule files, for the code that reads them while drawing: parsed on the first call and
     * kept for the session, since they ship in the jar. When they cannot be read, a warning is
     * logged once and the rules are empty, so every reader does nothing; the pack generator reads
     * the files itself, and stops retro when it cannot.
     *
     * @return the parsed files, never {@code null}
     */
    public static RetroManifest manifest() {
        RetroManifest loaded = manifest;
        if (loaded != null) return loaded;
        synchronized (RetroModeManager.class) {
            if (manifest == null) {
                try {
                    manifest = RetroManifest.load();
                } catch (IOException | RuntimeException e) {
                    VetsLogger.warn("Retro: could not read the rule files; nothing is drawn", e);
                    manifest = RetroManifest.empty();
                }
            }
            return manifest;
        }
    }

    /**
     * Why retro stopped for the session.
     *
     * @return the reason, or {@code null} while it has not stopped
     */
    public static String latchReason() {
        return latchReason;
    }

    /**
     * The latest generation's report.
     *
     * @return the report, or {@code null} before the first generation this session
     */
    public static RetroReport lastReport() {
        return lastReport;
    }

    /**
     * The report of the pack drawn right now.
     *
     * @return the latest generation's report while {@link #isPackLive()}; {@code null} otherwise
     */
    public static RetroReport liveReport() {
        RetroReport report = lastReport;
        return packLive ? report : null;
    }

    /**
     * Turns retro textures on or off, stores the choice, and starts a resource reload when the
     * loaded packs no longer match it: on and not stopped, with a Wynncraft pack loaded and no
     * retro pack, or otherwise with a retro pack still loaded. The reload is queued on the render
     * thread. With no Wynncraft pack yet, the next reload that brings one (joining Wynncraft)
     * adds the retro pack. It also changes the {@code wynntils_config} settings while retro is on
     * and not stopped, or puts them back ({@link WynntilsConfigOverrides}). Render thread only.
     *
     * @param enabled whether retro textures should be on
     * @return what happened to the resources; {@link Outcome#LATCHED} whenever {@code enabled}
     *     meets a stopped session, even if a reload was queued to drop an empty retro pack
     */
    public static Outcome setEnabled(boolean enabled) {
        VetsConfig.set(VetsConfigKeys.RETRO_TEXTURES, enabled);
        if (isActive()) {
            WynntilsConfigOverrides.apply();
        } else {
            WynntilsConfigOverrides.restore();
        }
        boolean wynncraft = RetroPackInjector.isWynncraftLoaded();
        boolean reload = (isActive() && wynncraft) != RetroPackInjector.isRetroLoaded();
        if (reload) {
            Minecraft mc = McUtils.mc();
            mc.execute(mc::reloadResourcePacks);
        }
        if (enabled && latchReason != null) return Outcome.LATCHED;
        if (reload) return Outcome.RELOADING;
        if (enabled && !wynncraft) return Outcome.NO_WYNNCRAFT_PACK;
        return Outcome.UNCHANGED;
    }

    /**
     * Turns parts of retro off, and every other on, and stores the choice. When that changes
     * anything it applies the {@code wynntils_config} settings again while retro is active, which
     * puts back those of a part now off, and starts a resource reload when a retro pack is loaded;
     * with none the next reload reads the setting. Render thread only.
     *
     * @param off the parts to turn off
     * @return what happened to the resources; {@link Outcome#LATCHED} when retro is on but stopped,
     *     with no reload queued
     */
    public static Outcome setPartsOff(Set<RetroPart> off) {
        Set<RetroPart> on = RetroPart.allBut(off);
        boolean changed = !on.equals(partsOn());
        VetsConfig.setString(VetsConfigKeys.RETRO_PARTS_OFF, RetroPart.formatOff(off));
        synchronized (RetroModeManager.class) {
            partsOn = on;
        }
        if (changed && isActive()) WynntilsConfigOverrides.apply();
        Outcome outcome =
                partsOutcome(
                        changed,
                        isEnabled(),
                        latchReason != null,
                        RetroPackInjector.isRetroLoaded(),
                        RetroPackInjector.isWynncraftLoaded());
        if (outcome == Outcome.RELOADING) {
            Minecraft mc = McUtils.mc();
            mc.execute(mc::reloadResourcePacks);
        }
        return outcome;
    }

    /**
     * What storing new parts does about the resources.
     *
     * @param changed whether the parts that are on change
     * @param enabled whether retro is on
     * @param latched whether a failure has stopped retro until the game restarts
     * @param retroLoaded whether a retro pack is loaded
     * @param wynncraftLoaded whether a Wynncraft pack is loaded
     * @return the outcome; {@link Outcome#RELOADING} means a reload must be queued
     */
    // Package-private for unit tests. See RetroModeManagerTest.
    static Outcome partsOutcome(
            boolean changed,
            boolean enabled,
            boolean latched,
            boolean retroLoaded,
            boolean wynncraftLoaded) {
        if (enabled && latched) return Outcome.LATCHED;
        if (changed && retroLoaded) return Outcome.RELOADING;
        if (enabled && !wynncraftLoaded) return Outcome.NO_WYNNCRAFT_PACK;
        return Outcome.UNCHANGED;
    }

    // ── Reported by the retro pack ──────────────────────────────────────

    /**
     * A resource reload is starting: whatever was drawn is about to be replaced.
     *
     * @return the reload's number, for {@link #packWentLive(int)}
     */
    public static synchronized int reloadStarted() {
        packLive = false;
        return ++reloads;
    }

    /**
     * A reload that included a retro pack with files in it has finished. Ignored when another
     * reload has started since.
     *
     * @param reload the number {@link #reloadStarted()} gave it
     */
    public static synchronized void packWentLive(int reload) {
        if (reload == reloads) packLive = true;
    }

    /**
     * Records a finished generation.
     *
     * @param report what it did
     */
    public static void generated(RetroReport report) {
        lastReport = report;
    }

    /**
     * Stops retro for the rest of the session. The first call logs a warning with the cause, hands
     * the putting back of retro's Wynntils settings to the render thread and tells the player in
     * chat; later calls keep the first reason and only log at debug. Safe from any thread: the chat
     * line is handed to the render thread too, and dropped when there is no player yet.
     *
     * @param reason why, as a clause such as {@code "the retro pack failed to build"}
     * @param cause the failure
     */
    public static void latch(String reason, Throwable cause) {
        synchronized (RetroModeManager.class) {
            if (latchReason != null) {
                VetsLogger.debug("Retro textures already stopped; also: {}", reason, cause);
                return;
            }
            latchReason = reason;
        }
        VetsLogger.warn("Retro textures stopped until the game restarts: {}", reason, cause);
        restoreWynntilsSettings();
        ChatUtils.sendLocalMessage(
                Component.literal(
                                "Retro textures stopped until the game restarts: "
                                        + reason
                                        + ". Wynncraft's textures are showing.")
                        .withStyle(ChatFormatting.RED));
    }

    /**
     * Hands {@link WynntilsConfigOverrides#restore} to the render thread. Before the client can
     * take it, {@link WynntilsConfigOverrides}' start-up step does the same, seeing retro stopped.
     */
    private static void restoreWynntilsSettings() {
        try {
            McUtils.mc().execute(WynntilsConfigOverrides::restore);
        } catch (RuntimeException | LinkageError e) {
            VetsLogger.debug("Retro: its Wynntils settings wait for the start-up step", e);
        }
    }

    // Test-only: resets the session state, the latch included, so it doesn't leak across tests.
    static synchronized void clearForTesting() {
        packLive = false;
        reloads = 0;
        latchReason = null;
        lastReport = null;
        partsOn = null;
    }

    // Test-only: stands in for the setting, which cannot be read outside the game.
    static synchronized void setPartsOnForTesting(Set<RetroPart> on) {
        partsOn = on;
    }
}
