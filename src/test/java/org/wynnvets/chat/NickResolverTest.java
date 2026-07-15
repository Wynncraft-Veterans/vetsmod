package org.wynnvets.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import org.junit.jupiter.api.Test;

class NickResolverTest {

    private static final String REAL_NAME = "RealUser";
    private static final String NICK = "Nickname";
    private static final Style AQUA_STYLE = Style.EMPTY.withColor(ChatFormatting.AQUA);
    private static final Style DARK_AQUA_ITALIC = Style.EMPTY
            .withColor(ChatFormatting.DARK_AQUA)
            .withItalic(true);
    private static final Style FALLBACK = Style.EMPTY.withColor(ChatFormatting.WHITE);

    private static HoverEvent realNameHover(String realName) {
        return new HoverEvent.ShowText(Component.literal(NICK + "'s real name is " + realName));
    }

    private static MutableComponent aquaRootWithNickChild(Style nickStyle) {
        MutableComponent root = Component.literal("badge ").setStyle(AQUA_STYLE);
        root.append(Component.literal(NICK).setStyle(nickStyle));
        return root;
    }

    @Test
    void realNameSpanStyleOrFallback_childColorOverridesParent() {
        Style nickStyle = DARK_AQUA_ITALIC.withHoverEvent(realNameHover(REAL_NAME));
        MutableComponent root = aquaRootWithNickChild(nickStyle);

        Style resolved = NickResolver.realNameSpanStyleOrFallback(root, FALLBACK);

        assertNotNull(resolved.getColor(), "color must be preserved");
        assertEquals(ChatFormatting.DARK_AQUA.getColor().intValue(), resolved.getColor().getValue(),
                "child DARK_AQUA must win over parent AQUA");
        assertTrue(resolved.isItalic(), "child italic must be preserved");
        assertNotNull(resolved.getHoverEvent(), "hover event must be preserved");
    }

    @Test
    void realNameSpanStyleOrFallback_fallsBackWhenNoHover() {
        MutableComponent root = aquaRootWithNickChild(DARK_AQUA_ITALIC);

        Style resolved = NickResolver.realNameSpanStyleOrFallback(root, FALLBACK);

        assertEquals(FALLBACK, resolved,
                "no hover-carrying span → return the caller-provided fallback verbatim");
    }

    @Test
    void realUsernameOrFallback_findsHoverUsername() {
        Style nickStyle = DARK_AQUA_ITALIC.withHoverEvent(realNameHover(REAL_NAME));
        MutableComponent root = aquaRootWithNickChild(nickStyle);

        String username = NickResolver.realUsernameOrFallback(root, "fallback-user");

        assertEquals(REAL_NAME, username);
    }

    @Test
    void realUsernameOrFallback_returnsFallbackWhenNoHover() {
        MutableComponent root = aquaRootWithNickChild(DARK_AQUA_ITALIC);

        String username = NickResolver.realUsernameOrFallback(root, "fallback-user");

        assertEquals("fallback-user", username);
    }

    @Test
    void realUsernameFromHover_matchesCaseInsensitively() {
        HoverEvent hover = new HoverEvent.ShowText(
                Component.literal("Nickname's Real Name Is " + REAL_NAME));

        assertEquals(REAL_NAME, NickResolver.realUsernameFromHover(hover));
    }

    @Test
    void realUsernameFromHover_returnsNullForNonMatchingText() {
        HoverEvent hover = new HoverEvent.ShowText(Component.literal("just a hover"));

        assertNull(NickResolver.realUsernameFromHover(hover));
    }

    @Test
    void flattenComponent_childFieldsOverrideParent() {
        MutableComponent root = Component.literal("badge ").setStyle(AQUA_STYLE);
        Style nickStyle = DARK_AQUA_ITALIC.withShadowColor(1);
        root.append(Component.literal(NICK).setStyle(nickStyle));

        List<NickResolver.FlatPart> parts = new ArrayList<>();
        NickResolver.flattenComponent(root, root.getStyle(), parts);

        assertEquals(2, parts.size(), "one FlatPart per non-empty leaf");

        NickResolver.FlatPart badge = parts.get(0);
        assertEquals("badge ", badge.text());
        assertEquals(ChatFormatting.AQUA.getColor().intValue(), badge.style().getColor().getValue());

        NickResolver.FlatPart nick = parts.get(1);
        assertEquals(NICK, nick.text());
        assertEquals(ChatFormatting.DARK_AQUA.getColor().intValue(),
                nick.style().getColor().getValue(),
                "child DARK_AQUA must win over parent AQUA");
        assertTrue(nick.style().isItalic(), "child italic must survive");
        assertNotNull(nick.style().getShadowColor(),
                "child shadowColor must survive (parent has none)");
    }

    @Test
    void flattenComponent_siblingWithoutColorInheritsParent() {
        MutableComponent root = Component.literal("").setStyle(AQUA_STYLE);
        root.append(Component.literal("child-no-color").setStyle(Style.EMPTY.withItalic(true)));

        List<NickResolver.FlatPart> parts = new ArrayList<>();
        NickResolver.flattenComponent(root, root.getStyle(), parts);

        assertEquals(1, parts.size(), "empty root literal contributes no FlatPart");
        NickResolver.FlatPart child = parts.get(0);
        assertEquals(ChatFormatting.AQUA.getColor().intValue(),
                child.style().getColor().getValue(),
                "parent AQUA fills the gap when child has no color");
        assertTrue(child.style().isItalic(), "child italic must survive");
    }
}
