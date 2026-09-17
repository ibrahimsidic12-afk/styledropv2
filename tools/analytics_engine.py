"""
analytics_engine.py — StyleDrop Analytics Layer (Agent #19)

Pure Python standard library. No network, no third-party deps.
Reads the SAME field names as the real Room entity
app/src/main/java/com/example/models/WardrobeItem.kt and the wear-event
+ price/purchaseDate extensions declared in mock_data.py.

The 8 features:
  1. realtime_insights()      -> built on the existing total/category/top-color
  2. style_dna()              -> radar  (occasion / style / season / formality)
  3. cost_per_wear()          -> CPW dashboard + winners/losers
  4. color_distribution()     -> donut (primary + secondary colours)
  5. closet_health()          -> utilization / rotation / balance composite 0-100
  6. wear_calendar()          -> 365-day heatmap grid
  7. seasonal_coverage()      -> coverage gap analysis
  8. unused_items()           -> dead-stock report (timesWorn == 0)

Run:  python3 analytics_engine.py
Prints a text dashboard + JSON blobs consumed verbatim by ANALYTICS.md.
"""

import json
from collections import Counter, defaultdict
from datetime import date, datetime, timedelta

from mock_data import (make_wardrobe, CATEGORIES, CATEGORY_EMOJI, SEASONS,
                       OCCASIONS, COLOR_HEX, TODAY)

MS_DAY = 86_400_000

# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------

def _as_date(ms):
    return datetime.utcfromtimestamp(ms / 1000).date() if ms else None


def _days_since(d):
    return (TODAY - d).days if d else None


def _age_days(it):
    c = _as_date(it["createdAt"])
    return (TODAY - c).days if c else 0


# ---------------------------------------------------------------------------
# 1. REAL-TIME WARDROBE INSIGHTS  (extends the existing 3 stats)
# ---------------------------------------------------------------------------

def realtime_insights(items, events):
    total = len(items)
    by_cat = Counter(it["category"] for it in items)
    by_color = Counter(it["color"] for it in items)
    by_style = Counter(it["style"] for it in items)
    by_season = Counter(it["season"] for it in items)

    total_wears = sum(it["timesWorn"] for it in items)
    worn_items = [it for it in items if it["timesWorn"] > 0]
    most_worn = max(items, key=lambda it: it["timesWorn"], default=None)
    fav = sum(1 for it in items if it["isFavoriteItem"])
    brands = Counter(it["brand"] for it in items if it["brand"])

    # wear concentration: share of all wears held by the top 20% of items
    ranked = sorted(items, key=lambda it: it["timesWorn"], reverse=True)
    top20 = max(1, round(total * 0.2))
    conc = sum(it["timesWorn"] for it in ranked[:top20]) / total_wears if total_wears else 0

    return {
        "total_items": total,
        "total_wears_logged": total_wears,
        "avg_wears_per_item": round(total_wears / total, 2) if total else 0,
        "worn_at_least_once": len(worn_items),
        "never_worn": total - len(worn_items),
        "favorites": fav,
        "by_category": {c: by_cat.get(c, 0) for c in CATEGORIES},
        "by_color_top5": by_color.most_common(5),
        "by_style_top5": by_style.most_common(5),
        "by_season": {s: by_season.get(s, 0) for s in SEASONS},
        "top_brands": brands.most_common(5),
        "most_worn_item": {"type": most_worn["type"], "wears": most_worn["timesWorn"],
                           "category": most_worn["category"]} if most_worn else None,
        "wear_concentration_top20pct": round(conc, 3),
    }


# ---------------------------------------------------------------------------
# 2. STYLE DNA  (radar: occasion / style / season / formality)
# ---------------------------------------------------------------------------

# formality 0..1 seeded from the app's own style vocabulary (documented, not invented blindly)
STYLE_FORMALITY = {
    "Formal": 0.95, "Old Money": 0.85, "Smart Casual": 0.65, "Minimalist": 0.6,
    "Korean": 0.5, "Casual": 0.4, "Vintage": 0.5, "Streetwear": 0.35,
    "Y2K": 0.35, "Sporty": 0.2, "Grunge": 0.3, "Techwear": 0.4,
}
OCCASION_FORMALITY = {
    "Formal": 1.0, "Wedding": 0.95, "Work": 0.8, "Date": 0.6, "Party": 0.65,
    "College": 0.45, "School": 0.4, "Travel": 0.4, "Casual": 0.3,
    "Gym": 0.1, "Beach": 0.1,
}


def style_dna(items, events):
    """Return four normalised 0..1 axes + the top driver per axis."""
    style = Counter(it["style"] for it in items)
    season = Counter(it["season"] for it in items)

    # occasion axis is measured from ACTUAL WEAR EVENTS, not wardrobe composition
    occ = Counter(e["occasion"] for e in events) if events else Counter()
    occ_total = sum(occ.values())

    # formality axis = mean formality of what is actually worn (weighted by wear events)
    item_by_id = {it["id"]: it for it in items}
    f = []
    for e in events:
        it = item_by_id.get(e["itemId"])
        if it:
            f.append(STYLE_FORMALITY.get(it["style"], 0.4))
    formality_score = sum(f) / len(f) if f else 0.0

    n_style = len(items)
    top_style, top_style_n = style.most_common(1)[0] if style else ("-", 0)
    top_season, top_season_n = season.most_common(1)[0] if season else ("-", 0)
    top_occ, top_occ_n = occ.most_common(1)[0] if occ else ("-", 0)

    return {
        "axes": {
            # "dominance" of the leading style = how concentrated the closet is
            "style_dominance": round(top_style_n / n_style, 3) if n_style else 0,
            "season_balance": round(top_season_n / n_style, 3) if n_style else 0,
            "occasion_spread": round(len(occ) / len(OCCASIONS), 3) if occ else 0,
            "formality": round(formality_score, 3),
        },
        "top_style": top_style, "top_style_count": top_style_n,
        "top_season": top_season, "top_season_count": top_season_n,
        "top_occasion": top_occ, "top_occasion_count": top_occ_n,
        "occasions_used": len(occ), "occasions_total": len(OCCASIONS),
        "style_breakdown": style.most_common(),
    }


# ---------------------------------------------------------------------------
# 3. COST PER WEAR
# ---------------------------------------------------------------------------

def cost_per_wear(items):
    rows = []
    for it in items:
        cpw = round(it["price"] / it["timesWorn"], 2) if it["timesWorn"] > 0 else None
        rows.append({
            "id": it["id"], "type": it["type"], "category": it["category"],
            "price": it["price"], "wears": it["timesWorn"],
            "cpw": cpw, "age_days": _age_days(it),
        })
    worn = [r for r in rows if r["cpw"] is not None]
    worn.sort(key=lambda r: r["cpw"])
    total_spend = round(sum(it["price"] for it in items), 2)
    total_wears = sum(it["timesWorn"] for it in items)
    return {
        "total_spend": total_spend,
        "portfolio_cpw": round(total_spend / total_wears, 2) if total_wears else None,
        "best_value": worn[:5],
        "worst_value": worn[-5:][::-1],
        "dead_money": round(sum(r["price"] for r in rows if r["wears"] == 0), 2),
        "avg_price": round(total_spend / len(items), 2) if items else 0,
    }


# ---------------------------------------------------------------------------
# 4. COLOR DISTRIBUTION (donut)
# ---------------------------------------------------------------------------

def color_distribution(items):
    primary = Counter(it["color"] for it in items)
    secondary = Counter(it["secondaryColor"] for it in items if it["secondaryColor"])
    total = sum(primary.values())
    slices = []
    for name, n in primary.most_common():
        slices.append({
            "color": name, "count": n, "pct": round(100 * n / total, 1),
            "hex": COLOR_HEX.get(name, "#8A8A8A"),
        })
    # neutral vs chromatic split (mirrors the neutral rule used across the app's engines)
    neutrals = {"Black", "White", "Gray", "Beige", "Cream"}
    n_neutral = sum(n for c, n in primary.items() if c in neutrals)
    return {
        "slices": slices,
        "secondary_present": sum(secondary.values()),
        "secondary_top3": secondary.most_common(3),
        "neutral_share": round(n_neutral / total, 3) if total else 0,
        "chromatic_share": round(1 - (n_neutral / total), 3) if total else 0,
        "distinct_colors": len(primary),
    }


# ---------------------------------------------------------------------------
# 5. CLOSET HEALTH  (utilization / rotation / balance -> 0..100)
# ---------------------------------------------------------------------------

def closet_health(items, events):
    n = len(items)
    if n == 0:
        return {"score": 0, "grade": "Empty", "components": {}}

    # --- utilization (45): what fraction of the closet has been worn, weighted by recency
    worn = [it for it in items if it["timesWorn"] > 0]
    util_raw = len(worn) / n
    # recency factor: items worn in last 90d count as "live"
    live = 0
    for it in worn:
        d = _as_date(it["lastWorn"])
        if d and _days_since(d) <= 90:
            live += 1
    recency = live / n
    utilization = 100 * (0.6 * util_raw + 0.4 * recency)

    # --- rotation (35): evenness of wear (Gini-style). 1.0 = perfectly even
    wears = sorted(it["timesWorn"] for it in items)
    tot = sum(wears)
    if tot > 0:
        cum = 0
        gini_num = 0
        for i, w in enumerate(wears, 1):
            cum += w
            gini_num += (2 * i - n - 1) * w
        gini = gini_num / (n * tot)
        rotation = 100 * (1 - max(0.0, gini))
    else:
        rotation = 0

    # --- balance (20): category spread vs an ideal closet ratio
    ideal = {"Tops": 0.22, "Bottoms": 0.18, "Shoes": 0.14, "Outerwear": 0.12,
             "Accessories": 0.14, "Bags": 0.08, "Watches": 0.06, "Jewelry": 0.06}
    by_cat = Counter(it["category"] for it in items)
    l1 = sum(abs(by_cat.get(c, 0) / n - ideal[c]) for c in CATEGORIES)
    max_l1 = 2 * (1 - min(ideal.values()))  # worst-case normaliser
    balance = 100 * max(0.0, 1 - l1 / max_l1)

    score = round(0.45 * utilization + 0.35 * rotation + 0.20 * balance)
    grade = ("Excellent" if score >= 85 else "Healthy" if score >= 70 else
             "Fair" if score >= 50 else "Needs work")
    return {
        "score": score, "grade": grade,
        "components": {
            "utilization": round(utilization, 1),
            "rotation": round(rotation, 1),
            "balance": round(balance, 1),
        },
        "weights": {"utilization": 0.45, "rotation": 0.35, "balance": 0.20},
        "gini": round(gini, 3) if tot else None,
    }


# ---------------------------------------------------------------------------
# 6. WEAR CALENDAR HEATMAP
# ---------------------------------------------------------------------------

def wear_calendar(items, events, weeks=52):
    per_day = Counter(e["date"] for e in events)
    start = TODAY - timedelta(days=weeks * 7 - 1)
    grid, week = [], []
    d = start
    while d <= TODAY:
        c = per_day.get(d.isoformat(), 0)
        lvl = 0 if c == 0 else 1 if c == 1 else 2 if c <= 3 else 3 if c <= 5 else 4
        week.append({"date": d.isoformat(), "count": c, "level": lvl})
        if len(week) == 7:
            grid.append(week)
            week = []
        d += timedelta(days=1)
    if week:
        grid.append(week)

    counts = [v["count"] for w in grid for v in w]
    active = sum(1 for c in counts if c > 0)
    return {
        "weeks": len(grid),
        "days": len(counts),
        "active_days": active,
        "coverage_pct": round(100 * active / len(counts), 1) if counts else 0,
        "busiest_day": max(per_day.items(), key=lambda kv: kv[1]) if per_day else None,
        "max_in_a_day": max(counts) if counts else 0,
        "grid": grid,
    }


# ---------------------------------------------------------------------------
# 7. SEASONAL COVERAGE GAP
# ---------------------------------------------------------------------------

def seasonal_coverage(items):
    """A season is 'covered' when it holds >= MIN_ITEMS and is not top-heavy."""
    MIN_ITEMS = 6
    by_season = defaultdict(list)
    for it in items:
        by_season[it["season"]].append(it)
    n = len(items)
    out = {}
    for s in SEASONS:
        grp = by_season.get(s, [])
        cats = Counter(it["category"] for it in grp)
        core_ok = all(cats.get(c, 0) >= 1 for c in ("Tops", "Bottoms", "Shoes"))
        share = len(grp) / n if n else 0
        out[s] = {
            "count": len(grp), "share": round(share, 3),
            "has_core_outfit": core_ok,
            "missing_core": [c for c in ("Tops", "Bottoms", "Shoes") if cats.get(c, 0) == 0],
            "covered": len(grp) >= MIN_ITEMS and core_ok,
        }
    gaps = [s for s, v in out.items() if not v["covered"]]
    return {"min_items_rule": MIN_ITEMS, "seasons": out, "gaps": gaps}


# ---------------------------------------------------------------------------
# 8. UNUSED ITEMS REPORT
# ---------------------------------------------------------------------------

def unused_items(items, events):
    never = [it for it in items if it["timesWorn"] == 0]
    stale, recently = [], []
    for it in items:
        if it["timesWorn"] == 0:
            continue
        d = _as_date(it["lastWorn"])
        if not d:
            continue
        age = _days_since(d)
        (stale if age > 180 else recently).append({**it, "days_since_worn": age})
    total_value = round(sum(it["price"] for it in never), 2)
    return {
        "never_worn_count": len(never),
        "never_worn_pct": round(100 * len(never) / len(items), 1) if items else 0,
        "dormant_value": total_value,
        "never_worn": sorted(never, key=lambda it: -_age_days(it)),
        "stale_180d_count": sum(1 for it in items
                                if it["timesWorn"] > 0 and (_as_date(it["lastWorn"]) or TODAY)
                                and _days_since(_as_date(it["lastWorn"])) > 180),
    }


# ---------------------------------------------------------------------------
# DASHBOARD
# ---------------------------------------------------------------------------

def build_all(seed=42):
    items, events = make_wardrobe(seed=seed)
    return {
        "generated": TODAY.isoformat(),
        "insights": realtime_insights(items, events),
        "style_dna": style_dna(items, events),
        "cost_per_wear": cost_per_wear(items),
        "color_distribution": color_distribution(items),
        "closet_health": closet_health(items, events),
        "wear_calendar": {k: v for k, v in wear_calendar(items, events).items() if k != "grid"},
        "seasonal_coverage": seasonal_coverage(items),
        "unused_items": {k: v for k, v in unused_items(items, events).items()
                         if k != "never_worn"},
    }


def _print_dashboard(d):
    i, dna, cpw, col = d["insights"], d["style_dna"], d["cost_per_wear"], d["color_distribution"]
    h, cal, sc, un = d["closet_health"], d["wear_calendar"], d["seasonal_coverage"], d["unused_items"]
    line = "-" * 62
    print("=" * 62)
    print(f"  STYLEDROP ANALYTICS DASHBOARD        {d['generated']}")
    print("=" * 62)
    print(f"\n[1] REAL-TIME INSIGHTS\n{line}")
    print(f"  Total items ............ {i['total_items']}")
    print(f"  Wears logged ........... {i['total_wears_logged']}  (avg {i['avg_wears_per_item']}/item)")
    print(f"  Worn at least once ..... {i['worn_at_least_once']}   never worn: {i['never_worn']}")
    print(f"  Favourites ............. {i['favorites']}")
    print(f"  Wear concentration ..... top 20% of items hold {i['wear_concentration_top20pct']*100:.0f}% of wears")
    print(f"  Top colours ............ {', '.join(f'{c} ({n})' for c,n in i['by_color_top5'])}")
    print(f"  By category ............ " + "  ".join(
        f"{CATEGORY_EMOJI[c]}{c}:{i['by_category'][c] if False else n}"
        for c, n in [(c, i['by_category'][c]) for c in CATEGORIES] if n))

    print(f"\n[2] STYLE DNA  (radar axes, 0-1)\n{line}")
    for k, v in dna["axes"].items():
        bar = "#" * int(round(v * 20))
        print(f"  {k:<18} {v:>5.3f}  |{bar:<20}|")
    print(f"  -> leads: style={dna['top_style']}  season={dna['top_season']}  occasion={dna['top_occasion']}")
    print(f"  -> occasions exercised: {dna['occasions_used']}/{dna['occasions_total']}")

    print(f"\n[3] COST PER WEAR\n{line}")
    print(f"  Total spend ............ {cpw['total_spend']}")
    print(f"  Portfolio CPW .......... {cpw['portfolio_cpw']}  (avg price {cpw['avg_price']})")
    print(f"  Dead money (unworn) .... {cpw['dead_money']}")
    print("  Best value (lowest CPW):")
    for r in cpw["best_value"]:
        print(f"      {r['type'][:26]:<26} {r['cpw']:>7} /wear  ({r['wears']}x, {r['price']})")
    print("  Worst value (highest CPW):")
    for r in cpw["worst_value"]:
        print(f"      {r['type'][:26]:<26} {r['cpw']:>7} /wear  ({r['wears']}x, {r['price']})")

    print(f"\n[4] COLOR DISTRIBUTION (donut)\n{line}")
    for s in col["slices"]:
        bar = "=" * int(round(s["pct"] / 2))
        print(f"  {s['color']:<8} {s['hex']:<8} {s['count']:>3}  {s['pct']:>5.1f}% |{bar}")
    print(f"  neutral {col['neutral_share']*100:.0f}%  ·  chromatic {col['chromatic_share']*100:.0f}%  ·  {col['distinct_colors']} distinct")

    print(f"\n[5] CLOSET HEALTH\n{line}")
    print(f"  SCORE  {h['score']}/100   ->  {h['grade']}")
    for k, v in h["components"].items():
        print(f"      {k:<14} {v:>5}  (weight {h['weights'][k]})")
    print(f"      wear-evenness Gini {h['gini']}")

    print(f"\n[6] WEAR CALENDAR (heatmap)\n{line}")
    print(f"  {cal['active_days']}/{cal['days']} active days  ({cal['coverage_pct']}% coverage)  max {cal['max_in_a_day']}/day")
    print(f"  busiest ............... {cal['busiest_day']}")

    print(f"\n[7] SEASONAL COVERAGE GAP\n{line}")
    for s, v in sc["seasons"].items():
        flag = "OK " if v["covered"] else "GAP"
        print(f"  [{flag}] {s:<12} {v['count']:>2} items ({v['share']*100:>4.1f}%)  core outfit: {v['has_core_outfit']}")
    print(f"  -> GAPS: {', '.join(sc['gaps']) if sc['gaps'] else 'none'}")

    print(f"\n[8] UNUSED ITEMS REPORT\n{line}")
    print(f"  Never worn ............. {un['never_worn_count']} ({un['never_worn_pct']}% of closet)")
    print(f"  Dormant value .......... {un['dormant_value']}")
    print(f"  Stale >180 days ........ {un['stale_180d_count']}")
    print("=" * 62)


if __name__ == "__main__":
    data = build_all(seed=42)
    _print_dashboard(data)
    print("\n### JSON ###")
    print(json.dumps(data, indent=2, default=str))
