package org.wynnvets.retro.pack.generate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.wynnvets.retro.RetroReport.Count;

/** Tests for {@link PackOutput}: the shape a step's rows share, and writing a texture. */
class PackOutputTest {

    private static final Identifier TO = Identifier.parse("vetsmod:textures/a.png");

    @Test
    void whatARowThrowsIsOneSkippedLineNamingItsCause() {
        PackOutput out = new PackOutput();

        out.attempt(
                "could not read a",
                () -> {
                    throw new IOException("gone");
                });
        out.attempt(
                "could not read b",
                () -> {
                    throw new IllegalStateException("bad");
                });
        out.attempt("not reported", () -> out.count(Count.ALIASES));

        assertEquals(
                List.of(
                        "could not read a (java.io.IOException: gone)",
                        "could not read b (java.lang.IllegalStateException: bad)"),
                out.skipped);
        assertEquals(1, out.counts.get(Count.ALIASES), "the row after them still ran");
    }

    @Test
    void anAttemptThatAnswersFallsBackWhenItThrows() {
        PackOutput out = new PackOutput();

        assertEquals("made", out.attempt("not reported", "fallback", () -> "made"));
        assertEquals(List.of(), out.skipped);
        assertEquals(
                "fallback",
                out.attempt(
                        "failed",
                        "fallback",
                        () -> {
                            throw new IOException("gone");
                        }));
        assertEquals(List.of("failed (java.io.IOException: gone)"), out.skipped);
    }

    @Test
    void anErrorIsNotARowFailure() {
        // Only a thrown Exception is a row's; RetroPackResources handles what is left.
        PackOutput out = new PackOutput();

        assertThrows(
                AssertionError.class,
                () ->
                        out.attempt(
                                "not reported",
                                () -> {
                                    throw new AssertionError();
                                }));
    }

    @Test
    void aRowsIdThatIsNoTextureIdIsReportedOnce() {
        PackOutput out = new PackOutput();

        assertEquals(
                Identifier.parse("minecraft:textures/block/a.png"),
                out.textureFile("minecraft:block/a"));
        assertEquals(List.of(), out.skipped);
        assertNull(out.textureFile("Not An Id"));
        assertEquals(List.of("not a texture id: Not An Id"), out.skipped);
    }

    @Test
    void aCopysFilesNeedBothIdsAndItsLineNamesBoth() {
        PackOutput out = new PackOutput();

        assertEquals(
                new PackOutput.CopyFiles(
                        Identifier.parse("minecraft:textures/a.png"),
                        Identifier.parse("vetsmod:textures/b.png")),
                out.copyFiles("minecraft:a", "vetsmod:b"));
        assertEquals(List.of(), out.skipped);
        assertNull(out.copyFiles("Not An Id", "vetsmod:b"));
        assertNull(out.copyFiles("minecraft:a", "Not An Id"));
        assertEquals(
                List.of(
                        "not a texture id: Not An Id for vetsmod:b",
                        "not a texture id: minecraft:a for Not An Id"),
                out.skipped);
    }

    @Test
    void aTextureIsCountedAndItsMcmetaGoesBesideIt() {
        PackOutput out = new PackOutput();
        byte[] png = {1};
        byte[] mcmeta = {2};

        out.writeTexture(TO, png, mcmeta, Count.ALIASES);

        assertArrayEquals(png, out.files.get(TO));
        assertArrayEquals(mcmeta, out.files.get(Identifier.parse("vetsmod:textures/a.png.mcmeta")));
        assertEquals(1, out.counts.get(Count.ALIASES));
        assertEquals(1, out.counts.get(Count.MCMETA));
    }

    @Test
    void aTextureWithNoMcmetaWritesOneFile() {
        PackOutput out = new PackOutput();

        out.writeTexture(TO, new byte[] {1}, null, Count.ALIASES);

        assertEquals(1, out.files.size());
        assertFalse(out.counts.containsKey(Count.MCMETA));
    }
}
