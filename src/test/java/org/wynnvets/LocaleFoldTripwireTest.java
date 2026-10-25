package org.wynnvets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Fails the build on a case fold that follows the JVM's default locale. Under a Turkish or Azeri
 * default the no-argument fold turns {@code I} into the dotless {@code ı} and {@code i} into the
 * dotted {@code İ}, which once broke staff recognition, the rank relay and the anni mode.
 * Every fold in the mod passes {@link java.util.Locale#ROOT}; this walks the main and client
 * sources and names each bare {@code toLowerCase()} or {@code toUpperCase()} call.
 *
 * <p>It reads source text, so a bare call in a comment counts too. Tests are not scanned: some
 * demonstrate the default-locale fold on purpose.</p>
 */
class LocaleFoldTripwireTest {

    private static final Pattern BARE_FOLD = Pattern.compile("\\.to(Lower|Upper)Case\\(\\)");

    @Test
    void noSourceFoldsCaseWithTheDefaultLocale() throws IOException {
        List<String> hits = new ArrayList<>();
        for (String root : List.of("src/main/java", "src/client/java")) {
            Path dir = Path.of(root);
            assertTrue(Files.isDirectory(dir), root + " resolves against the project directory");
            List<Path> sources;
            try (Stream<Path> walk = Files.walk(dir)) {
                sources = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
            }
            for (Path file : sources) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (BARE_FOLD.matcher(lines.get(i)).find()) {
                        hits.add(file + ":" + (i + 1));
                    }
                }
            }
        }
        assertEquals(List.of(), hits, "pass Locale.ROOT to every case fold");
    }
}
