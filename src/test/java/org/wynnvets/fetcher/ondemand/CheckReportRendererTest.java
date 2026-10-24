package org.wynnvets.fetcher.ondemand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.Test;
import org.wynnvets.api.VetsApi;
import org.wynnvets.datamodels.MembershipSnapshot;
import org.wynnvets.datamodels.User;
import org.wynnvets.fetcher.ondemand.ReturnersRosterProbe.ReturnersMembership;
import org.wynnvets.fetcher.ondemand.ReturnersRosterProbe.RosterVerdict;
import org.wynnvets.util.Json;

/**
 * Tests for the pure line builders behind {@link CheckReportRenderer}'s {@code /wv check} block,
 * read through {@link Component#getString()}: the Guild line's decision table over the payload's
 * guild and the roster evidence.
 *
 * <p>The send wrappers and the tab-list, clock and chat reads in {@code renderCheck} are not
 * covered.</p>
 */
class CheckReportRendererTest {

    private static User user(String guildJson) {
        return Json.GSON.fromJson(
                "{\"username\": \"Metrafish\", \"guild\": " + guildJson + "}", User.class);
    }

    private static User returnersUser() {
        return user(
                "{\"uuid\": \"%s\", \"name\": \"Returners\", \"prefix\": \"VETS\", \"rank\": \"RECRUIT\"}"
                        .formatted(VetsApi.GUILD_UUID));
    }

    private static User otherGuildUser() {
        return user(
                "{\"uuid\": \"11111111-2222-3333-4444-555555555555\", \"name\": \"Other Guild\","
                        + " \"prefix\": \"OTH\", \"rank\": \"RECRUIT\"}");
    }

    private static ReturnersMembership in(String rank) {
        return new ReturnersMembership(RosterVerdict.IN_ROSTER, null, rank);
    }

    private static final ReturnersMembership ABSENT = ReturnersMembership.notInGuild();
    private static final ReturnersMembership UNKNOWN = ReturnersMembership.unknown();

    private static String guild(User user, GuildEvidence evidence) {
        return CheckReportRenderer.guildLine(user, evidence, null).getString();
    }

    // ── Guild line ────────────────────────────────────────────────────────

    @Test
    void returnersIn_showsReturnersAndRosterRank() {
        assertEquals(
                "Guild: Returners  -  Chief",
                guild(returnersUser(), new GuildEvidence(in("Chief"), null, false)));
    }

    @Test
    void returnersIn_ignoresThePayloadRank() {
        // The payload says RECRUIT; the roster lists no readable rank, so none is shown.
        assertEquals(
                "Guild: Returners",
                guild(returnersUser(), new GuildEvidence(in(null), null, false)));
    }

    @Test
    void returnersAbsent_payloadSaysReturners_showsCatchingUp() {
        assertEquals(
                "Guild: guildless  -  Wynncraft profile still catching up",
                guild(returnersUser(), new GuildEvidence(ABSENT, null, false)));
    }

    @Test
    void returnersUnknown_payloadSaysReturners_showsCachedTail_noRank() {
        MutableComponent line =
                CheckReportRenderer.guildLine(
                        returnersUser(), new GuildEvidence(UNKNOWN, null, false), null);
        assertEquals("Guild: Returners  (cached)", line.getString());
        Component tail = line.getSiblings().get(line.getSiblings().size() - 1);
        assertEquals("  (cached)", tail.getString());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.YELLOW), tail.getStyle().getColor());
    }

    @Test
    void tabListOnly_showsReturners() {
        assertEquals(
                "Guild: Returners",
                guild(otherGuildUser(), new GuildEvidence(UNKNOWN, null, true)));
    }

    @Test
    void otherGuildIn_withRank() {
        assertEquals(
                "Guild: Other Guild  [OTH]  -  Captain",
                guild(otherGuildUser(), new GuildEvidence(ABSENT, in("Captain"), false)));
    }

    @Test
    void otherGuildIn_rankUnreadable_showsGuildNoRank() {
        String text = guild(otherGuildUser(), new GuildEvidence(ABSENT, in(null), false));
        assertEquals("Guild: Other Guild  [OTH]", text);
        assertFalse(text.contains("RECRUIT"));
    }

    @Test
    void otherGuildAbsent_showsCatchingUp() {
        assertEquals(
                "Guild: guildless  -  Wynncraft profile still catching up",
                guild(otherGuildUser(), new GuildEvidence(ABSENT, ABSENT, false)));
    }

    @Test
    void otherGuildUnknown_showsCachedTail_noRank() {
        String text = guild(otherGuildUser(), new GuildEvidence(ABSENT, UNKNOWN, false));
        assertEquals("Guild: Other Guild  [OTH]  (cached)", text);
        assertFalse(text.contains("RECRUIT"));
    }

    @Test
    void guildlessPayload_unchanged() {
        User guildless = Json.GSON.fromJson("{\"username\": \"Metrafish\"}", User.class);
        assertEquals("Guild: guildless", guild(guildless, new GuildEvidence(ABSENT, null, false)));
        // An unknown roster adds nothing for a payload that names no guild.
        assertEquals("Guild: guildless", guild(guildless, new GuildEvidence(UNKNOWN, null, false)));
    }

    @Test
    void guildlessPayload_discordTierAnnotationUnchanged() {
        User guildless = Json.GSON.fromJson("{\"username\": \"Metrafish\"}", User.class);
        MembershipSnapshot member =
                Json.GSON.fromJson(
                        "{\"discord\": {\"linked\": true, \"tier\": \"member\"}}",
                        MembershipSnapshot.class);
        assertEquals(
                "Guild: guildless  -  appears in vets roster (cached)",
                CheckReportRenderer.guildLine(
                                guildless, new GuildEvidence(ABSENT, null, false), member)
                        .getString());
    }

    @Test
    void noProfile_showsNoWynncraftProfile() {
        assertEquals(
                "Guild: (no Wynncraft profile)",
                guild(null, new GuildEvidence(UNKNOWN, null, false)));
    }

    @Test
    void evidence_nullReturners_readsAsUnknown() {
        GuildEvidence evidence = new GuildEvidence(null, null, false);
        assertEquals(RosterVerdict.UNKNOWN, evidence.returners().verdict());
        assertFalse(evidence.confirmsReturners());
        assertTrue(new GuildEvidence(null, null, true).confirmsReturners());
    }
}
