package org.wynnvets.util;

import com.wynntils.core.text.StyledText;
import com.wynntils.utils.mc.McUtils;
import java.util.regex.Pattern;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * The two ways this mod asks <em>"which container screen is open right now?"</em>.
 *
 * <p>Seven sites pattern-matched {@code McUtils.mc().screen} against
 * {@link AbstractContainerScreen} for themselves, each following it with one of exactly two
 * discriminators. The idiom lives here once so a reader comparing two call sites is comparing
 * the discriminators rather than re-deriving that both start the same way. The two bodies
 * below are the only remaining occurrences in {@code src/}, which is what makes that grep-able.</p>
 *
 * <h2>The two predicates are not interchangeable</h2>
 * <p>They answer different questions and a caller that swaps one for the other changes
 * behaviour on the path it exists to survive &mdash; which is why they are separate methods
 * with separate names rather than one method with a flag:</p>
 *
 * <ul>
 *   <li>{@link #currentWithId(int)} asks <b>"is the menu I bound still the one on screen?"</b>
 *       An id mismatch is a <em>signal</em>: the menu was replaced, and a caller driving a
 *       multi-step interaction against a stale id would have its packets dropped silently.</li>
 *   <li>{@link #currentWithTitle(Pattern)} asks <b>"is a screen of this kind on screen?"</b>
 *       Wynncraft reassigns the container id when it refreshes a menu, so a caller that must
 *       survive a refresh &mdash; or that is rebinding <em>because</em> of one &mdash; cannot
 *       discriminate on id at all.</li>
 * </ul>
 *
 * <p>Neither method checks what the other checks. {@code currentWithId} does not look at the
 * title, so the bound id is the whole predicate; {@code currentWithTitle} does not look at the
 * id, which is exactly what makes it refresh-proof.</p>
 *
 * <p>Both return {@code null} rather than an {@code Optional}: every call site is an
 * abort-or-proceed guard on the next statement, and the callers pre-date any {@code Optional}
 * convention in this package.</p>
 */
public final class ContainerScreens {

    /**
     * The open container screen iff its menu carries {@code containerId}, otherwise
     * {@code null}. No title check &mdash; the caller's bound id is the whole predicate.
     *
     * <p>The unbound sentinel {@code -1} that callers hold while nothing is bound needs no
     * special case: no live menu carries it, so the comparison simply fails and this returns
     * {@code null}.</p>
     */
    public static AbstractContainerScreen<?> currentWithId(int containerId) {
        if (McUtils.mc().screen instanceof AbstractContainerScreen<?> screen
                && screen.getMenu().containerId == containerId) {
            return screen;
        }
        return null;
    }

    /**
     * The open container screen iff its title matches {@code titlePattern},
     * <em>regardless</em> of container id &mdash; which is what survives Wynncraft refreshing a
     * menu under a new id. Returns {@code null} when no container screen is open or its title
     * does not match.
     *
     * <p>Matching is against {@link StyledText#matches(Pattern)}, so the pattern is tested
     * against the §-formatted string. Patterns that mean to ignore formatting must say so
     * themselves.</p>
     */
    public static AbstractContainerScreen<?> currentWithTitle(Pattern titlePattern) {
        if (McUtils.mc().screen instanceof AbstractContainerScreen<?> screen
                && StyledText.fromComponent(screen.getTitle()).matches(titlePattern)) {
            return screen;
        }
        return null;
    }

    private ContainerScreens() {}
}
