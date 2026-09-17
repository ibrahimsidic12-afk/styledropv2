# STYLEDROP — BRAND IDENTITY GUIDE
**Version 1.0 · September 2026 · "The Digital Atelier" system**

> StyleDrop — *Your AI Wardrobe Stylist.* This guide defines a complete premium identity
> for the styledrop wardrobe/styling app. It is engineered to be executed by a human
> designer **or** pasted verbatim into an image-generation tool. Every hex below is a
> production token; several are already live in the codebase (see §8 for the exact
> mapping to `app/src/main/java/com/example/ui/theme/Color.kt`).

---

## 0. BRAND CORE

| | |
|---|---|
| **Name** | styledrop (always lowercase in brand contexts; "StyleDrop" is the technical app name) |
| **Positioning** | Your personal atelier — a tailor's studio that lives in your pocket |
| **One-line story** | *A wardrobe that drops looks on you, like a tailor pins a fitting.* |
| **Concept** | **The Digital Atelier.** The name fuses two wardrobe worlds: the *drop* (hype-culture seasonal collection releases) and the *tailor's drop* (a garment falling correctly from the shoulder). The whole identity is built from tailor's materials: thread, basting stitches, brass hardware, garment tags, care labels. |
| **Feel** | Quiet luxury meets a working tailor's bench: warm, precise, editorial, never loud. |
| **Anti-definition** | Not a neon tech app. Not a fast-fashion sale banner. No gradients-as-personality. No stock "AI blue-purple". |

---

## 1. LOGO

### 1.1 Concept — "The Thread & The Drop"
A clothes hanger drawn as **one continuous thread of gold**, with a hook shaped like a
loose **stitch-loop**. The hanger's shoulder line does not close at the bottom vertex —
the thread escapes the rail, falls, and terminates in a single **drop** in basting-stitch
red. Hanger = wardrobe. Drop = the name, and the moment of the right look arriving.
One line, no breaks — *cut from one cloth.*

### 1.2 ASCII representation

```
              ___
             /   \
             \___/             hook — one loose stitch-loop
               |
    ___________|___________
    \                     /
     \                   /
      \                 /
       \               /
        \             /         the shoulder — cut from one
         \___________/          continuous line of thread
               |
               |                the thread escapes the rail…
               |
              ○
              ◉                 …and ends in a single drop
```

### 1.3 Wordmark
- `styledrop` in **lowercase Playfair Display SemiBold**, near-black ink `#1B1A17`,
  letter-spacing +2%.
- The final "p" sits over a short **dashed underline in Basting Red** `#C8442C`
  (2 px dashes, 6 px gaps) — a tailor's basting stitch: *temporary marks, committed fits.*
- Lockup: mark centered above wordmark, 0.5 drop-unit gap. Horizontal lockup: mark left,
  wordmark baseline-aligned, drop glyph used as the full stop after the wordmark.

### 1.4 Visual description for image-generation tools (verbatim spec)
Flat vector mark on warm unbleached-cotton ivory `#FAF7F0`. A clothes hanger drawn in a
single unbroken thin line of aged brass gold `#B08A4F` (stroke weight uniform, rounded
caps). The hanger's hook is a small open loop resembling a hand-sewn stitch. The triangle
body is wide and shallow, like tailored shoulders. At the bottom vertex the line does not
close: it continues vertically downward and ends in one small filled teardrop in basting
red `#C8442C`. Beneath the mark, the wordmark `styledrop` in lowercase elegant high-
contrast serif (Playfair Display style) in near-black ink `#1B1A17`, with a short dashed
red line under it like tailor's basting stitches. No gradients, no shadows, no 3D, no
background texture, generous negative space, centered composition, luxury fashion-house
aesthetic.

### 1.5 Rendered logo (generated with the spec above)
![styledrop logo — gold thread hanger with red drop](https://www.genspark.ai/api/files/s/h0TbNiSl?cache_control=3600)

### 1.6 App icon
Rounded squircle, **Espresso Felt** `#16150F` ground, gold hanger-thread mark centered,
red drop. The dark ground is deliberate: the icon must read like a brass rail against a
dark fitting-room wall on a phone's home screen.

### 1.7 Clearspace & minimum sizes
- Clearspace is measured in **drop units** (the width of the drop glyph = 1 d).
  Keep ≥ 1 d on all sides of the mark; ≥ 0.5 d between mark and wordmark.
- Minimum: mark alone 24 px; full lockup 96 px wide. Below that, use the mark alone.

### 1.8 Misuse
Never: rotate the hanger; recolor the drop to anything but Basting Red or Aged Brass;
outline the drop; add gradients; place the mark on busy fabric photography without a
scrim; stretch the lockup; replace the serif wordmark with a geometric sans.

---

## 2. COLOR PALETTE

The palette is built from **fabric, thread and hardware** — every color is named after
the physical material it imitates. Foundation tokens are already in the app's
`Color.kt`; accent colors are new brand additions.

### 2.1 Primary (foundation — the atelier)
| Token | Hex | Material story | Usage |
|---|---|---|---|
| **Muslin Ivorie** | `#FAF7F0` | unbleached cotton muslin | App background (light theme) |
| **Card White** | `#FFFFFF` | pressed cotton | Card surfaces on light theme |
| **Pressing Ink** | `#1B1A17` | black garment dye | Headlines, body text, dark rails |
| **Felted Black** | `#16150F` | felted wool, dark fitting room | Dark-theme background, app icon ground |
| **Espresso Velvet** | `#201E17` | dark velvet nap | Dark-theme surfaces |

### 2.2 Accent
| Token | Hex | Material story | Usage |
|---|---|---|---|
| **Aged Brass** | `#B08A4F` (light) / `#CBA870` (dark) | brass hanger hardware | Icons, logo thread, premium accents — decorative/large type only (2.98:1 on ivory — never body text) |
| **Basting Red** | `#C8442C` | tailor's red basting thread | **The signature accent.** CTAs, badges, the logo drop, active states. 4.54:1 on ivory — passes AA for normal text; 4.86:1 with white text on it |
| **Riviera Blue** | `#46688C` | washed indigo denim | Secondary links, selected chips. 5.42:1 on ivory — full AA |
| **Field Moss** | `#77754E` | olive twill | Seasonal/casual tags. 4.42:1 on ivory — large text only |
| **Mulled Wine** | `#7A3E4A` | burgundy wool | Editorial highlights. 7.50:1 on ivory |
| **Blush Muslin** | `#E8C4B8` | dyed silk lining | Decorative fills only (1.51:1 — never text) |

### 2.3 Neutral ramp (all existing codebase tokens)
| Token | Hex | Role |
|---|---|---|
| Muslin Ivorie | `#FAF7F0` | background |
| Raw Linen | `#F1ECE1` | alt panels, chips |
| Canvas | `#E7E0D2` | hairlines, dividers (basting-stitch lines) |
| Driftwood | `#CFC8B8` | dark-theme soft ink |
| Twill Grey | `#837B6C` | captions, muted text (3.91:1 — large only) |
| Charcoal Wool | `#4A463E` | secondary text (8.77:1 on ivory) |
| Chalk | `#F3EEE2` | dark-theme ink |
| Espresso Velvet | `#201E17` | dark surfaces |
| Felted Black | `#16150F` | dark background |

### 2.4 Contrast ledger (computed, WCAG 2.1 relative luminance)
| Pair | Ratio | Verdict |
|---|---|---|
| Pressing Ink on Muslin Ivorie | 16.26 | AAA |
| White on Pressing Ink | 17.40 | AAA |
| Chalk on Felted Black | 15.80 | AAA |
| Charcoal Wool on Muslin Ivorie | 8.77 | AAA |
| Driftwood on Felted Black | 10.98 | AAA |
| Aged Brass (dark) on Felted Black | 8.16 | AAA |
| Aged Brass (dark) on Espresso Velvet | 7.44 | AAA |
| Mulled Wine on Muslin Ivorie | 7.50 | AAA |
| Ink on Raw Linen chip | 13.25 | AAA |
| Basting Red on Muslin Ivorie | 4.54 | AA normal |
| White on Basting Red | 4.86 | AA normal |
| Riviera Blue on Muslin Ivorie | 5.42 | AA normal |
| Chalk on Basting Red | 4.20 | AA large only |
| Basting Red on Felted Black | 3.76 | AA large only |
| Field Moss on Muslin Ivorie | 4.42 | AA large only |
| Twill Grey on Muslin Ivorie | 3.91 | AA large only |
| Aged Brass on Muslin Ivorie | 2.98 | decorative only |

**Rule:** body text is always Pressing Ink / Charcoal Wool on ivory, or Chalk / Driftwood
on dark. Brass and Blush are jewelry, never text.

### 2.5 Seasonal drop palettes
Each seasonal "drop" gets a capsule of three accents laid over the permanent ivory/ink
foundation — like a mini collection appended to a core line:
- **CORE (permanent):** Muslin Ivorie · Pressing Ink · Basting Red
- **SS (Riviera):** Riviera Blue `#46688C` · Blush Muslin `#E8C4B8` · Field Moss `#77754E`
- **AW (Fireside):** Mulled Wine `#7A3E4A` · Aged Brass `#B08A4F` · Espresso Velvet `#201E17`

---

## 3. TYPOGRAPHY STACK

Two voices, like an atelier: the **couturière** (serif, editorial) and the **fitter** (sans, precise). Both are free, open-licensed Google Fonts, already wired into the app via the Android downloadable-fonts provider in `app/src/main/java/com/example/ui/theme/Type.kt`.

### 3.1 Families & licensing
| Role | Family | Weights | License / source | Status in codebase |
|---|---|---|---|---|
| Headings / editorial display | **Playfair Display** | SemiBold 600 (Bold 700 for posters) | SIL OFL, free via Google Fonts | ✅ live in `Type.kt` (`PlayfairDisplayFont`) |
| Body / UI / buttons | **Inter** | Regular 400, Medium 500, SemiBold 600 | SIL OFL, free via Google Fonts | ✅ live in `Type.kt` (`InterFont`) |
| Editorial accent (pull-quotes, "fit notes") | **Cormorant Garamond** | Italic 500 | SIL OFL, free via Google Fonts | optional 3rd voice — add if needed |

No paid fonts anywhere in the brand. For offline reliability, bundle WOFF2/TTF in `res/font/` as fallback alongside the downloadable provider.

### 3.2 Type scale (mapped to the app's Material 3 roles)
| Style | Family / Weight | Size / line | Tracking | Use |
|---|---|---|---|---|
| displayLarge | Playfair SemiBold | 34sp / 40sp | −0.5 | Screen hero: "Your Atelier" |
| displayMedium | Playfair SemiBold | 26sp / 32sp | 0 | Section openers |
| headlineMedium | Playfair SemiBold | 22sp / 28sp | 0 | Card-group titles |
| titleLarge | Inter SemiBold | 18sp | +0.2 | Item names, dialogs |
| titleMedium | Inter SemiBold | 15sp | 0 | List rows |
| bodyLarge | Inter Regular | 15sp / 23sp | 0 | Primary copy |
| bodyMedium | Inter Regular | 13sp / 18sp | 0 | Secondary copy |
| bodySmall | Inter Regular | 11sp | 0 | Timestamps, metadata |
| labelLarge | Inter SemiBold | 13sp | +0.8 | Buttons, tabs — **sentence case, never ALL CAPS**, letter-spacing does the work |

### 3.3 Rules
- Playfair never below 22sp — below that it loses its hairline contrast and reads «fancy» instead of tailored.
- Buttons, tabs, labels: Inter only. Numerals on the Analytics screen: Inter with tabular figures (`tnum`) so wardrobes counts align in columns.
- Editorial pull-quote (AI stylist commentary) may use Cormorant Garamond Italic 20sp in Mulled Wine `#7A3E4A`.
- Max two families per screen; a third only for the editorial voice.

---

## 4. TONE OF VOICE

**Sartorial. Precise. Warm.** The app speaks like a tailor who knows your name — not an assistant, not a hype account.

### 4.1 Voice pillars
1. **Craft authority** — tailoring vocabulary used correctly: *pieces, drape, capsule, pins, fitting, line* — never as gimmick.
2. **Quiet confidence** — understate. The brand never exclaims; the clothes do the talking.
3. **Personal attention** — second person, always. The app is *your* atelier and it notices what you wear.

### 4.2 Do / Don't — example copy
| Moment | ❌ Don't (generic app voice) | ✅ Do (styledrop voice) |
|---|---|---|
| Empty wardrobe | "No items found. Add garments to get started." | "Your atelier is waiting. Hang your first piece." |
| AI outfit ready | "Outfit generated successfully! 🎉" | "Pinned for you — the Riviera look, cut for today's forecast." |
| Add-item CTA | "Upload photo" | "Hang it in" |
| Item worn often | "You wore this item 3 times" | "Three wears this season — a quiet favorite." |
| Weather tie-in | "Rain detected in your area" | "The sky turned. The trench agrees." |
| Error state | "Error 500: something went wrong" | "A loose thread. Give it a moment and try again." |
| Onboarding | "Let's set up your profile!" | "First, the measurements. Every good fitting starts here." |
| Deletion | "Are you sure you want to delete?" | "Off the rail? This piece leaves your atelier for good." |

### 4.3 Style rules
- Sentence case everywhere, including buttons. NEVER ALL CAPS.
- Never more than one exclamation point per screen — ideally zero.
- Fabric names spelled out and honored ("washed linen", not "material").
- Numbers as numerals ("12 pieces"), fit ranges with an en dash ("34–36").
- Humor allowed, memes banned. Puns at most one per drop.

### 4.4 Lexicon
| We say | We never say |
|---|---|
| piece, capsule, rail, atelier, fitting, drop | garment, inventory, closet dump, outfit generator |
| hang it in / off the rail | upload / delete |
| pinned for you | AI-powered result |
| the drop (seasonal release) | collection update |

---

## 5. VISUAL MOTIFS

A motif library drawn entirely from a tailor's bench — this is what makes the brand *uniquely wardrobe* and never generic.

1. **The Basting Stitch** — 2 dp dashed red `#C8442C` lines (6 dp dash / 4 dp gap, round caps). The signature texture: active-tab underlines, selection outlines, progress bars, the wordmark underline. Meaning: *work in progress, being fitted.*
2. **The Brass Rail** — a 1 dp hairline in Aged Brass `#B08A4F` under every horizontally scrolling row, ending in a tiny hook curl (the hanger hook mirrored). Turns any carousel into a wardrobe rail.
3. **The Care Label** — small ivory tag with one stitched edge (dashed border) and a laundry-symbol-style glyph. Used for chips: categories, fabrics, care instructions, item metadata.
4. **The Fabric Swatch** — rounded-square (12 dp radius) color/material chips with a subtle woven overlay at 4% opacity. Used for color metadata, category pickers, palette pickers.
5. **The Single-Line Silhouette** — empty states and photo-less cards use a one-line garment drawing (blazer, dress, trench) in Pressing Ink, 1.5 dp stroke, like a pattern-drafting sketch.
6. **Thread Connectors** — on the Look Board, a thin dashed thread links the pieces of one outfit, ending in a needle-dot at the latest piece.
7. **The Seasonal Drop Tag** — each seasonal capsule (SS/AW palettes, §2.5) arrives with its own hang-tag illustration on the home hero.
8. **Texture discipline** — fabric textures (linen weave, felt) only at 3–5% opacity, never behind text, never in the dark theme's surfaces below 8% elevation.

---

## 6. UI PATTERNS (sketch descriptions)

### 6.1 Wardrobe Home — «The Rail»
```
┌────────────────────────────────────┐
│  styledrop            ◯ ◯          │  ← wordmark top-left, avatar right
│  Good morning, Imao.               │  ← Playfair displayLarge
│  ────────────────╮h                │  ← brass rail hairline, hook end-cap
│  ⌐T1──┐ ⌐T2──┐ ⌐T3──┐ ⌐T4──┐      │  ← garment cards on the rail (h-scroll)
│  │img │ │img │ │img │ │img │       │
│  └─tag┘ └─tag┘ └─tag┘ └─tag┘       │  ← care-label chips
│  · · ● · · ·                       │  ← basting-dash active indicator
└────────────────────────────────────┘
```
Spec: top bar transparent on Muslin Ivorie; rails of `GarmentCard`s; horizontal scroll with snap; brass hairline runs the exact card-row width with a 6 dp hook terminal.

### 6.2 Garment Card
Rounded 12 dp, Card White ground, garment photo full-bleed, bottom-left care-label chip (category + fabric), top-right swatch dot for dominant color. Selected state: 2 dp Basting Red dashed outline offset 2 dp. Long-press lifts card 2 dp with a warm soft shadow (`0 6 16 rgba(27,26,23,0.12)`).

### 6.3 Look Board — «The Atelier Wall»
```
┌──────────────┐      ┌──────────────┐
│  blazer img  │~ ~ ~ │  trouser img │   dashed thread connector
└──────────────┘      └──────╮───────┘
                             ↓ needle-dot
                      ┌──────────────┐
                      │  oxford img  │
                      └──────────────┘
        [ Pin this look ]                  ← Basting Red button
```
Spec: 2-column masonry on ivory; outfit pieces joined by the thread motif; the CTA is the only solid-red element on screen.

### 6.4 Add Item flow — «The Fitting»
Stepped progress drawn as a basting stitch that fills in: ○ ┄ ● ┄ ○ — each completed step's dash becomes a solid stitch. Steps: Photograph → Cut (crop) → Cloth (category) → Thread (color) → Hang (save). Copy per §4.2.

### 6.5 Swatch row (item color metadata)
Row of 28 dp fabric-swatch circles; selected swatch wears a brass ring 2 dp with a 2 dp gap; label below in Inter bodySmall ("Madder Red").

### 6.6 Empty states
Single-line garment silhouette centered (120 dp tall), caption in Playfair displayMedium, sub-caption Inter bodyMedium in Twill Grey, CTA in labelLarge. Never illustrations with faces; never 3D renders.

### 6.7 Analytics — «The Ledger»
A fabric ledger book: ivory page, Canvas hairlines as ruled rows, Inter tabular numerals, tiny swatch squares as legend keys. Charts in Aged Brass on ivory; the single "most-worn" highlight in Basting Red. Dark theme variant on Felted Black with Chalk text.

### 6.8 Dark theme — «The Fitting Room»
Felted Black `#16150F` ground, Espresso Velvet cards, Chalk/Driftwood text, Aged Brass accents at full strength (7.44:1 on felt). Basting Red drops to large-text/large-surface use only (3.76:1) — CTAs become Chalk-on-Red or outlined.

### 6.9 Component tokens
| Token | Value |
|---|---|
| Radius | cards 12 dp · chips 999 dp · tags 4 dp (clipped corner) |
| Spacing grid | 4 dp base; screen gutter 20 dp |
| Elevation | 0/2/6 dp, shadow color `#1B1A17` at 8/10/12% |
| Icons | outlined, 1.5 dp stroke, 24 dp — hanger-hook curves on caps where possible |
| Motion | 250 ms ease-out; cards settle like fabric (slight 2% overshoot, never bouncy) |

---

## 7. IMAGE-GENERATION PROMPT LIBRARY (verbatim)

**Prompt A — Logo (rendered in §1.5):**
> Minimalist luxury fashion app logo on warm unbleached-cotton ivory background (#FAF7F0). A clothes hanger drawn in a single unbroken thin line of aged brass gold (#B08A4F), uniform stroke weight, rounded caps. The hanger hook is a small open loop resembling a hand-sewn stitch. The triangle body is wide and shallow like tailored shoulders. At the bottom vertex the line does not close: it continues vertically downward and ends in one small filled teardrop in basting red (#C8442C). Below the mark, the wordmark "styledrop" in lowercase elegant high-contrast serif (Playfair Display style) in near-black ink (#1B1A17), generous letter spacing, with a short dashed red line under it like tailor's basting stitches. Flat vector style, no gradients, no shadows, no background texture, generous negative space, centered composition, luxury fashion house aesthetic.

**Prompt B — App icon:**
> Squircle app icon, very dark felted-black background (#16150F). Centered: a clothes hanger drawn as one continuous thin gold line (#CBA870), the hook shaped like a small stitch loop, the shoulder line wide and shallow; the line continues past the bottom vertex and ends in a single small red teardrop (#C8442C). Flat vector, no text, no gradients, subtle luxury, generous margins.

**Prompt C — Hero UI mock (rendered below):**

![styledrop hero UI mockup](https://www.genspark.ai/api/files/s/DVrOsCX9?cache_control=3600)

> Mobile app UI design mockup for a luxury wardrobe app, warm ivory background (#FAF7F0). Top: lowercase serif wordmark "styledrop". Below: a wardrobe rail of rounded garment photo cards hanging from a thin gold hairline rail. A 2px dashed red basting-stitch line under the active tab. Care-label style chips on the cards. Quiet luxury fashion aesthetic, soft warm shadows, flat modern editorial style, high fidelity UI design, no device frame.

---

## 8. IMPLEMENTATION MAP — this codebase (Android · Jetpack Compose)

The brand was reverse-engineered from the live design system, so most of it is already in the app. Remaining work:

**`app/src/main/java/com/example/ui/theme/Color.kt`** — rename/mapping:
| Existing token | Brand token |
|---|---|
| `BackgroundLight` `#FAF7F0` | Muslin Ivorie |
| `SurfaceAltLight` / `ChipBgLight` `#F1ECE1` | Raw Linen |
| `LineLight` `#E7E0D2` | Canvas |
| `InkLight` `#1B1A17` | Pressing Ink |
| `InkSoftLight` `#4A463E` | Charcoal Wool |
| `MutedTextLight` `#837B6C` | Twill Grey |
| `GoldLight` `#B08A4F` | Aged Brass (light) |
| `GoldDark` `#CBA870` | Aged Brass (dark) |
| `BackgroundDark` `#16150F` | Felted Black |
| `SurfaceDark` `#201E17` | Espresso Velvet |
| `InkDark` `#F3EEE2` | Chalk |
| `InkSoftDark` `#CFC8B8` | Driftwood |

**To add:** `val BastingRed = Color(0xFFC8442C)`, `val RivieraBlue = Color(0xFF46688C)`, `val FieldMoss = Color(0xFF77754E)`, `val MulledWine = Color(0xFF7A3E4A)`, `val BlushMuslin = Color(0xFFE8C4B8)`, plus `SS`/`AW` capsule lists in `models/AppConstants.kt`.

**`ui/theme/Type.kt`** — already correct (Playfair Display + Inter via Google Font provider). Optionally add Cormorant Garamond Italic for the editorial voice. Add `fontFeatureSettings = "tnum"` on analytics numerals.

**`res/drawable/ic_launcher_foreground.xml`** — replace the current hanger vector (which lacks the thread-drop) with the Thread & Drop mark: keep hook path, extend the triangle vertex line downward ~14 dp, terminate with a filled circle-drop in `#C8442C` (r ≈ 2.5 dp at viewport 108).

**`res/values/colors.xml`** — template purple/teal leftovers should be replaced with the brand tokens above.

---

## 9. PROVENANCE

- Guide authored 2026-09-17 from direct analysis of the `styledropv2` repository (Jetpack Compose, existing ivory/gold quiet-luxury theme).
- All contrast ratios computed with the WCAG 2.1 relative-luminance formula at authoring time.
- Logo (§1.5) and hero mock rendered with the **GPT Image 2** model from the verbatim prompts in §7.

*styledrop® brand system v1.0 — cut from one cloth.*
