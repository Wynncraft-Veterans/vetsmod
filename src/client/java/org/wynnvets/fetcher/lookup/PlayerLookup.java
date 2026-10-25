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
 * <p>An {@linkplain LookupProvider#authoritative() authoritative} provider's definite miss (one
 * of Mojang's saying no such profile) ends the cascade at once with
 * {@link FailureReason#PLAYER_NOT_FOUND}, whatever the earlier providers answered: Ashcon
 * answers 429 to every name, and would otherwise make a misspelled name read as an outage.
 * Otherwise, if every provider misses, the result distinguishes
 * {@link FailureReason#PLAYER_NOT_FOUND} (every miss was definite, or an
 * {@linkplain ProviderOutcome#abstain() abstention}, and at least one was
 * definite) from {@link FailureReason#ALL_PROVIDERS_TRANSIENT} (at least one
 * was transient, or every provider abstained, {@link
 * org.wynnvets.commands.InviteGate InviteGate}'s fail-open case).
 * {@link VetsSnapshotProvider} abstains rather than miss transiently when it
 * cannot speak to existence, since the independent providers around it
 * decide that.</p>
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
        return runCascade(PROVIDERS, name);
    }

    // Package-private for unit tests. See PlayerLookupTest.
    static CompletableFuture<LookupResult> runCascade(List<LookupProvider> providers, String name) {
        return runCascadeStep(name, providers.iterator(), false, false)
                .orTimeout(OVERALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(
                        e -> {
                            VetsLogger.debug(
                                    "Cascade timeout/error for '{}': {}", name, e.getMessage());
                            return LookupResult.exhausted(FailureReason.ALL_PROVIDERS_TRANSIENT);
                        });
    }

    private static CompletableFuture<LookupResult> runCascadeStep(
            String name, Iterator<LookupProvider> it, boolean anyTransient, boolean anyDefinite) {
        if (!it.hasNext()) {
            // PLAYER_NOT_FOUND needs evidence: at least one definite miss and no transient one.
            // A cascade where every provider abstained learned nothing. (An authoritative
            // definite miss ended the cascade before it got here.)
            FailureReason reason =
                    !anyTransient && anyDefinite
                            ? FailureReason.PLAYER_NOT_FOUND
                            : FailureReason.ALL_PROVIDERS_TRANSIENT;
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
                            VetsLogger.debug(
                                    "Lookup '{}' missed at {}: {}",
                                    name,
                                    provider.id(),
                                    outcome.isDefiniteMiss()
                                            ? "definite"
                                            : outcome.isAbstain() ? "abstain" : "transient");
                            if (outcome.isDefiniteMiss() && provider.authoritative()) {
                                // Mojang said no such profile: no later provider can know
                                // better, and asking the next Mojang host spends its quota.
                                return CompletableFuture.completedFuture(
                                        LookupResult.exhausted(FailureReason.PLAYER_NOT_FOUND));
                            }
                            boolean nextTransient =
                                    anyTransient
                                            || (!outcome.isDefiniteMiss() && !outcome.isAbstain());
                            boolean nextDefinite = anyDefinite || outcome.isDefiniteMiss();
                            return runCascadeStep(name, it, nextTransient, nextDefinite);
                        });
    }
}
