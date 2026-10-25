package org.wynnvets.datamodels;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.wynnvets.api.VetsApi;

/**
 * GSON-deserialisable model for a WynnCraft player.
 *
 * <p>Fields are populated by GSON from the WynnCraft API response JSON.
 * Provides convenience accessors for guild membership, veteran status,
 * and formatted join dates, used by the {@code /wv check} renderer and
 * the {@code /gu invite} gate.</p>
 */
public class User {
    private String username;
    private String uuid;
    private Guild guild;
    private String firstJoin;
    private String lastJoin;
    private Boolean veteran;
    private Float playtime;
    private Boolean online;
    private String server;

    @Override
    public String toString() {
        return "USER INFO:\n" + username + "\n" + uuid + "\n" + guild;
    }

    public String getUsername() {
        return username;
    }

    public String getUuid() {
        return uuid;
    }

    public Guild getGuild() {
        return guild;
    }

    public String getFirstJoinDate() {
        return firstJoin == null ? null : firstJoin.split("T")[0];
    }

    public String getLastJoinDate() {
        return lastJoin == null ? null : lastJoin.split("T")[0];
    }

    /**
     * The player's first join as an instant, or {@code null} when the field is absent or does not
     * parse. Unlike {@link #getFirstJoinDate()}, which cuts the string at its {@code T} and so
     * keeps only the calendar date at the string's own offset, this keeps the moment, so a caller
     * can show it in the viewer's time zone.
     */
    public Instant getFirstJoinInstant() {
        return parseInstant(firstJoin);
    }

    /** The player's last join as an instant; see {@link #getFirstJoinInstant()}. */
    public Instant getLastJoinInstant() {
        return parseInstant(lastJoin);
    }

    private static Instant parseInstant(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    public boolean isInVets() {
        if (guild == null) {
            return false;
        }

        // Constant first: a guild object without a uuid is not Returners.
        return VetsApi.GUILD_UUID.equals(guild.getUuid());
    }

    /**
     * Wynncraft veteran badge state: {@code true} when the API reports the
     * badge, otherwise {@code null} (field absent or JSON null) or
     * {@code false}. Renderers should treat anything but {@code true} as
     * "not a veteran", not as "hidden".
     */
    public Boolean getVeteran() {
        return veteran;
    }

    public boolean isInGuild() {
        return guild != null;
    }

    /**
     * Cumulative playtime in hours as reported by the Wynncraft API.
     * {@code null} when hidden by player privacy settings.
     */
    public Float getPlaytime() {
        return playtime;
    }

    /**
     * Live online state from Wynncraft's {@code /v3/player/{uuid}} response.
     * {@code null} when the player has API privacy enabled (the field is
     * omitted entirely). Renderers should distinguish null (unknown) from
     * {@code false} (definitely offline).
     */
    public Boolean getOnline() {
        return online;
    }

    /**
     * Current server name (e.g. {@code "NA35"}) when {@link #getOnline()}
     * is {@code true}. {@code null} when offline or hidden by privacy.
     */
    public String getServer() {
        return server;
    }
}
