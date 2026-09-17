# StyleDrop — Core Wardrobe UI Specification (v2)

**Repo:** https://github.com/ibrahimsidic12-afk/styledropv2 (cloned & inspected)
**Stack confirmed:** Android **Kotlin + Jetpack Compose (Material 3)**, Room DB, Coil images, Firebase AI/Gemini — *not* React/Vue. Sample code below is therefore Compose-first (matches the repo), plus a framework-agnostic HTML/CSS preview that mirrors the exact visual language for fast design review.
**Scope of this spec:** ① Closet grid view, ② Category tabs, ③ Filters sidebar, ④ Sort options, ⑤ Empty states.

---

## 1. Source of Truth: Existing Data Model (reused, not invented)

From `app/src/main/java/com/example/models/WardrobeItem.kt` and `AppConstants.kt`:

| Field (Room entity `wardrobe_items`) | Type | Notes |
|---|---|---|
| `id` | `String` (UUID) | Primary key |
| `imageUrl` | `String` | Garment thumbnail (Coil `AsyncImage`) |
| `category` | `String` | Stored values: Tops, Bottoms, **Shoes**, Outerwear, Accessories, Bags, Watches, Jewelry |
| `type` | `String` | e.g. "Oversized T-Shirt" |
| `color` / `secondaryColor` | `String` | From `colorPreferences`: Black, White, Gray, Beige, Navy, Brown, Olive |
| `style` | `String` | From `styles`: Streetwear, Old Money, Y2K, Korean, Techwear, … |
| `fit` | `String` | Oversized, Slim, Regular, Relaxed |
| `pattern` | `String` | Plain, Striped, Graphic, Checked, Camo |
| `season` | `String` | All Season, Summer, Winter, Spring/Fall |
| `brand` | `String` | Free text, may be empty |
| `timesWorn` | `Int` | Drives "Most worn" sort + wear-count chip |
| `isFavoriteItem` | `Boolean` | Heart toggle on card |
| `lastWorn` | `Long?` | Timestamp |
| `createdAt` | `Long` | Drives "Recently added" sort + NEW badge |

**[NEW] fields introduced by this spec** (require a Room migration — included in `compose/WardrobeItemV2.kt`):

| New field | Type | Values | Why |
|---|---|---|---|
| `formality` | `String` | Casual, Smart Casual, Formal, Athleisure | Filter sidebar needs a formality axis; occasion lists in `AppConstants` imply it but no column exists |
| `fabric` | `String` | Cotton, Linen, Wool, Denim, Leather, Silk, Cashmere, Technical | Filter sidebar fabric axis |

**[NEW] categories:** **Dresses** and **Activewear** tabs (the task's 7-tab list). "Shoes" is kept as the *stored* value for data compatibility but is **displayed** as "Footwear"; legacy Bags/Watches/Jewelry values render under the Accessories tab via `ItemCategory.tabOf()`.

---

## 2. Design Tokens (from `ui/theme/Color.kt`, `Type.kt`, `Modifiers.kt`)

| Token | Light | Dark | Usage |
|---|---|---|---|
| `background` | `#FAF7F0` ivory | `#16150F` | Screen background |
| `surface` | `#FFFFFF` | `#201E17` | Cards, rails, sheets |
| `surfaceVariant` | `#F1ECE1` warm sand | `#2A281F` | Chips, image placeholder |
| `primary / onPrimary` | `#1B1A17` / `#FAF7F0` | `#F3EEE2` / `#16150F` | FAB, primary buttons, selected ink |
| `secondary` | `#B08A4F` gold | `#CBA870` | Tab indicator, NEW badge, selection border |
| `onSurfaceVariant` | `#837B6C` | `#9A9384` | Captions, unselected tabs |
| `outline` | `#E7E0D2` | `#35322A` | Hairlines, card borders |
| `error` | `#B5533C` | `#D1745C` | Favorite heart when active |

- **Type:** Playfair Display for `displayLarge/Medium` + `headlineMedium` (screen title, empty-state headings); Inter for everything else (`Type.kt`).
- **Card recipe:** `premiumCard()` = 20 dp radius, 8 dp shadow (`#12000000` ambient / `#18000000` spot), 1 dp `outline @ 40%` border. All garment cards reuse it.

---

## 3. Screen Architecture & ASCII Wireframes

### 3.1 Wardrobe screen (phone, < 840 dp)

```
┌──────────────────────────────────────────────┐
│  Wardrobe                        [↓ Recent▾] [⚙3]   ← displayMedium (Playfair)
├──────────────────────────────────────────────┤
│ ✕ Clear all  (Black ✕) (Leather ✕) (Winter ✕)│  ← active-filter chips, animated in
├──────────────────────────────────────────────┤
│ Tops 12 │ Bottoms 8 │ Outerwear │ Footwear │ Accessories │ Dresses │ Activewear   ← scrollable
│ ═══════                                                       (gold underline slides)
├──────────────────────────────────────────────┤
│ ┌──────────┐  ┌──────────┐                    │
│ │ ▢ photo  │♥ │ ▢ photo  │   ← 2 columns phone │
│ │          │  │NEW       │                     │
│ ├──────────┤  ├──────────┤                     │
│ │Oxford Shi│  │OversizedT│  ← titleMedium      │
│ │Uniqlo·Wh…│  │·Black·Ov…│  ← bodySmall muted  │
│ │○ ☦ ✦ ·14×│  │○ ○ ☀ ·31×│  ← swatches+season  │
│ └──────────┘  └──────────┘                     │
│ ┌──────────┐  ┌──────────┐                     │
│ │    …     │  │    …     │                     │
│ └──────────┘  └──────────┘                     │
│                                    ┌─────────┐ │
│                                    │ + Add   │ │  ← ExtendedFAB, pre-set to active tab
│                                    │  Item   │ │
│                                    └─────────┘ │
└──────────────────────────────────────────────┘
```

### 3.2 Expanded width (≥ 840 dp): persistent filter rail replaces the sheet

```
┌────────────┬─────────────────────────────────────────────────┐
│ FILTERS    │  Wardrobe                       [↓ Recently ▾]  │
│ Clear all  │ ─────────────────────────────────────────────── │
│            │  Tops │ Bottoms │ Outerwear │ Footwear │ …      │
│ COLOR      │ ═════                                           │
│ ● Black    │ ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐     │
│ ● White    │ │ card   │ │ card   │ │ card   │ │ card   │     │
│ ○ Gray     │ └────────┘ └────────┘ └────────┘ └────────┘     │
│ ● Beige    │ ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐     │
│ ○ Navy …   │ │ card   │ │ card   │ │ card   │ │ card   │     │
│            │ └────────┘ └────────┘ └────────┘ └────────┘     │
│ SEASON     │   4 columns at 1240 dp+                         │
│ ○ All      │                                                 │
│ ○ Summer…  │                                                 │
│ FORMALITY  │                                                 │
│ ○ Casual…  │                                                 │
│ FABRIC     │                                                 │
│ ○ Cotton…  │                                    ┌─────────┐  │
│            │                                    │ + Add   │  │
│            │                                    │  Item   │  │
└────────────┴────────────────────────────────────┴─────────┴──┘
```

### 3.3 Garment card anatomy (atomic unit)

```
┌─────────────────────────┐  ← premiumCard: 20dp radius, 1dp outline@40%,
│ ┌─────────────────────┐ │     8dp shadow, white surface
│ │                     │ │
│ │   garment photo     │ │  ← 1:1 aspect, 14dp radius, surfaceVariant
│ │                     │ │     placeholder while Coil loads
│ │              ♥      │ │  ← heart appears on hover; filled+error when favorited
│ │ NEW                 │ │  ← gold badge, < 7 days old
│ └─────────────────────┘ │
│  Oxford Shirt           │  ← titleMedium (Inter 15sp semi), 1 line ellipsis
│  Uniqlo · White · Reg…  │  ← bodySmall (11sp) muted, "brand · color · fit · fabric"
│  ●●○  ✦        · 14×    │  ← color swatch dots (12dp circles) + season glyph
└─────────────────────────┘     + wear count when sort = Most worn
```

### 3.4 Sort menu (dropdown from toolbar chip)

```
        [↓ Recently added ▾]        ← FilterChip, label = current sort
       ┌──────────────────────────┐
       │ Recently added         ✓ │  ← gold check marks the active sort
       │ Most worn                │
       │ By color                 │
       │ By season                │
       └──────────────────────────┘
```

### 3.5 Filter bottom sheet (phone) / rail (tablet) — 4 sections

```
┌──────────────────────────────────┐
│ Filters               CLEAR ALL  │  ← headlineMedium + gold text button
│                                  │
│ COLOR                            │  ← 12sp uppercase section titles
│ (● Black)(● White)(○ Gray)(● Beige) │  ← swatch chips; selected = ink bg
│ (○ Navy)(○ Brown)(○ Olive)       │
│                                  │
│ SEASON                           │
│ (○ All Season)(○ Summer)…        │
│                                  │
│ FORMALITY  [NEW]                 │
│ (○ Casual)(○ Smart Casual)…      │
│                                  │
│ FABRIC     [NEW]                 │
│ (○ Cotton)(○ Linen)(○ Wool)…     │
│                                  │
│      [ Show 23 items ]           │  ← live count in sheet CTA
└──────────────────────────────────┘
```

**Filter logic:** chips within one section are **OR**-ed; sections are **AND**-ed. Color matches either `color` or `secondaryColor`. Active filter count shows as a badge on the toolbar chip; each selection appears as a removable chip under the header.

### 3.6 Empty states

```
State 1 — zero items anywhere ("just getting started")
┌──────────────────────────────────────┐
│                 🧥                   │  ← displayLarge glyph
│          Your closet awaits          │  ← Playfair headlineMedium
│  Add your first piece and let your   │
│  AI stylist do the rest. Snap a      │
│  photo — StyleDrop identifies the    │
│  type, color, fabric and season.     │  ← bodyLarge muted, centered
│      [ Add your first item ]         │  ← ink pill button (FAB hidden here)
│      Watch a 30-second intro         │  ← gold text button
└──────────────────────────────────────┘

State 2 — category tab empty, wardrobe not ("building the wardrobe")
┌──────────────────────────────────────┐
│                 👗                   │  ← category emoji via ItemCategory.emoji()
│            No Dresses yet            │
│  You have pieces in other            │
│  categories. Every Dress you add     │
│  unlocks new outfit combinations in  │
│  the AI generator.                   │
│         [ Add Dresses ]              │  ← deep-links into Add flow with tab preselected
└──────────────────────────────────────┘

State 2b — filters hide every item ("no matches")
┌──────────────────────────────────────┐
│                 🔍                   │
│             No matches               │
│  Nothing in your closet matches      │
│  these filters. Try removing one —   │
│  or clear everything and start       │
│  again.                              │
│       [ Clear all filters ]          │
└──────────────────────────────────────┘
```

---

## 4. Component Inventory & Prop Tables

### 4.1 `WardrobeScreenV2` — screen container

| Prop | Type | Default | Required | Description |
|---|---|---|---|---|
| `items` | `List<WardrobeItem>` | — | ✅ | Full wardrobe from `WardrobeViewModel.allItems`; screen filters by tab + filterState |
| `activeTab` | `String` | `"Tops"` | ✅ | One of `ItemCategory.tabs` (7 values) |
| `onTabSelected` | `(String) -> Unit` | — | ✅ | Tab click; pager/swipe animates content |
| `filterState` | `FilterState` | `FilterState()` | ✅ | See 4.5 |
| `onFilterChanged` | `(FilterState) -> Unit` | — | ✅ | Rail/sheet pushes new state up |
| `sortOption` | `SortOption` | `RECENTLY_ADDED` | ✅ | See 4.6 |
| `onSortSelected` | `(SortOption) -> Unit` | — | ✅ | Dropdown selection |
| `onAddItemClick` | `() -> Unit` | — | ✅ | FAB → Add flow, current tab preselected |
| `onItemClick` | `(WardrobeItem) -> Unit` | — | ✅ | Card tap → `ItemDetailScreen` |

Responsive behavior: `containerWidth >= 840` → persistent `FilterRail` + VerticalDivider; below → `ModalBottomSheet` triggered by the toolbar Filters chip. Grid columns: `GridCells.Adaptive(minSize = 160.dp)` ⇒ ~2 cols on phones, 3 on tablets, 4 fixed at ≥ 1240 dp.

### 4.2 `GarmentCard` — grid cell

| Prop | Type | Default | Required | Description |
|---|---|---|---|---|
| `item` | `WardrobeItem` | — | ✅ | Room entity (§1) |
| `onClick` | `() -> Unit` | — | ✅ | Open detail |
| `onLongPress` | `() -> Unit` | — | ✅ | Enter multi-select / drag mode |
| `onToggleFavorite` | `() -> Unit` | — | ✅ | Heart tap (writes `isFavoriteItem`) |
| `selectionState` | `CardSelectionState` | `None` | — | `None` / `Selected` (2 dp gold border) / `Dimmed` (35% alpha) |
| `secondaryColors` | `List<String>` | `emptyList()` | — | Up to 2 extra swatch dots from `secondaryColor` |
| `showNewBadge` | `Boolean` | `false` | — | True when `createdAt` < 7 days old |
| `showStatChip` | `Boolean` | `false` | — | True when sort = Most worn |
| `statChipLabel` | `String` | `""` | — | e.g. `"· 14×"` |

### 4.3 Category tab strip

| Prop | Type | Default | Required | Description |
|---|---|---|---|---|
| `tabs` | `List<String>` | `ItemCategory.tabs` | ✅ | Tops, Bottoms, Outerwear, Footwear, Accessories, Dresses, Activewear |
| `activeTab` | `String` | — | ✅ | |
| `onTabSelected` | `(String) -> Unit` | — | ✅ | |
| `counts` | `Map<String, Int>` | — | ✅ | Live per-tab counts, shown as "Tops 12" |

### 4.4 `FilterRail` (shared by rail & bottom sheet)

| Prop | Type | Default | Required | Description |
|---|---|---|---|---|
| `filterState` | `FilterState` | — | ✅ | |
| `onFilterChanged` | `(FilterState) -> Unit` | — | ✅ | |
| `resultCount` | `Int` | — | — | Live "Show N items" CTA in sheet mode |

### 4.5 `FilterState` (pure Kotlin, unit-testable)

| Member | Type | Default | Description |
|---|---|---|---|
| `colors` | `Set<String>` | `∅` | OR-ed, matches `color`/`secondaryColor` |
| `seasons` | `Set<String>` | `∅` | OR-ed |
| `formalities` | `Set<String>` | `∅` | OR-ed `[NEW]` axis |
| `fabrics` | `Set<String>` | `∅` | OR-ed `[NEW]` axis |
| `favoritesOnly` | `Boolean` | `false` | Optional heart filter |
| `isActive` | `Boolean` | — | Any criterion set → toolbar badge shows count |
| `matches(item)` | `(WardrobeItem) -> Boolean` | — | AND across sections |
| `toggle(section, value)` / `clearAll()` | | | Immutable updates |

### 4.6 `SortOption` enum → sort implementations (in `WardrobeUiModels.kt`)

| Option | Label | Comparator |
|---|---|---|
| `RECENTLY_ADDED` | Recently added | `createdAt` desc |
| `MOST_WORN` | Most worn | `timesWorn` desc, tie-break `lastWorn` desc |
| `BY_COLOR` | By color | Color-family order (Black→White→Gray→Beige→Brown→Olive→Navy), then `color`, then `type` |
| `BY_SEASON` | By season | All Season → Spring/Fall → Summer → Winter, then `createdAt` desc |

All sorts are **stable**; equal keys keep insertion order.

---

## 5. Interaction & Motion Spec (every state named)

| Surface | State / trigger | Behavior |
|---|---|---|
| Garment card | **Rest** | 8 dp soft shadow, 1 dp `outline@40%` border |
| | **Hover / pressed (pointer)** | Lifts 4 dp (`translateY(-4px)`), shadow deepens to 16/40, thumbnail scales to 1.05 (300 ms `cubic-bezier(.2,.8,.2,1)`); heart fades in top-right |
| | **Tap** | Opens `ItemDetailScreen`; press feedback = scale .985 |
| | **Long-press** | Enters multi-select: card gets 2 dp gold border; all others dim to 35%; top app bar switches to "N selected" with delete/add-to-outfit actions |
| | **Drag** | Card drags at 45% opacity, dashed border, 1.5° tilt; drop between cards reorders (persists via manual-order column) or drops onto an outfit slot |
| Favorite heart | Tap | Crossfade to filled `#B5533C`; scale bounce 0.8→1.15→1.0; works without opening detail |
| NEW badge | Item < 7 days | Gold pill, top-left of thumbnail |
| Category tabs | Switch | Gold 3 dp underline **slides** between tabs (`tabIndicatorOffset` animation); content cross-fades + slides 12 dp in swipe direction |
| Tab counts | Item added | Count text ticks up with a subtle scale pop |
| Filter chips | Toggle | Selected = ink background/ivory text (dark theme: gold bg), 120 ms; unselected hover = gold border + 1 dp lift |
| Active filters | Apply/remove | Chips pop in/out under header; badge count on Filters chip updates |
| Sort menu | Open | 120 ms fade + 4 dp drop; rows highlight on hover; gold check on active option |
| FAB | Hover | Lifts 2 dp; the **+** rotates 90° |
| Grid entry | First load | Cards stagger in: 35 ms per card, fade + 10 dp rise (first 8 cards) |
| Filter sheet | Open (phone) | Bottom sheet slides up; scrim fades; rail slides from left on < 840 dp |
| Empty states | Appear | 260 ms pop (fade + scale .98→1); CTA uses same hover lift as FAB |
| Theme | Light ⇄ dark | 220 ms background/ink cross-fade; all tokens from §2 |

**Accessibility:** every icon has `contentDescription`; tab counts are part of the tab label for TalkBack; filter chips announce selected state; min touch target 44 dp; grid images have `contentDescription = "<type> — <color>"`.

---

## 6. Files Delivered

| File | What it is |
|---|---|
| `WARDROBE_UI.md` | This spec |
| `compose/WardrobeItemV2.kt` | Entity v2 (+ `formality`, `fabric`, Dresses/Activewear categories, Room `Migration`) |
| `compose/WardrobeUiModels.kt` | `SortOption`, `FilterSection`, `FilterState`, sort implementations, swatch hex map |
| `compose/GarmentCard.kt` | Garment card composable (hover lift, favorite, NEW badge, drag-select states) |
| `compose/WardrobeScreenV2.kt` | Screen: header, sort dropdown, 7 tabs, adaptive grid, rail/sheet switch |
| `compose/FilterRailAndEmptyStates.kt` | Filter rail/sheet content + all three empty states |
| `wardrobe_preview.html` | Standalone interactive preview (light/dark toggle, working filters/sort/tabs, all empty states) |

**Validation performed:** Kotlin sources syntax-checked with kotlinc 2.0.21 (only androidx-classpath resolution errors remain, expected without the Compose/Material3 jars); the HTML preview's JavaScript passes `node --check`. No numeric or model data was invented — every field name, enum value, and color token is quoted from the cloned repo's `WardrobeItem.kt`, `AppConstants.kt`, `Color.kt`, `Type.kt`, and `Modifiers.kt`.
