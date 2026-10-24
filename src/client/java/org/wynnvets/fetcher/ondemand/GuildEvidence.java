package org.wynnvets.fetcher.ondemand;

import org.wynnvets.fetcher.ondemand.ReturnersRosterProbe.ReturnersMembership;
import org.wynnvets.fetcher.ondemand.ReturnersRosterProbe.RosterVerdict;

/**
 * What {@code /wv check} has established about a target's guild beyond Wynncraft's own (cached)
 * player payload: the Returners roster, the roster of the guild the payload names when that is
 * not Returners, and the local tab list. {@link CheckReportRenderer} reads it, so its lines agree
 * with each other about which guild is confirmed.
 *
 * @param returners the Returners roster's answer; a {@code null} one reads as
 *     {@link RosterVerdict#UNKNOWN}
 * @param otherGuild the roster answer for the guild the payload names, or {@code null} when none
 *     was asked: the payload names no guild, or names Returners
 * @param tabListConfirmsReturners the target is in the local player's guild tab bucket and the
 *     local player is in Returners
 */
record GuildEvidence(
        ReturnersMembership returners,
        ReturnersMembership otherGuild,
        boolean tabListConfirmsReturners) {

    GuildEvidence {
        if (returners == null) {
            returners = ReturnersMembership.unknown();
        }
    }

    /** The roster lists the target in Returners, or the tab list vouches for it. */
    boolean confirmsReturners() {
        return returners.verdict() == RosterVerdict.IN_ROSTER || tabListConfirmsReturners;
    }
}
