---
name: Project Overview
description: High-level summary of the 6-repo Wynncraft Veterans ecosystem (vetsmod, temporary-server, dazebot, auth-stack, vets-deploy, vets-anni)
type: project
---

This workspace is a Wynncraft (Minecraft MMO) guild ecosystem for the "Returners" veterans community. Six repos work together; do not treat any of them in isolation when making auth/identity changes.

| Repo | Role | Stack |
|------|------|-------|
| **vetsmod** | Fabric client mod players install. Owns chat UI, item highlighting, supporter glints, and the `/unlock <key>` command. | Java 21, MC 1.21.11, Wynntils v4.1.17-fabric |
| **temporary-server** | FastAPI backend at `wss://api.wynnvets.org/`. Owns the v1 inbound/outbound WebSockets, Discord bridge, dedup engine, and tier/auth gating. Validates `/unlock` keys via HTTP introspection against dazebot. | Python 3.12, FastAPI, discord.py, httpx |
| **dazebot** | In-house Discord bot. Owns the `VerifyKey` table, the `/vetsmod` slash command (issues 43-char base64url keys via DM), and the `POST /api/auth/introspect` endpoint temporary-server calls. Also handles guild waitlists, vanity roles, supporter detection, and the picolimbo link-code consumption flow. | Python 3.13, discord.py, tortoise-orm, FastAPI |
| **auth-stack** | Fork of [PicoLimbo](https://github.com/Quozul/PicoLimbo) running at `verify.wynnvets.org:25565`. Forwards every chat line a player types to dazebot's `/api/auth/{uuid}/{msg}` so dazebot can scan for link codes (Discord ↔ Minecraft account *linking*, separate from vetsmod *unlock*). | Rust, Pterodactyl egg + Docker |
| **vets-anni** | Annihilation-event app at `anni.wynnvets.org` — FastAPI web board plus **fishbot** (Discord, `\` prefix). Owns RSVP, party/role assignment and the MWE snapshots temporary-server polls and pushes to vetsmod. | Python, FastAPI, discord.py, tortoise-orm |
| **vets-deploy** | Source-of-truth Docker stacks + ops docs for the `timasca.wynnvets.org` VPS. Where every other repo runs in production. | Bash + docker compose + Traefik |
| **Wynntils** | Read-only sibling clone. Public Wynntils source — vetsmod compiles against it. Do not edit. | — |

All six (plus the `Wynntils` reference clone) are sibling directories under whatever workspace root they're checked out into.

## Production layout

- **`https://api.wynnvets.org/`** — temporary-server. Public.
- **`verify.wynnvets.org:25565`** — auth-stack (PicoLimbo fork). Public TCP.
- **`http://dazebot:8001/`** on the internal `verify` Docker network — dazebot's FastAPI. Reachable by temporary-server (introspection) and auth-stack (link-code consumption). Not public.
- **`https://wynnvets.org/discord`** — Discord invite, used by mod warning copy.

## Key flows

**Account link (one-time, via `/first_install` button):**
1. User clicks the "Link my Minecraft account" button in Discord.
2. Dazebot DMs them a 6-char code + the verify server IP.
3. User joins `verify.wynnvets.org` (auth-stack/PicoLimbo) and types the code.
4. PicoLimbo POSTs the chat line to dazebot's `/api/auth/{uuid}/{msg}`.
5. Dazebot couples the Discord account to the MC UUID and assigns roles.

**Vetsmod authentication (per-user, replaces SHA-256 password unlock):**
1. User runs `/vetsmod` in Discord (`#bot-commands`).
2. Dazebot DMs them the modrinth link + a `/unlock <43-char-key>` command.
3. User pastes `/unlock <key>` in Minecraft. The mod intercepts it, persists the key, and sends an `auth` frame on the inbound WS.
4. Temporary-server validates the key via dazebot's `POST /api/auth/introspect` (60s LRU cache), stores `(disc_uuid, mc_uuid, mc_username, tier, ws_tier)` on the connection, and replies with the resolved tier.
5. Subsequent chat is gated: `member`-tier may send/receive `guild`+`queue`, `waitlist` only `waitlist`, etc.
6. Connections without a valid auth are accepted *only* while the server's `unauth` admin toggle is enabled (default during alpha).

## Mod version + dependency

**Mod version:** see `vetsmod/gradle.properties` (`mod_version`) — bumps regularly. **Wynntils dependency:** compiled against v4.1.17-fabric (`modCompileOnly` in build.gradle); the runtime floor declared in fabric.mod.json is the older `>=4.1.4`, which is deliberate — the mod loads against anything from 4.1.4 up.

## How to apply

When making changes, consider impact on **all six tiers**: client mod (Java), backend server (Python), Discord bot (Python ORM + slash commands), link-code limbo (Rust), annihilation app + fishbot (Python), deployment compose (YAML). Auth/identity changes routinely touch four of the six. The introspection path is the one that most often breaks: check both ends of it (dazebot's `POST /api/auth/introspect` and temporary-server's caller) before changing anything there.
