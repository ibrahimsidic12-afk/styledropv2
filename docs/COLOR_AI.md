# COLOR_AI.md — Colour Harmony Engine for Styledrop v2

**Agent #9 deliverable** · module: [`color_engine.py`](./color_engine.py) · tests: [`test_color_engine.py`](./test_color_engine.py)

A deterministic, explainable colour-theory engine that tells the Styledrop outfit
builder whether a set of garment colours **works**, **clashes**, or is a **classic
combination** — and suggests companion hexes that a stylist would actually use.

Pure Python standard library. No numpy, no third-party packages, no network calls.
Every score is reproducible: same hexes in → same score out, always.

---

## 0. Why this exists in Styledrop

The app's wardrobe entity already stores colour per item:

```kotlin
// app/src/main/java/com/example/models/WardrobeItem.kt
val color: String,                      // primary garment colour, hex
val secondaryColor: String = "",        // optional second colour (defaults to blank)
val category: String,                   // Tops, Bottoms, Shoes, Outerwear, ...
val type: String, val style: String, val fit: String, val pattern: String
```

`AppConstants.colorPreferences` currently offers only eight flat options
(`Any, Black, White, Gray, Beige, Navy, Brown, Olive`) and the AI generator treats
colour as a single dropdown. This engine replaces that blind spot with real colour
theory: **the outfit builder can now reason about colour**, not just filter by it.

---

## 1. Algorithms — colour theory, implemented properly

### 1.1 Colour space pipeline

```
HEX "#1B2A4A"  →  RGB (27, 42, 74)  →  HSL (215.0°, 0.47, 0.20)  →  hue geometry
```

Conversions use the standard CSS/HSL model (max/min formulation), not a naive
approximation:

- `hex_to_rgb` / `rgb_to_hex` — validated, tolerant of `#RGB`, `RRGGBB`, whitespace
- `rgb_to_hsl` — hue in degrees 0–360, saturation & lightness normalised 0–1
- `hsl_to_rgb` — exact inverse (verified lossless over a 10-colour round-trip)
- `hue_delta(a, b)` — **circular** distance, 0–180° (350° vs 10° = 20°, not 340°)
- `relative_luminance` / `contrast_ratio` — WCAG luminance, so "value contrast" is
  judged on perceived brightness, not on HSL lightness alone

### 1.2 The six harmony relations

Every pair of garments is classified by circular hue distance `ΔH`, guarded by a
neutral check that runs **first** (neutrals are universally compatible, so they must
never be scored as clashes):

| Relation | Condition | Where it comes from | Base score |
|---|---|---|---|
| **neutral + neutral** | both achromatic | two neutrals never fight | 88 |
| **neutral + accent** | exactly one achromatic | neutral grounds the accent | 90 |
| **monochromatic** | `ΔH ≤ 12°` and `ΔL ≥ 0.10` | same hue family, different value | 85 |
| **tonal repeat** | `ΔH ≤ 12°` and `ΔL < 0.10` | same colour twice — safe but flat | 78 |
| **analogous** | `12° < ΔH ≤ 48°` | neighbouring hues on the wheel | 88 |
| **triadic** | `\|ΔH − 120°\| ≤ 12°` | three hues evenly spaced | 90 |
| **split-complementary** | `\|ΔH − 150°\| ≤ 10°` | a hue + the neighbours of its opposite | 86 |
| **complementary** | `\|ΔH − 180°\| ≤ 15°` | opposite hues, maximum contrast | 92 |
| **high-contrast near-opposite** | `ΔH ≥ 160°` (outside ±15° of 180°) | bold, slightly uneasy | 72 |
| **clash zone** | `48° < ΔH < 108°` | too far to blend, too close to contrast | 30 |
| **off-harmony** | anything else | no clear relationship | 60 |

**Neutral is defined structurally, not by a lookup list:**

```python
s <= 0.12  or  l <= 0.09  or  l >= 0.90      # achromatic, near-black, or near-white
```

This is what makes black/white/grey/ivory/charcoal behave correctly in every
combination — the classic and most common real-world outfit case.

**Loud-clash flag:** a clash-zone pair where *both* colours exceed saturation `0.62`
loses a further 8 points and is flagged `loud_clash = True`. Red + chartreuse is a
loud clash; muted terracotta + olive is not.

### 1.3 The 0–100 outfit score

For an outfit of *n* garment colours, all `n(n−1)/2` pairs are classified, then four
weighted components are combined and a clash penalty is subtracted:

| Component | Weight | What it measures |
|---|---|---|
| `harmony` | **0.55** | mean pairwise relation quality (the base scores above) |
| `lightness_contrast` | **0.20** | value spread across the whole outfit — dark↔light separation (40% HSL `ΔL`, 15% WCAG luminance spread) |
| `saturation_balance` | **0.15** | one loud colour + supporting cast good; three loud colours penalised 18 each; all-muted capped at 82 |
| `coherence` | **0.10** | % of pairs landing on a *recognised* relation (not clash / off-harmony) |
| `clash_penalty` | − | `22 × clash_pairs + 10 × loud_clashes`, **capped at 40** |

```
raw   = 0.55·harmony + 0.20·lightness + 0.15·saturation + 0.10·coherence
score = clamp(raw − clash_penalty, 0, 100)
```

**Verdict banding** (what the user actually reads):

| Condition | Verdict string |
|---|---|
| any clash + score ≥ 55 | `"Risky pairing"` |
| any clash + score < 55 | `"These colours clash"` |
| score ≥ 90 | `"Classic combination"` |
| score ≥ 78 | `"Harmonious — easy to wear"` |
| score ≥ 64 | `"Works, but could be tightened"` |
| score ≥ 50 | `"Mismatched"` |
| else | `"These colours clash"` |

The engine also emits a plain-English `summary` naming the dominant relation and the
specific defect ("Navy + Camel… balanced hue, value and saturation" /
"Red + Vivid Chartreuse sit 78.6° apart, right in the clash wedge").

### 1.4 Companion suggestions (theory → shoppable hexes)

`suggest_companions(hex)` derives candidates **from the base hue** rather than picking
from a fixed palette, and clamps saturation/lightness into a wearable band
(`0.10 ≤ S ≤ 0.95`, `0.18 ≤ L ≤ 0.88`) so nothing comes back neon-on-neon:

Order for a chromatic base — neutral anchor (35°, opposite value) → **+180°
complementary** → **±30° analogous** → **±120° triadic** → **+150°
split-complementary** → tonal (same H, lighter). For a neutral base it returns
*accents* instead (one warm, one cool, one camel/tonal) — because a grey outfit needs
one accent, never hue mathematics.

---

## 2. Input contract

### Python API

```python
from color_engine import analyze_outfit, harmony_score, suggest_companions, analyze_wardrobe

# 2+ garment hexes (order does not matter; blanks are ignored)
analysis = analyze_outfit(["#1B2A4A", "#C19A6B", "#FFFFFF"])
analysis.score          # 94.4
analysis.verdict        # "Classic combination"
analysis.dominant_relation
analysis.summary        # human sentence
analysis.to_dict()      # fully JSON-serialisable

harmony_score(["#FF0000", "#B0FF00"])   # 7.1  (just the number)
suggest_companions("#1B2A4A", count=6)  # [{hex, name, relation, why}, ...]
```

### Wardrobe-level entry point (matches the app's entity)

```python
analyze_wardrobe([
    {"id": "1", "category": "Tops",    "color": "#1B2A4A", "secondaryColor": ""},
    {"id": "2", "category": "Bottoms", "color": "#C19A6B", "secondaryColor": ""},
    ...
])
```

Rows with an empty/invalid `color` are skipped (never crash), and `secondaryColor`
defaulting to `""` — the Room entity default — is handled explicitly.

---

## 3. Output contract

| Field | Type | Example |
|---|---|---|
| `score` | float 0–100 | `94.4` |
| `verdict` | str | `"Classic combination"` |
| `summary` | str | `"Complementary outfit — balanced hue, value and saturation"` |
| `dominant_relation` | str | `"complementary"` |
| `clash_count` | int | `0` |
| `components` | dict | `{harmony, lightness_contrast, saturation_balance, coherence, clash_penalty}` |
| `pairs` | list | per-pair `{a, b, hue_delta, relation, score, loud_clash, explanation}` |
| `companions` | dict | anchor hex → list of `{hex, name, relation, why}` |
| `names` | list | `["Navy", "Camel", "White"]` — stylist-readable labels |

---

## 4. Verified test output

Run `python3 color_engine.py` for the demo, `python3 test_color_engine.py` for the suite.
Real, unedited results from the demo run:

| Case | Colours | Score | Verdict |
|---|---|---|---|
| Navy + Camel (classic tailored) | `#1B2A4A` `#C19A6B` | **94.4** | Classic combination (complementary, ΔH 171.9°) |
| Black + White + Red | `#000000` `#FFFFFF` `#FF0000` | **94.1** | Classic combination (neutral + accent) |
| Warm neutral layering | `#FFFDD0` `#C19A6B` `#8B4513` | **93.6** | Classic combination |
| Monochromatic blues | `#2C3E60` `#3B5998` `#87CEEB` | **92.6** | Classic combination |
| Analogous blues/teals | `#4169E1` `#008080` | **89.7** | Harmonious — easy to wear |
| Triadic primaries | `#FF0000` `#0000FF` `#228B22` | **87.9** | Harmonious — easy to wear |
| Red + Green (complementary) | `#FF0000` `#228B22` | **87.3** | Harmonious |
| Single item | `#1B2A4A` | **70.0** | Single item |
| **Two loud clashes** | `#FF0000` `#B0FF00` | **7.1** | **These colours clash** (ΔH 78.6°, loud, penalty −32.0) |

The clash case decomposes exactly as designed:
`harmony 22.0 · lightness 60.0 · sat 100.0 · coherence 0.0 · clash penalty −32.0`.

---

## 5. Integration call site (Kotlin port)

The formulas are pure arithmetic, so they port to Kotlin 1:1. Suggested home:
`app/src/main/java/com/example/ui/OutfitScorer.kt`, called from
`WardrobeViewModel` / `SavedOutfitsScreen` when a look is assembled.

```kotlin
// Requires: garment colours as hex strings, e.g. item.color (+ item.secondaryColor)
val analysis = ColorHarmony.analyze(listOf(top.color, bottom.color, shoes.color))

when {
    analysis.score < 50 -> showBanner("These colours clash", analysis.summary)
    analysis.score >= 90 -> showBanner("Classic combination ✨", analysis.summary)
    else -> showScore(analysis.score, analysis.verdict)
}

// Surface companion hexes on the Add-Item / AI-generator screen
ColorHarmony.suggestCompanions(anchorColor).forEach { suggestion ->
    ColorSwatch(suggestion.hex, label = suggestion.name)   // "Complements your Navy"
}
```

The portable primitives to mirror: `rgbToHsl`, `hslToRgb`, `hueDelta`,
`isNeutral`, `classifyPair`, `analyzeOutfit` — same constants
(`NEUTRAL_SAT_MAX = 0.12`, `HIGH_SAT = 0.62`, clash wedge `48–108°`, weights
`0.55/0.20/0.15/0.10`, penalty `22` per clash capped at `40`).

---

## 6. Design guarantees

1. **Deterministic** — no randomness, no clock, no network. Verified by asserting two
   identical calls produce identical dicts.
2. **Order-independent** — swapping the colour order gives the same score (asserted).
3. **Bounded** — score is clamped to `[0, 100]` for every input, including repeats.
4. **Explainable** — every score exposes its components, and every pair carries a
   colour-theory label plus a one-line rationale.
5. **Neutral-safe** — black/white/grey can never be labelled a clash (asserted).
6. **App-safe** — blank `secondaryColor` and rows with invalid hex are ignored, not fatal.
