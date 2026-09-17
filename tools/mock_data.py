"""
mock_data.py — Deterministic mock wardrobe generator for the StyleDrop analytics demo.

Every field mirrors the REAL Room entity app/src/main/java/com/example/models/WardrobeItem.kt
(id, imageUrl, category, type, color, secondaryColor, style, fit, pattern, season, brand,
 timesWorn, isFavoriteItem, lastWorn, createdAt).

Two fields the current schema does NOT have are declared explicitly as REQUIRED
schema extensions (never silently assumed):
    price        : Float        -> needed by cost-per-wear
    purchaseDate : ISO date str -> needed by cost-per-wear & aging
And one new table:
    WearEvent(itemId, date, occasion) -> needed by the wear calendar + Style DNA (occasion axis)

The generator is fully seeded so every number in ANALYTICS.md / the demo output is reproducible.
"""

import random
from datetime import date, timedelta

# --- canonical vocab (verbatim from AppConstants.kt / WardrobeItem.kt) -------------------
CATEGORIES = ["Tops", "Bottoms", "Shoes", "Outerwear",
              "Accessories", "Bags", "Watches", "Jewelry"]
CATEGORY_EMOJI = {"Tops": "👕", "Bottoms": "👖", "Shoes": "👟", "Outerwear": "🧥",
                  "Accessories": "🧢", "Bags": "👜", "Watches": "⌚", "Jewelry": "💍"}
STYLES = ["Streetwear", "Casual", "Minimalist", "Old Money", "Y2K", "Korean",
          "Smart Casual", "Formal", "Sporty", "Vintage", "Techwear", "Grunge"]
SEASONS = ["All Season", "Summer", "Winter", "Spring/Fall"]
FITS = ["Oversized", "Slim", "Regular", "Relaxed"]
PATTERNS = ["Plain", "Striped", "Graphic", "Checked", "Camo"]
OCCASIONS = ["School", "College", "Work", "Casual", "Date",
             "Party", "Wedding", "Gym", "Travel", "Beach", "Formal"]
BRANDS = ["Uniqlo", "Zara", "COS", "Nike", "Levi's", "H&M", "Muji", "ASOS", ""]

# colour name -> hex (only used for the donut swatches; the DB stores a name, not hex)
COLOR_HEX = {
    "Black": "#1B1A17", "White": "#FFFFFF", "Gray": "#8A8A8A", "Beige": "#D9C7A7",
    "Navy": "#1B2A4A", "Brown": "#6B4A2F", "Olive": "#77754E", "Blue": "#46688C",
    "Green": "#4A6B4A", "Red": "#B5533C", "Cream": "#F3EEE2",
}

TODAY = date(2026, 9, 17)  # pinned so the demo is reproducible


def _rand_date(rng, start_days_ago, end_days_ago):
    return TODAY - timedelta(days=rng.randint(end_days_ago, start_days_ago))


def make_wardrobe(seed=42, n=48):
    """Return (items, events). Deterministic for a given seed."""
    rng = random.Random(seed)

    # category weights: a realistic closet skews to tops/bottoms/shoes
    cat_w = {"Tops": 10, "Bottoms": 9, "Shoes": 7, "Outerwear": 6,
             "Accessories": 6, "Bags": 4, "Watches": 3, "Jewelry": 3}
    pool = []
    for c, w in cat_w.items():
        pool += [c] * w
    pool = pool[:n] if len(pool) >= n else pool + rng.choices(list(cat_w), k=n - len(pool))

    colors = list(COLOR_HEX.keys())
    items = []
    for i in range(n):
        cat = pool[i]
        style = rng.choice(STYLES)
        season = rng.choices(SEASONS, weights=[5, 3, 3, 2])[0]
        created = _rand_date(rng, 900, 5)
        price = round(rng.uniform(12, 240), 2)
        # wear behaviour: ~20% never worn, rest a long tail
        roll = rng.random()
        if roll < 0.20:
            worn = 0
            last = None
        else:
            worn = int(rng.paretovariate(1.4)) + 1
            worn = min(worn, 60)
            last = _rand_date(rng, 260, 0)
        items.append({
            "id": f"itm-{i+1:03d}",
            "imageUrl": f"content://media/picker/{i+1}",
            "category": cat,
            "type": f"{style} {cat[:-1] if cat.endswith('s') else cat}",
            "color": rng.choices(colors, weights=[16, 12, 11, 10, 10, 9, 8, 8, 5, 4, 7])[0],
            "secondaryColor": "" if rng.random() < 0.6 else rng.choice(colors),
            "style": style,
            "fit": rng.choice(FITS),
            "pattern": rng.choices(PATTERNS, weights=[10, 3, 3, 2, 1])[0],
            "season": season,
            "brand": rng.choice(BRANDS),
            "timesWorn": worn,
            "isFavoriteItem": rng.random() < 0.22,
            "lastWorn": int(last.strftime("%s")) * 1000 if last else None,
            "createdAt": int(created.strftime("%s")) * 1000,
            "price": price,               # [SCHEMA EXTENSION]
            "purchaseDate": created.isoformat(),  # [SCHEMA EXTENSION]
        })

    # --- wear events: last 365 days, occasions weighted by style formality ---------------
    events = []
    worn_items = [it for it in items if it["timesWorn"] > 0]
    for it in worn_items:
        k = min(it["timesWorn"], 18)  # cap per-item log length for a sane demo
        for _ in range(k):
            d = _rand_date(rng, 365, 0)
            occ = rng.choices(OCCASIONS, weights=[6, 6, 12, 14, 4, 4, 2, 5, 3, 2, 5])[0]
            events.append({"itemId": it["id"], "date": d.isoformat(), "occasion": occ})
    events.sort(key=lambda e: e["date"])
    return items, events


if __name__ == "__main__":
    its, evs = make_wardrobe()
    print(f"{len(its)} items, {len(evs)} wear events")
    print(its[0])
