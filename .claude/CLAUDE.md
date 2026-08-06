# vetsmod

Fabric client mod for the Wynncraft "Returners" veterans guild community. Requires Wynntils (hard dependency, not bundled — users install separately).

## Key facts

- **Mod ID:** `vetsmod` | **Version:** see `gradle.properties` (`mod_version`) | **MC:** 1.21.11 | **Java:** 21
- **Wynntils dependency:** `v4.1.4-fabric` via Modrinth Maven (`modCompileOnly`)
- Most client code lives under `src/client/`. `src/main/` holds a tiny server stub plus `org.wynnvets.logging.VetsLogger` (shared by both sides).
- JUnit 5 harness under `src/test/java/` for pure-logic classes (`VetsLogger`, `SpoilerCodec`, `RankChangeListener.classify`). Run via `./gradlew test` (also runs with `./gradlew build`). Anything importing `net.minecraft.*` or `com.wynntils.*` stays untested — out of scope for the harness.

## Related repos (same workspace)

The five repos in this workspace make up one auth/chat ecosystem. Read each repo's own `.claude/CLAUDE.md` for details; this section is just the wiring map.

- `../temporary-server` — FastAPI Python backend at `wss://api.wynnvets.org/`. Owns the v1 inbound/outbound WebSockets that vetsmod connects to. Validates `/unlock` keys via HTTP introspection against dazebot.
- `../dazebot` — In-house Discord bot. Issues vetsmod auth keys via the `/vetsmod` slash command (DM with `/unlock <key>` body), exposes `POST /api/auth/introspect` for temporary-server to validate keys, owns the `VerifyKey` ORM table.
- `../auth-stack` — Fork of [PicoLimbo](https://github.com/Quozul/PicoLimbo) at `verify.wynnvets.org:25565`. Forwards every chat line on its mini-server to dazebot's `/api/auth/{uuid}/{msg}` for the link-code consumption flow (separate from vetsmod auth — handles the Discord↔Minecraft account *link*, not the vetsmod *unlock*).
- `../vets-deploy` — Docker stack definitions + ops docs for the VPS at `timasca.wynnvets.org`. Where the four above actually run.
- `../Wynntils` — Read-only reference copy of the Wynntils mod source. Do not edit.

## Discord bots in this workspace — command prefixes

vetsmod doesn't own a Discord bot, but its in-game chat strings reference Discord commands run on **dazebot** (e.g. the `~vetsmod` link mentioned in `SessionAuthWarning`, `UnlockCommandMixin`, `UnlockManager`). The vets ecosystem runs four Discord bots; the table is duplicated across each bot's repo so the mapping is discoverable from any vantage point:

| Bot | Repo | Prefix |
|-----|------|--------|
| **dazebot** | `../dazebot` | `~` |
| nazbot | `../temporary-server` | `!` |
| fishbot | `../vets-anni` | `\` |
| dynobot | (third-party, no repo) | `?` |

### In-game chat string convention

In any user-facing chat string emitted from vetsmod (i.e. anything sent via `ChatUtils.sendLocalMessage` / `Component.literal` that the player sees in Minecraft chat), refer to the dazebot Discord command as **`~vetsmod`**, not `/vetsmod`. Minecraft treats `/` as the client-command prefix, so showing `/vetsmod` invites the player to type it as a Minecraft client command, which fails locally and is confusing.

- Applies only to **user-facing chat strings** inside Java source (callers of `ChatUtils.sendLocalMessage*` — `UnlockCommandMixin`, `UnlockManager`, `SessionAuthWarning`, etc.).
- Does **not** apply to Javadoc, code comments, this CLAUDE.md, or any documentation describing the Discord command itself — those keep `/vetsmod` (the real Discord slash command).
- Does **not** apply to `/unlock` — that is a real *client-side* command intercepted by `UnlockCommandMixin`, so the slash is correct there.

## Architecture overview

```
VetsmodClient (entry point)
  ├── VetsConfig              JSON config at vetsmod/storage/config.json
  ├── ItemDefinitions         YAML regex patterns (src/client/resources/definitions.yml)
  ├── GuildStateManager       Facade: GuildChecker, StaffRankChecker, UnlockManager, SessionAuthWarning
  ├── V1ApiManager            Dual WebSocket (inbound + outbound) to api.wynnvets.org
  │                           Sends `auth` frame after connect using the stored /unlock key
  ├── OutboundDisplayHandler  Receives WS messages, deduplicates, displays in chat
  ├── QueueStateManager       In-queue state + listeners; fed by QueueDetector (title + world events)
  ├── Polling services        StaffRanksPoller (2min), SupportersPoller (5min), GuildRosterCache
  ├── CommandRegistry         /wv command tree
  ├── WynntilsEventListener   WorldStateEvent, GuildEvent, ChatMessageEvent
  └── Mixins (11)             Chat (3), Legacy items (3), Commands (2 — incl. UnlockCommandMixin),
                               plus NametagMixin, CommandSuggestionsMixin, QueueTitleMixin
```

## WebSocket protocol

Both connections auto-reconnect (3s) with 30s pings. Registration frame *and* `auth` frame re-sent on reconnect.

- **Inbound** `wss://api.wynnvets.org/v1/inbound` — client sends messages
- **Outbound** `wss://api.wynnvets.org/v1/outbound` — server pushes to all clients

**Control frames** (sent by client):
- `register` — presence (uuid, username, tier)
- `tablist` — guild tab snapshot for `!list`
- `queue_status` — sender is currently in a Wynncraft world queue (presence side-channel; orthogonal to the `queue` chat type)
- `auth` — `{type:"auth", key:"<43-char base64url>"}` proves identity. Server replies `{status:"ok", tier, ws_tier, ...}` or `{status:"error", detail:"auth rejected: ..."}`.

**Server → client unsolicited frames:**
- `server_info` — pushed on outbound connect, carries `unauth_enabled` so the mod can choose the right session-warning copy.

**Chat message fields:** `uuid`, `type` (`guild`|`queue`|`waitlist`|`honourary`|`bridge`), `timestamp`, `rank`, `username`, `message`. `queue` carries guild chat originated by a sender stuck in a world queue (the game server drops `/g` while queued); semantically a guild message but routed via the WS so it still reaches the rest of the guild. Inbound dedup is skipped for `queue` since only the queued sender originates it.

**Tier gating:** when authenticated, the mod can only send/receive chat types its tier permits — `guild`-tier covers `guild`+`queue`, `waitlist`/`honourary` cover only their own type. `bridge` is visible to every authenticated tier. Unauthenticated sessions get unrestricted access *only* while the server's `unauth` toggle is enabled (see `../../temporary-server/v1_protocol.md` §3).

### API boundary — vetsmod talks to temporary-server, NOT to dazebot

vetsmod MUST NOT call dazebot directly. All vets-backend interactions go through **temporary-server** at `https://api.wynnvets.org` or the WebSockets `wss://api.wynnvets.org/v1/{inbound,outbound}`. dazebot's `/api/internal/*` endpoints are network-gated to the private `verify` Docker network and would reject a vetsmod client anyway; on top of that, vetsmod `/unlock` keys are per-user bearer tokens, not credentials for hitting dazebot's internal control plane.

When designing a new vetsmod feature that needs data from dazebot, route it through a temporary-server WS frame (`V1ApiManager.sendStaffActionFrame`, `sendInbound`, etc.) — the existing `check_membership` staff-action frame is the canonical pattern (vetsmod → `wss://api.wynnvets.org/v1/inbound` → temporary-server forwards to dazebot's `/api/internal/check-snapshot` → ack flows back through the same WebSocket).

In vetsmod-side docs, name the **immediate counterparty** (temporary-server / `api.wynnvets.org`), not the eventual upstream (dazebot). Calling the upstream by name in vetsmod-side docs invites the wrong mental model and the wrong implementation.

Allowed-from-vetsmod surface: anything fronted by `api.wynnvets.org` or `wss://api.wynnvets.org`. NOT allowed: any dazebot host, any `/api/internal/*` path, or any URL that requires being on the vets server network.

## User tiers (brief)

`member` / `waitlist` / `honourary` / `other`. Tier is **resolved server-side** from Discord roles + linked MC account; the mod gets it back in the `auth` frame ack and uses it for client-side display + warning copy. Staff (captain+) is orthogonal to tier — detected via `/gu rank`, cached 24h.

Auth detail (the `/unlock <key>` flow, key persistence, ack routing, `SessionAuthWarning`) is in [vetsmod_guild_system.md](vetsmod_guild_system.md). Wire-level tier gating is in [vetsmod_networking.md §8](vetsmod_networking.md).

## Key Wynntils integration points

```java
WynntilsMod.registerEventListener(this);   // register @SubscribeEvent methods
Models.Guild.getGuildName()
Models.Guild.isInGuild()
Handlers.Command                           // rate-limited queue for /gu stats, /gu rank, /find
ChatMessageEvent.Match / .Edit             // chat interception
WorldStateEvent                            // world join trigger
StyledText, ComponentUtils, McUtils
```

## Config keys

**User-facing (via `/wv config`):** `legacyItemHighlighting`, `printMOTD`, `printANNI`, `printBridgeMessages`, `showSupporterGlints`, `handleSpoilers`, `moreReliableGuildCheck`, legacy item gradient colours/opacity/sprite.

**Internal — vetsmod auth state:** `vetsAuthKey` (string), `vetsAuthTier` (string), `vetsAuthVerifiedAt` (long). Old `vetsWaitlistUnlockTime` / `vetsHonouraryUnlockTime` longs survive only as a "legacy unlock marker" for the session-start warning.

## Chat pipeline

Wynntils fires `ChatMessageEvent.Match` → rewriters (`SpoilerRewriter`, `StaffGuildAlertRewriter`, `StaffChannelMessageRewriter`, `ServerGuildChatRewriter`) → `ChatMessageEvent.Edit` → display.

Rank pills are invisible PUA sequences, not images. A codepoint's meaning is **frame-scoped** — `U+E003` is the private-message separator despite sitting in the same `U+E000` block that spells lowercase letters inside a pill — so decode from the frame inward and never map a bare codepoint to a character. Encode and decode through [`PillCodec`](../src/client/java/org/wynnvets/chat/PillCodec.java); the blocks, the four sequences, and the captured-log evidence are in [vetsmod_pua_pills.md](vetsmod_pua_pills.md).

## Guild rank-change alerts

[`RankChangeListener`](../src/client/java/org/wynnvets/listeners/RankChangeListener.java) subscribes to `ChatMessageEvent.Match`, detects Wynncraft's `"X has set Y guild rank from A to B"` broadcast (regex widens Wynntils' `GuildModel.MSG_RANK_CHANGED` to also capture actor + old rank), classifies it as `ban` / `kick` / `mote`, and emits a `rank_change` control frame on `/v1/inbound`. Server-side dispatch and the trust model are documented in [server_api_reference.md](server_api_reference.md) and authoritatively in [`temporary-server/v1_protocol.md` §1.9](../../temporary-server/v1_protocol.md).

## Item definitions

`src/client/resources/definitions.yml` — regex categories: `definitions`, `no_lore_legacy`, `misc_definitions`, `unenchanted`, `notjunk`, `new_format_override`, `enchant_excluded_items`. Edit this file to add/change item patterns without touching Java.

## Building

```bash
./gradlew build          # produces jar in build/libs/
./gradlew runClient      # launches Minecraft with the mod loaded
```

If a gitignored `local.gradle` is present (see `local.gradle.example`), `./gradlew build` is wired via `build.finalizedBy 'deployToPrism'` to also drop `vetsmod-dev.jar` into the developer's PrismLauncher mods folder, scrubbing prior `vetsmod-*.jar` to avoid duplicate-mod-ID load failures. Restart Minecraft to pick up changes — Fabric mods can't hot-reload mixins or registries.

**Sanity-check builds must NOT touch the live mods folder.** If the goal is verifying a change compiles, type-checks, or passes tests — anything short of "the user is actively iterating on the mod in MC right now" — use one of these instead, since the user's Wynntils instance may be in active use and they don't want their session interrupted by a swapped jar:

- `./gradlew build -x deployToPrism` — full build, skips the deploy hook
- `./gradlew compileJava compileClientJava` — compile-only, fastest
- `./gradlew test` — tests are unaffected by the deploy hook

Only use the deploying form (`./gradlew build`) when the user has explicitly asked for an end-to-end build-and-deploy, or is mid-iteration on the mod.

## External name-resolution providers

Reliability ladder: `ashcon < wynncraft < playerdb < mojang`.

| Provider | Accuracy | Rate limit |
|---|---|---|
| ashcon | low (frequently stale) | very permissive |
| PlayerDB | medium | medium-permissive (not unlimited) |
| Wynncraft `/v3/player` | only authoritative for Wynncraft-internal state | shared with the user's other traffic |
| Mojang | source of truth | very restrictive |

**This repo is client-side.** The Mojang rate-limit bucket is shared with whatever else the user has installed (Wynntils, other mods), so the remaining budget is unpredictable. Be conservative — try permissive sources first.

Implementation: [`org.wynnvets.fetcher.lookup.PlayerLookup`](../src/client/java/org/wynnvets/fetcher/lookup/PlayerLookup.java) already does this. Its cascade is `WynncraftProvider → VetsSnapshotProvider → PlayerDbProvider → AshconProvider → MojangServicesProvider → MojangLegacyProvider`, with Mojang as the last resort. Preserve that ordering; do **not** promote Mojang earlier without a strong reason.

When a permissive provider returns a player record, treat its `username` field as potentially stale (we've observed PlayerDB and ashcon both returning old names long after Mojang has the rename live). If you need to write a name to a long-lived cache, confirm against Mojang first; if that fails, skip the cache write rather than persisting a known-stale value.
