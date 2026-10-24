package org.wynnvets.commands;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wynntils.utils.mc.McUtils;
import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.wynnvets.api.WynnCraftApi;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.chat.dispatcher.GuildChatDispatcher;
import org.wynnvets.datamodels.MembershipSnapshot;
import org.wynnvets.datamodels.User;
import org.wynnvets.fetcher.lookup.FailureReason;
import org.wynnvets.fetcher.lookup.LookupResult;
import org.wynnvets.fetcher.lookup.PlayerLookup;
import org.wynnvets.fetcher.lookup.providers.VetsSnapshotProvider;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.guild.SessionAuthWarning;
import org.wynnvets.util.HttpClients;
import org.wynnvets.util.Json;

/**
 * Pre-dispatch gate for {@code /gu invite} and {@code /guild invite}.
 *
 * <p>Only fires for Returners members — vets-mod users in other guilds
 * (waitlist, honourary, etc.) get pass-through behaviour because they
 * have no guild-invite permission server-side.</p>
 *
 * <p>For <b>non-staff</b> Returners members the gate blocks with the one
 * {@link #GENERIC_REJECTION} line when the target is dazebot-blocklisted,
 * joined Wynncraft on or after 2020, or has no readable first-join date,
 * and asks them to route the target through the waitlist instead when the
 * guild is so full that the next slot is reserved for the waitlist queue.
 * Without a Wynncraft profile it checks only the blocklist, for staff and
 * non-staff alike: a listed target is refused in red with no bypass button,
 * and anyone else is invited after a yellow warning. A non-staff session gets
 * only an invite verdict from temporary-server
 * ({@link MembershipSnapshot#getMayInvite()}), never the blocklist itself,
 * and every non-staff refusal prints the same line, so the refusal does not
 * say which check failed. temporary-server answers that verdict only to an
 * authenticated session of tier {@code member}, so {@link #entryVerdict} stops
 * anyone else up front: {@link #STILL_CHECKING} while a key, or the member tier after a
 * Returners join, may still be on its way, otherwise {@link #UNAUTHENTICATED}, with a link to
 * the Discord invite.</p>
 *
 * <p>For <b>staff</b> (confirmed-staff via the vetsmod token) the gate
 * surfaces the exact reason and, when the target has a Wynncraft profile,
 * offers a clickable {@code /wv invite-force <name>} bypass. The slot-pressure check is
 * skipped for staff entirely — they're trusted to make the call.</p>
 *
 * <p>Name → UUID resolution runs through {@link PlayerLookup}, which
 * tries Wynncraft / temporary-server / PlayerDB / Ashcon / Mojang
 * Services / Mojang legacy in that order. If no source hits and one
 * fails transiently (or none gives any answer) the gate fails open
 * (dispatches the invite anyway with a warning); if none fails
 * transiently and at least one returns a definite 404 the gate blocks
 * with a "no such player" message.</p>
 */
public final class InviteGate {

    private static final HttpClient HTTP_CLIENT = HttpClients.standard();

    /** Players who joined Wynncraft on or after this date do not qualify
     *  under the "vet" criterion (legacy account requirement). */
    private static final LocalDate VET_JOIN_CUTOFF = LocalDate.of(2020, 1, 1);

    /** Every non-staff refusal, whichever check failed, so it does not reveal the blocklist. */
    // Package-private for unit tests. See InviteGateTest.
    static final String GENERIC_REJECTION =
            "This user is not eligible to join the guild, ask a Steward.";

    /** A non-staff player temporary-server will not answer and who is not about to be (no key
     *  stored, the stored one rejected, or authenticated without the member tier). Printed in red
     *  with {@link SessionAuthWarning#withDiscordLink}'s link after it. */
    // Package-private for unit tests. See InviteGateTest.
    static final String UNAUTHENTICATED =
            "Unable to handle /gu invite: vetsmod is running unauthenticated."
                    + " For more details, use ~vetsmod in #bot-commands on vetscord.";

    /** A non-staff player whose stored key has not been confirmed yet (its ack is pending, or the
     *  last check failed transiently), or an authenticated non-member while a Returners join's
     *  re-auth is in progress; the same line {@code /wv list world} prints. */
    // Package-private for unit tests. See InviteGateTest.
    static final String STILL_CHECKING =
            "We are still checking your permissions, please retry in a moment.";

    /** What {@link #entryVerdict} decides before any lookup. */
    // Package-private for unit tests. See InviteGateTest.
    enum EntryVerdict {
        PROCEED,
        STILL_CHECKING,
        UNAUTHENTICATED
    }

    private InviteGate() {}

    /**
     * Entry point: runs the gate for {@code target}. On allow, dispatches
     * {@code /gu invite <target>} via the local game connection. On
     * block, renders an explanation in chat. On staff warn, renders the
     * reason plus a clickable override.
     */
    public static void checkInvite(String target) {
        if (target == null || target.isBlank()) return;
        boolean isStaff = GuildStateManager.isConfirmedStaff();
        switch (entryVerdict(
                isStaff,
                GuildStateManager.isAuthenticatedThisSession(),
                GuildStateManager.hasStoredAuthKey(),
                GuildStateManager.isStoredKeyRejected(),
                GuildStateManager.isAuthTierMember(),
                GuildStateManager.isReturnersReauthDue())) {
            case STILL_CHECKING -> {
                renderNotice(STILL_CHECKING);
                return;
            }
            case UNAUTHENTICATED -> {
                renderUnauthenticated();
                return;
            }
            case PROCEED -> {}
        }

        PlayerLookup.resolve(target)
                .thenAccept(
                        lookup -> {
                            if (lookup.isSuccess()) {
                                gatherAndDecide(target, lookup, isStaff);
                                return;
                            }
                            if (lookup.failureReason() == FailureReason.PLAYER_NOT_FOUND) {
                                renderError(
                                        "Unable to find a player named '"
                                                + target
                                                + "', check spelling?");
                                return;
                            }
                            // ALL_PROVIDERS_TRANSIENT / INPUT_INVALID — fail open: every UUID
                            // source we tried was either down or rate-limited, so we can't
                            // verify the cutoff but the player almost certainly exists. Let
                            // the server make the final call.
                            failOpenInvite(target);
                        });
    }

    /**
     * Sends {@code /gu invite <target>} with every vetsmod-side check
     * skipped, via {@link GuildChatDispatcher#sendCommandBypassed}, which
     * makes the dispatcher's intercept pass it through so this won't
     * re-trigger {@link #checkInvite}. The entry point for
     * {@code /wv invite-force}, and also this class's own allow and
     * fail-open paths.
     */
    public static void forceDispatch(String target) {
        GuildChatDispatcher.sendCommandBypassed("gu invite " + target);
    }

    // ── Async fan-out + decision ────────────────────────────────────────

    private static void gatherAndDecide(String target, LookupResult lookup, boolean isStaff) {
        UUID uuid = lookup.uuid();
        String canonical = lookup.canonicalName() != null ? lookup.canonicalName() : target;

        // Reuse the cascade's attached Wynncraft profile when the Wynncraft
        // provider was the one that hit; otherwise issue a secondary fetch
        // by UUID. Same pattern for the membership snapshot.
        CompletableFuture<User> wynnFuture =
                lookup.wynnProfile() != null
                        ? CompletableFuture.completedFuture(lookup.wynnProfile())
                        : fetchWynnProfile(uuid);

        CompletableFuture<MembershipSnapshot> snapFuture =
                lookup.vetsSnapshot() != null
                        ? CompletableFuture.completedFuture(lookup.vetsSnapshot())
                        : VetsSnapshotProvider.requestSnapshot(canonical);

        // Staff skip the slot-pressure check entirely, so no roster fetch.
        CompletableFuture<GuildSnapshot> guildFuture =
                isStaff ? CompletableFuture.completedFuture(null) : fetchReturnersSnapshot();

        CompletableFuture.allOf(wynnFuture, snapFuture, guildFuture)
                .thenAccept(
                        _ignore -> {
                            User wynn = wynnFuture.join();
                            MembershipSnapshot snap = snapFuture.join();
                            GuildSnapshot guildSnap = guildFuture.join();
                            McUtils.mc()
                                    .execute(
                                            () ->
                                                    decide(
                                                            canonical, wynn, snap, guildSnap,
                                                            isStaff));
                        });
    }

    private static void decide(
            String canonicalName,
            User wynn,
            MembershipSnapshot snap,
            GuildSnapshot guildSnap,
            boolean isStaff) {

        String displayName =
                (wynn != null && wynn.getUsername() != null) ? wynn.getUsername() : canonicalName;

        // Snapshot null = internal WS infrastructure failure (temporary-server
        // unreachable, socket lost or no reply in time, ack drained on
        // disconnect, etc.). Distinct from the
        // upstream-UUID-source failure that triggered the failOpenInvite path
        // above. Keep the existing safety-block here. A staff snapshot must
        // carry the Discord blob; a member's invite verdict never does.
        if (!snapshotUsable(snap, isStaff)) {
            renderError(
                    "Could not get enough info on '"
                            + displayName
                            + "' to determine if they meet vets' recruitment criteria"
                            + " (API down?). Invite cancelled."
                            + (isStaff ? " Use /wv invite-force to override." : ""));
            return;
        }
        // Wynncraft profile null after secondary fetch — we got a UUID from a
        // non-Wynncraft provider but Wynncraft itself didn't reply. The
        // membership snapshot still gates against the blocklist, checked first;
        // anyone else is invited with a warning, since the vet-cutoff check
        // can't run.
        if (wynn == null) {
            // Blocklist is still authoritative even without a Wynn profile.
            if (snap.blocksInvite()) {
                renderError(
                        isStaff
                                ? "'"
                                        + displayName
                                        + "' does not appear to meet vets' recruitment criteria."
                                        + " Please ask a steward."
                                : GENERIC_REJECTION);
                return;
            }
            ChatUtils.sendLocalMessage(
                    Component.literal(
                                    "Profiles seem to be unreachable, unable to verify '"
                                            + displayName
                                            + "'. Dispatching /gu invite anyway.")
                            .withStyle(ChatFormatting.YELLOW));
            forceDispatch(displayName);
            return;
        }

        boolean blocked = snap.blocksInvite();

        // firstJoin may be hidden by Wynncraft privacy; treat hidden as a
        // soft "we can't verify they're a vet" -> staff get warn, non-staff
        // get blocked. (Non-vets shouldn't be invited blind.)
        String firstJoinText = wynn.getFirstJoinDate();
        LocalDate firstJoin = null;
        if (firstJoinText != null) {
            try {
                firstJoin = LocalDate.parse(firstJoinText);
            } catch (RuntimeException ignored) {
                firstJoin = null;
            }
        }
        boolean tooRecent = firstJoin != null && !firstJoin.isBefore(VET_JOIN_CUTOFF);
        boolean joinHidden = firstJoin == null;

        if (blocked || tooRecent || joinHidden) {
            if (isStaff) {
                renderStaffWarning(displayName, blocked, snap, tooRecent, joinHidden, firstJoin);
            } else {
                // Non-staff get the generic line regardless of which check failed.
                ChatUtils.sendLocalMessage(
                        Component.literal(GENERIC_REJECTION).withStyle(ChatFormatting.RED));
            }
            return;
        }

        // Slot-pressure check (non-staff only).
        if (!isStaff && guildSnap != null) {
            int waitlistCount = snap.getWaitlistCount();
            int openSlots = getMaxGuildMembers(guildSnap.level()) - guildSnap.total();
            if (waitlistCount > 0 && openSlots <= waitlistCount + 1) {
                ChatUtils.sendLocalMessage(
                        Component.literal(
                                        "The guild has more waitlisted users ("
                                                + waitlistCount
                                                + ") than open slots. "
                                                + "Please ask "
                                                + displayName
                                                + " to join the waitlist instead of inviting them directly.")
                                .withStyle(ChatFormatting.YELLOW));
                return;
            }
        }

        // All gates passed -> dispatch the original /gu invite to the server.
        forceDispatch(displayName);
    }

    /**
     * Fail-open path: every UUID provider failed transiently (Mojang
     * rate-limited, Wynncraft 5xx, etc.), so we can't run the gate. Dispatch
     * the invite anyway with a yellow warning so staff know to verify
     * manually once sources recover. Per the cascade design, this should be
     * rare — Wynncraft alone has historically been resilient even when
     * Mojang isn't.
     */
    private static void failOpenInvite(String target) {
        McUtils.mc()
                .execute(
                        () -> {
                            ChatUtils.sendLocalMessage(
                                    Component.literal(
                                                    "The APIs seem to be down, unable to verify '"
                                                            + target
                                                            + "'. Dispatching /gu invite anyway.")
                                            .withStyle(ChatFormatting.YELLOW));
                            forceDispatch(target);
                        });
    }

    // ── Render helpers ──────────────────────────────────────────────────

    /**
     * Whether a Returners player's invite may go to the lookup, and if not, which line they get.
     * Confirmed staff always proceed. Otherwise temporary-server answers only an authenticated
     * session of tier {@code member}: an unauthenticated player with a stored, unrejected key is
     * still being checked ({@link #STILL_CHECKING}), one with no key or a rejected key is
     * {@link EntryVerdict#UNAUTHENTICATED}, and an authenticated player of another tier (waitlist,
     * honourary, other) is still being checked while {@code reauthDue} says a Returners join's
     * re-auth is in progress ({@link GuildStateManager#isReturnersReauthDue()}), else
     * unauthenticated.
     */
    // Package-private for unit tests. See InviteGateTest.
    static EntryVerdict entryVerdict(
            boolean confirmedStaff,
            boolean authenticated,
            boolean hasStoredKey,
            boolean keyRejected,
            boolean memberTier,
            boolean reauthDue) {
        if (confirmedStaff) return EntryVerdict.PROCEED;
        if (!authenticated) {
            return keyStillChecking(hasStoredKey, keyRejected)
                    ? EntryVerdict.STILL_CHECKING
                    : EntryVerdict.UNAUTHENTICATED;
        }
        if (memberTier) return EntryVerdict.PROCEED;
        return reauthDue ? EntryVerdict.STILL_CHECKING : EntryVerdict.UNAUTHENTICATED;
    }

    /** Whether an unauthenticated player's stored key may still be confirmed: one is stored and
     *  no auth ack has rejected it. Otherwise the player needs a key. */
    // Package-private for unit tests. See InviteGateTest.
    static boolean keyStillChecking(boolean hasStoredKey, boolean keyRejected) {
        return hasStoredKey && !keyRejected;
    }

    /** Whether the membership snapshot can drive the gate: present, and for staff carrying the
     *  Discord blob of a full snapshot. A member's invite verdict has none and is enough. */
    // Package-private for unit tests. See InviteGateTest.
    static boolean snapshotUsable(MembershipSnapshot snap, boolean isStaff) {
        return snap != null && (!isStaff || snap.getDiscord() != null);
    }

    private static void renderNotice(String msg) {
        McUtils.mc()
                .execute(
                        () ->
                                ChatUtils.sendLocalMessage(
                                        Component.literal(msg).withStyle(ChatFormatting.YELLOW)));
    }

    private static void renderUnauthenticated() {
        McUtils.mc()
                .execute(
                        () ->
                                ChatUtils.sendLocalMessage(
                                        SessionAuthWarning.withDiscordLink(
                                                UNAUTHENTICATED, ChatFormatting.RED)));
    }

    private static void renderError(String msg) {
        McUtils.mc()
                .execute(
                        () ->
                                ChatUtils.sendLocalMessage(
                                        Component.literal(msg).withStyle(ChatFormatting.RED)));
    }

    private static void renderStaffWarning(
            String canonicalName,
            boolean blocked,
            MembershipSnapshot snap,
            boolean tooRecent,
            boolean joinHidden,
            LocalDate firstJoin) {

        MutableComponent header =
                Component.literal("⚠ ")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD))
                        .append(
                                Component.literal(canonicalName)
                                        .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)));

        if (blocked) {
            String reason = snap.getBlocklistReason();
            if (reason == null || reason.isEmpty()) reason = "(no reason on file)";
            header.append(
                            Component.literal(" is on the dazebot blocklist: ")
                                    .setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)))
                    .append(
                            Component.literal("\"" + reason + "\"")
                                    .setStyle(
                                            Style.EMPTY
                                                    .withColor(ChatFormatting.RED)
                                                    .withItalic(true)));
        } else if (tooRecent) {
            header.append(
                            Component.literal(" joined Wynncraft ")
                                    .setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)))
                    .append(
                            Component.literal(firstJoin.toString())
                                    .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)))
                    .append(
                            Component.literal(" - not a vet (cutoff " + VET_JOIN_CUTOFF + ").")
                                    .setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)));
        } else if (joinHidden) {
            header.append(
                            Component.literal(" has a privacy-hidden Wynncraft profile - ")
                                    .setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)))
                    .append(
                            Component.literal("cannot verify they're a vet.")
                                    .setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)));
        }
        ChatUtils.sendLocalMessage(header);

        Style overrideStyle =
                Style.EMPTY
                        .withColor(ChatFormatting.GREEN)
                        .withClickEvent(
                                new ClickEvent.RunCommand("/wv invite-force " + canonicalName))
                        .withHoverEvent(
                                new HoverEvent.ShowText(
                                        Component.literal(
                                                "Bypass the vetsmod check and dispatch "
                                                        + "/gu invite "
                                                        + canonicalName
                                                        + " directly.")));
        ChatUtils.sendLocalMessage(
                Component.literal("  ")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY))
                        .append(Component.literal("[Invite anyway]").setStyle(overrideStyle)));
    }

    // ── Fetch helpers ───────────────────────────────────────────────────

    private static CompletableFuture<User> fetchWynnProfile(UUID uuid) {
        HttpRequest req =
                HttpRequest.newBuilder()
                        .uri(WynnCraftApi.playerInfo(uuid))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();
        return HTTP_CLIENT
                .sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(
                        resp ->
                                resp.statusCode() == HttpURLConnection.HTTP_OK
                                        ? Json.GSON.fromJson(resp.body(), User.class)
                                        : null)
                .exceptionally(e -> null);
    }

    private static CompletableFuture<GuildSnapshot> fetchReturnersSnapshot() {
        HttpRequest req =
                HttpRequest.newBuilder()
                        .uri(WynnCraftApi.guildInfo("Returners"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();
        return HTTP_CLIENT
                .sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(
                        resp -> {
                            if (resp.statusCode() != HttpURLConnection.HTTP_OK) return null;
                            try {
                                JsonElement payload =
                                        Json.GSON.fromJson(resp.body(), JsonElement.class);
                                if (payload == null || !payload.isJsonObject()) return null;
                                JsonObject obj = payload.getAsJsonObject();
                                JsonElement members = obj.get("members");
                                JsonElement levelEl = obj.get("level");
                                if (members == null || !members.isJsonObject()) return null;
                                if (levelEl == null || !levelEl.isJsonPrimitive()) return null;
                                JsonElement total = members.getAsJsonObject().get("total");
                                if (total == null || !total.isJsonPrimitive()) return null;
                                return new GuildSnapshot(total.getAsInt(), levelEl.getAsInt());
                            } catch (Exception e) {
                                return null;
                            }
                        })
                .exceptionally(e -> null);
    }

    private record GuildSnapshot(int total, int level) {}

    // Wynncraft V3 /guild exposes level but not the slot cap. Table mirrors Wynnpool's
    // getMaxGuildMembers (github.com/AiverAiva/Wynnpool apps/web/src/lib/guildUtils.ts).
    private static int getMaxGuildMembers(int level) {
        if (level == 1) return 4;
        if (level <= 5) return 8;
        if (level <= 14) return 16;
        if (level <= 23) return 26;
        if (level <= 32) return 38;
        if (level <= 41) return 48;
        if (level <= 53) return 60;
        if (level <= 65) return 72;
        if (level <= 74) return 80;
        if (level <= 80) return 86;
        if (level <= 86) return 92;
        if (level <= 92) return 98;
        if (level <= 95) return 102;
        if (level <= 98) return 106;
        if (level <= 100) return 110;
        if (level <= 103) return 114;
        if (level <= 106) return 118;
        if (level <= 109) return 122;
        if (level <= 112) return 126;
        if (level <= 115) return 130;
        if (level <= 118) return 140;
        if (level >= 119) return 150;
        return 0;
    }
}
