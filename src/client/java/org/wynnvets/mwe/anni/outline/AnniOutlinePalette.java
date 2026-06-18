package org.wynnvets.mwe.anni.outline;

import com.wynntils.utils.colors.CustomColor;
import net.minecraft.ChatFormatting;

/**
 * Spec-canonical role / tier colour table for the S4 highlight overlay.
 *
 * <p>Spec §"Player Highlights" pins the colours to vanilla {@link ChatFormatting}
 * codes ({@code §f}/{@code §b}/{@code §a}/{@code §c}/{@code §e}/{@code §d}),
 * NOT raw hex constants. Sourcing from {@code ChatFormatting.X.getColor()}
 * keeps the outline ARGB in sync with whatever Minecraft renders for the
 * matching colour code elsewhere (chat text, scoreboard, etc.) — if Mojang
 * ever tweaks those values, we follow along automatically.</p>
 *
 * <p>The table covers three classes of player while the S4 activation gate
 * holds:</p>
 * <ul>
 *   <li>{@link #forRole} — local player's own-party members. Returned from
 *       {@code snapshot.board.party.members[].role}; FILL when no specific
 *       role is recorded.</li>
 *   <li>{@link #OTHER_VETS_PARTY} — players in any other vets-anni party
 *       (schema v2 {@code event.all_parties[].members[]}). Spec §"FOR
 *       PLAYERS IN OTHER VETS PARTIES" → light grey ({@code §7}).</li>
 *   <li>{@link #OUTSIDER_NAMETAG} — every nearby player NOT in either of
 *       the above tiers. Spec §"FOR PLAYERS NOT IN VETS" → dark grey
 *       ({@code §8}) nametag + no outline (no glow colour applied).</li>
 * </ul>
 *
 * <p>Single source of truth: both {@code AnniOutlineTicker} (glow colour
 * via {@code EntityExtension.setGlowColor}) and the anni branch of
 * {@code NametagMixin} (recoloured nametag component) read from this
 * table. Drift between outline colour and nametag colour would be a bug.</p>
 */
public final class AnniOutlinePalette {

    /** Outline + nametag colour for players in another vets-anni party
     *  (schema v2 {@code event.all_parties} members that aren't on the
     *  local player's own party). Light grey, {@code §7}. */
    public static final CustomColor OTHER_VETS_PARTY =
            CustomColor.fromChatFormatting(ChatFormatting.GRAY);

    /** Nametag colour for outsiders — every nearby player not on any
     *  vets-anni party. Dark grey, {@code §8}. The outline for this tier
     *  is NONE (no glow override applied); see
     *  {@code EntityTeamColorMixin} for the suppression of any native
     *  Wynncraft relationship outline. */
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

    /** {@link ChatFormatting} chosen for a given role code. Exposed so the
     *  nametag branch in {@code NametagMixin} can build text components
     *  with the same colour family the outline uses. */
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
