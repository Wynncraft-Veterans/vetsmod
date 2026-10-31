package org.wynnvets.retro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.Test;
import org.wynnvets.retro.manifest.RetroPart;

/**
 * The {@code /wv retro} tree builds with the shape {@link RetroCommands#buildCommandTree()}'s
 * Javadoc gives it, and what its parts commands work out. The commands themselves are not run: an
 * answer goes through Wynntils' chat and a stored setting through {@code VetsConfig}.
 */
class RetroCommandsTest {

    @Test
    void treeBuildsWithItsDocumentedShape() {
        LiteralCommandNode<FabricClientCommandSource> retro =
                RetroCommands.buildCommandTree().build();

        assertEquals("retro", retro.getName());
        assertNotNull(retro.getCommand(), "a bare retro says its state");
        assertEquals(List.of("on", "off", "parts", "only"), names(retro));
        for (String leaf : List.of("on", "off")) {
            assertNotNull(retro.getChild(leaf).getCommand(), leaf);
            assertEquals(List.of(), names(retro.getChild(leaf)), leaf);
        }
    }

    @Test
    void turningRetroOnEndsWithItsCreditsOnHoverAndAClickToTheReadme() {
        Component line = RetroCommands.toggleLine(true, RetroModeManager.Outcome.RELOADING);
        assertEquals(
                "Retro textures on. Reloading resources… (Hover for credits)", line.getString());

        Style credits = line.getSiblings().getLast().getStyle();
        String hover =
                assertInstanceOf(HoverEvent.ShowText.class, credits.getHoverEvent())
                        .value()
                        .getString();
        for (String site :
                List.of("wynntils.net", "wynncraft.com", "mojang.com", "vanillatweaks.net")) {
            assertTrue(hover.contains(site), site);
        }
        assertTrue(hover.contains("Credits section"));
        assertEquals(
                "https://github.com/Wynncraft-Veterans/vetsmod#credits",
                assertInstanceOf(ClickEvent.OpenUrl.class, credits.getClickEvent())
                        .uri()
                        .toString());
    }

    @Test
    void everyAnswerThatRetroIsOnHasTheCreditsAndOffHasNone() {
        for (RetroModeManager.Outcome on :
                List.of(
                        RetroModeManager.Outcome.UNCHANGED,
                        RetroModeManager.Outcome.NO_WYNNCRAFT_PACK)) {
            assertTrue(
                    RetroCommands.toggleLine(true, on).getString().endsWith(" (Hover for credits)"),
                    on.name());
        }
        for (RetroModeManager.Outcome off :
                List.of(RetroModeManager.Outcome.RELOADING, RetroModeManager.Outcome.UNCHANGED)) {
            assertFalse(
                    RetroCommands.toggleLine(false, off).getString().contains("credits"),
                    off.name());
        }
    }

    @Test
    void partsListsAndTakesAPartOnOrOff() {
        CommandNode<FabricClientCommandSource> parts =
                RetroCommands.buildCommandTree().build().getChild("parts");

        assertNotNull(parts.getCommand(), "a bare parts lists them");
        assertEquals(List.of("part"), names(parts));
        CommandNode<FabricClientCommandSource> part = parts.getChild("part");
        assertNull(part.getCommand(), "a part alone says nothing");
        assertEquals(List.of("on", "off"), names(part));
        assertNotNull(part.getChild("on").getCommand());
        assertNotNull(part.getChild("off").getCommand());
    }

    @Test
    void onlyTakesOnePart() {
        CommandNode<FabricClientCommandSource> only =
                RetroCommands.buildCommandTree().build().getChild("only");

        assertNull(only.getCommand(), "only needs its part");
        assertEquals(List.of("part"), names(only));
        assertNotNull(only.getChild("part").getCommand());
        assertEquals(List.of(), names(only.getChild("part")));
    }

    @Test
    void onePartChangesAndTheOthersKeepTheirState() {
        Set<RetroPart> hudOff = RetroPart.allBut(Set.of(RetroPart.HUD));

        assertEquals(
                Set.of(RetroPart.HUD, RetroPart.MOBS),
                RetroCommands.offAfter(hudOff, RetroPart.MOBS, false));
        assertEquals(Set.of(), RetroCommands.offAfter(hudOff, RetroPart.HUD, true));
        assertEquals(
                Set.of(RetroPart.HUD),
                RetroCommands.offAfter(hudOff, RetroPart.HUD, false),
                "already off");
        assertEquals(
                Set.of(RetroPart.HUD),
                RetroCommands.offAfter(hudOff, RetroPart.MOBS, true),
                "already on");
    }

    @Test
    void anAnswerSaysWhatHappensToTheResources() {
        assertEquals(
                "Retro part hud on. Reloading resources…",
                RetroCommands.partsText(
                        "Retro part hud on", RetroModeManager.Outcome.RELOADING, true));
        assertEquals(
                "Retro part hud on.",
                RetroCommands.partsText(
                        "Retro part hud on", RetroModeManager.Outcome.UNCHANGED, true));
        assertEquals(
                "Retro part hud on. It shows once Wynncraft's resource pack has loaded.",
                RetroCommands.partsText(
                        "Retro part hud on", RetroModeManager.Outcome.NO_WYNNCRAFT_PACK, true));
    }

    @Test
    void anAnswerWithRetroOffSaysNothingShowsUntilItIsOn() {
        assertEquals(
                "Only retro part weapons on. Retro itself is off: /wv retro on shows it.",
                RetroCommands.partsText(
                        "Only retro part weapons on", RetroModeManager.Outcome.UNCHANGED, false));
    }

    private static List<String> names(CommandNode<FabricClientCommandSource> node) {
        return node.getChildren().stream().map(CommandNode::getName).toList();
    }
}
