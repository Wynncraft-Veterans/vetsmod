package org.wynnvets.fetcher.lookup;

/**
 * Why a {@link PlayerLookup#resolve(String)} cascade did not produce a hit.
 *
 * <p>Distinguishes "we couldn't find out" from "every provider definitively
 * said no such player." Callers can act differently on these.
 * {@link org.wynnvets.commands.InviteGate InviteGate} fails open on
 * {@code ALL_PROVIDERS_TRANSIENT} (dispatches the invite anyway with a
 * warning) and blocks on {@code PLAYER_NOT_FOUND};
 * {@link org.wynnvets.fetcher.ondemand.UserInfoFetcher UserInfoFetcher}
 * reports a distinct error for each.</p>
 */
public enum FailureReason {
    /** Input was blank/empty. */
    INPUT_INVALID,
    /** No provider failed transiently and at least one returned a definite 404 (or equivalent
     *  "no such name"); providers that abstained said nothing either way. The player almost
     *  certainly doesn't exist. */
    PLAYER_NOT_FOUND,
    /** At least one provider failed transiently (429/5xx/timeout/IO), or every provider
     *  abstained, and no provider returned a hit. We can't tell whether the player exists. */
    ALL_PROVIDERS_TRANSIENT
}
