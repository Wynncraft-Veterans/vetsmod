package org.wynnvets.fetcher.lookup;

import java.util.concurrent.CompletableFuture;

/**
 * A single source in the {@link PlayerLookup} cascade.
 *
 * <p>Implementations must not complete the future exceptionally — translate
 * thrown exceptions into {@link ProviderOutcome#transientMiss()} so the
 * orchestrator can carry on. A provider that legitimately determines the
 * name doesn't exist returns {@link ProviderOutcome#notFound()}; one that
 * was unable to determine anything (429, 5xx, IO, timeout) returns
 * {@link ProviderOutcome#transientMiss()}; one that cannot be asked at all, or
 * cannot speak to whether the name exists, returns
 * {@link ProviderOutcome#abstain()}.</p>
 */
@FunctionalInterface
public interface LookupProvider {

    CompletableFuture<ProviderOutcome> lookup(String name);

    default String id() {
        return getClass().getSimpleName();
    }

    /**
     * Whether this provider's definite miss settles that the name does not exist: the cascade
     * stops there, whatever the earlier providers answered, and asks no later one. True only for
     * Mojang's own hosts, the source of truth for which names exist: a third-party mirror's error
     * or rate limit cannot outweigh Mojang saying no such profile.
     */
    default boolean authoritative() {
        return false;
    }
}
