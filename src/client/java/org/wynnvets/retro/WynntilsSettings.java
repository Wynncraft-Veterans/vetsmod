package org.wynnvets.retro;

/**
 * What {@link WynntilsConfigOverrides} needs of Wynntils and of its own stored snapshot, so that
 * what happens to a setting can run without either. {@link LiveWynntilsSettings} is the one
 * production form. A value is the text {@code /wynntils config set} takes.
 */
interface WynntilsSettings {

    /**
     * Whether Wynntils has loaded its features, and with them its settings file.
     *
     * @return {@code true} once it has
     */
    boolean loaded();

    /**
     * Finds a setting.
     *
     * @param feature the feature's short name
     * @param config the setting's field name
     * @return the setting; {@code null} (logged) when this Wynntils does not have it
     */
    Setting find(String feature, String config);

    /** Saves Wynntils' settings file. A failure is logged. */
    void save();

    /**
     * The stored snapshot.
     *
     * @return its text; {@code null} or empty for none
     */
    String snapshot();

    /**
     * Stores the snapshot.
     *
     * @param text its text
     */
    void writeSnapshot(String text);

    /** One of Wynntils' settings. */
    interface Setting {

        /**
         * Whether Wynntils can read a value for this setting.
         *
         * @param value the value
         * @return {@code true} when it can
         */
        boolean reads(String value);

        /**
         * Whether this setting has a value now.
         *
         * @param value the value
         * @return {@code true} when the setting's value equals what {@code value} reads as
         */
        boolean holds(String value);

        /**
         * This setting's value, as text.
         *
         * @return the text; {@link #holds} says whether it reads back as the same value
         */
        String valueText();

        /**
         * Whether the player has set this themselves, so Wynntils keeps it in its file.
         *
         * @return {@code true} when they have
         */
        boolean userEdited();

        /**
         * Sets this setting.
         *
         * @param value a value {@link #reads} accepts
         */
        void set(String value);

        /** Resets this setting to Wynntils' default, leaving it out of Wynntils' file. */
        void reset();
    }
}
