package org.wynnvets.fetcher.lookup;

/**
 * Why a {@link PlayerLookup#resolve(String)} cascade did not produce a hit.
 *
 * <p>Distinguishes "we couldn't find out" from "every provider definitively
 * said no such player." Callers act differently on these: {@code
 * ALL_PROVIDERS_TRANSIENT} fails open (dispatch the invite anyway with a
 * warning), while {@code PLAYER_NOT_FOUND} blocks cleanly.</p>
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
