package org.wynnvets.mwe.anni.outline;

import com.wynntils.utils.colors.CustomColor;
import net.minecraft.ChatFormatting;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.mwe.anni.state.AnniSnapshot;
import org.wynnvets.mwe.anni.state.AnniSnapshotCache;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Username → tiered colour mapping for the S4 highlight overlay.
 *
 * <p>Built and re-built off {@link AnniSnapshotCache} pushes — every time
 * vetsmod receives a fresh snapshot, the registry rebuilds from:</p>
 * <ul>
 *   <li>{@code snapshot.board.party.members} → local player's own party →
 *       tier {@link Tier#OWN_PARTY} with the spec-canonical role colour.</li>
 *   <li>{@code snapshot.event.all_parties} (schema v2) minus the own-party
 *       members → tier {@link Tier#OTHER_VETS_PARTY} with the light-grey
 *       {@code §7} colour.</li>
 * </ul>
 *
 * <p>Everyone NOT in the registry is treated as an "outsider" by the
 * consumers (no outline, dark-grey nametag while the activation gate
 * holds) — that's a default applied by the ticker / nametag mixin, NOT
 * a registry entry. Keeps the table small (~50 entries max during an
 * anni) and the consumer side stateless.</p>
 *
 * <p>Username-keyed per outlines.md §2.4 ("All three models key by
 * username, not UUID"). vetsmod's existing {@code GameProfile.name()}
 * resolution path in {@code NametagMixin} matches this; {@code level.players()}
 * iteration in the ticker matches this too.</p>
 *
 * <p>Thread-safety: rebuilds run on the WS reader thread (via
 * {@link AnniSnapshotCache} listener). Reads run off that thread — from
 * the client tick ({@code AnniOutlineTicker}) and from the render path
 * ({@code NametagMixin}, {@code EntityOutlineColorMixin}, both injecting
 * into {@code extractRenderState}). {@link ConcurrentHashMap} gives both
 * sides lock-free O(1) access; a stale read during a rebuild is harmless
 * because both old and new states are valid registry views.</p>
 */
public final class AnniOutlineRegistry {

    /** Per-snapshot tier classification. Drives colour and nametag style
     *  selection at the consumer side. */
    public enum Tier {
        /** Member of the local player's own anni party. Role-coloured. */
        OWN_PARTY,
        /** Member of any other vets-anni party. Light grey, role-agnostic. */
        OTHER_VETS_PARTY,
    }

    /** Immutable per-entry record. */
    public static final class Entry {
        private final Tier tier;
        private final String role;
        private final CustomColor outlineColor;
        private final ChatFormatting nametagFormatting;

        Entry(Tier tier, String role, CustomColor outlineColor, ChatFormatting nametagFormatting) {
            this.tier = tier;
            this.role = role;
            this.outlineColor = outlineColor;
            this.nametagFormatting = nametagFormatting;
        }

        public Tier tier() {
            return tier;
        }

        /** Role code as it appears in the snapshot
         *  ({@code TANK}/{@code HEALER}/{@code FILL}/...); {@code null}
         *  for non-own-party entries. */
        public String role() {
            return role;
        }

        public CustomColor outlineColor() {
            return outlineColor;
        }

        /** {@link ChatFormatting} the nametag branch in
         *  {@code NametagMixin} should recolour with. Matches the outline
         *  family for the same tier. */
        public ChatFormatting nametagFormatting() {
            return nametagFormatting;
        }
    }

    /** Lowercase-username → entry. Lowercase keys defend against minor
     *  case mismatches between snapshot data and Mojang profile names. */
    private static final Map<String, Entry> entries = new ConcurrentHashMap<>();

    private static volatile boolean registered = false;

    private AnniOutlineRegistry() {
    }

    /** Idempotent registration. Subscribes the rebuild listener to the
     *  shared snapshot cache. Call from {@code VetsmodClient}
     *  {@code onInitializeClient}. */
    public static void register() {
        if (registered) return;
        registered = true;
        AnniSnapshotCache.addListener(AnniOutlineRegistry::rebuildFrom);
        rebuildFrom(AnniSnapshotCache.latest());
        VetsLogger.debug("AnniOutlineRegistry registered");
    }

    /** Look up a player by their {@link com.mojang.authlib.GameProfile}
     *  name. {@code null} if not on any tracked vets-anni party for the
     *  current snapshot (consumer should apply the outsider default). */
    public static Entry getEntry(String username) {
        if (username == null) return null;
        return entries.get(username.toLowerCase(Locale.ROOT));
    }

    /** Quick "do we have any active overrides" check. Lets consumers
     *  short-circuit the per-tick walk when there's nothing to do. */
    public static boolean isAnyActive() {
        return !entries.isEmpty();
    }

    /** Number of entries currently registered. Diagnostic only. */
    public static int size() {
        return entries.size();
    }

    /** Drop every entry. Part of the debug API below in everything but
     *  placement: its only caller is {@code AnniDebugCommands.registryClearAll}
     *  ({@code /wv debug tree anni registry clearall}). Neither a null
     *  snapshot nor the activation gate closing reaches it — {@code rebuildFrom}
     *  does its own inline clear-and-swap, and the gate is the ticker's
     *  business, not the registry's. */
    public static void clearAll() {
        entries.clear();
    }

    // ── Rebuild path ────────────────────────────────────────────────────

    private static void rebuildFrom(AnniSnapshot snapshot) {
        Map<String, Entry> next = new ConcurrentHashMap<>();
        if (snapshot != null) {
            String selfUsername = snapshot.mcUsername();
            String selfLower = selfUsername != null ? selfUsername.toLowerCase(Locale.ROOT) : null;

            // Own-party members first so the per-username overwrite below
            // resolves to OWN_PARTY when the same player appears in both
            // board.party.members and event.all_parties (which they will,
            // since own-party IS one of the all_parties entries).
            AnniSnapshot.Board board = snapshot.board();
            if (board != null && board.party() != null) {
                for (AnniSnapshot.MemberRef m : board.party().members()) {
                    String name = m.username();
                    if (name == null || name.isEmpty()) continue;
                    String key = name.toLowerCase(Locale.ROOT);
                    // Skip the local player — we don't outline ourselves.
                    if (selfLower != null && selfLower.equals(key)) continue;
                    next.put(key, ownPartyEntry(m.role()));
                }
            }

            // Then every other vets-anni party.
            AnniSnapshot.Event event = snapshot.event();
            if (event != null) {
                for (AnniSnapshot.PartySummary p : event.allParties()) {
                    for (AnniSnapshot.MemberRef m : p.members()) {
                        String name = m.username();
                        if (name == null || name.isEmpty()) continue;
                        String key = name.toLowerCase(Locale.ROOT);
                        if (selfLower != null && selfLower.equals(key)) continue;
                        // putIfAbsent — already-mapped OWN_PARTY entries win.
                        next.putIfAbsent(key, OTHER_PARTY_ENTRY);
                    }
                }
            }
        }
        entries.clear();
        entries.putAll(next);
        VetsLogger.debug("AnniOutlineRegistry rebuilt: {} entries", entries.size());
    }

    private static Entry ownPartyEntry(String role) {
        ChatFormatting fmt = AnniOutlinePalette.chatFormattingForRole(role);
        CustomColor outline = CustomColor.fromChatFormatting(fmt);
        return new Entry(Tier.OWN_PARTY, role, outline, fmt);
    }

    private static final Entry OTHER_PARTY_ENTRY = new Entry(
            Tier.OTHER_VETS_PARTY,
            null,
            AnniOutlinePalette.OTHER_VETS_PARTY,
            ChatFormatting.GRAY);

    // ── Debug API ───────────────────────────────────────────────────────
    //
    // Drives /wv debug tree anni registry … — lets a developer place an
    // arbitrary nearby player into a chosen tier/role to visually verify
    // the highlight + nametag branches without coordinating a real anni
    // party. Debug entries are wiped by every snapshot rebuild (any
    // AnniSnapshotCache push, including null) because rebuildFrom does an
    // unconditional clear; the test workflow is fire-and-forget per
    // snapshot generation.

    /** Test-only: insert an arbitrary registry entry. */
    public static void debugSet(String username, Entry entry) {
        if (username == null || username.isEmpty() || entry == null) return;
        entries.put(username.toLowerCase(Locale.ROOT), entry);
        VetsLogger.debug("AnniOutlineRegistry debug set: {} -> tier={} role={}",
                username, entry.tier(), entry.role());
    }

    /** Test-only: remove a username's entry (no-op if none). */
    public static void debugRemove(String username) {
        if (username == null) return;
        if (entries.remove(username.toLowerCase(Locale.ROOT)) != null) {
            VetsLogger.debug("AnniOutlineRegistry debug remove: {}", username);
        }
    }

    /** Test-only: build an OWN_PARTY entry for the given role using the
     *  same palette as the snapshot rebuild path. {@code null}/unknown
     *  role falls back to grey per {@link AnniOutlinePalette#chatFormattingForRole}. */
    public static Entry debugOwnPartyEntry(String role) {
        return ownPartyEntry(role);
    }

    /** Test-only: the canonical OTHER_VETS_PARTY entry (light grey). */
    public static Entry debugOtherPartyEntry() {
        return OTHER_PARTY_ENTRY;
    }
}
