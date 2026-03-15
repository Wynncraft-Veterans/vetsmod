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

    // ── Chat endpoints ────────────────────────────────────────────────

    /** POST inbound guild chat messages to the relay server. */
    public static final URI CHAT_INBOUND = URI.create("http://api.wynnvets.org/v0/inbound");

    /** GET outbound guild chat messages from the relay server. */
    public static final URI CHAT_OUTBOUND = URI.create("http://api.wynnvets.org/v0/outbound/chat");

    /** GET bridge chat messages (for guildless + unlocked users). */
    public static final URI BRIDGE_OUTBOUND = URI.create("http://api.wynnvets.org/v0/outbound/bridge");

    // ── Information endpoints ─────────────────────────────────────────

    /** GET the message of the day. */
    public static final URI MOTD = URI.create("http://api.wynnvets.org/v0/outbound/motd");

    /** GET return event information. */
    public static final URI RETURN = URI.create("http://api.wynnvets.org/v0/outbound/return");

    /** GET the annihilation event timestamp. */
    public static final URI STAMP = URI.create("http://api.wynnvets.org/v0/outbound/stamp");

    // ── Staff & supporter endpoints ───────────────────────────────────

    /** GET confirmed staff members and their ranks. */
    public static final URI STAFF = URI.create("http://api.wynnvets.org/v0/outbound/staff");

    /** GET the list of users who receive special supporter styling. */
    public static final URI SUPPORTERS = URI.create("http://api.wynnvets.org/v0/outbound/supporters");

    // ── External links ────────────────────────────────────────────────

    /** Link to the WynnVets annihilation schedule page. */
    public static final URI ANNI = URI.create("https://wynnvets.org/anni");
}
