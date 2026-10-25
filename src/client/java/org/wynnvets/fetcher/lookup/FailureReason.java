package org.wynnvets.fetcher.lookup;

/**
 * Why a {@link PlayerLookup#resolve(String)} cascade did not produce a hit.
 *
 * <p>Distinguishes "we couldn't find out" from "no such player": Mojang said so, or every
 * provider that answered did. Callers can act differently on these.
 * {@link org.wynnvets.commands.InviteGate InviteGate} fails open on
 * {@code ALL_PROVIDERS_TRANSIENT} (dispatches the invite anyway with a
 * warning) and blocks on {@code PLAYER_NOT_FOUND};
 * {@link org.wynnvets.fetcher.ondemand.UserInfoFetcher UserInfoFetcher}
 * reports a distinct error for each.</p>
 */
public enum FailureReason {
    /** Input was blank/empty. */
    INPUT_INVALID,
    /** An {@linkplain LookupProvider#authoritative() authoritative} provider (one of Mojang's)
     *  returned a definite miss (404 or 204), which ends the cascade whatever the earlier
     *  providers answered; or no provider failed transiently and at least one returned a
     *  definite miss (a 404 or equivalent "no such name"). Providers that abstained said nothing
     *  either way. The player almost certainly doesn't exist. */
    PLAYER_NOT_FOUND,
    /** No authoritative definite miss, and at least one provider failed transiently
     *  (429/5xx/timeout/IO) or every provider abstained, and no provider returned a hit. We
     *  can't tell whether the player exists. */
    ALL_PROVIDERS_TRANSIENT
}
