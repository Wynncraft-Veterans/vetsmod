---
name: temporary-server Deduplication Engine
description: Guild-message deduplication engine — fingerprinting, 4 matching strategies, alias TTL, cleanup cadence, edge cases
type: project
originSessionId: dc63f47a-2d15-4f8d-9b6a-41d3049f0cc2
---
# temporary-server Dedup Engine

File: [app/services/dedup.py:33-264](../../temporary-server/app/services/dedup.py) (sibling-repo path).

Class `MessageDeduplicator`. Applied to incoming WS messages of `type="guild"` only (waitlist/honourary/bridge pass through). Exists because multiple vetsmod clients may each independently send the same message.

## Why it exists

Every Wynncraft client running vetsmod sees the same guild chat and relays it. The server would broadcast N identical copies. Dedup collapses them.

## Configuration (app/constants.py)

| Constant | Value | Purpose |
|----------|-------|---------|
| Dedup window | 5.0s | Sliding window for exact/prefix/truncation match |
| Cleanup interval | 60s | Prune expired entries |
| Alias TTL | 30s | Nickname→realname mapping lifetime |
| Min prefix length | 20 chars | Min body len for prefix/truncation matching |
| Min cross-user length | 20 chars | Min body len for cross-user match |
| Max message length | 256 chars | Truncated in sanitization |

## Fingerprint format

[dedup.py:204-229](../../temporary-server/app/services/dedup.py)

`{username_lower}\x00{message_stripped}`

- Null byte separator — cannot appear after sanitization (control chars removed)
- Detects supplementary PUA (U+F0000+) item-encoding glyphs
- Returns two fingerprints when PUA present: full (cleaned) + prefix (text before first PUA)

## Matching strategies

Every incoming guild message is tested against recent fingerprints in order:

### 1. Exact match
[dedup.py:108-110](../../temporary-server/app/services/dedup.py)

If `fingerprint` already in `_seen` dict AND `(now - first_seen) < 5.0s`, return True (duplicate).

### 2. Prefix match (item-encoded dual events)
[dedup.py:117-119](../../temporary-server/app/services/dedup.py)

Wynncraft fires item-encoded messages twice: once with raw PUA glyphs, once with the decoded text. The prefix (text before PUA) is recorded when the first is seen; the second is matched on that prefix and suppressed.

### 3. Truncation match (soft-wrap)
[dedup.py:127-141](../../temporary-server/app/services/dedup.py)

For messages ≥20 chars from the same user:
- If incoming is a prefix of a recent message → duplicate
- If recent message is a prefix of incoming → duplicate

Handles Wynncraft's line-wrap artifacts where one client sees `"hello worl"` and another sees `"hello world"`.

### 4. Cross-username alias (nickname vs real name)
[dedup.py:158-177](../../temporary-server/app/services/dedup.py)

If message body ≥20 chars identical to a recent message from a different resolved username:
- Treat as duplicate
- Register nickname alias in `_aliases` (TTL 30s)
- Return True

Why: A Wynncraft nickname (set via `/nick`) shows differently to different clients.

### 5. New message
[dedup.py:180-184](../../temporary-server/app/services/dedup.py)

No match → record in `_seen` and optionally `_item_prefixes`. Return False (forward to outbound queue).

## Username resolution

[dedup.py:190-202](../../temporary-server/app/services/dedup.py)

`_resolve_username()`:
- Look up nickname in `_aliases`
- If alias exists and fresh (< 30s) → return real name
- Else → return input lowercased

`register_alias(nickname, real_username)` stores mapping with timestamp.

## Cleanup

[dedup.py:231-260](../../temporary-server/app/services/dedup.py)

Runs every 60 seconds, checked on each `is_duplicate()` call (amortized):
- Prune `_seen` entries where `now - ts >= 5.0s`
- Prune `_aliases` entries where `now - ts >= 30.0s`
- Prune `_item_prefixes` with matching expired fingerprints

## Where it's called from

[app/chat/inbound.py:259-267](../../temporary-server/app/chat/inbound.py)

```python
if payload["type"] == "guild":
    if state.guild_deduplicator.is_duplicate(sanitized):
        await ws.send_json({"status": "ok"})  # silently ACK
        continue
```

Only guild-type messages go through dedup. Waitlist / honourary / bridge bypass.

## Edge cases handled

- **Nicknames:** Handled via cross-user alias + truncation match
- **Item encoding:** Handled via prefix match (dual-event Wynncraft behaviour)
- **Line wrapping:** Handled via truncation match (≥20 chars)
- **Profanity censoring:** NOT handled (would require fuzzy matching) — each censored variant is distinct
- **URLs with spaces:** NOT handled at server level — vetsmod client repairs these before relay

## Edge cases NOT handled (by design)

- Messages <20 chars across users (too risky to auto-merge short messages)
- Very fast retyped messages >5s apart (window too short)
- Messages with same username but different case → handled via `username_lower` normalization

## State exposure

State accessed via:
- `state.guild_deduplicator` — the `MessageDeduplicator` instance
- `state.guild_deduplicator.is_duplicate(sanitized_message)` — main predicate
- `state.guild_deduplicator.register_alias(...)` — called by `transform_inbound()` when username has `name/nick` form

## Testing considerations

No unit tests in repo. When modifying this engine, consider:
- Exact replay (expected duplicate)
- Item-encoded dual event (expected duplicate after prefix match)
- Slow-duplicate >5s (expected new)
- Nickname cross-user (expected duplicate; alias registered)
- Short messages (<20 chars) — should NOT cross-user dedup
- Same message from different case (`USER` vs `user`) — should dedup (normalized)
