package org.wynnvets.commands;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import org.junit.jupiter.api.Test;

/**
 * {@link CommandCompletionSync#isStale} finds a tab-completion copy that no longer matches the
 * client tree's gates, whichever way a gate moved and however deep its node sits, and passes a
 * copy that matches. Each copy is built by hand, as Fabric would have built it while the gates
 * stood some other way. The source is a plain {@code Object}, which no gate here reads.
 */
class CommandCompletionSyncTest {

    private static final Object SOURCE = new Object();

    /** The client tree {@code wv → open, gated}, where only {@code gated} has a gate. */
    private static LiteralCommandNode<Object> tree(boolean gatePasses) {
        return LiteralArgumentBuilder.<Object>literal("wv")
                .then(LiteralArgumentBuilder.literal("open"))
                .then(LiteralArgumentBuilder.literal("gated").requires(s -> gatePasses))
                .build();
    }

    /** A copy: {@code wv} with a childless literal for each of {@code children}. */
    private static LiteralCommandNode<Object> copy(String... children) {
        LiteralArgumentBuilder<Object> wv = LiteralArgumentBuilder.literal("wv");
        for (String child : children) {
            wv.then(LiteralArgumentBuilder.literal(child));
        }
        return wv.build();
    }

    @Test
    void aCopyThatMatchesTheGatesIsNotStale() {
        assertFalse(CommandCompletionSync.isStale(tree(true), copy("open", "gated"), SOURCE));
        assertFalse(CommandCompletionSync.isStale(tree(false), copy("open"), SOURCE));
    }

    @Test
    void aGateThatOpenedSinceTheCopyMakesItStale() {
        assertTrue(CommandCompletionSync.isStale(tree(true), copy("open"), SOURCE));
    }

    @Test
    void aGateThatClosedSinceTheCopyMakesItStale() {
        assertTrue(CommandCompletionSync.isStale(tree(false), copy("open", "gated"), SOURCE));
    }

    @Test
    void theWalkReachesNestedGatesButNotBelowANodeTheCopyLacks() {
        LiteralCommandNode<Object> nested =
                LiteralArgumentBuilder.<Object>literal("wv")
                        .then(
                                LiteralArgumentBuilder.literal("outer")
                                        .then(LiteralArgumentBuilder.literal("inner")))
                        .then(
                                LiteralArgumentBuilder.literal("closed")
                                        .requires(s -> false)
                                        .then(LiteralArgumentBuilder.literal("below")))
                        .build();
        LiteralCommandNode<Object> copyWithoutInner =
                LiteralArgumentBuilder.<Object>literal("wv")
                        .then(LiteralArgumentBuilder.literal("outer"))
                        .build();
        LiteralCommandNode<Object> copyWithInner =
                LiteralArgumentBuilder.<Object>literal("wv")
                        .then(
                                LiteralArgumentBuilder.literal("outer")
                                        .then(LiteralArgumentBuilder.literal("inner")))
                        .build();

        assertTrue(CommandCompletionSync.isStale(nested, copyWithoutInner, SOURCE));
        assertFalse(CommandCompletionSync.isStale(nested, copyWithInner, SOURCE));
    }
}
