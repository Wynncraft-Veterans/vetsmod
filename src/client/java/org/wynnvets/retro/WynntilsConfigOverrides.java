package org.wynnvets.retro;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.wynnvets.config.VetsConfigKeys;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.retro.manifest.RetroManifest;
import org.wynnvets.retro.manifest.WynntilsSetting;
import org.wynnvets.retro.rules.PartFilter;

/**
 * The Wynntils settings retro changes while it is on and not stopped ({@code wynntils_config} in
 * {@code retro_textures.yml}), and puts back when it goes off or stops.
 *
 * <p>A setting is found the way {@code /wynntils config set <feature> <setting> <value>} finds it,
 * through Wynntils' own API: {@code Managers.Feature.getFeatureFromString}, then the feature's
 * {@code getConfigOptionFromString}, and the value is read with the setting's
 * {@code tryParseStringValue}. A setting this Wynntils does not have, or a value it cannot read,
 * is skipped with a warning: {@code setValue(null)} would reset the player's setting to Wynntils'
 * default instead.</p>
 *
 * <p>Before retro first changes a setting it saves the player's value, and whether the player had
 * set it themselves ({@code userEdited}), in {@link VetsConfigKeys#RETRO_WYNNTILS_SNAPSHOT}. A
 * setting is saved only while the snapshot has no entry for it, so a snapshot that survived a
 * crash, which holds the player's values, is never overwritten with retro's. Putting a setting
 * back happens only while it still has the value retro set: a setting the player changed since is
 * theirs, and its entry is dropped. It goes back to the saved value, or is reset when the player
 * had never set it, so Wynntils again leaves it out of its file. A setting that cannot be found,
 * read or set keeps its entry, so a later start-up or {@code /wv retro off} tries again, just as
 * {@link #apply()} keeps the entry of a setting this Wynntils does not have. Each change is saved
 * with {@code Managers.Config.saveConfig()}.</p>
 *
 * <ul>
 *   <li>{@link #apply()}: on {@code /wv retro on}, at start-up while retro is on, unless it has
 *       stopped for the session, and when {@link RetroModeManager#setPartsOff} changes the parts.
 *       A part that is off keeps its feature's settings the player's.</li>
 *   <li>{@link #restore()}: on {@code /wv retro off}, when retro stops, when the game closes, and
 *       at start-up while retro is off or stopped and a snapshot is left over (the game stopped
 *       without closing). Putting the settings back at every close leaves the player's Wynntils
 *       file as their own after a normal close, so removing vetsmod then strands none of retro's
 *       values; after a crash or a kill they stay in the file until vetsmod next starts (the
 *       snapshot survives on disk).</li>
 * </ul>
 *
 * <p>Wynntils loads its features and its settings file at the end of the game's first resource
 * reload, so the start-up step waits for that: a client tick checks until Wynntils has
 * features. Everything here runs on the render thread. Every call into Wynntils is guarded, as
 * {@code GuildLogWalker} guards its feature lookup: a failure is logged and skips that setting,
 * and never reaches the caller. The calls themselves are in {@link LiveWynntilsSettings}; what
 * happens to a setting goes through {@link WynntilsSettings}, and is unit-tested.</p>
 */
public final class WynntilsConfigOverrides {

    /** Whether the start-up step has run; render thread only. */
    private static boolean started;

    private WynntilsConfigOverrides() {}

    /** What putting a saved setting back came to. */
    private enum PutBack {
        /** It had retro's value, and has the player's again: its entry is done with. */
        RESTORED,
        /** The player changed it since, so it is theirs: its entry is dropped. */
        LEFT,
        /** It could not be found, read or set: its entry is kept, to try again later. */
        FAILED
    }

    /**
     * One setting retro changed: the player's value before, and the value retro set.
     *
     * @param feature the feature's short name
     * @param config the setting's field name
     * @param original the player's value, as {@code tryParseStringValue} reads it
     * @param userEdited whether the player had set it themselves
     * @param applied the value retro set
     */
    // Package-private for unit tests. See WynntilsConfigOverridesTest.
    record Saved(
            String feature, String config, String original, boolean userEdited, String applied) {

        /** The setting's name, as {@code /wynntils config} takes it: feature, then setting. */
        String key() {
            return feature + " " + config;
        }
    }

    /**
     * Registers the start-up step on the client tick and the restore at {@code CLIENT_STOPPING}.
     * Call it once, from {@code VetsmodClient}'s {@code CLIENT_STARTED} phase: the tick reaches
     * Wynntils' {@code Managers}.
     */
    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> startUp());
        ClientLifecycleEvents.CLIENT_STOPPING.register(
                client -> {
                    if (started) restore();
                });
    }

    /** Once Wynntils has loaded its features: applies, or puts back a leftover snapshot. */
    private static void startUp() {
        if (started || !LiveWynntilsSettings.INSTANCE.loaded()) return;
        started = true;
        if (RetroModeManager.isActive()) {
            apply();
        } else {
            restore();
        }
    }

    /**
     * Changes every {@code wynntils_config} setting whose part is on to retro's value, saving the
     * player's first where the snapshot has no entry for it. A saved setting the rule files no
     * longer change, or whose part has gone off, is put back, and stays saved when it cannot be.
     * Does nothing until Wynntils has loaded its features; the start-up step applies then.
     */
    public static void apply() {
        RetroManifest manifest = RetroModeManager.manifest();
        apply(
                LiveWynntilsSettings.INSTANCE,
                rowsOn(
                        manifest.wynntilsConfig(),
                        new PartFilter(manifest.parts(), RetroModeManager.partsOn())));
    }

    /**
     * The rows {@link #apply()} changes.
     *
     * @param rows the {@code wynntils_config} rows
     * @param parts the parts that are on
     * @return the rows whose feature's part is on, in order
     */
    // Package-private for unit tests. See WynntilsConfigOverridesTest.
    static List<WynntilsSetting> rowsOn(List<WynntilsSetting> rows, PartFilter parts) {
        return rows.stream().filter(row -> parts.settingOn(row.feature())).toList();
    }

    // Package-private for unit tests. See WynntilsConfigOverridesTest.
    static void apply(WynntilsSettings settings, List<WynntilsSetting> rows) {
        if (!settings.loaded()) return;
        Map<String, Saved> saved = new LinkedHashMap<>();
        for (Saved entry : decode(settings.snapshot())) saved.put(entry.key(), entry);
        List<Saved> next = new ArrayList<>();
        Map<Saved, WynntilsSettings.Setting> toSet = new LinkedHashMap<>();
        for (WynntilsSetting row : rows) {
            Saved entry = saved.remove(row.feature() + " " + row.config());
            WynntilsSettings.Setting setting = find(settings, row.feature(), row.config());
            if (setting == null) {
                // Not in this Wynntils: keep a saved value, in case another version has it.
                if (entry != null) next.add(entry);
                continue;
            }
            entry =
                    entry == null
                            ? snapshot(setting, row)
                            : new Saved(
                                    entry.feature(),
                                    entry.config(),
                                    entry.original(),
                                    entry.userEdited(),
                                    row.value());
            if (entry == null) continue;
            next.add(entry);
            toSet.put(entry, setting);
        }
        boolean changed = false;
        // What is left is a setting the rule files no longer change.
        for (Saved gone : saved.values()) {
            PutBack result = putBack(settings, gone);
            changed |= result == PutBack.RESTORED;
            if (result == PutBack.FAILED) next.add(gone);
        }
        // The snapshot is written before anything changes, so a crash cannot lose a value.
        settings.writeSnapshot(encode(next));
        for (Map.Entry<Saved, WynntilsSettings.Setting> pending : toSet.entrySet()) {
            changed |= set(pending.getValue(), pending.getKey());
        }
        if (changed) settings.save();
    }

    /**
     * Puts back every saved setting that still has retro's value, then clears the snapshot of all
     * but the settings that could not be found, read or set, which a later call tries again. Does
     * nothing without a snapshot, or until Wynntils has loaded its features.
     */
    public static void restore() {
        restore(LiveWynntilsSettings.INSTANCE);
    }

    // Package-private for unit tests. See WynntilsConfigOverridesTest.
    static void restore(WynntilsSettings settings) {
        String snapshot = settings.snapshot();
        if (snapshot == null || snapshot.isEmpty() || !settings.loaded()) return;
        boolean changed = false;
        List<Saved> failed = new ArrayList<>();
        for (Saved entry : decode(snapshot)) {
            PutBack result = putBack(settings, entry);
            changed |= result == PutBack.RESTORED;
            if (result == PutBack.FAILED) failed.add(entry);
        }
        if (changed) settings.save();
        settings.writeSnapshot(failed.isEmpty() ? "" : encode(failed));
    }

    /**
     * The settings retro has changed and not yet put back, as {@code /wv debug trigger retroDump} lists them,
     * read from the snapshot alone.
     *
     * @return one {@code "<feature> <setting> <value>"} per setting, in file order; none while
     *     retro has changed nothing
     */
    public static List<String> changed() {
        List<String> lines = new ArrayList<>();
        for (Saved entry : decode(LiveWynntilsSettings.INSTANCE.snapshot())) {
            lines.add(entry.key() + " " + entry.applied());
        }
        return lines;
    }

    // ── One setting ─────────────────────────────────────────────────────

    /** The setting, or {@code null}, logged by the lookup or here. */
    private static WynntilsSettings.Setting find(
            WynntilsSettings settings, String feature, String config) {
        try {
            return settings.find(feature, config);
        } catch (RuntimeException | LinkageError e) {
            VetsLogger.warn("Retro: could not look up Wynntils' {} {}", feature, config, e);
            return null;
        }
    }

    /**
     * The player's value of {@code setting}, before retro sets it to {@code row}'s; or {@code null}
     * (logged) when retro could not set it, or could not put the value back.
     */
    private static Saved snapshot(WynntilsSettings.Setting setting, WynntilsSetting row) {
        String key = row.feature() + " " + row.config();
        try {
            if (!setting.reads(row.value())) {
                VetsLogger.warn(
                        "Retro: Wynntils cannot read '{}' for {}; skipped it", row.value(), key);
                return null;
            }
            String original = setting.valueText();
            if (!setting.holds(original)) {
                VetsLogger.warn(
                        "Retro: could not save {}'s value {} to put back; skipped it",
                        key,
                        original);
                return null;
            }
            return new Saved(
                    row.feature(), row.config(), original, setting.userEdited(), row.value());
        } catch (RuntimeException | LinkageError e) {
            VetsLogger.warn("Retro: could not save Wynntils' {}; skipped it", key, e);
            return null;
        }
    }

    /** Sets {@code setting} to {@code entry}'s applied value; whether it changed. */
    private static boolean set(WynntilsSettings.Setting setting, Saved entry) {
        try {
            if (!setting.reads(entry.applied()) || setting.holds(entry.applied())) return false;
            setting.set(entry.applied());
            VetsLogger.debug("Retro: set Wynntils' {} to {}", entry.key(), entry.applied());
            return true;
        } catch (RuntimeException | LinkageError e) {
            VetsLogger.warn("Retro: could not set Wynntils' {}", entry.key(), e);
            return false;
        }
    }

    /**
     * Puts {@code entry}'s setting back, if it still has retro's value. A setting the player
     * changed since is left as it is.
     */
    private static PutBack putBack(WynntilsSettings settings, Saved entry) {
        WynntilsSettings.Setting setting = find(settings, entry.feature(), entry.config());
        if (setting == null) return PutBack.FAILED;
        try {
            if (!setting.reads(entry.applied())) {
                VetsLogger.warn(
                        "Retro: Wynntils cannot read retro's '{}' for {}; kept the saved value",
                        entry.applied(),
                        entry.key());
                return PutBack.FAILED;
            }
            if (!setting.holds(entry.applied())) {
                VetsLogger.debug("Retro: Wynntils' {} was changed since; left it", entry.key());
                return PutBack.LEFT;
            }
            if (!entry.userEdited()) {
                setting.reset();
            } else {
                if (!setting.reads(entry.original())) {
                    VetsLogger.warn(
                            "Retro: Wynntils cannot read the saved '{}' for {}; kept it",
                            entry.original(),
                            entry.key());
                    return PutBack.FAILED;
                }
                setting.set(entry.original());
            }
            VetsLogger.debug("Retro: put Wynntils' {} back", entry.key());
            return PutBack.RESTORED;
        } catch (RuntimeException | LinkageError e) {
            VetsLogger.warn(
                    "Retro: could not put Wynntils' {} back; kept the saved value", entry.key(), e);
            return PutBack.FAILED;
        }
    }

    // ── The snapshot ────────────────────────────────────────────────────

    /**
     * The snapshot as stored: a JSON array of objects with {@code feature}, {@code config},
     * {@code original}, {@code userEdited} and {@code applied}.
     *
     * @param saved the entries, in order
     * @return the JSON text
     */
    // Package-private for unit tests. See WynntilsConfigOverridesTest.
    static String encode(List<Saved> saved) {
        JsonArray array = new JsonArray();
        for (Saved entry : saved) {
            JsonObject object = new JsonObject();
            object.addProperty("feature", entry.feature());
            object.addProperty("config", entry.config());
            object.addProperty("original", entry.original());
            object.addProperty("userEdited", entry.userEdited());
            object.addProperty("applied", entry.applied());
            array.add(object);
        }
        return array.toString();
    }

    /**
     * Reads a stored snapshot.
     *
     * @param text the stored text; {@code null} or empty for none
     * @return the entries, in order; none for no snapshot, or for one that cannot be read (a
     *     warning is logged and its values are lost)
     */
    // Package-private for unit tests. See WynntilsConfigOverridesTest.
    static List<Saved> decode(String text) {
        if (text == null || text.isEmpty()) return List.of();
        try {
            List<Saved> saved = new ArrayList<>();
            for (JsonElement element : JsonParser.parseString(text).getAsJsonArray()) {
                JsonObject object = element.getAsJsonObject();
                Saved entry =
                        new Saved(
                                object.get("feature").getAsString(),
                                object.get("config").getAsString(),
                                object.get("original").getAsString(),
                                object.get("userEdited").getAsBoolean(),
                                object.get("applied").getAsString());
                saved.add(entry);
            }
            return saved;
        } catch (RuntimeException e) {
            VetsLogger.warn("Retro: could not read the saved Wynntils settings; dropped them", e);
            return List.of();
        }
    }
}
