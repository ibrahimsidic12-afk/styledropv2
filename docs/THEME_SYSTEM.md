# StyleDrop — Theme System

**Scope of this document:** a single, portable token-based theme layer for StyleDrop that
delivers Light / Dark / Auto modes, a high-contrast accessibility mode and a reduced-motion
mode, with semantically-named design tokens (e.g. `--color-garment-base`) and a runtime
switcher with persistence.

**What was in the repository (verified by inspection of the clone):** StyleDrop is a
native **Android / Jetpack Compose** app — 63 tracked files, all `.kt` / `.xml` / Gradle.
`find` for `*.css`, `*.html`, `*.js`, `*.ts`, `*.tsx`, `*.scss` returned **zero results**.
The existing theme lives entirely in Compose:

| File (real path from the clone) | Role |
|---|---|
| `app/src/main/java/com/example/ui/theme/Color.kt` | the light + dark palette (ivory / near-black / refined gold) |
| `app/src/main/java/com/example/ui/theme/Theme.kt` | `MyApplicationTheme()` — Material3 `lightColorScheme` / `darkColorScheme` |
| `app/src/main/java/com/example/ui/theme/Type.kt` | typography: **Playfair Display** (display) + **Inter** (body) |
| `app/src/main/java/com/example/ui/theme/Modifiers.kt` | `premiumCard()` — 20 dp radius, 8 dp shadow, 1 dp outline @ 40 % |
| `app/src/main/res/values/themes.xml` | `Theme.MyApplication` (DeviceDefault.NoActionBar) |
| `app/src/main/java/com/example/models/WardrobeItem.kt` | `color`, `secondaryColor`, `category`, `style`, `fit`, `pattern`, `season` |
| `app/src/main/java/com/example/models/AppConstants.kt` | style / colour / pattern / fit / season vocabularies |

So `theme.css` + `theme-switcher.js` are the **web-surface implementation** of that same
design language (PWA shell, share-an-outfit links, marketing pages), and every token is
**mapped 1:1 back to `Color.kt`**. `THEME_SYSTEM.md` is the contract that keeps the two in
sync. No existing repository file was modified by this deliverable.

---

## 1. Architecture

```
        ┌─────────────────────────── LAYER 1 — PALETTE ───────────────────────────┐
        │  --sd-*     raw values, mirrors Color.kt exactly, swapped per mode       │
        │  e.g. --sd-bg: #FAF7F0 (light)  →  #16150F (dark)                        │
        └──────────────────────────────────┬──────────────────────────────────────┘
                                           │  var()
        ┌──────────────────────────────────▼──────────────────────────────────────┐
        │  LAYER 2 — SEMANTIC (the ONLY layer components touch)                   │
        │  --color-page-bg, --color-text, --color-surface, --color-accent,        │
        │  --color-garment-base, --color-garment-true, …                          │
        └──────────────────────────────────┬──────────────────────────────────────┘
                                           │
        ┌──────────────────────────────────▼──────────────────────────────────────┐
        │  LAYER 3 — SCALES   --space-*  --font-*  --radius-*  --shadow-*  --motion-* │
        └──────────────────────────────────┬──────────────────────────────────────┘
                                           │
        ┌──────────────────────────────────▼──────────────────────────────────────┐
        │  LAYER 4 — MODES    html[data-theme]  [data-contrast]  [data-motion]     │
        └─────────────────────────────────────────────────────────────────────────┘
```

**Rules**
1. A component may only reference Layer 2/3 tokens. Never `--sd-*` (palette) directly.
2. Mode switching happens **only** by swapping palette primitives — the semantic names
   never change, so no component needs a mode conditional.
3. `data-theme` is always the **resolved** value (`light` | `dark`). The user's three-way
   choice (`light` | `dark` | `auto`) is kept separately in `data-theme-pref`, so CSS never
   has to reason about "auto".
4. Attributes go on `<html>`, never `<body>` — `<html>` exists before body parsing, which is
   what makes zero-flash switching possible (§7).

### Mode / attribute matrix

| User choice | `<html>` attributes | Painted result |
|---|---|---|
| Light | `data-theme="light" data-theme-pref="light"` | ivory palette |
| Dark | `data-theme="dark" data-theme-pref="dark"` | near-black palette |
| Auto, OS light | `data-theme="light" data-theme-pref="auto"` | ivory palette |
| Auto, OS dark | `data-theme="dark" data-theme-pref="auto"` | near-black palette |
| High contrast on | `+ data-contrast="high"` | pure black/white, 7:1 text |
| Reduced motion on | `+ data-motion="reduced"` | all durations → 0 ms |

---

## 2. Palette tokens (Layer 1) — mirrors `Color.kt` verbatim

| Token | Light | Dark | Compose counterpart (`Color.kt`) |
|---|---|---|---|
| `--sd-bg` | `#FAF7F0` | `#16150F` | `BackgroundLight` / `BackgroundDark` |
| `--sd-surface` | `#FFFFFF` | `#201E17` | `SurfaceLight` / `SurfaceDark` |
| `--sd-surface-alt` | `#F1ECE1` | `#2A281F` | `SurfaceAltLight` / `SurfaceAltDark` |
| `--sd-ink` | `#1B1A17` | `#F3EEE2` | `InkLight` / `InkDark` |
| `--sd-ink-soft` | `#4A463E` | `#CFC8B8` | `InkSoftLight` / `InkSoftDark` |
| `--sd-muted` | `#837B6C` | `#9A9384` | `MutedTextLight` / `MutedTextDark` |
| `--sd-muted-aa` | `#776D5C` | `#9A9384` | **new** WCAG-AA derivative of `--sd-muted` (see §4) |
| `--sd-focus` | `#A07C42` | `#CBA870` | **new** focus-ring gold — darkened so it clears 3:1 on ivory |
| `--sd-gold-ui` | `#AC8748` | `#CBA870` | **new** brand gold nudged to clear 3:1 on ivory as a UI accent |
| `--sd-line` | `#E7E0D2` | `#35322A` | `LineLight` / `LineDark` |
| `--sd-accent` | `#6B6355` | `#BFB39C` | `AccentLight` / `AccentDark` |
| `--sd-gold` | `#B08A4F` | `#CBA870` | `GoldLight` / `GoldDark` |
| `--sd-chip` | `#F1ECE1` | `#2A281F` | `ChipBgLight` / `ChipBgDark` |
| `--sd-danger` | `#B5533C` | `#D1745C` | `error` in `Theme.kt` |

The dark column is **not invented** — it is transcribed from the `BackgroundDark … ChipBgDark`
block of `Color.kt`.

---

## 3. Semantic tokens (Layer 2) — name, light, dark, high-contrast, usage

### Surfaces & text

| Token | Light | Dark | HC (light / dark) | Usage |
|---|---|---|---|---|
| `--color-page-bg` | `#FAF7F0` | `#16150F` | `#FFFFFF` / `#000000` | app background, screen scaffold |
| `--color-surface` | `#FFFFFF` | `#201E17` | `#FFFFFF` / `#000000` | cards (`premiumCard`) |
| `--color-surface-alt` | `#F1ECE1` | `#2A281F` | `#F2F2F2` / `#141414` | alt panels, tab track |
| `--color-chip-bg` | `#F1ECE1` | `#2A281F` | `#F2F2F2` / `#141414` | filter chips |
| `--color-text` | `#1B1A17` | `#F3EEE2` | `#000000` / `#FFFFFF` | headings + body |
| `--color-text-soft` | `#4A463E` | `#CFC8B8` | `#000000` / `#FFFFFF` | secondary body |
| `--color-text-muted` | `#776D5C` | `#9A9384` | `#1A1A1A` / `#E6E6E6` | captions, hints (AA-safe: 4.76:1 / 5.09:1) |
| `--color-text-inverse` | `#FAF7F0` | `#16150F` | `#FFFFFF` / `#000000` | text on ink-filled buttons |
| `--color-border` | `#E7E0D2` | `#35322A` | `#000000` / `#FFFFFF` | card outlines, dividers |
| `--color-divider` | `#E7E0D2` | `#35322A` | `#000000` / `#FFFFFF` | `HorizontalDivider` parity |
| `--color-overlay` | `rgb(27 26 23 / .45)` | `rgb(0 0 0 / .62)` | `.72` / `.72` | scrims, sheets |

### Brand / status

| Token | Light | Dark | HC (light / dark) | Usage |
|---|---|---|---|---|
| `--color-brand-ink` | `#1B1A17` | `#F3EEE2` | `#000000` / `#FFFFFF` | primary button / FAB / selected tab |
| `--color-on-brand-ink` | `#FAF7F0` | `#16150F` | `#FFFFFF` / `#000000` | label on the above |
| `--color-accent` | `#AC8748` | `#CBA870` | `#6B4E00` / `#FFD98A` | gold accents, active states — clears **3:1** on both page bg and card |
| `--color-accent-display` | `#B08A4F` | `#CBA870` | `#6B4E00` / `#FFD98A` | the original brand gold — decorative fills and large text only, where 3:1 does not apply |
| `--color-on-accent` | `#1B1A17` | `#16150F` | `#FFFFFF` / `#000000` | label on gold |
| `--color-accent-quiet` | `#6B6355` | `#BFB39C` | `#000000` / `#FFFFFF` | low-emphasis accent text |
| `--color-danger` | `#B5533C` | `#D1745C` | `#8B1A00` / `#FF9E86` | errors, destructive |
| `--color-success` | `#4F7A52` | `#8FBF92` | `#145214` / `#A8F0AC` | saved / synced |
| `--color-warning` | `#B07D2A` | `#E0B25C` | `#6B4E00` / `#FFD98A` | soft warnings |
| `--color-focus-ring` | `#A07C42` | `#CBA870` | `#0000EE` / `#7FD4FF` | `:focus-visible` outline (≥3:1 everywhere) |
| `--color-focus-ring-off` | `#FAF7F0` | `#16150F` | `#FFFFFF` / `#000000` | halo separating ring from bg |

### Garment / wardrobe tokens — the part that most often breaks

| Token | Light | Dark | HC (light / dark) | Usage |
|---|---|---|---|---|
| `--color-garment-base` | `#FFFFFF` | `#E6E6E6` | `#FFFFFF` / `#000000` | neutral plate behind a garment photo |
| `--color-garment-alt` | `#E9E9E9` | `#D2D2D2` | `#FFFFFF` / `#000000` | 2nd checkerboard square |
| `--color-garment-border` | `#D8D2C6` | `#6E6E6E` | `#000000` / `#FFFFFF` | plinth edge — keeps pale garments visible |
| `--color-garment-plate` | `#FFFFFF` | `#1B1A13` | `#FFFFFF` / `#000000` | frame around the plate |
| `--color-garment-true` | `#8A8A8A` | *(never themed)* | *(never themed)* | fallback when a colour is unknown |
| `--color-garment-label` | `#1B1A17` | `#1B1A17` (fixed) | `#000000` / `#FFFFFF` | text drawn **on** the plate — the plate backing is mode-neutral light grey, so its ink is fixed dark |
| `--color-garment-caption` | `#776D5C` | `#9A9384` | `#1A1A1A` / `#E6E6E6` | text **under** the plate, on the card surface |
| `--color-garment-meta-bg` | `rgb(255 255 255 / .82)` | `rgb(20 19 15 / .86)` | `.95` / `.95` | badge sitting **on** the photo |
| `--color-garment-meta-ink` | `#1B1A17` | `#F7F3EA` | `#000000` / `#FFFFFF` | badge text |
| `--color-garment-shadow` | `0 1px 2px rgb(27 26 23/.18)` | `0 1px 2px rgb(0 0 0/.55)` | unchanged | plate lift |

**The garment-colour law (why `--color-garment-true` is never themed):**
`WardrobeItem.color` is *product data* — "Oversized Tee · **Black**", "Cargo Pant · **Olive**".
If the theme tinted it, a black item would go invisible in dark mode and an ivory item would
glow white. Therefore:

* The **item's real colour** is painted from an inline custom property `--garment-color`
  (set from the DB value) — identical in light, dark and high contrast.
* The **backing** behind it is a mode-neutral grey checkerboard (`--color-garment-base` /
  `--color-garment-alt`), so pale *and* deep garments keep the same relative contrast in
  both modes, and translucent colours stay readable.
* A `--color-garment-border` ring guarantees a white garment is not invisible on a white card.
* Any text drawn *on* the colour is picked by measurement — see §6 (`bestInk`, 4.5:1 floor).

---

## 4. Accessibility tokens

### High contrast — `html[data-contrast="high"]`

| Change | Light HC | Dark HC |
|---|---|---|
| Text on background | `#000000` on `#FFFFFF` = **21:1** | `#FFFFFF` on `#000000` = **21:1** |
| Muted text | `#1A1A1A` | `#E6E6E6` |
| Borders | `#000000`, width `2px`, alpha `1` | `#FFFFFF`, width `2px`, alpha `1` |
| Shadows | `none` | `none` |
| Focus ring | `#0000EE`, **3px** | `#7FD4FF`, **3px** |
| Checkerboard | switched **off** (decorative noise) | off |
| Gold accent | `#6B4E00` (**7.73:1** on white) | `#FFD98A` |

High-contrast is declared **after** the dark block in `theme.css`, so it always wins; the
combined selector `html[data-theme="dark"][data-contrast="high"]` handles dark+HC.
`@media (forced-colors: active)` maps the ring to the OS `Highlight` keyword so Windows
High Contrast and Android "high contrast text" users are handed back to the platform.

### Reduced motion — `html[data-motion="reduced"]`

* Every `--motion-*` duration collapses to `0ms`.
* A blanket rule forces `animation-duration: 0.001ms`, `transition-duration: 0.001ms`
  and `scroll-behavior: auto` on `*` and its pseudo-elements.
* `@media (prefers-reduced-motion: reduce)` applies the same treatment when the user has
  never touched the in-app switch — the OS setting is always honoured, and JS never
  *overrides* it: `resolvedMotion()` returns `reduced` if either the user or the OS asked.

### Why the existing muted colour was changed

Measured with the WCAG 2.1 relative-luminance formula (numbers reproduced by the harness in
§8), the repository's `MutedTextLight`
`#837B6C` scores **3.91:1** on the ivory page background (`#FAF7F0`) and **4.19:1** on a
white card — below the 4.5:1 AA floor for body text. `--sd-muted-aa` (`#776D5C`) keeps the
same warm hue family and scores **4.76:1** on ivory and **5.09:1** on white. The primitive
`--sd-muted` is preserved untouched (still 1:1 with `Color.kt`) and is used only where
`#837B6C` is acceptable — captions inside cards at ≥3:1 — while `--color-text-muted`
points at `--sd-muted-aa`. Dark mode needed no change (`#9A9384` = **5.99:1** on `#16150F`).

---

## 5. Scale tokens (Layer 3)

### Spacing — mirrors the `.dp` paddings found across the screens

| Token | Value | Used by (repo evidence) |
|---|---|---|
| `--space-1` / `--space-2` | `2px` / `4px` | chip padding |
| `--space-3` / `--space-4` / `--space-5` | `8px` / `12px` / `14px` | tile gaps, badge padding |
| `--space-6` / `--space-7` | `16px` / `18px` | card interior (`premiumCard` `.padding(18.dp)`) |
| `--space-8` | `20px` | screen gutter (`HomeScreen` `.padding(20.dp)`) |
| `--space-9` / `--space-10` | `24px` / `32px` | section gaps, empty-state padding |
| `--space-11` / `--space-12` | `40px` / `48px` | hero rhythm |

### Radius / elevation

| Token | Value | Parity |
|---|---|---|
| `--radius-card` | `20px` | `premiumCard(shape = RoundedCornerShape(20.dp))` |
| `--radius-lg` | `16px` | `ExtendedFloatingActionButton(shape = RoundedCornerShape(16.dp))` |
| `--radius-md` / `--radius-sm` / `--radius-xs` | `12` / `8` / `6px` | chips, inputs, focus ring |
| `--radius-pill` | `999px` | chips, badges, theme switch |
| `--shadow-card` | `0 8px 24px rgb(0 0 0/.07), 0 2px 6px rgb(0 0 0/.05)` | `shadow(8.dp, ambient 0x12000000, spot 0x18000000)` |
| `--shadow-raised` / `--shadow-fab` | `4px` / `6px` layer | badges, FAB |
| `--card-border-width` / `--card-border-alpha` | `1px` / `0.4` | `border(1.dp, outline.copy(alpha = 0.4f))` |

### Typography — mirrors `Type.kt` (Playfair Display + Inter)

| Token | Value | Compose style |
|---|---|---|
| `--font-family-display` | `"Playfair Display", "Times New Roman", serif` | `PlayfairDisplayFont` |
| `--font-family-text` | `"Inter", "Helvetica Neue", Arial, sans-serif` | `InterFont` |
| `--font-size-display-lg` | `34px` | `displayLarge` |
| `--font-size-display-md` | `26px` | `displayMedium` |
| `--font-size-headline` | `22px` | `headlineMedium` |
| `--font-size-title-lg` / `-md` | `18px` / `15px` | `titleLarge` / `titleMedium` |
| `--font-size-body-lg` / `-md` / `-sm` | `15px` / `13px` / `11px` | `bodyLarge` / `bodyMedium` / `bodySmall` |
| `--font-size-label` | `13px` | `labelLarge` |
| `--line-height-headline` / `-body-lg` / `-body-md` | `1.27` / `1.53` / `1.38` | `28sp/22sp`, `23sp/15sp`, `18sp/13sp` |
| `--letter-spacing-display` / `-label` | `-0.5px` / `0.8px` | `displayLarge`, `labelLarge` |

### Motion

| Token | Value | Notes |
|---|---|---|
| `--motion-fast` / `-base` / `-slow` | `120ms` / `200ms` / `320ms` | 0 ms under reduced motion |
| `--ease-standard` | `cubic-bezier(0.2, 0, 0, 1)` | default everywhere |
| `--ease-emphasis` | `cubic-bezier(0.3, 0, 0.1, 1)` | sheets, page transitions |

### Layout

`--layout-gutter` (20px), `--layout-max-content` (1120px), `--layout-tap-target` (48px —
Android/Google minimum touch target), `--z-header` (100), `--z-overlay` (200), `--z-toast` (300).

---

## 6. Runtime API — `theme-switcher.js`

Zero dependencies, no build step, safe with `<script defer>`. Attaches
`window.StyleDropTheme`.

| Member | Type | Behaviour |
|---|---|---|
| `.mode` | getter | `"light" \| "dark" \| "auto"` — the stored choice |
| `.setMode(m)` | method | validates against the allowed set, persists, paints, returns the applied value |
| `.toggleMode()` | method | cycles light → dark → auto → light |
| `.contrast` / `.setContrast(c)` / `.toggleContrast()` | | `"normal" \| "high"` |
| `.motion` / `.setMotion(m)` / `.toggleMotion()` | | `"normal" \| "reduced"` |
| `.resolved` | getter | `"light" \| "dark"` — what is actually painted (resolves `auto` via `matchMedia`) |
| `.preference` | getter | `{ mode, contrast, motion }` snapshot |
| `.subscribe(fn)` | method | calls `fn` immediately then on every change; returns an unsubscribe fn |
| `.reset()` | method | clears the saved preference, back to `auto` / `normal` / `normal` |
| `.color.bestInk(color)` | method | `{ tone, ink, ratio }` — the ink that clears **4.5:1** on that colour |
| `.color.contrastRatio(a, b)` | method | WCAG 2.1 ratio between two CSS colours |
| `.applySwatchTones(root?)` | method | walks `.sd-swatch`, measures each real colour, writes `data-swatch-tone` + `--swatch-ink` |

**Events**

* Custom DOM event `styledrop:themechange` on `document` with
  `detail = { preference:{mode,contrast,motion}, resolved:{theme,motion} }`.
* Cross-tab sync via the `storage` event — changing the theme in one tab repaints the others.
* Live `matchMedia` listeners on `(prefers-color-scheme: dark)`,
  `(prefers-reduced-motion: reduce)` and `(forced-colors: active)`.
* Native bridge: if the Android shell exposes `window.StyleDropNative.onThemeChange`,
  each change is pushed to it as JSON so Compose screens can react without a reload.

**Markup contract for the switcher** — any element carrying one of
`data-theme-set="light|dark|auto"`, `data-contrast-set="normal|high"`,
`data-motion-set="normal|reduced"` becomes a control automatically (one delegated click
listener, no per-button JS). The runtime keeps `aria-pressed` in sync, so screen readers
announce the active mode:

```html
<div class="sd-theme-switch" role="group" aria-label="Colour theme">
  <button type="button" data-theme-set="light"     data-theme-set-title="Light">Light</button>
  <button type="button" data-theme-set="dark"      data-theme-set-title="Dark">Dark</button>
  <button type="button" data-theme-set="auto"      data-theme-set-title="Auto">Auto</button>
</div>
```

**Storage resilience:** every `localStorage` access is wrapped in `try/catch` with an
in-memory fallback, so Safari private mode / storage-disabled WebViews degrade to a
session-only preference instead of throwing.

---

## 7. Integration guide

### 7.1 Wire it into a web surface (PWA shell, share pages, marketing)

1. Copy `theme/theme.css`, `theme/theme-switcher.js` into the site root (e.g. `static/`).
2. Add the **FOUC-blocking bootstrap** as the *first* script in `<head>` — it patches the
   attributes before the first paint, so there is no flash of the wrong theme:

```html
<script>(function(){try{
  var s=JSON.parse(localStorage.getItem('styledrop.theme.v1')||'{}');
  var m=s.mode||'auto', t=m==='auto'
    ? (matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light') : m;
  var h=document.documentElement;
  h.setAttribute('data-theme',t);
  h.setAttribute('data-theme-pref',m);
  h.setAttribute('data-contrast',s.contrast||'normal');
  h.setAttribute('data-motion',s.motion||'normal');
  h.style.colorScheme=t;
}catch(e){}})();</script>
```

3. Then, in order:

```html
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600&family=Playfair+Display:wght@600&display=swap">
<link rel="stylesheet" href="theme/theme.css">
<meta name="theme-color" content="#FAF7F0">   <!-- rewritten live from --color-page-bg -->
<script defer src="theme/theme-switcher.js"></script>
```

4. Put the switch markup (§6) in your header. Nothing else is required.
5. Use the classes as-is: `.sd-card`, `.sd-btn`, `.sd-chip`, `.sd-divider`,
   `.sd-theme-switch`, `.sd-garment-grid`, `.sd-garment-card`, `.sd-swatch`.
6. `theme/theme-preview.html` is the QA page: it renders both modes, the garment grid, the
   swatches and all three switches side by side — open it locally to eyeball a change.

### 7.2 Paint a garment's real colour

```html
<div class="sd-swatch" style="--garment-color:#1F2A4A" title="Navy">
  <span class="sd-swatch__label">N</span>
</div>
```

* `--garment-color` ← `WardrobeItem.color`, `--garment-color-secondary` ←
  `WardrobeItem.secondaryColor` (renders as a half-and-half disc when set).
* Never map a garment colour to a `--color-*` token — it is data, not theme.
* Call `StyleDropTheme.applySwatchTones()` after rendering a new grid (the runtime also
  does it on boot and on a `MutationObserver`), and the label ink is chosen by measurement
  so contrast on the swatch is **≥ 4.5:1** for the real colour.

### 7.3 Keep Compose in step (Android side)

The web tokens are a mirror; the native app must move too, otherwise the APK and the web
shell disagree. Two real defects found in `Theme.kt`:

```kotlin
fun MyApplicationTheme(
    darkTheme: Boolean = false,        // ← (1) forced light; system dark is ignored
    ...
) {
    ...
    window.statusBarColor = BackgroundLight.toArgb()      // ← (2) always ivory,
    WindowCompat.getInsetsController(window, view)        //     even in dark mode
        .isAppearanceLightStatusBars = true               //     → white status icons on ivory
```

Recommended patch, in the same file paths:

```kotlin
@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),   // (1) follow the OS by default
    highContrast: Boolean = LocalHighContrast.current,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = when {
        highContrast && darkTheme -> HighContrastDarkScheme
        highContrast               -> HighContrastLightScheme
        darkTheme                  -> DarkColorScheme
        else                       -> LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val window = (view.context as Activity).window
        window.statusBarColor = scheme.background.toArgb()             // (2)
        WindowCompat.getInsetsController(window, view)
            .isAppearanceLightStatusBars = scheme.background.luminance() > 0.5f
    }
    MaterialTheme(colorScheme = scheme, typography = Typography, content = content)
}
```

* Add `HighContrastLightScheme` / `HighContrastDarkScheme` using the HC column of §4
  (`#FFFFFF`/`#000000`, 2 dp borders, no shadow) so `data-contrast="high"` has a native twin.
* **Reduced motion on Android:** gate animations on `ValueAnimator.areAnimatorsEnabled()`
  (it is false when the user sets *Remove animations*) and keep durations near zero — the
  Compose equivalent of `data-motion="reduced"`.
* **Shared vocabulary:** `secondary = GoldLight`, `primary = InkLight`,
  `surfaceVariant = SurfaceAltLight`, `outline = LineLight`, `onSurfaceVariant =
  MutedTextLight` — swap `onSurfaceVariant` to `MutedTextAA` (`#776D5C`) for the AA win in §4.
* **Persistence:** store the three-way choice in DataStore (the dependency is already
  commented out in `app/build.gradle.kts`) and expose it to the WebView through the
  `StyleDropNative` bridge described in §6 — one preference, both surfaces.

### 7.4 QA checklist

| Check | How |
|---|---|
| No FOUC | hard-reload with `auto` + OS dark; body must never paint ivory first |
| Garment truth | a `#FFFFFF` and a `#000000` item must be equally visible in both modes |
| Swatch labels | every swatch label ≥ 4.5:1 (`StyleDropTheme.color.bestInk`) |
| HC mode | focus the theme switch: a 3 px non-gold ring must be visible; borders snap to 2 px |
| Reduced motion | with `data-motion="reduced"`, transitions are 0 ms and `scroll-behavior` is `auto` |
| Cross-tab | change the mode in tab A, tab B repaints without reload |
| Print / forced colors | `forced-colors: active` hands the palette to the OS |

---

## 8. Verification performed for this deliverable

A Node harness (`verify_themesystem.mjs`) runs against the real files in this repository and
reads values **out of `theme.css` itself** (186 custom-property declarations parsed) rather
than re-stating the palette:

1. **Cascade resolution** — the file was parsed and the cascade resolved for `light`,
   `dark`, `high` and `dark+high`, dereferencing `var()` chains. Sample of the resolved
   output: `--color-page-bg` = `#FAF7F0` / `#16150F` / `#FFFFFF` / `#000000`;
   `--color-focus-ring` = `#A07C42` / `#CBA870` / `#0000EE` / `#7FD4FF`.
2. **Contrast** — 32 text/surface pairs across the four contexts. All pass, e.g. light
   body text `16.26:1`, dark body text `15.80:1`, HC `21.00:1`, caption on card `5.09:1`
   (light), `5.46:1` (dark), focus ring `3.49:1` (light), `8.16:1` (dark).
3. **Mode differentiation** — every probed token differs between light and dark; HC text is
   `#000000` on `#FFFFFF`; dark+HC text is `#FFFFFF` on `#000000`; HC border width is `2px`;
   reduced motion collapses 4 duration tokens to `0ms`.
4. **Runtime** — `theme-switcher.js` was **executed** in a DOM stub: auto under an OS-dark
   stub paints `data-theme="dark"`; an explicit `setMode("light")` overrides the OS;
   returning to `auto` re-resolves to dark; OS-light stub paints light; contrast/motion
   setters write their attributes; the preference round-trips through `localStorage`; the
   `light → dark → auto` toggle cycle was exercised.
5. **Swatch ink** — for each colour name in `AppConstants.colorPreferences` (Black, White,
   Gray, Beige, Navy, Brown, Olive) plus the unknown-colour fallback, the chosen ink clears
   4.5:1: Black `21.00:1`, White `18.89:1`, Gray `4.78:1`, Beige `11.54:1`, Navy `14.11:1`,
   Brown `10.18:1`, Olive `5.54:1`, Unknown `5.47:1`.

No browser was available in the build environment, so these are computed-value and logic
checks rather than pixel screenshots; `theme-preview.html` exists for the visual pass.

## 9. Known limits

* `color-mix()` in the card border is Baseline-2023; older WebViews fall back to the
  `--color-border` solid, which is visually equivalent at `--card-border-alpha: 0.4`.
* `backdrop-filter: blur(6px)` on garment badges degrades to the translucent background
  where unsupported (still ≥ 4.5:1 against a photo edge because the badge ink is fixed).
* Compose ↔ CSS parity is manual: a change in `Color.kt` must be mirrored in §2. A CI check
  comparing the two files is the natural next step.
* `--sd-muted-aa` and `--sd-focus` are **additions** on top of `Color.kt`, introduced only
  because the original values missed WCAG AA (4.19:1) and SC 1.4.11 (2.98:1) respectively.
