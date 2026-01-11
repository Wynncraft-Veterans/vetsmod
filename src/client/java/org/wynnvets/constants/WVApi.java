package org.wynnvets.constants;

import java.net.URI;

public class WVApi {
    /**
     * The endpoint for the outbound stamp.
     */
    public static final URI Stamp = URI.create("http://api.wynnvets.org/v0/outbound/stamp");

    /**
     * Chat endpoint
     */
    public static final URI Chat = URI.create("http://api.wynnvets.org/v0/outbound/chat");

    /**
     * Endpoint for information about the returns.
     */
    public static final URI Return = URI.create("http://api.wynnvets.org/v0/outbound/return");

    /**
     * MOTD information.
     */
    public static final URI Motd = URI.create("http://api.wynnvets.org/v0/outbound/motd");

    /**
     * Link to the WV anni website.
     */
    public static final URI Anni = URI.create("https://wynnvets.org/anni");
}
