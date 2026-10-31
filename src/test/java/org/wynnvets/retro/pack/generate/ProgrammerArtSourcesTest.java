package org.wynnvets.retro.pack.generate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.Pack;
import org.junit.jupiter.api.Test;
import org.wynnvets.retro.manifest.RetroSource;
import org.wynnvets.retro.pack.MemoryPack;

/** Tests for {@link ProgrammerArtSources}, over packs held in memory. */
class ProgrammerArtSourcesTest {

    /** A pack in the client's list, which only ever needs opening here. */
    private static final class ListedPack extends Pack {
        private final PackResources contents;

        ListedPack(PackResources contents) {
            super(null, null, null, null);
            this.contents = contents;
        }

        @Override
        public PackResources open() {
            if (contents == null) throw new IllegalStateException("cannot open");
            return contents;
        }
    }

    private static final byte[] PNG = {1, 2, 3};

    @Test
    void eachSourceAnswersForItsOwnTextures() throws IOException {
        MemoryPack vanilla = new MemoryPack("vanilla").texture("minecraft:block/a", PNG);
        MemoryPack art = new MemoryPack("art").texture("minecraft:block/b", PNG);

        try (ProgrammerArtSources sources =
                ProgrammerArtSources.open(new ListedPack(art), null, vanilla)) {
            assertEquals(
                    List.of(RetroSource.PROGRAMMER_ART, RetroSource.VANILLA), sources.present());
            assertTrue(sources.has(RetroSource.VANILLA, "minecraft:block/a"));
            assertFalse(sources.has(RetroSource.PROGRAMMER_ART, "minecraft:block/a"));
            assertEquals(Set.of("minecraft:block/a", "minecraft:block/b"), sources.textureIds());
            Identifier file = Identifier.parse("minecraft:textures/block/b.png");
            assertArrayEquals(PNG, sources.read(RetroSource.PROGRAMMER_ART, file));
            assertNull(sources.read(RetroSource.VANILLA, file));
        }
    }

    @Test
    void closingClosesEveryPackButVanilla() {
        MemoryPack vanilla = new MemoryPack("vanilla");
        MemoryPack art = new MemoryPack("art");
        MemoryPack tweaks = new MemoryPack("tweaks");

        ProgrammerArtSources.open(new ListedPack(art), tweaks, vanilla).close();

        assertTrue(art.closed());
        assertTrue(tweaks.closed());
        assertFalse(vanilla.closed(), "the vanilla pack is Minecraft's to close");
    }

    @Test
    void theBundledArtAnswersAsASourceOfItsOwn() throws IOException {
        MemoryPack vanilla = new MemoryPack("vanilla");
        MemoryPack tweaks = new MemoryPack("tweaks").texture("minecraft:block/c", PNG);

        try (ProgrammerArtSources sources = ProgrammerArtSources.open(null, tweaks, vanilla)) {
            assertEquals(
                    List.of(RetroSource.VANILLA_TWEAKS, RetroSource.VANILLA), sources.present());
            assertTrue(sources.has(RetroSource.VANILLA_TWEAKS, "minecraft:block/c"));
            assertArrayEquals(
                    PNG,
                    sources.read(
                            RetroSource.VANILLA_TWEAKS,
                            Identifier.parse("minecraft:textures/block/c.png")));
        }
    }

    @Test
    void aMissingOrBrokenProgrammerArtPackLeavesVanillaAlone() throws IOException {
        MemoryPack vanilla = new MemoryPack("vanilla");

        assertEquals(
                List.of(RetroSource.VANILLA),
                ProgrammerArtSources.open(null, null, vanilla).present(),
                "not in the client's list");
        assertEquals(
                List.of(RetroSource.VANILLA),
                ProgrammerArtSources.open(new ListedPack(null), null, vanilla).present(),
                "cannot be opened");
        assertNull(
                ProgrammerArtSources.open(null, null, vanilla)
                        .read(
                                RetroSource.PROGRAMMER_ART,
                                Identifier.parse("minecraft:textures/a.png")),
                "a missing source has nothing");
    }
}
