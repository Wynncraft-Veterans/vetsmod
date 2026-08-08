package org.wynnvets.datamodels;

/**
 * GSON-deserialisable model for a Mojang username-to-UUID lookup response.
 *
 * <p>The Mojang API returns a JSON object with {@code id} (UUID without dashes)
 * and {@code name} (current display name). Fields are public for direct GSON
 * mapping.</p>
 */
public class UserUUID {
    public String id;
    public String name;
}
