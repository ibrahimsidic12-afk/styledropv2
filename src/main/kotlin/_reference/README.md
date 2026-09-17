# `src/main/kotlin/_reference/` — READ BEFORE USE

**These Kotlin files are NOT part of the Android build.**

The `:app` module compiles only `app/src/main/java/...`. This repo-root `src/main/kotlin/_reference/`
directory is outside every Gradle source set, so nothing here is compiled, ktlint-ed, or shipped in the
APK. They are staged here deliberately so this branch can be merged without breaking the existing build.

## How to use

1. Read the agent doc that produced the file (mapping table below) plus `ENHANCEMENTS.md` §3 (known conflicts).
2. Copy the file into the matching package under `app/src/main/java/com/example/...`.
3. Adjust the `package` declaration and imports to the project's real namespace (`com.example...` placeholders are used).
4. For data-layer files, merge into the **single** Room migration chain described in `docs/DB_SCHEMA.md` — do not add a parallel database version.

## File → agent → intended target

| File | Producing agent | Intended target in `app/src/main/java/com/example/` |
|---|---|---|
| `Entities.kt` | #13 DB Optimizer | `data/` (merge with Agent 15/16/17 tables) |
| `AppDatabaseV3.kt` | #13 DB Optimizer | `data/` (replaces `AppDatabase.kt`; real migrations, no destructive fallback) |
| `migrations.kt` | #13 DB Optimizer | `data/` |
| `WardrobeDaoV2.kt` | #13 DB Optimizer | `data/` |
| `OkHttpConfigSample.kt` | #12 API Performance | `network/` (port its builder onto Agent 14's secure `NetworkModule`) |
| `WardrobeItemV2.kt` | #4 Wardrobe UI | `models/` (align fields with Agent 13's `Entities.kt` first) |
| `WardrobeUiModels.kt` | #4 Wardrobe UI | `ui/wardrobe/` |
| `GarmentCard.kt` | #4 Wardrobe UI | `ui/wardrobe/` |
| `WardrobeScreenV2.kt` | #4 Wardrobe UI | `ui/` (replaces `WardrobeScreen.kt`) |
| `FilterRailAndEmptyStates.kt` | #4 Wardrobe UI | `ui/wardrobe/` |
| `image_processor.kt` | #15 Image Pipeline | `data/` or `util/` |
| `OnboardingScreens.kt` | #16 Onboarding | `ui/onboarding/` |
| `WearTracker.kt` | #17 Wear Tracking | `data/` (its tables join the Agent 13 migration) |
| `wear_screen.kt` | #17 Wear Tracking | `ui/` |
| `Sharing.kt` | #18 Community | `ui/` or `domain/` |
| `AnalyticsEngine.kt` | #19 Analytics | `ui/analytics/` or `domain/` |
| `AnalyticsCharts.kt` | #19 Analytics | `ui/analytics/` |

## Verification status

These files were syntax-level checked by their producing agents where noted in `docs/` (e.g. Agent 4's
kotlinc pass), but **no file here has been compiled inside the app module** — that requires the Android
SDK and is exactly what Phase 3 of `ENHANCEMENTS.md` is for. Expect import/layout adjustments.
