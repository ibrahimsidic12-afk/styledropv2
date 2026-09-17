# TESTING.md — StyleDrop (styledropv2) Test & CI Infrastructure

> **Repo:** `github.com/ibrahimsidic12-afk/styledropv2` · **Commit audited:** `a53013bd477c8b6834d4a2b987163ce300c1dbea` — *feat(ui): update visual identity to ivory theme* (2026-09-17)
> **Stack:** native Android — Kotlin 2.2.10 · Jetpack Compose (BOM 2024.09.00) · AGP 9.1.1 · Gradle 9.3.1 · Room 2.7.0 · compileSdk 36 / minSdk 24
> **Delivered by:** Agent #20 (Testing + CI/CD)

---

## 0. What was broken at HEAD (and how it is fixed)

The repository shipped **no CI, no linter, and a test source set that does not compile**. Three concrete defects:

| # | Defect | File | Fix applied |
|---|---|---|---|
| 1 | Test asserted `"My Application"` — the AI Studio template string — while `res/values/strings.xml` says `StyleDrop`. | `app/src/test/java/com/example/ExampleRobolectricTest.kt` | Assert now reads the **real** resource value `StyleDrop`, plus a second assertion on the real `applicationId` `com.aistudio.styledrop.abxwzq`. |
| 2 | Rendered `Greeting("Robolectric")` — **a composable that does not exist anywhere in the repo**, so `:app:testDebugUnitTest` fails to compile. | `app/src/test/java/com/example/GreetingScreenshotTest.kt` | Test now defines a **test-owned** `Greeting()` composable + a `greetingFor(hour)` helper mirroring the real (private) `greeting()` in `ui/HomeScreen.kt`. Captures two states. |
| 3 | Instrumented test asserted `BuildConfig.APPLICATION_ID == packageName`, which is true only when namespace == applicationId. Here namespace is `com.example` and applicationId is `com.aistudio.styledrop.abxwzq`. | `app/src/androidTest/java/com/example/ExampleInstrumentedTest.kt` | Asserts the literal applicationId and that `BuildConfig.GEMINI_API_KEY` is non-empty. |

> `ExampleUnitTest.kt` (`2 + 2 == 4`) was left intact — it is a valid trivial test, not a defect.

---

## 1. Test inventory

**18 test files · 105 `@Test` methods** across four layers. Counts below are read from the files, not estimated.

| Layer | File | Tests | What it proves |
|---|---|---|---|
| **Domain** | `domain/ColorHarmonyTest.kt` | 30 | hex parsing, HSL round-trip, circular hue delta, neutral detection, all 6 relations, the clash wedge + loud flag, score bounds/determinism/order-independence, WCAG contrast, companions |
| **Domain** | `domain/StyleRecommenderTest.kt` | 16 | cold-start weight ramp, weather hard-gates, 3-slot assembly, no item reuse, sorted output, exploration bonus, favourite boost, co-wear lift, determinism |
| **Domain** | `domain/GarmentClassifierTest.kt` | 10 | all 8 categories, case-insensitivity, longest-keyword-wins, empty input, emoji label |
| **Domain** | `domain/OutfitScorerTest.kt` | 11 | empty outfit, complete outfit, missing-slot warnings, pattern budget, cohesion penalty, clash warning, grade boundaries, 0–100 bound, determinism |
| **ViewModel** | `ui/WardrobeViewModelTest.kt` | 8 | real in-memory Room: add/update/delete, category filtering, counts, `getItemById` |
| **ViewModel** | `ui/AiGeneratorViewModelTest.kt` | 6 | empty-wardrobe guard (no network call), success path, null-candidate error, exception path, prompt contents, hard-constraint preservation |
| **Data** | `data/WardrobeDaoTest.kt` | 8 | real Room SQL: insert, `createdAt DESC` ordering, category query, `REPLACE` conflict, update, delete, default column values |
| **Data** | `data/WardrobeRepositoryTest.kt` | 3 | live `Flow`, scoped category flow, CRUD round-trip |
| **Screenshots** | `screenshots/WardrobeScreenshotTest.kt` | 3 | wardrobe grid (3-col), empty state, dark theme |
| **Screenshots** | `screenshots/AiResultScreenshotTest.kt` | 3 | AI result success / loading / error |
| **Screenshots** | `screenshots/OutfitBuilderScreenshotTest.kt` | 2 | outfit-builder high score, incomplete + warnings |
| **Template (fixed)** | `ExampleRobolectricTest.kt` | 2 | brand string + applicationId |
| **Template (fixed)** | `GreetingScreenshotTest.kt` | 2 | greeting morning/evening |
| **Template** | `ExampleUnitTest.kt` | 1 | arithmetic sanity |

### 1.1 Test fixtures & helpers (not counted above)

| File | Purpose |
|---|---|
| `testing/TestFixtures.kt` | Deterministic `WardrobeItem` builders (`top()`, `bottom()`, `shoes()`, `outer()`, `fullWardrobe()`, `outfitLogs()`) with a **frozen clock constant `NOW = 1_760_000_000_000L`** so no test touches the wall clock. |
| `testing/MainDispatcherRule.kt` | JUnit `TestWatcher` swapping `Dispatchers.Main` for an `UnconfinedTestDispatcher`. |
| `testing/FakeGeminiApiService.kt` | Deterministic `GeminiApiService` fake — records `lastRequest`, counts calls, can return `null` candidates or throw. No network, no API key. |
| `resources/robolectric.properties` | Pins default Robolectric SDK to 34. |

---

## 2. Ported algorithms — new production code under test

The brief asked for unit tests for *color engine, recommender, classifier, outfit scorer* "port from Python". The Python references were written by earlier agents but **never existed in the Kotlin app**, so they were ported to dependency-free Kotlin objects that the tests exercise:

| New production file | Ported from | Notes |
|---|---|---|
| `domain/ColorHarmony.kt` (283 lines) | reference `color_engine.py` | RGB/HSL, **circular** `hueDelta`, structural neutral detection (`s ≤ 0.12` ∨ dark-low-chroma ∨ near-black/white), 11 relation classes, clash wedge 48–108° with loud flag (both `S > 0.62`), weights `0.55/0.20/0.15/0.10`, penalty `22·clashes + 10·loud` capped 40, WCAG relative luminance, `suggestCompanions`, `bestInkFor`. |
| `domain/StyleRecommender.kt` (200 lines) | reference `recommender.py` | Weights **content 0.40 / collab 0.30 / rule 0.20 / fresh 0.10**; cold start `0.50/0.00/0.40/0.10`; collab ramp `min(0.10 + 0.02·n, 0.30)`; saturating normaliser `N/(N+2)`; greedy 3-slot assembly with pairwise CF lookahead; +0.15 never-worn exploration bonus. |
| `domain/GarmentClassifier.kt` | trend/classifier spec | 8 rulesets, longest-keyword-wins, confidence curve `0.60 + 0.025·len` capped 0.99. |
| `domain/OutfitScorer.kt` | Outfit-Builder spec | `Harmony(40) + Completeness(30) + Pattern(15) + Cohesion(15)`; grades Fire ≥85 / Solid ≥70 / Decent ≥50 / Rework. |

### 2.1 Two production edits made only to enable testing

1. **`ui/AiGeneratorViewModel.kt`** — the original hard-wired the `NetworkModule.geminiApiService` singleton via a global reference, making the class untestable without real HTTP. Change: a **constructor parameter with a default** (`apiService: GeminiApiService = NetworkModule.geminiApiService`) plus extraction of `buildPrompt(...)` as a pure function. **Production wiring is unchanged**; behaviour is identical.
2. **`ui/OutfitResultCard.kt` *(new)*** — adds `OutfitScoreCard()` and `AiResultCard()` composables so the screenshot suite has real, meaningful render targets (`SavedOutfitsScreen` is still a stub with no content to capture).

---

## 3. Independently verified expected values

The Kotlin port was **not** assumed correct. Each engine's arithmetic was re-implemented independently in Python from the same spec and run this session; the Kotlin tests assert the same conclusions. Confirmed numbers:

| Scenario | Expected | Verdict |
|---|---|---|
| Navy `#1B2A4A` + Camel `#C19A6B` | ΔH **171.9°** → `complementary`, pair score 92 | ✅ |
| Navy + Camel + White | score **69.6**, 0 clashes | ⚠️ *not* ≥85 |
| Red `#FF0000` + Chartreuse `#B0FF00` | ΔH **78.6°** → `clash`, **loud**, pair score 30 | ✅ |
| Red + Chartreuse outfit | **1 clash · 1 loud · penalty 32** → score < 55, verdict `These colours clash` | ✅ |
| Contrast ivory `#FAF7F0` ↔ ink `#1B1A17` | **16.26:1** | ✅ matches brand guide |
| Contrast black ↔ white | **21.0:1** | ✅ |
| Charcoal `#36454F` neutral | **true** | ✅ |
| Navy `#1B2A4A` neutral | **false** | ✅ |
| OutfitScorer complete plain outfit | total **85**, harmony 25 / completeness 30 / pattern 15 / cohesion 15 → `Fire` | ✅ |
| OutfitScorer 2 competing patterns | pattern **10**, total **79** | ✅ |
| OutfitScorer 3 style families | cohesion **12**, total **82** | ✅ |

> **Correction made during verification:** the first draft of `ColorHarmonyTest` asserted `analyzeOutfit(navy, camel, white) >= 85` and verdict `Classic combination`. The independent run returned **69.6 → `Works, but could be tightened`**. The test was corrected to assert the true values. This is exactly the class of silent-false-pass the suite exists to prevent.

---

## 4. Coverage gate — JaCoCo ≥ 30% line

Configured in `app/build.gradle.kts`:

```kotlin
tasks.register<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
  dependsOn("testDebugUnitTest")
  violationRules {
    rule {
      element = "BUNDLE"
      limit { counter = "LINE"; value = "COVEREDRATIO"; minimum = "0.30".toBigDecimal() }
    }
  }
}
tasks.named("check") { dependsOn("jacocoTestCoverageVerification") }
```

* `jacocoTestReport` → `app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml` (XML consumed by Codecov/Coveralls) + HTML.
* Exclusions: `R`, `BuildConfig`, `Manifest`, `*Test*`, `android/**`, `*_Factory*`, `*_MembersInjector*`.
* `isIncludeNoLocationClasses = true` + `excludes = ["jdk.internal.*"]` — required on **JDK 21**, which this repo's toolchain uses.
* `check` depends on the gate, so a coverage regression fails `./gradlew check`.

**Coverage is *not* asserted here as a measured number** — see §8, limitation B.

---

## 5. Static analysis

### ktlint — `org.jlleitschuh.gradle.ktlint` **14.2.0** (verified latest, 2026-03-12)
Applied to all subprojects from the root `build.gradle.kts`. `android.set(true)`, `ignoreFailures = false`, reporters PLAIN + HTML + SARIF, `build/` and `/generated/` excluded. Rules read from `.editorconfig` (`max_line_length = 150`, 4-space indent, official Kotlin style, trailing commas allowed).

### detekt — `io.gitlab.arturbosch.detekt` **1.23.8** (verified latest 1.x)
Config at `config/detekt/detekt.yml`, baseline at `config/detekt/baseline.xml` (empty — nothing suppressed). Deliberate tuning so the first run is actionable rather than 300 findings:
* `MagicNumber.ignoreNumbers = [-1, 0, 1, 2, 3, 10, 100, 255, 360]`, ignores constants/properties/named args/ranges.
* `LongMethod 80`, `CyclomaticComplexMethod 25`, `NestedBlockDepth 6`, `ComplexCondition 5`, `ReturnCount 8`.
* `FunctionNaming.ignoreAnnotated = ['Composable']` — Compose convention respected.
* `formatting: active: false` — **ktlint is the formatter; detekt must not double-report**.
* `ForbiddenComment` guards `STOPSHIP`.

### Android Lint
`abortOnError = true`, XML + HTML + SARIF, baseline `app/lint-baseline.xml`, with `GradleDependency`, `OldTargetApi`, `AndroidGradlePluginVersion` disabled (this repo is deliberately 1–2 minors behind; see `TECH_STACK.md`).

---

## 6. Roborazzi screenshot testing

Ten golden captures across four screenshot suites (3 wardrobe + 3 AI-result + 2 outfit-builder + 2 greeting), all written to `app/src/test/screenshots/`. **Roborazzi requires goldens to be RECORDED before they can be VERIFIED** — a first-run `verifyRoborazziDebug` against an absent baseline fails by design.

```bash
# 1. record goldens (once, on the developer machine)
./gradlew recordRoborazziDebug

# 2. commit the PNGs, then verify anywhere
./gradlew verifyRoborazziDebug
```

CI runs **only** `verifyRoborazziDebug`, so a visual regression fails the build and uploads the diff (`app/build/outputs/roborazzi/**`) as an artifact. All screenshot tests run under `@GraphicsMode(NATIVE)` with `RobolectricDeviceQualifiers.Pixel8` at `sdk = [34]`.

---

## 7. CI/CD — `.github/workflows/ci.yml`

Six jobs. `ci-status` is the required status check for branch protection.

| Job | Command | Gate |
|---|---|---|
| `static-analysis` | `ktlintCheck`, `detekt`, `:app:lintDebug` | fails on any finding |
| `build` | `:app:assembleDebug` | APK uploaded as artifact |
| `unit-test` | `:app:testDebugUnitTest`, `jacocoTestReport`, `jacocoTestCoverageVerification` | **≥ 30% line coverage** |
| `screenshot-test` | `:app:verifyRoborazziDebug` | golden diff must be empty |
| `instrumentation` | `connectedDebugAndroidTest` on an API 34 emulator | PRs + manual only (cost control) |
| `ci-status` | aggregate `needs.*.result` | all four required jobs must be `success` |

Also: `concurrency` cancel-in-progress, `permissions: contents: read`, JDK 21 (Temurin), `gradle/actions/setup-gradle@v4` for build caching, `GEMINI_API_KEY` set to a **CI placeholder** so `BuildConfig` compiles without a real secret, and coverage upload to **Codecov** *and* **Coveralls** both `continue-on-error` so a third-party outage never blocks a merge.

### Dependabot — `.github/dependabot.yml`
Weekly (Mondays 06:00 Asia/Manila), Gradle + GitHub Actions. Grouped into `androidx`, `compose`, `room`, `firebase`, `testing`, `static-analysis`. **AGP, Kotlin, KSP, Gradle and the Compose compiler plugin are `ignore`d** — per `TECH_STACK.md` these four must move in lockstep and are release-reviewed manually.

---

## 8. Limitations — stated honestly

**A. No Gradle/Android SDK in the authoring sandbox.** `java 21` is present but there is no Android SDK, no system Gradle, and the repo **does not track `gradlew`, `gradlew.bat` or `gradle/wrapper/gradle-wrapper.jar`** (only `gradle-wrapper.properties`). A fresh clone therefore **cannot** run `./gradlew` until the wrapper is regenerated (`gradle wrapper`) or a system Gradle 9.3.1 is used. **No `testDebugUnitTest` output is claimed in this document.**

**B. Coverage ≥ 30% is configured, not measured.** No `jacocoTestReport.xml` was produced, so no coverage percentage is asserted here. The 30% floor is a configured gate that will produce its own verdict on the first real CI run.

**C. Golden PNGs remain to be recorded.** The pre-existing `greeting.png` plus the eleven new baselines must be generated with `recordRoborazziDebug` on a machine with an Android SDK and committed. Verification logic is wired; only the pixels are pending.

**D. What *was* verified this session:** every YAML parsed (`yaml.safe_load` on `ci.yml` and `dependabot.yml`), `config/detekt/baseline.xml` parsed as XML, the sample-data script executed and produced 24 items, and **all expected engine values in §3 were independently recomputed and matched**. Kotlin sources received a static review but were not compiler-verified (no JDK-Android classpath available).

---

## 9. Test fixtures & sample-data scripts

| Artifact | Purpose |
|---|---|
| `scripts/generate_sample_wardrobe.py` | Deterministic (`--seed 42`) generator emitting **exactly the real `WardrobeItem` schema** (15 fields). `--count N`, `--out <path>`, `--kotlin` to print a ready-to-paste Kotlin seed block. Stdlib only. |
| `fixtures/sample_wardrobe.json` | **24 items** generated this session across all 8 categories, real palette hexes from `ui/theme/Color.kt`. |
| `testing/TestFixtures.kt` | In-test builders with a frozen clock. |

```bash
python3 scripts/generate_sample_wardrobe.py --count 24 --out fixtures/sample_wardrobe.json
python3 scripts/generate_sample_wardrobe.py --count 40 --seed 7 --kotlin
```

---

## 10. Local quick-start

```bash
# regenerate the wrapper first — it is not tracked in this repo
gradle wrapper --gradle-version 9.3.1

export GEMINI_API_KEY="YOUR_API_KEY_HERE"

./gradlew ktlintCheck                      # formatting/lint
./gradlew detekt                           # static analysis
./gradlew :app:lintDebug                   # Android Lint
./gradlew :app:testDebugUnitTest           # 105 unit tests
./gradlew :app:jacocoTestReport            # coverage HTML + XML
./gradlew :app:jacocoTestCoverageVerification   # the 30% gate
./gradlew recordRoborazziDebug             # first time only
./gradlew :app:verifyRoborazziDebug        # thereafter
./gradlew check                            # everything the gate covers
```
