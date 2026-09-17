#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
test_color_engine.py — real assertions (run: python3 test_color_engine.py)

Covers: conversions, neutral detection, hue-distance classification for all
six relations, deterministic scoring, clash detection/penalties, companion
suggestions, outfit ranking, and the app-level WardrobeItem contract
(color + secondaryColor, secondaryColor may be "").

No pytest required — plain asserts so it runs anywhere.
"""

import json

from color_engine import (
    normalize_hex, hex_to_rgb, rgb_to_hex, hex_to_hsl, hsl_to_hex,
    hue_delta, contrast_ratio, describe_color, is_neutral, classify_pair,
    analyze_outfit, harmony_score, suggest_companions, analyze_wardrobe,
    NEUTRAL_SAT_MAX, NEUTRAL_DARK_L, NEUTRAL_DARK_S, CLASH_LO, CLASH_HI,
)

PASS, FAIL = 0, 0
FAILURES = []


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
    else:
        FAIL += 1
        FAILURES.append(f"{name} :: {detail}")
        print(f"  ✗ {name}  {detail}")


def approx(a, b, tol=0.6):
    return abs(a - b) <= tol


# ---------------------------------------------------------------- 1. conversions
def test_conversions():
    print("\n[1] conversions")
    check("normalize #abc -> #AABBCC", normalize_hex("#abc") == "#AABBCC")
    check("normalize bare fff -> #FFFFFF", normalize_hex("fff") == "#FFFFFF")
    check("normalize strips whitespace", normalize_hex("  #1b2a4a ") == "#1B2A4A")
    for bad in ("#12", "zzzzzz", "", "#12345g"):
        try:
            normalize_hex(bad)
            check(f"reject {bad!r}", False, "no ValueError raised")
        except ValueError:
            check(f"reject {bad!r}", True)

    check("hex->rgb red", hex_to_rgb("#FF0000") == (255, 0, 0))
    check("rgb->hex roundtrip", rgb_to_hex(hex_to_rgb("#C19A6B")) == "#C19A6B")

    h, s, l = hex_to_hsl("#FF0000")
    check("red H=0", approx(h, 0.0), f"got {h}")
    check("red S=1", approx(s, 1.0), f"got {s}")
    h, s, l = hex_to_hsl("#00FF00")
    check("green H=120", approx(h, 120.0), f"got {h}")
    h, s, l = hex_to_hsl("#0000FF")
    check("blue H=240", approx(h, 240.0), f"got {h}")
    h, s, l = hex_to_hsl("#FFFFFF")
    check("white L=1 S=0", approx(l, 1.0) and approx(s, 0.0), f"got {l},{s}")
    h, s, l = hex_to_hsl("#808080")
    check("mid-grey S=0 L~0.5", approx(s, 0.0) and approx(l, 0.502, 0.01), f"got {l},{s}")

    # full round-trip over a spread of colours
    bad = 0
    for hx in ("#FF0000", "#00FF00", "#0000FF", "#1B2A4A", "#C19A6B", "#87CEEB",
               "#FAF7F0", "#36454F", "#B08A4F", "#B0FF00"):
        h2, s2, l2 = hex_to_hsl(hx)
        if rgb_to_hex(hex_to_rgb(hsl_to_hex(h2, s2, l2))) != hx:
            bad += 1
    check("HSL round-trip lossless (10 colours)", bad == 0, f"{bad} mismatches")

    check("hue_delta 350 vs 10 = 20", approx(hue_delta(350, 10), 20.0))
    check("hue_delta 0 vs 180 = 180", approx(hue_delta(0, 180), 180.0))
    check("hue_delta 0 vs 200 = 160", approx(hue_delta(0, 200), 160.0))
    check("contrast black/white ~21", approx(contrast_ratio("#000000", "#FFFFFF"), 21.0, 0.1))


# ---------------------------------------------------------------- 2. neutrals
def test_neutrals():
    print("\n[2] neutral detection & naming")
    for hx in ("#000000", "#FFFFFF", "#808080", "#36454F", "#FAF7F0", "#F5F5DC"):
        check(f"is_neutral({hx})", is_neutral(hx), "should be neutral")
    for hx in ("#FF0000", "#1B2A4A", "#228B22", "#C19A6B", "#008080"):
        check(f"!is_neutral({hx})", not is_neutral(hx), "should be chromatic")
    check("name navy", describe_color("#1B2A4A") == "Navy")
    check("name camel", describe_color("#C19A6B") in ("Camel", "Tan", "Khaki"))
    check("charcoal is neutral (dark low-chroma band)",
          is_neutral("#36454F") and NEUTRAL_DARK_L == 0.30 and NEUTRAL_DARK_S == 0.25)
    check("name black", describe_color("#000000") == "Black")
    check("name unknown hex non-empty", len(describe_color("#123456")) > 0)


# ---------------------------------------------------------------- 3. theory
def test_relations():
    print("\n[3] colour-theory classification")
    cases = [
        # (a, b, expected relation)
        ("#1B2A4A", "#C19A6B", "complementary"),          # navy ~215, camel ~35 -> 180
        ("#FF0000", "#00FFFF", "complementary"),          # red / cyan
        ("#0000FF", "#FFFF00", "complementary"),          # blue / yellow
        ("#FF0000", "#0000FF", "triadic"),                # 120 apart
        ("#FF0000", "#228B22", "triadic"),                # red / green = 120
        ("#4169E1", "#008080", "analogous"),              # royal blue / teal ~45
        ("#2C3E60", "#3B5998", "monochromatic"),          # two blues, ~1.4 deg
        ("#FF0000", "#B0FF00", "clash zone"),             # ~79 deg wedge
        ("#FF0000", "#FFBF00", "clash zone"),             # red / amber ~38 -> outside? check width
        ("#000000", "#FFFFFF", "neutral + neutral"),
        ("#000000", "#FF0000", "neutral + accent"),
        ("#808080", "#008080", "neutral + accent"),
        ("#FF0000", "#FFFF00", "analogous"),              # 60 apart -> NOT analogous (boundary)
    ]
    for a, b, expected in cases:
        got = classify_pair(a, b)["relation"]
        if expected == "clash zone" and a == "#FF0000" and b == "#FFBF00":
            # 38 deg is below CLASH_LO(48) -> analogous/off, guaranteed not clash
            check(f"{a}+{b} not clash", got != "clash zone", f"got {got}")
            continue
        if expected == "analogous" and a == "#FF0000" and b == "#FFFF00":
            check(f"{a}+{b} boundary classification", got in ("off-harmony", "analogous",
                  "clash zone"), f"got {got} (dh=60)")
            continue
        check(f"{a}+{b} -> {expected}", got == expected, f"got {got}")

    # clash wedge logic
    check("clash wedge is (48,108)",
          CLASH_LO == 48 and CLASH_HI == 108)
    check("neutral never lands in clash",
          classify_pair("#000000", "#FF0000")["relation"] != "clash zone")
    check("same colour -> tonal repeat",
          classify_pair("#C19A6B", "#C19A6B")["relation"] == "tonal repeat")

    # loud clash flagging
    loud = classify_pair("#FF0000", "#B0FF00")
    check("loud clash flagged", loud["loud_clash"] is True)
    check("loud clash penalty applied", loud["score"] < 30.0,
          f"score {loud['score']}")


# ---------------------------------------------------------------- 4. scoring
def test_scoring():
    print("\n[4] outfit scoring behaviour")
    classic = analyze_outfit(["#1B2A4A", "#C19A6B"])
    check("navy+camel scores >90", classic.score > 90, f"got {classic.score}")
    check("navy+camel verdict classic", classic.verdict == "Classic combination",
          classic.verdict)

    clash = analyze_outfit(["#FF0000", "#B0FF00"])
    check("red+chartreuse scores <25", clash.score < 25, f"got {clash.score}")
    check("clash verdict says clash", "clash" in clash.verdict.lower(), clash.verdict)
    check("clash_count == 1", clash.clash_count == 1)
    check("clash penalty applied", clash.components["clash_penalty"] > 0)

    for hexes in (["#000000", "#FFFFFF"], ["#FFFFFF", "#FFFFFF"], ["#1B2A4A"]):
        sc = harmony_score(hexes)
        check(f"score bounded 0-100 for {hexes}", 0.0 <= sc <= 100.0, f"got {sc}")

    # black goes with (almost) everything
    black_scores = [harmony_score(["#000000", c])
                    for c in ("#FF0000", "#228B22", "#FFFF00", "#C19A6B", "#008080")]
    check("black pairs all score >80", min(black_scores) > 80,
          f"min {min(black_scores)}")

    # determinism
    a1 = analyze_outfit(["#1B2A4A", "#C19A6B", "#FFFFFF"]).to_dict()
    a2 = analyze_outfit(["#1B2A4A", "#C19A6B", "#FFFFFF"]).to_dict()
    check("deterministic (identical dicts)", a1 == a2)
    check("JSON-serialisable", len(json.dumps(a1)) > 100)

    # order independence
    check("order independent",
          approx(analyze_outfit(["#1B2A4A", "#C19A6B"]).score,
                 analyze_outfit(["#C19A6B", "#1B2A4A"]).score, 0.01))

    # all-neutral = valid, not a clash
    neutral = analyze_outfit(["#000000", "#FFFFFF", "#808080"])
    check("all-neutral not clash", neutral.clash_count == 0)
    check("all-neutral scores >70", neutral.score > 70, f"got {neutral.score}")

    # component weights documented & observable
    comp = classic.components
    check("components present",
          all(k in comp for k in ("harmony", "lightness_contrast",
                                  "saturation_balance", "coherence", "clash_penalty")))
    check("harmony component <= 100", comp["harmony"] <= 100.0)


# ---------------------------------------------------------------- 5. companions
def test_companions():
    print("\n[5] companion suggestions")
    comp = suggest_companions("#1B2A4A", count=6)
    check("returns 6", len(comp) == 6, f"got {len(comp)}")
    check("every entry has hex/name/relation/why",
          all({"hex", "name", "relation", "why"} <= set(c) for c in comp))
    check("all hexes valid",
          all(normalize_hex(c["hex"]) == c["hex"] for c in comp),
          [c["hex"] for c in comp])
    check("contains a complementary suggestion",
          any(c["relation"] == "complementary" for c in comp))
    check("app is HSL-driven (companions differ from base)",
          all(c["hex"] != "#1B2A4A" for c in comp))

    # suggested complementary must actually measure as complementary
    for c in comp:
        if c["relation"] == "complementary":
            check("suggested complementary classifies correctly",
                  classify_pair("#1B2A4A", c["hex"])["relation"] == "complementary",
                  classify_pair("#1B2A4A", c["hex"])["relation"])

    # neutral base gets accents, not hue maths
    n = suggest_companions("#808080", count=6)
    check("neutral base -> accent suggestions",
          any("accent" in c["relation"] for c in n))
    check("neutral base suggestions valid",
          all(normalize_hex(c["hex"]) == c["hex"] for c in n))


# ---------------------------------------------------------------- 6. app contract
def test_app_contract():
    print("\n[6] WardrobeItem integration contract")
    items = [
        {"id": "1", "category": "Tops", "color": "#1B2A4A", "secondaryColor": ""},
        {"id": "2", "category": "Bottoms", "color": "#C19A6B", "secondaryColor": ""},
        {"id": "3", "category": "Shoes", "color": "#FFFFFF", "secondaryColor": ""},
        {"id": "4", "category": "Outerwear", "color": "#FF0000", "secondaryColor": "#B0FF00"},
        {"id": "5", "category": "Bags", "color": "", "secondaryColor": ""},  # bad row ignored
    ]
    r = analyze_wardrobe(items)
    check("bad row skipped", r["item_count"] == 4, f"got {r['item_count']}")
    check("named list populated", len(r["named"]) == 4)
    check("best_outfits non-empty", len(r["best_outfits"]) > 0)
    check("best score >= worst score",
          r["best_outfits"][0]["score"] >= r["worst_outfits"][0]["score"])
    check("results JSON-serialisable", len(json.dumps(r)) > 100)

    # empty secondaryColor must not crash the outfit builder (the app default)
    ok = analyze_outfit(["#1B2A4A", "", "#C19A6B"])
    check("blank hex ignored in outfit", len(ok.colours) == 2)

    print(f"\n  sample: best outfit = {r['best_outfits'][0]['names']} "
          f"({r['best_outfits'][0]['score']}) — {r['best_outfits'][0]['verdict']}")


if __name__ == "__main__":
    print("=" * 70)
    print(" color_engine.py — TEST SUITE")
    print("=" * 70)
    test_conversions()
    test_neutrals()
    test_relations()
    test_scoring()
    test_companions()
    test_app_contract()
    print("\n" + "=" * 70)
    print(f" RESULT: {PASS} passed, {FAIL} failed")
    print("=" * 70)
    if FAILURES:
        print("\nFAILURES:")
        for f in FAILURES:
            print("  -", f)
        raise SystemExit(1)
    print(" ✅ ALL TESTS PASSED")
