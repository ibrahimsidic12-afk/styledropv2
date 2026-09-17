#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
StyleDrop v2 — Hybrid AI Style Recommender (sample implementation)
===================================================================

Companion reference implementation for STYLIST_AI.md.

Grounded in the REAL styledropv2 data model
(app/src/main/java/com/example/models/WardrobeItem.kt):

    @Entity(tableName = "wardrobe_items")
    WardrobeItem(
        id, imageUrl, category, type, color, secondaryColor, style, fit,
        pattern, season, brand, timesWorn, isFavoriteItem, lastWorn, createdAt)

and in app/src/main/java/com/example/models/AppConstants.kt
(occasions, styles, colorPreferences, shoePreferences, patterns, fits, seasons).

HARD CONSTRAINT (§3.1 of STYLIST_AI.md):
    Every suggestion is assembled ONLY from item ids that exist in the
    user's wardrobe_items table. Candidate generation is a hard filter on
    wardrobe ids — the recommender can never invent or hallucinate items.

Architecture (three signal layers, weighted sum):
    1. CONTENT-BASED  — occasion formality, style affinity, season/weather
                        fit, mood alignment, per-item color harmony.
    2. COLLABORATIVE  — item-item co-occurrence from outfit_logs (items
                        historically worn together) + implicit user
                        affinity (timesWorn, isFavoriteItem, lastWorn
                        recency). Works fully on-device, single user.
    3. RULE-BASED     — hard gates (weather→season, wardrobe-membership,
                        category completeness) + soft rules (color
                        preference, formality band, pattern budget,
                        freshness rotation).

Runs on PURE PYTHON 3.8+ (stdlib only — no scikit-learn needed), is fully
deterministic, and prints a live demo on mock wardrobe data so the scoring,
ranking and reasoning strings can be verified end-to-end.
"""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field
from itertools import combinations
from typing import Dict, List, Optional, Tuple

# ============================================================================
# 1. DATA MODEL — mirrors WardrobeItem.kt exactly (field names preserved)
# ============================================================================

@dataclass
class WardrobeItem:
    """Mirror of com.example.models.WardrobeItem (Room entity wardrobe_items).

    NOTE on lastWorn: the app stores epoch MILLIS; in this demo we store
    'days ago' (small ints). _days_since() auto-detects the scale, so the
    same code works for both.
    """
    id: str
    category: str = ""            # Tops|Bottoms|Shoes|Outerwear|Accessories|Bags|Watches|Jewelry
    type: str = ""                # e.g. "Oversized T-Shirt"
    color: str = ""
    secondaryColor: str = ""
    style: str = ""               # one of AppConstants.styles
    fit: str = "Regular"          # Oversized|Slim|Regular|Relaxed
    pattern: str = "Plain"        # Plain|Striped|Graphic|Checked|Camo
    season: str = "All Season"    # All Season|Summer|Winter|Spring/Fall
    brand: str = ""
    timesWorn: int = 0
    isFavoriteItem: bool = False
    lastWorn: Optional[int] = None
    createdAt: int = 0


@dataclass
class OutfitLog:
    """PROPOSED new Room entity `outfit_logs` (does not exist in the repo yet).

    Adding this table is what unlocks item-item collaborative filtering:
    every outfit the user saves/backs becomes one row of co-wear evidence.
    (Today SavedOutfitsScreen is a UI placeholder with no backing entity.)
    """
    outfit_id: str
    item_ids: List[str]
    occasion: str
    weather: str            # hot|mild|cold
    created_days_ago: int


# --- mirrored from AppConstants.kt -----------------------------------------
OCCASIONS = ["School", "College", "Work", "Casual", "Date", "Party",
             "Wedding", "Gym", "Travel", "Beach", "Formal"]
STYLES = ["Streetwear", "Casual", "Minimalist", "Old Money", "Y2K", "Korean",
          "Smart Casual", "Formal", "Sporty", "Vintage", "Techwear", "Grunge"]
COLOR_PREFS = ["Any", "Black", "White", "Gray", "Beige", "Navy", "Brown", "Olive"]
PATTERNS = ["Plain", "Striped", "Graphic", "Checked", "Camo"]
FITS = ["Oversized", "Slim", "Regular", "Relaxed"]
SEASONS = ["All Season", "Summer", "Winter", "Spring/Fall"]

TOP, BOTTOM, SHOES, OUTER = "Tops", "Bottoms", "Shoes", "Outerwear"
EXTRA_CATS = ["Accessories", "Bags", "Watches", "Jewelry"]

# ============================================================================
# 2. KNOWLEDGE TABLES (the "content-based" domain knowledge)
# ============================================================================

# occasion -> target formality in [0,1]  (0 = very casual, 1 = black tie)
OCCASION_FORMALITY = {"Gym": 0.15, "Beach": 0.20, "School": 0.40, "College": 0.40,
                      "Casual": 0.40, "Travel": 0.50, "Party": 0.60, "Date": 0.70,
                      "Work": 0.80, "Wedding": 0.95, "Formal": 1.00}

# style -> formality signature in [0,1]
STYLE_FORMALITY = {"Formal": 1.00, "Old Money": 0.85, "Smart Casual": 0.80,
                   "Minimalist": 0.65, "Korean": 0.55, "Casual": 0.45,
                   "Techwear": 0.50, "Vintage": 0.45, "Streetwear": 0.40,
                   "Y2K": 0.35, "Grunge": 0.30, "Sporty": 0.25}

# mood -> styles it amplifies (STYLIST_AI.md §2.4, 'mood' input feature)
MOOD_STYLES = {"Bold":    {"Streetwear", "Y2K", "Grunge"},
               "Calm":    {"Minimalist", "Casual", "Old Money"},
               "Focused": {"Smart Casual", "Formal", "Techwear"},
               "Playful": {"Vintage", "Korean", "Y2K"}}

# weather band -> acceptable `season` values on WardrobeItem (HARD gate)
WEATHER_SEASON = {"hot":  {"All Season", "Summer"},
                  "mild": {"All Season", "Spring/Fall", "Summer"},
                  "cold": {"All Season", "Winter", "Spring/Fall"}}

NEUTRALS = {"Black", "White", "Gray", "Beige", "Navy", "Brown", "Olive",
            "Cream", "Denim"}
# pairwise color harmony in [0,1]; missing pairs default to _DEFAULT_HARMONY
_COLOR_PAIRS = {("Navy", "White"): 1.0, ("Black", "White"): 1.0,
                ("Gray", "White"): 0.95, ("Navy", "Beige"): 0.95,
                ("Brown", "Beige"): 0.95, ("Olive", "Beige"): 0.90,
                ("Black", "Gray"): 0.90, ("Navy", "Gray"): 0.90,
                ("Brown", "White"): 0.90, ("Olive", "White"): 0.85,
                ("Black", "Navy"): 0.75, ("Brown", "Navy"): 0.85,
                ("Olive", "Brown"): 0.85, ("Denim", "White"): 0.95,
                ("Denim", "Black"): 0.85, ("Denim", "Brown"): 0.85}
_DEFAULT_HARMONY = 0.70


def color_harmony(a: str, b: str) -> float:
    if a == b:
        return 0.80 if a in NEUTRALS else 0.55
    return _COLOR_PAIRS.get((a, b), _COLOR_PAIRS.get((b, a), _DEFAULT_HARMONY))


# ============================================================================
# 3. WEIGHTS + COLD-START SCHEDULING (STYLIST_AI.md §4)
# ============================================================================

class Weights:
    def __init__(self, content: float, collab: float, rule: float, fresh: float,
                 explore: float = 0.0, note: str = "mature profile"):
        self.content, self.collab, self.rule, self.fresh = content, collab, rule, fresh
        self.explore = explore
        self.note = note


def weights_for(n_logs: int, wardrobe_size: int) -> Weights:
    """Adaptive weight scheduling.

    - Cold start, no history  -> rule + content dominate, collab OFF.
    - Sparse history (1-9)    -> collab ramps up linearly.
    - Mature (>=10 logs)      -> full hybrid weights.
    Plus a small exploration bonus for never-worn items (item-side cold start).
    """
    explore = 0.15 if True else 0.0        # bonus applied per-item below
    if n_logs == 0:
        return Weights(0.50, 0.00, 0.40, 0.10, explore,
                       note="COLD START (no logs): rules+content only")
    if n_logs < 10:
        c = 0.10 + 0.02 * n_logs           # 0.12 .. 0.28
        return Weights(0.50 - 0.4 * c, c, 0.40 - 0.2 * c, 0.10, explore,
                       note=f"WARM-UP ({n_logs} logs): collab ramping in")
    return Weights(0.40, 0.30, 0.20, 0.10, explore,
                   note=f"MATURE ({n_logs} logs): full hybrid")


# ============================================================================
# 4. SIGNAL LAYERS — each returns (score in [0,1], list of reason strings)
# ============================================================================

def days_since(item: WardrobeItem) -> Optional[int]:
    """lastWorn -> days ago. Handles epoch-millis (app) or days-ago (demo)."""
    if item.lastWorn is None:
        return None
    if item.lastWorn > 10_000_000_000:          # epoch millis heuristic
        return max(0, int((NOW_MILLIS - item.lastWorn) / 86_400_000))
    return int(item.lastWorn)


def content_score(item: WardrobeItem, ctx: "Context") -> Tuple[float, List[str]]:
    """Layer 1 — content-based filtering (item attributes vs. outfit context)."""
    reasons: List[str] = []
    # (a) occasion formality match
    f_item = STYLE_FORMALITY.get(item.style, 0.5)
    f_occ = OCCASION_FORMALITY[ctx.occasion]
    formality = max(0.0, 1.0 - abs(f_item - f_occ) * 1.5)
    # (b) style affinity vs. requested style + mood
    if item.style == ctx.style:
        style_aff = 1.0
        reasons.append(f"style match: {item.style} == requested {ctx.style}")
    elif item.style in MOOD_STYLES.get(ctx.mood, set()):
        style_aff = 0.85
        reasons.append(f"{item.style} suits your '{ctx.mood}' mood")
    else:
        style_aff = 0.45
    # (c) season / weather fit (survivors of the hard gate get >=0.85)
    season_fit = {"All Season": 0.90}.get(item.season, 1.00)
    # (d) mood direct boost folded into style_aff above
    score = 0.40 * formality + 0.45 * style_aff + 0.15 * season_fit
    if formality >= 0.85:
        reasons.append(f"{item.type.lower()} formality fits a {ctx.occasion} day")
    if season_fit == 1.0:
        reasons.append(f"made for {item.season} weather ({ctx.weather})")
    return score, reasons


def collab_score(item: WardrobeItem, ctx: "Context",
                 cowear_counts: Dict[Tuple[str, str], int],
                 anchor_ids: List[str]) -> Tuple[float, List[str]]:
    """Layer 2 — collaborative filtering.

    Two local, privacy-safe signals (no other users needed):
      (a) user affinity  = normalized wear frequency + favorite + recency;
      (b) item-item co-occurrence with the items ALREADY picked for this
          outfit (anchor items), from outfit_logs.
    """
    reasons: List[str] = []
    max_worn = max([i.timesWorn for i in ctx.wardrobe] + [1])
    freq = item.timesWorn / max_worn
    rec = days_since(item)
    recency = 1.0 if rec is None else min(1.0, rec / 21.0)
    affinity = 0.50 * freq + 0.30 * (1.0 if item.isFavoriteItem else 0.0) \
               + 0.20 * recency
    if item.isFavoriteItem:
        reasons.append("one of your favorites")
    if item.timesWorn >= max(6, int(0.4 * max_worn)):
        reasons.append(f"a trusted go-to ({item.timesWorn}x worn)")

    # item-item co-occurrence with anchors (cosine on co-wear counts)
    cow = 0.0
    for anchor in anchor_ids:
        n = cowear_counts.get(_pair(item.id, anchor), 0)
        if n:
            cow = max(cow, n / (n + 2))     # saturating normalizer
            other = ctx.by_id[anchor]
            reasons.append(f"you often pair it with {other.color} "
                           f"{other.type.lower()} ({n}x together)")
    score = 0.65 * affinity + 0.35 * cow
    return score, reasons


def rule_score(item: WardrobeItem, ctx: "Context") -> Tuple[float, List[str]]:
    """Layer 3 — soft rules. Hard gates (wardrobe membership, weather) were
    already applied during candidate generation."""
    reasons: List[str] = []
    # (a) explicit color preference
    if ctx.color_pref in ("Any", ""):
        cpref = 1.0
    elif item.color == ctx.color_pref or item.secondaryColor == ctx.color_pref:
        cpref = 1.0
        reasons.append(f"matches your {ctx.color_pref} color preference")
    else:
        cpref = 0.55 if item.color in NEUTRALS else 0.30
    # (b) formality band around the occasion target
    f_item = STYLE_FORMALITY.get(item.style, 0.5)
    f_occ = OCCASION_FORMALITY[ctx.occasion]
    band = 1.0 if abs(f_item - f_occ) <= 0.20 else (0.6 if abs(f_item - f_occ) <= 0.35 else 0.3)
    # (c) shoe preference (only meaningful for Shoes)
    shoe = 1.0
    if item.category == SHOES and ctx.shoe_pref not in ("Any", ""):
        if ctx.shoe_pref.lower() in item.type.lower():
            shoe = 1.0
            reasons.append(f"your preferred {ctx.shoe_pref.lower()}")
        else:
            shoe = 0.50
    score = 0.45 * cpref + 0.35 * band + 0.20 * shoe
    return score, reasons


def freshness_score(item: WardrobeItem) -> Tuple[float, List[str]]:
    """Layer 3b — rotation rule: reward items not worn recently, penalize
    items worn today/yesterday (outfit repetition)."""
    rec = days_since(item)
    if rec is None:
        return 1.0, ["still unworn — time to break it in"]
    if rec <= 1:
        return 0.15, []
    if rec <= 3:
        return 0.45, []
    if rec <= 7:
        return 0.75, []
    return 1.0, [f"unworn for {rec} days — perfect rotation pick"]


# ============================================================================
# 5. CONTEXT + CANDIDATE GENERATION (hard filters)
# ============================================================================

@dataclass
class Context:
    occasion: str
    weather: str          # hot|mild|cold
    style: str
    color_pref: str = "Any"
    shoe_pref: str = "Any"
    mood: str = "Calm"
    wardrobe: List[WardrobeItem] = field(default_factory=list)
    by_id: Dict[str, WardrobeItem] = field(default_factory=dict)


def _pair(a: str, b: str) -> Tuple[str, str]:
    return (a, b) if a < b else (b, a)


def cowear_from_logs(logs: List[OutfitLog]) -> Dict[Tuple[str, str], int]:
    """Item-item co-occurrence counts from outfit_logs (CF training data)."""
    counts: Dict[Tuple[str, str], int] = defaultdict(int)
    for log in logs:
        for a, b in combinations(sorted(set(log.item_ids)), 2):
            counts[(a, b)] += 1
    return dict(counts)


def candidates(ctx: Context) -> Dict[str, List[WardrobeItem]]:
    """HARD-FILTER candidate generation — STYLIST_AI.md §3.1.

    Rule 0 (wardrobe membership): every candidate is drawn from the user's
    own wardrobe_items table. Items are NEVER synthesized.
    Rule 1 (weather->season gate): item.season must fit ctx.weather.
    """
    ok_season = WEATHER_SEASON[ctx.weather]
    buckets: Dict[str, List[WardrobeItem]] = defaultdict(list)
    for it in ctx.wardrobe:                    # <- user's OWN items only
        if it.season in ok_season:
            buckets[it.category].append(it)
    return buckets


# ============================================================================
# 6. OUTFIT ASSEMBLY — greedy by category with pairwise co-wear lookahead
# ============================================================================

def _total_item_score(item, ctx, w, cowear, anchors):
    c, _ = content_score(item, ctx)
    k, _ = collab_score(item, ctx, cowear, anchors)
    r, _ = rule_score(item, ctx)
    f, _ = freshness_score(item)
    s = w.content * c + w.collab * k + w.rule * r + w.fresh * f
    if item.timesWorn == 0 and w.explore:      # item-side cold-start boost
        s += w.explore
    return s


def assemble_outfit(ctx: Context, w: Weights, cowear: Dict[Tuple[str, str], int],
                    used: set) -> Tuple[List[WardrobeItem], List[str]]:
    """Greedy category-constrained assembly.

    mandatory: Tops + Bottoms + Shoes; Outerwear forced when cold;
    up to 2 extras (Accessories/Bags/Watches/Jewelry).
    Each pick conditions on the items already chosen (pairwise CF lookahead).
    Falls back to a 'encore' reuse if a category is exhausted (diversity mode).
    """
    buckets = candidates(ctx)
    picked: List[WardrobeItem] = []
    reasons: List[str] = []

    def pick(category: str, required: bool) -> Optional[WardrobeItem]:
        pool = [i for i in buckets.get(category, []) if i.id not in used]
        encore = False
        if not pool:
            if not required:
                return None
            pool = buckets.get(category, [])
            encore = bool(pool) and category not in (TOP, BOTTOM, SHOES)
            if not pool:
                return None
        anchors = [p.id for p in picked]
        best = max(pool, key=lambda i: _total_item_score(i, ctx, w, cowear, anchors))
        if encore:
            reasons.append(f"wardrobe ran out of fresh {category.lower()} — "
                           f"reusing {best.color} {best.type.lower()}")
        picked.append(best)
        return best

    # ---- mandatory skeleton -------------------------------------------------
    for cat in (TOP, BOTTOM, SHOES):
        pick(cat, required=True)
    # ---- weather-driven outerwear ------------------------------------------
    if ctx.weather == "cold":
        pick(OUTER, required=False)
    # ---- up to two extras ----------------------------------------------------
    extras = sorted((i for c in EXTRA_CATS for i in buckets.get(c, [])),
                    key=lambda i: _total_item_score(i, ctx, w, cowear, [p.id for p in picked]),
                    reverse=True)
    seen, chosen = set(), 0
    for e in extras:
        if e.id in used or e.id in {p.id for p in picked}:
            continue
        if e.style not in _style_ok(ctx):
            continue
        picked.append(e); chosen += 1
        if chosen == 2:
            break
    return picked, reasons


def _style_ok(ctx: Context) -> set:
    """Extras must not fight the requested style or mood."""
    return {ctx.style} | MOOD_STYLES.get(ctx.mood, set()) | {"Minimalist", "Casual"}


def outfit_cohesion(outfit: List[WardrobeItem], ctx: Context) -> Tuple[float, List[str]]:
    """Outfit-level rules: color harmony, pattern budget, color-pref coverage."""
    reasons: List[str] = []
    colors = [i.color for i in outfit]
    harm = sum(color_harmony(a, b) for a, b in combinations(colors, 2)) / max(1, len(colors) * (len(colors) - 1) // 2)
    patterns = [i.pattern for i in outfit if i.pattern != "Plain"]
    mult = 1.0
    if len(patterns) > 1:
        mult = 0.75
        reasons.append(f"pattern budget: {len(patterns)} statement patterns — "
                       f"kept score in check (max 1 recommended)")
    else:
        reasons.append("clean pattern story (max 1 statement piece)")
    if ctx.color_pref not in ("Any", "") and ctx.color_pref not in colors + \
            [i.secondaryColor for i in outfit]:
        mult *= 0.85
    if harm >= 0.85:
        reasons.append(f"strong {colors[0].lower()}-based color harmony across the fit")
    return harm * mult, reasons


# ============================================================================
# 7. PUBLIC API — recommend()
# ============================================================================

def recommend(ctx: Context, logs: List[OutfitLog], k: int = 3
              ) -> List[dict]:
    """Return top-k ranked outfit suggestions built ONLY from ctx.wardrobe.

    score = w.content*content + w.collab*collab + w.rule*rules + w.fresh*fresh
            (+ exploration bonus for never-worn items)
    outfit_score = mean(item scores) * outfit_cohesion
    """
    w = weights_for(len(logs), len(ctx.wardrobe))
    cowear = cowear_from_logs(logs)
    ctx.by_id = {i.id: i for i in ctx.wardrobe}
    results, used = [], set()

    for _round in range(k):
        items, extra_reasons = assemble_outfit(ctx, w, cowear, used)
        if not items:
            break
        item_scores, item_reasons = [], []
        anchors: List[str] = []
        for it in items:                       # score each item in context
            c, cr = content_score(it, ctx)
            cf, fr = collab_score(it, ctx, cowear, anchors)
            r, rr = rule_score(it, ctx)
            f, frsn = freshness_score(it)
            s = w.content * c + w.collab * cf + w.rule * r + w.fresh * f
            if it.timesWorn == 0 and w.explore:
                s += w.explore
                frsn = frsn + [f"new-item exploration bonus (+{w.explore})"]
            item_scores.append(s)
            item_reasons.append(sorted(set(cr + fr + rr + frsn),
                                       key=lambda x: -len(x))[:2])
            anchors.append(it.id)              # later items see earlier ones
        coh, coh_reasons = outfit_cohesion(items, ctx)
        outfit_score = (sum(item_scores) / len(item_scores)) * coh
        missing = [c for c in (TOP, BOTTOM, SHOES)
                   if c not in {i.category for i in items}]
        if missing:
            outfit_score *= 0.5                # completeness hard-ish penalty
        results.append({
            "score": round(outfit_score, 4),
            "items": items,
            "item_reasons": item_reasons,
            "cohesion_reasons": coh_reasons + extra_reasons,
            "missing": missing,
        })
        used |= {i.id for i in items}          # diversity for next round
    results.sort(key=lambda r: -r["score"])
    return results


def print_recommendations(title: str, ctx: Context, logs: List[OutfitLog],
                          k: int = 3) -> None:
    w = weights_for(len(logs), len(ctx.wardrobe))
    print("=" * 78)
    print(f"SCENARIO: {title}")
    print(f"  context  : occasion={ctx.occasion}  weather={ctx.weather}  "
          f"style={ctx.style}  color_pref={ctx.color_pref}  mood={ctx.mood}")
    print(f"  weights  : content={w.content:.2f} collab={w.collab:.2f} "
          f"rule={w.rule:.2f} fresh={w.fresh:.2f} explore=+{w.explore:.2f}")
    print(f"  ({w.note}; wardrobe={len(ctx.wardrobe)} items, logs={len(logs)})")
    print("=" * 78)
    for rank, r in enumerate(recommend(ctx, logs, k), 1):
        print(f"\n  #{rank}  OUTFIT  score={r['score']:.3f}")
        for it, why in zip(r["items"], r["item_reasons"]):
            print(f"      - [{it.category:<10}] {it.color} {it.type} "
                  f"({it.style}, {it.season})")
            for reason in why:
                print(f"          * {reason}")
        if r["missing"]:
            print(f"      ! incomplete outfit — missing {r['missing']}")
        print(f"      WHY: " + "; ".join(r["cohesion_reasons"][:3]))
    print()


# ============================================================================
# 8. MOCK DATA — synthetic wardrobe fixtures + outfit logs (demo only)
# ============================================================================

def mock_wardrobe() -> List[WardrobeItem]:
    W = WardrobeItem
    return [
        # ---- Tops ----
        W("T1", TOP, "Oversized T-Shirt", color="White", style="Streetwear",
          fit="Oversized", pattern="Plain", season="All Season",
          timesWorn=12, isFavoriteItem=True, lastWorn=2, createdAt=1),
        W("T2", TOP, "Slim Polo", color="Black", style="Smart Casual",
          fit="Slim", pattern="Plain", season="All Season",
          timesWorn=6, lastWorn=9, createdAt=2),
        W("T3", TOP, "Oxford Shirt", color="Navy", style="Old Money",
          fit="Regular", pattern="Plain", season="All Season",
          timesWorn=4, isFavoriteItem=True, lastWorn=20, createdAt=3),
        W("T4", TOP, "Flannel Shirt", color="Olive", style="Grunge",
          fit="Relaxed", pattern="Checked", season="Winter",
          timesWorn=7, lastWorn=5, createdAt=4),
        W("T5", TOP, "Graphic Tee", color="Gray", style="Streetwear",
          fit="Oversized", pattern="Graphic", season="Summer",
          timesWorn=9, lastWorn=1, createdAt=5),
        W("T6", TOP, "Linen Shirt", color="Beige", style="Casual",
          fit="Relaxed", pattern="Plain", season="Summer",
          timesWorn=3, lastWorn=30, createdAt=6),
        # ---- Bottoms ----
        W("B1", BOTTOM, "Straight Jeans", color="Denim", style="Casual",
          fit="Regular", pattern="Plain", season="All Season",
          timesWorn=14, isFavoriteItem=True, lastWorn=2, createdAt=7),
        W("B2", BOTTOM, "Slim Chinos", color="Black", style="Smart Casual",
          fit="Slim", pattern="Plain", season="All Season",
          timesWorn=8, lastWorn=6, createdAt=8),
        W("B3", BOTTOM, "Relaxed Chinos", color="Beige", style="Old Money",
          fit="Relaxed", pattern="Plain", season="Spring/Fall",
          timesWorn=5, lastWorn=15, createdAt=9),
        W("B4", BOTTOM, "Sweatpants", color="Gray", style="Sporty",
          fit="Relaxed", pattern="Plain", season="Winter",
          timesWorn=10, lastWorn=3, createdAt=10),
        W("B5", BOTTOM, "Cargo Pants", color="Olive", style="Streetwear",
          fit="Oversized", pattern="Camo", season="All Season",
          timesWorn=4, lastWorn=8, createdAt=11),
        # ---- Shoes ----
        W("S1", SHOES, "Sneakers", color="White", style="Streetwear",
          fit="Regular", pattern="Plain", season="All Season",
          timesWorn=16, isFavoriteItem=True, lastWorn=2, createdAt=12),
        W("S2", SHOES, "Loafers", color="Brown", style="Old Money",
          fit="Regular", pattern="Plain", season="All Season",
          timesWorn=3, lastWorn=25, createdAt=13),
        W("S3", SHOES, "Running Shoes", color="Black", style="Sporty",
          fit="Regular", pattern="Plain", season="All Season",
          timesWorn=11, lastWorn=1, createdAt=14),
        W("S4", SHOES, "Boots", color="Black", style="Grunge",
          fit="Regular", pattern="Plain", season="Winter",
          timesWorn=6, lastWorn=12, createdAt=15),
        # ---- Outerwear ----
        W("O1", OUTER, "Bomber Jacket", color="Black", style="Streetwear",
          fit="Regular", pattern="Plain", season="Winter",
          timesWorn=5, lastWorn=10, createdAt=16),
        W("O2", OUTER, "Trench Coat", color="Beige", style="Old Money",
          fit="Regular", pattern="Plain", season="Winter",
          timesWorn=2, lastWorn=40, createdAt=17),
        W("O3", OUTER, "Hoodie", color="Gray", style="Casual",
          fit="Oversized", pattern="Plain", season="Winter",
          timesWorn=9, lastWorn=4, createdAt=18),
        # ---- Extras ----
        W("A1", "Accessories", "Cap", color="Black", style="Streetwear",
          fit="Regular", pattern="Plain", season="All Season",
          timesWorn=7, lastWorn=3, createdAt=19),
        W("W1", "Watches", "Silver Watch", color="Gray", style="Minimalist",
          fit="Regular", pattern="Plain", season="All Season",
          timesWorn=12, isFavoriteItem=True, lastWorn=2, createdAt=20),
        W("G1", "Bags", "Leather Bag", color="Brown", style="Old Money",
          fit="Regular", pattern="Plain", season="All Season",
          timesWorn=4, lastWorn=18, createdAt=21),
        W("J1", "Jewelry", "Silver Chain", color="Gray", style="Y2K",
          fit="Regular", pattern="Plain", season="All Season",
          timesWorn=0, lastWorn=None, createdAt=22),   # brand-new, never worn
    ]


def mock_logs() -> List[OutfitLog]:
    L = OutfitLog
    return [
        L("o1",  ["T1", "B1", "S1", "A1"], "Casual", "mild", 6),
        L("o2",  ["T1", "B1", "S1", "W1"], "Casual", "mild", 13),
        L("o3",  ["T5", "B1", "S1", "A1"], "Casual", "hot", 9),
        L("o4",  ["T2", "B2", "S2", "W1", "G1"], "Work", "mild", 4),
        L("o5",  ["T3", "B3", "S2", "W1"], "Work", "mild", 11),
        L("o6",  ["T4", "B5", "S4", "O1"], "Casual", "cold", 8),
        L("o7",  ["T5", "B4", "S3", "O3"], "Casual", "cold", 2),
        L("o8",  ["T1", "B4", "S3"], "Gym", "cold", 1),
        L("o9",  ["T6", "B3", "S1"], "Travel", "hot", 15),
        L("o10", ["T2", "B2", "S3"], "School", "mild", 5),
        L("o11", ["T1", "B1", "S1", "J1"], "Casual", "mild", 20),
        L("o12", ["T3", "B2", "S2", "W1", "G1"], "Formal", "mild", 25),
    ]


# ============================================================================
# 9. DEMO — run: python recommender.py
# ============================================================================

NOW_MILLIS = 1_760_000_000_000   # fixed so the demo is fully deterministic


def main() -> None:
    wardrobe = mock_wardrobe()
    logs = mock_logs()

    # Scenario A — mature profile: office day, mild weather
    ctx_a = Context(occasion="Work", weather="mild", style="Smart Casual",
                    color_pref="Navy", shoe_pref="Loafers", mood="Focused",
                    wardrobe=wardrobe)
    print_recommendations("Work day, mild weather, Smart Casual / Focused mood",
                          ctx_a, logs, k=3)

    # Scenario B — mature profile: cold weekend, streetwear mood
    ctx_b = Context(occasion="Casual", weather="cold", style="Streetwear",
                    color_pref="Any", shoe_pref="Any", mood="Bold",
                    wardrobe=wardrobe)
    print_recommendations("Cold weekend, Streetwear / Bold mood", ctx_b, logs, k=3)

    # Scenario C — COLD START: brand-new user, no outfit logs at all
    fresh_wardrobe = [WardrobeItem(i.id, category=i.category, type=i.type,
                                   color=i.color, secondaryColor=i.secondaryColor,
                                   style=i.style, fit=i.fit, pattern=i.pattern,
                                   season=i.season, timesWorn=i.timesWorn,
                                   isFavoriteItem=i.isFavoriteItem,
                                   lastWorn=i.lastWorn, createdAt=i.createdAt)
                      for i in wardrobe]
    ctx_c = Context(occasion="Casual", weather="mild", style="Casual",
                    color_pref="Any", shoe_pref="Sneakers", mood="Calm",
                    wardrobe=fresh_wardrobe)
    print_recommendations("COLD START — new user, zero outfit history",
                          ctx_c, logs=[], k=2)


if __name__ == "__main__":
    main()
