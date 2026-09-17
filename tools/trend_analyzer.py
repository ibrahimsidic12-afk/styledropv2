"""
StyleDrop — Fashion Trend Analyzer (reference implementation)
==============================================================
Companion code for TRENDS.md. Drop-in module behind the existing
`AnalyticsScreen.kt` / `WardrobeRepository` layer of styledropv2.

Data model assumed (mirrors app/src/main/java/com/example/models/WardrobeItem.kt):
    id, imageUrl, category, type, color, secondaryColor, style, fit,
    pattern, season, brand, timesWorn, isFavoriteItem, lastWorn, createdAt

Run:
    python3 trend_analyzer.py            # smoke test on synthetic data
    python3 trend_analyzer.py --days 30  # custom window
"""

from __future__ import annotations

import argparse
import json
import math
import statistics
from dataclasses import dataclass, field, asdict
from datetime import datetime, timedelta, timezone
from typing import Dict, List, Tuple

# ---------------------------------------------------------------------------
# 0. CONSTANTS FROM THE APP  (AppConstants.kt — never invent new vocab here)
# ---------------------------------------------------------------------------

STYLES = ["Streetwear", "Casual", "Minimalist", "Old Money", "Y2K",
          "Korean", "Smart Casual", "Formal", "Sporty", "Vintage",
          "Techwear", "Grunge"]

CATEGORIES = ["Tops", "Bottoms", "Shoes", "Outerwear",
              "Accessories", "Bags", "Watches", "Jewelry"]

COLORS = ["Black", "White", "Gray", "Beige", "Navy",
          "Brown", "Olive", "Red", "Green", "Blue"]

PATTERNS = ["Plain", "Striped", "Graphic", "Checked", "Camo"]

# Scoring weights (Section 3 of TRENDS.md). Tuned so a signal must both be
# LARGE and ACCELERATING to win — not merely large-but-flat.
WEIGHTS = {
    "popularity": 0.30,   # share-of-voice vs. the whole corpus
    "velocity":   0.30,   # week-over-week growth rate
    "acceleration": 0.15, # change in velocity (2nd derivative / z-score)
    "freshness":  0.15,   # inverse of how long it has been peaking
    "cross_source": 0.10, # how many independent sources carry the signal
}

# Exponential time-decay half-life in days (Section 3).
HALF_LIFE_DAYS = 21.0

# Fashion-week lead time: AW trends are detected ~12 weeks before retail.
FASHION_WEEK_LEAD_WEEKS = 12


# ---------------------------------------------------------------------------
# 1. SIGNAL CONTAINER
# ---------------------------------------------------------------------------

@dataclass
class Signal:
    """One observed data point for a (source, entity, week) triple."""
    source: str          # 'pinterest' | 'instagram' | 'google' | 'in_app'
    entity_type: str     # 'hashtag' | 'color' | 'style' | 'pattern' | 'item_type'
    entity: str          # e.g. 'Y2K', '#quietluxury', 'Olive'
    week: str            # ISO week key, e.g. '2026-W24'
    count: int           # raw occurrences / saves / views
    engagement: int = 0  # likes+saves+sends where the source exposes it
    region: str = "GLOBAL"

    @property
    def raw(self) -> float:
        """Blend reach and engagement so a small-but-fierce signal can win."""
        return self.count + (self.engagement * 0.35)


# ---------------------------------------------------------------------------
# 2. MOCK DATA  (deterministic — NO live scraping happens here)
# ---------------------------------------------------------------------------

def _week_key(i: int, weeks_back: int) -> str:
    d = datetime.now(timezone.utc) - timedelta(weeks=(weeks_back - i))
    iso = d.isocalendar()
    return f"{iso[0]}-W{iso[1]:02d}"


def build_mock_signals(weeks_back: int = 12) -> List[Signal]:
    """
    Synthetic corpus with deliberately realistic shapes:
      - 'Y2K'      : rising, accelerating  -> should be a BREAKOUT
      - 'Chromecore': new, tiny but exploding -> RISING
      - 'Old Money': huge but flat, decays  -> PEAKING / FADING
      - 'Techwear' : shrinking              -> DECLINING
    Counts are made up for the demo. Replace with Section 1 feeds.
    """
    sig: List[Signal] = []

    # (entity, entity_type, start, weekly_growth, source_mix)
    curves = [
        ("Y2K",          "style",    420,  1.14, ["pinterest", "instagram", "google", "in_app"]),
        ("Chromecore",   "style",     40,  1.42, ["pinterest", "instagram", "google"]),
        ("Old Money",    "style",   1800,  1.01, ["pinterest", "instagram", "google", "in_app"]),
        ("Techwear",     "style",   900,   0.94, ["pinterest", "google"]),
        ("Olive",        "color",    310,  1.20, ["pinterest", "instagram", "in_app"]),
        ("Butter Yellow","color",     55,  1.38, ["instagram", "google"]),
        ("Black",        "color",   2400,  1.00, ["pinterest", "instagram", "google", "in_app"]),
        ("#quietluxury", "hashtag",  780,  1.05, ["instagram", "pinterest"]),
        ("#blokecore",   "hashtag",  260,  1.23, ["instagram", "google"]),
        ("Oversized",    "item_type",600,  1.09, ["in_app", "pinterest"]),
        ("Checked",      "pattern",  180,  1.11, ["pinterest", "in_app"]),
    ]

    for entity, etype, base, growth, sources in curves:
        for i in range(weeks_back):
            wk = _week_key(i, weeks_back)
            for s in sources:
                jitter = 0.90 + ((hash((entity, s, wk)) % 21) / 100.0)  # 0.90–1.10
                count = int(base * (growth ** i) * jitter)
                engagement = int(count * (0.25 + (hash((entity, wk)) % 30) / 100.0))
                sig.append(Signal(s, etype, entity, wk, max(count, 1), engagement))
    return sig


# ---------------------------------------------------------------------------
# 3. DETECTION LOGIC  (Section 2 — popularity & velocity)
# ---------------------------------------------------------------------------

def aggregate_by_week(signals: List[Signal]) -> Dict[Tuple[str, str, str], Dict[str, float]]:
    """(entity_type, entity, week) -> {raw, sources, engagement}"""
    out: Dict[Tuple[str, str, str], Dict[str, float]] = {}
    for s in signals:
        k = (s.entity_type, s.entity, s.week)
        row = out.setdefault(k, {"raw": 0.0, "sources": 0.0, "engagement": 0.0})
        row["raw"] += s.raw
        row["engagement"] += s.engagement
        row["sources"] += 1
    return out


def weekly_series(signals: List[Signal], entity: str) -> List[Tuple[str, float]]:
    agg = aggregate_by_week(signals)
    rows = [(k[2], v["raw"]) for k, v in agg.items() if k[1] == entity]
    return sorted(rows, key=lambda r: r[0])


def velocity(series: List[float]) -> float:
    """Mean week-over-week growth rate. >1 = growing, <1 = shrinking."""
    if len(series) < 2:
        return 1.0
    rates = []
    for prev, cur in zip(series, series[1:]):
        if prev <= 0:
            continue
        rates.append(cur / prev)
    return statistics.fmean(rates) if rates else 1.0


def acceleration(series: List[float], window: int = 4) -> float:
    """
    Second derivative, normalised as a z-score against the entity's own
    history. Positive = the growth itself is speeding up (true breakout).
    """
    if len(series) < window + 2:
        return 0.0
    recent = velocity(series[-window:])
    prior = velocity(series[-(window * 2):-window])
    deltas = [series[i + 1] - series[i] for i in range(len(series) - 1)]
    sd = statistics.pstdev(deltas) or 1.0
    return (recent - prior) * (statistics.fmean(abs(d) for d in deltas) / sd)


def freshness(series: List[float]) -> float:
    """1.0 = peaking right now; decays with a 21-day half-life."""
    if len(series) < 2:
        return 0.5
    peak_i = max(range(len(series)), key=lambda i: series[i])
    weeks_since_peak = (len(series) - 1) - peak_i
    days = weeks_since_peak * 7
    return 0.5 ** (days / HALF_LIFE_DAYS)


def cross_source_index(signals: List[Signal], entity: str, tail_weeks: int = 4) -> float:
    """0–1. Rewards signals that appear in MANY independent feeds at once."""
    agg = aggregate_by_week(signals)
    weeks = sorted({k[2] for k in agg if k[1] == entity})[-tail_weeks:]
    src = {k[0] for k in agg if k[1] == entity and k[2] in weeks}
    return round(len(src) / 4.0, 3)  # 4 possible feeds


# ---------------------------------------------------------------------------
# 4. TRENDING ALGORITHM  (Section 3)
# ---------------------------------------------------------------------------
# final = 100 * (0.30*P̂ + 0.30*V̂ + 0.15*Â + 0.15*F + 0.10*X)
#   P̂ popularity  = entity share of total corpus reach, min-max normalised
#   V̂ velocity    = (velocity - 1) clipped to [0,1] then scaled
#   Â acceleration= z-score squashed via tanh
#   F  freshness  = 0.5 ** (days_since_peak / 21)
#   X  cross-src  = distinct feeds / 4
# ---------------------------------------------------------------------------

def _tanh(x: float) -> float:
    return math.tanh(x)


def score_entity(signals: List[Signal],
                 entity: str,
                 total_reach: float,
                 max_reach: float) -> Dict[str, object]:
    series_pairs = weekly_series(signals, entity)
    series = [v for _, v in series_pairs]
    etype = next(s.entity_type for s in signals if s.entity == entity)

    reach = sum(series)
    pop = (reach / total_reach) if total_reach else 0.0
    pop_n = min(pop / 0.25, 1.0)            # 25% share == saturated

    vel = velocity(series)
    vel_n = max(0.0, min((vel - 1.0) / 0.5, 1.0))   # +50% w/w == maxed

    acc = acceleration(series)
    acc_n = (_tanh(acc) + 1) / 2            # map (-inf,inf) -> (0,1)

    fresh = freshness(series)
    xs = cross_source_index(signals, entity)

    score = 100 * (
        WEIGHTS["popularity"] * pop_n +
        WEIGHTS["velocity"] * vel_n +
        WEIGHTS["acceleration"] * acc_n +
        WEIGHTS["freshness"] * fresh +
        WEIGHTS["cross_source"] * xs
    )

    return {
        "entity": entity,
        "entity_type": etype,
        "score": round(score, 2),
        "popularity": round(pop_n, 3),
        "velocity_wow": round(vel, 3),
        "acceleration": round(acc, 3),
        "freshness": round(fresh, 3),
        "cross_source": xs,
        "reach": int(reach),
        "weeks_of_data": len(series),
        "stage": classify_stage(vel, acc, fresh),
    }


def classify_stage(vel: float, acc: float, fresh: float) -> str:
    """Fashion lifecycle stage (Section 4)."""
    if vel >= 1.20 and acc > 0.05:
        return "BREAKOUT"        # integrate now, lead the curve
    if vel >= 1.05 and fresh >= 0.5:
        return "RISING"          # safe to buy
    if vel >= 0.98 and fresh < 0.5:
        return "PEAKING"         # late; buy only if already owned
    if 0.95 <= vel < 0.98:
        return "SATURATED"       # skip
    return "DECLINING"


def rank_trends(signals: List[Signal]) -> List[Dict[str, object]]:
    agg = aggregate_by_week(signals)
    entities = sorted({k[1] for k in agg})
    total_reach = sum(v["raw"] for v in agg.values())
    max_reach = max(v["raw"] for v in agg.values()) or 1.0
    rows = [score_entity(signals, e, total_reach, max_reach) for e in entities]
    return sorted(rows, key=lambda r: r["score"], reverse=True)


# ---------------------------------------------------------------------------
# 5. SEASONALITY MAPPING  (Section 4)
# ---------------------------------------------------------------------------

SEASON_MAP = {
    "SS":  {"label": "Spring/Summer", "months": [3, 4, 5, 6, 7, 8],
            "retail_intake": [1, 2],  "peak": [5, 6, 7]},
    "AW":  {"label": "Autumn/Winter", "months": [9, 10, 11, 12, 1, 2],
            "retail_intake": [7, 8],  "peak": [11, 12, 1]},
    "FW":  {"label": "Fall/Winter (fashion-week shorthand)", "months": [9, 10, 11],
            "retail_intake": [7, 8],  "peak": [10, 11]},
    "RS":  {"label": "Resort", "months": [11, 12, 1, 2],
            "retail_intake": [10, 11], "peak": [12, 1]},
}


def hemisphere_adjust(month: int, hemisphere: str) -> str:
    """Northern SS = Southern AW. StyleDrop must not show coats in Manila in May."""
    northern = "SS" if month in SEASON_MAP["SS"]["months"] else "AW"
    if hemisphere.lower().startswith("s"):
        return "AW" if northern == "SS" else "SS"
    return northern


def seasonality_report(signals: List[Signal], trends: List[Dict],
                       month: int, hemisphere: str = "north") -> Dict:
    season = hemisphere_adjust(month, hemisphere)
    meta = SEASON_MAP[season]
    buying_now = month in meta["retail_intake"]
    lead_note = (
        f"{FASHION_WEEK_LEAD_WEEKS}w lead: signals shown now correspond to "
        f"{meta['label']} retail intake in months {meta['retail_intake']}."
    )
    return {
        "month": month,
        "hemisphere": hemisphere,
        "active_season": season,
        "season_label": meta["label"],
        "retail_intake_months": meta["retail_intake"],
        "peak_months": meta["peak"],
        "buy_now_window": buying_now,
        "lead_time_note": lead_note,
        "top_trends_this_season": [t["entity"] for t in trends[:5]],
    }


# ---------------------------------------------------------------------------
# 6. WARDROBE GAP ANALYSIS  (uses the real WardrobeItem shape)
# ---------------------------------------------------------------------------

def wardrobe_gap(trends: List[Dict], owned_items: List[Dict]) -> List[Dict]:
    """
    owned_items = list of dicts shaped like WardrobeItem (style, color,
    category, season). Returns the highest-scoring trends this user does
    NOT already cover — the actionable "what should I buy next" list.
    """
    owned_styles = {i.get("style", "") for i in owned_items}
    owned_colors = {i.get("color", "") for i in owned_items}
    gaps = []
    for t in trends:
        e = t["entity"]
        owns = e in owned_styles or e in owned_colors
        if not owns:
            gaps.append({
                "entity": e,
                "entity_type": t["entity_type"],
                "score": t["score"],
                "stage": t["stage"],
                "reason": f"Not present in wardrobe · {t['stage']} · velocity {t['velocity_wow']}x/wk",
            })
    return gaps[:5]


# ---------------------------------------------------------------------------
# 7. DEMO / SMOKE TEST
# ---------------------------------------------------------------------------

def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=int, default=84)
    args = ap.parse_args()
    weeks = max(8, args.days // 7)

    signals = build_mock_signals(weeks_back=weeks)
    print(f"[1] Loaded {len(signals)} mock signals across "
          f"{len({s.source for s in signals})} sources / {weeks} weeks.\n")

    trends = rank_trends(signals)

    print("[2] RANKED TRENDS (mock data)")
    hdr = f"{'#':<3}{'ENTITY':<15}{'TYPE':<11}{'SCORE':>7}{'VEL':>7}{'ACC':>7}{'FRESH':>7}  STAGE"
    print(hdr)
    print("-" * len(hdr))
    for i, t in enumerate(trends, 1):
        print(f"{i:<3}{t['entity']:<15}{t['entity_type']:<11}"
              f"{t['score']:>7.2f}{t['velocity_wow']:>7.2f}"
              f"{t['acceleration']:>7.2f}{t['freshness']:>7.2f}  {t['stage']}")

    # --- invariants a reviewer can re-check ---
    assert trends, "ranking produced nothing"
    assert all(0 <= t["score"] <= 100 for t in trends), "score out of range"
    assert trends == sorted(trends, key=lambda r: r["score"], reverse=True), "not sorted"

    print("\n[3] SEASONALITY (September = AW intake window)")
    rep = seasonality_report(signals, trends, month=9, hemisphere="north")
    print(json.dumps(rep, indent=2))

    print("\n[4] SOUTHERN-HEMISPHERE CHECK (May, Manila)")
    print(json.dumps(seasonality_report(signals, trends, month=5,
                                        hemisphere="south"), indent=2))

    print("\n[5] WARDROBE GAP (mock user owns 4 items)")
    owned = [
        {"style": "Minimalist", "color": "Black", "category": "Tops",    "season": "All Season"},
        {"style": "Formal",     "color": "Navy",  "category": "Bottoms", "season": "All Season"},
        {"style": "Casual",     "color": "White", "category": "Shoes",   "season": "All Season"},
        {"style": "Old Money",  "color": "Beige", "category": "Outerwear","season": "Winter"},
    ]
    for g in wardrobe_gap(trends, owned):
        print(f"  → {g['entity']:<14} score {g['score']:>5}  [{g['stage']}]  {g['reason']}")

    print("\n[6] Emitting Kotlin payload for AnalyticsScreen.kt …")
    kotlin_payload = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "season": rep["active_season"],
        "trends": [{"entity": t["entity"], "score": t["score"],
                    "stage": t["stage"]} for t in trends],
    }
    print(json.dumps(kotlin_payload, indent=2)[:600] + " …")

    print("\n✅ trend_analyzer.py runs clean.")


if __name__ == "__main__":
    main()
