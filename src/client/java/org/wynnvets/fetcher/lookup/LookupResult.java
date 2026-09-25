package org.wynnvets.fetcher.lookup;

import java.util.UUID;
import org.wynnvets.datamodels.MembershipSnapshot;
import org.wynnvets.datamodels.User;

/**
 * Outcome of a {@link PlayerLookup#resolve(String)} cascade.
 *
 * <p>On success, {@link #uuid} is non-null. Optional attachments
 * ({@link #wynnProfile}, {@link #vetsSnapshot}) carry data the resolving
 * provider already fetched as a side effect, letting a caller that needs
 * the profile or snapshot skip a duplicate round-trip when the provider
 * that won the cascade already fetched it.</p>
 *
 * <p>On failure, {@link #uuid} is null and {@link #failureReason} explains
 * which terminal branch we hit.
 * {@link org.wynnvets.commands.InviteGate InviteGate} treats
 * {@link FailureReason#ALL_PROVIDERS_TRANSIENT} as its fail-open case and
 * {@link FailureReason#PLAYER_NOT_FOUND} as its clean-block case;
 * {@link org.wynnvets.fetcher.ondemand.UserInfoFetcher UserInfoFetcher}
 * prints a distinct error for each. Today no cascade ends in
 * {@code PLAYER_NOT_FOUND} (see
 * {@code vets-snapshot-provider-makes-player-not-found-unreachable}).</p>
 */
public record LookupResult(
        String source,
        String canonicalName,
        UUID uuid,
        User wynnProfile,
        MembershipSnapshot vetsSnapshot,
        FailureReason failureReason) {
    public boolean isSuccess() {
        return uuid != null;
    }

    public static LookupResult exhausted(FailureReason reason) {
        return new LookupResult(null, null, null, null, null, reason);
    }

    public static LookupResult ofWynncraft(String canonicalName, UUID uuid, User profile) {
        return new LookupResult("wynncraft", canonicalName, uuid, profile, null, null);
    }

    public static LookupResult ofVets(String canonicalName, UUID uuid, MembershipSnapshot snap) {
        return new LookupResult("vets", canonicalName, uuid, null, snap, null);
    }

    public static LookupResult ofGeneric(String source, String canonicalName, UUID uuid) {
        return new LookupResult(source, canonicalName, uuid, null, null, null);
    }
}
