# StyleDrop v2 — 20-Agent Enhancement Set

> **Branch:** `feat/enhancements-20-agents` · **Base:** `main` @ `a53013b` ("feat(ui): update visual identity to ivory theme")
> **What this is:** the complete output of 20 specialized agents that analyzed and designed enhancements for the StyleDrop wardrobe app (native Android, Kotlin + Jetpack Compose, Room, Retrofit → Gemini).
> **What this is NOT:** nothing here modifies the Android build. Everything is staged as documentation, runnable reference tools, prototypes, and reference Kotlin — see the rollout plan at the bottom before integrating anything into `app/src/main/`.

---

## 1. The 20 agents and their deliverables

| # | Agent | Purpose | Key deliverables (in this branch) |
|---|-------|---------|-----------------------------------|
| 1 | Repo Clone & Architecture Analyst | Full read-only static analysis of the codebase: stack, layering, entity model, feature-status matrix, risks | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| 2 | Tech Stack & Dependency Auditor | Every Gradle dependency vs. latest, CVE register (incl. Okio CVE-2023-3635 via OkHttp 4.10.0), phased upgrade roadmap | [docs/TECH_STACK.md](docs/TECH_STACK.md) |
| 3 | Brand Identity & Visual Designer | "Digital Atelier" brand system: logo spec, color palette with WCAG contrast ledger, typography, tone of voice, UI motifs | [docs/BRAND_GUIDE.md](docs/BRAND_GUIDE.md), [brand/styledrop_logo.png](brand/styledrop_logo.png), [brand/styledrop_hero_mock.png](brand/styledrop_hero_mock.png) |
| 4 | Wardrobe UI Architect | Wardrobe screen redesign: 7 tabs, sort/filter system, garment card, empty states + interactive preview | [docs/WARDROBE_UI.md](docs/WARDROBE_UI.md), [docs/prototypes/wardrobe_preview.html](docs/prototypes/wardrobe_preview.html), 5 Kotlin files in `src/main/kotlin/_reference/` |
| 5 | Outfit Visualizer & Canvas | Drag-and-drop outfit builder on 8 slots, color-clash detection (HSL hue distance), style scoring, share links | [docs/OUTFIT_BUILDER.md](docs/OUTFIT_BUILDER.md), [docs/prototypes/outfit_builder.html](docs/prototypes/outfit_builder.html) (verified: 17/17 headless functional tests) |
| 6 | Theme System + Dark Mode + A11y | Web-surface theme twin of the Compose theme: token matrix, light/dark/high-contrast, runtime switcher, FOUC blocker | [docs/THEME_SYSTEM.md](docs/THEME_SYSTEM.md), [theme/theme.css](theme/theme.css), [theme/theme-switcher.js](theme/theme-switcher.js), [theme/theme-preview.html](theme/theme-preview.html) |
| 7 | Accessibility + i18n Specialist | WCAG 2.1 AA audit with computed contrast fixes, semantics/role repairs, en/es/ja translation samples, RTL guidance, full code diff | [docs/A11Y_I18N.md](docs/A11Y_I18N.md), [i18n/en.json](i18n/en.json), [i18n/es.json](i18n/es.json), [i18n/ja.json](i18n/ja.json), [docs/A11Y_I18N_PATCHES.diff](docs/A11Y_I18N_PATCHES.diff) |
| 8 | AI Style Recommender | Hybrid recommender (content + collaborative + rules) grounded in the real `WardrobeItem` schema, cold-start handling, runnable reference | [docs/STYLIST_AI.md](docs/STYLIST_AI.md), [tools/recommender.py](tools/recommender.py) |
| 9 | Color Harmony AI Engine | Deterministic color-harmony scoring (complementary/analogous/clash detection with circular hue math) + companion suggestions | [docs/COLOR_AI.md](docs/COLOR_AI.md), [tools/color_engine.py](tools/color_engine.py), [tools/test_color_engine.py](tools/test_color_engine.py) (85/85 tests passing) |
| 10 | Fashion Trend Analyzer | Multi-source trend scoring (popularity/velocity/acceleration/freshness/cross-source), seasonality with hemisphere correction, wardrobe-gap finder | [docs/TRENDS.md](docs/TRENDS.md), [tools/trend_analyzer.py](tools/trend_analyzer.py) |
| 11 | Wardrobe Classifier | Vision-based auto-tagging of garment photos (category/type/color/pattern) constrained to the app's exact vocabulary | [docs/CLASSIFIER.md](docs/CLASSIFIER.md), [tools/classifier.py](tools/classifier.py) (6/6 demo garments verified) |
| 12 | API Performance | Network-layer hardening: OkHttpClient with timeouts, retry w/ backoff, rate limiting (10/min), queuing, metrics, benchmarks | [docs/API_PERF.md](docs/API_PERF.md), [src/main/kotlin/_reference/OkHttpConfigSample.kt](src/main/kotlin/_reference/OkHttpConfigSample.kt), [tools/bench_harness.py](tools/bench_harness.py) |
| 13 | Database Optimizer | Room schema v3 design: real migrations (replacing `fallbackToDestructiveMigration`), new entities/DAOs | [docs/DB_SCHEMA.md](docs/DB_SCHEMA.md), `src/main/kotlin/_reference/{migrations,WardrobeDaoV2,Entities,AppDatabaseV3}.kt` |
| 14 | Security Hardening | S1–S12 security fixes (API-key removal → Firebase AI Logic, encrypted storage, network hardening), staged as an applyable patch | [docs/SECURITY.md](docs/SECURITY.md), [patches/security-patches.diff](patches/security-patches.diff), [docs/patches/security_apply_report.md](docs/patches/security_apply_report.md) |
| 15 | Image Pipeline | Garment image ingestion: copy-from-picker, compression, persistence, thumbnailing | [docs/IMAGE_PIPELINE.md](docs/IMAGE_PIPELINE.md), [tools/image_processor.py](tools/image_processor.py), [src/main/kotlin/_reference/image_processor.kt](src/main/kotlin/_reference/image_processor.kt) |
| 16 | Onboarding + Style Quiz | First-run flow: style quiz, personalization hooks, full copy deck | [docs/ONBOARDING.md](docs/ONBOARDING.md), [docs/onboarding_copy.md](docs/onboarding_copy.md), [src/main/kotlin/_reference/OnboardingScreens.kt](src/main/kotlin/_reference/OnboardingScreens.kt) |
| 17 | Wear Tracking | Activating the dead `timesWorn`/`lastWorn`/`isFavoriteItem` fields: wear logging, cost-per-wear, heatmap UI | [docs/WEAR_TRACKING.md](docs/WEAR_TRACKING.md), [src/main/kotlin/_reference/WearTracker.kt](src/main/kotlin/_reference/WearTracker.kt), [src/main/kotlin/_reference/wear_screen.kt](src/main/kotlin/_reference/wear_screen.kt), [docs/mockups/](docs/mockups/) |
| 18 | Community & Sharing | Outfit share cards / deep links, sharing round-trip logic | [docs/COMMUNITY.md](docs/COMMUNITY.md), [tools/sharing.py](tools/sharing.py), [src/main/kotlin/_reference/Sharing.kt](src/main/kotlin/_reference/Sharing.kt) |
| 19 | Analytics & Insights | Wardrobe analytics engine + chart components beyond the current 3 stat tiles | [docs/ANALYTICS.md](docs/ANALYTICS.md), [tools/analytics_engine.py](tools/analytics_engine.py), [tools/mock_data.py](tools/mock_data.py), [src/main/kotlin/_reference/AnalyticsEngine.kt](src/main/kotlin/_reference/AnalyticsEngine.kt), [src/main/kotlin/_reference/AnalyticsCharts.kt](src/main/kotlin/_reference/AnalyticsCharts.kt) |
| 20 | Testing & CI/CD | Test strategy, GitHub Actions pipeline, Dependabot, detekt config, sample-data generator + fixture | [docs/TESTING.md](docs/TESTING.md), [.github/workflows/ci.yml](.github/workflows/ci.yml), [.github/dependabot.yml](.github/dependabot.yml), [config/detekt/detekt.yml](config/detekt/detekt.yml), [tools/generate_sample_wardrobe.py](tools/generate_sample_wardrobe.py), [fixtures/sample_wardrobe.json](fixtures/sample_wardrobe.json) |

---

## 2. How to integrate (target paths inside `app/src/main/`)

Nothing under `docs/`, `tools/`, `theme/`, `i18n/`, `brand/`, `fixtures/`, `patches/`, `config/`, `.github/`, or `src/main/kotlin/_reference/` affects the Android build — the app module only compiles `app/src/main/java/...`. Integration means moving/copying code into the app module yourself, per feature:

| Feature | Files to integrate | Target in `app/src/main/java/com/example/` | Read first |
|---|---|---|---|
| DB v3 + migrations | `_reference/Entities.kt`, `AppDatabaseV3.kt`, `migrations.kt`, `WardrobeDaoV2.kt` | `data/` | docs/DB_SCHEMA.md — merge rules in §4 below |
| Network hardening | `_reference/OkHttpConfigSample.kt` | `network/` (replaces `NetworkModule.kt`) | docs/API_PERF.md + docs/SECURITY.md §S1 |
| Wardrobe UI v2 | `_reference/WardrobeItemV2.kt`, `WardrobeUiModels.kt`, `GarmentCard.kt`, `WardrobeScreenV2.kt`, `FilterRailAndEmptyStates.kt` | `models/`, `ui/` | docs/WARDROBE_UI.md |
| Wear tracking | `_reference/WearTracker.kt`, `wear_screen.kt` | `data/`, `ui/` | docs/WEAR_TRACKING.md (its tables must join the same migration chain as Agent 13's) |
| Onboarding | `_reference/OnboardingScreens.kt` | `ui/onboarding/` | docs/ONBOARDING.md + docs/onboarding_copy.md |
| Analytics | `_reference/AnalyticsEngine.kt`, `AnalyticsCharts.kt` | `ui/analytics/` | docs/ANALYTICS.md |
| Sharing | `_reference/Sharing.kt` | `ui/` or `domain/` | docs/COMMUNITY.md |
| Image pipeline | `_reference/image_processor.kt` | `data/` or `util/` | docs/IMAGE_PIPELINE.md |
| A11y + i18n code fixes | `docs/A11Y_I18N_PATCHES.diff` (touches existing app sources) | apply with `git apply` | docs/A11Y_I18N.md |
| Security hardening | `patches/security-patches.diff` (touches 27 existing app files) | apply on a branch, cherry-pick hunks | docs/SECURITY.md + docs/patches/security_apply_report.md |

The Python tools run standalone (stdlib-only unless noted in their headers) and are the algorithmic reference for their Kotlin ports.

---

## 3. Known conflicts (read before integrating)

1. **`AppDatabase.kt` / entity schema — Agents 13, 14, 15, 16, 17 all add or touch data-layer code.**
   Agent 13's `Entities.kt` + `AppDatabaseV3.kt` + `migrations.kt` is the **canonical schema proposal**. Agents 15 (image metadata), 16 (onboarding prefs), and 17 (wear-log tables) each assume additional entities — fold their tables into Agent 13's single migration chain instead of stacking separate `fallbackToDestructiveMigration()` versions.
2. **`NetworkModule.kt` — Agents 12 and 14 both rewrite it.**
   Agent 14 removes the embedded API key (Firebase AI Logic path); Agent 12 adds timeouts/retry/rate-limit/metrics on the raw Retrofit path. **Resolution:** take Agent 14's secure base, then port Agent 12's `OkHttpClient` builder (timeouts, `ExponentialBackoffRetryInterceptor`, `TokenBucketRateLimiter`, `MetricsEventListener`) on top.
3. **`WardrobeItem` entity — Agent 4 vs Agent 13.**
   Agent 4's `WardrobeItemV2.kt` adds `formality`/`fabric` fields and Dresses/Activewear categories; Agent 13's `Entities.kt` has its own evolution of the same table. Align the field sets **before** writing the migration, or you will ship two competing v3 schemas.
4. **UI files — Agents 7, 14, and 17 touch overlapping Compose screens** (detail screens, theme, home). Apply the security patch first, then the a11y diff, then feature screens, re-running `git apply --check` at each step.
5. **`AiGeneratorViewModel` / prompt pipeline — Agents 8, 9, 10, 12 all propose hooks.** Sequence: Agent 12's transport hardening → Agent 8's candidate ranking feeds the prompt → Agent 9's clash scoring post-validates the response → Agent 10's trends enrich context. Do not wire all four at once.

---

## 4. Rollout recommendation

| Phase | Content | Risk |
|---|---|---|
| **Phase 0 — Docs** | Merge this branch's `docs/`, `brand/`, prototypes, and `ENHANCEMENTS.md` as-is. Zero build impact. | None |
| **Phase 1 — Tools** | Adopt `tools/` + `fixtures/` (Python references, CI-adjacent). Still zero Android-build impact; validates algorithms against real data. | None |
| **Phase 2 — Security** | Check out `patches/security-hardening`, review `docs/patches/security_apply_report.md`, cherry-pick the hunks that apply cleanly (S1 key removal first). | Medium — touches 27 files |
| **Phase 3 — Kotlin integration** | Move `_reference/` files into `app/src/main/java/` feature-by-feature with the single merged migration from §3.1, in this order: DB v3 → network hardening → wear tracking → wardrobe UI v2 → analytics → onboarding → sharing → image pipeline. | Highest — do one feature per PR |

---

## 5. Verification snapshot (from the agents' own runs)

- Agent 9 color engine: **85/85 tests passed** (`tools/test_color_engine.py`).
- Agent 5 outfit-builder prototype: **17/17 headless functional tests**, zero console errors.
- Agent 11 classifier: **6/6 synthetic demo garments** classified within the app's vocabulary.
- Agent 6 theme system: **40 contrast pairs** checked across 4 contexts; caption/focus-ring fixes measured before/after.
- Agent 8 recommender + Agent 10 trend analyzer: executed end-to-end on synthetic/mock data this session (their stdout tables are in their docs).
- The Kotlin files in `_reference/` are **not compiled by any Gradle build** — see [src/main/kotlin/_reference/README.md](src/main/kotlin/_reference/README.md).
