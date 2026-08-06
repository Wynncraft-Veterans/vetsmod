---
name: vetsmod Rendering System
description: Non-legacy rendering — territory lines, nametag animator, animated gradient sequence, gradient text builder, shader color palette
type: project
originSessionId: dc63f47a-2d15-4f8d-9b6a-41d3049f0cc2
---
# vetsmod Rendering System

Package: [org.wynnvets.rendering](../src/client/java/org/wynnvets/rendering/). 6 files across three sub-packages.

## 1. Territory subpackage

### TerritoryLineRenderer
[TerritoryLineRenderer](../src/client/java/org/wynnvets/rendering/territory/TerritoryLineRenderer.java)

Draws territory boundary outlines using Minecraft's Gizmos API.
- Green = player inside territory
- Red = player outside territory
- Rectangles at 4-block vertical intervals around player Y
- Culling at render distance × 15 blocks (max 16 chunks)

Credit: inspired by avomod2's `TerritoryOutlineRenderer` by Avicia (Yarn→Mojang mapping adapted).

### TerritoryLineManager
[TerritoryLineManager](../src/client/java/org/wynnvets/rendering/territory/TerritoryLineManager.java)

- Toggle state: `ConcurrentHashMap<String, Boolean> activeLines`
- Aliases (`TerritoryLineManager.LINE_ALIASES`, five entries): `church` → Forest of Eyes, `scrap` → Corkus Sea Cove, `bat` → Royal Barracks, `hegea` → Fort Hegea, `lighthouse` → Contested District
- Fetches territory data from `https://api.wynncraft.com/v3/guild/list/territory` on activation
- Normalizes bounds `[startX, startZ, endX, endZ]` with min/max

Used by the `/wv line <church|scrap|bat|hegea|lighthouse>` command.

## 2. Nametag subpackage

### NametagAnimator
[NametagAnimator](../src/client/java/org/wynnvets/rendering/nametag/NametagAnimator.java)

Animated gradient for supporter usernames in nametags.

Animation mechanics:
- Cycle: 3000ms ping-pong
- Lighten factor: 0.65 — blends toward light lavender (255, 225, 255)
- Per-character phase: `charPhase = idx / (usernameLen - 1)`, then `phase = (charPhase + time) % 1.0`
- Wave formula matches `AnimatedGradientSequence`

Features:
- Case-insensitive username location (searches from END for last occurrence)
- Handles Wynncraft nametag format: `[colour1][symbol] [colour2][prefix] username` with PUA chars
- Segment boundary handling for split username parts
- Called every render frame from `NametagMixin` — no external tick

## 3. Colors subpackage

### AnimatedGradientSequence
[AnimatedGradientSequence](../src/client/java/org/wynnvets/rendering/colors/AnimatedGradientSequence.java)

`FormattedCharSequence` wrapper that applies animated two-colour gradient to chars with marker colour.

Marker colors (sentinels; replaced at render time):
- `MARKER_COLOR = 0x00DEAD` — normal animation
- `GREY_MARKER_COLOR = 0x00DEAF` — grey animation

Defaults:
- Start: Dark Aqua `0x55FFFF`
- End: Light aqua `0xAADDFF`
- Grey: `0x888888 → 0xBBBBBB`
- Cycle: 3000ms

`ThreadLocal<AnimConfig>` for context-safe setup/teardown. Usage:
```java
AnimatedGradientSequence.beginAnimation(startColor, endColor, cycleMs);
try {
    sendMessage(component.withColor(MARKER_COLOR));
} finally {
    AnimatedGradientSequence.endAnimation();
}
```

Ping-pong formula:
```
phase = (charPhase + time) % 1.0
t = phase < 0.5 ? phase * 2.0 : 2.0 - phase * 2.0  // oscillates 0→1→0
color = start + (end - start) * t                   // linear RGB interp
```

`AnimatedChatMixin` picks up the ThreadLocal config at insert-time and wraps newly-inserted lines.

### GradientTextBuilder
[GradientTextBuilder](../src/client/java/org/wynnvets/rendering/colors/GradientTextBuilder.java)

Static utility for static (non-animated) gradient text components.
- Splits into code points, interpolates RGB per character
- PUA / supplementary-plane glyphs are grouped (preserves composite badge glyphs); colour applied at group midpoint
- Handles surrogate pairs properly
- Accepts `baseStyle` to inherit non-colour properties (font, etc.)
- Single code point → uses start colour directly

### ShaderColorPalette
[ShaderColorPalette](../src/client/java/org/wynnvets/rendering/colors/ShaderColorPalette.java)

Colour constants including Wynncraft resource-pack shader sentinel colours.

Gradient/util:
- `AQUA = 0xC7FFE5`
- `DARK_AQUA = 0x55FFFF`

Resource-pack shader sentinels (GPU-animated by server pack shader):
- `RAINBOW = 0x00F000` — rainbow cycle
- `GRADIENT = 0x00F004` — #f56217 → #0b486b
- `FADE = 0x00F008` — #5af082 → black
- `BLINK = 0x00F00C` — #e63232 ↔ black
- `GRADIENT_2 = 0x00F010` — #560505 → #8a0303
- `SHINE = 0x00F014` — #a3cc52 → #ffffd2

## 4. Marker-colour handoff pattern

Three-stage handoff used across the codebase:
1. **Set intent:** Caller calls `AnimatedGradientSequence.beginAnimation(...)` to configure colours + cycle.
2. **Mark chars:** Caller sets `MARKER_COLOR` or `GREY_MARKER_COLOR` on components that should animate. The marker is a unique-looking sentinel that won't appear in real content.
3. **Replace at render:** `AnimatedChatMixin` wraps new lines in `AnimatedGradientSequence`, which substitutes the actual animated colour per-char per-frame when a marker is hit.

This lets e.g. `PillFormatter` compose components in one pass and have animation applied without coupling composition to the render loop.

## 5. Integration touch points

| Subsystem | Uses |
|-----------|------|
| `PillFormatter` | Marker colour + AnimatedGradientSequence for supporter pills |
| `ChatUtils.dispatchAnimatedChat` | Begin/end animation context around a dispatch |
| `ServerGuildChatRewriter` | Marker colour on server-rendered pill backgrounds |
| `NametagMixin` | `NametagAnimator.tryAnimate()` |
| `ListFetcher` / `WorldListFetcher` | `GradientTextBuilder` for static supporter gradient on list entries |
| `EncourageUpdateRewriter` | `ComponentUtils.makeRainbowStyle()` for up-to-date case |
| `/wv line` | `TerritoryLineRenderer` + `TerritoryLineManager` |
