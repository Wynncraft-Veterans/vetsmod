---
name: temporary-server Deduplication Engine
description: Guild-message deduplication engine — fingerprinting, 4 matching strategies plus the no-match fallthrough, 35s window, alias TTL, cleanup cadence, edge cases
type: project
originSessionId: dc63f47a-2d15-4f8d-9b6a-41d3049f0cc2
---
# temporary-server Dedup Engine

Class `MessageDeduplicator`, in
[`app/services/dedup.py`](../../temporary-server/app/services/dedup.py)
(sibling-repo path). Applied to incoming WS messages of `type="guild"` only.
Exists because multiple vetsmod clients may each independently send the same
message.

## Why it exists

Every Wynncraft client running vetsmod sees the same guild chat and relays it.
The server would broadcast N identical copies. Dedup collapses them.

## How it is reached

There is **one process-global instance**: `guild_deduplicator`, a module-level
singleton at the bottom of `dedup.py`. It is **not** a field on `AppState` —
`state.py` has no deduplicator attribute of any kind, and only three modules
touch the singleton at all:

- `dedup.py` — defines `MessageDeduplicator` and instantiates it.
- `chat/inbound.py` — imports it and calls `is_duplicate`.
- `chat/processing.py` — imports it and calls `register_alias` from
  `transform_inbound`.

Both consumers reach it by a plain module-level import
(`from app.services.dedup import guild_deduplicator`), not through `AppState`
and not through a deferred import. `processing.py` takes no state parameter
anywhere. The practical consequence is that the alias table is shared across
every connection in the process, not scoped per-client.

## Configuration (app/constants.py)

| Constant | Value | Purpose |
|----------|-------|---------|
| `DEDUP_WINDOW_SECONDS` | 35.0 | Sliding window for exact/prefix/truncation match |
| `DEDUP_CLEANUP_INTERVAL_SECONDS` | 60.0 | Prune expired entries |
| `DEDUP_ALIAS_TTL_SECONDS` | 30.0 | Nickname→realname mapping lifetime |
| `DEDUP_MIN_PREFIX_LENGTH` | 20 | Min body len for the **truncation** match (strategy 3) — despite the name, the item-PUA prefix match has no floor |
| `DEDUP_MIN_CROSS_USER_LENGTH` | 20 | Min body len for cross-user match |
| `MAX_MESSAGE_LENGTH` | 256 | Truncated in sanitization — a message constraint, not a dedup one |

The window is **35 seconds**. This doc claimed a much shorter one for a long
time; the likely origin is the inline comment on `dedup.py`'s cross-user
strategy, which still describes the older, shorter window and is the only
place in that file that disagrees with the constant. Everything executable
agrees on 35.0 — the constant, `MessageDeduplicator.__init__`'s default, the
module docstring, and `inbound.py`'s stale-timestamp canary, which warns when
an accepted message is older than `DEDUP_WINDOW_SECONDS`. Correcting that
comment is temporary-server's business, not this doc's.

⚠️ **`DEDUP_WINDOW_SECONDS` is not a unique name.** `app/services/rank_alerts.py`
defines its own module-local `DEDUP_WINDOW_SECONDS = 60.0` (and
`DEDUP_CLEANUP_INTERVAL_SECONDS = 300.0`) for `RankAlertDispatcher`'s
rank_change deduplication — an unrelated mechanism that does not import from
`constants.py`. A bare grep returns both 35.0 and 60.0. The values in this
table are the guild deduplicator's, from `app/constants.py`.

The two length thresholds are both 20 but are **separate constants** serving
separate strategies — don't collapse them. Note also that fingerprints expire
at the 35 s window while aliases expire at 30 s, so an alias is shorter-lived
than the window it serves.

The three timing constants are injected as `__init__` defaults and stored as
`_window` / `_cleanup_interval` / `_alias_ttl`; the two length thresholds are
read straight from the module import inside `is_duplicate`.

## Instance state

`MessageDeduplicator.__init__` keeps three dicts plus a clock:

- `_seen` — full fingerprint → first-seen timestamp
- `_item_prefixes` — prefix fingerprint → first-seen timestamp
- `_aliases` — `nick_lower` → `(real_lower, timestamp)`
- `_last_cleanup` — seeded to `time.time()`

## Fingerprint format

`_fingerprint(data)` returns a **2-tuple**: the full fingerprint and an
optional prefix fingerprint (`None` when the message carries no item PUA).
Both have the shape `{resolved_username}\x00{content}`.

- Null-byte separator — it cannot appear after sanitization, which strips
  control characters.
- The full fingerprint's content is the message with every supplementary PUA
  codepoint (`ord >= 0xF0000`) removed, then stripped. **If that leaves an
  empty string it falls back to the raw message** — a pure-PUA item share
  would otherwise collapse to `{username}\x00` and dedup every distinct item
  a player posted.
- The prefix fingerprint is the text before the first PUA codepoint,
  right-stripped, and is only produced when that text is non-empty — an empty
  prefix would `startswith`-match everything.

## Matching strategies

`is_duplicate(data)` is the single public predicate and all four strategies
are written **inline** inside it. There are no per-strategy helper methods, so
there is no `_check_prefix` or `_check_cross_user` to cite — the whole class
surface is `is_duplicate`, `register_alias`, `_resolve_username`,
`_fingerprint` and `_cleanup`.

The user/message split that strategies 3 and 4 need is done once via
`fp.index("\x00")`. That call is unguarded, which is safe only because
`_fingerprint` always emits a separator.

### 1. Exact match

`_seen.get(fp)` hits and the entry is inside the window → duplicate.

### 2. Prefix match (item-encoded dual events)

Wynncraft fires item-encoded messages twice: once with raw PUA glyphs, once
with the decoded text. The prefix recorded from the first is scanned against
in `_item_prefixes`; an in-window entry the incoming fingerprint starts with
suppresses it. This is the one strategy with **no length threshold**.

### 3. Truncation match (soft-wrap)

Scans in-window `_seen` entries **from the same resolved user**. Both the
incoming body and the seen body must be at least `DEDUP_MIN_PREFIX_LENGTH`
(20) characters, and either may be a prefix of the other — the test is
bidirectional. Handles Wynncraft's line-wrap artifacts, where one client sees
`"hello worl"` and another sees `"hello world"`.

### 4. Cross-username alias (nickname vs real name)

Gated on the **incoming** body being at least `DEDUP_MIN_CROSS_USER_LENGTH`
(20) characters — note the asymmetry with strategy 3, which checks both
sides. Scans in-window `_seen` entries from a *different* resolved user for an
**exact** body equality. On a hit it self-heals by calling `register_alias`
for the pair, logs at debug level, and reports the duplicate.

Why: a Wynncraft nickname (set via `/nick`) shows differently to different
clients.

### 5. No match — record and accept

Not a strategy. The fallthrough records the full fingerprint in `_seen`, plus
the prefix fingerprint in `_item_prefixes` when `_fingerprint` produced one,
and returns False so the message is forwarded to the outbound queue.

## Username resolution

`_resolve_username(username)` lowercases and strips its input, looks it up in
`_aliases`, and returns the mapped real name only while that entry is younger
than `_alias_ttl`; otherwise it returns the lowercased input unchanged.

`register_alias(nickname, real_username)` is **public**. It lowercases and
strips both sides and stores the mapping only when both are non-empty *and*
differ. It has two callers: strategy 4 above, and `transform_inbound` in
`chat/processing.py`, which splits a `realName/nickname` username on the first
`/` and registers the pair before rewriting the field to the real name.

## Cleanup

`_cleanup(now)` is **lazy, not scheduled** — there is no background task. It
runs at the top of `is_duplicate` whenever `_cleanup_interval` (60 s) has
elapsed since `_last_cleanup`, and resets that clock on the way out.

It prunes three dicts with **two** different expiries: `_seen` and
`_item_prefixes` at `_window` (35 s), `_aliases` at `_alias_ttl` (30 s).

Because cleanup is piggybacked, an idle bridge can hold expired fingerprints
in memory indefinitely. That is a retention detail, not a correctness one —
every match check re-tests the window, so a stale entry can never produce a
false duplicate.

## Where it's called from

`chat/inbound.py`, inside the chat pipeline, **after** `process_inbound`
returns and only for `processed.get("type") == "guild"`. A duplicate is
logged at debug level and then silently acked with `{"status": "ok"}`, and the
handler moves to the next frame.

The ack carries no `duplicate` flag and no `detail`, so a client cannot
distinguish a suppressed duplicate from an accepted message. That is
deliberate — the sending client has nothing useful to do with the difference.

`queue`, `waitlist` and `honourary` bypass the **duplicate check** — but not
the deduplicator. `transform_inbound` runs for every inbound type with no type
branch, so any of them carrying a `real/nick` username still writes into the
shared `_aliases` table. Only `bridge` bypasses the object entirely, and for a
different reason: it is minted in the Discord bot and pushed straight onto the
outbound queue, never entering the inbound pipeline at all — it is not even a
member of `VALID_INBOUND_TYPES`.

`queue` is the easy one to miss: it is a guild message from a sender stuck in
a world queue, but only the queued sender originates a copy, so there is
nothing to collapse.

> **Why there is no code excerpt here.** A verbatim excerpt of server source
> is a line anchor by another name, and a worse one: a line number rots
> visibly and a grep catches it, whereas an excerpt rots invisibly and no
> regex can tell a correct copy from a stale one. The excerpt this section
> used to carry had drifted on five independent axes. Fenced blocks in these
> docs are for wire formats and contracts — things the mod's own code pins
> independently, so drift surfaces as a build or runtime failure — never for
> server control flow.

## Edge cases handled

- **Nicknames:** cross-user alias + truncation match
- **Item encoding:** prefix match (dual-event Wynncraft behaviour)
- **Line wrapping:** truncation match (≥20 chars)
- **Pure-PUA item shares:** the raw-message fallback in `_fingerprint`
- **Profanity censoring:** NOT handled (would require fuzzy matching) — each censored variant is distinct
- **URLs with spaces:** NOT handled at server level — vetsmod client repairs these before relay

## Edge cases NOT handled (by design)

- Messages <20 chars across users (too risky to auto-merge short messages)
- Retyped messages more than 35 s apart (outside the window — a deliberate
  floor, not a limitation: past `DEDUP_WINDOW_SECONDS` a repeat is treated as
  a genuine new message)
- Messages with same username but different case → handled via lowercase
  normalization in `_resolve_username`

## Testing considerations

No tests in the repo — temporary-server has no `tests/` directory and no test
files at all. When modifying this engine, consider:

- Exact replay (expected duplicate)
- Item-encoded dual event (expected duplicate after prefix match)
- Pure-PUA item share, twice with different items (expected **new** — this is
  what the raw-message fallback protects)
- Slow-duplicate more than 35 s later (expected new)
- Nickname cross-user (expected duplicate; alias registered)
- Short messages (<20 chars) — should NOT cross-user dedup
- Same message from different case (`USER` vs `user`) — should dedup (normalized)
