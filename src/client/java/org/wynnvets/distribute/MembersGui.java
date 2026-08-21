package org.wynnvets.distribute;

import com.wynntils.core.text.StyledText;
import com.wynntils.utils.wynn.ContainerUtils;
import java.util.List;
import java.util.regex.Pattern;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import org.wynnvets.distribute.distributor.MemberSlotPresser;
import org.wynnvets.distribute.walker.MembersListSearcher;
import org.wynnvets.distribute.walker.MembersListWalker;
import org.wynnvets.util.ContainerScreens;

/**
 * The shape of Wynncraft's {@code "<guild>: Members"} GUI: its title, its two page buttons,
 * which slots hold player heads, and how a page button is clicked.
 *
 * <p>This is a description of the <em>layout</em>, mirroring Wynntils'
 * {@code GuildMemberListContainer}, not a bag of constants that happened to be shared. Every
 * field below is here because it says something about the menu, including
 * {@link #PREVIOUS_PAGE_SLOT} and {@link #PREVIOUS_PAGE_PATTERN}, which only
 * {@link MembersListSearcher} uses — when Wynncraft changes the menu, this is the one file to
 * check against upstream.</p>
 *
 * <h2>The two screen predicates</h2>
 * <p>"Is the Members menu open?" has two answers in this package and they are not
 * interchangeable. This class supplies the title one ({@link #currentByTitle()}); the id one is
 * {@link ContainerScreens#currentWithId(int)}, called directly with each caller's own bound
 * field. Which job takes which:</p>
 *
 * <table border="1">
 *   <caption>Screen predicate by job</caption>
 *   <tr><th>Caller</th><th>Job</th><th>Predicate</th><th>Why</th></tr>
 *   <tr>
 *     <td>{@link MemberSlotPresser}</td>
 *     <td>every press in a batch, and the close-when-done guard</td>
 *     <td><b>title</b></td>
 *     <td>Wynncraft refreshes the Members menu after every send and reassigns the container id
 *         doing it. Matching on id would fail on precisely the path this class exists to
 *         survive — see its class Javadoc.</td>
 *   </tr>
 *   <tr>
 *     <td>{@link MembersListSearcher}, {@link MembersListWalker}</td>
 *     <td>the scan that drives pagination</td>
 *     <td><b>id</b></td>
 *     <td>They bound an id when the menu opened, and a mismatch is their <em>signal</em> that
 *         the menu they bound is gone — the searcher rebinds on it, the walker abandons.
 *         Matching on title would hide the very event they are testing for.</td>
 *   </tr>
 *   <tr>
 *     <td>{@link MembersListSearcher}, {@link MembersListWalker}</td>
 *     <td>the re-arm fast path, and the searcher's mid-search rebind</td>
 *     <td><b>title</b></td>
 *     <td>Both are looking for a menu they have not bound, or have just lost. There is no id
 *         to match against yet; finding one is the point.</td>
 *   </tr>
 * </table>
 *
 * <p>The tick constants each of those classes owns stay where they are. They encode observed
 * server behaviour rather than menu layout, and their values differ between the callers on
 * purpose.</p>
 */
public final class MembersGui {

    /** Mirrors {@code GuildMemberListContainer.TITLE_PATTERN}. */
    public static final Pattern TITLE_PATTERN = Pattern.compile(".+: Members");

    /** Mirrors {@code GuildMemberListContainer.getNextItemSlot()}. */
    public static final int NEXT_PAGE_SLOT = 28;

    /** Mirrors {@code GuildMemberListContainer.NEXT_PAGE_PATTERN}. */
    public static final Pattern NEXT_PAGE_PATTERN = Pattern.compile("§a§lNext Page");

    /** Mirrors {@code GuildMemberListContainer.getPreviousItemSlot()}. */
    public static final int PREVIOUS_PAGE_SLOT = 10;

    /** Mirrors {@code GuildMemberListContainer.PREVIOUS_PAGE_PATTERN}. */
    public static final Pattern PREVIOUS_PAGE_PATTERN = Pattern.compile("§a§lPrevious Page");

    // ContainerBounds(0, 2, 4, 8) on GuildMemberListContainer — the player-head
    // tiles are rows 0–4 × cols 2–8 of the 9-wide grid.
    //
    // Private on purpose. They exist only to express isTileSlot, and three
    // separately-drifting expressions of that one predicate is the defect this
    // class was extracted to fix. A caller that wants the area wants the
    // predicate.
    private static final int BOUNDS_START_ROW = 0;
    private static final int BOUNDS_END_ROW = 4;
    private static final int BOUNDS_START_COL = 2;
    private static final int BOUNDS_END_COL = 8;

    /**
     * Whether {@code slot} is one of the menu's player-head tiles — rows 0–4 × cols 2–8 of the
     * 9-wide grid.
     *
     * <p>This is both the area the searcher scans for a name and the area the walker collects
     * from, and the two page buttons are deliberately outside it: {@link #PREVIOUS_PAGE_SLOT}
     * (10) is in column 1 and {@link #NEXT_PAGE_SLOT} (28) is in column 1 as well, so a caller
     * that wants to react to either has to name it separately.</p>
     *
     * <p>Only the row/column arithmetic is checked, not the menu's size. A slot past the end
     * of the container that happens to land in-bounds modulo 9 returns {@code true}; the two
     * scan loops never produce one, because they iterate a real item list, but
     * {@code MembersListSearcher.onSetSlot} passes an event's slot straight through and this
     * method does not police it. Negative slots fall out through the column check, because
     * Java's {@code /} and {@code %} truncate toward zero. Neither is a contract — both are
     * what the arithmetic does, and what the three copies this replaced did.</p>
     */
    public static boolean isTileSlot(int slot) {
        int row = slot / 9;
        int col = slot % 9;
        return row >= BOUNDS_START_ROW
                && row <= BOUNDS_END_ROW
                && col >= BOUNDS_START_COL
                && col <= BOUNDS_END_COL;
    }

    /**
     * The open Members screen by {@link #TITLE_PATTERN}, regardless of container id, or
     * {@code null} if the screen on top is something else.
     *
     * <p>See the table on this class for when this is the right predicate and when
     * {@link ContainerScreens#currentWithId(int)} is.</p>
     */
    public static AbstractContainerScreen<?> currentByTitle() {
        return ContainerScreens.currentWithTitle(TITLE_PATTERN);
    }

    /**
     * Left-clicks {@code slot} in {@code containerId} iff it currently holds an item whose
     * hover name matches {@code pattern}; returns whether the click was issued.
     *
     * <p>Returns {@code false} without clicking when the slot is past the end of {@code items}
     * (a shorter menu than expected) or when it holds something other than the page button —
     * which is how both callers detect that they have reached the last page. The two cases are
     * not distinguished, because neither caller does anything different with them.</p>
     *
     * @param items the live item list from {@code screen.getMenu().getItems()}, passed on to
     *     {@link ContainerUtils#clickOnSlot} as the click's item context
     * @param slot {@link #NEXT_PAGE_SLOT} or {@link #PREVIOUS_PAGE_SLOT}
     * @param pattern the matching {@link #NEXT_PAGE_PATTERN} or {@link #PREVIOUS_PAGE_PATTERN}
     * @param containerId the caller's bound Members container id
     */
    public static boolean clickPaginationIfPresent(
            List<ItemStack> items, int slot, Pattern pattern, int containerId) {
        if (slot >= items.size()) return false;
        StyledText name = StyledText.fromComponent(items.get(slot).getHoverName());
        if (!name.matches(pattern)) return false;
        ContainerUtils.clickOnSlot(slot, containerId, GLFW.GLFW_MOUSE_BUTTON_LEFT, items);
        return true;
    }

    private MembersGui() {}
}
