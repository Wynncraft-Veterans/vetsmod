package org.wynnvets.constants;

import java.net.URI;

public class WVApi {
    /**
     * The endpoint for the outbound stamp.
     */
    public static final URI StampEndpoint = URI.create("http://api.wynnvets.org/v0/outbound/stamp");

    /**
     * Chat endpoint
     */
    public static final URI ChatEndpoint = URI.create("http://api.wynnvets.org/v0/outbound/chat");

    /**
     * Endpoint for information about the returns.
     */
    public static final URI ReturnEndpoint = URI.create("http://api.wynnvets.org/v0/outbound/return");

    /**
     * MOTD information.
     */
    public static final URI MotdEndpoint = URI.create("http://api.wynnvets.org/v0/outbound/motd");

    /**
     * Link to the WV anni website.
     */
    public static final URI AnniEndpoint = URI.create("https://wynnvets.org/anni");
}
