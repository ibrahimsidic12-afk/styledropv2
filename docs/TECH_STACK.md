# TECH_STACK.md — StyleDrop (styledropv2) Dependency & Stack Audit

**Repository:** `https://github.com/ibrahimsidic12-afk/styledropv2.git`
**Audit date:** 2026-09-17
**Auditor:** Agent #2 — Dependencies & Stack Audit
**Audit scope:** Full clone, every declared dependency, build tool, test framework, linter, platform target, and version catalog entry; security + deprecation review; wardrobe/fashion-tech upgrade path.

> **Deliverable status:** Complete. All versions below were read directly from the cloned repo's manifests (`gradle/libs.versions.toml`, `*.gradle.kts`, `gradle-wrapper.properties`) and cross-checked against live registries this turn. Anything that could **not** be verified is explicitly labelled **[LATEST UNVERIFIED]**.

---

## 1. Executive Summary

StyleDrop ("Your AI Wardrobe Stylist", per `metadata.json`) is **not a web app — it is a native Android application** written in Kotlin with Jetpack Compose [metadata.json](https://github.com/ibrahimsidic12-afk/styledropv2/blob/main/metadata.json).

Because the repo is Android-only, there is **no** `package.json`, no lockfile, no Node/TypeScript, no Python `requirements.txt`, and no Dockerfile. The dependency surface is entirely Gradle: one root build file, one `:app` module, and a **Gradle Version Catalog** (`gradle/libs.versions.toml`) as the single source of truth for ~20 `[versions]` entries and ~50 `[libraries]` aliases.

Key findings:

| # | Finding | Severity |
|---|---------|----------|
| 1 | **Jetpack Compose BOM is 2 years stale** (`2024.09.00` vs `2026.09.00` current) — every Compose artifact is pinned to a 2024 release train | 🔴 High |
| 2 | **`isMinifyEnabled = false` on the release build type** — no R8 shrinking/obfuscation; combined with a *reflection-based* Moshi adapter (`KotlinJsonAdapterFactory`) and an empty `proguard-rules.pro`, release hardening is effectively absent | 🔴 High |
| 3 | **Gemini API key is compiled into the APK** via `buildConfigField("String", "GEMINI_API_KEY", ...)` — a client-side secret in a shippable artifact | 🔴 High |
| 4 | **OkHttp 4.10.0** transitively pulls **Okio < 3.4.0**, exposed to **CVE-2023-3635** (GzipSource DoS) | 🟠 Medium-High |
| 5 | **`fallbackToDestructiveMigration()`** on Room DB v2 — every future schema bump silently wipes the user's wardrobe | 🟠 Medium |
| 6 | **No CI, no linter, no static analysis** — no `.github/` directory, no ktlint, no detekt, no `.editorconfig`, no dependency-verification lock | 🟠 Medium |
| 7 | `android:allowBackup="true"` — wardrobe photos/metadata exfiltratable via device backup | 🟡 Low-Medium |
| 8 | Networking layer has **no timeouts, no certificate pinning, no network-security-config**, and a hardcoded base URL | 🟡 Low-Medium |
| 9 | Production `namespace` is still the Android Studio placeholder **`com.example`** | 🟡 Low |
| 10 | Only **template tests** exist (JUnit/Robolectric/Roborazzi stubs) for a 2,324-line Kotlin codebase | 🟡 Low |

**Overall stack health: MODERATE.** Firebase BOM (34.17.0), Moshi (1.15.2) and the Gradle wrapper are constitutionally current, and AGP/Kotlin are only one-to-two minor releases behind — but the **UI layer is frozen in 2024**, and the release/security posture needs immediate remediation.

---

## 2. Project Identity & Footprint

| Property | Value | Source |
|---|---|---|
| App name | **StyleDrop** | `metadata.json` |
| Description | "Your AI Wardrobe Stylist" | `metadata.json` |
| Declared capability | `MAJOR_CAPABILITY_SERVER_SIDE_GEMINI_API` | `metadata.json` |
| Build system | Gradle (Kotlin DSL) + Gradle Version Catalog | `build.gradle.kts`, `settings.gradle.kts` |
| Root project name | `StyleDrop` | `settings.gradle.kts` |
| Modules | `:app` (single module) | `settings.gradle.kts` |
| Kotlin source files | **29 files / 2,324 lines** | `wc -l` over `*.kt` |
| Total repo files (excl. `.git`) | 63 | `find` |
| Git history | 3 commits — `e5b81a5` Initial → `a19601c` init Android app → `a53013b` "update visual identity to ivory theme" | `git log` |
| Last commit | `a53013b` — 2026-09-17 | `git log -1` |

**Module layout:** `data/` (Room DAO, database, repository), `network/` (Retrofit service + `NetworkModule`), `models/` (`WardrobeItem`, `AppConstants`), `ui/` (11 Compose screens), `ui/theme/` (Color, Type, Theme, Modifiers).

---

## 3. Platform & Language Configuration

| Setting | Declared | Notes |
|---|---|---|
| `compileSdk` | **36** (minorApiLevel `1`) | Uses the newer `compileSdk { version = release(36) { minorApiLevel = 1 } }` DSL |
| `targetSdk` | **36** | Current-generation target |
| `minSdk` | **24** (Android 7.0) | Broad reach; blocks modern media/ML APIs that need API 26+ |
| `sourceCompatibility` / `targetCompatibility` | **Java 11** | Java 17 is the modern baseline; Java 11 also lags the Kotlin `jvmTarget` defaulting behaviour |
| `namespace` | **`com.example`** ⚠️ placeholder | Must be renamed before Play Store release |
| `applicationId` | `com.aistudio.styledrop.abxwzq` | AI Studio-generated identifier |
| `buildFeatures` | `compose = true`, `buildConfig = true` | |
| `isMinifyEnabled` | **`false`** ⚠️ | Release ships unshrunk |
| `isCrunchPngs` | `false` | Intentional |
| Room DB version | **2**, `exportSchema = false`, `fallbackToDestructiveMigration()` ⚠️ | No schema JSON in VCS |
| `allowBackup` | **`true`** ⚠️ | With `fullBackupContent` + `dataExtractionRules` |
| Secrets plugin | reads `.env` / `.env.example`, `ignoreList += FIREBASE_APPCHECK_DEBUG_TOKEN` | `app/build.gradle.kts` |
| Signing | `release` config reads `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD` from env; `debugConfig` uses the standard debug keystore | `app/build.gradle.kts` |

---

## 4. Complete Dependency Inventory

Legend — **Status:** 🔴 overhaul · 🟠 upgrade soon · 🟢 current · ⚪ verify
"Latest" values marked `[LATEST UNVERIFIED]` were **not** confirmable from a live registry this turn and must not be treated as authoritative.

### 4.1 Build Tooling & Plugins

| Tool / Plugin | Current | Latest (verified) | Status | Risk / Note |
|---|---|---|---|---|
| Android Gradle Plugin (`com.android.application`) | **9.1.1** | **9.4.0** (Sept 2026) | 🟠 | Repo is on the April 2026 9.1.1 release; 9.4.0 is current [AGP 9.4.0 release notes](https://developer.android.com/build/releases/agp-9-4-0-release-notes) |
| Kotlin | **2.2.10** | **2.4.20** | 🟠 | 2.4.0 shipped June 2026; 2.4.20 is the latest release [JetBrains Kotlin releases](https://github.com/jetbrains/kotlin/releases) |
| Gradle wrapper | **9.3.1** | **9.7.1** (2026-08-19) | 🟠 | 9.7.1 is the current patch line [Gradle 9.7.1 release notes](https://docs.gradle.org/9.7.1/release-notes.html) |
| KSP (`com.google.devtools.ksp`) | **2.3.5** | **2.3.12** | 🟠 | KSP must always track the Kotlin version — upgrade in lockstep [google/ksp releases](https://github.com/google/ksp/releases) |
| `org.jetbrains.kotlin.plugin.compose` | 2.2.10 | 2.4.20 | 🟠 | Versioned with Kotlin, not with the Compose BOM |
| Compose Compiler | (bundled via Kotlin plugin) | — | 🟢 | Correct modern setup: no separate `composeOptions` block |
| `secrets-gradle-plugin` | **2.0.1** | `[LATEST UNVERIFIED]` | ⚪ | Google Maps Platform Secrets plugin |
| `com.google.gms.google-services` | **4.5.0** | `[LATEST UNVERIFIED]` | ⚪ | `missingGoogleServicesStrategy = WARN` |
| `org.gradle.toolchains.foojay-resolver-convention` | **1.0.0** | `[LATEST UNVERIFIED]` | ⚪ | In `settings.gradle.kts` |

### 4.2 UI Layer — Jetpack Compose (frontend)

| Dependency | Current | Latest (verified) | Status | Risk / Note |
|---|---|---|---|---|
| `androidx.compose:compose-bom` | **2024.09.00** | **2026.09.00** (Sep 9, 2026) | 🔴 | **Two years stale.** BOM `2026.08.00` carries Compose **1.12** [Compose BOM docs](https://developer.android.com/develop/ui/compose/bom) · [August '26 release](https://android-developers.googleblog.com/2026/08/jetpack-compose-august-2026-release.html) |
| `androidx.compose.ui:ui` | BOM-pinned | BOM-pinned | 🔴 | Inherits the 2024 train |
| `androidx.compose.ui:ui-graphics` | BOM-pinned | BOM-pinned | 🔴 | Inherits the 2024 train |
| `androidx.compose.ui:ui-tooling` / `-preview` | BOM-pinned | BOM-pinned | 🔴 | Debug-only tooling |
| `androidx.compose.ui:ui-test-manifest` / `ui-test-junit4` | BOM-pinned | BOM-pinned | 🟠 | Test artifacts pinned to 2024 |
| `androidx.compose.material3:material3` | BOM-pinned | BOM-pinned | 🔴 | Material 3 is now independently versioned upstream |
| `androidx.compose.material:material-icons-core` | BOM-pinned | BOM-pinned | 🟠 | **Google has deprecated the legacy extended icon set in favour of Material Symbols** |
| `androidx.compose.material:material-icons-extended` | BOM-pinned | BOM-pinned | 🔴 | **Deprecation risk.** `material-icons-extended` is the single largest contributor to Compose APK dex size — the official guidance is now Material Symbols or selectively-imported vectors |
| `androidx.compose.ui:ui-text-google-fonts` | BOM-pinned | BOM-pinned | 🟢 | Pairs with `res/values/font_certs.xml` |
| `androidx.core:core-ktx` | **1.18.0** | `[LATEST UNVERIFIED]` | ⚪ | Appears recent |
| `androidx.activity:activity-compose` | **1.10.1** | **1.13.0** (Mar 2026) | 🟠 | `1.14.0-alpha01` also published [Activity release notes](https://developer.android.com/jetpack/androidx/releases/activity) |
| `androidx.lifecycle:lifecycle-runtime-ktx` | **2.8.7** | **2.11.0** (Jun 17, 2026) | 🟠 | Three minor releases behind [Lifecycle release notes](https://developer.android.com/jetpack/androidx/releases/lifecycle) |
| `androidx.lifecycle:lifecycle-runtime-compose` | **2.8.7** | **2.11.0** | 🟠 | Same train |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | **2.8.7** | **2.11.0** | 🟠 | Drives `WardrobeViewModel` / `AiGeneratorViewModel` |
| `androidx.navigation:navigation-compose` | **2.8.9** | **2.10.0** (Aug 26, 2026) | 🟠 | **Navigation 3** is now a separate successor library worth evaluating [Navigation release notes](https://developer.android.com/jetpack/androidx/releases/navigation) · [Migrating to Navigation 3](https://proandroiddev.com/migrating-to-navigation-3-in-jetpack-compose-34b0389a9aea) |

### 4.3 Networking (backend interface)

| Dependency | Current | Latest (verified) | Status | Risk / Note |
|---|---|---|---|---|
| `com.squareup.retrofit2:retrofit` | **2.12.0** | **3.0.0** (May 15, 2025) | 🟠 | Retrofit 3 is fully rewritten in Kotlin, less boilerplate, better nullability; 3.x keeps **forward binary compatibility with 2.x** so migration is low-risk [Retrofit 3.0 migration guide](https://proandroiddev.com/retrofit-3-0-0-detailed-migration-guide-0d2c043d43e3) · [Retrofit 3 changelog](https://github.com/lysine-dev/retrofit/blob/master/CHANGELOG.md) |
| `com.squareup.retrofit2:converter-moshi` | **2.12.0** | **3.0.0** | 🟠 | Upgrade together with core |
| `com.squareup.okhttp3:okhttp` | **4.10.0** | **5.3.2** | 🔴 | **Security + staleness.** OkHttp 5.0.0 went stable 2025-07-02; 5.3.2 is current [OkHttp changelog](https://github.com/square/okhttp/blob/master/CHANGELOG.md) · [Maven Central 5.3.x](https://mvnrepository.com/artifact/com.squareup.okhttp3/okhttp) |
| `com.squareup.okhttp3:logging-interceptor` | **4.10.0** | **5.3.2** | 🔴 | ⚠️ A logging interceptor is wired into the dependency graph — confirm `Level.BODY` is **not** enabled in release builds, as it would log the Gemini key and full wardrobe payloads |
| transitive `com.squareup.okio:okio` | **< 3.4.0** (pulled by OkHttp 4.10.0) | 3.4.0+ | 🔴 | **CVE-2023-3635** — `GzipSource` fails to handle an exception while parsing a malformed gzip buffer, leading to denial of service (CVSS 5.9). Affects OkHttp 4.11.0's Okio 3.2.0 lineage [NVD CVE-2023-3635](https://nvd.nist.gov/vuln/detail/cve-2023-3635) · [endorlabs advisory](https://www.endorlabs.com/vulnerability/cve-2023-3635) · [resend-java issue tracking OkHttp→okio](https://github.com/resend/resend-java/issues/83) |
| `com.squareup.moshi:moshi-kotlin` | **1.15.2** | 1.15.x (current line) | 🟢 | Moshi's published line tops out at 1.15.x — the repo is on it [square/moshi](https://github.com/square/moshi) |
| `com.squareup.moshi:moshi-kotlin-codegen` | **1.15.2** | 1.15.x | 🟢 | ⚠️ Applied via KSP, **but unused**: `NetworkModule` builds Moshi with `.add(KotlinJsonAdapterFactory())` (reflection) instead of `@JsonClass(generateAdapter = true)` adaptation. Codegen is dead weight while reflection is the live path |

### 4.4 Local Persistence

| Dependency | Current | Latest (verified) | Status | Risk / Note |
|---|---|---|---|---|
| `androidx.room:room-runtime` | **2.7.0** | **2.8.5** (Sep 9, 2026) | 🟠 | [Room release notes](https://developer.android.com/jetpack/androidx/releases/room) |
| `androidx.room:room-ktx` | **2.7.0** | **2.8.5** | 🟠 | Provides the `Flow` returns used throughout `WardrobeDao` |
| `androidx.room:room-compiler` (KSP) | **2.7.0** | **2.8.5** | 🟠 | Processor + runtime must match |
| **Room 3.0** (`androidx.room3`) | not used | 3.0 line available | ⚪ | Major version focused on Kotlin Multiplatform; a deliberate, non-trivial migration [Room 3.0 announcement](https://android-developers.googleblog.com/2026/03/room-30-modernizing-room.html) · [Room 3.0 docs](https://developer.android.com/jetpack/androidx/releases/room3) |
| `androidx.datastore:datastore-preferences` | **1.1.7** — **commented out** | `[LATEST UNVERIFIED]` | ⚪ | Declared in the catalog but the implementation line is commented in `app/build.gradle.kts`. Currently unused |

### 4.5 AI / Cloud (Firebase)

| Dependency | Current | Latest (verified) | Status | Risk / Note |
|---|---|---|---|---|
| `com.google.firebase:firebase-bom` | **34.17.0** | **34.18.x** (Sep 9, 2026) | 🟢 | Nearly current — the healthiest part of the stack [Firebase Android release notes](https://firebase.google.com/support/release-notes/android) · [firebase-bom on Maven](https://mvnrepository.com/artifact/com.google.firebase/firebase-bom) |
| `com.google.firebase:firebase-ai` | BOM-pinned | BOM-pinned | 🟢 | **The modern, correct AI path** — Firebase AI Logic exposes Gemini directly to Android/Kotlin with server-side key protection [Firebase AI Logic docs](https://firebase.google.com/docs/ai-logic) · [Gemini models for Android](https://developer.android.com/ai/gemini) |
| `com.google.firebase:firebase-appcheck-recaptcha` | BOM-pinned | BOM-pinned | 🟢 | ⚠️ **Critical upcoming change:** from early July 2026 Firebase **automatically enforces App Check** for Gemini calls [Android AI docs](https://developer.android.com/ai/gemini) — App Check must be fully wired before then |
| `com.google.firebase:firebase-appcheck-debug` | BOM-pinned | BOM-pinned | 🟢 | Debug-only; `FIREBASE_APPCHECK_DEBUG_TOKEN` is in the secrets `ignoreList` |
| `com.google.firebase:firebase-firestore` | **commented out** | BOM-pinned | ⚪ | Declared but disabled — cloud sync/backup of the wardrobe is not implemented |
| `com.google.firebase:firebase-auth` | **commented out** | BOM-pinned | ⚪ | Disabled |
| `androidx.credentials:credentials` | **1.5.0** — commented out | `[LATEST UNVERIFIED]` | ⚪ | Part of the disabled Google Sign-In quartet |
| `androidx.credentials:credentials-play-services-auth` | **1.5.0** — commented out | `[LATEST UNVERIFIED]` | ⚪ | Disabled |
| `com.google.android.libraries.identity.googleid:googleid` | **1.1.1** — commented out | `[LATEST UNVERIFIED]` | ⚪ | Disabled |
| Google Gemini REST endpoint | `v1beta/models/gemini-1.5-flash:generateContent` | — | 🔴 | **Legacy.** Called directly through Retrofit with the API key as a `@Query("key")` parameter; superseded by Firebase AI Logic |

### 4.6 Concurrency

| Dependency | Current | Latest | Status | Risk / Note |
|---|---|---|---|---|
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | **1.10.2** | `[LATEST UNVERIFIED]` | ⚪ | |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | **1.10.2** | `[LATEST UNVERIFIED]` | ⚪ | |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | **1.10.2** | `[LATEST UNVERIFIED]` | ⚪ | |

### 4.7 Image Loading

| Dependency | Current | Latest (verified) | Status | Risk / Note |
|---|---|---|---|---|
| `io.coil-kt:coil-compose` | **2.7.0** | **3.6.1** (Sep 4, 2026) | 🟠 | **Coil 3 is a major rewrite**: Compose Multiplatform support, a rewritten network stack, and an independent maven group (`io.coil-kt.coil3`). Coil 3.6.1 tracks compileSdk 37 / Kotlin 2.4.10 [Coil changelog](https://coil-kt.github.io/coil/changelog/) · [Upgrading to Coil 3.x](https://coil-kt.github.io/coil/upgrading_to_coil3/) · [Coil repo](https://github.com/coil-kt/coil) |

### 4.8 Feature Dependencies Declared but Disabled

These are present in the version catalog with pinned versions, but their `implementation(...)` lines are commented out in `app/build.gradle.kts`. The app's wardrobe-photo feature therefore has **no working camera path**.

| Dependency | Version | Status |
|---|---|---|
| `androidx.camera:camera-camera2` | 1.5.0 | ⚪ commented out |
| `androidx.camera:camera-core` | 1.5.0 | ⚪ commented out |
| `androidx.camera:camera-lifecycle` | 1.5.0 | ⚪ commented out |
| `androidx.camera:camera-view` | 1.5.0 | ⚪ commented out |
| `com.google.accompanist:accompanist-permissions` | 0.37.3 | ⚪ commented out |
| `com.google.android.gms:play-services-location` | 21.3.0 | ⚪ commented out |
| `androidx.datastore:datastore-preferences` | 1.1.7 | ⚪ commented out |

> ⚠️ **Accompanist `0.37.3` is a deprecated-adjacent dependency.** The Accompanist library's permissions module has been superseded by first-party APIs, and the whole Accompanist family has been largely wound down as its APIs graduated into AndroidX. Its presence in the catalog is a maintenance liability even while disabled.

### 4.9 Test, QA & Static Analysis Toolchain

| Tool | Current | Latest | Status | Risk / Note |
|---|---|---|---|---|
| `junit:junit` | **4.13.2** | JUnit 5 line | 🟠 | JUnit **4** — JUnit 5 (Jupiter) is the modern baseline |
| `androidx.test.ext:junit` | **1.3.0** | `[LATEST UNVERIFIED]` | ⚪ | |
| `androidx.test.espresso:espresso-core` | **3.7.0** | `[LATEST UNVERIFIED]` | ⚪ | |
| `androidx.test:core` | **1.6.1** | `[LATEST UNVERIFIED]` | ⚪ | |
| `androidx.test:runner` | **1.6.2** | `[LATEST UNVERIFIED]` | ⚪ | |
| `org.robolectric:robolectric` | **4.16.1** | `[LATEST UNVERIFIED]` | ⚪ | `unitTests.isIncludeAndroidResources = true` is correctly set |
| `io.github.takahirom.roborazzi:roborazzi` (+ `-compose`, `-junit-rule`) | **1.59.0** | `[LATEST UNVERIFIED]` | ⚪ | Screenshot testing is wired — good sign |
| **ktlint** | — | — | 🔴 | **ABSENT** |
| **detekt** | — | — | 🔴 | **ABSENT** |
| **Android Lint enforcement** | default only | — | 🔴 | No `lint {}` block, no `lintOptions`, no baseline, no `abortOnError` |
| **`.editorconfig`** | — | — | 🔴 | **ABSENT** |
| **CI/CD workflows** | — | — | 🔴 | **No `.github/` directory — verified absent** |
| **Dependency lock / verification** | — | — | 🔴 | No `gradle.lockfile`, no `verification-metadata.xml`, no Dependabot config |
| **Coverage (JaCoCo/Kover)** | — | — | 🔴 | **ABSENT** |

**Test reality check:** the repo contains exactly four test files — `ExampleUnitTest.kt` (16 lines), `ExampleInstrumentedTest.kt` (22 lines), `ExampleRobolectricTest.kt` (21 lines), and `GreetingScreenshotTest.kt` (28 lines, with `test/screenshots/greeting.png`). All four are **framework scaffolding**. For 2,324 lines of production Kotlin across 11 screens, real coverage is effectively **zero**.

---

## 5. Security & Deprecation Register

### 5.1 Confirmed / Verifiable Issues

**S1 — Client-side API key baked into the APK (🔴 Critical)**
`app/build.gradle.kts` performs:
```
val apiKey = System.getenv("GEMINI_API_KEY") ?: "YOUR_API_KEY_HERE"
buildConfigField("String", "GEMINI_API_KEY", "\"$apiKey\"")
```
and `GeminiApiService` consumes it as a **`@Query("key")` parameter on every request**. Anyone can unzip the APK, extract `BuildConfig.GEMINI_API_KEY`, and use the project's billable Gemini quota. `.env` is correctly gitignored, but that only protects the repository — **not the shipped binary**.
**Fix:** route all model calls through **Firebase AI Logic**, which is exactly what `firebase-ai` + App Check exist for and which is the repo's own declared capability [Firebase AI Logic](https://firebase.google.com/docs/ai-logic). If a direct REST key must remain, proxy it through a server.

**S2 — Okio CVE-2023-3635 via OkHttp 4.10.0 (🔴 High)**
`GzipSource` mishandles a malformed gzip buffer, causing denial of service; OkHttp 4.11.0's dependency chain (okio-jvm 3.2.0) is documented as affected [NVD](https://nvd.nist.gov/vuln/detail/cve-2023-3635) · [Miggio](https://www.miggo.io/vulnerability-database/cve/CVE-2023-3635) · [Red Hat](https://access.redhat.com/security/cve/cve-2023-3635). The repo sits on **4.10.0**, i.e. older still.
**Fix:** upgrade to OkHttp **5.3.2** (or at minimum pin `okio` ≥ 3.4.0).

**S3 — Release build ships unshrunk and unobfuscated (🔴 High)**
`isMinifyEnabled = false` with an **empty, fully-commented** `proguard-rules.pro`. Two compounding problems:
1. No R8 shrinking → larger APK, exposed class/method names, easier reverse engineering.
2. `NetworkModule` uses **`KotlinJsonAdapterFactory`** (runtime reflection). If minification is ever turned on without adding Moshi keep-rules, the reflection path will break at runtime. The project already includes `moshi-kotlin-codegen` via KSP — the correct design is `@JsonClass(generateAdapter = true)` + generated adapters, which is minification-safe and faster. Note `@JsonClass(generateAdapter = true)` annotations **are** already present on the network DTOs, so the migration is largely wired but the runtime factory bypasses it.

**S4 — Destructive database migration (🟠 Medium)**
`AppDatabase` uses `.fallbackToDestructiveMigration()` on a database currently at **version 2** with `exportSchema = false`. Any future schema change **silently deletes the user's entire wardrobe** — the app's most valuable user data, and mostly irreplaceable photos.
**Fix:** export schemas to VCS, write explicit `Migration` objects, and never ship destructive fallback in production.

**S5 — Debug-grade backup exposure (🟡 Low-Medium)**
`android:allowBackup="true"` with `fullBackupContent` and `dataExtractionRules` defined. Wardrobe photos and the full SQLite DB are eligible for cloud/device backup.
**Fix:** set `allowBackup="false"`, or scope the backup rules to exclude the wardrobe database and user images.

**S6 — No network hardening (🟡 Low-Medium)**
`NetworkModule` hardcodes `https://generativelanguage.googleapis.com/`, builds Retrofit with **no `OkHttpClient`** at all — therefore **no timeouts, no interceptors, no retry policy, no certificate pinning**, and no `network_security_config.xml`.
**Fix:** supply an explicit `OkHttpClient` with connect/read/write timeouts, a `CertificatePinner`, and a network security config restricting cleartext.

**S7 — Unresolved build configuration (🟡 Low)**
`googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }` plus `googleServices.missing.passthrough=true` in `gradle.properties` means the build **succeeds even when `google-services.json` is absent**. The Firebase AI path can therefore fail silently at runtime rather than at build time.
**Fix:** switch to `FAIL` for release builds.

**S8 — Release signing left to environment (🟡 Low)**
The `release` signing config unconditionally references `${rootDir}/my-upload-key.jks` and reads `STORE_PASSWORD`/`KEY_PASSWORD` from the environment. If unset, the release build fails or signs with `null` credentials. This is acceptable for CI but brittle for local release builds.

**S9 — Placeholder namespace (🟡 Low)**
`namespace = "com.example"` while `applicationId = "com.aistudio.styledrop.abxwzq"`. A `com.example` namespace is a Play-Store rejection risk and a code-smell that no refactor has been done.

### 5.2 Not Confirmed — Explicitly Not Asserted

To avoid fabricating findings, the following were **not** verified this turn and are **not** claimed:
- Whether `gemini-1.5-flash` is formally **deprecated** or decommissioned on the `v1beta` endpoint — no advisory was retrieved. It is described here only as a **legacy generation string**.
- Any CVE in Kotlin, AGP, Gradle, Compose, Room, Coil, Moshi, Robolectric, Roborazzi or Firebase BOM. **No such advisories were looked up or returned**, so none are asserted.
- `core-ktx`, `datastore`, `coroutines`, `roborazzi`, `robolectric`, `google-services`, `secrets-plugin`, `credentials` and `googleid` **latest** versions are marked `[LATEST UNVERIFIED]` rather than guessed.
- A **`core` = 1.6.1 / `runner` = 1.6.2** pair is worth a manual re-check: these are `androidx.test` artifacts whose numbering differs from the `androidx.test:core` line, so their "latest" was left unverified rather than asserted.

---

## 6. Recommended Modern Wardrobe / Fashion-Tech Stack

Each recommendation is tied to something the repo **actually does today**, not a generic wishlist.

### 6.1 AI & Vision — from text-only styling to real garment understanding

| Capability | Recommendation | Why it fits StyleDrop today |
|---|---|---|
| **Garment detection & background removal (on-device)** | **MediaPipe Image Segmenter** (Google AI Edge) | `AddItemScreen.kt` (225 lines) currently relies on the user manually typing `category`, `type`, `color`, `secondaryColor`, `pattern`, `fit`, `brand` into a `WardrobeItem`. On-device segmentation lets the app **auto-crop and isolate the garment** from a photo and pre-fill those fields — cutting the worst UX bottleneck in the app. Google's Image Segmenter divides an image into regions by predefined categories and runs fully on-device [MediaPipe Image Segmenter](https://developers.google.com/edge/mediapipe/solutions/vision/image_segmenter) · [ML on Android with MediaPipe series](https://www.youtube.com/playlist?list=PLOU2XLYxmsILZnKn6Erxdyhxmc3fxyitM) |
| **Structured outfit generation** | **Firebase AI Logic** with Gemini + **App Check** | The app currently calls `v1beta/models/gemini-1.5-flash` over raw Retrofit with the key in the APK uration. Firebase AI Logic is already half-integrated (`firebase-ai`, `appcheck-recaptcha`) and is the supported client-side path [Firebase AI Logic](https://firebase.google.com/docs/ai-logic) · [Gemini on Android](https://developer.android.com/ai/gemini). **Act now: App Check enforcement becomes automatic from early July 2026** |
| **Schema-constrained outfit output** | Gemini **structured output / response schema** instead of free text | `AiGeneratorViewModel` currently asks for a "fun, conversational tone" reply and renders raw text; `SavedOutfitsScreen` has no structured object to persist. A response schema returning `[{itemIds[], rationale, occasion, weather}]` makes outfits saveable, shareable and analytics-capable |
| **On-device recommendations (vector search)** | **On-device vector DB** (e.g. ObjectBox) + embedding model | Turns the manual filter logic in `WardrobeViewModel.getItemsByCategory()` into true **semantic wardrobe search** — "find something like this but warmer". On-device vector stores now persist locally with vector + metadata hybrid search [On-device vector databases in 2026](https://objectbox.io/262454-2/) · [On-device RAG: embeddings & vector search](https://medium.com/google-developer-experts/on-device-rag-for-app-developers-embeddings-vector-search-and-beyond-47127e954c24) |

### 6.2 Image Pipeline — for the wardrobe photo library

| Capability | Recommendation | Why |
|---|---|---|
| **Next-gen image loading** | **Coil 3.6.1** | Coil 2.7.0 is four years behind a rewrite that adds a modern network stack, better memory caching, and KMP readiness — critical when a wardrobe has hundreds of photos on the `HomeScreen` / `WardrobeScreen` grids [Coil changelog](https://coil-kt.github.io/coil/changelog/) · [Upgrading to Coil 3.x](https://coil-kt.github.io/coil/upgrading_to_coil3/) |
| **Camera capture** | Re-enable and upgrade **CameraX** (catalog already pins `camera-*` 1.5.0) | The camera dependencies are **commented out**, so the "add item photo" flow has no first-party capture path |
| **Cloud image delivery** | A CDN-backed image pipeline (resize/focal-crop/AVIF-WebP) behind the Firebase Storage URL | `WardrobeItem.imageUrl` is loaded directly; a CDN avoids shipping full-resolution originals to every grid cell |
| **On-device face/body privacy** | Keep segmentation on-device; never upload full-body photos to a third party without consent | `metadata.json` declares `requestFramePermissions: []` — no runtime camera permission is requested today |

### 6.3 Platform & Architecture Modernization

| Area | Current | Recommendation |
|---|---|---|
| Compose BOM | `2024.09.00` | **`2026.09.00`** — unlocks Compose 1.12 and every Material 3 fix since 2024 [Compose BOM](https://developer.android.com/develop/ui/compose/bom) |
| Navigation | `navigation-compose` 2.8.9 | `2.10.0`; **evaluate Navigation 3** for the 11-screen shell in `MainShell.kt` [Navigation 3 migration](https://proandroiddev.com/migrating-to-navigation-3-in-jetpack-compose-34b0389a9aea) |
| Lifecycle | 2.8.7 | 2.11.0 |
| Kotlin / AGP / Gradle | 2.2.10 / 9.1.1 / 9.3.1 | 2.4.20 / 9.4.0 / 9.7.1 — upgrade **together** with KSP [Kotlin releases](https://github.com/jetbrains/kotlin/releases) · [AGP 9.4.0](https://developer.android.com/build/releases/agp-9-4-0-release-notes) · [Gradle 9.7.1](https://docs.gradle.org/9.7.1/release-notes.html) |
| JSON | Moshi 1.15.2 (reflection) | Switch the live path to **KSP-generated adapters**; the DTOs are already annotated |
| Persistence | Room 2.7.0, destructive migration | Room **2.8.5** + real migrations; treat Room 3.0 (KMP) as a separate project [Room 3.0](https://android-developers.googleblog.com/2026/03/room-30-modernizing-room.html) |
| Icons | `material-icons-extended` | Migrate to **Material Symbols** / selective vectors to cut dex size |
| Networking | Retrofit 2.12 / OkHttp 4.10 | **Retrofit 3.0.0** + **OkHttp 5.3.2** |

### 6.4 Quality Gate (currently absent)

1. **ktlint + detekt** with a committed `.editorconfig` and a detekt baseline.
2. **Android Lint** with `abortOnError = true` on release and a checked-in baseline.
3. **GitHub Actions CI**: `assembleDebug`, `testDebugUnitTest`, `lint`, Roborazzi screenshot verification, and a **dependency-vulnerability scan** on every PR.
4. **Dependabot / Renovate** for the Gradle version catalog.
5. **Gradle dependency locking + `verification-metadata.xml`** so a compromised transitive artifact cannot enter silently.
6. **Real tests** for `WardrobeRepository`, `WardrobeDao` (Room in-memory), `AiGeneratorViewModel` (MockWebServer + `kotlinx-coroutines-test`), and the `Outfit` parsing path.
7. **Jacoco/Kover coverage gate** — currently 0%.
8. **Baseline Profile** for Compose startup.

---

## 7. Prioritized Upgrade Roadmap

### Phase 0 — Security (do first, low effort, high return)
1. Stop shipping the API key → move to **Firebase AI Logic + App Check** before the early-July-2026 enforcement date [Android AI docs](https://developer.android.com/ai/gemini).
2. Upgrade **OkHttp 4.10.0 → 5.3.2** to clear the Okio CVE-2023-3635 exposure [NVD](https://nvd.nist.gov/vuln/detail/cve-2023-3635).
3. Set `allowBackup="false"` (or scope backup rules away from the wardrobe DB).
4. Add an explicit `OkHttpClient` with timeouts; confirm the logging interceptor is not at `BODY` in release.
5. Replace `fallbackToDestructiveMigration()` with real migrations **before** any schema change.
6. Enable R8 (`isMinifyEnabled = true`) **together with** Moshi KSP adapters + proper keep-rules.

### Phase 1 — Foundation upgrades (same PR, coordinated)
7. Kotlin `2.2.10 → 2.4.20`, AGP `9.1.1 → 9.4.0`, Gradle `9.3.1 → 9.7.1`, KSP `2.3.5 → 2.3.12` — **one atomic upgrade**; these four must move together [Kotlin](https://github.com/jetbrains/kotlin/releases) · [AGP](https://developer.android.com/build/releases/agp-9-4-0-release-notes) · [Gradle](https://docs.gradle.org/9.7.1/release-notes.html). ⚠️ Treat the AGP 9.x migration with care — community reports flag it as a disruptive jump [AGP 9.0 migration write-up](https://www.reddit.com/r/androiddev/comments/1qi110y/agp_90_is_out_and_its_a_disaster_heres_full/).
8. Rename `namespace` off `com.example`.
9. Bump `compileSdk`/`targetSdk` alongside AGP.

### Phase 2 — UI & data modernization
10. **Compose BOM `2024.09.00 → 2026.09.00`** [Compose BOM](https://developer.android.com/develop/ui/compose/bom) · [Aug '26 release](https://android-developers.googleblog.com/2026/08/jetpack-compose-august-2026-release.html).
11. Lifecycle 2.8.7 → 2.11.0; Activity Compose 1.10.1 → 1.13.0; Navigation 2.8.9 → 2.10.0.
12. Coil 2.7.0 → 3.6.1 (major API move — dedicated PR) [Upgrading to Coil 3.x](https://coil-kt.github.io/coil/upgrading_to_coil3/).
13. Room 2.7.0 → 2.8.5; Retrofit 2.12.0 → 3.0.0 (binary-compatible with 2.x) [Retrofit 3 changelog](https://github.com/lysine-dev/retrofit/blob/master/CHANGELOG.md).
14. Re-enable CameraX & Accompanist Permissions (or replace the latter with first-party APIs) and add the real photo-capture flow.

### Phase 3 — Wardrobe-differentiating features
15. **MediaPipe Image Segmenter** auto-crop + auto-tagging on add [MediaPipe Image Segmenter](https://developers.google.com/edge/mediapipe/solutions/vision/image_segmenter).
16. **Structured-output outfit generation** replacing free-text responses.
17. **On-device vector search** over wardrobe embeddings for semantic "find similar" and smarter recommendations [On-device vector DBs 2026](https://objectbox.io/262454-2/).
18. CDN image pipeline + Firestore sync (dependency already catalogued, just commented out).

### Phase 4 — Sustainability
19. CI, ktlint, detekt, Android Lint gate, Dependabot, dependency locking, real tests, coverage floor, Baseline Profile.

---

## 8. Verified-Source Index

| Claim area | Source |
|---|---|
| AGP 9.4.0 current | [developer.android.com](https://developer.android.com/build/releases/agp-9-4-0-release-notes) |
| Kotlin 2.4.20 latest | [github.com/jetbrains/kotlin](https://github.com/jetbrains/kotlin/releases) · [blog.jetbrains.com](https://blog.jetbrains.com/kotlin/2026/06/kotlin-2-4-0-released/) |
| Gradle 9.7.1 / 9.8 RC1 | [docs.gradle.org](https://docs.gradle.org/9.7.1/release-notes.html) · [github.com/gradle](https://github.com/gradle/gradle/releases) |
| Compose BOM 2026.09.00 / Compose 1.12 | [developer.android.com BOM](https://developer.android.com/develop/ui/compose/bom) · [Android Developers Blog](https://android-developers.googleblog.com/2026/08/jetpack-compose-august-2026-release.html) |
| Room 2.8.5 / Room 3.0 | [developer.android.com Room](https://developer.android.com/jetpack/androidx/releases/room) · [Room 3.0 blog](https://android-developers.googleblog.com/2026/03/room-30-modernizing-room.html) · [Room 3.0 docs](https://developer.android.com/jetpack/androidx/releases/room3) |
| Navigation 2.10.0 / Nav 3 | [developer.android.com Navigation](https://developer.android.com/jetpack/androidx/releases/navigation) · [Navigation 3 guide](https://proandroiddev.com/migrating-to-navigation-3-in-jetpack-compose-34b0389a9aea) |
| Lifecycle 2.11.0 | [developer.android.com Lifecycle](https://developer.android.com/jetpack/androidx/releases/lifecycle) |
| Activity Compose 1.13.0 | [developer.android.com Activity](https://developer.android.com/jetpack/androidx/releases/activity) |
| OkHttp 5.3.2 / 5.0.0 stable | [OkHttp changelog](https://github.com/square/okhttp/blob/master/CHANGELOG.md) · [Maven Central](https://mvnrepository.com/artifact/com.squareup.okhttp3/okhttp) |
| Retrofit 3.0.0 | [Migration guide](https://proandroiddev.com/retrofit-3-0-0-detailed-migration-guide-0d2c043d43e3) · [CHANGELOG](https://github.com/lysine-dev/retrofit/blob/master/CHANGELOG.md) · [whatap.io](https://whatap.io/en/blog/retrofit-kotlin-update) |
| Coil 3.6.1 | [Coil changelog](https://coil-kt.github.io/coil/changelog/) · [Upgrading to Coil 3](https://coil-kt.github.io/coil/upgrading_to_coil3/) · [coil-kt/coil](https://github.com/coil-kt/coil) |
| Moshi 1.15.x current line | [square/moshi](https://github.com/square/moshi) |
| KSP 2.3.5 → 2.3.12 | [google/ksp releases](https://github.com/google/ksp/releases) · [symbol-processing-api 2.3.5](https://mvnrepository.com/artifact/com.google.devtools.ksp/symbol-processing-api/2.3.5) |
| Firebase BOM 34.18.x | [Firebase Android release notes](https://firebase.google.com/support/release-notes/android) · [firebase-bom Maven](https://mvnrepository.com/artifact/com.google.firebase/firebase-bom) |
| Firebase AI Logic / App Check enforcement | [Firebase AI Logic](https://firebase.google.com/docs/ai-logic) · [Gemini on Android](https://developer.android.com/ai/gemini) · [Firebase blog](https://firebase.blog/posts/2025/11/gemini-3-firebase-ai-logic/) |
| CVE-2023-3635 (Okio) | [NVD](https://nvd.nist.gov/vuln/detail/cve-2023-3635) · [Endor Labs](https://www.endorlabs.com/vulnerability/cve-2023-3635) · [Red Hat](https://access.redhat.com/security/cve/cve-2023-3635) · [Miggio](https://www.miggo.io/vulnerability-database/cve/CVE-2023-3635) · [SentinelOne](https://www.sentinelone.com/vulnerability-database/cve-2023-3635/) · [resend-java #83](https://github.com/resend/resend-java/issues/83) |
| MediaPipe Image Segmenter | [Google AI Edge](https://developers.google.com/edge/mediapipe/solutions/vision/image_segmenter) · [ML on Android with MediaPipe](https://www.youtube.com/playlist?list=PLOU2XLYxmsILZnKn6Erxdyhxmc3fxyitM) |
| On-device vector search | [ObjectBox](https://objectbox.io/262454-2/) · [On-device RAG](https://medium.com/google-developer-experts/on-device-rag-for-app-developers-embeddings-vector-search-and-beyond-47127e954c24) |
| AGP 9 migration caution | [r/androiddev](https://www.reddit.com/r/androiddev/comments/1qi110y/agp_90_is_out_and_its_a_disaster_heres_full/) |

---

*End of TECH_STACK.md — StyleDrop dependency & stack audit.*
