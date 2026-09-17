# CLASSIFIER.md — StyleDrop v2 Wardrobe Classifier (Agent #11)

Automatic garment categorization for the StyleDrop Android app. Given a clothing
photo (URL or local path) plus optional text hints, the classifier predicts the
exact fields that `AddItemScreen.kt` needs — using **only** values that exist in
the app's own vocabulary.

- **Repo analyzed:** https://github.com/ibrahimsidic12-afk/styledropv2
- **Commit analyzed:** `a53013b` — *feat(ui): update visual identity to ivory theme* (2026-09-17)
- **Stack confirmed by clone:** **NOT Dart/Flutter.** It is a native **Android / Kotlin + Jetpack Compose** app (Room, Coil, Retrofit→Gemini). The vocabulary lives in `app/src/main/java/com/example/models/` — `ItemCategory` in `WardrobeItem.kt` and `object AppConstants` in `AppConstants.kt`.
- **Deliverables:** `classifier.py` (Python stdlib + Pillow, runnable) + this document.
- **Verified:** `python3 classifier.py` ran end-to-end this session (exit 0); every emitted value passed the vocabulary gate; URL input and text-hint overrides tested live.

---

## 1. Vocabulary mapping — copied verbatim from the codebase

Every value below was read directly from the cloned repo (not invented). The
Python classifier mirrors these lists and **validates its output against them
before returning** — an out-of-vocabulary value raises `ValueError` instead of
reaching the app.

### 1.1 `ItemCategory` — source: `models/WardrobeItem.kt` (`object ItemCategory`)

| Kotlin constant | String value (stored in Room) | Emoji (via `ItemCategory.emoji()`) |
|---|---|---|
| `TOP` | `Tops` | 👕 |
| `BOTTOM` | `Bottoms` | 👖 |
| `SHOES` | `Shoes` | 👟 |
| `OUTERWEAR` | `Outerwear` | 🧥 |
| `ACCESSORY` | `Accessories` | 🧢 |
| `BAG` | `Bags` | 👜 |
| `WATCH` | `Watches` | ⌚ |
| `JEWELRY` | `Jewelry` | 💍 |

`ItemCategory.all` = the 8 string values above, in that order.

### 1.2 `AppConstants` — source: `models/AppConstants.kt`

| List | Values (exact casing) | Used by classifier for |
|---|---|---|
| `patterns` | `Plain, Striped, Graphic, Checked, Camo` | **output `pattern`** — closed list |
| `seasons` | `All Season, Summer, Winter, Spring/Fall` | **output `season`** — closed list |
| `colorPreferences` | `Any, Black, White, Gray, Beige, Navy, Brown, Olive` | **output `colorName`** (`Any` excluded — it is a preference, not a color) |
| `shoePreferences` | `Any, Sneakers, Boots, Loafers, Sandals, Running Shoes` | `type` nouns for the `Shoes` category |
| `fits` | `Oversized, Slim, Regular, Relaxed` | prefix of composed `type` labels |
| `styles` | `Streetwear … Grunge` (12) | not predicted — user-set only |
| `occasions` | `School … Formal` (11) | not predicted — user-set only |

### 1.3 Notes on vocabulary fidelity

- **`category`, `pattern`, `season`, `colorName` are closed lists** — the classifier can only emit values from §1.1–1.2, enforced by the `validate()` gate.
- **`type` is free text in the app** (`AddItemScreen` uses an `OutlinedTextField`, not a dropdown). The classifier still constrains itself to canonical labels: an optional fit prefix from `AppConstants.fits` + a garment noun from a per-category lexicon (e.g. `Oversized T-Shirt`, `Slim Jeans`, `Running Shoes`). The shoe nouns are exactly `AppConstants.shoePreferences` minus `Any`.
- **`color` is stored as free text** in `WardrobeItem` (e.g. "Black"), and the existing dropdowns only offer the 7 color names. The classifier therefore returns **both**: `color` = dominant **hex** (`#RRGGBB`, useful for the future swatch UI / color-harmony engine) and `colorName` = the nearest `AppConstants.colorPreferences` entry. A pure-hex value would NOT be a legal dropdown value today — `colorName` is the field to write into the `color` column if you want strict dropdown compatibility; keep the hex in `secondaryColor`-style storage or a future swatch field.
- Fields the classifier intentionally does NOT touch: `style`, `fit` (as a separate field), `brand`, `imageUrl`, `timesWorn`, `isFavoriteItem`, `lastWorn` — these have no visual heuristic or are user-owned.

---

## 2. Architecture

```
                    ┌─────────────────────────────────────────────┐
 image URL/path ──► │ load_image()   urllib (URL) / PIL open (file)│
                    │  → RGB, resized to 128×128 analysis space    │
                    └──────────────────┬──────────────────────────┘
                                       ▼
                    ┌─────────────────────────────────────────────┐
                    │ build_mask()                                 │
                    │  background = 4 corner colors                │
                    │  subject = pixels farther than tol (42, or   │
                    │  18 on a 2nd pass for white-on-light)        │
                    │  fallback = full-frame mask                  │
                    └──────┬───────────────┬──────────────────────┘
                           ▼               ▼
        ┌──────────────────────┐   ┌───────────────────────────────┐
        │ silhouette_features()│   │ dominant_color()              │
        │  aspect, taper,      │   │  16-bit bucket histogram over │
        │  waist_pinch,        │   │  subject pixels → mode bucket │
        │  legs_runs (2 =      │   │  → averaged exact hex         │
        │  trouser legs)       │   │ nearest_color_name() → 7 names│
        └──────────┬───────────┘   └──────────────┬────────────────┘
                   ▼                              ▼
        ┌──────────────────────┐   ┌───────────────────────────────┐
        │ score_categories()   │   │ classify_pattern()            │
        │  geometry points per │   │  bg flattened to subject mean │
        │  category + metallic │   │  FIND_EDGES row/col energy    │
        │  color priors        │   │  peak regularity test →       │
        └──────────┬───────────┘   │  Plain/Striped/Checked/Camo/  │
                   │               │  Graphic                      │
                   ▼               └──────────────┬────────────────┘
        ┌──────────────────────┐                  │
        │ text-hint override   │   ┌──────────────▼────────────────┐
        │  HINT_KEYWORDS map   │   │ classify_season()             │
        │  (hint > vision)     │   │  category + type + brightness │
        └──────────┬───────────┘   └──────────────┬────────────────┘
                   └───────────────┬───────────────┘
                                   ▼
                    ┌─────────────────────────────────────────────┐
                    │ validate()  — every value ∈ repo vocabulary  │
                    │  else ValueError (hard gate)                 │
                    └─────────────────────────────────────────────┘
```

### 2.1 Category heuristics (`score_categories`)

Points are summed per category; highest wins. The strongest signals:

| Signal | Rule | Points |
|---|---|---|
| Two separate leg runs in the lower third + tall | → `Bottoms` (trousers/jeans pair) | +3.0 |
| Wide, low object (aspect < 0.75) | → `Shoes` | +2.2 |
| Square-ish lay-flat (0.75 ≤ aspect < 1.30) | → `Tops`, then `Outerwear` | +2.0 / +1.5–0.8 |
| Tall single column (≥ 1.05) | → `Tops`/`Outerwear`/`Bottoms`/scarves | +0.6–1.2 |
| Sparse subject inside its bbox (fill < 0.42) | thin object → `Accessories`/`Watches`/`Jewelry` | +1.0–1.2 |
| Very tall & thin (aspect > 2.6) | chain/necklace → `Jewelry`, `Watches` | +0.6–1.0 |
| Metallic mid-gray dominant color (S<0.25, 0.40<L<0.75) | → `Watches`, `Jewelry` | +0.6 each |
| Multi-hue fabric (≥3 hue families) | → `Outerwear` bonus (camo jacket) | +0.3 |

A text hint (§2.4) overrides the visual winner with 0.95 confidence.

### 2.2 Pattern heuristics (`classify_pattern`)

Applied to the **background-flattened** subject crop (background pixels are
replaced with the subject's mean luminance so silhouette borders never count as
texture). Statistics: `FIND_EDGES` energy per row/column, peak positions, and
peak-spacing regularity; plus quantized color count and hue-family spread.

| Rule (ordered) | Output | Conf. |
|---|---|---|
| ≥6 regular periodic peaks on ONE axis only | `Striped` | 0.80 |
| ≥4 regular peaks on BOTH axes + ≤2 hue families | `Checked` | 0.72 |
| No periodic structure + (≥3 hue families ≥3 colors OR ≥4 major colors) + busy edges | `Camo` | 0.74 |
| Busy edges + few hues + no periodicity | `Graphic` | 0.64 |
| Low edge energy + ≤2 colors | `Plain` | 0.90 |
| Fallback | `Plain` | 0.58 |

The **peak-regularity test** is what keeps a jeans' leg gap (two clustered
column peaks) from reading as stripes: real stripes/plaid have consistent gap
variance (< 0.65 CV) and spread over > 55% of the axis.

### 2.3 Color heuristics

- `dominant_color()`: 16-bit-per-channel bucket histogram over subject pixels only; the mode bucket's true pixels are averaged to the exact `#RRGGBB`.
- `nearest_color_name()`: Euclidean match against 7 reference swatches (Black `#000000`, White `#FFFFFF`, Gray `#808080`, Beige `#E8DCC4`, Navy `#1B2A4A`, Brown `#6B4226`, Olive `#556B2F`).

### 2.4 Season heuristics + text-hint override

`classify_season()` combines category (Outerwear → Winter lean; Watches/Jewelry
→ always All Season; Shorts/Tank → Summer; Hoodie/Sweater → Winter) with
brightness (dark → Winter lean, light → Summer lean) and the composed type
label. Text hints are the highest-trust signal: any keyword hit in
`HINT_KEYWORDS` (e.g. "denim jacket" → `Outerwear`, "sneakers" → `Shoes`)
overrides the visual category; an explicit `type_hint` wins for `type`; a
`shoe_hint` must be one of `AppConstants.shoePreferences`.

---

## 3. API usage

```python
from classifier import WardrobeClassifier

clf = WardrobeClassifier()

# Vision-only
result = clf.classify("photo.jpg")                     # local path
result = clf.classify("https://example.com/item.jpg")  # http(s) URL

# With text hints (highest trust)
result = clf.classify("photo.jpg", hint="black denim jacket")
result = clf.classify("photo.jpg", shoe_hint="Running Shoes")

# Result shape
# {
#   "category":  "Outerwear",            ← ItemCategory.all only
#   "type":      "Denim Jacket",         ← fit + lexicon noun (free-text field)
#   "color":     "#1B2A4A",              ← dominant hex
#   "colorName": "Navy",                 ← AppConstants.colorPreferences only
#   "pattern":   "Plain",                ← AppConstants.patterns only
#   "season":    "All Season",           ← AppConstants.seasons only
#   "confidence": {"category":…, "pattern":…, "season":…},
#   "alternatives": [ {"category":…, "score":…} ×3 ],
#   "silhouette": {"aspect":…, "fill_ratio":…, "waist_pinch":…, "taper":…, "legs_runs":…},
#   "source": …
# }

# CLI
#   python3 classifier.py photo.jpg --hint "denim jacket"
#   python3 classifier.py                     # built-in demo suite (6 garments)
```

### 3.1 Auto-filling `AddItemScreen.kt` (Kotlin side)

The JSON maps 1:1 onto the fields `AddItemScreen` writes into `WardrobeItem`:

```kotlin
// Pseudocode in AddItemScreen: after the user picks a photo,
// POST it to a host running `classifier.py` (or port the rules to Kotlin),
// then pre-fill the form state:
//   category  = result["category"]     // dropdown, ItemCategory.all
//   type      = result["type"]         // text field (user may edit)
//   color     = result["colorName"]    // dropdown, AppConstants.colorPreferences
//   pattern   = result["pattern"]      // dropdown, AppConstants.patterns
//   season    = result["season"]       // dropdown, AppConstants.seasons
// Hex (result["color"]) is extra data for a future color-swatch field.
```

The vocabularies in `classifier.py` are plain Python lists copied from
`WardrobeItem.kt` / `AppConstants.kt` — update both together if the app's lists
change, or generate the Python lists from the Kotlin source in CI.

---

## 4. Verified demo run (real output, this session)

`python3 classifier.py` renders 6 deterministic synthetic garments with Pillow
and classifies them. Captured results:

| Demo image | Expected | Predicted `category` | `type` | `colorName` | `pattern` | `season` |
|---|---|---|---|---|---|---|
| `black_tshirt` | Tops / Black / Plain | **Tops** ✅ | Oversized T-Shirt | **Black** ✅ | **Plain** ✅ | All Season |
| `blue_jeans` | Bottoms / Navy / Plain | **Bottoms** ✅ | Jeans | **Navy** ✅ | Plain | All Season |
| `striped_tee` | Tops / red / Striped | **Tops** ✅ | Oversized T-Shirt | Brown* | **Striped** ✅ | All Season |
| `camo_jacket` | Outerwear / olive / Camo | **Outerwear** ✅ | Jacket | **Olive** ✅ | **Camo** ✅ | Winter |
| `white_sneakers` | Shoes / White / Plain | **Shoes** ✅ | Sneakers | **White** ✅ | **Plain** ✅ | All Season |
| `checked_shirt` | Tops / blue / Checked | **Tops** ✅ | Oversized T-Shirt | Gray* | **Checked** ✅ | All Season |

6/6 categories correct, 6/6 patterns correct, 4/6 exact color names.

\* Known color-name quirks (see §5): a saturated red can land nearer the Brown
swatch than the 7-name palette allows, and a light desaturated blue reads Gray.
The **hex** value is always exact (`#DB2626`, `#586C96`) — only the 7-name
snap is lossy.

Additional live verifications this session:
- URL input: classified `https://raw.githubusercontent.com/python-pillow/Pillow/main/Tests/images/flower.jpg` end-to-end (no crash; degenerate subject handled by the full-frame fallback).
- Hint override: `striped_tee.png` + hint `"red striped t-shirt"` → `Tops / T-Shirt / Striped` (0.95 category confidence).
- Shoe override: `white_sneakers.png` + `shoe_hint="Running Shoes"` → `Shoes / Running Shoes`.
- Vocabulary gate: all outputs above passed `validate()` against the repo-derived lists.

---

## 5. Limitations (honest scope)

1. **Heuristics, not a neural net.** The category model is geometric — calibrated on flat-lay / studio-style shots where the background is near-uniform. Cluttered scenes, mannequins, or on-body shots will fall back to the full-frame mask and lean on text hints.
2. **7-color naming is lossy.** `colorName` snaps to the 7 `AppConstants.colorPreferences` entries; saturated red ↔ Brown and light blue ↔ Gray confusions are inherent to the palette. The hex is exact; keep it alongside the name.
3. **`type` nuance is limited.** Without a detector model, "Polo Shirt" vs "Button-Up Shirt" cannot be resolved visually — the lexicon fallback picks by aspect ratio, and the text hint is the reliable path. Users can edit the free-text field in the app.
4. **Jewelry/Watches rely on size + luster priors.** A small metallic object is a watch; a small saturated object is jewelry. A ring on a busy background may misfire.
5. **No training data / no dataset was provided**, so thresholds were tuned against the 6 deterministic synthetic renders in the demo suite + 2 live URL tests — not against a labeled fashion dataset. Re-tune `score_categories` constants before production.
6. **Kotlin port is future work.** All formulas are pure arithmetic (histograms, medians, edge sums) and port 1:1 to Kotlin if on-device inference is preferred over a host round-trip.

---

## 6. Files

| File | Purpose |
|---|---|
| `classifier.py` | `WardrobeClassifier` class + vocabulary constants + demo suite. Python stdlib + Pillow only. |
| `CLASSIFIER.md` | This document. |

Both files are delivered as download links in the accompanying chat message.
