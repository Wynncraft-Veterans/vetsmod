package org.wynnvets.api;

import java.net.URI;

/**
 * VetsMod API endpoint constants and guild identifiers.
 *
 * <p>Centralises all WynnVets-specific API URIs used for guild chat relay,
 * bridge messaging, staff/supporter lookups, MOTD, return events, and
 * annihilation timer data.</p>
 */
public final class VetsApi {

    private VetsApi() {}

    /** UUID of the VETS guild as reported by the WynnCraft API. */
    public static final String GUILD_UUID = "a36bd64c-c053-4727-872d-b0d0729f474a";

    // ── Information endpoints ─────────────────────────────────────────

    /** GET the message of the day. */
    public static final URI MOTD = URI.create("https://api.wynnvets.org/v1/outbound/motd");

    /** GET the guild-specific message of the day (for members/waitlist/honourary). */
    public static final URI GUILD_MOTD = URI.create("https://api.wynnvets.org/v1/outbound/guild_motd");

    /** GET return event information. */
    public static final URI RETURN = URI.create("https://api.wynnvets.org/v1/outbound/return");

    /** GET the annihilation event timestamp. */
    public static final URI STAMP = URI.create("https://api.wynnvets.org/v1/outbound/stamp");

    // ── Staff & supporter endpoints ───────────────────────────────────

    /** GET confirmed staff members and their ranks. */
    public static final URI STAFF = URI.create("https://api.wynnvets.org/v1/outbound/staff");

    /** GET the list of users who receive special supporter styling. */
    public static final URI SUPPORTERS = URI.create("https://api.wynnvets.org/v1/outbound/supporters");

    /** GET the list of currently connected VetsMod users. */
    public static final URI LIST = URI.create("https://api.wynnvets.org/v1/outbound/list");

    /** GET the full guild UUID→current-username mapping (resolved via Minecraft Services API). */
    public static final URI ROSTER = URI.create("https://api.wynnvets.org/v1/outbound/roster");

    // ── External links ────────────────────────────────────────────────

    /** Link to the WynnVets annihilation schedule page. */
    public static final URI ANNI = URI.create("https://wynnvets.org/anni");
}
