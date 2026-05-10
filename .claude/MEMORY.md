# Memory Index

## Project overviews
- [Project Overview](project_overview.md) — 5-repo Wynncraft Veterans ecosystem: vetsmod, temporary-server, dazebot, auth-stack, vets-deploy
- [User Profile](user_profile.md) — Developer maintaining the full ecosystem

## vetsmod (Fabric client mod — Java)
- [vetsmod Architecture](project_vetsmod.md) — Entry points, package map, WebSocket protocol, user tiers, Wynntils integration summary
- [Chat Pipeline](vetsmod_chat_pipeline.md) — ChatLogMixin hook, rewriter chain (5 rewriters), dispatcher (staff fanout + /find), OutboundDisplayHandler, PillFormatter, SpoilerCodec, state/caches
- [Commands Reference](vetsmod_commands.md) — Complete /wv command tree, permissions, handlers, chat-command mixins (/unlock <key>, /toggle, /g/wg/v)
- [Guild / Unlock / Staff-Rank System](vetsmod_guild_system.md) — GuildStateManager facade, GuildChecker (3-day cache), StaffRankChecker (24h cache), UnlockManager (bearer-key auth, replaces SHA-256), SessionAuthWarning
- [Config Reference](vetsmod_config.md) — All config keys (internal + user-facing) including new vetsAuth* fields, defaults, validation, persistence format
- [Networking (WebSocket + Fetchers + Polling)](vetsmod_networking.md) — V1ApiManager (incl. sendAuth + auth-ack routing), WsClient reconnection/ping, on-demand HTTP fetchers, polling services
- [Legacy Items System](project_legacy_items.md) — 7-branch detection cascade, 8-branch tooltip rewriter, YAML definitions, mixins, config, edge cases
- [Mixins Reference](vetsmod_mixins.md) — All 11 mixins: target class, inject point, purpose, rationale (UnlockCommandMixin intercepts `/unlock <key>`; QueueTitleMixin/CommandSuggestionsMixin handle queue UX)
- [Queue subsystem](project_vetsmod.md#queue) — `org.wynnvets.queue` package + how the `queue` chat type routes guild messages while the game server drops `/g`
- [Rendering System](vetsmod_rendering.md) — Territory lines, NametagAnimator, AnimatedGradientSequence, GradientTextBuilder, ShaderColorPalette

## temporary-server (FastAPI Python backend)
- [temporary-server Architecture](project_server.md) — Stack, four pillars (incl. dazebot HTTP introspection), file structure, REST endpoints, dedup summary, Discord admin commands
- [API Reference](server_api_reference.md) — /v1/inbound WS (incl. auth frame + tier gate), /v1/outbound WS (incl. server_info hello + tier filter), all REST endpoints, admin toggles, protocol details, TTLs
- [Deduplication Engine](server_dedup_engine.md) — Fingerprinting, 4 matching strategies (exact/prefix/truncation/cross-user), alias TTL, cleanup, edge cases
- [Discord Bot](server_discord_bot.md) — Bridge routing, role→rank mapping, all admin commands incl. `unauth` toggle, !list, staff alert relay, mention resolution, sanitization
- [Services Layer](server_services.md) — AppState fields (incl. authenticated_sessions), StaffPoller (5min + 10s probe), GuildRosterPoller, username_cache (12h TTL), recorder (120s → DM), config loader

## Wynntils (read-only dependency)
- [Wynntils API Reference](project_wynntils.md) — Events (with field layouts), Handlers.Command, Models.Guild, StyledText, McUtils, event priority system, import paths

## Cross-repo / ops
- [Auth deployment instructions](temporary-ephemeral/auth_deployment_instructions.md) — Step-by-step rollout for the `/vetsmod` + `/unlock` flow on the vets-deploy stack (ephemeral)
