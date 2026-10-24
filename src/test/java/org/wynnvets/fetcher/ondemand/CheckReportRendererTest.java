package org.wynnvets.fetcher.ondemand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
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
 * guild and the roster evidence, the Discord link line's {@code (hiatus)} annotation over the
 * same evidence, and the "Last seen" fallback line read from a parsed {@code last_seen_observed}.
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

    // ── Discord link line ─────────────────────────────────────────────────

    private static MembershipSnapshot snapshotWith(String discordJson) {
        return Json.GSON.fromJson("{\"discord\": " + discordJson + "}", MembershipSnapshot.class);
    }

    private static MembershipSnapshot hiatusSnapshot() {
        return snapshotWith(
                "{\"linked\": true, \"disc_uuid\": \"42\", \"disc_display\": \"meta\","
                        + " \"in_guild\": true, \"hiatus\": true}");
    }

    private static String discord(MembershipSnapshot snapshot, User user, GuildEvidence evidence) {
        return CheckReportRenderer.discordLinkLine(snapshot, user, evidence).getString();
    }

    private static final String HIATUS_BASE = "Discord link: @meta  (42)  -  registered  (hiatus";

    @Test
    void hiatus_guildlessOnline_noStaleHint() {
        // Online or not, a guildless target gives the roster nothing to contradict the role.
        assertEquals(
                HIATUS_BASE + ")",
                discord(
                        hiatusSnapshot(),
                        Json.GSON.fromJson(
                                "{\"username\": \"Metrafish\", \"online\": true}", User.class),
                        new GuildEvidence(ABSENT, null, false)));
    }

    @Test
    void hiatus_rosterConfirmsReturners_showsNowInReturnersStale() {
        assertEquals(
                HIATUS_BASE + " - now in Returners; role may be stale)",
                discord(
                        hiatusSnapshot(),
                        returnersUser(),
                        new GuildEvidence(in("Chief"), null, false)));
    }

    @Test
    void hiatus_tabListConfirmsReturners_showsStale() {
        assertEquals(
                HIATUS_BASE + " - now in Returners; role may be stale)",
                discord(
                        hiatusSnapshot(),
                        otherGuildUser(),
                        new GuildEvidence(UNKNOWN, null, true)));
    }

    @Test
    void hiatus_otherGuildRosterIn_showsNowInGuildStale() {
        assertEquals(
                HIATUS_BASE + " - now in Other Guild; role may be stale)",
                discord(
                        hiatusSnapshot(),
                        otherGuildUser(),
                        new GuildEvidence(ABSENT, in("Captain"), false)));
    }

    @Test
    void hiatus_rosterUnknown_noHint() {
        assertEquals(
                HIATUS_BASE + ")",
                discord(
                        hiatusSnapshot(),
                        returnersUser(),
                        new GuildEvidence(UNKNOWN, null, false)));
        assertEquals(
                HIATUS_BASE + ")",
                discord(
                        hiatusSnapshot(),
                        otherGuildUser(),
                        new GuildEvidence(ABSENT, UNKNOWN, false)));
    }

    @Test
    void notHiatus_neverShowsHint() {
        MembershipSnapshot member =
                snapshotWith(
                        "{\"linked\": true, \"disc_uuid\": \"42\", \"disc_display\": \"meta\","
                                + " \"in_guild\": true, \"tier\": \"member\"}");
        assertEquals(
                "Discord link: @meta  (42)  -  member",
                discord(member, returnersUser(), new GuildEvidence(in("Chief"), null, true)));
    }

    @Test
    void discordLine_snapshotUnavailableAndNotLinked_unchanged() {
        GuildEvidence none = new GuildEvidence(ABSENT, null, false);
        assertEquals("Discord link: (snapshot unavailable)", discord(null, null, none));
        assertEquals(
                "Discord link: (snapshot unavailable)",
                discord(Json.GSON.fromJson("{}", MembershipSnapshot.class), null, none));
        assertEquals(
                "Discord link: not linked",
                discord(snapshotWith("{\"linked\": false}"), null, none));
    }

    // ── Last-seen fallback line ───────────────────────────────────────────

    private static MembershipSnapshot snapshotSeen(long ts, String server) {
        String serverJson = server == null ? "null" : "\"" + server + "\"";
        return Json.GSON.fromJson(
                "{\"last_seen_observed\": {\"ts\": %d, \"server_at_observation\": %s}}"
                        .formatted(ts, serverJson),
                MembershipSnapshot.class);
    }

    private static String lastSeen(MembershipSnapshot snapshot, String tabList, boolean self) {
        return CheckReportRenderer.lastSeenFallbackLine(snapshot, tabList, self).getString();
    }

    @Test
    void lastSeenFallback_withObservation_isLabelledBestGuessOnly() {
        long fiveMinutesAgo = Instant.now().getEpochSecond() - 300;
        String text = lastSeen(snapshotSeen(fiveMinutesAgo, "NA35"), null, false);
        assertTrue(text.startsWith("Last seen on Wynncraft: "), text);
        assertTrue(text.endsWith(" ago  (best guess)"), text);
        assertFalse(text.contains("NA35"));
        assertFalse(text.contains("server-change"));
    }

    @Test
    void lastSeenFallback_withoutServer_isTheSameLabel() {
        long fiveMinutesAgo = Instant.now().getEpochSecond() - 300;
        assertTrue(
                lastSeen(snapshotSeen(fiveMinutesAgo, null), null, false)
                        .endsWith("  (best guess)"));
    }

    @Test
    void lastSeenFallback_tsZero_isHidden() {
        assertEquals(
                "Last seen on Wynncraft: (hidden)", lastSeen(snapshotSeen(0, "NA35"), null, false));
    }

    @Test
    void lastSeenFallback_noObservation_isHidden() {
        String hidden = "Last seen on Wynncraft: (hidden)";
        assertEquals(hidden, lastSeen(null, null, false));
        assertEquals(
                hidden, lastSeen(Json.GSON.fromJson("{}", MembershipSnapshot.class), null, false));
    }

    @Test
    void lastSeenFallback_selfAndTabList_unchanged() {
        MembershipSnapshot seen = snapshotSeen(Instant.now().getEpochSecond() - 300, "NA35");
        assertEquals("Last seen on Wynncraft: now  (self)", lastSeen(seen, "WC7", true));
        assertEquals("Last seen on Wynncraft: now  (tab-list: WC7)", lastSeen(seen, "WC7", false));
    }
}
