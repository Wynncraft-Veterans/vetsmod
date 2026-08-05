package org.wynnvets.mwe.anni.outline;

import com.wynntils.utils.colors.CustomColor;
import net.minecraft.ChatFormatting;

/**
 * Spec-canonical role / tier colour table for the S4 highlight overlay.
 *
 * <p>Spec §"Player Highlights" pins the colours to vanilla {@link ChatFormatting}
 * codes ({@code §f}/{@code §b}/{@code §a}/{@code §c}/{@code §e}/{@code §d}),
 * NOT raw hex constants. Building through
 * {@link CustomColor#fromChatFormatting(ChatFormatting)} keeps the outline
 * ARGB in sync with whatever Minecraft renders for the matching colour
 * code elsewhere (chat text, scoreboard, etc.) — if Mojang ever tweaks
 * those values, we follow along automatically.</p>
 *
 * <p>The tiers this table still serves while the S4 activation gate
 * holds:</p>
 * <ul>
 *   <li>{@link #chatFormattingForRole} — local player's own-party members,
 *       keyed on {@code snapshot.board.party.members[].role}. Unknown or
 *       absent roles fall through to light grey, not to FILL.</li>
 *   <li>{@link #OTHER_VETS_PARTY} — players in any other vets-anni party
 *       (schema v2 {@code event.all_parties[].members[]}). Spec §"FOR
 *       PLAYERS IN OTHER VETS PARTIES" → light grey ({@code §7}).</li>
 * </ul>
 *
 * <p>Single source of truth, one hop removed: nothing outside
 * {@code AnniOutlineRegistry} reads this table. Its {@code ownPartyEntry}
 * takes a single {@link #chatFormattingForRole} result and derives both
 * halves of the {@code Entry} from it — the outline {@link CustomColor}
 * and the nametag {@link ChatFormatting} — and it is that {@code Entry}
 * which {@code AnniOutlineTicker} and {@code NametagMixin} read. Deriving
 * both from one call is what makes the two colours unable to drift.</p>
 */
public final class AnniOutlinePalette {

    /** Outline + nametag colour for players in another vets-anni party
     *  (schema v2 {@code event.all_parties} members that aren't on the
     *  local player's own party). Light grey, {@code §7}. */
    public static final CustomColor OTHER_VETS_PARTY =
            CustomColor.fromChatFormatting(ChatFormatting.GRAY);

    /** Nametag colour for outsiders — every nearby player not on any
     *  vets-anni party. Dark grey, {@code §8}. The outline for this tier
     *  is NONE (no glow override applied). */
    public static final CustomColor OUTSIDER_NAMETAG =
            CustomColor.fromChatFormatting(ChatFormatting.DARK_GRAY);

    private AnniOutlinePalette() {
    }

    /** Per-role outline / nametag colour for own-party members.
     *  Recognised role codes (case-insensitive):
     *  <ul>
     *    <li>{@code FILL} → {@code §f} white</li>
     *    <li>{@code TANK} → {@code §b} aqua</li>
     *    <li>{@code HEAL} / {@code HEALER} → {@code §a} green</li>
     *    <li>{@code TERTIARY} → {@code §d} light purple</li>
     *    <li>{@code SECONDARY} → {@code §e} yellow</li>
     *    <li>{@code PRIMARY} → {@code §c} red</li>
     *  </ul>
     *  Unknown or null → {@link #OTHER_VETS_PARTY} (light grey) so that a
     *  party member we can't role-identify still reads as "vets-anni-party
     *  but not differentiated" rather than as an outsider. */
    public static CustomColor forRole(String role) {
        return CustomColor.fromChatFormatting(chatFormattingForRole(role));
    }

    /** {@link ChatFormatting} chosen for a given role code. Exposed so
     *  {@code AnniOutlineRegistry.ownPartyEntry} can derive an Entry's
     *  outline colour and its nametag formatting from this one call —
     *  which is what stops the two from drifting. {@code NametagMixin}
     *  reads the resolved formatting off the Entry, not from here. */
    public static ChatFormatting chatFormattingForRole(String role) {
        if (role == null) return ChatFormatting.GRAY;
        switch (role.toUpperCase()) {
            case "FILL":      return ChatFormatting.WHITE;
            case "TANK":      return ChatFormatting.AQUA;
            case "HEAL":
            case "HEALER":    return ChatFormatting.GREEN;
            case "TERTIARY":  return ChatFormatting.LIGHT_PURPLE;
            case "SECONDARY": return ChatFormatting.YELLOW;
            case "PRIMARY":   return ChatFormatting.RED;
            default:          return ChatFormatting.GRAY;
        }
    }
}
