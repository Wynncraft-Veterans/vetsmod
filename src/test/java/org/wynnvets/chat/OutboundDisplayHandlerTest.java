package org.wynnvets.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins {@link OutboundDisplayHandler#seenNatively}, the one gate that tests a relayed line's
 * {@code type}: under temporary-server's all-tiers receive map every member gets every chat
 * type, and the only line a viewer already sees in the game's own channel is a {@code guild}
 * line while that viewer is a Returner in a world.
 */
class OutboundDisplayHandlerTest {

    private static final String[] TYPES = {"guild", "queue", "waitlist", "honourary", "bridge"};

    @Test
    void onlyAGuildLineIsSeenNativelyAndOnlyByAReturnerInAWorld() {
        for (String type : TYPES) {
            boolean guild = "guild".equals(type);
            assertEquals(guild, OutboundDisplayHandler.seenNatively(type, true, false), type);
            assertEquals(false, OutboundDisplayHandler.seenNatively(type, true, true), type);
            assertEquals(false, OutboundDisplayHandler.seenNatively(type, false, false), type);
            assertEquals(false, OutboundDisplayHandler.seenNatively(type, false, true), type);
        }
    }

    @Test
    void anUntypedOrUnknownLineIsNeverSeenNatively() {
        assertEquals(false, OutboundDisplayHandler.seenNatively("", true, false));
        assertEquals(false, OutboundDisplayHandler.seenNatively("warning", true, false));
    }
}
