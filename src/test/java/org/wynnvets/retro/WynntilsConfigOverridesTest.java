package org.wynnvets.retro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.wynnvets.retro.WynntilsConfigOverrides.Saved;
import org.wynnvets.retro.manifest.ManifestFixtures;
import org.wynnvets.retro.manifest.RetroPart;
import org.wynnvets.retro.manifest.WynntilsSetting;
import org.wynnvets.retro.rules.PartFilter;

/**
 * Tests for {@link WynntilsConfigOverrides}: its snapshot as stored in
 * {@code retroWynntilsSnapshot}, and what applying and restoring do to a setting, over a stand-in
 * for {@link WynntilsSettings}. {@link LiveWynntilsSettings} needs Wynntils and is not tested.
 */
class WynntilsConfigOverridesTest {

    private static final List<Saved> SAVED =
            List.of(
                    new Saved("ItemHighlight", "highlightTexture", "wynn", true, "boxTransparent"),
                    new Saved("CustomPlayerListOverlay", "userEnabled", "true", false, "false"));

    private static final WynntilsSetting ROW = new WynntilsSetting("Feature", "setting", "retro");

    /** A setting with a text value; the text {@code unreadable} reads as nothing. */
    private static final class FakeSetting implements WynntilsSettings.Setting {
        String value;
        boolean userEdited;
        boolean roundTrips = true;
        boolean throwsOnSet;

        FakeSetting(String value, boolean userEdited) {
            this.value = value;
            this.userEdited = userEdited;
        }

        @Override
        public boolean reads(String text) {
            return !"unreadable".equals(text);
        }

        @Override
        public boolean holds(String text) {
            return roundTrips && value.equals(text);
        }

        @Override
        public String valueText() {
            return value;
        }

        @Override
        public boolean userEdited() {
            return userEdited;
        }

        @Override
        public void set(String text) {
            if (throwsOnSet) throw new IllegalStateException("cannot set");
            value = text;
            userEdited = true;
        }

        @Override
        public void reset() {
            if (throwsOnSet) throw new IllegalStateException("cannot set");
            value = "default";
            userEdited = false;
        }
    }

    /** Settings by {@code "<feature> <setting>"}, a stored snapshot, and what was done. */
    private static final class FakeSettings implements WynntilsSettings {
        final Map<String, FakeSetting> settings = new HashMap<>();
        final List<String> done = new ArrayList<>();
        boolean loaded = true;
        String snapshot;

        @Override
        public boolean loaded() {
            return loaded;
        }

        @Override
        public Setting find(String feature, String config) {
            return settings.get(feature + " " + config);
        }

        @Override
        public void save() {
            done.add("save " + settings.get("Feature setting").value);
        }

        @Override
        public String snapshot() {
            return snapshot;
        }

        @Override
        public void writeSnapshot(String text) {
            done.add("snapshot " + settings.get("Feature setting").value);
            snapshot = text;
        }

        FakeSettings with(FakeSetting setting) {
            settings.put("Feature setting", setting);
            return this;
        }

        FakeSettings savedAs(Saved entry) {
            snapshot = WynntilsConfigOverrides.encode(List.of(entry));
            return this;
        }
    }

    private static Saved saved(String original, boolean userEdited, String applied) {
        return new Saved("Feature", "setting", original, userEdited, applied);
    }

    @Test
    void applyingSavesThePlayersValueBeforeItChangesAnything() {
        FakeSetting setting = new FakeSetting("players", true);
        FakeSettings settings = new FakeSettings().with(setting);

        WynntilsConfigOverrides.apply(settings, List.of(ROW));

        assertEquals("retro", setting.value);
        assertEquals(List.of(saved("players", true, "retro")), decoded(settings));
        assertEquals(List.of("snapshot players", "save retro"), settings.done);
    }

    @Test
    void aSnapshotThatSurvivedACrashKeepsThePlayersValue() {
        // The game died with retro's value in Wynntils' file: what is there now is not the
        // player's.
        FakeSetting setting = new FakeSetting("retro", true);
        FakeSettings settings =
                new FakeSettings().with(setting).savedAs(saved("players", false, "retro"));

        WynntilsConfigOverrides.apply(settings, List.of(ROW));

        assertEquals(List.of(saved("players", false, "retro")), decoded(settings));
        assertEquals(List.of("snapshot retro"), settings.done, "nothing changed, so nothing saved");
    }

    @Test
    void aRowsNewValueKeepsThePlayersSavedValue() {
        FakeSetting setting = new FakeSetting("older", true);
        FakeSettings settings =
                new FakeSettings().with(setting).savedAs(saved("players", true, "older"));

        WynntilsConfigOverrides.apply(settings, List.of(ROW));

        assertEquals("retro", setting.value);
        assertEquals(List.of(saved("players", true, "retro")), decoded(settings));
    }

    @Test
    void aSettingThisWynntilsLacksKeepsItsEntryWhenApplying() {
        FakeSettings settings = new FakeSettings().with(new FakeSetting("x", false));
        Saved gone = new Saved("Gone", "setting", "players", true, "retro");
        settings.savedAs(gone);

        WynntilsConfigOverrides.apply(
                settings, List.of(new WynntilsSetting("Gone", "setting", "retro")));

        assertEquals(List.of(gone), decoded(settings));
    }

    @Test
    void aValueWynntilsCannotReadIsSkippedAndSavesNothing() {
        FakeSetting setting = new FakeSetting("players", true);
        FakeSettings settings = new FakeSettings().with(setting);

        WynntilsConfigOverrides.apply(
                settings, List.of(new WynntilsSetting("Feature", "setting", "unreadable")));

        assertEquals("players", setting.value);
        assertEquals(List.of(), decoded(settings));
    }

    @Test
    void aValueThatDoesNotReadBackIsNotChanged() {
        // Retro could never put it back, so it leaves the setting alone.
        FakeSetting setting = new FakeSetting("players", true);
        setting.roundTrips = false;
        FakeSettings settings = new FakeSettings().with(setting);

        WynntilsConfigOverrides.apply(settings, List.of(ROW));

        assertEquals("players", setting.value);
        assertEquals(List.of(), decoded(settings));
    }

    @Test
    void aSettingTheRuleFilesNoLongerChangeIsPutBack() {
        FakeSetting setting = new FakeSetting("retro", true);
        FakeSettings settings =
                new FakeSettings().with(setting).savedAs(saved("players", true, "retro"));

        WynntilsConfigOverrides.apply(settings, List.of());

        assertEquals("players", setting.value);
        assertEquals(List.of(), decoded(settings));
        assertEquals(List.of("snapshot players", "save players"), settings.done);
    }

    @Test
    void aSettingTheRuleFilesNoLongerChangeStaysSavedWhenItCannotBePutBack() {
        FakeSetting setting = new FakeSetting("retro", true);
        setting.throwsOnSet = true;
        FakeSettings settings =
                new FakeSettings().with(setting).savedAs(saved("players", true, "retro"));

        WynntilsConfigOverrides.apply(settings, List.of());

        assertEquals(List.of(saved("players", true, "retro")), decoded(settings));
    }

    /** A filter whose {@code hud} part owns the feature {@code Feature}. */
    private static PartFilter partOwningTheFeature(Set<RetroPart> on) {
        return new PartFilter(
                ManifestFixtures.parseTextures(
                                """
                                parts:
                                  hud:
                                    about: 'The HUD'
                                    wynntils: ['Feature']
                                """)
                        .parts(),
                on);
    }

    @Test
    void aRowIsPassedOnlyWhileThePartOwningItsFeatureIsOn() {
        WynntilsSetting unowned = new WynntilsSetting("Other", "setting", "retro");
        List<WynntilsSetting> rows = List.of(ROW, unowned);

        assertEquals(
                rows, WynntilsConfigOverrides.rowsOn(rows, partOwningTheFeature(RetroPart.all())));
        assertEquals(
                List.of(unowned),
                WynntilsConfigOverrides.rowsOn(
                        rows, partOwningTheFeature(RetroPart.allBut(Set.of(RetroPart.HUD)))));
    }

    @Test
    void aSettingWhosePartGoesOffIsPutBackAndComesBackWithIt() {
        FakeSetting setting = new FakeSetting("players", true);
        FakeSettings settings = new FakeSettings().with(setting);
        List<WynntilsSetting> rows = List.of(ROW);
        Set<RetroPart> noHud = RetroPart.allBut(Set.of(RetroPart.HUD));

        WynntilsConfigOverrides.apply(
                settings,
                WynntilsConfigOverrides.rowsOn(rows, partOwningTheFeature(RetroPart.all())));
        assertEquals("retro", setting.value);

        WynntilsConfigOverrides.apply(
                settings, WynntilsConfigOverrides.rowsOn(rows, partOwningTheFeature(noHud)));
        assertEquals("players", setting.value, "put back");
        assertEquals(List.of(), decoded(settings));

        WynntilsConfigOverrides.apply(
                settings,
                WynntilsConfigOverrides.rowsOn(rows, partOwningTheFeature(RetroPart.all())));
        assertEquals("retro", setting.value);
        assertEquals(List.of(saved("players", true, "retro")), decoded(settings));

        WynntilsConfigOverrides.apply(
                settings, WynntilsConfigOverrides.rowsOn(rows, partOwningTheFeature(noHud)));
        WynntilsConfigOverrides.restore(settings);
        assertEquals("players", setting.value, "restoring after it finds nothing to do");
    }

    @Test
    void applyingDoesNothingUntilWynntilsHasItsFeatures() {
        FakeSetting setting = new FakeSetting("players", true);
        FakeSettings settings = new FakeSettings().with(setting);
        settings.loaded = false;

        WynntilsConfigOverrides.apply(settings, List.of(ROW));

        assertEquals("players", setting.value);
        assertNull(settings.snapshot);
    }

    @Test
    void restoringPutsThePlayersValueBackAndClearsTheSnapshot() {
        FakeSetting setting = new FakeSetting("retro", true);
        FakeSettings settings =
                new FakeSettings().with(setting).savedAs(saved("players", true, "retro"));

        WynntilsConfigOverrides.restore(settings);

        assertEquals("players", setting.value);
        assertEquals("", settings.snapshot);
        assertEquals(List.of("save players", "snapshot players"), settings.done);
    }

    @Test
    void restoringResetsASettingThePlayerNeverSet() {
        FakeSetting setting = new FakeSetting("retro", true);
        FakeSettings settings =
                new FakeSettings().with(setting).savedAs(saved("players", false, "retro"));

        WynntilsConfigOverrides.restore(settings);

        assertEquals("default", setting.value);
        assertFalse(setting.userEdited, "so Wynntils again leaves it out of its file");
    }

    @Test
    void aSettingThePlayerChangedSinceIsLeftAndItsEntryDropped() {
        FakeSetting setting = new FakeSetting("changed by hand", true);
        FakeSettings settings =
                new FakeSettings().with(setting).savedAs(saved("players", true, "retro"));

        WynntilsConfigOverrides.restore(settings);

        assertEquals("changed by hand", setting.value);
        assertEquals("", settings.snapshot);
        assertEquals(List.of("snapshot changed by hand"), settings.done, "nothing to save");
    }

    @Test
    void aSettingThatCannotBeFoundKeepsItsEntry() {
        FakeSettings settings = new FakeSettings().with(new FakeSetting("x", false));
        settings.savedAs(new Saved("Gone", "setting", "players", true, "retro"));

        WynntilsConfigOverrides.restore(settings);

        assertEquals(
                List.of(new Saved("Gone", "setting", "players", true, "retro")), decoded(settings));
    }

    @Test
    void aSettingThatCannotBeSetKeepsItsEntry() {
        FakeSetting setting = new FakeSetting("retro", true);
        setting.throwsOnSet = true;
        FakeSettings settings =
                new FakeSettings().with(setting).savedAs(saved("players", true, "retro"));

        WynntilsConfigOverrides.restore(settings);

        assertEquals(List.of(saved("players", true, "retro")), decoded(settings));
    }

    @Test
    void aSavedValueThatCannotBeReadKeepsItsEntry() {
        FakeSetting setting = new FakeSetting("retro", true);
        FakeSettings settings =
                new FakeSettings().with(setting).savedAs(saved("unreadable", true, "retro"));

        WynntilsConfigOverrides.restore(settings);

        assertEquals("retro", setting.value);
        assertEquals(List.of(saved("unreadable", true, "retro")), decoded(settings));
    }

    @Test
    void restoringDoesNothingWithoutASnapshotOrUntilWynntilsHasItsFeatures() {
        FakeSetting setting = new FakeSetting("retro", true);
        FakeSettings none = new FakeSettings().with(setting);

        WynntilsConfigOverrides.restore(none);
        assertTrue(none.done.isEmpty());

        FakeSettings early =
                new FakeSettings().with(setting).savedAs(saved("players", true, "retro"));
        early.loaded = false;
        WynntilsConfigOverrides.restore(early);

        assertEquals("retro", setting.value);
        assertEquals(List.of(saved("players", true, "retro")), decoded(early));
    }

    private static List<Saved> decoded(FakeSettings settings) {
        return WynntilsConfigOverrides.decode(settings.snapshot);
    }

    @Test
    void aSnapshotReadsBackAsWritten() {
        assertEquals(SAVED, WynntilsConfigOverrides.decode(WynntilsConfigOverrides.encode(SAVED)));
        assertEquals("[]", WynntilsConfigOverrides.encode(List.of()));
        assertEquals(List.of(), WynntilsConfigOverrides.decode("[]"));
    }

    @Test
    void noSnapshotReadsAsNone() {
        assertEquals(List.of(), WynntilsConfigOverrides.decode(null));
        assertEquals(List.of(), WynntilsConfigOverrides.decode(""));
    }

    @Test
    void anUnreadableSnapshotReadsAsNone() {
        for (String text : List.of("{", "{}", "[1]", "[{\"feature\": \"ItemHighlight\"}]")) {
            assertEquals(List.of(), WynntilsConfigOverrides.decode(text), text);
        }
    }

    @Test
    void aSettingIsNamedAsWynntilsConfigCommandNamesIt() {
        assertEquals("ItemHighlight highlightTexture", SAVED.get(0).key());
    }
}
