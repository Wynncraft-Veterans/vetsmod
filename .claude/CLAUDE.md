# vetsmod

Fabric client mod for the Wynncraft "Returners" veterans guild community. Requires Wynntils (hard dependency, not bundled — users install separately).

## Key facts

- **Mod ID:** `vetsmod` | **Version:** see `gradle.properties` (`mod_version`) | **MC:** 1.21.11 | **Java:** 21
- **Wynntils dependency:** `v4.1.4-fabric` via Modrinth Maven (`modCompileOnly`)
- Most client code lives under `src/client/`. `src/main/` holds a tiny server stub plus `org.wynnvets.logging.VetsLogger` (shared by both sides).
- No unit tests (standard for Fabric mods with tight Minecraft coupling)

## Related repos (same workspace)

The five repos in this workspace make up one auth/chat ecosystem. Read each repo's own `.claude/CLAUDE.md` for details; this section is just the wiring map.

- `../temporary-server` — FastAPI Python backend at `wss://api.wynnvets.org/`. Owns the v1 inbound/outbound WebSockets that vetsmod connects to. Validates `/unlock` keys via HTTP introspection against dazebot.
- `../dazebot` — In-house Discord bot. Issues vetsmod auth keys via the `/vetsmod` slash command (DM with `/unlock <key>` body), exposes `POST /api/auth/introspect` for temporary-server to validate keys, owns the `VerifyKey` ORM table.
- `../auth-stack` — Fork of [PicoLimbo](https://github.com/Quozul/PicoLimbo) at `verify.wynnvets.org:25565`. Forwards every chat line on its mini-server to dazebot's `/api/auth/{uuid}/{msg}` for the link-code consumption flow (separate from vetsmod auth — handles the Discord↔Minecraft account *link*, not the vetsmod *unlock*).
- `../vets-deploy` — Docker stack definitions + ops docs for the VPS at `timasca.wynnvets.org`. Where the four above actually run.
- `../Wynntils` — Read-only reference copy of the Wynntils mod source. Do not edit.

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

**Tier gating:** when authenticated, the mod can only send/receive chat types its tier permits — `guild`-tier covers `guild`+`queue`, `waitlist`/`honourary` cover only their own type. `bridge` is visible to every authenticated tier. Unauthenticated sessions get unrestricted access *only* while the server's `unauth` toggle is enabled (see `../temporary-server/v1_protocol.md` §3).

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

## Item definitions

`src/client/resources/definitions.yml` — regex categories: `definitions`, `no_lore_legacy`, `misc_definitions`, `unenchanted`, `notjunk`, `new_format_override`, `enchant_excluded_items`. Edit this file to add/change item patterns without touching Java.

## Building

```bash
./gradlew build          # produces jar in build/libs/
./gradlew runClient      # launches Minecraft with the mod loaded
```
