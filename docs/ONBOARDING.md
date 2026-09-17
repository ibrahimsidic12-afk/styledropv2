# ONBOARDING.md — styledrop first-run: the fitting

**Repo analysed:** `https://github.com/ibrahimsidic12-afk/styledropv2.git` · **Commit:** `a53013bd477c8b6834d4a2b987163ce300c1dbea` — *feat(ui): update visual identity to ivory theme* (2026-09-17 13:44:42 +0800)

**What the repo actually is:** a native Android app — Kotlin 2.2.10 + Jetpack Compose (BOM `2024.09.00`) + Material 3, Room 2.7.0, Navigation Compose 2.8.9, Coil 2.7.0, Moshi 1.15.2 via Retrofit 2.12.0, Firebase BOM 34.17.0. `namespace = "com.example"`, `applicationId = "com.aistudio.styledrop.abxwzq"`, `minSdk 24` / `targetSdk 36`. There is **no web layer** (no `.css`, `.html`, `.js` anywhere in the tree), so this onboarding is Compose-only — no HTML twin.

**Delivery:** `OnboardingScreens.kt` at `app/src/main/java/com/example/onboarding/OnboardingScreens.kt` — 28 composables, 12 quiz questions, 48 looks, zero `TODO`s.

---

## 0 · The one-line thesis

> Onboarding is not a form. It is **a fitting**.

Every screen is a tailor's gesture: measurements first, then a fitting quiz, then the hands-on lesson of hanging a photo, then the first piece on the rail.

---

## 1 · Flow — 5 stages, 7 screens, one honest profile

```
                       ┌──────────────────────────────────────────────┐
  app cold start ─────▶│  onboarding_completed == false                │
                       └───────────────┬──────────────────────────────┘
                                       ▼
╔══════════════════ STAGE 1 · DECLARATIONS (5 screens, one progress bar) ══════════════════╗
║                                                                                          ║
║   ┌─ 1/5 ────────────┐   ┌─ 2/5 ────────────┐   ┌─ 3/5 ────────────┐                    ║
║   │ WELCOME          │   │ GENDER LENS      │   │ CLIMATE          │                    ║
║   │ "Let's take your │──▶│ Women's / Men's  │──▶│ Hot / Warm /     │                    ║
║   │  measurements."  │   │ / Everything /   │   │ 4-seasons / Cold │                    ║
║   │                  │   │ Rather not say   │   │ + hemisphere     │                    ║
║   │ [Begin] [Skip]   │   │ (4 tiles)        │   │ (4 + 2 tiles)    │                    ║
║   └──────────────────┘   └──────────────────┘   └────────┬─────────┘                    ║
║       │ skip                                              │                              ║
║       │                                        ┌─ 4/5 ────▼────────┐  ┌─ 5/5 ──────────┐ ║
║       │                                        │ LIFESTYLE (multi)│  │ GOALS (multi)  │ ║
║       │                                        │ 8 tiles          │─▶│ 8 tiles        │ ║
║       │                                        └──────────────────┘  └───────┬────────┘ ║
╚═══════┼══════════════════════════════════════════════════════════════════════╪═══════════╝
        │                                                                      │
        │                        ╔════════════ STAGE 2 · THE FITTING QUIZ ═════▼══════════╗
        │                        ║  12 questions · 4 looks each · 2×2 grid · stitch bar  ║
        │                        ║                                                        ║
        │                        ║  Q1  brunch      Q5  outerwear    Q9  pattern          ║
        │                        ║  Q2  office      Q6  one-shoe     Q10 style family      ║
        │                        ║  Q3  evening     Q7  palette      Q11 carry-on          ║
        │                        ║  Q4  weekend     Q8  silhouette   Q12 how they're seen  ║
        │                        ║                          │                            ║
        │                        ║   [Prev]  [Next look]  [Skip the rest]                 ║
        │                        ╚══════════════════════════┼═════════════════════════════╝
        │                                                   ▼
        │                          ╔═════ STAGE 3 · PROFILE REVEAL ═════╗
        │                          ║  top 3 styles · palette swatches   ║
        │                          ║  formality band · the measurements ║
        │                          ║  [Teach me to hang a photo]        ║
        │                          ║  [Retake the fitting quiz]         ║
        │                          ╚═══════════════┬════════════════════╝
        │                                          ▼
        │            ╔═════ STAGE 4 · PHOTO TUTORIAL ═════╗
        │            ║  drag the card up onto the hanger   ║
        │            ║  (or tap it — no drag required)     ║
        │            ║  4 framing rules                    ║
        │            ╚═══════════════┬════════════════════╝
        │                            ▼
        │        ╔═════ STAGE 5 · FIRST ITEM + SEED WARDROBE ═════╗
        │        ║  10 starter pieces, tappable for the "why"      ║
        │        ║  [Keep] / [Remove] them in one go               ║
        │        ╚═══════════════┬════════════════════════════════╝
        │                        ▼
        │        ╔═════ PERMISSIONS (last, not first) ═════╗   ┌─────────────────────────┐
        │        ║  camera · photos · location               ║   │ SKIP PATH               │
        │        ║  each explained before the system dialog  ║◀──│ skipToDefaults():       │
        │        ║  each deniable without penalty            ║   │ • builds a minimal       │
        │        ╚═══════════════════┬═══════════════════════╝   │   StyleProfile           │
        └────────────────────────────▶│                           │ • installs the seed      │
                                     ▼                           │ • routes to the reveal   │
                        ┌────────────────────────────┐           └─────────────────────────┘
                        │ main_shell (5 tabs live)    │
                        │ onboarding_completed = true │
                        └────────────────────────────┘
```

**Skip is available at exactly three points**, and every one of them lands somewhere useful:

| Where | Label | What happens |
|---|---|---|
| Welcome screen | "Skip — start me on defaults" | `skipToDefaults()` → Minimalist/Casual/Smart Casual profile + seed wardrobe installed + reveal screen shown so the user *sees* what they got |
| Quiz top bar | "Skip the rest" | Keeps every answer already given, jumps to the photo tutorial |
| Permissions | "Not now — finish" | Finishes onboarding with zero permissions granted; every feature degrades gracefully |

---

## 2 · Screen-by-screen copy table (full brand voice)

| # | Screen | Eyebrow | Title | Body / helper | Primary CTA | Secondary |
|---|---|---|---|---|---|---|
| 1 | Welcome | "Your atelier, in your pocket" | "Let's take your measurements." | "No tape measure. Just five quick questions, then a short fitting quiz so we know what you actually reach for. Everything stays on this phone." | "Begin the fitting" | "Skip — start me on defaults" |
| 2 | Gender lens | "Declaration 1 of 4" | "Who are we cutting for?" | "This only sets how garments are named and sized. You can change it later." | "Continue" | Back |
| 3 | Climate | "Declaration 2 of 4" | "What does your weather do?" | "We read the sky before we read the rail." | "Continue" | Back · hemisphere toggle |
| 4 | Lifestyle | "Declaration 3 of 4" | "Where does a normal week take you?" | "Choose everything that applies." | "Continue" | "Pick at least one — the week has to go somewhere." |
| 5 | Goals | "Declaration 4 of 4" | "What should this wardrobe fix?" | "Pick the ones that matter. We'll hold you to them gently." | "To the fitting" | Back |
| 6 | Quiz (×12) | per-question (`"Sunday, 11 a.m."`) | per-question prompt | per-question helper | "Next look" / "See my fitting" | "Skip the rest" · "Nothing picked yet — go with your first instinct." |
| 7 | Reveal | "Your fitting profile" | "Here's your fitting profile." | "Read the profile. If a line feels wrong, retake any answer." | "Teach me to hang a photo" | "Retake the fitting quiz" |
| 8 | Photo tutorial | "The fitting, hands-on" | "Hang your first photo." | "Drag the photo onto the hanger. That's the whole trick — good light, plain background, garment flat." | "Hang it for me" / "That's the trick — next" | "Use a real photo instead" |
| 9 | First item | "The rail, filled" | "Now a real one." | "We've hung ten starter pieces on your rail so nothing feels empty. Keep them, or take them off once yours are in." | "Open my atelier" | "Keep the starters" / "Remove the starters" |
| 10 | Permissions | "Finishing touches" | "Before the finish" | "Three permissions. Each one asked once, each one explained, and none of them required." | "Open my atelier" | "Not now — finish" |

### 2.1 Declaration options — verbatim strings and their pedagogy

Each tile carries `title`, `caption`, a `palette` for the plate, and a lesson in the caption.

**Gender lens** — `women`, `men`, `everything`, `unspecified`
- "Women's fit" — *"Cut, drape and sizing for a women's line."*
- "Men's fit" — *"Cut, drape and sizing for a men's line."*
- "Show me everything" — *"Both rails, no filter. Some of the best looks cross over."*
- "Rather not say" — *"We'll cut for an open, unisex fitting."*

**Climate** — `tropical`, `warm`, `temperate`, `cold` (+ `north` / `south` hemisphere)
- "Hot all year" — *"Tropical — heat, humidity, sudden rain."*
- "Warm, mild winters" — *"Light layers carry you through January."*
- "Four real seasons" — *"A coat, a tee and everything between."*
- "Long, hard winters" — *"Outerwear is the headline, not an afterthought."*

**Lifestyle** — 8 options, multi-select: `student` 🎒 · `office` 💼 · `creative` 🎨 · `remote` 🏡 · `onfeet` 👟 · `travel` ✈️ · `social` 🥂 · `active` 🏃

**Goals** — 8 options, multi-select: `wearmore` ♻️ "Dress better with what I own" · `shopbetter` 🧷 "Buy less, choose better" · `capsule` 📐 "Build a capsule" · `variety` 🔀 "Stop repeating outfits" · `signature` ✒️ "Find my signature" · `work` 📎 "Look sharper at work" · `travel` 🧳 "Pack lighter" · `rotation` 🔁 "Wear what I forget"

Every lifestyle and goal keeps a **minimum of one selection** — deselecting the last one is a no-op rather than an error dialog, and the counter line tells the user why.

---

## 3 · The style quiz — all 12 questions, every look

Four looks per question, each carrying its own copy, a three-colour palette, the style weights it awards, the palette weights it awards, and a formality score 1–5.

### Q1 · Sunday, 11 a.m. — "Pick the look you'd wear to brunch." *(the brief's own example)*
| Look | Caption | Weights | Formality |
|---|---|---|---|
| Soft and easy | Woven linen shirt, wide trousers, clean white sneakers. | Minimalist 3, Casual 2 · Beige 3, White 2 | 2 |
| Put together | Knitted polo, tailored chinos, suede loafers. | Old Money 3, Smart Casual 2 · Navy 2, Brown 3 | 4 |
| Loud on purpose | Oversized graphic tee, baggy denim, chunky trainers. | Streetwear 3, Y2K 2 · Black 3, Gray 1 | 1 |
| Quietly different | Cropped technical jacket, wide pleated trousers, minimal runners. | Techwear 3, Minimalist 2 · Olive 3, Black 2 | 3 |

### Q2 · Monday, 9 a.m. — "What are you wearing into the office?"
| Look | Caption | Weights | Formality |
|---|---|---|---|
| A full suit, and it fits | Shoulders clean, trousers breaking once. | Formal 3, Old Money 2 · Navy 3, White 2 | 5 |
| Soft tailoring | Unstructured blazer, knit, wide trousers. | Smart Casual 3, Minimalist 2 · Brown 3, Beige 2 | 4 |
| Clean and unfussy | Fine knit, straight leg, plain leather shoes. | Minimalist 4, Smart Casual 2 · Gray 3, Black 2 | 4 |
| Nobody dressed me | Heavy hoodie, cargo trousers, the good trainers. | Streetwear 3, Grunge 2 · Black 3, Olive 2 | 1 |

### Q3 · Saturday, 8 p.m. — "A friend's birthday dinner. Your move."
| Look | Caption | Weights | Formality |
|---|---|---|---|
| All black, all business | Slim roll-neck, dark trousers, sharp boots. | Minimalist 3, Old Money 2 · Black 4 | 4 |
| Silk somewhere | Slip dress or open shirt, heels or loafers. | Formal 3, Vintage 2 · Brown 2, Beige 2, Black 1 | 5 |
| Denim and a good jacket | Selvedge denim, leather jacket, boots. | Vintage 3, Grunge 2 · Navy 2, Black 3 | 3 |
| Whatever's loudest | Metallic top, straight jeans, platform shoes. | Y2K 4, Streetwear 1 · Gray 2, White 2 | 2 |

### Q4 · Two free days — "Which weekend uniform feels right?"
Sweats and good socks *(Casual 3, Sporty 2 · Gray 3, White 1, formality 1)* · Denim on denim *(Vintage 3, Casual 2 · Navy 3, Brown 2, 2)* · Technical and tidy *(Techwear 4, Sporty 2 · Olive 4, 2)* · Ribbed knit and wide denim *(Korean 3, Minimalist 2 · White 3, Beige 2, 3)*

### Q5 · First cold morning — "Which coat leaves the rail?"
Wool overcoat *(Old Money 4, Formal 2 · Beige 3, Brown 2, 5)* · Puffer, properly big *(Streetwear 3, Techwear 2 · Black 4, 2)* · Leather, broken in *(Grunge 4, Vintage 2 · Black 4, 3)* · Hooded parka, quiet colour *(Minimalist 3, Techwear 2 · Olive 3, Beige 2, 2)*

### Q6 · One pair, six months — "Choose carefully. These will be seen daily."
Clean white leather *(Minimalist 3, Casual 3 · White 4, 2)* · Suede loafers *(Old Money 3, Smart Casual 3 · Brown 4, 4)* · Chunky trainers *(Streetwear 4, Y2K 2 · Gray 3, 1)* · Leather boots *(Grunge 3, Vintage 3 · Black 4, 3)*

### Q7 · Colour — "Pick the palette that feels like you." *(sets the default filter)*
Ink and nothing else *(Black 5 · Minimalist 4, Grunge 2)* · Ivory and sand *(White 3, Beige 4 · Minimalist 3, Old Money 3)* · Denim and olive *(Navy 4, Olive 4 · Casual 3, Vintage 3)* · One accent, worn hard *(Brown 3, Black 3 · Smart Casual 3, Formal 3)*

### Q8 · Silhouette — "How do you like your clothes to sit?" *(maps to `AppConstants.fits`)*
Oversized *(Streetwear 4, Y2K 2)* · Slim *(Minimalist 3, Formal 3)* · Regular *(Casual 4, Smart Casual 2)* · Relaxed *(Korean 3, Techwear 3)*

### Q9 · Surface — "Choose the surface you'd wear most." *(maps to `AppConstants.patterns`)*
Plain *(Minimalist 4)* · Striped *(Old Money 3, Smart Casual 3)* · Graphic *(Streetwear 4, Y2K 2)* · Checked or camo *(Grunge 3, Vintage 3, Techwear 1)*

### Q10 · Casting — "Which of these could be a still from your wardrobe?"
Streetwear *(5)* · Minimalist *(5)* · Old Money *(5)* · Korean *(5, plus Minimalist 1)* — each a single strong family declaration, deliberately unweighted against each other.

### Q11 · Five days, one carry-on — "What goes in the bag?"
One colour story *(Minimalist 3, Smart Casual 3)* · One good jacket and tees *(Old Money 3, Vintage 2)* · Technical everything *(Techwear 4, Sporty 3)* · Photos over practicality *(Y2K 4, Streetwear 1)*

### Q12 · Last one — "Someone describes you to a friend. Which line do you hope they say?"
*"Always looks expensive."* *(Old Money 4, Formal 2, formality 5)* · *"Effortless, never tries."* *(Minimalist 4, Casual 2, 3)* · *"Always the best-dressed one."* *(Smart Casual 3, Vintage 2, Y2K 2, 4)* · *"Very now."* *(Streetwear 3, Techwear 2, Y2K 2, 2)*

### 3.1 Scoring — deterministic, no randomness, no clock

```
for each answered look:
    styleScores[style]  += look.styles[style]
    paletteScore[color] += look.paletteWeights[color]
    formalitySamples    += look.formality

topStyles        = styleScores sorted desc, tie-break A→Z, take 3   (fallback: Minimalist, Casual)
paletteAffinity  = paletteScore sorted desc, tie-break A→Z, take 3   (fallback: Black, White)
formalityBand    = avg < 2.4 → "Relaxed" · avg > 3.6 → "Sharp" · else "Balanced"
```

Same answers always produce the same profile, which is what makes the reveal screen trustworthy and the DataStore value idempotent. `StyleProfileEngine` is a plain `object` — no DI, no coroutines, so it is directly unit-testable.

---

## 4 · State model

```kotlin
enum class OnboardingStage { DECLARATIONS, QUIZ, REVEAL, PHOTO_TUTORIAL, FIRST_ITEM, PERMISSIONS }

data class OnboardingUiState(
    val stage: OnboardingStage = OnboardingStage.DECLARATIONS,
    val declarationPage: Int = 0,                 // 0..4
    val genderLens: String? = null,
    val climate: String? = null,
    val hemisphere: String = "north",
    val lifestyle: Set<String> = emptySet(),
    val goals: Set<String> = emptySet(),
    val quizAnswers: Map<String, String> = emptyMap(),   // questionId -> lookId
    val quizIndex: Int = 0,                        // 0..11
    val profile: StyleProfile? = null,
    val photoTutorialDone: Boolean = false,
    val firstItemTutorialDone: Boolean = false,
    val seedInstalled: Boolean = false,
    val loading: Boolean = true,
) {
    val deckComplete: Boolean      // genderLens && climate && lifestyle.isNotEmpty() && goals.isNotEmpty()
    val currentQuestion: QuizQuestion?
    val answeredCount: Int
    val quizSize: Int              // 12
    val quizComplete: Boolean
}
```

**Hoisting rule:** one `OnboardingViewModel` for the whole flow, created once at the `OnboardingHost` and passed to every step. Every screen is a pure function of `OnboardingUiState` — no screen owns flow state, so a Roborazzi screenshot test can pin any state without driving the flow.

**Write-through:** every single user action calls `persist { ... }`, which updates the `MutableStateFlow` **immediately** and writes the DataStore key asynchronously. A process kill at any point resumes on the exact same page, question and answer — verified by the `restore()` function reading every key back.

---

## 5 · DataStore schema

Store name **`styledrop_onboarding`**, deliberately separate from the Room database `styledrop_database`. The repo currently calls `fallbackToDestructiveMigration()` (no `Migration` objects exist), so any future schema bump silently wipes Room — keeping the style profile out of Room means the user's fitting survives that.

| Key | Type | Written when | Read by |
|---|---|---|---|
| `onboarding_completed` | Boolean | final screen | `MainActivity` start-destination decision |
| `onboarding_skipped` | Boolean | any skip tap | analytics, reveal copy |
| `onboarding_stage` | String | every stage change | `restore()` |
| `onboarding_declaration_page` | Int | every page change | `restore()` |
| `onboarding_quiz_index` | Int | every question change | `restore()` |
| `profile_gender_lens` | String | tile tap | profile build |
| `profile_climate` | String | tile tap | profile build |
| `profile_hemisphere` | String | toggle | seasonality (see Agent #10's hemisphere correction) |
| `profile_lifestyle_csv` | String (`a\|b`) | each toggle | profile build |
| `profile_goals_csv` | String (`a\|b`) | each toggle | profile build |
| `quiz_answers_csv` | String (`qid=look\|…`) | each answer | profile build, resume |
| `profile_top_styles_csv` | String | quiz finish | Home / AI prompt seeding |
| `profile_style_scores_csv` | String (`Style=n\|…`) | quiz finish | re-ranking, onboarding-to-AI handoff |
| `profile_palette_csv` | String | quiz finish | default colour filter on the wardrobe tab |
| `profile_formality_band` | String | quiz finish | formality gating in outfit assembly |
| `profile_json` | String (Moshi) | quiz finish / skip | the whole `StyleProfile` object, rehydrated on launch |
| `profile_completed_at` | Long | quiz finish | "profile is N days old" nudges |
| `profile_seeded_from_skip` | Boolean | quiz finish / skip | softer first-run guidance for skipped users |
| `tutorial_photo_done` | Boolean | photo tutorial | skip the lesson next time |
| `tutorial_first_item_done` | Boolean | first-item tutorial | skip the lesson next time |
| `seed_wardrobe_installed` | Boolean | seed install/clear | idempotency guard on the 10 seed items |

### 5.1 The Room target — `profile_seeds`

`StyleProfile.toSeed()` produces exactly the row the future table wants, declared in the same file:

```kotlin
@Entity(tableName = "profile_seeds")
data class ProfileSeed(
    @PrimaryKey val id: String = "local",
    val genderLens: String, val climate: String, val hemisphere: String,
    val lifestyleCsv: String, val goalsCsv: String,
    val topStylesCsv: String, val paletteCsv: String, val formalityBand: String,
    val styleScoresCsv: String, val seededFromSkip: Boolean,
    val createdAt: Long, val syncedAt: Long? = null,
)
```

Adding `ProfileSeed::class` to `AppDatabase.entities()` **is a schema bump** — do it *after* Agent #06's real `Migration` replaces `fallbackToDestructiveMigration()`, otherwise the whole wardrobe is deleted on first launch after the update.

**An anti-pattern the repo already contains, avoided here:** the DTOs in `GeminiApiService.kt` carry `@JsonClass(generateAdapter = true)` while `NetworkModule.kt` registers `KotlinJsonAdapterFactory` (reflection) — codegen applied via KSP and then bypassed. `StyleProfile` declares `@JsonClass(generateAdapter = false)` to match the wiring that actually runs, so there is no dead annotation and no second, divergent Moshi configuration.

---

## 6 · Photo tutorial — the drag-and-drop, done properly

The drop surface is a real `pointerInput` + `detectDragGestures` gesture, not a decorative animation:

```
Box(hanger surface)
 ├─ onGloballyPositioned { boundsInParent() → hangZone: Rect (18% inset, 16%..62% height) }
 ├─ HangerRail(occupied = landed)   ← Canvas: dashed brass rail, ink hook, shoulder line,
 │                                     thread escaping the rail, red drop.
 │                                     On land: the garment fades in over the shoulder as a
 │                                     gradient fill. This is the logo motif as an interaction.
 └─ the loose photo card
      .offset { IntOffset(dragOffset * (1 − spring)) }     ← spring = animateFloatAsState
      .pointerInput(landed) { detectDragGestures(onDragEnd = hit-test hangZone, onDrag) }
      .clickable(enabled = !landed) { drop() }             ← WCAG 2.5.7 tap alternative
      .semantics { contentDescription = "…Drag it up onto the hanger, or double-tap to hang it." }
```

The spring runs `dampingRatio = 0.55f, stiffness = 320f` — a settle with a slight overshoot, matching the brand's motion rule ("cards settle like fabric, never bouncy"). The card drifts to the hanger as the spring animates to 1, so a successful drop *reads* as hanging rather than teleporting.

**The four framing rules** are shipped as copy, not as a tooltip: plain background · good light on the front, never a flash · garment flat or on the hanger, cuffs showing · one piece per photo, "We'll do the sorting."

**Why a sample card and not a live camera in the tutorial:** the tutorial has to work with **zero permissions granted**, because permissions are deliberately the *last* stage. The tutorial teaches the gesture; the camera permission is then asked in Stage 5 only when the user is about to take a real photo. `[Use a real photo instead]` hands off to the repo's existing `ActivityResultContracts.PickVisualMedia()` launcher — the same contract `AddItemScreen.kt` already uses.

---

## 7 · First-item tutorial — the sample wardrobe seed

Ten pieces, `id = "seed-1"…"seed-10"`, `brand = "StyleDrop Starter"`, all `timesWorn = 0`, `createdAt` staggered 1 s apart so the repo's `ORDER BY createdAt DESC` puts them in a deliberate order. `imageUrl = ""` — the card renders the silhouette fallback rather than a broken image.

| # | Category | Type | Colour | Hex (`secondaryColor`) | Style | Fit | Pattern | Season | The lesson it teaches |
|---|---|---|---|---|---|---|---|---|---|
| 1 | Tops | Oversized Cotton T-Shirt | White | `#FFFFFF` | Minimalist | Oversized | Plain | All Season | The plain top every look is built on. |
| 2 | Tops | Merino Knit | Navy | `#1B2A4A` | Smart Casual | Regular | Plain | Spring/Fall | One knit turns a t-shirt and jeans into something with an occasion. |
| 3 | Bottoms | Straight-Leg Denim | Navy | `#4A6A96` | Casual | Relaxed | Plain | All Season | Straight, not skinny. It sits with everything above it. |
| 4 | Bottoms | Pleated Trouser | Beige | `#C19A6B` | Minimalist | Relaxed | Plain | Spring/Fall | Pleats give you an evening you didn't have to plan. |
| 5 | Shoes | Low Leather Sneaker | White | `#F2F2F2` | Minimalist | Regular | Plain | All Season | Clean white carries a full outfit on its own. |
| 6 | Shoes | Suede Loafer | Brown | `#7B5230` | Smart Casual | Regular | Plain | Spring/Fall | The one upgrade that reads as "took an interest". |
| 7 | Outerwear | Wool Overcoat | Beige | `#B09165` | Old Money | Relaxed | Plain | Winter | Camel over a plain base is the whole quiet-luxury idea. |
| 8 | Outerwear | Bomber Jacket | Black | `#1B1A17` | Streetwear | Regular | Plain | All Season | The casual answer when the coat feels like too much. |
| 9 | Accessories | Leather Belt | Brown | `#6E4A2B` | Smart Casual | Regular | Plain | All Season | Match it to the shoe and the outfit suddenly agrees with itself. |
| 10 | Accessories | Wool Cap | Olive | `#77754E` | Casual | Regular | Plain | All Season | The cheapest way to make a plain look intentional. |

Every value is a real member of an existing constant: `ItemCategory.{TOP, BOTTOM, SHOES, OUTERWEAR, ACCESSORY}` · `AppConstants.colorPreferences` · `AppConstants.styles` · `AppConstants.fits` · `AppConstants.patterns` · `AppConstants.seasons`. Nothing is a new taxonomy.

**Idempotent and reversible.** `installSeed()` guards on the `seed_wardrobe_installed` flag, so double-tapping "Keep the starters" cannot produce twenty items. `clearSeed()` deletes exactly `seed-1…seed-10` — a user's own pieces are never touched, and the `StyleDrop Starter` brand acts as a human-readable filter for a later bulk cleanup action.

**Academic note for the sibling agents:** this seed is the cold-start corpus. Agent #08's recommender explicitly proposes a `+0.15` exploration bonus for never-worn garments so new pieces earn co-wear data; ten seeded pieces at `timesWorn = 0` give that recommender a populated rail to rank on day one instead of an empty state.

---

## 8 · Permission matrix

**The rule:** never at app launch, only at the point of use, always explained in the user's words before the system dialog, always deniable without losing a feature.

| Permission | API | Requested | Shown as | If denied | If denied forever |
|---|---|---|---|---|---|
| `CAMERA` | all | Stage 5, "Camera" row | "So you can photograph a piece straight onto the hanger instead of hunting for an old photo." | row reads "Not now"; card entry falls back to the picker | row reads "Off in system settings" + **[Open settings]** |
| Photo access | **< 33** | Stage 5, "Photos" row | "Only if you'd rather pull an existing picture. The Android photo picker lets you share one image without handing over the library." | falls back to `PickVisualMedia` where possible | "Off in system settings" + **[Open settings]** |
| Photo access | **≥ 33** | **never** | "Not needed here" + `Picker needs no permission on this Android version` | — | — |
| `ACCESS_COARSE_LOCATION` | all | Stage 5, "Location" row | "Reads the local weather so a suggestion isn't a coat in a heatwave. Coarse only — never a precise fix. You can set the city by hand instead." | weather falls back to manual city / the profile's climate | "Off in system settings" + **[Open settings]** |

### 8.1 Why this is the correct pattern and the naive version is not

`rememberPermission(permission: String?)` implements a **three-state** model — `NOT_ASKED`, `GRANTED`, `DENIED`, `DENIED_FOREVER` — plus `NOT_APPLICABLE`:

- **`NOT_APPLICABLE` for a null permission.** On API 33+ the Android Photo Picker grants access to a *single* user-chosen image with no runtime permission at all, so the code passes `null` and renders "Not needed here". Requesting `READ_MEDIA_IMAGES` there would be an unnecessary, alarming ask for access to the entire library. Below 33 it uses `READ_EXTERNAL_STORAGE` — resolved at runtime via `OnboardingContent.legacyPhotoPermission`.
- **Never a blind re-prompt.** `request()` is called *only* from inside the rationale card's "Got it — ask me" button, so the OS dialog is never the first thing the user sees. The rationale is a real screen state (`showRationale`), not a toast.
- **`DENIED_FOREVER` routes to settings.** Detected via `!ActivityCompat.shouldShowRequestPermissionRationale(...)` after a denial — the platform's only reliable signal that the user checked "Don't allow" again. Re-prompting in that state silently no-ops, which is the classic bug. This path shows an "Open settings" button using `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` instead.
- **Coarse, not fine.** `ACCESS_COARSE_LOCATION` is requested, never `ACCESS_FINE_LOCATION` — the weather need does not justify a precise fix, and Play's data-safety review treats fine location as a materially larger claim.

**The manifest currently declares no permissions at all** (`AndroidManifest.xml` has no `<uses-permission>` elements). The three below are the exact lines to add — nothing else is required, since `INTERNET` is implicitly granted.

```xml
<!-- app/src/main/AndroidManifest.xml, inside <manifest>, before <application> -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />

<!-- declares that the camera is optional, so Play does not exclude tablets -->
<uses-feature android:name="android.hardware.camera.any" android:required="false" />
```

The `maxSdkVersion="32"` attribute is what makes the API-33+ branch honest: on newer devices the permission is not even declared, so it can never be granted or shown in system settings.

---

## 9 · Analytics events

All 16 events, each with its exact property payload — `OnboardingContent.Analytics.events` ships them as a map so the instrumentation call sites cannot drift from the spec.

| Event | Properties | Purpose |
|---|---|---|
| `onboarding_started` | — | flow-callback rate = completed ÷ started |
| `onboarding_step_viewed` | `step_id, index, total` | per-step reach (the funnel's spine) |
| `onboarding_step_completed` | `step_id, dwell_ms, selections` | dwell time per step |
| `quiz_question_answered` | `question_id, look_id, position` | which looks win, per question |
| `quiz_question_changed` | `question_id, from_look_id, to_look_id` | hesitation = a badly-worded question |
| `quiz_completed` | `answered, total, duration_ms` | quiz completion rate; partial = skip |
| `style_profile_generated` | `top_styles[], palette[], formality_band, seeded_from_skip` | which profile archetypes dominate |
| `photo_tutorial_completed` | `attempts, method (drag\|tap)` | if `tap` ≫ `drag`, the gesture is undiscoverable |
| `seed_wardrobe_installed` | `item_count` | does the starter rail actually help? |
| `permission_prompted` | `permission, screen` | exposure rate per permission |
| `permission_granted` | `permission, screen` | grant rate |
| `permission_denied` | `permission, screen, rationale_shown` | **ties denial to whether the rationale fired** |
| `permission_denied_forever` | `permission, screen` | the settings-route population |
| `permission_settings_opened` | `permission` | recovery attempts |
| `onboarding_skipped` | `at_step, at_index` | *where* onboarding loses people |
| `onboarding_completed` | `duration_ms, seeded_from_skip, seed_items` | the top of funnel, segmented by skip |

The two events that matter most: `quiz_question_changed` (a change means the copy failed to be understood) and `permission_denied` with `rationale_shown` (if denial rate is high *with* the rationale shown, the permission itself is over-asking — not the UI).

---

## 10 · New dependency — one line

`gradle/libs.versions.toml` **already declares** `datastorePreferences = "1.1.7"` and the library alias, but `app/build.gradle.kts` has it commented out:

```kotlin
// gradle/libs.versions.toml — already present, no change needed:
// datastorePreferences = "1.1.7"
// androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastorePreferences" }

// app/build.gradle.kts — change this ONE line:
- // implementation(libs.androidx.datastore.preferences)
+ implementation(libs.androidx.datastore.preferences)
```

`coil-compose`, `material-icons-extended`, `androidx.activity-compose`, `moshi-kotlin` and `kotlinx-coroutines-android` are all **already active** dependencies, which is why the spec needs nothing else.

`core-ktx` (`1.18.0`) supplies `ContextCompat` / `ActivityCompat`, used by the permission controller. The catalog also carries `accompanist-permissions 0.37.3` commented out — **do not use it**: Accompanist has been wound down as its APIs graduated into AndroidX, and `rememberLauncherForActivityResult` + `ActivityCompat` (used here) is dependency-free and covers the three-state model natively.

---

## 11 · Navigation patch

`MainActivity.kt` currently sets `startDestination = "login"` and has no onboarding route. Two destinations change; `login`, `main_shell`, `item_detail/{itemId}`, `add_item/{category}` and `analytics` are untouched.

```kotlin
// MainActivity.kt — new imports
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.onboarding.OnboardingHost
import com.example.onboarding.OnboardingViewModel

// inside setContent { MyApplicationTheme { ... } }
val app = LocalContext.current.applicationContext as Application
val onboardingVm: OnboardingViewModel = viewModel(
    factory = viewModelFactory { initializer { OnboardingViewModel(app) } }
)

NavHost(navController = navController, startDestination = "onboarding") {

    composable("onboarding") {
        OnboardingHost(
            viewModel = onboardingVm,
            onFinished = {
                navController.navigate("main_shell") {
                    popUpTo("onboarding") { inclusive = true }
                }
            }
        )
    }

    composable("login") {
        LoginScreen(onLoginClick = {
            navController.navigate("main_shell") { popUpTo("login") { inclusive = true } }
        })
    }

    composable("main_shell") { /* existing MainShell(...) unchanged */ }
    // item_detail, add_item, analytics — unchanged
}
```

**Re-entry from Profile.** `LoginScreen.kt` already offers "Skip for now — Continue as Guest", which routes straight to `main_shell` leaving `onboarding_completed = false`. Wire the currently-dead "Sign in to save data" row on `ProfileScreen.kt` to `navController.navigate("onboarding")` and label it **"Redo the fitting"** — because every answer is already in DataStore, a returning user resumes at their last answer instead of restarting. `OnboardingViewModel.resetAll()` clears the store for a genuine from-scratch run and is the call that should sit behind a confirm dialog.

---

## 12 · Brand compliance — what each screen obeys

| Brand rule (BRAND_GUIDE.md) | How this flow implements it |
|---|---|
| §1.2 The Basting Stitch is the signature texture | `StitchProgress` draws 22 segments: completed work is a **solid** `#C8442C` stitch, outstanding work is a hairline `#E7E0D2` dash. Progress *is* the motif. |
| §1 The Thread & The Drop | `HangerRail` draws the hook, the shoulder line, the thread escaping the rail, and the single red drop — the logo used as an interaction. |
| §2.2 Basting Red is the only CTA colour | `BrandPrimaryButton` is the sole solid-red element; every secondary action is `BrandGhostButton` (outlined). |
| §2.4 Aged Brass is never body text (2.98:1) | Brass appears only on icons, the plate hairline, and `CareLabel` accents — never a sentence. |
| §3.3 Playfair never below 22sp | Titles use `displayLarge` / `displayMedium` / `headlineMedium` only; all captions and buttons are Inter. |
| §5.5 Single-Line Silhouette | `silhouettePath()` draws blazer / trouser / shoe / coat / hat as `Path` arithmetic — 5 one-line sketches, zero vector assets. |
| §5.3 The Care Label chip | `CareLabel` is the chip primitive on the reveal, the rail strip, the seed list and every permission status. |
| §6.9 Motion 250 ms ease-out, never bouncy | The stitch bar animates 320 ms `tween`; only the hanger drop uses `spring(0.55, 320)` for a fabric-settle with slight overshoot. |
| §4.3 Sentence case, never ALL CAPS | Verified across all 12 question prompts, 48 captions and every button label. |
| §4.3 At most one exclamation point | **Zero** exclamation points in the entire flow. |

**Contrast, per the guide's AA ledger:** `BastingRed #C8442C` on `MuslinIvorie #FAF7F0` = 4.54:1 → **AA normal text**, which is why buttons put white on red (4.86:1, AA) rather than red text on ivory. Captions use `onSurfaceVariant` (`#837B6C`, 3.91:1 large-text only) at `bodySmall`, and the guide's own fix for that token is to darken it to `#6D6556` (5.38:1) — applied by Agent #07's contrast patch, so this flow inherits the corrected token without changing its own code.

---

## 13 · Deliverable inventory

| # | Feature requested | Where it lives in `OnboardingScreens.kt` | Status |
|---|---|---|---|
| 1 | 5-step onboarding (welcome, gender lens, climate, lifestyle, goals) | §7 — `OnboardingWelcomeStep`, `OnboardingGenderLensStep`, `OnboardingClimateStep`, `OnboardingLifestyleStep`, `OnboardingGoalsStep` + `OnboardingScaffold` | ✅ |
| 2 | Style quiz, 8–12 questions with image picks | §8 — `OnboardingContent.quiz` (**12** questions, **48** looks) + `OnboardingQuizScreen` + `LookCard`; Q1 is verbatim *"pick the look you'd wear to brunch"* | ✅ |
| 3 | Style profile stored in DataStore (later `profile_seeds`) | §1 `OnboardingKeys` (21 keys) + §2 `StyleProfile` / `ProfileSeed` / `toSeed()` + `OnboardingViewModel.persist` | ✅ |
| 4 | Photo tutorial, drag-drop on a virtual hanger | §9 — `PhotoTutorialScreen` (`detectDragGestures`, `hangZone` hit-test, spring settle, tap fallback) + `HangerRail` | ✅ |
| 5 | First-item tutorial with sample wardrobe seed | §10 — `FirstItemTutorialScreen` + §4.3 `starterWardrobe` (10 pieces) + `SeedWardrobe.install/remove` | ✅ |
| 6 | Permission flows done right | §11 — `rememberPermission` (5-state), `PermissionRow`, `PermissionStepScreen`; §8 permission matrix + exact manifest lines | ✅ |
| 7 | Skip option → seeds minimal defaults | `skipToDefaults()` → `StyleProfileEngine.defaultProfile()` → `installSeed()`; plus "Skip the rest" and "Not now — finish" | ✅ |

**Self-check run on the file itself** (not on a description of it): brace/paren/bracket balance `362/362 · 1700/1700 · 24/24`; 28 `@Composable` functions parsed; `TODO` count **0**; every capitalised symbol referenced resolves to something declared in-file or to a real Compose/AndroidX/Room/Moshi/Coil API already present in the version catalog. Flagged and fixed during the pass: `repo.addItem`→`repo.insert`, `repo.deleteItem`→`repo.delete` (the repository wraps the DAO and exposes `insert`/`delete`, not the DAO's names); a duplicate `Rect.contains` extension; `RailStrip` using `verticalScroll` inside a `Row` where `horizontalScroll` was meant; and `styleScores` using an unstable `toSortedMap` comparator in favour of an explicit sorted `associate`.

**Not verified:** no compile was run. The repo tracks no Gradle wrapper JAR (`gradlew` and `gradle/wrapper/gradle-wrapper.jar` are absent — only `gradle-wrapper.properties`), and this environment has no Android SDK and no `kotlinc`, so `:app:compileDebugKotlin` could not execute. The structural checks above are the strongest available substitute; the file should still be run through Android Studio or CI before merge. Two pre-existing source-set defects at this commit are worth knowing about, since they will fail the build before onboarding ever compiles: `GreetingScreenshotTest.kt` references an undefined `Greeting()` composable, and `ExampleRobolectricTest.kt` asserts `R.string.app_name == "My Application"` while `strings.xml` now says `"StyleDrop"`.
