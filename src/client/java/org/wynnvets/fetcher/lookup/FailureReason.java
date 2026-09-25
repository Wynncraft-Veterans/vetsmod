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
 * prints a distinct error for each. Today no cascade ends in
 * {@code PLAYER_NOT_FOUND} (see
 * {@code vets-snapshot-provider-makes-player-not-found-unreachable}).</p>
 */
public enum FailureReason {
    /** Input was blank/empty. */
    INPUT_INVALID,
    /** Every provider returned a definite 404 (or equivalent "no such name").
     *  The player almost certainly doesn't exist. */
    PLAYER_NOT_FOUND,
    /** At least one provider failed transiently (429/5xx/timeout/IO) and no
     *  provider returned a hit. We can't tell whether the player exists. */
    ALL_PROVIDERS_TRANSIENT
}
