---
name: vetsmod Rendering System
description: Non-legacy rendering — territory lines, nametag animator, animated gradient sequence, gradient text builder, shader color palette, and the transferable render-pipeline lessons
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

Defaults. All four resolve through `effectiveDefaultStart()` / `effectiveDefaultEnd()` / `effectiveGreyStart()` / `effectiveGreyEnd()`, which swap in the `CV_*` pairs when `colorBlindMode` is on — but at different times: the grey pair is read per-frame inside `accept()`, while the default pair is read once by the caller and frozen into the sequence's `startColor`/`endColor` at construction, so toggling `colorBlindMode` only reaches already-wrapped lines through the grey path.
- Start: Dark Aqua `0x55FFFF` — `CV_DEFAULT_START_COLOR = 0x6699BB`
- End: Light aqua `0xAADDFF` — `CV_DEFAULT_END_COLOR = 0xDDF0FF`
- Grey: `0x888888 → 0xBBBBBB` — `CV_GREY_START_COLOR = 0x666666`, `CV_GREY_END_COLOR = 0xCCCCCC`
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

`AnimatedChatMixin` wraps newly-inserted lines at insert time. It does **not** read the ThreadLocal config — it builds every wrapper from the `effective*()` defaults above, so custom colours handed to `beginAnimation` are dropped. The one real caller, `ChatUtils.dispatchAnimatedChat`, passes exactly those defaults, which is why nothing looks wrong.

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
3. **Replace at render:** `AnimatedChatMixin` wraps new lines in `AnimatedGradientSequence`, which substitutes the actual animated colour per-char per-frame when a marker is hit — using the `effective*()` defaults, not whatever stage 1 configured.

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

The `mwe/anni/` render components — `AnniZoneLineRenderer` (a Gizmos peer of `TerritoryLineRenderer`), `ScrollSpotMarkerProvider`, the outline trio (`AnniOutlineTicker`/`Registry`/`Palette`), the boss-bar classes, and the component renderers under `mwe/anni/render/` — are documented in [vetsmod_mwe_anni.md](vetsmod_mwe_anni.md), beside the subsystem they belong to. What generalises out of building them is §6 below.

## 6. Render-pipeline lessons

Collected while building the `mwe/anni/` render stack; they apply to any new render mixin or per-tick render component, not just to anni. Mixin-authoring constraints and per-mixin injection points live in [vetsmod_mixins.md](vetsmod_mixins.md); Wynntils-side API behaviour lives in [project_wynntils.md](project_wynntils.md).

1. **Wynncraft's resource pack overrides specific vanilla sprites.** `boss_bar/pink_background.png` is fully transparent (so PINK/PROGRESS bars render as text-only HUD strips). Other vanilla GUI sprites *may* be similarly overridden; if a vanilla render component goes unexpectedly invisible, blame the resource pack first. Stick to `{PURPLE, RED, GREEN, YELLOW, BLUE, WHITE}` for any new boss-bar colour and don't assume vanilla sprite paths are intact.

2. **`isOutlineSuppressionActive()` / `isAggressiveActive()` are the cheap public flags** for "are we doing the anni-active rendering thing right now". Each is a `volatile boolean` set per tick by its ticker — safe to query from render-thread mixins without extra locking. Expose a parallel flag from any new ticker with its own gate semantics.

3. **`Gizmos.circle` exists.** The zone-line renderer uses it. Centre is `Vec3(disc.x, snappedY, disc.z)` — the disc geometry is 2D in `AnniZone` so Y is unspecified; snapping to multiples of `Y_STEP` lets the cylinder-cage stack stay anchored as the player moves. The rest of `net.minecraft.gizmos.Gizmos`' public static surface: `cuboid` (four overloads), `line`, `arrow`, `rect`, `point`, `billboardTextOverBlock`, `billboardTextOverMob`, `billboardText`, plus `addGizmo` and `withCollector`. Nothing special is needed to reach them — the whole project compiles against `loom.officialMojangMappings()` under Loom `1.15-SNAPSHOT`, so `net.minecraft.gizmos.*` resolves by plain import like any other vanilla class.

4. **MarkerProvider lifecycle.** `Models.Marker.registerMarkerProvider(...)` is one-shot at vetsmod load. The provider's `isEnabled()` is what gates per-tick visibility — do NOT call `registerMarkerProvider` from a snapshot listener or reconnect handler (the registration list would grow on every reconnect). `ScrollSpotMarkerProvider.registerWithWynntils()` is idempotent via a static flag. The flag guards three things, not one: the `registerMarkerProvider` call, an `AnniSnapshotCache.addListener` subscription, and a priming `onSnapshot(AnniSnapshotCache.latest())` so a marker appears immediately rather than at the next push. The listener subscription is what makes the flag matter — a second call would double-subscribe.

5. **Wynntils `BeaconBeamFeature` is unconditional.** Every entry in `Models.Marker.getAllMarkers()` gets a beacon beam — there's no per-marker "skip" path. `marker.beaconColor()` is called and `.withAlpha(float).asInt()` invoked on it: null → render-thread NPE → "Pose stack not empty" next-frame crash; `CustomColor.NONE` → fallback to user-config beacon (still visible); custom 0-alpha → Wynntils overrides alpha back to opaque before render. Conclusion: if you register a MarkerProvider, you ship a beacon. Pick a colour you can live with.
