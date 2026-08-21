package org.wynnvets.fetcher.lookup;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.wynnvets.fetcher.lookup.providers.AshconProvider;
import org.wynnvets.fetcher.lookup.providers.MojangLegacyProvider;
import org.wynnvets.fetcher.lookup.providers.MojangServicesProvider;
import org.wynnvets.fetcher.lookup.providers.PlayerDbProvider;
import org.wynnvets.fetcher.lookup.providers.VetsSnapshotProvider;
import org.wynnvets.fetcher.lookup.providers.WynncraftProvider;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.util.HttpClients;

/**
 * Cascading player-name → UUID (and profile/snapshot) resolver.
 *
 * <p>Tries providers in order, stopping at the first hit:</p>
 * <ol>
 *   <li>Wynncraft v3 — returns UUID + full profile</li>
 *   <li>Vets snapshot via temporary-server ({@code api.wynnvets.org}) —
 *       returns UUID + membership snapshot</li>
 *   <li>PlayerDB</li>
 *   <li>Ashcon</li>
 *   <li>Mojang Services</li>
 *   <li>Mojang legacy ({@code api.mojang.com}) — last resort, gated by
 *       {@link MojangCooldown}</li>
 * </ol>
 *
 * <p>If every provider misses, the result distinguishes
 * {@link FailureReason#PLAYER_NOT_FOUND} (every miss was definite) from
 * {@link FailureReason#ALL_PROVIDERS_TRANSIENT} (at least one was
 * transient — fail open).</p>
 *
 * <p>Single-flight: concurrent {@link #resolve(String)} calls for the
 * same lowercased name share one underlying cascade.</p>
 */
public final class PlayerLookup {

    private static final Duration OVERALL_TIMEOUT = Duration.ofSeconds(15);

    private static final HttpClient HTTP_CLIENT = HttpClients.standard();

    private static final List<LookupProvider> PROVIDERS =
            List.of(
                    new WynncraftProvider(HTTP_CLIENT),
                    new VetsSnapshotProvider(),
                    new PlayerDbProvider(HTTP_CLIENT),
                    new AshconProvider(HTTP_CLIENT),
                    new MojangServicesProvider(HTTP_CLIENT),
                    new MojangLegacyProvider(HTTP_CLIENT));

    private static final ConcurrentHashMap<String, CompletableFuture<LookupResult>> inFlight =
            new ConcurrentHashMap<>();

    private PlayerLookup() {}

    /**
     * Resolves a player name through the provider cascade. Never completes
     * exceptionally — failures are encoded in
     * {@link LookupResult#failureReason()}.
     */
    public static CompletableFuture<LookupResult> resolve(String name) {
        if (name == null || name.isBlank()) {
            return CompletableFuture.completedFuture(
                    LookupResult.exhausted(FailureReason.INPUT_INVALID));
        }
        String trimmed = name.trim();
        String key = trimmed.toLowerCase(Locale.ROOT);
        return inFlight.computeIfAbsent(
                key,
                k -> {
                    CompletableFuture<LookupResult> cf = runCascade(trimmed);
                    cf.whenComplete((r, ex) -> inFlight.remove(k, cf));
                    return cf;
                });
    }

    private static CompletableFuture<LookupResult> runCascade(String name) {
        return runCascadeStep(name, PROVIDERS.iterator(), false)
                .orTimeout(OVERALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(
                        e -> {
                            VetsLogger.debug(
                                    "Cascade timeout/error for '{}': {}", name, e.getMessage());
                            return LookupResult.exhausted(FailureReason.ALL_PROVIDERS_TRANSIENT);
                        });
    }

    private static CompletableFuture<LookupResult> runCascadeStep(
            String name, Iterator<LookupProvider> it, boolean anyTransient) {
        if (!it.hasNext()) {
            FailureReason reason =
                    anyTransient
                            ? FailureReason.ALL_PROVIDERS_TRANSIENT
                            : FailureReason.PLAYER_NOT_FOUND;
            return CompletableFuture.completedFuture(LookupResult.exhausted(reason));
        }
        LookupProvider provider = it.next();
        return provider.lookup(name)
                .exceptionally(
                        e -> {
                            VetsLogger.debug(
                                    "Provider {} threw for '{}': {}",
                                    provider.id(),
                                    name,
                                    e.getMessage());
                            return ProviderOutcome.transientMiss();
                        })
                .thenCompose(
                        outcome -> {
                            if (outcome.isHit()) {
                                VetsLogger.debug(
                                        "Lookup '{}' resolved via {}", name, provider.id());
                                return CompletableFuture.completedFuture(outcome.hit());
                            }
                            boolean nextTransient = anyTransient || !outcome.isDefiniteMiss();
                            return runCascadeStep(name, it, nextTransient);
                        });
    }
}
