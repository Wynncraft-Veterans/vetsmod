package org.wynnvets.retro.rules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import org.wynnvets.rendering.image.Rect;
import org.wynnvets.retro.manifest.TextureSections.OldClientClear;
import org.wynnvets.retro.manifest.TextureSections.OldClientRow;

/**
 * What an old client holds, by its {@code old_clients} rows, and what {@code old_client_clears}
 * takes out of it. Pure: the caller names the texture ids there are.
 */
public final class OldClientRules {

    private OldClientRules() {}

    /**
     * One texture an old client holds.
     *
     * @param textureId the texture's id today, such as {@code minecraft:block/white_wool}
     * @param file the old client's texture written there, such as
     *     {@code minecraft:blocks/wool_colored_white}; {@code null} for a fully transparent one
     * @param row the index in the client's rows of the row that decided it
     */
    public record Target(String textureId, String file, int row) {}

    /**
     * The textures an old client holds: each of {@code textureIds} its first matching row decides,
     * in id order.
     *
     * @param rows the client's {@code old_clients} rows
     * @param textureIds the texture ids there are today
     * @return the targets; none when no row matches
     */
    public static List<Target> targets(List<OldClientRow> rows, Collection<String> textureIds) {
        List<Target> targets = new ArrayList<>();
        for (String textureId : new TreeSet<>(textureIds)) {
            for (int i = 0; i < rows.size(); i++) {
                OldClientRow row = rows.get(i);
                Matcher matcher = row.textures().matcher(textureId);
                if (!matcher.matches()) continue;
                String file =
                        row.file() == null ? null : RowMatches.substitute(matcher, row.file());
                targets.add(new Target(textureId, file, i));
                break;
            }
        }
        return targets;
    }

    /**
     * The rectangles cleared in the old client's texture written at {@code textureId}.
     *
     * @param clears the {@code old_client_clears} rows
     * @param textureId the texture's id today
     * @return the rectangles of every matching row, in file order; none when no row matches
     */
    public static List<Rect> clears(List<OldClientClear> clears, String textureId) {
        List<Rect> rects = new ArrayList<>();
        for (OldClientClear clear : clears) {
            if (clear.textures().matcher(textureId).matches()) rects.addAll(clear.rects());
        }
        return rects;
    }
}
