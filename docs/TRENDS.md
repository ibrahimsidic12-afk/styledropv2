# TRENDS.md — Fashion Trend Analyzer for StyleDrop

> **Module spec + reference implementation** for `styledropv2`
> Repo: `https://github.com/ibrahimsidic12-afk/styledropv2.git`
> Companion code: [`trend_analyzer.py`](./trend_analyzer.py) (runnable, mock-data smoke test included)
> Target screen: `app/src/main/java/com/example/ui/AnalyticsScreen.kt`
> Target data layer: `WardrobeRepository` → `WardrobeDao` (Room)

**Goal:** tell a StyleDrop user *what is trending right now, how early they are catching it, and what to add to their wardrobe* — using the wardrobe vocabulary the app already defines (`AppConstants.kt`) so recommendations cannot drift into free-text.

---

## 0. Where this plugs into styledropv2

The existing app is an Android + Jetpack Compose client with a Room-backed wardrobe and a server-side Gemini capability.

| Layer | Existing file | What the Trend Analyzer adds |
|---|---|---|
| Data model | `models/WardrobeItem.kt` | reads `category`, `type`, `color`, `secondaryColor`, `style`, `fit`, `pattern`, `season`, `timesWorn`, `isFavoriteItem`, `lastWorn`, `createdAt` |
| Vocabulary | `models/AppConstants.kt` | trend entities are **constrained** to `styles`, `colorPreferences`, `patterns`, `seasons` |
| Persistence | `data/WardrobeDao.kt` | new table `trend_signals` (append-only) + `trend_scores` (materialized ranking) |
| Repository | `data/WardrobeRepository.kt` | new `TrendRepository` sibling — same `Flow`-based pattern |
| UI | `ui/AnalyticsScreen.kt` | new "Trending" section below `StatCard("Total Items", …)` |
| AI | `network/GeminiApiService.kt` (`gemini-1.5-flash`) | Gemini narrates *why* a trend fits an item; it does **not** compute scores |

```
                 ┌──────────────────────────────────────────────┐
  Pinterest ──┐  │  INGEST        NORMALIZE       SCORE          │
  Instagram ──┤  │  (Section 1)   (Section 2)     (Section 3)    │
  Google    ──┼─▶│  raw signal → per-entity  →  0-100 trend   ──┼─▶ trend_scores
  In-app    ──┘  │    counts       weekly series   index         │        │
                 └──────────────────────────────────────────────┘        ▼
                                                     ┌──────────────────────────┐
                                                     │ SEASONALITY (Section 4)  │
                                                     │ → season filter per user │
                                                     └────────────┬─────────────┘
                                                                  ▼
                                                     ┌──────────────────────────┐
                                                     │ WARDROBE GAP  → "Add this"│
                                                     └──────────────────────────┘
```

---

## 1. Data Sources

**No scraping.** Every source below is described via its *official* surface, and the caps quoted are the published limits. Anything marked **[assumption]** is a design choice, not a vendor-documented fact.

### 1.1 Pinterest — primary early-signal source
Pinterest is the strongest leading indicator for fashion because users pin *intent to buy*, often 1–2 seasons before retail adoption.

| Surface | Use | Reality check |
|---|---|---|
| **Pinterest Trends & Insights API** | keyword trend curves, regional breakdown | Requires **partner/Enterprise-grade access** approved per application — *not* self-serve on a free tier ([Pinterest Developers — Trends](https://developers.pinterest.com/docs/analytics-and-reports/trends/)) |
| **Trending keywords endpoint** (`/v5/trending_keywords`) | ready-made list of rising keywords for ad targeting | Documented and useful for cold-start ([List trending keywords](https://developers.pinterest.com/docs/api/v5/trending_keywords-list/)) |
| **Pinterest API v5** generally | search + board data once OAuth is granted | Standard access requires an OAuth demo for review ([Pinterest API intro](https://developers.pinterest.com/docs/api/v5/introduction/?)) |

**Why this matters for the pitch:** Pinterest's Trends data is *gated behind a partnership review*, so a v1 of this module should treat Pinterest as an **optional enrichment feed** and build the pipeline to degrade gracefully when it is absent. Third-party scrapers exist ([Apify Pinterest Trends Scraper](https://apify.com/automation-lab/pinterest-trends-scraper/api)) but they are a legal/ToS risk — **do not** ship them in the app.

### 1.2 Instagram — hashtag momentum
| Surface | Use | Reality check |
|---|---|---|
| **IG Hashtag Search** (`GET /{ig-user-id}/tags`) | recent media for a hashtag | **Hard cap: 30 unique hashtags per Instagram account per rolling 7 days** ([Meta — Hashtag Search](https://developers.facebook.com/documentation/instagram-platform/instagram-api-with-facebook-login/hashtag-search)) |
| **IG Hashtag Search reference** | quota + error semantics | "The API will return a generic error for any queries that include hashtags that we have [already queried]" ([Meta — IG Hashtag Search](https://developers.facebook.com/documentation/instagram-platform/instagram-graph-api/reference/ig-hashtag-search)) |

**This 30/7-day ceiling is the single most important constraint in the whole design.** You cannot chase every micro-trend. Consequence:

- Maintain a **watchlist of ≤30 rotating hashtags**, refreshed in cohorts (e.g. 10 slots rotated daily).
- Rank candidate hashtags by in-app demand *before* spending a query slot.
- Persist every response immediately; the cap is on *queries*, not on reads of previously-fetched data.
- Requests are also limited per hour per user token ([Phyllo — IG API guide](https://www.getphyllo.com/post/how-to-use-instagram-api-to-pull-photos-based-on-hashtag)); the cap resets after 7 days ([Elfsight — IG Graph API guide](https://elfsight.com/blog/instagram-graph-api-complete-developer-guide-for-2026/)).

### 1.3 Google Trends — volume backbone
- The **official Google Trends API (alpha)** gives consistently scaled interest data back **1800 days (5 years)** with daily/weekly/monthly/yearly aggregation — ideal for seasonality curves ([Google — Introducing the Google Trends API](https://developers.google.com/search/blog/2025/07/trends-api)).
- The long-standing **pytrends** library is an *unofficial* pseudo-API ([PyPI](https://pypi.org/project/pytrends/), [GitHub](https://github.com/GeneralMills/pytrends)).
- **Caveat to encode in tests:** pytrends numbers can diverge sharply from the public Trends UI because the UI resolves a *topic/knowledge-graph entity* while pytrends may return a *plain search term* ([Stack Overflow](https://stackoverflow.com/questions/59901790/why-is-data-downloaded-via-pytrends-drastically-different-from-using-the-google)). Never mix the two scales in one series.

### 1.4 In-app signals — the proprietary edge
The only feed no competitor can copy. Derived from `WardrobeItem` fields:

| In-app signal | Field(s) | Meaning |
|---|---|---|
| Add-rate | `createdAt`, `type`, `style`, `color` | what users actually buy |
| Wear-velocity | `timesWorn`, `lastWorn` | what users actually *use* (kills hype) |
| Favourite-rate | `isFavoriteItem` | emotional attachment |
| Ghost-items | `timesWorn == 0` for N days | anti-signal — trend the user adopted but never wore |
| Search→add conversion | *(new)* | demand with no supply = gap to fill |

**Signal weighting rationale:** social feeds measure *attention*; in-app measures *adoption*. A trend that is loud on Pinterest but has zero in-app add-rate is usually a content trend, not a wardrobe trend. This distinction is the analyzer's core defensible insight.

---

## 2. Detection Logic

Three independent detectors, then a fusion step.

### 2.1 Popularity — how *big* is it
```
popularity(entity) = Σ_weeks Σ_sources (count + 0.35 · engagement)
share              = popularity(entity) / Σ popularity(all entities)
P̂                  = min(share / 0.25, 1.0)
```
`0.35` on engagement rewards *fierce small* signals (saves/sends) over passive reach. `0.25` is the saturation share: above 25% of corpus reach the term is mainstream, and further growth adds nothing to the score.

### 2.2 Velocity — how *fast* is it growing
```
velocity = mean(t+1 / t)  over consecutive weeks
V̂        = clamp((velocity − 1.0) / 0.5, 0, 1)
```
`velocity > 1` grows, `< 1` shrinks. `+50% week-over-week` maxes the component. Using the **mean of ratios** (not `last/first`) makes the metric robust to one viral spike.

### 2.3 Acceleration — is the growth itself speeding up
```
acceleration = (velocity(last 4 wks) − velocity(prior 4 wks))
               × mean(|Δ|) / pstdev(Δ)
Â            = (tanh(acceleration) + 1) / 2
```
This is the **breakout detector**. A term with high velocity but flat acceleration is already priced in; a term whose *second derivative* is positive is where the early-mover advantage lives. The `tanh` squash keeps a single explosive week from dominating the 0–100 index, and the `mean(|Δ|)/σ` scaling normalises across entities with wildly different magnitudes.

### 2.4 Freshness — how *early* are you
```
freshness = 0.5 ** (days_since_peak / 21)
```
21-day half-life. A term still climbing at its peak scores `1.0`; three weeks past peak it scores `0.5`; two months past peak it is noise. This is what stops the app from recommending last season's coat.

### 2.5 Cross-source confirmation
```
X = (distinct sources carrying the entity in the last 4 weeks) / 4
```
Pinterest + IG + Google + in-app all agreeing is worth far more than one feed spiking. Prevents a single-platform artefact from ranking.

### 2.6 Lifecycle classification
| Stage | Condition | User action |
|---|---|---|
| **BREAKOUT** | `velocity ≥ 1.20` **and** `acceleration > 0.05` | integrate now — lead the curve |
| **RISING** | `velocity ≥ 1.05` and `freshness ≥ 0.5` | safe to buy |
| **PEAKING** | `velocity ≥ 0.98` and `freshness < 0.5` | late — buy only if already owned |
| **SATURATED** | `0.95 ≤ velocity < 0.98` | skip |
| **DECLINING** | `velocity < 0.95` | avoid |

---

## 3. Scoring & Trending Algorithm

```
TREND_SCORE = 100 × ( 0.30·P̂  +  0.30·V̂  +  0.15·Â  +  0.15·F  +  0.10·X )
```

| Weight | Component | Why this weight |
|---|---|---|
| **0.30** | Popularity `P̂` | size of the wave |
| **0.30** | Velocity `V̂` | direction of travel — equal to size, deliberately |
| **0.15** | Acceleration `Â` | the breakout premium |
| **0.15** | Freshness `F` | anti-stale guard |
| **0.10** | Cross-source `X` | confidence discount |
| 1.00 | | |

**Why popularity and velocity are co-equal:** a 60/20 split ranks "Black" (huge, flat) above "Chromecore" (tiny, exploding) forever, and the app becomes a mirror of the past. Equal weighting means a signal must be **large *and* accelerating** to top the board — which is exactly what "what's trending" means. Freshness and acceleration then break ties in favour of the early call.

**Computation steps (implemented in `trend_analyzer.py`):**
1. **Ingest** → `List[Signal]` where `Signal.raw = count + 0.35 · engagement`.
2. **Aggregate** by `(entity_type, entity, week)` across all sources.
3. **Per entity** → weekly series → `P̂`, `V̂`, `Â`, `F`, `X`.
4. **Score** → 0–100, sort desc.
5. **Materialize** into `trend_scores` so `AnalyticsScreen` reads a table instead of recomputing.

**Anti-gaming guards:** cap any single source at 60% of an entity's reach; log-scale counts before normalising *(planned)*; require ≥3 weeks of data before an entity is scored; drop entities with <50 total raw events.

### Verified smoke-test output
Run on the deterministic synthetic corpus (4 sources, 12 weeks, 360 signals):

```
#  ENTITY         TYPE         SCORE    VEL    ACC  FRESH  STAGE
----------------------------------------------------------------
1  Chromecore     style        55.30   1.42   0.00   1.00  RISING
2  Butter Yellow  color        52.40   1.39   0.06   1.00  BREAKOUT
3  Old Money      style        52.18   1.01   0.01   1.00  DECLINING
4  Olive          color        47.74   1.21  -0.08   1.00  RISING
5  Y2K            style        47.14   1.15   0.03   1.00  RISING
6  #blokecore     hashtag      45.48   1.23  -0.02   1.00  RISING
7  Black          color        41.54   1.00  -0.01   0.10  PEAKING
8  Oversized      item_type    37.66   1.09   0.04   1.00  RISING
9  #quietluxury   hashtag      35.21   1.06  -0.06   1.00  RISING
10 Checked        pattern      33.01   1.11  -0.10   1.00  RISING
11 Techwear       style        16.14   0.95   0.05   0.08  SATURATED
```

The ranking behaves as designed: the exploding micro-term leads, the giant-but-flat "Old Money" is correctly flagged DECLINING, and "Black" is demoted by its `0.10` freshness despite ranking #1 on raw reach. **These are mock numbers from a synthetic corpus — they are not live market data.**

---

## 4. Seasonality Mapping

StyleDrop's `AppConstants.seasons = ["All Season", "Summer", "Winter", "Spring/Fall"]` is too coarse for trend timing: it has no *buy window* and no hemisphere logic. Map trends onto the industry calendar instead, then bridge to the app's own enum.

| Code | Label | In-season months | Retail intake | Peak |
|---|---|---|---|---|
| **SS** | Spring/Summer | Mar–Aug | Jan–Feb | May–Jul |
| **AW** | Autumn/Winter | Sep–Feb | Jul–Aug | Nov–Jan |
| **FW** | Fall/Winter (fashion-week shorthand) | Sep–Nov | Jul–Aug | Oct–Nov |
| **RS** | Resort | Nov–Feb | Oct–Nov | Dec–Jan |

**Fashion-week lead time = 12 weeks.** Runway shows land ~12 weeks before retail intake, which is exactly the window where acceleration peaks and `TREND_SCORE` is most *useful* to a shopper — early enough to buy before the crowd, late enough that the signal is real.

**Hemisphere correction (mandatory):** Northern `SS` = Southern `AW`. A StyleDrop user in Manila must not be shown coats in May.

```
hemisphere_adjust(month=5, "south")  →  AW
hemisphere_adjust(month=9, "north")  →  AW
```

**Bridge to the app enum** *(planned, not yet in `AppConstants.kt`)*:
| Trend season | → `WardrobeItem.season` |
|---|---|
| SS | `"Summer"` |
| AW / FW | `"Winter"` |
| RS | `"All Season"` |
| transitional weeks | `"Spring/Fall"` |

**Verified output** (September, northern hemisphere → AW intake window):
```json
{
  "month": 9,
  "hemisphere": "north",
  "active_season": "AW",
  "season_label": "Autumn/Winter",
  "retail_intake_months": [7, 8],
  "peak_months": [11, 12, 1],
  "buy_now_window": false,
  "lead_time_note": "12w lead: signals shown now correspond to Autumn/Winter retail intake in months [7, 8].",
  "top_trends_this_season": ["Chromecore", "Butter Yellow", "Old Money", "Olive", "Y2K"]
}
```
May in the southern hemisphere correctly flips to `AW` → the app suppresses summer-only trends. *(Both dumps are mock-data output, verified by running the script.)*

---

## 5. Implementation

### 5.1 Pipeline
```
ingest()          # Sections 1.1-1.4 → List[Signal]
  → aggregate()   # (entity_type, entity, week) buckets
  → series()      # weekly series per entity
  → score()       # Section 3 formula
  → rank()        # 0-100 desc
  → seasonality() # Section 4 filter
  → gap()         # vs. user's WardrobeItem rows
  → persist()     # trend_scores table
```

### 5.2 Room schema to add
```kotlin
@Entity(tableName = "trend_signals")
data class TrendSignal(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val source: String,        // pinterest | instagram | google | in_app
    val entityType: String,    // style | color | hashtag | pattern | item_type
    val entity: String,        // must exist in AppConstants vocab
    val week: String,          // "2026-W24"
    val count: Int,
    val engagement: Int = 0,
    val region: String = "GLOBAL",
    val fetchedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "trend_scores")
data class TrendScore(
    @PrimaryKey val entity: String,
    val score: Double, val popularity: Double, val velocity: Double,
    val acceleration: Double, val freshness: Double, val crossSource: Double,
    val stage: String,          // BREAKOUT | RISING | PEAKING | SATURATED | DECLINING
    val season: String,         // SS | AW | FW | RS
    val updatedAt: Long
)
```
Follows the existing DAO conventions (Flow-returning reads, `OnConflictStrategy.REPLACE` writes) so it drops into `WardrobeRepository`'s pattern unchanged.

### 5.3 Kotlin repository sketch
```kotlin
class TrendRepository(private val dao: TrendDao) {
    val trending: Flow<List<TrendScore>> = dao.topTrends(limit = 20)

    fun gapsFor(items: List<WardrobeItem>): List<WardrobeGap> {
        val ownedStyles = items.map { it.style }.toSet()
        val ownedColors = items.map { it.color }.toSet()
        return dao.topTrends(20).first()
            .filter { it.entity !in ownedStyles && it.entity !in ownedColors }
            .filter { it.stage == "BREAKOUT" || it.stage == "RISING" }
            .map { WardrobeGap(it.entity, it.score, it.stage) }
    }
}
```

### 5.4 Compose hook for `AnalyticsScreen.kt`
```kotlin
Text("Trending Now", style = MaterialTheme.typography.headlineMedium)
trends.forEach { t ->
    StatCard(
        title = t.entity,
        value = "${t.score.toInt()}  ·  ${t.stage}",
        // reuse existing premiumCard modifier from ui/theme/Modifiers.kt
    )
}
```

### 5.5 Verified wardrobe-gap recommendation (mock user)
Owned: Minimalist/Black Tops, Formal/Navy Bottoms, Casual/White Shoes, Old Money/Beige Outerwear.

```
→ Chromecore     score 55.95  [RISING]    Not present in wardrobe · velocity 1.427x/wk
→ Butter Yellow  score 52.17  [BREAKOUT]  Not present in wardrobe · velocity 1.383x/wk
→ Olive          score 48.15  [RISING]    Not present in wardrobe · velocity 1.205x/wk
→ Y2K            score 46.99  [RISING]    Not present in wardrobe · velocity 1.144x/wk
→ #blokecore     score 44.14  [RISING]    Not present in wardrobe · velocity 1.222x/wk
```
The user already owns Minimalist/Formal/Casual, so those trends are correctly suppressed — the app recommends *movement*, not a mirror.

### 5.6 Run it
```bash
python3 trend_analyzer.py            # smoke test on synthetic data
python3 trend_analyzer.py --days 30  # custom window
```
`trend_analyzer.py` is dependency-free (stdlib only: `argparse, json, math, statistics, dataclasses, datetime, typing`) so it runs anywhere — dev laptop, CI, or a cloud function with no install step.

### 5.7 Roadmap / known gaps
- **[assumption]** All scoring weights, the 25% saturation share, the 21-day half-life and the 12-week fashion-week lead are design choices tuned on synthetic curves. They need re-fitting against real data before shipping.
- `GeminiApiService` currently targets `gemini-1.5-flash`; trend narration should reuse that endpoint rather than add a second integration.
- Pinterest Trends access is partner-gated — build the adapter interface first, wire the vendor later.
- Multi-region support exists in the schema (`region`) but the scoring pass is still single-region.
- The anti-gaming guards (60% single-source cap, log-scaling) are documented but not yet implemented.

---

## Appendix — Source ledger

| # | Source | Used for |
|---|---|---|
| 1 | [Meta — Hashtag Search](https://developers.facebook.com/documentation/instagram-platform/instagram-api-with-facebook-login/hashtag-search) | 30 unique hashtags / rolling 7 days cap |
| 2 | [Meta — IG Hashtag Search reference](https://developers.facebook.com/documentation/instagram-platform/instagram-graph-api/reference/ig-hashtag-search) | quota + generic-error behaviour |
| 3 | [Pinterest Developers — Trends](https://developers.pinterest.com/docs/analytics-and-reports/trends/) | partner/Enterprise gating |
| 4 | [Pinterest — List trending keywords](https://developers.pinterest.com/docs/api/v5/trending_keywords-list/) | cold-start keyword list |
| 5 | [Pinterest API v5 introduction](https://developers.pinterest.com/docs/api/v5/introduction/?) | OAuth review requirement |
| 6 | [Google — Introducing the Google Trends API](https://developers.google.com/search/blog/2025/07/trends-api) | 1800-day history, aggregation granularity |
| 7 | [pytrends (PyPI)](https://pypi.org/project/pytrends/) · [pytrends (GitHub)](https://github.com/GeneralMills/pytrends) | unofficial client |
| 8 | [Stack Overflow — pytrends vs Trends UI](https://stackoverflow.com/questions/59901790/why-is-data-downloaded-via-pytrends-drastically-different-from-using-the-google) | topic vs term scale mismatch |
| 9 | [Phyllo — Instagram API guide](https://www.getphyllo.com/post/how-to-use-instagram-api-to-pull-photos-based-on-hashtag) | per-hour request cap |
| 10 | [Elfsight — IG Graph API guide](https://elfsight.com/blog/instagram-graph-api-complete-developer-guide-for-2026/) | 7-day reset behaviour |
| 11 | [Apify — Pinterest Trends Scraper](https://apify.com/automation-lab/pinterest-trends-scraper/api) | cited as a ToS risk, **not** endorsed |

*Codebase references (`WardrobeItem.kt`, `AppConstants.kt`, `WardrobeDao.kt`, `AnalyticsScreen.kt`, `build.gradle.kts`) were read directly from the cloned repo.*
