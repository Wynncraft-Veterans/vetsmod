---
name: vetsmod PUA Rank Pills
description: The Private Use Area encodings behind rank pills — Wynncraft's server pill (decode) and vetsmod's two output styles (encode), plus the PillCodec library
type: project
originSessionId: 5b71a342-5c81-487a-99a2-c1687c7d995d
---
# PUA Rank Pills — Encoding Reference

A "pill" is the rounded rank badge that sits before a username in guild chat.
Nothing about it travels as an image or a colour: the badge art is spelled out
as invisible Private Use Area codepoints that Wynncraft's resource pack renders
as glyphs, and the colour comes entirely from the `Style` applied to those
codepoints by whoever built the component.

Library: [`PillCodec`](../src/client/java/org/wynnvets/chat/PillCodec.java).
Everything below is implemented there; prefer it over hand-rolling sequences.

## Codepoint meaning is frame-scoped

There is one glyph page in Wynncraft's resource pack, and **a codepoint in it
means nothing on its own** — only its position inside a framed sequence gives
it a meaning. `U+E003` is the private-message separator, `U+E006` and `U+E002`
are guild badge glyphs, `U+E010`/`U+E011` are frame pieces — and all of them
sit inside the same `U+E000` block that spells lowercase letters inside a rank
pill. In the captured logs `U+E003` alone appears 25,432 times, none of them as
the letter `d`.

Decode from the frame inward. Never map a bare codepoint to a character.

Within a frame, three letter blocks are in use:

| Block | Range | Meaning |
|---|---|---|
| lowercase | `U+E000`–`U+E019` | `a`–`z` |
| digits | `U+E030`–`U+E039` | `0`–`9` |
| uppercase | `U+E040`–`U+E059` | `A`–`Z` |

**All three are Wynncraft's.** vetsmod borrows the uppercase block for its own
pills, but that is a convention we adopted, not an ownership boundary — the
server uses all three itself, in different sequences. Case does not tell you
who built a sequence.

## Four sequences

### 1. Server rank pill — decode only

What Wynncraft prefixes to every guild chat message. We never build these.

```
U+E062                  opener
U+CFFxx                 width marker; 0xD0000 - marker = label width in px
U+E000 + (c - 'a') ...  the label, lowercase, one glyph per letter
U+D0002                 terminator
```

The width marker exists so the background art can be sized to the label. It
tracks *rendered width*, not letter count — which is why `owner` (32) and
`chief` (30) differ despite both being five letters, and why `captain` and
`recruit` collide at 42.

Observed values:

| Rank | Marker | Width |
|---|---|---|
| owner | `U+CFFE0` | 32 |
| chief | `U+CFFE2` | 30 |
| strategist | `U+CFFC4` | 60 |
| captain | `U+CFFD6` | 42 |
| recruiter | `U+CFFCA` | 54 |
| recruit | `U+CFFD6` | 42 |

> **The captain/recruit marker collision is not a key collision.** Their full
> sequences differ in the letter glyphs, so both `RANK_MAP` entries are live
> and distinct. A comment in `ChatLogger` claimed for a while that the second
> `put()` overwrote the first; it does not.

`PillCodec.decodeServerPill()` scans for the opener rather than anchoring at
position 0 — the rank indicator handed to the chat rewriter still carries the
guild badge prepend and the id run below, whose glyphs come from all three
letter blocks. Anchoring on `U+E062` is what keeps them from decoding as
garbage.

Every rank pill in the captured logs spells its label in **lowercase**. The
decoder accepts uppercase and digits anyway and folds them to lowercase —
costs nothing, and the alternative failure mode is silently returning `null`
for a rank Wynncraft chose to spell differently.

### 2. Server id run — not a pill, but it is why capitals show up

Sits immediately *before* the rank pill in guild chat. This is the answer to
"does the server ever send capitals": **yes, but never as a rank label.**

```
U+E060                              opener
(U+CFFFF, one hex digit glyph) ...  9–10 chars from the digit and uppercase
                                    blocks — '0'-'9' and 'A'-'F' only
```

`U+CFFFF` is a 1px negative advance (`0xD0000 - 0xCFFFF = 1`) kerning the run
tight. Decoded samples: `B42BE8D4B`, `CDB0D468CD` — stable per sender, so it
reads as a per-player identifier rather than anything about rank.

Because only hex digits appear, the uppercase glyphs seen from the server are
always `A`–`F`. That is the whole of the server's uppercase usage in captured
traffic.

### 3. Remote pill — light on dark

`PillCodec.encodeRemote(label) -> String`

A light label on a dark rounded field. Used for anything that reached the
player over vetsmod's WebSocket rather than through Wynncraft's guild channel:
bridge, honourary, and queue messages.

```
U+E06B                  frame open
U+E040 + (c - 'A') ...  the label, uppercase
U+E06C                  frame close
```

Returns a `String`, not a `Component`, because the whole sequence is one
colour — which is what lets [`PillFormatter`](../src/client/java/org/wynnvets/chat/PillFormatter.java)
pick that colour per message (flat, or the supporter-gradient marker).

Input already containing PUA is returned unchanged, so it's safe to call on a
rank that arrived pre-encoded (waitlist and honourary self-messages do).

### 4. Local pill — dark on light

`PillCodec.encodeLocal(label, frameStyle) -> MutableComponent`

A dark label on a light rounded field, matching Wynncraft's own guild pill and
the `[Vetsmod]` pill in `/wv help`. Used when rewriting chat that genuinely
arrived through Wynncraft's guild channel, so the rewrite doesn't visually
announce itself as mod output.

```
U+E010 U+2064                        frame open
(U+E00F U+E012, U+E040 + (c-'A'))... one frame segment per letter
U+E011                               frame close
```

One frame segment precedes each letter, widening the field by one character
before the letter is drawn over it.

Returns a `Component` rather than a `String` because it's inherently two-tone:
the frame takes the caller's colour, the letters are always black. A bare PUA
string carries no seam between the two, so this style also can't pass
pre-encoded input through the way `encodeRemote` does.

`U+E00F` and `U+E012` are frame pieces here while being `p` and `s` inside a
server rank pill — the frame-scoping rule at the top of this doc, in practice.

## Evidence

Everything above was checked against five captured `vetsmod/debug.log` files
(74,821 lines) from live Prism instances, not inferred from the source:

| Claim | What the logs show |
|---|---|
| Rank pill labels are lowercase | 10/10 pills; blocks inside a pill are lowercase + width marker only |
| Server sends uppercase | 62 occurrences — all inside the `U+E060` hex id run, all `A`–`F` |
| Digit block is `U+E030`–`U+E039` | `0`,`2`,`4`,`6`,`8` observed in id runs |
| `U+E000` block isn't only letters | `U+E003` (separator) 25,432 times |
| Id run precedes the pill | opener at col 104, pill at col 124 in the sampled line |

Re-run the survey against fresh logs if Wynncraft changes its chat format; the
scripts are throwaway but the method is: extract maximal PUA runs, split on
frame openers, classify each glyph by block.

## Fonts — a correction

Both vetsmod styles render in the **default font**, relying on Wynncraft's
resource pack to supply the PUA glyphs. No font override is applied anywhere on
the pill path.

`ChatUtils.CHAT_PREFIX_STYLE` (the named `chat/prefix` font) is used only for
badges and continuation markers. The server's own pill uses `banner/pill`,
which `ServerGuildChatRewriter` does reference when it extracts pill fragments
from an incoming component tree. Several older docstrings loosely attribute
*vetsmod's* pills to `chat/prefix`; that isn't what the code does.

## Where decoding happens

[`ServerGuildChatRewriter.decodeRawRank`](../src/client/java/org/wynnvets/chat/rewriter/ServerGuildChatRewriter.java)
tries two things in order:

1. **`ChatLogger.rankMap()`** — exact whole-sequence match against six known
   pills. Fast and impossible to fool.
2. **`PillCodec.decodeServerPill()`** — structural decode, covering ranks the
   table has never seen.

The fallback is what stops a future Wynncraft rank from silently losing its
rewrite. It's safe because whatever comes back still passes through
[`RankDisplayMap`](../src/client/java/org/wynnvets/chat/RankDisplayMap.java),
which maps an unrecognised rank to itself — so an unknown pill ends up
rendering exactly as the server sent it.

Note `RANK_MAP`'s first entry is labelled `Owner` but the label is not what the
glyphs say — decode the sequence, don't trust the label. (It spelled `owner`
while being labelled `Chief` for a while; both route to "Steward", so nothing
broke, but it would have if Chief and Owner ever got separate display labels.)

## Testing

`PillCodecTest` covers all four entry points, `encodeLocal` included. There is
no rule against `net.minecraft` imports in the harness — [CLAUDE.md](CLAUDE.md)
says the opposite, and `build.gradle` deliberately puts the client compile
classpath on the test source set so tests can build real `Component`/`Style`
objects. (An earlier version of this paragraph claimed such a rule and
attributed it to CLAUDE.md; that attribution was never true.) The limit is
runtime, not imports: a test cannot boot Minecraft, and Wynntils is
`modCompileOnly` and absent at test runtime.

The test writes every codepoint numerically rather than pasting the glyphs. The
sequences are invisible in an editor, and reading them back from `PillCodec`'s
own private fields would make the test pass through any renumbering of a format
Wynncraft owns.

## Related

- [vetsmod_chat_pipeline.md](vetsmod_chat_pipeline.md) — where pills sit in the
  rewriter chain
- [vetsmod_rendering.md](vetsmod_rendering.md) — supporter gradients and the
  animation marker colour
