#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
StyleDrop v2 — Wardrobe Classifier (Agent #11)
================================================
Automatic garment categorization for the StyleDrop Android app
(https://github.com/ibrahimsidic12-afk/styledropv2).

Given a clothing photo (URL or local path) plus optional text hints, predicts:
  - category  : one of ItemCategory.all  (Tops, Bottoms, Shoes, Outerwear,
                Accessories, Bags, Watches, Jewelry)
  - type      : free-text label shaped like the app's examples
                (e.g. "Oversized T-Shirt", "Slim Jeans")
  - color     : dominant hex + nearest name from AppConstants.colorPreferences
  - pattern   : one of AppConstants.patterns (Plain, Striped, Graphic, Checked, Camo)
  - season    : one of AppConstants.seasons (All Season, Summer, Winter, Spring/Fall)

Design contract (mirrors the Kotlin codebase):
  * Every emitted value is validated against the repo-derived ALLOWED_* lists
    before return. Anything that fails validation raises ValueError — the
    classifier can never drift outside the app's AddItemScreen dropdowns.
  * WardrobeItem fields filled (Kotlin side): category, type, color, pattern,
    season (+ secondaryColor left to the user).
  * Pure Python stdlib + Pillow. No numpy, no network ML, no native deps.

Usage:
    python3 classifier.py                       # runs the built-in demo suite
    python3 classifier.py path/to/photo.jpg     # classify one image
    python3 classifier.py https://.../img.jpg --hint "denim jacket"
"""
import argparse
import colorsys
import io
import json
import math
import os
import sys
import urllib.request
import warnings

warnings.filterwarnings("ignore", category=DeprecationWarning)

try:
    from PIL import Image, ImageFilter
except ImportError:  # pragma: no cover
    sys.stderr.write("Pillow is required: pip install Pillow\n")
    raise

# ---------------------------------------------------------------------------
# VOCABULARY — copied verbatim from the styledropv2 codebase
# ---------------------------------------------------------------------------
# Source: app/src/main/java/com/example/models/WardrobeItem.kt  -> object ItemCategory
ITEM_CATEGORY = {
    "TOP": "Tops",
    "BOTTOM": "Bottoms",
    "SHOES": "Shoes",
    "OUTERWEAR": "Outerwear",
    "ACCESSORY": "Accessories",
    "BAG": "Bags",
    "WATCH": "Watches",
    "JEWELRY": "Jewelry",
}
ALLOWED_CATEGORIES = list(ITEM_CATEGORY.values())  # ItemCategory.all

# Source: app/src/main/java/com/example/models/AppConstants.kt -> object AppConstants
ALLOWED_PATTERNS = ["Plain", "Striped", "Graphic", "Checked", "Camo"]
ALLOWED_SEASONS = ["All Season", "Summer", "Winter", "Spring/Fall"]
ALLOWED_COLOR_NAMES = ["Black", "White", "Gray", "Beige", "Navy", "Brown", "Olive"]
ALLOWED_FITS = ["Oversized", "Slim", "Regular", "Relaxed"]
ALLOWED_SHOE_TYPES = ["Sneakers", "Boots", "Loafers", "Sandals", "Running Shoes"]

# `type` is free text in the app (AddItemScreen OutlinedTextField), so the
# classifier emits canonical labels composed from the app's own vocabulary:
# fit prefix (AppConstants.fits) + garment noun.
TYPE_LEXICON = {
    "Tops": ["T-Shirt", "Polo Shirt", "Button-Up Shirt", "Hoodie", "Sweater",
             "Tank Top", "Blouse", "Crop Top"],
    "Bottoms": ["Jeans", "Trousers", "Shorts", "Joggers", "Chinos", "Skirt",
                "Sweatpants"],
    "Shoes": ALLOWED_SHOE_TYPES,  # mirrors AppConstants.shoePreferences
    "Outerwear": ["Jacket", "Bomber Jacket", "Denim Jacket", "Trench Coat",
                  "Puffer Jacket", "Blazer", "Hooded Coat", "Cardigan"],
    "Accessories": ["Cap", "Beanie", "Scarf", "Belt", "Sunglasses"],
    "Bags": ["Backpack", "Tote Bag", "Crossbody Bag", "Duffel Bag"],
    "Watches": ["Watch"],
    "Jewelry": ["Necklace", "Bracelet", "Ring", "Earrings", "Chain"],
}

# Text-hint keyword map (hint word -> category). Checked case-insensitively.
HINT_KEYWORDS = {
    "Outerwear": ["jacket", "coat", "blazer", "trench", "bomber", "parka",
                  "puffer", "cardigan", "windbreaker"],
    "Tops": ["shirt", "t-shirt", "tee", "polo", "hoodie", "sweater", "blouse",
             "tank", "crop top", "longsleeve", "knit"],
    "Bottoms": ["jeans", "pants", "trousers", "shorts", "joggers", "chinos",
                "skirt", "sweatpants"],
    "Shoes": ["sneaker", "shoe", "boot", "loafer", "sandal", "runner",
              "running shoes", "slipper"],
    "Bags": ["bag", "backpack", "tote", "purse", "duffel", "satchel"],
    "Accessories": ["cap", "hat", "beanie", "scarf", "belt", "sunglasses"],
    "Watches": ["watch", "timepiece"],
    "Jewelry": ["necklace", "bracelet", "ring", "earring", "chain", "pendant",
                "jewelry"],
}

# ---------------------------------------------------------------------------
# IMAGE LOADING
# ---------------------------------------------------------------------------

def load_image(source: str) -> Image.Image:
    """Load an image from a local path or an http(s) URL. Returns RGB PIL image."""
    if source.startswith(("http://", "https://")):
        req = urllib.request.Request(
            source, headers={"User-Agent": "StyleDropClassifier/1.0"})
        with urllib.request.urlopen(req, timeout=20) as resp:
            data = resp.read()
        img = Image.open(io.BytesIO(data))
    else:
        img = Image.open(source)
    return img.convert("RGB")


def _dist2(a, b):
    return (a[0] - b[0]) ** 2 + (a[1] - b[1]) ** 2 + (a[2] - b[2]) ** 2

# ---------------------------------------------------------------------------
# SUBJECT MASK + SILHOUETTE FEATURES
# ---------------------------------------------------------------------------

MASK_SIZE = 128  # analysis resolution


def build_mask(img: Image.Image):
    """
    Detect the garment as 'anything unlike the studio background'.
    Background reference = the 4 corner colors of the frame (typical
    flat-lay / e-commerce shots). Returns (mask 2D list of bool, subject bbox).
    """
    small = img.resize((MASK_SIZE, MASK_SIZE))
    w = h = MASK_SIZE
    px = small.load()
    corners = [px[0, 0], px[w - 1, 0], px[0, h - 1], px[w - 1, h - 1]]

    def attempt(tol2):
        m = [[False] * w for _ in range(h)]
        min_x, min_y, max_x, max_y, count = w, h, 0, 0, 0
        for y in range(h):
            for x in range(w):
                p = px[x, y]
                if all(_dist2(p, c) > tol2 for c in corners):
                    m[y][x] = True
                    count += 1
                    if x < min_x: min_x = x
                    if x > max_x: max_x = x
                    if y < min_y: min_y = y
                    if y > max_y: max_y = y
        return m, (min_x, min_y, max_x, max_y), count

    # Pass 1: normal tolerance. Pass 2: tight tolerance (white garment on
    # light studio background needs it). Fallback: full-frame mask.
    tol = 42 ** 2
    mask, bbox, count = attempt(tol)
    if count < w * h * 0.06 or bbox[2] <= bbox[0] or bbox[3] <= bbox[1]:
        mask, bbox, count = attempt(18 ** 2)
    if count < w * h * 0.06 or bbox[2] <= bbox[0] or bbox[3] <= bbox[1]:
        for y in range(h):
            for x in range(w):
                mask[y][x] = True
        bbox = (0, 0, w - 1, h - 1)
    return mask, bbox


def silhouette_features(mask, bbox) -> dict:
    """
    Geometric descriptors of the subject mask:
      aspect       subject bbox height / width  (jeans tall, shoes wide)
      fill_ratio   subject pixels / frame pixels
      taper        bottom-third width / top-third width
      legs_runs    median count of separate vertical runs in the lower
                   third (2 => a pair of trouser legs)
      waist_pinch  mid width vs mean(top, bottom) widths
    """
    w = h = MASK_SIZE
    x0, y0, x1, y1 = bbox
    bw, bh = x1 - x0 + 1, y1 - y0 + 1
    aspect = bh / max(1, bw)

    widths = []
    for y in range(y0, y1 + 1):
        xs = [x for x in range(x0, x1 + 1) if mask[y][x]]
        widths.append((max(xs) - min(xs) + 1) if xs else 0)

    third = max(1, bh // 3)
    top_w = sum(widths[:third]) / third
    mid_w = sum(widths[third:2 * third]) / third
    bot_w = sum(widths[2 * third:]) / third
    subject_in_bbox = sum(widths)
    fill_bbox = subject_in_bbox / max(1, bw * bh)   # solidity inside the bbox

    def runs():
        # median count of separate subject runs per row in the lower third
        counts = []
        lower = range(y1 - third + 1, y1 + 1)
        for y in lower:
            r, n, prev = 0, 0, False
            for x in range(x0, x1 + 1):
                on = mask[y][x]
                if on and not prev:
                    n += 1
                prev = on
            counts.append(n)
        counts = [c for c in counts if c > 0]
        return sorted(counts)[len(counts) // 2] if counts else 0

    legs = runs()
    return {
        "aspect": aspect,
        "fill_ratio": fill_bbox,   # solidity of subject within its own bbox
        "taper": bot_w / max(1.0, top_w),
        "waist_pinch": mid_w / max(1.0, (top_w + bot_w) / 2.0),
        "legs_runs": legs,
        "top_w": top_w, "bot_w": bot_w,
    }

# ---------------------------------------------------------------------------
# COLOR ANALYSIS (subject pixels only)
# ---------------------------------------------------------------------------

def dominant_color(img: Image.Image, mask, bbox, k: int = 5):
    """Quantize the subject pixels and return (hex, rgb) of the dominant color."""
    small = img.resize((MASK_SIZE, MASK_SIZE))
    px = small.load()
    x0, y0, x1, y1 = bbox
    pts = [(px[x, y]) for y in range(y0, y1 + 1) for x in range(x0, x1 + 1)
           if mask[y][x]]
    if not pts:  # degenerate mask
        pts = [px[MASK_SIZE // 2, MASK_SIZE // 2]]
    # bucket to 4-bit per channel then count (median-cut substitute)
    buckets = {}
    for r, g, b in pts:
        key = (r // 16, g // 16, b // 16)
        buckets[key] = buckets.get(key, 0) + 1
    # dominant bucket = mode; average true pixels inside it for the exact hex
    best = max(buckets.items(), key=lambda kv: kv[1])[0]
    sel = [(r, g, b) for r, g, b in pts
           if (r // 16, g // 16, b // 16) == best]
    n = len(sel)
    dom = tuple(sum(c[i] for c in sel) // n for i in range(3))
    return "#%02X%02X%02X" % dom, dom


def nearest_color_name(rgb):
    """
    Map an RGB to the nearest AppConstants.colorPreferences entry (minus 'Any').
    Reference swatches: Black #000000, White #FFFFFF, Gray #808080,
    Beige #E8DCC4, Navy #1B2A4A, Brown #6B4226, Olive #556B2F.
    """
    refs = {
        "Black": (0, 0, 0),
        "White": (255, 255, 255),
        "Gray": (128, 128, 128),
        "Beige": (232, 220, 196),
        "Navy": (27, 42, 74),
        "Brown": (107, 66, 38),
        "Olive": (85, 107, 47),
    }
    best, best_d = None, 1e9
    for name, ref in refs.items():
        d = _dist2(rgb, ref)
        if d < best_d:
            best, best_d = name, d
    return best

# ---------------------------------------------------------------------------
# CATEGORY SCORING (silhouette heuristics)
# ---------------------------------------------------------------------------

def score_categories(feats: dict, rgb, img: Image.Image, mask, bbox) -> dict:
    """
    Heuristic scoring per category. Calibrated on flat-lay/studio shots;
    a text hint (processed separately) overrides the winner when present.
    """
    a, taper, pinch = feats["aspect"], feats["taper"], feats["waist_pinch"]
    fill, legs = feats["fill_ratio"], feats["legs_runs"]
    s = {c: 0.0 for c in ALLOWED_CATEGORIES}

    # Strongest signal: two separate leg runs low in the frame => trousers
    if legs >= 2 and a > 0.9:
        s["Bottoms"] += 3.0
    # Wide, low object => shoes (or a lay-flat bag)
    if a < 0.75:
        s["Shoes"] += 2.2
        s["Bags"] += 1.2
    # Square-ish garment laid flat => top / outerwear
    if 0.75 <= a < 1.30:
        s["Tops"] += 2.0
        s["Outerwear"] += 1.5 if a < 1.05 else 0.8
        s["Bags"] += 1.0
        s["Shoes"] += 0.5
    # Tall single column
    if a >= 1.05:
        s["Tops"] += 1.0          # hoodies/dresses-like knits
        s["Outerwear"] += 1.2
        s["Bottoms"] += 0.8
        s["Accessories"] += 0.6   # scarves
    if a >= 1.9:
        s["Bottoms"] += 1.0
        s["Accessories"] += 1.0

    # Small/sparse subject inside its own bbox = thin accessory-class object
    # (necklace, scarf, belt, strap). A solid garment fills its bbox.
    if fill < 0.42:
        s["Accessories"] += 1.2
        s["Watches"] += 1.0
        s["Jewelry"] += 1.2
        s["Bags"] += 0.4
    if a > 2.6:
        s["Jewelry"] += 1.0       # chains, necklaces
        s["Watches"] += 0.6

    # Metallic mid-gray look => watch / jewelry likelihood
    hh, ll, ss = colorsys.rgb_to_hls(*[v / 255.0 for v in rgb])
    if ss < 0.25 and 0.40 < ll < 0.75:
        s["Watches"] += 0.6
        s["Jewelry"] += 0.6
    # High-saturation tiny subject => jewelry often
    if ss > 0.6 and fill < 0.35:
        s["Jewelry"] += 0.4

    # Camo fabric bonus: many distinct hues in one garment
    if hue_spread_of(img, mask, bbox) >= 3:
        s["Outerwear"] += 0.3

    return s


def hue_spread_of(img: Image.Image, mask, bbox) -> int:
    """Count distinct major hues among the subject's major colors."""
    ref = img.resize((MASK_SIZE, MASK_SIZE))  # bbox is in MASK_SIZE space
    x0, y0, x1, y1 = bbox
    crop = ref.crop((max(0, x0), max(0, y0),
                     min(MASK_SIZE, x1 + 1), min(MASK_SIZE, y1 + 1)))
    cpx = crop.load()
    cw, ch = crop.size
    # flatten background to subject mean so it never counts as a color
    vals = [sum(cpx[x, y]) // 3
            for y in range(ch) for x in range(cw)
            if y0 + y < MASK_SIZE and x0 + x < MASK_SIZE and mask[y0 + y][x0 + x]]
    mean = sum(vals) // max(1, len(vals))
    for y in range(ch):
        for x in range(cw):
            gy, gx = y0 + y, x0 + x
            if gy >= MASK_SIZE or gx >= MASK_SIZE or not mask[gy][gx]:
                cpx[x, y] = (mean, mean, mean)
    q = crop.resize((48, 48)).quantize(colors=8,
                                       method=Image.MEDIANCUT).convert("RGB")
    total_px = 48 * 48
    cols = {}
    for c in q.getdata():
        cols[c] = cols.get(c, 0) + 1
    ranked = sorted(cols.items(), key=lambda kv: -kv[1])
    major = [(c, n) for c, n in ranked if n > total_px * 0.12] or ranked[:1]
    hues = set()
    for c, _ in major:
        hh, ll, ss = colorsys.rgb_to_hls(*[v / 255.0 for v in c])
        if ss > 0.20:
            hues.add(int(hh * 12) % 12)
    return len(hues)

# ---------------------------------------------------------------------------
# PATTERN ANALYSIS
# ---------------------------------------------------------------------------

def classify_pattern(img: Image.Image, mask, bbox) -> tuple:
    """
    Decide among AppConstants.patterns using:
      * color count + hue spread   -> Camo / Graphic
      * directional edge peaks     -> Striped vs Checked
      * low edge energy + 1 color  -> Plain
    Background pixels inside the bbox are replaced with the subject's mean
    luminance so silhouette borders never read as texture.
    Returns (pattern, confidence 0..1).
    """
    # bbox is in MASK_SIZE coordinate space — resize first, then crop
    ref = img.resize((MASK_SIZE, MASK_SIZE))
    x0, y0, x1, y1 = bbox
    crop = ref.crop((max(0, x0), max(0, y0),
                     min(MASK_SIZE, x1 + 1), min(MASK_SIZE, y1 + 1)))
    cw, ch = crop.size
    cpx = crop.load()

    # subject mean luminance, then flatten background to it
    vals = [sum(cpx[x, y]) // 3
            for y in range(ch) for x in range(cw)
            if y0 + y < MASK_SIZE and x0 + x < MASK_SIZE and mask[y0 + y][x0 + x]]
    mean = sum(vals) // max(1, len(vals))
    for y in range(ch):
        for x in range(cw):
            gy, gx = y0 + y, x0 + x
            if gy >= MASK_SIZE or gx >= MASK_SIZE or not mask[gy][gx]:
                cpx[x, y] = (mean, mean, mean)
    small = crop.resize((96, 96)).convert("L")
    edges = small.filter(ImageFilter.FIND_EDGES)
    ep = edges.load()
    w = h = 96
    row_energy = [sum(ep[x, y] for x in range(w)) for y in range(h)]
    col_energy = [sum(ep[x, y] for y in range(h)) for x in range(w)]
    total = sum(row_energy) + sum(col_energy) + 1
    edge_density = total / (2 * w * h * 255)

    def peak_positions(v):
        m = sum(v) / len(v)
        return [i for i in range(2, len(v) - 2)
                if v[i] > m * 1.6 and v[i] >= v[i - 1] and v[i] >= v[i + 1]]

    def regular(pos, size):
        """True when peaks are spread across the axis with consistent gaps
        (real stripes/plaid). Clustered peaks (a trouser leg gap, a pocket
        seam) fail the spread test."""
        if len(pos) < 4:
            return False
        gaps = [b - a for a, b in zip(pos, pos[1:])]
        mean_gap = sum(gaps) / len(gaps)
        if mean_gap <= 0:
            return False
        var = sum((g - mean_gap) ** 2 for g in gaps) / len(gaps)
        spread = (pos[-1] - pos[0]) / size
        return (var ** 0.5) / mean_gap < 0.65 and spread > 0.55

    rp_pos, cp_pos = peak_positions(row_energy), peak_positions(col_energy)
    row_reg, col_reg = regular(rp_pos, h), regular(cp_pos, w)
    row_peaks, col_peaks = len(rp_pos), len(cp_pos)

    # color diversity (masked subject crop, quantized)
    q = crop.resize((48, 48)).quantize(colors=8,
                                       method=Image.MEDIANCUT).convert("RGB")
    total_px = 48 * 48
    cols = {}
    for c in q.getdata():
        cols[c] = cols.get(c, 0) + 1
    ranked = sorted(cols.items(), key=lambda kv: -kv[1])
    major = [(c, n) for c, n in ranked if n > total_px * 0.12] or ranked[:1]
    n_colors = len(major)
    hues = set()
    for c, _ in major:
        hh, ll, ss = colorsys.rgb_to_hls(*[v / 255.0 for v in c])
        if ss > 0.20:
            hues.add(int(hh * 12) % 12)
    hue_spread = len(hues)

    # Stripes: strong REGULAR periodic peaks on ONE axis only
    if row_peaks >= 6 and row_reg and not col_reg:
        return "Striped", 0.80
    if col_peaks >= 6 and col_reg and not row_reg:
        return "Striped", 0.80
    # Checked: REGULAR periodic peaks on BOTH axes, limited hue families
    if row_peaks >= 4 and col_peaks >= 4 and row_reg and col_reg \
            and edge_density > 0.05 and hue_spread <= 2:
        return "Checked", 0.72
    # Camo: >=3 distinct hue families among major colors, OR >=4 major
    # colors over a busy texture — but NO periodic edge structure
    if row_peaks < 4 and col_peaks < 4 and \
            ((hue_spread >= 3 and n_colors >= 3 and edge_density > 0.06) or
             (n_colors >= 4 and edge_density > 0.06)):
        return "Camo", 0.74
    # Graphic: busy edges, few hues, no periodicity
    if edge_density > 0.10 and hue_spread <= 2 and n_colors >= 2 \
            and row_peaks < 4 and col_peaks < 4:
        return "Graphic", 0.64
    if edge_density < 0.045 and n_colors <= 2:
        return "Plain", 0.90
    return "Plain", 0.58

# ---------------------------------------------------------------------------
# SEASON HEURISTICS
# ---------------------------------------------------------------------------

def classify_season(category: str, type_label: str, feats: dict, rgb) -> tuple:
    """category + type + brightness -> one of AppConstants.seasons."""
    hh, ll, ss = colorsys.rgb_to_hls(*[v / 255.0 for v in rgb])
    dark_heavy = ll < 0.35
    light_air = ll > 0.70
    t = type_label.lower()

    if category == "Outerwear":
        if "puffer" in t or "trench" in t or "coat" in t:
            return ("Winter", 0.85)
        return ("Winter", 0.65) if dark_heavy else ("All Season", 0.62)
    if category in ("Watches", "Jewelry"):
        return ("All Season", 0.95)
    if category == "Shoes":
        if "sandal" in t:
            return ("Summer", 0.80)
        return ("All Season", 0.80)
    if category in ("Bags", "Accessories"):
        if "beanie" in t or "scarf" in t:
            return ("Winter", 0.75)
        return ("Summer", 0.55) if light_air else ("All Season", 0.85)
    if category == "Bottoms":
        if "short" in t:
            return ("Summer", 0.85)
        return ("All Season", 0.70)
    # Tops
    if "t-shirt" in t or "tee" in t or "polo" in t or "blouse" in t:
        return ("All Season", 0.78)
    if "tank" in t or "crop" in t:
        return ("Summer", 0.80)
    if "hoodie" in t or "sweater" in t:
        return ("Winter", 0.72)
    if dark_heavy:
        return ("Winter", 0.60)
    if light_air:
        return ("Summer", 0.62)
    return ("All Season", 0.60)

# ---------------------------------------------------------------------------
# TYPE LABEL
# ---------------------------------------------------------------------------

def build_type_label(category: str, feats: dict, hints: dict) -> str:
    """
    Compose a free-text `type` from AppConstants.fits + the category lexicon.
    User text hints win outright if they name a known garment noun.
    """
    if hints.get("type"):
        return hints["type"]
    nouns = TYPE_LEXICON[category]
    hint_txt = (hints.get("raw") or "").lower()
    for n in nouns:
        if n.lower() in hint_txt or n.lower().rstrip("s") in hint_txt:
            return n
    if category == "Shoes" and hints.get("shoe") in ALLOWED_SHOE_TYPES:
        return hints["shoe"]

    a, fill = feats["aspect"], feats["fill_ratio"]
    if category == "Tops":
        noun = "T-Shirt" if a < 1.3 else "Hoodie"
    elif category == "Bottoms":
        noun = "Shorts" if a < 0.9 else "Jeans"
    elif category == "Outerwear":
        noun = "Jacket" if a < 1.4 else "Trench Coat"
    elif category == "Bags":
        noun = "Backpack" if a > 1.1 else "Tote Bag"
    elif category == "Shoes":
        noun = "Sneakers"
    elif category == "Accessories":
        noun = "Scarf" if a > 2.0 else "Cap"
    elif category == "Watches":
        noun = "Watch"
    else:  # Jewelry
        noun = "Necklace" if a > 2.0 else "Ring"

    fit = "Regular"
    if fill > 0.45 and category in ("Tops", "Outerwear"):
        fit = "Oversized"
    elif feats["waist_pinch"] < 0.85 and category == "Bottoms":
        fit = "Slim"
    return f"{fit} {noun}" if fit != "Regular" else noun

# ---------------------------------------------------------------------------
# VALIDATION GATE — the classifier may not leave the app's vocabulary
# ---------------------------------------------------------------------------

def validate(result: dict) -> dict:
    checks = [
        (result["category"] in ALLOWED_CATEGORIES,
         f"category '{result['category']}' not in ItemCategory.all"),
        (result["pattern"] in ALLOWED_PATTERNS,
         f"pattern '{result['pattern']}' not in AppConstants.patterns"),
        (result["season"] in ALLOWED_SEASONS,
         f"season '{result['season']}' not in AppConstants.seasons"),
        (result["colorName"] in ALLOWED_COLOR_NAMES,
         f"colorName '{result['colorName']}' not in AppConstants.colorPreferences"),
        (isinstance(result["color"], str) and result["color"].startswith("#")
         and len(result["color"]) == 7,
         f"color '{result['color']}' is not a #RRGGBB hex"),
        (isinstance(result["type"], str) and result["type"].strip() != "",
         "type label is empty"),
    ]
    failed = [msg for ok, msg in checks if not ok]
    if failed:
        raise ValueError("Classifier emitted out-of-vocabulary value(s): "
                         + "; ".join(failed))
    return result

# ---------------------------------------------------------------------------
# MAIN ENTRY POINT
# ---------------------------------------------------------------------------

class WardrobeClassifier:
    """
    StyleDrop wardrobe item classifier.

    >>> clf = WardrobeClassifier()
    >>> result = clf.classify("photo.jpg", hint="black denim jacket")
    >>> result["category"] in ItemCategory.all  # True, always
    """

    def classify(self, image_source: str, hint: str = "",
                 type_hint: str = "", shoe_hint: str = "") -> dict:
        """
        :param image_source: http(s) URL or local file path of the garment photo
        :param hint: free-text hint, e.g. "denim jacket" — keywords override
                     the visual category when unambiguous
        :param type_hint: explicit `type` label override (user-typed)
        :param shoe_hint: one of AppConstants.shoePreferences (Shoes only)
        """
        img = load_image(image_source)
        mask, bbox = build_mask(img)
        feats = silhouette_features(mask, bbox)

        hexc, rgb = dominant_color(img, mask, bbox)
        color_name = nearest_color_name(rgb)
        pattern, pat_conf = classify_pattern(img, mask, bbox)

        scores = score_categories(feats, rgb, img, mask, bbox)
        ranked = sorted(scores.items(), key=lambda kv: -kv[1])
        category, cat_conf = ranked[0]

        # text-hint override (highest trust signal)
        hints = {"raw": hint or "", "type": type_hint or "",
                 "shoe": shoe_hint or ""}
        hint_lower = (hint or "").lower()
        hint_cat = None
        for cat, kws in HINT_KEYWORDS.items():
            if any(k in hint_lower for k in kws):
                hint_cat = cat
                break
        if hint_cat:
            category, cat_conf = hint_cat, 0.95

        type_label = build_type_label(category, feats, hints)
        season, sea_conf = classify_season(category, type_label, feats, rgb)

        return validate({
            "category": category,
            "type": type_label,
            "color": hexc,
            "colorName": color_name,
            "pattern": pattern,
            "season": season,
            "confidence": {
                "category": round(float(cat_conf), 2),
                "pattern": pat_conf,
                "season": sea_conf,
            },
            "alternatives": [{"category": c, "score": round(float(s), 2)}
                             for c, s in ranked[1:5] if c != category][:3],
            "silhouette": {
                "aspect": round(feats["aspect"], 2),
                "fill_ratio": round(feats["fill_ratio"], 2),
                "waist_pinch": round(feats["waist_pinch"], 2),
                "taper": round(feats["taper"], 2),
                "legs_runs": feats["legs_runs"],
            },
            "source": image_source,
        })


# ---------------------------------------------------------------------------
# DEMO / TEST HARNESS — synthetic garment renders, deterministic
# ---------------------------------------------------------------------------

def _demo_images(outdir="/tmp/sd_demo"):
    """Render 6 deterministic synthetic garment photos with Pillow."""
    os.makedirs(outdir, exist_ok=True)
    paths = {}

    def canvas(bg=(240, 238, 232)):
        im = Image.new("RGB", (400, 400), bg)
        return im, im.load()

    # 1) Black oversized t-shirt: wide flat torso + sleeves at top
    im, px = canvas()
    for y in range(110, 330):                       # torso
        for x in range(120, 280):
            px[x, y] = (20, 20, 22)
    for y in range(110, 190):                       # sleeves
        for x in range(60, 120):
            px[x, y] = (20, 20, 22)
        for x in range(280, 340):
            px[x, y] = (20, 20, 22)
    paths["black_tshirt"] = os.path.join(outdir, "black_tshirt.png")
    im.save(paths["black_tshirt"])

    # 2) Blue jeans: tall, two legs visible at the bottom
    im, px = canvas()
    for y in range(50, 360):
        half = 100 - int(20 * (y - 50) / 310)       # waist 200 -> hem 160 wide
        for x in range(200 - half, 200 + half):
            px[x, y] = (38, 58, 108)
    for y in range(210, 360):                       # split into two legs
        for x in range(178, 222):
            px[x, y] = (240, 238, 232)
    paths["blue_jeans"] = os.path.join(outdir, "blue_jeans.png")
    im.save(paths["blue_jeans"])

    # 3) Red-and-white striped tee (horizontal stripes)
    im, px = canvas()
    for y in range(110, 330):
        for x in range(110, 290):
            px[x, y] = (220, 40, 40) if (y // 14) % 2 == 0 else (245, 243, 238)
    paths["striped_tee"] = os.path.join(outdir, "striped_tee.png")
    im.save(paths["striped_tee"])

    # 4) Camo jacket: multi-hue irregular blobs
    import random
    rnd = random.Random(7)
    im, px = canvas()
    palette = [(60, 74, 38), (108, 116, 62), (38, 42, 30), (140, 132, 92)]
    for y in range(90, 340):
        for x in range(70, 330):
            px[x, y] = rnd.choice(palette)
    paths["camo_jacket"] = os.path.join(outdir, "camo_jacket.png")
    im.save(paths["camo_jacket"])

    # 5) White sneakers: wide, low object
    im, px = canvas()
    for y in range(260, 340):
        for x in range(50, 350):
            px[x, y] = (250, 250, 250)
    for y in range(235, 260):
        for x in range(90, 310):
            px[x, y] = (238, 238, 238)
    paths["white_sneakers"] = os.path.join(outdir, "white_sneakers.png")
    im.save(paths["white_sneakers"])

    # 6) Checked (plaid) shirt: red/navy grid
    im, px = canvas()
    for y in range(110, 330):
        for x in range(110, 290):
            base = (30, 48, 90)
            if (x // 20) % 2 == 0:
                base = (170, 40, 40)
            if (y // 20) % 2 == 0:
                base = tuple(min(255, v + 60) for v in base)
            px[x, y] = base
    paths["checked_shirt"] = os.path.join(outdir, "checked_shirt.png")
    im.save(paths["checked_shirt"])

    return paths


def main():
    ap = argparse.ArgumentParser(description="StyleDrop wardrobe classifier")
    ap.add_argument("image", nargs="?", help="image URL or local path")
    ap.add_argument("--hint", default="", help="text hint, e.g. 'denim jacket'")
    ap.add_argument("--type", dest="type_hint", default="")
    ap.add_argument("--shoe", dest="shoe_hint", default="",
                    choices=[""] + ALLOWED_SHOE_TYPES)
    args = ap.parse_args()

    clf = WardrobeClassifier()
    if args.image:
        print(json.dumps(clf.classify(args.image, hint=args.hint,
                                      type_hint=args.type_hint,
                                      shoe_hint=args.shoe_hint), indent=2))
        return

    # built-in demo suite
    print("StyleDrop Wardrobe Classifier — demo suite "
          "(synthetic garments, vocabulary = repo AppConstants)\n")
    demos = [
        ("black_tshirt", {}),
        ("blue_jeans", {}),
        ("striped_tee", {}),
        ("camo_jacket", {"hint": "camo jacket"}),
        ("white_sneakers", {}),
        ("checked_shirt", {}),
    ]
    paths = _demo_images()
    for name, kwargs in demos:
        r = clf.classify(paths[name], **kwargs)
        print(f"== {name} ==")
        print(json.dumps(r, indent=2))
        print()


if __name__ == "__main__":
    main()
