#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
color_engine.py
================
COLOR HARMONY ENGINE for Styledrop v2 (wardrobe / outfit builder).

Pure standard library (no numpy / no 3rd-party deps). Designed to be:
  * deterministic  (same input -> same score, no randomness)
  * explainable    (every score is decomposed into named components)
  * portable       (formulas mirror the Kotlin port in COLOR_AI.md)

Input : clothing item colours as hex strings  ("#1B2A4A", "C19A6B", "#fff")
Output: harmony score 0-100, a verdict humans can read
        ("These colours clash" / "Classic combination"), companion hexes.

Core pipeline: HEX -> RGB -> HSL -> hue geometry -> weighted score.

Author: Styledrop v2 / Agent #9 (Colour Harmony Engine)
"""

from __future__ import annotations

import json
import math
from dataclasses import dataclass, field
from itertools import combinations
from typing import Dict, List, Optional, Sequence, Tuple

# ----------------------------------------------------------------------------
# 0. Tunable thresholds  (single source of truth — no magic numbers elsewhere)
# ----------------------------------------------------------------------------

NEUTRAL_SAT_MAX = 0.12      # saturation at/below this  -> chromatic content is negligible
NEUTRAL_L_MIN = 0.09        # near-black
NEUTRAL_L_MAX = 0.90        # near-white
NEUTRAL_DARK_L = 0.30       # dark tones (charcoal/slate) read as neutral in fashion
NEUTRAL_DARK_S = 0.25       # ...provided saturation stays under this
HIGH_SAT = 0.62             # "loud" colour threshold

# hue-distance windows (degrees, circular distance 0..180)
W_MONO = 12                 # monochromatic
W_ANALOGOUS = 48            # analogous  (12 < dH <= 48)
W_TRIAD = 12                # |dH - 120| <= 12      -> triadic
W_SPLIT = 10                # |dH - 150| <= 10      -> split-complementary
W_COMP = 15                 # |dH - 180| <= 15      -> complementary
CLASH_LO, CLASH_HI = 48, 108  # 48 < dH < 108 and neither colour neutral -> clash zone

# score weights (must sum to 1.00)
W_HARMONY = 0.55            # pairwise harmony quality
W_LIGHT = 0.20              # value / lightness contrast
W_SAT = 0.15                # saturation balance
W_COHERENCE = 0.10          # how many pairs land on a recognised relation

CLASH_PENALTY_PER_PAIR = 22.0
CLASH_PENALTY_CAP = 40.0

RGB_NAME_MATCH_TOLERANCE = 45.0   # squared-distance cut-off when snapping to a named colour


# ----------------------------------------------------------------------------
# 1. Colour-space conversions  (HEX <-> RGB <-> HSL)
# ----------------------------------------------------------------------------

def clamp(x: float, lo: float = 0.0, hi: float = 1.0) -> float:
    return lo if x < lo else hi if x > hi else x


def normalize_hex(value: str) -> str:
    """Accept '#RRGGBB', 'RRGGBB', '#RGB', 'fff' -> '#RRGGBB' (upper case)."""
    if value is None:
        raise ValueError("colour is None")
    s = str(value).strip().lstrip("#")
    if len(s) == 3:
        s = "".join(ch * 2 for ch in s)
    if len(s) != 6:
        raise ValueError(f"invalid hex colour: {value!r}")
    try:
        int(s, 16)
    except ValueError as exc:
        raise ValueError(f"invalid hex colour: {value!r}") from exc
    return "#" + s.upper()


def hex_to_rgb(value: str) -> Tuple[int, int, int]:
    h = normalize_hex(value).lstrip("#")
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)


def rgb_to_hex(rgb: Sequence[int]) -> str:
    r, g, b = (int(clamp(round(c), 0, 255)) for c in rgb)
    return "#{:02X}{:02X}{:02X}".format(r, g, b)


def rgb_to_hsl(rgb: Sequence[int]) -> Tuple[float, float, float]:
    """RGB 0-255 -> (H degrees 0-360, S 0-1, L 0-1). Standard CSS/HSL model."""
    r, g, b = (c / 255.0 for c in rgb)
    mx, mn = max(r, g, b), min(r, g, b)
    d = mx - mn
    l = (mx + mn) / 2.0
    if d == 0:
        return 0.0, 0.0, l
    s = d / (1.0 - abs(2.0 * l - 1.0)) if l not in (0.0, 1.0) else 0.0
    if mx == r:
        h = ((g - b) / d) % 6.0
    elif mx == g:
        h = (b - r) / d + 2.0
    else:
        h = (r - g) / d + 4.0
    return (h * 60.0) % 360.0, clamp(s), clamp(l)


def hsl_to_rgb(h: float, s: float, l: float) -> Tuple[int, int, int]:
    """(H degrees, S 0-1, L 0-1) -> RGB 0-255."""
    h = h % 360.0
    s, l = clamp(s), clamp(l)
    c = (1.0 - abs(2.0 * l - 1.0)) * s
    x = c * (1.0 - abs((h / 60.0) % 2.0 - 1.0))
    m = l - c / 2.0
    if h < 60:
        r, g, b = c, x, 0.0
    elif h < 120:
        r, g, b = x, c, 0.0
    elif h < 180:
        r, g, b = 0.0, c, x
    elif h < 240:
        r, g, b = 0.0, x, c
    elif h < 300:
        r, g, b = x, 0.0, c
    else:
        r, g, b = c, 0.0, x
    return tuple(int(round((v + m) * 255)) for v in (r, g, b))  # type: ignore[return-value]


def hex_to_hsl(value: str) -> Tuple[float, float, float]:
    return rgb_to_hsl(hex_to_rgb(value))


def hsl_to_hex(h: float, s: float, l: float) -> str:
    return rgb_to_hex(hsl_to_rgb(h, s, l))


def hue_delta(h1: float, h2: float) -> float:
    """Circular hue distance in degrees, 0..180."""
    d = abs((h1 - h2) % 360.0)
    return d if d <= 180.0 else 360.0 - d


def relative_luminance(rgb: Sequence[int]) -> float:
    """WCAG relative luminance (used for real value-contrast, not just HSL L)."""
    def lin(c: float) -> float:
        c /= 255.0
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = rgb
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)


def contrast_ratio(c1: str, c2: str) -> float:
    """WCAG contrast ratio 1..21 between two clothing colours."""
    l1, l2 = relative_luminance(hex_to_rgb(c1)), relative_luminance(hex_to_rgb(c2))
    hi, lo = max(l1, l2), min(l1, l2)
    return (hi + 0.05) / (lo + 0.05)


# ----------------------------------------------------------------------------
# 2. Colour naming  (human-readable, the way a stylist would say it)
# ----------------------------------------------------------------------------

NAMED_COLORS: Dict[str, str] = {
    "#000000": "Black",        "#1B1B1B": "Near-Black",   "#36454F": "Charcoal",
    "#808080": "Grey",         "#A9A9A9": "Silver Grey",  "#D3D3D3": "Light Grey",
    "#FFFFFF": "White",        "#FAF7F0": "Ivory White",  "#FFFFF0": "Ivory",
    "#1B2A4A": "Navy",         "#22304F": "Midnight Navy", "#2C3E60": "Deep Blue",
    "#3B5998": "Denim Blue",   "#4169E1": "Royal Blue",   "#87CEEB": "Sky Blue",
    "#008080": "Teal",         "#40E0D0": "Turquoise",    "#98FF98": "Mint",
    "#6B8E23": "Olive",        "#228B22": "Forest Green", "#50C878": "Emerald",
    "#9CAF88": "Sage",         "#556B2F": "Dark Olive",   "#2E4A3B": "Bottle Green",
    "#F5F5DC": "Beige",        "#FFFDD0": "Cream",        "#C19A6B": "Camel",
    "#D2B48C": "Tan",          "#C3B091": "Khaki",        "#8B4513": "Brown",
    "#7B3F00": "Chocolate",    "#483C32": "Taupe",        "#928E85": "Stone",
    "#800020": "Burgundy",     "#800000": "Maroon",       "#B7410E": "Rust",
    "#E2725B": "Terracotta",   "#FF0000": "Red",          "#DC143C": "Crimson",
    "#FF7F50": "Coral",        "#FA8072": "Salmon",       "#FF7F00": "Orange",
    "#FFBF00": "Amber",        "#FFDB58": "Mustard",      "#D4AF37": "Gold",
    "#B08A4F": "Antique Gold", "#FFFF00": "Yellow",       "#E6E6FA": "Lavender",
    "#C8A2C8": "Lilac",        "#800080": "Purple",       "#8E4585": "Plum",
    "#FF00FF": "Magenta",      "#FF00AA": "Fuchsia",      "#FFC0CB": "Pink",
    "#FF69B4": "Hot Pink",     "#708090": "Slate",        "#5A6673": "Slate Grey",
    "#FFCBA4": "Peach",        "#DDA0DD": "Mauve",        "#C48B9F": "Dusty Rose",
}

_RGB_NAMED = {k: hex_to_rgb(k) for k in NAMED_COLORS}

HUE_BANDS: List[Tuple[float, float, str]] = [
    (0, 15, "Red"), (15, 45, "Orange"), (45, 70, "Yellow"), (70, 90, "Chartreuse"),
    (90, 150, "Green"), (150, 165, "Spring Green"), (165, 195, "Teal"),
    (195, 210, "Cyan"), (210, 240, "Blue"), (240, 265, "Indigo"),
    (265, 300, "Violet"), (300, 330, "Magenta"), (330, 345, "Pink"), (345, 360, "Rose"),
]


def describe_color(value: str) -> str:
    """Fashion-friendly name: snaps to a known named colour, else describes it."""
    hexv = normalize_hex(value)
    if hexv in NAMED_COLORS:
        return NAMED_COLORS[hexv]

    rgb = hex_to_rgb(hexv)
    best, best_d = None, float("inf")
    for hx, name in NAMED_COLORS.items():
        nr, ng, nb = _RGB_NAMED[hx]
        d = (rgb[0] - nr) ** 2 + (rgb[1] - ng) ** 2 + (rgb[2] - nb) ** 2
        if d < best_d:
            best, best_d = name, d
    if best is not None and math.sqrt(best_d) <= RGB_NAME_MATCH_TOLERANCE:
        return best

    h, s, l = hex_to_hsl(hexv)
    if s <= NEUTRAL_SAT_MAX:
        return "Light Grey" if l > 0.6 else "Grey" if l > 0.3 else "Charcoal"
    if l >= NEUTRAL_L_MAX:
        return "Off-White"
    if l <= NEUTRAL_L_MIN:
        return "Near-Black"

    band = next((n for lo, hi, n in HUE_BANDS if lo <= h < hi), "Red")
    tone = ("Pale" if l > 0.82 else "Light" if l > 0.68 else
            "Deep" if l < 0.22 else "Dark" if l < 0.38 else
            "Muted" if s < 0.30 else "Vivid" if s > 0.75 else "")
    return f"{tone} {band}".strip()


def is_neutral(value: str) -> bool:
    """
    Achromatic-ish. Fashion treats a colour as neutral when either:
      * saturation is very low (true grey family), or
      * it is a dark, low-chroma tone (charcoal / slate / off-black), or
      * it is effectively black or white.
    Navy, olive and forest green stay chromatic (their saturation is well above
    the dark-tone band), which is what keeps complementary logic meaningful.
    """
    _, s, l = hex_to_hsl(value)
    return (s <= NEUTRAL_SAT_MAX
            or (l <= NEUTRAL_DARK_L and s <= NEUTRAL_DARK_S)
            or l <= NEUTRAL_L_MIN
            or l >= NEUTRAL_L_MAX)


# ----------------------------------------------------------------------------
# 3. Harmony classification  (the colour-theory core)
# ----------------------------------------------------------------------------

@dataclass
class HarmonyRelation:
    name: str
    base_score: float          # 0-100 quality of this relation
    explanation: str


REL_NEUTRAL_NEUTRAL = HarmonyRelation(
    "neutral + neutral", 88.0,
    "Two neutrals never fight — the look reads calm and deliberate.")
REL_NEUTRAL_ACCENT = HarmonyRelation(
    "neutral + accent", 90.0,
    "A neutral grounds the accent colour; this is the safest pairing in fashion.")
REL_MONOCHROMATIC = HarmonyRelation(
    "monochromatic", 85.0,
    "Same hue family, different lightness — tonal and elegant.")
REL_TONAL_EXACT = HarmonyRelation(
    "tonal repeat", 78.0,
    "Same colour twice: safe but flat — vary texture or lightness.")
REL_ANALOGOUS = HarmonyRelation(
    "analogous", 88.0,
    "Neighbouring hues on the wheel — inherently pleasing, low tension.")
REL_TRIADIC = HarmonyRelation(
    "triadic", 90.0,
    "Three hues evenly spaced at 120°; balanced and lively.")
REL_SPLIT_COMPLEMENTARY = HarmonyRelation(
    "split-complementary", 86.0,
    "A hue plus the two neighbours of its opposite; contrast without a hard jump.")
REL_COMPLEMENTARY = HarmonyRelation(
    "complementary", 92.0,
    "Opposite hues at 180° — maximum contrast, classic and confident.")
REL_HIGH_CONTRAST = HarmonyRelation(
    "high-contrast near-opposite", 72.0,
    "Close to complementary but off by a few degrees — bold, slightly uneasy.")
REL_CLASH = HarmonyRelation(
    "clash zone", 30.0,
    "Hues 48-108° apart fight: too far to blend, too close to contrast cleanly.")
REL_OFF = HarmonyRelation(
    "off-harmony", 60.0,
    "Neither neighbour nor opposite — no clear colour-theory relationship.")


def classify_pair(hex_a: str, hex_b: str) -> Dict:
    """Classify the colour-theory relationship between two garments."""
    a, b = normalize_hex(hex_a), normalize_hex(hex_b)
    ha, sa, la = hex_to_hsl(a)
    hb, sb, lb = hex_to_hsl(b)
    na, nb = is_neutral(a), is_neutral(b)
    dh = hue_delta(ha, hb)

    if na and nb:
        rel = REL_NEUTRAL_NEUTRAL
    elif na or nb:
        rel = REL_NEUTRAL_ACCENT
    elif dh <= W_MONO:
        rel = REL_TONAL_EXACT if abs(la - lb) < 0.10 else REL_MONOCHROMATIC
    elif dh <= W_ANALOGOUS:
        rel = REL_ANALOGOUS
    elif abs(dh - 120.0) <= W_TRIAD:
        rel = REL_TRIADIC
    elif abs(dh - 150.0) <= W_SPLIT:
        rel = REL_SPLIT_COMPLEMENTARY
    elif abs(dh - 180.0) <= W_COMP:
        rel = REL_COMPLEMENTARY
    elif CLASH_LO < dh < CLASH_HI:
        rel = REL_CLASH
    elif dh >= 160.0:
        rel = REL_HIGH_CONTRAST
    else:
        rel = REL_OFF

    # Loud clash: both colours strongly saturated AND in the clash wedge
    loud = rel is REL_CLASH and sa > HIGH_SAT and sb > HIGH_SAT
    score = rel.base_score - (8.0 if loud else 0.0)

    return {
        "a": a, "b": b,
        "a_name": describe_color(a), "b_name": describe_color(b),
        "hue_delta": round(dh, 1),
        "relation": rel.name,
        "score": round(score, 1),
        "loud_clash": loud,
        "explanation": rel.explanation,
    }


# ----------------------------------------------------------------------------
# 4. Outfit scoring  (deterministic, decomposed, explainable)
# ----------------------------------------------------------------------------

@dataclass
class OutfitAnalysis:
    colours: List[str] = field(default_factory=list)
    names: List[str] = field(default_factory=list)
    score: float = 0.0
    verdict: str = ""
    summary: str = ""
    dominant_relation: str = ""
    pairs: List[Dict] = field(default_factory=list)
    components: Dict[str, float] = field(default_factory=dict)
    clash_count: int = 0
    companions: Dict[str, List[Dict]] = field(default_factory=dict)

    def to_dict(self) -> Dict:
        return {
            "colours": self.colours, "names": self.names,
            "score": self.score, "verdict": self.verdict,
            "summary": self.summary, "dominant_relation": self.dominant_relation,
            "clash_count": self.clash_count,
            "components": self.components, "pairs": self.pairs,
            "companions": self.companions,
        }


def _lightness_contrast_score(hsls: Sequence[Tuple[float, float, float]]) -> float:
    """Reward outfits that separate in value (dark vs light) — depth reads as style."""
    ls = [l for _, _, l in hsls]
    spread = max(ls) - min(ls)
    lums = [relative_luminance(hsl_to_rgb(*h)) for h in hsls]
    lum_spread = max(lums) - min(lums)
    base = 45.0 + 40.0 * min(spread / 0.35, 1.0) + 15.0 * min(lum_spread / 0.55, 1.0)
    return round(clamp(base, 30.0, 100.0), 1)


def _saturation_balance_score(hsls: Sequence[Tuple[float, float, float]]) -> float:
    """One loud colour + supporting cast = good. Three loud colours = shouty."""
    sats = [s for _, s, _ in hsls]
    high = sum(1 for s in sats if s > HIGH_SAT)
    score = 100.0 - max(0, high - 2) * 18.0
    if all(s < 0.18 for s in sats) and len(sats) > 1:
        score = min(score, 82.0)     # all-muted: tasteful but low energy
    return round(clamp(score, 20.0, 100.0), 1)


def analyze_outfit(hex_colours: Sequence[str], suggest: bool = True) -> OutfitAnalysis:
    """Score a whole outfit (2+ garment colours) from 0-100 with full reasoning."""
    colours: List[str] = []
    for c in hex_colours:
        if c is None or str(c).strip() == "":
            continue                      # WardrobeItem.secondaryColor defaults to ""
        colours.append(normalize_hex(c))
    if not colours:
        raise ValueError("analyze_outfit() needs at least one colour")

    hsls = [hex_to_hsl(c) for c in colours]
    result = OutfitAnalysis(colours=colours, names=[describe_color(c) for c in colours])

    # --- single item: nothing to harmonise, but tell the user what to add ---
    if len(colours) == 1:
        result.score = 70.0
        result.verdict = "Single item"
        result.summary = (f"{result.names[0]} on its own — add a neutral bottom "
                          f"and one accent for a complete look.")
        result.dominant_relation = "n/a"
        result.components = {"harmony": 70.0, "lightness_contrast": 55.0,
                             "saturation_balance": 100.0, "coherence": 100.0,
                             "clash_penalty": 0.0}
        if suggest:
            result.companions[colours[0]] = suggest_companions(colours[0])
        return result

    # --- pairwise harmony ---
    pairs = [classify_pair(colours[i], colours[j])
             for i, j in combinations(range(len(colours)), 2)]
    result.pairs = pairs
    harmony = sum(p["score"] for p in pairs) / len(pairs)

    recognised = sum(1 for p in pairs if p["relation"] not in ("clash zone", "off-harmony"))
    coherence = 100.0 * recognised / len(pairs)

    light_score = _lightness_contrast_score(hsls)
    sat_score = _saturation_balance_score(hsls)

    clash_pairs = [p for p in pairs if p["relation"] == "clash zone"]
    result.clash_count = len(clash_pairs)
    loud_clashes = sum(1 for p in clash_pairs if p["loud_clash"])
    clash_penalty = min(CLASH_PENALTY_PER_PAIR * len(clash_pairs)
                        + 10.0 * loud_clashes, CLASH_PENALTY_CAP)

    raw = (W_HARMONY * harmony + W_LIGHT * light_score
           + W_SAT * sat_score + W_COHERENCE * coherence) - clash_penalty
    score = round(clamp(raw, 0.0, 100.0), 1)

    # dominant relation = most frequent, ties broken by higher base score
    counts: Dict[str, int] = {}
    for p in pairs:
        counts[p["relation"]] = counts.get(p["relation"], 0) + 1
    result.dominant_relation = max(
        counts, key=lambda k: (counts[k], max(p["score"] for p in pairs if p["relation"] == k)))

    # --- verdict banding ---
    if clash_pairs:
        verdict = "These colours clash" if score < 55 else "Risky pairing"
    elif score >= 90:
        verdict = "Classic combination"
    elif score >= 78:
        verdict = "Harmonious — easy to wear"
    elif score >= 64:
        verdict = "Works, but could be tightened"
    elif score >= 50:
        verdict = "Mismatched"
    else:
        verdict = "These colours clash"
    result.verdict = verdict
    result.score = score
    result.components = {
        "harmony": round(harmony, 1),
        "lightness_contrast": light_score,
        "saturation_balance": sat_score,
        "coherence": round(coherence, 1),
        "clash_penalty": round(clash_penalty, 1),
    }

    # --- human summary ---
    parts = [f"{result.dominant_relation.replace('-', ' ')} outfit"]
    if clash_pairs:
        worst = min(clash_pairs, key=lambda p: p["score"])
        parts.append(f"{worst['a_name']} + {worst['b_name']} sit {worst['hue_delta']}° "
                     f"apart, right in the clash wedge")
    if light_score < 60:
        parts.append("values are too similar — add a darker or lighter piece for depth")
    if sat_score < 80:
        parts.append("too many loud colours competing — mute one of them")
    if len(colours) > 4:
        parts.append("4+ colours is hard to control; keep 3 dominant")
    if not parts[1:]:
        parts.append("balanced hue, value and saturation")
    head = parts[0].capitalize()
    body = "; ".join(parts[1:])
    result.summary = head + (f" — {body}" if body else "") + "."

    if suggest:
        anchor = colours[0]
        result.companions[anchor] = suggest_companions(anchor)
    return result


def harmony_score(hex_colours: Sequence[str]) -> float:
    """Convenience: just the 0-100 number."""
    return analyze_outfit(hex_colours, suggest=False).score


# ----------------------------------------------------------------------------
# 5. Companion suggestions  (colour theory turned into shoppable hexes)
# ----------------------------------------------------------------------------

def _mk(h: float, s: float, l: float, relation: str, reason: str) -> Dict:
    """Build a companion entry with sane, wearable saturation/lightness."""
    h = h % 360.0
    s = clamp(s, 0.10, 0.95)
    l = clamp(l, 0.18, 0.88)
    hx = hsl_to_hex(h, s, l)
    return {"hex": hx, "name": describe_color(hx), "relation": relation, "why": reason}


def suggest_companions(base_hex: str, count: int = 6) -> List[Dict]:
    """
    Companion colours that a stylist would actually put next to `base_hex`.
    Ordered by colour-theory strength: neutral anchor -> analogous -> complementary
    -> triadic -> split-complementary -> tonal.
    """
    h, s, l = hex_to_hsl(base_hex)

    # neutral anchor: a greige/camel pushed to the opposite end of the value scale
    neutral_l = 0.86 if l < 0.45 else 0.28
    neutral_s = 0.06 if l >= 0.45 else 0.12
    anchor = _mk(35.0, neutral_s, neutral_l, "neutral anchor",
                 "A neutral at the opposite value keeps the palette grounded.")

    out = [anchor]
    if s > NEUTRAL_SAT_MAX:
        out += [
            _mk(h + 180, s * 0.85, l, "complementary",
                "180° opposite — maximum contrast, classic and confident."),
            _mk(h + 30, s * 0.80, clamp(l + 0.08), "analogous",
                "+30° neighbour — blends with zero tension."),
            _mk(h - 30, s * 0.80, clamp(l - 0.08), "analogous",
                "-30° neighbour — blends with zero tension."),
            _mk(h + 120, s * 0.75, clamp(0.55 if l < 0.5 else 0.38), "triadic",
                "+120° triadic partner — balanced, lively third colour."),
            _mk(h - 120, s * 0.75, clamp(0.55 if l < 0.5 else 0.38), "triadic",
                "-120° triadic partner — balanced, lively third colour."),
            _mk(h + 150, s * 0.65, clamp(l + 0.15), "split-complementary",
                "Soft contrast: neighbour of the opposite hue."),
            _mk(h, s * 0.45, clamp(l + 0.25), "tonal",
                "Same hue, lighter — pairs as a tonal layer."),
        ]
    else:
        # a neutral base is best finished with ONE clear accent
        out += [
            _mk(0.0, 0.70, 0.45, "accent (warm)",
                "A neutral base can carry one saturated warm accent."),
            _mk(220.0, 0.55, 0.35, "accent (cool)",
                "Or one deep cool accent — never both."),
            _mk(35.0, 0.35, 0.55, "tonal",
                "Camel/tan reads as a tonal extension of the neutral."),
        ]
    return out[:count]


# ----------------------------------------------------------------------------
# 6. Wardrobe-level helpers  (what the Styledrop outfit builder actually calls)
# ----------------------------------------------------------------------------

def analyze_wardrobe(items: Sequence[Dict], top_n: int = 3) -> Dict:
    """
    `items` = the app's WardrobeItem rows, minimally:
        {"id": str, "category": str, "color": "#RRGGBB", "secondaryColor": "#.." (optional)}
    Returns: per-item clash flags + the best-scoring colour trios in the wardrobe.
    """
    clean: List[Dict] = []
    for it in items:
        try:
            primary = normalize_hex(it.get("color") or "")
        except (ValueError, TypeError):
            continue
        secondary = ""
        try:
            if it.get("secondaryColor"):
                secondary = normalize_hex(it["secondaryColor"])
        except (ValueError, TypeError):
            secondary = ""
        clean.append({"id": it.get("id", ""), "category": it.get("category", ""),
                      "primary": primary, "secondary": secondary,
                      "name": describe_color(primary)})

    combos = []
    for trio in combinations(clean, min(3, len(clean))):
        if len(trio) < 2:
            continue
        hexes = [t["primary"] for t in trio]
        analysis = analyze_outfit(hexes, suggest=False)
        combos.append({
            "item_ids": [t["id"] for t in trio],
            "colours": hexes,
            "names": analysis.names,
            "score": analysis.score,
            "verdict": analysis.verdict,
            "dominant_relation": analysis.dominant_relation,
            "pairs": analysis.pairs,
        })
    combos.sort(key=lambda c: c["score"], reverse=True)

    private = [c for c in combos if len({i["category"] for i in
               ({i["id"]: i for i in clean}[x] for x in c["item_ids"])}) >= 2]

    return {
        "item_count": len(clean),
        "named": [{"id": c["id"], "category": c["category"],
                   "hex": c["primary"], "name": c["name"],
                   "is_neutral": is_neutral(c["primary"]),
                   "has_secondary": bool(c["secondary"])} for c in clean],
        "best_outfits": (private or combos)[:top_n],
        "worst_outfits": sorted((private or combos), key=lambda c: c["score"])[:top_n],
    }


# ----------------------------------------------------------------------------
# 7. CLI demo / self-test
# ----------------------------------------------------------------------------

DEMO_CASES: List[Tuple[str, List[str]]] = [
    ("Navy + Camel (classic tailored)",            ["#1B2A4A", "#C19A6B"]),
    ("Red + Green (complementary)",                ["#FF0000", "#228B22"]),
    ("Black + White + Red (neutral anchor)",       ["#000000", "#FFFFFF", "#FF0000"]),
    ("Two loud clashes (red + chartreuse)",        ["#FF0000", "#B0FF00"]),
    ("Monochromatic blues",                        ["#2C3E60", "#3B5998", "#87CEEB"]),
    ("Analogous blues/teals",                      ["#4169E1", "#008080"]),
    ("Triadic primaries (red/blue/green)",         ["#FF0000", "#0000FF", "#228B22"]),
    ("Warm neutral layering (cream/camel/brown)",  ["#FFFDD0", "#C19A6B", "#8B4513"]),
    ("Single item (navy)",                         ["#1B2A4A"]),
    ("All-black + olive (streetwear)",             ["#1B1B1B", "#556B2F"]),
]


def _demo() -> None:
    print("=" * 74)
    print(" STYLEDROP COLOR HARMONY ENGINE — demo / self-test")
    print("=" * 74)
    for label, hexes in DEMO_CASES:
        a = analyze_outfit(hexes)
        print(f"\n▸ {label}")
        print(f"  colours : {'  '.join(f'{h} ({n})' for h, n in zip(a.colours, a.names))}")
        print(f"  score   : {a.score}/100   verdict: {a.verdict}")
        print(f"  relation: {a.dominant_relation}")
        print(f"  summary : {a.summary}")
        c = a.components
        print(f"  scoring : harmony {c['harmony']}  lightness {c['lightness_contrast']}  "
              f"sat {c['saturation_balance']}  coherence {c['coherence']}  "
              f"clash penalty -{c['clash_penalty']}")
        for p in a.pairs:
            flag = "  ⚠ LOUD CLASH" if p["loud_clash"] else ""
            print(f"    · {p['a_name']} + {p['b_name']} → {p['relation']} "
                  f"(ΔH {p['hue_delta']}°, {p['score']}){flag}")
        if a.companions:
            print("  companions for the anchor colour:")
            for c2 in list(a.companions.values())[0][:4]:
                print(f"    − {c2['hex']} {c2['name']:<14} [{c2['relation']}]")
    print("\n" + "=" * 74)
    print(" JSON output example (integration contract):")
    print("=" * 74)
    print(json.dumps(analyze_outfit(["#1B2A4A", "#C19A6B", "#FFFFFF"]).to_dict(),
                     indent=2)[:1400])


if __name__ == "__main__":
    _demo()
