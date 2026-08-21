package org.wynnvets.mwe.anni.outline;

import com.wynntils.utils.colors.CustomColor;
import java.util.Locale;
import net.minecraft.ChatFormatting;

/**
 * Spec-canonical role colour table for the S4 highlight overlay.
 *
 * <p>Spec §"Player Highlights" pins the colours to vanilla {@link ChatFormatting}
 * codes ({@code §f}/{@code §b}/{@code §a}/{@code §c}/{@code §e}/{@code §d}),
 * NOT raw hex constants. {@link AnniOutlineRegistry#ownPartyEntry} turns the
 * chosen code into an outline colour through
 * {@link CustomColor#fromChatFormatting(ChatFormatting)}, which keeps the
 * outline ARGB in sync with whatever Minecraft renders for the matching colour
 * code elsewhere (chat text, scoreboard, etc.) — if Mojang ever tweaks those
 * values, we follow along automatically.</p>
 *
 * <p>The class declares <b>no fields</b>. Every {@link CustomColor} in the
 * highlight path is constructed by {@link AnniOutlineRegistry}, including the
 * light-grey other-vets-party outline that used to live here. That is
 * deliberate and load-bearing: an empty {@code <clinit>} is what lets a test
 * touch this class at all, since Wynntils is absent at test runtime. The
 * {@code CustomColor} import above resolves a Javadoc link and nothing else —
 * imports are free, static initializers are not.</p>
 *
 * <p><b>The mod's only role table for chat and outline alike.</b> Not for the whole mod:
 * the boss bar keeps a third, described below. Two callers:
 * {@link AnniOutlineRegistry#ownPartyEntry}, for the outline and nametag overlay, and
 * {@link org.wynnvets.mwe.anni.render.AnniHoverBuilder#roleColor AnniHoverBuilder#roleColor},
 * which the chat surfaces call and which now delegates here rather than carrying its own copy
 * of the same seven arms. The boss bar's {@code VetsBossBarContentBuilder.roleColor} is a third
 * role table and is <em>deliberately</em> not this one — {@code TANK} to BLUE, {@code FILL} to
 * DARK_AQUA, spec-cited as "distinct from S4's outline colours". Do not fold it in.</p>
 *
 * <p>{@code ownPartyEntry} takes a single {@link #chatFormattingForRole} result and derives both
 * halves of the {@code Entry} from it — the outline {@link CustomColor} and the nametag
 * {@link ChatFormatting} — and it is that {@code Entry} which {@link AnniOutlineTicker} and
 * {@link org.wynnvets.mixin.client.NametagMixin NametagMixin} read. Deriving both from one call
 * is what stops those two colours drifting. That guarantee is per-tier and does <em>not</em>
 * extend to the other-vets-party tier, whose two halves are written out separately; they now sit
 * in one expression in {@link AnniOutlineRegistry} so the convention is at least visible.</p>
 */
public final class AnniOutlinePalette {

    private AnniOutlinePalette() {}

    /** {@link ChatFormatting} chosen for a given role code, for own-party
     *  members. Exposed so {@link AnniOutlineRegistry#ownPartyEntry} can derive an Entry's outline
     *  colour and its nametag formatting from this one call — which is what stops the two from
     *  drifting. {@link org.wynnvets.mixin.client.NametagMixin NametagMixin} reads the resolved
     *  formatting off the Entry, not from here.
     *
     *  <p>Folded with {@link Locale#ROOT}, not with the default locale.
     *  Under a Turkish or Azeri default, {@code "fill"}, {@code "primary"} and
     *  {@code "tertiary"} upper-case their {@code i} to the dotted capital
     *  U+0130, match no arm below, and render an own-party member as an
     *  outsider while the chat hover — which has always passed
     *  {@link Locale#ROOT} — shows the right colour. The argument is
     *  load-bearing; see {@code AnniOutlinePaletteTest}.</p>
     *
     *  <p>Recognised role codes (case-insensitive):</p>
     *  <ul>
     *    <li>{@code FILL} → {@code §f} white</li>
     *    <li>{@code TANK} → {@code §b} aqua</li>
     *    <li>{@code HEAL} / {@code HEALER} → {@code §a} green</li>
     *    <li>{@code TERTIARY} → {@code §d} light purple</li>
     *    <li>{@code SECONDARY} → {@code §e} yellow</li>
     *    <li>{@code PRIMARY} → {@code §c} red</li>
     *  </ul>
     *  Unknown or null → light grey, the same colour the other-vets-party
     *  tier gets in {@link AnniOutlineRegistry}, so that a party member we
     *  can't role-identify still reads as "vets-anni-party but not
     *  differentiated" rather than as an outsider. */
    public static ChatFormatting chatFormattingForRole(String role) {
        if (role == null) return ChatFormatting.GRAY;
        switch (role.toUpperCase(Locale.ROOT)) {
            case "FILL":
                return ChatFormatting.WHITE;
            case "TANK":
                return ChatFormatting.AQUA;
            case "HEAL":
            case "HEALER":
                return ChatFormatting.GREEN;
            case "TERTIARY":
                return ChatFormatting.LIGHT_PURPLE;
            case "SECONDARY":
                return ChatFormatting.YELLOW;
            case "PRIMARY":
                return ChatFormatting.RED;
            default:
                return ChatFormatting.GRAY;
        }
    }
}
