# StyleDrop v2 — Architecture Report

> **Scope:** Read-only analysis. No source file was modified or deleted.
> **Source:** `https://github.com/ibrahimsidic12-afk/styledropv2.git`
> **Commit analyzed:** `a53013bd477c8b6834d4a2b987163ce300c1dbea` — `feat(ui): update visual identity to ivory theme` (2026-09-17 13:44:42 +0800)
> **Tracked files:** 63 · **Kotlin source lines:** 2,324
> **Clone result:** SUCCESS (public repo, `git clone --depth 1`, exit 0). No access issues.

---

## 1. Executive Summary

StyleDrop ("Your AI Wardrobe Stylist") is **not a web app** — it is a **native Android application written in Kotlin with Jetpack Compose**, built from Google AI Studio's Android template. There is no backend server, no REST API surface of its own, and no database server: all wardrobe data is stored **on-device in Room (SQLite)**, and the only network dependency is a direct call to the **Google Gemini API** (`generativelanguage.googleapis.com`) from the client.

The app is an **early-stage MVP (~2.3k LOC)**. The core vertical slice — *add a clothing item → store it locally → browse it by category → ask Gemini to style an outfit from owned items* — exists and is coherent. Several advertised surfaces are **stubs**: Saved Outfits is an empty-state screen with `TODO` action buttons, Profile shows hardcoded `"0"` stats and a `"Guest User"`, weather is a hardcoded string, authentication is a no-op button, and there is **no camera capture, no image persistence, no cloud sync, and no outfit save path**.

---

## 2. Tech Stack

| Layer | Technology | Version / Detail |
|---|---|---|
| Language | Kotlin | `2.2.10` |
| UI toolkit | Jetpack Compose + Material 3 | Compose BOM `2024.09.00` |
| Build system | Gradle (Kotlin DSL) | Wrapper targets Gradle `9.3.1` |
| Android Gradle Plugin | AGP | `9.1.1` |
| SDK levels | minSdk 24 / targetSdk 36 / compileSdk 36 (minorApiLevel 1) | Java 11 source & target |
| Local database | Room (SQLite) | runtime/ktx/compiler `2.7.0` |
| DI / ViewModel | AndroidX Lifecycle ViewModel + manual `ViewModelProvider.Factory` | lifecycle `2.8.7` |
| Navigation | Navigation Compose | `2.8.9` (string routes) |
| Networking | Retrofit + OkHttp + Moshi | `2.12.0` / `4.10.0` / `1.15.2` |
| AI | Gemini API direct HTTP (`v1beta/models/gemini-1.5-flash:generateContent`) | key via `BuildConfig` |
| Image loading | Coil Compose | `2.7.0` |
| Firebase | BOM `34.17.0` — **App Check only** (recaptcha + debug) | Firestore/Auth commented out |
| Fonts | Google Fonts provider (Inter, Playfair Display) | `font_certs.xml` |
| Testing | JUnit 4, Robolectric `4.16.1`, Roborazzi `1.59.0` | screenshot testing wired |
| Secrets | `secrets-gradle-plugin` `2.0.1` reading `.env` | `GEMINI_API_KEY` → `BuildConfig` |
| Package managers | Gradle (Kotlin DSL) + version catalog (`gradle/libs.versions.toml`). No npm/pip/cargo. | |

**Dependency notes (from `app/build.gradle.kts`):** camera (`camera2/core/lifecycle/view`), `accompanist-permissions`, `play-services-location`, `datastore-preferences`, `firebase-firestore`, `firebase-auth`, `credentials`, `googleid` are all declared in the version catalog but **commented out** in the dependency block. `firebase-ai` **is** active as a dependency, but no Kotlin source imports Firebase AI — only the Retrofit Gemini client is used. So there is **dead/parallel AI plumbing**.

---

## 3. Architectural Pattern

```
MainActivity (ComponentActivity, single-activity)
 └─ MyApplicationTheme
     └─ NavHost (startDestination = "login")
         ├─ "login"                → LoginScreen(onLoginClick)                    [no auth]
         ├─ "main_shell"           → MainShell(5-tab bottom nav)
         │     ├─ 0 Home           → HomeScreen(onNavigateToAI)
         │     ├─ 1 Wardrobe       → WardrobeScreen(onItemClick, onAddItemClick, onAnalyticsClick)
         │     ├─ 2 AI             → AiGeneratorScreen()
         │     ├─ 3 Saved          → SavedOutfitsScreen()   [stub]
         │     └─ 4 Profile        → ProfileScreen()        [hardcoded]
         ├─ "item_detail/{itemId}" → ItemDetailScreen
         ├─ "add_item/{category}"  → AddItemScreen
         └─ "analytics"            → AnalyticsScreen
```

Layering: **UI (Compose) → ViewModel (StateFlow) → Repository → DAO (Room) → SQLite**. The AI path diverges: **ViewModel → `NetworkModule.geminiApiService` (Retrofit) → Gemini**. Manual construction (no Hilt/Koin): `AppViewModelProvider` is an `Application`-backed factory that builds `AppDatabase` → `WardrobeRepository` → `WardrobeViewModel`. `AiGeneratorViewModel` is created with a **bare default `viewModel()`** (no factory, no repository), so the AI screen and the wardrobe screen hold **separate ViewModel instances** reading the same Room DB via Flow.

---

## 4. Wardrobe Domain Logic (the core of the app)

### 4.1 Entity — `WardrobeItem` (`@Entity(tableName = "wardrobe_items")`)
| Field | Type | Default | Notes |
|---|---|---|---|
| `id` | String `@PrimaryKey` | `UUID.randomUUID().toString()` | client-generated |
| `imageUrl` | String | — | **stores the picker `Uri.toString()`**, not a copied file |
| `category` | String | — | one of 8 `ItemCategory` values |
| `type` | String | — | free text, e.g. "Oversized T-Shirt" |
| `color` | String | — | free text |
| `secondaryColor` | String | `""` | **persisted but never written or rendered** |
| `style` | String | — | from `AppConstants.styles` |
| `fit` | String | — | from `AppConstants.fits` |
| `pattern` | String | — | from `AppConstants.patterns` |
| `season` | String | `"All Season"` | from `AppConstants.seasons` |
| `brand` | String | `""` | optional free text |
| `timesWorn` | Int | `0` | **displayed in detail screen, never incremented anywhere** |
| `isFavoriteItem` | Boolean | `false` | **never toggled; no UI writes it** |
| `lastWorn` | Long? | `null` | **never set** |
| `createdAt` | Long | `System.currentTimeMillis()` | sort key |

### 4.2 Categories — `ItemCategory`
`Tops, Bottoms, Shoes, Outerwear, Accessories, Bags, Watches, Jewelry` (8), each with an emoji via `ItemCategory.emoji()`: 👕 👖 👟 🧥 🧢 👜 ⌚ 💍. `ItemCategory.all` is the ordered driver of the wardrobe tab pager and the Analytics breakdown.

### 4.3 Taxonomy constants — `AppConstants`
`occasions` (11): School, College, Work, Casual, Date, Party, Wedding, Gym, Travel, Beach, Formal.
`styles` (12): Streetwear, Casual, Minimalist, Old Money, Y2K, Korean, Smart Casual, Formal, Sporty, Vintage, Techwear, Grunge.
`colorPreferences` (8): Any, Black, White, Gray, Beige, Navy, Brown, Olive.
`shoePreferences` (6): Any, Sneakers, Boots, Loafers, Sandals, Running Shoes.
`patterns` (5): Plain, Striped, Graphic, Checked, Camo.
`fits` (4): Oversized, Slim, Regular, Relaxed.
`seasons` (4): All Season, Summer, Winter, Spring/Fall.

### 4.4 Persistence — `AppDatabase` / `WardrobeDao` / `WardrobeRepository`
- DB name `styledrop_database`, `version = 2`, `exportSchema = false`, singleton via double-checked `@Volatile INSTANCE`.
- **`fallbackToDestructiveMigration()`** — any future schema bump silently wipes user data. No `Migration` objects exist.
- DAO queries: `getAllItems()` (`ORDER BY createdAt DESC`, `Flow`), `getItemsByCategory(category)` (`Flow`), `getItemById(id)` (`suspend`, `LIMIT 1`), `insertItem` (`OnConflictStrategy.REPLACE`, `suspend`), `updateItem` (`@Update`), `deleteItem(id)` (raw `DELETE`).
- Repository is a thin pass-through; it exposes `allItems` as a `Flow` and wrappers for the rest. No caching, no threading policy of its own (Room suspend + Flow handle it).

### 4.5 State — `WardrobeViewModel`
`allItems: StateFlow<List<WardrobeItem>>` via `stateIn(WhileSubscribed(5000), emptyList())`. Derived selectors are computed **in memory from `allItems`** (not SQL): `getItemsByCategory()` filters, `getCategoryCount()` counts. Mutations: `addItem`, `updateItem`, `deleteItem` (fire-and-forget `viewModelScope.launch`), plus `suspend getItemById()` used once by the detail screen. **Note:** every call to `getItemsByCategory`/`getCategoryCount` creates a *new* `stateIn` flow — the wardrobe screen calls `getCategoryCount` once per tab (8 flows) and `CategoryGrid` re-subscribes per page.

### 4.6 Wardrobe UI flow
- **`WardrobeScreen`** — `ScrollableTabRow` + `HorizontalPager` (sync'd via `pagerState`), one `CategoryGrid` per category, `ExtendedFloatingActionButton("Add Item")` that passes the *currently paged* category into the route. Analytics icon in the top bar.
- **`CategoryGrid`** — 3-column `LazyVerticalGrid`, `items(items, key = { it.id })`, empty state with category emoji + hint text.
- **`ItemCard`** — square `AsyncImage` (Coil) + type/color caption.
- **`AddItemScreen`** — uses `ActivityResultContracts.PickVisualMedia` (Photo Picker). Fields: type, color, category, pattern, style, fit, season, brand. Persists `WardrobeItem(imageUrl = imageUri.toString(), …)` with fallbacks `type.ifBlank{"Item"}`, `color.ifBlank{"Black"}`. **No camera, no crop, no upload, no URI permission persistence.**
- **`ItemDetailScreen`** — `LaunchedEffect(itemId)` loads once; read mode shows an `InfoRow` card; edit mode toggles inline `DropdownField`s/`OutlinedTextField`s and writes back with `item.copy(...)`; delete opens an `AlertDialog` then `deleteItem` + pop. **Edit does not touch `brand`, `imageUrl`, `secondaryColor`, `isFavoriteItem`.**

### 4.7 AI stylist logic — `AiGeneratorViewModel` + `GeminiApiService`
Prompt construction (verbatim shape): a system line declaring the user an "expert AI Stylist", then the **entire wardrobe serialized as `"- {color} {pattern} {category} ({type}) [ID: {id}]"` per line**, then the captured controls — `count` outfits, occasion, style, color preference, weather string, shoe preference — and the hard constraint *"Only use the exact items provided in the wardrobe list."* Response is taken as `candidates[0].content.parts[0].text` and shown as a plain text card.
Guard: empty wardrobe → inline error `"Your wardrobe is empty! Add some items first."`. Failures surface the exception message plus a hint to set the Gemini key.
**Weaknesses:** no JSON/structured response (imports `org.json.JSONArray/JSONObject` are unused), no item-ID validation of what the model returns, no saved-outfit write path, no retry/timeout tuning, and the weather value is a hardcoded literal (`"22°C Partly Cloudy"` when the toggle is on, `"Mild"` otherwise). The API key is passed as a **query parameter** (`@Query("key")`), and the model id is **hardcoded to `gemini-1.5-flash`** in the Retrofit interface.

---

## 5. Network Layer

| File | Responsibility |
|---|---|
| `network/NetworkModule.kt` | Object singleton. `BASE_URL = "https://generativelanguage.googleapis.com/"`, Moshi built with `KotlinJsonAdapterFactory()`, Retrofit + `MoshiConverterFactory`, lazy `geminiApiService`. |
| `network/GeminiApiService.kt` | Retrofit interface + 5 `@JsonClass(generateAdapter = true)` DTOs (`GenerateContentRequest`, `Content`, `Part`, `GenerateContentResponse`, `Candidate`). `@POST("v1beta/models/gemini-1.5-flash:generateContent")`, key defaulted from `BuildConfig.GEMINI_API_KEY`. |

⚠️ **Moshi inconsistency:** DTOs use codegen annotations (`generateAdapter = true`, and KSP runs `moshi-kotlin-codegen`), while `NetworkModule` registers the **reflection-based** `KotlinJsonAdapterFactory` instead of `Moshi.Builder().build()` + generated adapters. This works but is redundant and a known source of subtle runtime issues. Also, OkHttp logging interceptor is a declared dependency but **no `OkHttpClient` is ever installed** into Retrofit.

---

## 6. UI / Design System

- **Brand direction:** "ivory / quiet-luxury", **light-first** (`MyApplicationTheme(darkTheme = false)` by default; `dynamicColor = false` to protect brand). Status bar forced to `BackgroundLight` with dark icons.
- **Palette (`theme/Color.kt`):** ivory background `#FAF7F0`, white surfaces `#FFFFFF`, sand alt `#F1ECE1`, ink `#1B1A17`, muted `#837B6C`, hairline `#E7E0D2`, gold accent `#B08A4F`, error `#B5533C`. A full dark scheme exists (background `#16150F`, gold `#CBA870`) but is not reachable from the UI (no theme switch).
- **Typography (`theme/Type.kt`):** **Playfair Display** (serif) for `displayLarge/displayMedium/headlineMedium`; **Inter** for titles/body/labels — both fetched at runtime via the Google Fonts provider using the certs in `font_certs.xml`.
- **`Modifier.premiumCard()` (`theme/Modifiers.kt`):** the shared visual primitive — 8dp shadow + clip + surface background + 1dp outline border at 40% alpha. Used by Home, Analytics, Profile, AI toggles, detail card.
- **Icons:** `material-icons-extended` (Checkroom, AutoAwesome, DashboardCustomize, CalendarMonth, …); launcher icon is a hand-drawn **gold hanger** vector (`ic_launcher_foreground.xml`) on an ivory-gradient background, with adaptive + monochrome variants.
- **Legacy/dead styling:** `res/values/colors.xml` still holds template `purple_200/500/700`, `teal_200/700`; `themes.xml` only declares `Theme.MyApplication` on `android:Theme.DeviceDefault.NoActionBar`.

---

## 7. Complete File Inventory (all 63 tracked files)

### 7.1 Root
| File | Purpose |
|---|---|
| `.env.example` | Root env template; documents `GEMINI_API_KEY` placeholder for AI Studio injection (commented out by default). |
| `.gitignore` | Ignores IDE files, `.gradle`, `.kotlin`, `build/`, `local.properties`, `.env`, `debug.keystore`. |
| `build.gradle.kts` | Root Gradle file; declares plugin aliases (android.application, kotlin.compose, KSP, roborazzi, secrets, google-services) with `apply false`. |
| `gradle.properties` | JVM args `-Xmx4g`, parallel + caching + configuration-cache on, `workers.max=4`, in-process Kotlin compiler, `googleServices.missing.passthrough=true`. |
| `metadata.json` | AI Studio app manifest: name `StyleDrop`, description "Your AI Wardrobe Stylist", `majorCapabilities: [MAJOR_CAPABILITY_SERVER_SIDE_GEMINI_API]`, no frame permissions. |
| `settings.gradle.kts` | Root project name `StyleDrop`; includes `:app`; plugin + dependency repositories (google, mavenCentral, gradlePluginPortal); FAIL_ON_PROJECT_REPOS. |

### 7.2 Gradle infrastructure
| File | Purpose |
|---|---|
| `gradle/libs.versions.toml` | Version catalog — 47 libraries, 7 plugins (see §2). ~7.5 KB, the single source of versions. |
| `gradle/wrapper/gradle-wrapper.properties` | Wrapper config pointing to Gradle `9.3.1-bin`. |

### 7.3 `app/` module config
| File | Purpose |
|---|---|
| `app/build.gradle.kts` | Android module config: namespace `com.example`, applicationId `com.aistudio.styledrop.abxwzq`, SDK levels, release+debug signing configs, `BuildConfig.GEMINI_API_KEY` from `System.getenv("GEMINI_API_KEY") ?: "YOUR_API_KEY_HERE"`, secrets plugin (`.env` / `.env.example`), google-services WARN strategy, full dependency block with commented-out camera/Firestore/Auth. |
| `app/proguard-rules.pro` | Template ProGuard file — all rules commented out (minify disabled anyway). |
| `app/.gitignore` | Ignores `build/`. |
| `app/.env.example` | `GEMINI_API_KEY="YOUR_API_KEY_HERE"` — module-level secrets template. |

### 7.4 Manifest & resources
| File | Purpose |
|---|---|
| `app/src/main/AndroidManifest.xml` | Single exported `MainActivity` (LAUNCHER), app label `@string/app_name`, icon/roundIcon, backup rules, RTL support, theme `Theme.MyApplication`. **No permissions declared at all** (no CAMERA, no INTERNET explicitly — the latter is implicit for network in practice via AGP, but not declared). |
| `res/values/strings.xml` | One string: `app_name = StyleDrop`. |
| `res/values/colors.xml` | Legacy Material template colors (purple/teal) — unused by Compose theme. |
| `res/values/themes.xml` | `Theme.MyApplication` extends `android:Theme.DeviceDefault.NoActionBar`. |
| `res/values/font_certs.xml` | Google Fonts provider certificates (dev + prod arrays) required by `Type.kt`. |
| `res/xml/backup_rules.xml` | Auto-backup rules, fully commented out. |
| `res/xml/data_extraction_rules.xml` | API 31+ cloud-backup/device-transfer rules, fully commented out. |
| `res/drawable/ic_launcher_background.xml` | Ivory→sand linear-gradient adaptive-icon background. |
| `res/drawable/ic_launcher_foreground.xml` | Gold clothes-hanger vector (hook + triangle) adaptive-icon foreground. |
| `res/mipmap-anydpi-v26/ic_launcher.xml` | Adaptive icon wiring background/foreground/monochrome. |
| `res/mipmap-anydpi-v26/ic_launcher_round.xml` | Round adaptive icon (same wiring). |
**10 raster WebP launcher assets (legacy densities):** `res/mipmap-hdpi/ic_launcher.webp`, `res/mipmap-hdpi/ic_launcher_round.webp`, `res/mipmap-mdpi/ic_launcher.webp`, `res/mipmap-mdpi/ic_launcher_round.webp`, `res/mipmap-xhdpi/ic_launcher.webp`, `res/mipmap-xhdpi/ic_launcher_round.webp`, `res/mipmap-xxhdpi/ic_launcher.webp`, `res/mipmap-xxhdpi/ic_launcher_round.webp`, `res/mipmap-xxxhdpi/ic_launcher.webp`, `res/mipmap-xxxhdpi/ic_launcher_round.webp` — pre-rendered `ic_launcher` + `ic_launcher_round` bitmaps for hdpi/mdpi/xhdpi/xxhdpi/xxxhdpi. |

### 7.5 Entry points
| File | Purpose |
|---|---|
| `MainActivity.kt` | Single-Compose-activity entry; `enableEdgeToEdge()`; wraps `MyApplicationTheme`; defines the whole `NavHost` (login → main_shell → item_detail/{itemId}, add_item/{category}, analytics). |
| `LoginScreen.kt` | Branded login: icon + "StyleDrop" + tagline "Your AI Wardrobe Stylist", divider, three `SocialButton`s (Google / Apple / Email) and "Skip for now — Continue as Guest". **Every button just fires the same `onLoginClick` → straight to `main_shell`. No authentication exists.** |

### 7.6 Data layer
| File | Purpose |
|---|---|
| `data/AppDatabase.kt` | Room `@Database(entities=[WardrobeItem], version=2)`; singleton builder, DB `styledrop_database`, destructive migration fallback. |
| `data/WardrobeDao.kt` | DAO interface — 6 operations (see §4.4). |
| `data/WardrobeRepository.kt` | Repository exposing `allItems` Flow + CRUD wrappers. |

### 7.7 Models
| File | Purpose |
|---|---|
| `models/WardrobeItem.kt` | `@Entity` wardrobe row (15 fields) + `ItemCategory` object (8 constants + `all` list + `emoji()` mapper). |
| `models/AppConstants.kt` | Static taxonomy lists driving all dropdowns (see §4.3). |

### 7.8 Network
| File | Purpose |
|---|---|
| `network/GeminiApiService.kt` | Retrofit interface + Moshi request/response DTOs for `generateContent`. |
| `network/NetworkModule.kt` | Retrofit/Moshi singleton exposing `geminiApiService`. |

### 7.9 UI layer (12 files — screens, view models, factory)
| File | Purpose |
|---|---|
| `ui/MainShell.kt` | 5-tab `NavigationBar` (Home, Wardrobe, AI, Saved, Profile) + `Crossfade` screen switching; `PlaceholderScreen` helper; `NavigationItem` data class. |
| `ui/HomeScreen.kt` | Greeting by local hour (`Good morning/afternoon/evening/Working late`), "Your wardrobe, curated.", **hardcoded weather card ("22°C • Partly Cloudy")**, 3 `StatTile`s (Tops/Bottoms/Shoes counts from Room), "Recommended Outfit" placeholder card with CTA into the AI tab. |
| `ui/WardrobeScreen.kt` | Category tab row + pager + `CategoryGrid` + `ItemCard` + FAB. |
| `ui/AddItemScreen.kt` | Photo-picker + metadata form + save; also hosts the shared `DropdownField` composable used app-wide. |
| `ui/ItemDetailScreen.kt` | Read/edit/delete an item; `InfoRow` + delete `AlertDialog`. |
| `ui/AiGeneratorScreen.kt` | AI Stylist form (occasion, style, color pref, weather toggle, shoe pref, "Use only my wardrobe" — **disabled switch**, outfit count 1/3/5) + result card + error surface. |
| `ui/AnalyticsScreen.kt` | Total items, most common color (`groupingBy/eachCount/maxByOrNull`), per-category counts; `StatCard` composable. |
| `ui/SavedOutfitsScreen.kt` | **Stub.** Empty state ("No saved outfits yet") + two `TODO` actions (Outfit Calendar, Mix & Match). No persistence anywhere. |
| `ui/ProfileScreen.kt` | **Stub.** Hardcoded "Guest User"/"Guest account"/"FREE", stats hardcoded to `"0"`, settings rows that do nothing (Notifications, Appearance, Sign in to save data). |
| `ui/AppViewModelProvider.kt` | Manual `ViewModelProvider.Factory` — DB → repository → `WardrobeViewModel` wiring (no Hilt/Koin). |
| `ui/WardrobeViewModel.kt` | Stateful wardrobe VM (see §4.5). |
| `ui/AiGeneratorViewModel.kt` | AI VM: builds prompt, calls Gemini, exposes `isGenerating`/`generatedResult`/`error`. |

### 7.10 Theme
| File | Purpose |
|---|---|
| `ui/theme/Color.kt` | Light + dark brand color tokens (ivory/gold/ink). |
| `ui/theme/Theme.kt` | `MyApplicationTheme` — light/dark schemes, dynamic-color branch (off), status-bar styling. |
| `ui/theme/Type.kt` | GoogleFont provider + Inter & Playfair Display families + full Material 3 `Typography`. |
| `ui/theme/Modifiers.kt` | `premiumCard()` shared surface modifier. |

### 7.11 Tests
| File | Purpose |
|---|---|
| `test/ExampleUnitTest.kt` | Template `2 + 2 == 4`. |
| `test/ExampleRobolectricTest.kt` | Asserts `R.string.app_name == "My Application"` — **stale: `strings.xml` now says "StyleDrop", so this test FAILS.** |
| `test/GreetingScreenshotTest.kt` | Roborazzi screenshot test rendering `Greeting("Robolectric")` to `src/test/screenshots/greeting.png` — **`Greeting()` is not defined anywhere in the repo, so this source set does not compile.** |
| `test/screenshots/greeting.png` | Committed baseline screenshot from the AI Studio template (2.8 KB). |
| `androidTest/ExampleInstrumentedTest.kt` | Asserts instrumentation package name equals `BuildConfig.APPLICATION_ID`. |

---

## 8. Feature Status Matrix

| Feature | State | Evidence |
|---|---|---|
| Add wardrobe item (photo + metadata) | ✅ Implemented | `AddItemScreen.kt` |
| Local persistence | ✅ Implemented | Room `WardrobeItem` + DAO |
| Browse by 8 categories (tabs + pager + grid) | ✅ Implemented | `WardrobeScreen.kt` |
| Item detail / edit / delete | ✅ Implemented | `ItemDetailScreen.kt` |
| AI outfit generation (text) | ✅ Implemented (network-dependent) | `AiGeneratorViewModel.kt` |
| Wardrobe analytics (total / top color / per-category) | ✅ Implemented | `AnalyticsScreen.kt` |
| Home dashboard | 🟡 Partial — hardcoded weather, "Recommended Outfit" is a placeholder | `HomeScreen.kt` |
| Save / list / manage outfits | ❌ Stub — empty state, `TODO`s, no entity | `SavedOutfitsScreen.kt` |
| Outfit calendar, Mix & Match | ❌ Not built (`TODO` onClick) | `SavedOutfitsScreen.kt` |
| Authentication (Google/Apple/Email) | ❌ Fake — all buttons bypass to main shell | `LoginScreen.kt` |
| Profile data (items, outfits made) | ❌ Hardcoded `"0"` / `"Guest User"` | `ProfileScreen.kt` |
| Camera capture | ❌ Absent (deps commented out; Photo Picker only) | `app/build.gradle.kts`, `AddItemScreen.kt` |
| Image storage / cloud sync | ❌ Absent — raw `Uri` string only | `WardrobeItem.imageUrl` |
| Wear tracking (`timesWorn`, `lastWorn`, favourites) | ❌ Fields exist, no UI/logic writes them | `WardrobeItem.kt` |
| Weather integration | ❌ Hardcoded string, no API, no location deps | `AiGeneratorScreen.kt`, `HomeScreen.kt` |
| Dark mode toggle | ❌ Scheme exists but unreachable | `Theme.kt` (`darkTheme = false`) |
| Notification of outfits / push | ❌ Not built (App Check deps unused) | — |

---

## 9. Access Issues & Risks

1. **Clone access:** ✅ No issue. Repository is public; `git clone --depth 1` exited 0 and `git status --porcelain` is clean.
2. **No Gradle wrapper JAR / scripts:** `gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar` are **not tracked** — only `gradle-wrapper.properties`. A fresh clone **cannot build via `./gradlew`** until `gradle wrapper` is regenerated or a system Gradle 9.3.1 is used.
3. **Build prerequisites absent (by design, gitignored):** `app/.env` (→ `GEMINI_API_KEY`) and `google-services.json` are not present. Without them the app compiles but `BuildConfig.GEMINI_API_KEY` = `"YOUR_API_KEY_HERE"` → **all AI calls fail at runtime** with the in-app error. `googleServices.missing.passthrough=true` prevents a build failure.
4. **Toolchain mismatch in analysis sandbox:** JDK 21 available, no Android SDK / no system Gradle → a full `assembleDebug` was **not attempted** (out of scope for read-only analysis). Versions (AGP 9.1.1, Gradle 9.3.1, Kotlin 2.2.10, compileSdk 36) are very recent and require a matching Android Studio/JDK 17+ environment.
5. **Test source set is broken at HEAD:** `GreetingScreenshotTest.kt` references an undefined `Greeting` composable → `:app:testDebugUnitTest` will not compile; `ExampleRobolectricTest.kt` asserts the wrong app name → fails once it does compile. **There is no CI config in the repo** to have caught this.
6. **No instrumentation of quality gates:** no detekt/ktlint/spotless, no GitHub Actions, no ProGuard rules, `isMinifyEnabled = false` in release, and a hardcoded **release keystore path** default (`${rootDir}/my-upload-key.jks`) with passwords read from env.
7. **Security/architecture risks for the enhancement phase:** API key embedded in the APK via `BuildConfig` and sent as a URL query param; destructive migrations; client-side-only data (no backup path); Gemini model id hardcoded (legacy `gemini-1.5-flash`); no offline queue/error retry; unbounded prompt growth as the wardrobe serializes every item into one request.
8. **Dead code / drift:** `firebase-ai` dependency active but unused; OkHttp logging interceptor declared but no `OkHttpClient` installed; `PlaceholderScreen`, `secondaryColor`, `isFavoriteItem`, `timesWorn`, `lastWorn` present but unused; unused `org.json` imports in `AiGeneratorViewModel`.

---

## 10. Recommended Enhancement Surface (for the 20-agent effort)

| Area | Concrete lever |
|---|---|
| Outfit persistence | New `SavedOutfit` / `OutfitItem` Room entities + DAO + migration (replace destructive fallback with a real `Migration`), and wire `SavedOutfitsScreen` + a SAVE action on the AI result card. |
| Structured AI output | Force JSON via `responseMimeType`/schema, validate returned `[ID]`s against the wardrobe, and add a deterministic fallback stylist that composes top+bottom+shoes when the model is unavailable. |
| Real weather | Location + weather provider (deps already cataloged), replacing the `"22°C Partly Cloudy"` literal. |
| Image pipeline | Copy picker URIs into app-private storage, compress, persist permissions (`takePersistableUriPermission`), add camera capture (deps cataloged but commented). |
| Wear tracking | Increment `timesWorn`/set `lastWorn`, expose favourites, add cost-per-wear analytics. |
| Architecture hygiene | Fix Moshi adapter wiring, install OkHttpClient with interceptor, share one ViewModel scope across tabs, inject via a proper DI container, restrict/avoid per-tab `stateIn` creation. |
| Quality gates | Fix/rewrite the two broken tests, add CI (build + unit + Roborazzi), detekt/ktlint, ProGuard rules, dark-mode toggle, and a keystore-in-CI strategy. |
| Backend (optional) | The commented Firestore/Auth/Credential-Manager deps are the intended path to sync + real sign-in; today `MAJOR_CAPABILITY_SERVER_SIDE_GEMINI_API` in `metadata.json` signals server-side Gemini proxying as the secure key strategy. |

---

*Generated by read-only static analysis (clone + `git ls-files` + full source read + grep mapping). No files were modified. Verified clean working tree after writing this report: only `ARCHITECTURE.md` untracked.*
