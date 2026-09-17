# STYLIST_AI — Hybrid AI Style Recommender for StyleDrop v2

**Repo analyzed:** https://github.com/ibrahimsidic12-afk/styledropv2.git
**Companion code:** `recommender.py` (pure Python 3.8+, stdlib only, fully runnable — demo included)
**Status:** Design spec + working reference implementation, grounded in the app's **actual** data model.

---

## 0. Grounding — what the real codebase actually has

The spec below is written against the real schema found in the cloned repo, not a hypothetical one:

| Source file (verified) | What it gives the recommender |
|---|---|
| `app/src/main/java/com/example/models/WardrobeItem.kt` | Room entity `wardrobe_items`: `id, imageUrl, category, type, color, secondaryColor, style, fit, pattern, season, brand, timesWorn, isFavoriteItem, lastWorn (Long?), createdAt`. Categories enum: `Tops, Bottoms, Shoes, Outerwear, Accessories, Bags, Watches, Jewelry`. |
| `app/src/main/java/com/example/models/AppConstants.kt` | Canonical value sets: `occasions` (School, College, Work, Casual, Date, Party, Wedding, Gym, Travel, Beach, Formal), `styles` (Streetwear, Casual, Minimalist, Old Money, Y2K, Korean, Smart Casual, Formal, Sporty, Vintage, Techwear, Grunge), `colorPreferences`, `shoePreferences`, `patterns` (Plain, Striped, Graphic, Checked, Camo), `fits` (Oversized, Slim, Regular, Relaxed), `seasons` (All Season, Summer, Winter, Spring/Fall). |
| `app/src/main/java/com/example/ui/AiGeneratorViewModel.kt` | Current AI flow: builds a wardrobe string (`color pattern category (type) [ID]`), prompts Gemini with occasion / style / colorPref / weather / shoePref, and instructs it to use **only** wardrobe items. |
| `app/src/main/java/com/example/data/WardrobeDao.kt`, `WardrobeRepository.kt`, `AppDatabase.kt` | Room DAO: `getAllItems()`, `getItemsByCategory()`, `getItemById()`, insert/update/delete. |
| `app/src/main/java/com/example/ui/SavedOutfitsScreen.kt` | Saved-outfits UI exists but has **no backing entity** — the biggest gap for collaborative filtering (see §4 and §6). |

Key finding: the app already stores per-item wear history (`timesWorn`, `lastWorn`, `isFavoriteItem`) — enough for content-based + rule-based + *self*-collaborative signals out of the box. What's missing is an outfit-level log table; §6 proposes it.

---

## 1. Recommendation algorithm — three-signal hybrid

### 1.1 Pipeline

```
                       ┌──────────────────────────────────────────────┐
                       │  INPUT CONTEXT  C = (occasion, weather,      │
                       │  style, color_pref, shoe_pref, mood)         │
                       └──────────────────┬───────────────────────────┘
                                          ▼
              ┌───────────────────────────────────────────────┐
              │  LAYER A · CANDIDATE GENERATION  (HARD RULES) │
              │  R0 wardrobe-membership: items ONLY from the  │
              │     user's wardrobe_items table (ids exist)   │
              │  R1 weather→season gate:                      │
              │     hot → {All Season, Summer}                │
              │     mild→ {All Season, Spring/Fall, Summer}   │
              │     cold→ {All Season, Winter, Spring/Fall}   │
              │  → candidate buckets per category             │
              └──────────────────┬────────────────────────────┘
                                 ▼
   ┌────────────────────────────────────────────────────────────────────┐
   │  LAYER B · SOFT SCORING per item (weighted sum, each in [0,1])     │
   │                                                                    │
   │   S(item) = w_content·C(item) + w_collab·K(item)                   │
   │           + w_rule·R(item)   + w_fresh·F(item)                     │
   │           + explore_bonus (never-worn items only)                  │
   │                                                                    │
   │   C  content-based   : occasion formality Δ, style affinity,       │
   │                        mood→style map, season fit                  │
   │   K  collaborative   : user affinity (timesWorn, favorite,         │
   │                        lastWorn recency) + item-item co-wear       │
   │                        counts from outfit_logs vs. picked anchors  │
   │   R  rule-based soft : color-pref match, formality band (±0.20),   │
   │                        shoe-preference match                       │
   │   F  freshness       : rotation reward for unworn items,           │
   │                        penalty if worn ≤1 day ago                  │
   └──────────────────┬─────────────────────────────────────────────────┘
                      ▼
   ┌────────────────────────────────────────────────────────────────────┐
   │  LAYER C · OUTFIT ASSEMBLY (greedy, category-constrained)          │
   │  mandatory: Tops + Bottoms + Shoes                                 │
   │  conditional: Outerwear forced when weather = cold                 │
   │  optional: up to 2 extras from Accessories/Bags/Watches/Jewelry    │
   │  each pick conditions on already-picked anchors (pairwise CF)      │
   │  diversity: mark chosen ids used → next outfit re-ranks            │
   └──────────────────┬─────────────────────────────────────────────────┘
                      ▼
   ┌────────────────────────────────────────────────────────────────────┐
   │  LAYER D · OUTFIT-LEVEL SCORING + RANKING                          │
   │  cohesion = pairwise color harmony × pattern-budget multiplier     │
   │  outfit_score = mean(item scores) × cohesion                       │
   │  completeness: missing Tops/Bottoms/Shoes ⇒ score × 0.5            │
   │  → top-k ranked outfits, each with reason strings                  │
   └────────────────────────────────────────────────────────────────────┘
```

### 1.2 The three filtering paradigms and why each is needed

**(a) Collaborative filtering (item-item, self-supervised).**
StyleDrop is a single-user, on-device app — classic user-user CF is impossible and unnecessary. Instead we use **item-item co-occurrence**: every saved/worn outfit in `outfit_logs` casts one vote for each item pair appearing together. The co-wear matrix `N[a,b]` is trained with one pass of `combinations(sorted(item_ids), 2)`. For an item `i` against already-picked anchor items `A`, the signal is the saturating normalizer:

```
cow(i, A) = max over a∈A of  N[i,a] / (N[i,a] + 2)
```

The `+2` Laplace-style term keeps one-time pairings from dominating. This captures *the user's actual pairing habits* ("you often pair the white tee with the straight jeans — 3× together") — something pure content rules can never learn.

**(b) Content-based filtering.** Item attributes vs. the request context: occasion formality distance (`1 − 1.5·|f_item − f_occ|`, clipped at 0), style affinity (exact match = 1.0; mood-mapped style = 0.85; else 0.45), and season fit. Knowledge tables (formality per occasion/style, mood→style map, color-pair harmony table) ship with the app; no training data needed — this is also the cold-start workhorse.

**(c) Rule-based filtering, hard and soft.**
- *Hard* (Layer A): wardrobe membership (never suggest an item id not in `wardrobe_items`) and the weather→season gate. These are **filters, not scores** — a winter coat can never appear in a hot-weather outfit no matter how high it scores.
- *Soft* (Layer B): explicit color preference, formality band, shoe preference; outfit-level pattern budget (max 1 statement pattern; 2+ patterns ⇒ cohesion × 0.75) and color-harmony multiplier.

### 1.3 Final score

```
S(item)      = w_c·C + w_k·K + w_r·R + w_f·F [+ 0.15 if timesWorn == 0]
outfit_score = (Σ S(item) / n_items) × cohesion × completeness
```

Default mature weights: `w_c=0.40, w_k=0.30, w_r=0.20, w_f=0.10` — verified in the demo run ("MATURE (12 logs): full hybrid").

---

## 2. Input features

| Feature | Source | Values | Used by |
|---|---|---|---|
| **User history** | `wardrobe_items`: `timesWorn`, `isFavoriteItem`, `lastWorn`; new `outfit_logs` table | wear counts, favorite flags, recency in days | CF affinity (K), freshness (F), co-wear matrix |
| **Season** | `WardrobeItem.season` + system date | All Season / Summer / Winter / Spring-Fall | Hard gate (R1) + content season fit |
| **Weather** | Device weather (already passed to Gemini today as free text) | `hot` / `mild` / `cold` band | Hard gate (R1) + forces Outerwear when cold |
| **Occasion** | `AppConstants.occasions` | School, College, Work, Casual, Date, Party, Wedding, Gym, Travel, Beach, Formal | Formality target (C, R) |
| **Mood** | New user picker (4 buttons) | Bold / Calm / Focused / Playful → style sets {Streetwear,Y2K,Grunge} / {Minimalist,Casual,Old Money} / {Smart Casual,Formal,Techwear} / {Vintage,Korean,Y2K} | Style affinity boost (C) |
| **Style preference** | `AppConstants.styles` | the 12 canonical styles | Exact-match boost (C) |
| **Color preference** | `AppConstants.colorPreferences` | Any, Black, White, Gray, Beige, Navy, Brown, Olive | Soft rule (R) + cohesion multiplier |
| **Shoe preference** | `AppConstants.shoePreferences` | Any, Sneakers, Boots, Loafers, Sandals, Running Shoes | Soft rule on Shoes (R) |

Every feature maps 1:1 onto an existing app constant or Room field — the only *new* inputs are `weather` (already collected in `AiGeneratorViewModel` as free text; just band it) and `mood` (one new 4-choice picker).

---

## 3. Output — ranked outfit suggestions with reasoning

Each suggestion is a JSON object containing **only item ids that exist in the user's wardrobe**, a numeric score, human-readable reasons per item, and outfit-level reasoning:

```json
{
  "rank": 1,
  "score": 0.718,
  "occasion": "Casual", "weather": "cold", "mood": "Bold",
  "items": [
    {"id": "T1", "slot": "Tops",       "type": "Oversized T-Shirt", "color": "White", "style": "Streetwear"},
    {"id": "B1", "slot": "Bottoms",    "type": "Straight Jeans",    "color": "Denim", "style": "Casual"},
    {"id": "S1", "slot": "Shoes",      "type": "Sneakers",          "color": "White", "style": "Streetwear"},
    {"id": "O1", "slot": "Outerwear",  "type": "Bomber Jacket",     "color": "Black", "style": "Streetwear"},
    {"id": "J1", "slot": "Jewelry",    "type": "Silver Chain",      "color": "Gray",  "style": "Y2K"},
    {"id": "A1", "slot": "Accessories","type": "Cap",               "color": "Black", "style": "Streetwear"}
  ],
  "reasoning": {
    "per_item": {
      "T1": ["style match: Streetwear == requested Streetwear",
             "oversized t-shirt formality fits a Casual day"],
      "S1": ["you often pair it with White oversized t-shirt (3x together)",
             "you often pair it with Denim straight jeans (4x together)"],
      "O1": ["style match: Streetwear == requested Streetwear",
             "unworn for 10 days — perfect rotation pick"]
    },
    "outfit": ["clean pattern story (max 1 statement piece)",
               "strong white-based color harmony across the fit"]
  }
}
```

These reason strings feed straight into the existing Gemini prompt (or the UI directly) — the LLM's job shrinks from "invent an outfit" (hallucination risk: today's prompt *can* return items the user doesn't own if parsing slips) to "phrase the pre-validated outfit nicely."

---

## 4. Cold-start strategy (two-sided)

**User-side cold start (no outfit history).**
1. **Onboarding style quiz** — first launch asks the user to pick 3–5 loved `styles` from `AppConstants.styles` and a default color family. These seed `style` affinity and the color-preference rule before any interaction data exists.
2. **Weight scheduling** — with `n_logs = 0`, collaborative weight is zero and rules + content dominate:
   `COLD START: content=0.50, collab=0.00, rule=0.40, fresh=0.10` (verified in demo Scenario C).
3. **Popularity/rule fallback** — with no co-occurrence data, ranking falls back to rules + content + the user's own `isFavoriteItem` / `timesWorn` (which the wardrobe screen already collects passively).

**Warm-up ramp.** Collaborative weight ramps in linearly with log count: `w_collab = 0.10 + 0.02·n_logs` for `n_logs < 10` (WARM-UP band), reaching the mature `0.30` at 10+ logs. No cliff, no retraining — pure arithmetic.

**Item-side cold start (newly added garments).** A brand-new item has `timesWorn = 0, lastWorn = null` — CF would bury it forever. Fix: **exploration bonus** `+0.15` on any never-worn item, guaranteeing new pieces appear in early suggestions and quickly earn co-wear counts. (Verified: the never-worn `Silver Chain` (J1) appears in Scenario B's #1 outfit via co-wear evidence + bonus.)

**Wardrobe-side cold start (tiny wardrobe).** If a category bucket is empty, mandatory slots fall back to reusing the best available item and the outfit is flagged `"encore": true` ("wardrobe ran out of fresh bottoms — reusing denim straight jeans"); if a mandatory category is missing entirely, the completeness penalty (`×0.5`) surfaces a *"your wardrobe is missing X"* nudge instead of a broken suggestion.

---

## 5. Python pseudocode + sample implementation

### 5.1 Pseudocode (language-neutral core)

```
function RECOMMEND(wardrobe, logs, ctx, k):
    W          ← schedule_weights(len(logs))            # §4 cold-start ramp
    cowear     ← {(a,b): count}  from logs             # item-item CF matrix
    candidates ← hard_filter(wardrobe, ctx.weather)    # R0 + R1, per category
    results, used ← [], ∅

    repeat k times:
        outfit ← []
        for slot in [Tops, Bottoms, Shoes]:            # mandatory skeleton
            outfit += argmax_score(pick(candidates[slot] − used))
        if ctx.weather == cold: outfit += pick(candidates[Outerwear])
        outfit += top2(extras scored vs. outfit anchors)

        for each item i in outfit (in pick order):     # anchors grow as we go
            C ← content(i, ctx);  K ← collab(i, cowear, anchors)
            R ← rules(i, ctx);    F ← freshness(i)
            S[i] ← W.c·C + W.k·K + W.r·R + W.f·F  (+0.15 if i.timesWorn == 0)
            anchors += i

        coh     ← color_harmony(pairwise(outfit)) × pattern_budget(outfit)
        score   ← mean(S) × coh × (0.5 if mandatory slot missing else 1.0)
        results += {outfit, score, reasons}
        used    += ids(outfit)                         # diversity for next round

    return sort_desc(results, by score)[:k]
```

### 5.2 Sample implementation — `recommender.py`

`recommender.py` (same folder as this document) is the complete, runnable reference implementation: ~450 lines of pure stdlib Python mirroring `WardrobeItem.kt` field-for-field, with the three layers, weight scheduling, cold-start fallbacks, 22-item mock wardrobe, 12 outfit logs, and a printed demo. Run:

```bash
python3 recommender.py
```

**Verified output (real run, excerpt — Scenario B, cold weekend / Streetwear / Bold mood):**

```
  weights  : content=0.40 collab=0.30 rule=0.20 fresh=0.10 explore=+0.15
  (MATURE (12 logs): full hybrid; wardrobe=22 items, logs=12)

  #1  OUTFIT  score=0.718
      - [Tops      ] White Oversized T-Shirt (Streetwear, All Season)
          * style match: Streetwear == requested Streetwear
      - [Bottoms   ] Denim Straight Jeans (Casual, All Season)
          * you often pair it with White oversized t-shirt (3x together)
      - [Shoes     ] White Sneakers (Streetwear, All Season)
          * you often pair it with Denim straight jeans (4x together)
      - [Outerwear ] Black Bomber Jacket (Streetwear, Winter)
          * style match: Streetwear == requested Streetwear
          * unworn for 10 days — perfect rotation pick
      - [Jewelry   ] Gray Silver Chain (Y2K, All Season)
          * you often pair it with White oversized t-shirt (1x together)
      - [Accessories] Black Cap (Streetwear, All Season)
          * you often pair it with Denim straight jeans (2x together)
      WHY: clean pattern story (max 1 statement piece); strong white-based color harmony
```

Other verified scenarios in the full output: **Scenario A** (Work / mild / Smart Casual / Navy pref / Loafers) ranks `Navy Oxford Shirt + Beige Relaxed Chinos + White Sneakers` #1 at `0.552` via co-wear + rotation; **Scenario C** (cold start, zero logs) correctly switches to `content=0.50 collab=0.00 rule=0.40` and still produces complete outfits at `0.684`. A rule check also fired: an outfit with 2 statement patterns was demoted ("pattern budget: 2 statement patterns — kept score in check").

---

## 6. Integration plan for StyleDrop v2 (Android/Kotlin)

1. **New Room entity** `outfit_logs(outfit_id, item_ids: List<String>, occasion, weather, created_at)` + DAO. Writing one row every time the user saves or marks an outfit worn is the *only* change needed to make CF work. (`SavedOutfitsScreen` currently has no data source — this fills that gap too.)
2. **Port `recommender.py`** to a `Recommender.kt` object (pure Kotlin, no dependencies — the algorithm is arithmetic + hash maps). All state stays **on-device** (Room) — consistent with the app's privacy posture; no new network calls, Gemini stays optional for phrasing only.
3. **Wire-up**: `HomeScreen` "Today's Fit" card calls `Recommender.recommend(wardrobe, logs, contextFrom(weatherApi, occasionPicker, moodPicker), k=3)`; each card shows the reason strings from §3.
4. **Feedback loop**: user taps "Wear this" → upsert `timesWorn`, `lastWorn`, insert `outfit_logs` row → next recommendation is immediately smarter.

## 7. Known limits & next steps

- Color harmony is a hand-seeded table over the app's canonical colors; a future version can learn pair preferences from logged outfit ratings.
- `imageUrl` (vision embedding of the garment photo) is unused today; adding a CLIP-style image vector to the content layer is the highest-leverage upgrade.
- Weather is currently a 3-band input; real forecasts (temp ranges, rain) can replace the band without changing the architecture (just refine the hard gate + add a waterproof-shoes rule).
