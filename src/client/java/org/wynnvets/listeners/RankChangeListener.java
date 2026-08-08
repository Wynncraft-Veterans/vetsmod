package org.wynnvets.listeners;

import com.wynntils.core.WynntilsMod;
import com.wynntils.core.text.StyledText;
import com.wynntils.handlers.chat.event.ChatMessageEvent;
import com.wynntils.handlers.chat.type.RecipientType;
import com.wynntils.utils.mc.StyledTextUtils;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.neoforged.bus.api.SubscribeEvent;
import org.wynnvets.api.V1ApiManager;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.logging.VetsLogger;

/**
 * Detects in-game guild rank-change broadcasts ({@code "X has set Y guild rank
 * from A to B"}) and forwards them to the v1 inbound WebSocket as
 * {@code rank_change} control frames so the server can dispatch the alerts.
 *
 * <p>Wynntils' {@code GuildModel.MSG_RANK_CHANGED} regex captures only the
 * target name and new rank, and only updates internal state for the local
 * player — it does not fire any event we could subscribe to. This listener
 * runs against the same unwrapped {@link StyledText} but with a fuller regex
 * that also captures the actor and old rank, then classifies the event:</p>
 *
 * <ul>
 *   <li><b>ban</b> — {@code from ∈ {Recruiter, Captain, Strategist, Chief, Owner}}
 *       and {@code to = Recruit}. Any non-trivial demotion to recruit is a
 *       ban request: the captain who set this is asking admins to remove
 *       the player.</li>
 *   <li><b>kick</b> — {@code from = Recruit} and {@code to = Recruit}. Used
 *       in the guild as a "failed onboarding" signal — the only case
 *       where a {@code → Recruit} broadcast is *not* a ban request.</li>
 *   <li><b>mote</b> — {@code from == to} and that rank is not Recruit. Used
 *       in the guild as a celebratory non-promotion-promotion.</li>
 * </ul>
 *
 * <p>Real promotions across distinct ranks (e.g. {@code Recruit → Recruiter},
 * {@code Captain → Strategist}) are not handled — no guidance from product
 * on what they should do. They are dropped client-side.</p>
 *
 * <p>Client-side dedup over a 30s window prevents the same Wynntils chat
 * event firing twice (e.g. via {@code .Match} and {@code .Edit}) from
 * generating duplicate WS frames. Server-side dedup at temporary-server
 * is the authoritative cross-client deduplicator.</p>
 */
public final class RankChangeListener {

    /**
     * Pattern matching Wynncraft's "X has set Y guild rank from A to B"
     * broadcast. Capture groups: 1=actor, 2=target, 3=fromRank, 4=toRank.
     *
     * <p>The {@code } / {@code } prefix glyphs are the PUA
     * banner-icon variants Wynncraft uses on guild-system broadcasts; they
     * also appear in Wynntils' upstream {@code GuildModel.MSG_RANK_CHANGED}.
     * We widen Wynntils' regex to also capture the actor and old rank
     * (Wynntils only captures target+toRank since it just tracks the local
     * player's own rank).</p>
     *
     * <p><b>Alternation order matters.</b> Java regex alternation is
     * left-to-right-first-match, and this pattern has no {@code $} anchor
     * (we want to tolerate trailing styled codes after the new rank). So
     * {@code Recruiter} MUST come before {@code Recruit}, otherwise a real
     * {@code Recruit → Recruiter} promotion would partial-match as
     * {@code Recruit → Recruit} (group 4 would capture "Recruit") and be
     * misclassified as a KICK notice. Wynntils' upstream regex hides this
     * with a {@code $} anchor that forces backtracking; we do not.</p>
     */
    private static final Pattern PATTERN =
            Pattern.compile(
                    "§b(?:|) (\\w{1,16}) has set (\\w{1,16}) guild rank "
                            + "from §3(?: )?(Recruiter|Recruit|Captain|Strategist|Chief|Owner)"
                            + "§b to §3(?: )?(Recruiter|Recruit|Captain|Strategist|Chief|Owner)");

    private static final long DEDUP_TTL_MS = TimeUnit.SECONDS.toMillis(30);
    private static final int MAX_DEDUP_ENTRIES = 50;
    private static final Deque<DedupEntry> recent = new ArrayDeque<>();
    private static final Object lock = new Object();

    private static final RankChangeListener INSTANCE = new RankChangeListener();

    private RankChangeListener() {}

    public static void register() {
        WynntilsMod.registerEventListener(INSTANCE);
        VetsLogger.debug("Registered rank-change listener");
    }

    @SubscribeEvent
    public void onChat(ChatMessageEvent.Match event) {
        if (event.getRecipientType() != RecipientType.GUILD) {
            return;
        }
        if (!GuildStateManager.isReturners()) {
            return;
        }

        StyledText unwrapped = StyledTextUtils.unwrap(event.getMessage()).stripAlignment();
        Matcher m = unwrapped.getMatcher(PATTERN);
        if (!m.find()) {
            return;
        }

        String actor = m.group(1);
        String target = m.group(2);
        String fromRank = m.group(3);
        String toRank = m.group(4);
        String classification = classify(fromRank, toRank);
        if (classification == null) {
            VetsLogger.debug(
                    "Rank change ignored (no rule match): {} set {} {} -> {}",
                    actor,
                    target,
                    fromRank,
                    toRank);
            return;
        }

        if (wasRecent(actor, target, fromRank, toRank)) {
            VetsLogger.debug(
                    "Rank change deduped client-side: {} set {} {} -> {}",
                    actor,
                    target,
                    fromRank,
                    toRank);
            return;
        }

        VetsLogger.info(
                "Rank change: {} set {} from {} to {} ({})",
                actor,
                target,
                fromRank,
                toRank,
                classification);
        V1ApiManager.sendRankChange(actor, target, fromRank, toRank, classification);
        recordRecent(actor, target, fromRank, toRank);
    }

    /**
     * Returns the classification for a rank change, or {@code null} if no
     * rule matches and the event should be ignored.
     */
    static String classify(String fromRank, String toRank) {
        boolean toRecruit = "Recruit".equals(toRank);
        boolean fromRecruit = "Recruit".equals(fromRank);
        if (toRecruit) {
            // The only "→ Recruit" event that is *not* a ban request is the
            // self-loop Recruit→Recruit (failed-onboarding KICK signal).
            // Every other demotion to recruit — including the routine
            // Recruiter→Recruit case — is the captain asking admins to
            // remove the player. WAPI cross-checks the post-fact rank a
            // few minutes later for confidence.
            return fromRecruit ? "kick" : "ban";
        }
        if (fromRank.equals(toRank)) {
            return "mote";
        }
        return null;
    }

    private static String fingerprint(String actor, String target, String fromRank, String toRank) {
        return actor + "\0" + target + "\0" + fromRank + "\0" + toRank;
    }

    // Package-private for unit tests. See RankChangeListenerTest.
    static boolean wasRecent(String actor, String target, String fromRank, String toRank) {
        String fp = fingerprint(actor, target, fromRank, toRank);
        synchronized (lock) {
            long now = System.currentTimeMillis();
            prune(now);
            for (DedupEntry e : recent) {
                if (e.fingerprint.equals(fp)) {
                    return true;
                }
            }
        }
        return false;
    }

    static void recordRecent(String actor, String target, String fromRank, String toRank) {
        String fp = fingerprint(actor, target, fromRank, toRank);
        synchronized (lock) {
            long now = System.currentTimeMillis();
            prune(now);
            if (recent.size() >= MAX_DEDUP_ENTRIES) {
                recent.pollFirst();
            }
            recent.addLast(new DedupEntry(fp, now));
        }
    }

    // Package-private so tests can drive the TTL boundary without sleeping.
    static void prune(long now) {
        while (!recent.isEmpty()) {
            DedupEntry head = recent.peekFirst();
            if (head == null || now - head.timestamp <= DEDUP_TTL_MS) {
                return;
            }
            recent.pollFirst();
        }
    }

    // Test-only: wipes the dedup deque so static state doesn't leak across tests.
    static void clearForTesting() {
        synchronized (lock) {
            recent.clear();
        }
    }

    private static final class DedupEntry {
        final String fingerprint;
        final long timestamp;

        DedupEntry(String fingerprint, long timestamp) {
            this.fingerprint = fingerprint;
            this.timestamp = timestamp;
        }
    }
}
