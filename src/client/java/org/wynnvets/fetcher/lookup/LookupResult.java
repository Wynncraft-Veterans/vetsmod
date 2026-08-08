package org.wynnvets.fetcher.lookup;

import java.util.UUID;
import org.wynnvets.datamodels.MembershipSnapshot;
import org.wynnvets.datamodels.User;

/**
 * Outcome of a {@link PlayerLookup#resolve(String)} cascade.
 *
 * <p>On success, {@link #uuid} is non-null. Optional attachments
 * ({@link #wynnProfile}, {@link #vetsSnapshot}) carry data the resolving
 * provider already fetched as a side effect, letting the invite gate skip
 * a duplicate round-trip when the provider that won the cascade also
 * provided downstream payload.</p>
 *
 * <p>On failure, {@link #uuid} is null and {@link #failureReason} explains
 * which terminal branch we hit. {@link FailureReason#ALL_PROVIDERS_TRANSIENT}
 * is the fail-open case; {@link FailureReason#PLAYER_NOT_FOUND} is the
 * clean-block case.</p>
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
