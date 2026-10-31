package org.wynnvets.retro.render;

import com.wynntils.core.components.Models;
import com.wynntils.mc.event.ContainerLabelRenderEvent;
import com.wynntils.models.containers.Container;
import com.wynntils.models.containers.type.BoundedContainerProperty;
import com.wynntils.models.containers.type.FullscreenContainerProperty;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.retro.LivePack;
import org.wynnvets.retro.RetroModeManager;
import org.wynnvets.retro.manifest.RetroManifest;
import org.wynnvets.retro.manifest.RetroPart;
import org.wynnvets.retro.manifest.ScreenSections.ContainerTitle;
import org.wynnvets.retro.pack.PackFiles;

/**
 * The titles retro draws on Wynncraft's containers ({@code container_titles} in
 * {@code retro_textures.yml}), which screens it drew one on, and the container texture under a
 * screen that keeps Wynncraft's.
 *
 * <p>Wynncraft writes most container titles as glyphs in its own fonts, and those glyphs also
 * draw the screen's ornate background over the container texture, which the retro pack has made
 * vanilla's. Drawing a plain title in the label's place therefore leaves the vanilla container.
 * Each screen is decided once, from its title, by the first of these that applies:</p>
 * <ol>
 *   <li>the first {@code container_titles} row matching the simple name of the class Wynntils'
 *       container model gives the screen: its title, or {@code keep} for Wynncraft's label;</li>
 *   <li>otherwise the readable part of the title ({@link #readableTitle});</li>
 *   <li>otherwise the {@code glyph_titles} row of a glyph in the title ({@link #glyphTitle});</li>
 *   <li>otherwise, with nothing to go on, Wynncraft's label.</li>
 * </ol>
 *
 * <p>The decision is made at whichever comes first of the screen's background
 * ({@link #background}, from
 * {@link org.wynnvets.mixin.client.retro.RetroContainerBackgroundMixin
 * RetroContainerBackgroundMixin}, as {@code renderBg} draws the container texture) and its label
 * ({@link #relabel}, from
 * {@link org.wynnvets.listeners.RetroContainerTitleListener RetroContainerTitleListener}, as
 * {@code renderLabels} draws it, after the background). Only the drawn label changes, in vanilla's
 * label grey (Wynntils passes vanilla's colour on, and nothing here sets one); the screen's title,
 * which the rest of the mod and Wynntils match against, is untouched. A screen that keeps
 * Wynncraft's label is drawn on the pack's copy of Wynncraft's own container texture, where the
 * live pack has one, so Wynncraft's art sits on the texture it was made for; and the player
 * inventory's label, which the pack's {@code lang} restores, is blanked again there
 * ({@link #relabelInventory}), since it would be drawn over that art, and on a Wynntils
 * full-screen view, whose inventory part holds Wynncraft's buttons. A screen whose label retro
 * draws is a vanilla container, so {@link RetroSlots} paints slot fills on it, unless it is a
 * full-screen view, and on no screen that keeps Wynncraft's label. Nothing changes unless the
 * retro pack is live and its {@code screens} part is on, and the background only on Wynncraft
 * ({@link RetroModeManager#isPartLiveOnWynncraft}): Wynntils posts the label events there alone, and
 * a vanilla container elsewhere draws its own label on the texture it asks for. Render thread
 * only.</p>
 *
 * <p>The decisions the fallback makes are logged at debug, once per title, so a screen whose
 * art matters can be given a {@code keep} row.</p>
 */
public final class RetroContainerTitles {

    /** Wynncraft's font of container art, which {@code glyph_titles} names glyphs of. */
    private static final FontDescription INTERFACE_FONT =
            new FontDescription.Resource(Identifier.withDefaultNamespace("interface"));

    /** A player trade's title: two names far apart, which no other screen's has. */
    private static final Pattern TWO_SIDES = Pattern.compile("\\S+ {4,}\\S+");

    /** How many distinct titles the debug log notes before it stops. */
    private static final int LOGGED_TITLES_CAP = 256;

    /** What retro drew on the screen last decided: {@code null} when it kept Wynncraft's label. */
    private static final WeakIdentityMemo<Screen, Relabelled> DECIDED = new WeakIdentityMemo<>();

    /** The titles already logged; render thread only. */
    private static final Set<String> logged = new HashSet<>();

    private static final RetroGuard DECISION_GUARD =
            RetroGuard.retrying("deciding a container's title", "it keeps Wynncraft's");

    private static final RetroGuard BACKGROUND_GUARD =
            RetroGuard.retrying("picking a container's background", "it draws as it is");

    private RetroContainerTitles() {}

    /**
     * A container whose label retro draws, and what was known of it then.
     *
     * @param label the label drawn
     * @param containerClass the simple name of its Wynntils container class, or {@code null}
     *     when Wynntils does not model it
     * @param title the label's text
     * @param bounds the container slots Wynntils says hold its items, or {@code null} when
     *     Wynntils gives no bounds
     * @param fullscreen whether Wynntils draws the screen as one of its full-screen views
     */
    record Relabelled(
            Component label,
            String containerClass,
            String title,
            Set<Integer> bounds,
            boolean fullscreen) {}

    /**
     * Draws retro's title in place of Wynncraft's container label, when the pack is live and
     * the screen gets one.
     *
     * @param event the label about to be drawn
     */
    public static void relabel(ContainerLabelRenderEvent.ContainerLabel event) {
        if (!RetroModeManager.isPartLive(RetroPart.SCREENS)) {
            DECIDED.clear();
            return;
        }
        Relabelled drawn = decisionFor(event.getScreen());
        if (drawn != null) event.setContainerLabel(drawn.label());
    }

    /**
     * The container texture {@code screen} draws as its background: when the pack is live on
     * Wynncraft and the screen keeps Wynncraft's label, the live pack's copy of Wynncraft's own
     * texture ({@code wynncraft_copies}), where it has one; otherwise {@code texture} itself, the
     * vanilla texture the pack copied over Wynncraft's.
     *
     * @param screen the screen drawing its background
     * @param texture the texture file it draws, such as
     *     {@code minecraft:textures/gui/container/generic_54.png}
     * @return the texture file to draw
     */
    public static Identifier background(AbstractContainerScreen<?> screen, Identifier texture) {
        if (!RetroModeManager.isPartLiveOnWynncraft(RetroPart.SCREENS)) return texture;
        return BACKGROUND_GUARD.call(
                () -> background(decisionFor(screen), texture, LivePack::wynncraftCopy), texture);
    }

    /**
     * The container texture a screen draws as its background.
     *
     * @param drawn what retro drew on the screen, or {@code null} when it keeps Wynncraft's label
     * @param texture the texture file the screen draws
     * @param liveCopy the live pack's copy of a Wynncraft texture, by texture id, as
     *     {@link LivePack#wynncraftCopy} answers
     * @return {@code texture} where retro drew the label or there is no copy; the copy's texture
     *     file where the screen keeps Wynncraft's label
     */
    // Package-private for unit tests. See RetroContainerTitlesTest.
    static Identifier background(
            Relabelled drawn, Identifier texture, Function<String, Optional<String>> liveCopy) {
        if (drawn != null) return texture;
        return liveCopy.apply(PackFiles.TEXTURES.fileToId(texture).toString())
                .map(Identifier::parse)
                .map(PackFiles.TEXTURES::idToFile)
                .orElse(texture);
    }

    /** What retro draws on {@code screen}, decided at the first call for it. */
    private static Relabelled decisionFor(AbstractContainerScreen<?> screen) {
        return DECIDED.get(screen, unused -> decide(screen));
    }

    /**
     * Blanks the player inventory's label on a container that keeps Wynncraft's label, and on a
     * Wynntils full-screen view, when the pack is live. The pack's {@code lang} restores vanilla's
     * "Inventory", which belongs on the vanilla containers retro relabels; on a screen that keeps
     * Wynncraft's label it would be drawn over Wynncraft's art, which is why Wynncraft blanks it,
     * and on a full-screen view retro relabels (the content book) the part below the label holds
     * Wynncraft's buttons, not the player's inventory. Wynntils posts this event right after the
     * container's label, from the same {@code renderLabels}, so the screen's decision is already
     * made.
     *
     * @param event the label about to be drawn
     */
    public static void relabelInventory(ContainerLabelRenderEvent.InventoryLabel event) {
        if (!RetroModeManager.isPartLive(RetroPart.SCREENS)) return;
        event.setInventoryLabel(
                inventoryLabel(relabelled(event.getScreen()), event.getInventoryLabel()));
    }

    /**
     * The player inventory's label on a container.
     *
     * @param drawn what retro drew on the container, or {@code null} when it keeps Wynncraft's
     *     label
     * @param original the label the language files give
     * @return {@code original} where retro drew the container's label on a screen that is not a
     *     Wynntils full-screen view; empty where it kept Wynncraft's, or on such a view
     */
    // Package-private for unit tests. See RetroContainerTitlesTest.
    static Component inventoryLabel(Relabelled drawn, Component original) {
        return drawn != null && !drawn.fullscreen() ? original : Component.empty();
    }

    /**
     * What retro drew on {@code screen}.
     *
     * @param screen the screen
     * @return its decision, or {@code null} when it keeps Wynncraft's label or has not been
     *     decided yet (neither its background nor its label drawn)
     */
    static Relabelled relabelled(Screen screen) {
        return DECIDED.peek(screen);
    }

    /**
     * What to draw on {@code screen}; {@code null} keeps Wynncraft's label. Its label is its
     * title, which Wynntils' label event carries as it is.
     */
    private static Relabelled decide(AbstractContainerScreen<?> screen) {
        return DECISION_GUARD.call(() -> decided(screen), null);
    }

    private static Relabelled decided(AbstractContainerScreen<?> screen) {
        Component original = screen.getTitle();
        Container container = container(screen);
        String containerClass = container == null ? null : container.getClass().getSimpleName();
        RetroManifest manifest = RetroModeManager.manifest();
        String title =
                title(
                        manifest.screens().containerTitles(),
                        manifest.screens().glyphTitles(),
                        containerClass,
                        original);
        log(containerClass, original, title);
        if (title == null) return null;
        Set<Integer> bounds =
                container instanceof BoundedContainerProperty bounded
                        ? Set.copyOf(bounded.getBounds().getSlots())
                        : null;
        return new Relabelled(
                Component.literal(title),
                containerClass,
                title,
                bounds,
                container instanceof FullscreenContainerProperty);
    }

    /** Wynntils' model of {@code screen}, or {@code null} when it has none for this screen. */
    private static Container container(AbstractContainerScreen<?> screen) {
        Container container = Models.Container.getCurrentContainer();
        if (container == null || container.getContainerId() != screen.getMenu().containerId) {
            return null;
        }
        return container;
    }

    /**
     * The title retro draws on a container.
     *
     * @param rows the {@code container_titles} rows
     * @param glyphRows the {@code glyph_titles} rows
     * @param containerClass the simple name of the container's Wynntils class, or {@code null}
     * @param original the label Wynncraft would draw
     * @return the first matching row's title, else the readable title, else a glyph's row's
     *     title; {@code null} to keep Wynncraft's label (a {@code keep} row, or none of those)
     */
    // Package-private for unit tests. See RetroContainerTitlesTest.
    static String title(
            List<ContainerTitle> rows,
            Map<Integer, String> glyphRows,
            String containerClass,
            Component original) {
        if (containerClass != null) {
            for (ContainerTitle row : rows) {
                if (row.containers().matcher(containerClass).matches()) {
                    return RetroManifest.KEEP.equals(row.title()) ? null : row.title();
                }
            }
        }
        String readable = readableTitle(original);
        return readable != null ? readable : glyphTitle(glyphRows, original);
    }

    /**
     * The title a {@code glyph_titles} row gives a title of glyphs.
     *
     * @param rows the rows, a glyph's code point to its title
     * @param title the title
     * @return the row of the first glyph in it that has one, in the interface font, since other
     *     fonts reuse the same code points; {@code null} when none has
     */
    // Package-private for unit tests. See RetroContainerTitlesTest.
    static String glyphTitle(Map<Integer, String> rows, Component title) {
        return title.visit(
                        (Style style, String run) ->
                                style.getFont().equals(INTERFACE_FONT)
                                        ? run.codePoints()
                                                .filter(rows::containsKey)
                                                .mapToObj(rows::get)
                                                .findFirst()
                                        : Optional.<String>empty(),
                        Style.EMPTY)
                .orElse(null);
    }

    /**
     * The part of a title a player reads as text: its runs in the default font, without
     * formatting codes, private-use glyphs or unassigned characters (Wynncraft's spacing
     * characters), and with its spaces collapsed. Runs in any other font (Wynncraft's
     * {@code interface} and {@code space} fonts) are glyph art and are dropped whole. A player
     * trade's title keeps its spaces, which put each name over its own side
     * ({@link #TWO_SIDES}).
     *
     * @param title the title
     * @return the text, or {@code null} when it has no letter or digit
     */
    // Package-private for unit tests. See RetroContainerTitlesTest.
    static String readableTitle(Component title) {
        StringBuilder text = new StringBuilder();
        title.visit(
                (Style style, String run) -> {
                    if (style.getFont().equals(FontDescription.DEFAULT)) {
                        ChatFormatting.stripFormatting(run)
                                .codePoints()
                                .filter(RetroContainerTitles::readable)
                                .forEach(text::appendCodePoint);
                    }
                    return Optional.empty();
                },
                Style.EMPTY);
        String stripped = text.toString().strip();
        String readable =
                TWO_SIDES.matcher(stripped).matches() ? stripped : stripped.replaceAll("\\s+", " ");
        return readable.codePoints().anyMatch(Character::isLetterOrDigit) ? readable : null;
    }

    /**
     * Whether a code point is one a player reads: assigned, not private use, not a control.
     *
     * <p><b>Deliberately wider than
     * {@link org.wynnvets.chat.PillCodec#isCustomGlyph(int) PillCodec.isCustomGlyph}.</b> On top
     * of {@code PRIVATE_USE} it drops {@code CONTROL}, {@code FORMAT}, {@code SURROGATE} and
     * unassigned code points with no {@code > 0xFFFF} bound. A title is reduced to the text a
     * player reads, and nothing downstream reads a code point, so anything invisible goes. Pinned
     * by {@code RetroContainerTitlesTest.glyphsFormattingCodesAndExtraSpacesAreStrippedFromText},
     * whose unassigned character inside the BMP is the case that discriminates the bound.</p>
     */
    private static boolean readable(int codePoint) {
        int type = Character.getType(codePoint);
        return type != Character.UNASSIGNED
                && type != Character.PRIVATE_USE
                && type != Character.SURROGATE
                && type != Character.CONTROL
                && type != Character.FORMAT;
    }

    /** Notes a decision at debug, once per title. */
    private static void log(String containerClass, Component original, String title) {
        String raw = original.getString();
        if (logged.size() >= LOGGED_TITLES_CAP || !logged.add(containerClass + "\0" + raw)) return;
        VetsLogger.debug(
                "Retro title: {} {} -> {}",
                containerClass == null ? "(no Wynntils class)" : containerClass,
                escaped(raw),
                title == null ? "Wynncraft's label kept" : "'" + title + "'");
    }

    /** {@code text} with each character outside printable ASCII as {@code \}{@code uXXXX}. */
    private static String escaped(String text) {
        StringBuilder out = new StringBuilder("'");
        for (char c : text.toCharArray()) {
            if (c >= 0x20 && c < 0x7F) {
                out.append(c);
            } else {
                out.append(String.format(Locale.ROOT, "\\u%04X", (int) c));
            }
        }
        return out.append('\'').toString();
    }
}
