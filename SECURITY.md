# SECURITY.md — StyleDrop (styledropv2)

**Repository:** `github.com/ibrahimsidic12-afk/styledropv2`
**Audited commit:** `a53013bd477c8b6834d4a2b987163ce300c1dbea` — *feat(ui): update visual identity to ivory theme* (2026-09-17)
**Audit date:** 2026-09-17
**Scope:** native Android app (Kotlin 2.2.10 / Jetpack Compose / Room 2.7.0 / Retrofit 2.12.0 / OkHttp 4.10.0 / Firebase BOM 34.17.0, AGP 9.1.1, compileSdk 36, minSdk 24)
**Patch set:** `security-patches.diff` (unified diff, generated with `git diff` against the commit above)

---

## 1. Threat model

StyleDrop is a **single-user, on-device** wardrobe application. There is no backend of its own, no account system that actually authenticates, and no server-side data store. The assets that matter are therefore local and small, but they are unusually sensitive because they combine **photographs of the user's body and clothing** with **a behavioural record of what they wear and when**.

### Assets
| # | Asset | Location before patch | Why it matters |
|---|---|---|---|
| A1 | Wardrobe photographs | `WardrobeItem.imageUrl` → raw `content://` picker Uri, or an app-private plaintext file | Body/grooming inference, plus location metadata in EXIF |
| A2 | Wardrobe metadata | Room `styledrop_database` (SQLite) | Item list, brands, wear counts, favourites — a behavioural profile |
| A3 | Gemini API credential | `BuildConfig.GEMINI_API_KEY` inside the APK | Billable quota theft; pivot into the developer's Google Cloud project |
| A4 | AI prompts containing the full wardrobe | request body to `generativelanguage.googleapis.com` | Full wardrobe exfiltration in a single message |
| A5 | Crash/analytics logs | logcat + Play Console | Leaked key/prompt if `Level.BODY` is ever enabled |

### Adversaries
| Adversary | Capability | Primary attack on StyleDrop |
|---|---|---|
| **Opportunistic APK analyst** | Downloads the public APK, `unzip`, `strings`, `apktool` | Extracts `BuildConfig.GEMINI_API_KEY` (A3) — item 1 |
| **Malware on the same device** | Another app with `READ_EXTERNAL_STORAGE` / media access; a rooted or ADB-debug device | Reads plaintext photos and DB (A1, A2) — items 3, 4 |
| **On-path network attacker / hostile Wi-Fi** | Full MITM with a user-installed CA | Reads the AI request and, on a device that trusts a proxy CA, decrypts everything (A4) — items 5, 9 |
| **Hostile proxy user** | The device owner is the threat in a borrowed-device scenario | Opens the wardrobe and deletes items without the owner present — item 6 |
| **Tampered client** | Modified APK or emulator hitting the model endpoint | Burns the project's Gemini quota with no attestation — item 1 (App Check) |

### Explicitly out of scope
- A determined attacker with a **rooted device and an unlocked bootloader** — Keystore keys remain protected, but a running-app memory dump can still capture decrypted pixels.
- **Server-side compromise** of Google's Gemini/Firebase infrastructure.
- **Physical coercion** of the device owner.
- Supply-chain compromise of Gradle dependencies above the version bumps performed here.

---

## 2. Findings and remediation status

Severity uses the CVSS-style bands used throughout the report: 🔴 Critical / High, 🟠 Medium, 🟡 Low.

| ID | Finding | Severity | Item | Status |
|---|---|---|---|---|
| **S1** | Gemini API key compiled into the APK and sent as a URL query parameter | 🔴 Critical | 1 | ✅ Remediated |
| **S2** | OkHttp 4.10.0 transitively resolves Okio < 3.4.0 → **CVE-2023-3635** | 🔴 High | 2 | ✅ Remediated |
| **S3** | `android:allowBackup="true"` with backup rules defined → DB + photos eligible for cloud/device backup | 🟠 Medium | 3 | ✅ Remediated |
| **S4** | Wardrobe photos stored unencrypted (raw Uri or plaintext file) | 🟠 Medium | 4 | ✅ Remediated |
| **S5** | No `OkHttpClient` at all → no timeouts, no pinning, no `network_security_config.xml` | 🟠 Medium | 5 | ✅ Remediated |
| **S6** | Destructive operations (`deleteItem`) required no re-authentication | 🟡 Low-Med | 6 | ✅ Remediated |
| **S7** | SQL surface lacked injection discipline for `ORDER BY` / `LIKE` / future `@RawQuery` | 🟡 Low | 7 | ✅ Remediated |
| **S8** | `isMinifyEnabled = false` + fully-commented empty `proguard-rules.pro` | 🟠 Medium | 8 | ✅ Remediated |
| **S9** | No certificate transparency posture; no cleartext refusal; no host allowlist | 🟡 Low-Med | 9 | ✅ Remediated |
| **S10** | `fallbackToDestructiveMigration()` silently wipes the entire wardrobe on schema change | 🟠 Medium | *(adjacent)* | ✅ Remediated |
| **S11** | API-error strings echoed raw exception text into the UI | 🟡 Low | *(adjacent)* | ✅ Remediated |
| **S12** | Deleted items left orphaned image files on disk indefinitely | 🟡 Low | *(adjacent)* | ✅ Remediated |

---

## 3. Per-item remediation

### 3.1 Item 1 — Move the API key off the client (Firebase AI Logic + App Check)

**Before.** `app/build.gradle.kts` did:

```kotlin
val apiKey = System.getenv("GEMINI_API_KEY") ?: "YOUR_API_KEY_HERE"
buildConfigField("String", "GEMINI_API_KEY", "\"$apiKey\"")
```

and `GeminiApiService` sent it as `@Query("key") apiKey: String = BuildConfig.GEMINI_API_KEY`. Gitignoring `.env` protects the **repository, not the shipped binary**: any APK unzipper recovers the key from the merged `BuildConfig` class, and the key then appears in every request URL — which means it also lands in proxies, server access logs, and crash reports.

**After.**
- The `buildConfigField` line and the entire `.env` key path are **deleted**. `GEMINI_API_KEY` is no longer read at build time, so no key can reach the APK.
- The `@Query("key")` parameter is **removed**; the DTO-only `GeminiApiService` is retained purely as a deprecated legacy shim.
- All model traffic now goes through `com.example.ai.StylistAiService` → `Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(modelName)`.
- `com.example.StyleDropApplication` installs App Check **before** first use:
  - release → `PlayIntegrityAppCheckProviderFactory.getInstance()` (hardware-backed attestation);
  - debug → `DebugAppCheckProviderFactory.getInstance()` (token in logcat, registered in the Firebase console);
  - the token is warmed at startup so the first AI call is not delayed.
- The secrets plugin is retained **only** for `FIREBASE_APPCHECK_DEBUG_TOKEN`.

**Model ID:** the legacy `gemini-1.5-flash` string is replaced by `gemini-2.5-flash` in `StylistAiService.DEFAULT_MODEL`. *Unconfirmed:* whether `gemini-1.5-flash` is formally decommissioned on `v1beta` — no advisory was retrieved, so it is described only as superseded.

**Operator follow-up (cannot be done from the client):** enable App Check enforcement in the Firebase console for the AI Logic product, and register the release signing certificate SHA-256 (and the Play App Signing SHA-256) with the Play Integrity provider — an unregistered certificate produces `AppCheckToken` failures in release.

**Precondition:** `google-services.json`. The build currently uses `MissingGoogleServicesStrategy.WARN` + `googleServices.missing.passthrough=true`, so a missing config file yields a build that succeeds but an app whose AI features fail at runtime. **Change to `FAIL` for release** so this surfaces at build time.

### 3.2 Item 2 — OkHttp 5.3.2 upgrade (CVE-2023-3635)

**Premise correction (important).** CVE-2023-3635 is **not** an OkHttp vulnerability. It is an **Okio** vulnerability: `GzipSource` does not handle the exception raised while parsing a malformed gzip buffer, so a crafted gzip stream triggers an infinite loop / denial of service (CVSS 5.9) — [NVD](https://nvd.nist.gov/vuln/detail/cve-2023-3635), [Red Hat](https://access.redhat.com/security/cve/cve-2023-3635), [Endor Labs](https://www.endorlabs.com/vulnerability/cve-2023-3635), [SentinelOne](https://www.sentinelone.com/vulnerability-database/cve-2023-3635/). Upgrading OkHttp alone does **not** clear it unless the transitive Okio is also raised — and the repository sat on the already-affected 4.11.0-era Okio chain, i.e. *older* than the affected range.

**After (belt and braces).**
1. `okhttp` and `logging-interceptor` → **5.3.2**, confirmed to exist on Maven Central ([Sonatype](https://central.sonatype.com/artifact/com.squareup.okhttp3/okhttp/5.3.2), [Maven Repository](https://mvnrepository.com/artifact/com.squareup.okhttp3/okhttp-jvm/5.3.2)).
2. An explicit `okio` version (`3.9.0`) declared in the catalog, wired through **both** a `constraints { }` block (with a `because(...)` audit note) **and** a `configurations.all { resolutionStrategy { force(...) } }`, so no transitive graph can silently downgrade below the fix.

`3.9.0` is a deliberate, conservative choice: Maven Central's `okio` `<release>` is `3.18.2`; 3.9.0 was chosen for maximum compatibility with the OkHttp 5.x line. *Unconfirmed:* no independent advisory was retrieved confirming a fix version beyond "3.4.0+" — the constraint is therefore documented as a floor, not a verified minimum.

### 3.3 Item 3 — `allowBackup="false"`

`AndroidManifest.xml` now sets `android:allowBackup="false"`, plus `android:fullBackupOnly="false"`. Both backup rule files are rewritten from commented-out templates into **active exclusion lists** covering `styledrop_database` (+ `-wal` / `-shm`), the `wardrobe_photos` dir, `sharedpref`, and `external` — for `cloud-backup`, `device-transfer`, and the legacy `fullBackupContent` path. Defence in depth: the flag is the control, the rules survive a future revert of the flag.

### 3.4 Item 4 — Encrypt wardrobe photos at rest (`EncryptedFile`)

**Before.** `AddItemScreen` persisted `imageUrl = imageUri.toString()` — the raw picker `content://` URI. Nothing was copied or encrypted, so the photo lived under the picker provider's storage; if the picker grant is ever persisted or widened, any app with media access can read it.

**After.** `com.example.security.SecurePhotoStore`:
- `importFromUri()` copies the picker stream into app-private `filesDir/wardrobe_photos/<uuid>.enc` through `EncryptedFile` (`AES256_GCM_HKDF_4KB`);
- the wrapping key is `MasterKey` (`AES256_GCM`) held in the **hardware-backed Android Keystore**;
- only the token `enc://wardrobe_photos/<uuid>.enc` is persisted in the database — the raw Uri never reaches Room;
- `delete()` removes ciphertext **and** any cached plaintext;
- `purgeCache()` wipes all transient renderings.

**Residual risk (explicit).** `EncryptedFile` is a stream API and **Coil cannot consume it directly**. `resolveForDisplay()` therefore decrypts into `cacheDir/secure_photo_cache/*.jpg` for rendering. Those copies are plaintext-on-disk while the app runs. They are inside the app sandbox (not world-readable), excluded from backup, and deleted on item removal — but they are a genuine, documented weakening relative to a fully in-memory image pipeline. The alternative (`ByteArrayImageSource` on an in-memory decrypt) is the recommended follow-up.

**Dependency caveat.** `androidx.security:security-crypto` — the module providing `EncryptedFile` — has been **deprecated by Google in favour of direct Android Keystore use** ([Jetpack Security release notes](https://developer.android.com/jetpack/androidx/releases/security), [discussion](https://stackoverflow.com/questions/78362124/is-the-androidx-securitysecurity-crypto-module-being-deprecated-or-not-what-is), [community fork](https://github.com/ed-george/encrypted-shared-preferences)). Item 4's requirement is implemented as specified; the recommended migration is to a hand-rolled `AES/GCM` stream over a Keystore key, which is mechanically identical to what `EncryptedFile` does internally. The version pinned is `1.1.0-alpha06`.

### 3.5 Item 5 — `network_security_config.xml` with certificate pinning

**Before.** There was no `network_security_config.xml`, and — verified by reading `NetworkModule.kt` — **no `OkHttpClient` was ever installed into Retrofit**, despite `logging-interceptor` being a declared dependency. Retrofit silently built a default client: no timeouts, no pinning, no interceptor.

**After.**
- `res/xml/network_security_config.xml`: `cleartextTrafficPermitted="false"` at both base and domain level; trust anchors restricted to `system` (user-installed CAs are not trusted for app traffic); an explicit domain allowlist — `generativelanguage.googleapis.com`, `firebasevertexai.googleapis.com`, `firebase.ai`, `firebaseinstallations.googleapis.com`, `content-firebaseappcheck.googleapis.com`; and a `<pin-set expiration="2027-12-31">` carrying **two** pins.
- `com.example.network.SecureHttpClient` is the single hardened client: `CertificatePinner` with the same two pins, a 15 s connect / 60 s read / 60 s write / 90 s call timeout, and a logging interceptor whose level is `BASIC` in debug and **`NONE` in release** (BODY logging would print the full wardrobe and AI prompt to logcat).
- `NetworkModule` now calls `.client(SecureHttpClient.client)`.

**The actual pin value.** A live TLS probe from the analysis sandbox returned the SPKI SHA-256 for both hosts:

```
generativelanguage.googleapis.com -> baA+Ld3UAHTjfVroigcvh0Vq9L8h5fH1ONS5pXySa7U=
firebasevertexai.googleapis.com   -> baA+Ld3UAHTjfVroigcvh0Vq9L8h5fH1ONS5pXySa7U=
```

This digest is written in as the **primary** pin. **The backup pin is still the literal placeholder `PLACEHOLDER_BACKUP_SPKI_SHA256` and MUST be replaced before release.** This is deliberate and not an oversight: a `pin-set` with a single pin is a self-inflicted outage — when Google rotates its certificate the app cannot connect at all. Android's recommended pattern is two pins (current leaf/intermediate + an offline CA key) with an expiration, so a rotation degrades to ordinary PKI validation instead of bricking the client. Regenerate the values with:

```bash
openssl s_client -servername generativelanguage.googleapis.com \
  -connect generativelanguage.googleapis.com:443 </dev/null 2>/dev/null \
  | openssl x509 -pubkey -noout | openssl pkey -pubin -outform der \
  | openssl dgst -sha256 -binary | openssl enc -base64
```

Note also that a malformed pin is treated by Android as *no pinning at all* — shipping the placeholder would silently disable the control rather than fail loudly.

### 3.6 Item 6 — Biometric authentication for sensitive operations

`com.example.security.BiometricGate` uses `androidx.biometric:biometric:1.2.0-alpha05` and — critically — a **`CryptoObject`**, not a bare prompt. The prompt is bound to a Keystore AES key created with `setUserAuthenticationRequired(true)` and `setInvalidatedByBiometricEnrollment(true)`, so a successful callback can only occur if the hardware actually released the key; a hooked or spoofed client cannot fabricate `result.cryptoObject.cipher != null`. Re-enrolling a fingerprint invalidates the key, which is the desired fail-closed behaviour.

Gate applied to **item deletion** in `ItemDetailScreen`: if the gate reports unavailable, the action aborts (`onUnavailable`) rather than proceeding. The operation is additionally wired to remove the item's encrypted photo.

`MainActivity` changed from `ComponentActivity` to **`FragmentActivity`**, which `BiometricPrompt` requires; `androidx.fragment` resolves transitively through `androidx.biometric` (Google Maven confirms `fragment-ktx` release `1.9.0`; it is not added explicitly to avoid a version conflict with the existing Compose-activity stack).

### 3.7 Item 7 — SQL injection hardening

`WardrobeDao` already bound every `@Query` parameter — Room compiles `:category` / `:id` into `?` placeholders. The real injection surface was therefore **not** the `WHERE` clause; it was:
1. anything concatenated into `ORDER BY` / `GROUP BY` (identifiers cannot be bound), and
2. `LIKE` patterns, where a user-typed `%` silently becomes a wildcard.

**After.** `com.example.security.SqlGuard` is the single chokepoint:
- `safeOrderBy(requested)` resolves an untrusted sort key against a fixed map of six hard-coded fragments (`recent`, `oldest`, `name`, `worn`, `colour`, `season`) and falls back to `createdAt DESC` for anything else. Unknown input never reaches SQL.
- `escapeLike()` escapes `\`, `%`, `_`; `likeParam()` wraps the result; `clamp()` caps free text at 120 chars.
- `WardrobeDao` gains three hardened members: `searchItems` (bound `:query` with `ESCAPE '\'`), `getItemsByCategoryAndSeason` (multi-parameter bound filter — no string building), and `getItemsSorted` (`@RawQuery`) which **must** be fed the output of `SqlGuard.safeOrderBy()`.
- `WardrobeRepository.search()` / `.sorted()` are the only public entry points; the DAO is never called with raw user text.

This is a preventive control as much as a corrective one — the outfit-builder and search features planned in parallel workstreams would otherwise have introduced concatenated `ORDER BY` clauses.

### 3.8 Item 8 — ProGuard / R8 rules for minification

`isMinifyEnabled = false` was paired with a `proguard-rules.pro` in which **every rule was commented out**. Flipping minification on without keeps would have broken Moshi (reflection), Room, Retrofit and Firebase at runtime — which is very likely why it was left off.

**After.** `isMinifyEnabled = true` + `isShrinkResources = true`, with a complete rule set:
- Moshi: `com.squareup.moshi.**` kept, `@JsonClass` / `@Json` members kept, `**$*` → `<1>_JsonAdapter` synthetic-adapter rewrite, plus `com.example.network.**` kept for the legacy DTOs;
- Retrofit / OkHttp / Okio / Conscrypt `-dontwarn` (Conscrypt is the platform default and its optional BouncyCastle/OpenJSSE paths are absent);
- Room: `RoomDatabase` subclasses and `@Entity` types kept;
- `androidx.security.crypto.**`, `com.google.crypto.tink.**`, `androidx.biometric.**` kept (Tink and the Keystore path use reflection and JNI names);
- Firebase / Play Services / Play Core kept;
- `-assumenosideeffects` strips `Log.v/d/i` in release so no wardrobe or AI text can reach logcat;
- `-keepattributes SourceFile,LineNumberTable` with `-renamesourcefileattribute SourceFile` for de-obfuscated Play Console traces.

`KotlinJsonAdapterFactory` (reflection) remains the active Moshi path while `moshi-kotlin-codegen` runs under KSP — the rules cover both, so the redundancy is now harmless rather than a latent release-crash.

### 3.9 Item 9 — Certificate transparency

**What is enforced today.** Android's platform `TrustManagerImpl` (Conscrypt) enforces Chrome's CT log policy for apps targeting API 24+; a certificate lacking sufficient SCTs from qualified logs is rejected. The app inherits this automatically at `minSdk 24` — no code change is required for baseline CT.

**What this patch adds on top.**
1. **Conscrypt pinned as the provider** — `Security.insertProviderAt(Conscrypt.newProvider(), 1)` in `StyleDropApplication`, guaranteeing the CT-verifying `TrustManagerImpl` is used rather than a device/OEM substitute. Conscrypt is already the Android platform provider, so this is a reinforcement, not an addition.
2. **Cleartext refusal** — `usesCleartextTraffic="false"` in the manifest **and** `cleartextTrafficPermitted="false"` in the network security config, so no code path can downgrade to HTTP.
3. **Explicit host allowlist** — the `domain-config` block enumerates exactly the Firebase/Gemini hosts, so CT + pinning apply to precisely the traffic that carries wardrobe data.
4. **SPKI pinning with a backup pin and expiry** — see §3.5, which is the strongest available client-side guarantee that the leaf presented is the intended one.

**Not used, and why.** Android 16+ supports a native `<certificateTransparency>` tag for opt-in enforcement in the network security config. It is **deliberately omitted**: the element is ignored on every API level below 16, and enabling it app-wide risks breaking Firebase endpoints not enumerated in the allowlist — a full outage traded for a control the platform already enforces. The recommended upgrade path, if explicit enforcement is required, is to add the tag to a `targetSdk`-gated `res/xml` variant once the domain allowlist has been validated against real production telemetry.

---

## 4. Verification performed

| Check | Result |
|---|---|
| Repository cloned | ✅ exit 0, public repo, working tree clean at `a53013bd` |
| `git diff` generated against the audited commit | ✅ real hunks, no hand-written headers (see `security-patches.diff`) |
| `GEMINI_API_KEY` references remaining in `app/src` and `app/build.gradle.kts` | ✅ **zero** (single remaining hit is a comment in `app/build.gradle.kts` explaining its removal) |
| Live TLS SPKI probe (`openssl s_client` → `dgst -sha256`) | ✅ returned `baA+Ld3UAHTjfVroigcvh0Vq9L8h5fH1ONS5pXySa7U=` for both hosts; written into both pin locations |
| OkHttp 5.3.2 existence confirmed | ✅ Maven Central / Sonatype artifact pages |
| CVE-2023-3635 attribution confirmed as **Okio**, not OkHttp | ✅ NVD, Red Hat, Endor Labs, SentinelOne, Miggo |
| `androidx.security:security-crypto` deprecation confirmed | ✅ Google Jetpack Security release notes + community sources |
| Maven Central reachable for version metadata | ✅ `okio` `<release>` = `3.18.2`; Google Maven `fragment-ktx` `<release>` = `1.9.0` |
| `okio` 3.9.0 exact existence in Maven Central index | ⚠️ **Unconfirmed** — the metadata `<release>` was read but the specific 3.9.0 POM was not fetched |
| Full `assembleDebug` / `assembleRelease` compile | ❌ **Not performed** — no Android SDK in the analysis sandbox; JDK 21 present, Gradle wrapper JAR absent from the repo |
| Backup pin value | ⚠️ **Placeholder by design** — see §3.5; must be replaced before release |

**Sincere limitation.** Every patch was applied as a real edit to the cloned tree and every diff hunk was produced by `git diff`, but the codebase was **not compiled**. The verification above is static/structural plus live network probes. Kotlin/Compose resolution errors (for example an import that needs `androidx.fragment` added explicitly, or a Firebase AI Logic API-surface mismatch against BOM 34.17.0) would surface at the first `./gradlew assembleDebug` in Android Studio and must be treated as expected first-build work, not as evidence that a patch is wrong.

---

## 5. Release checklist (operator actions that cannot live in code)

1. Replace `PLACEHOLDER_BACKUP_SPKI_SHA256` in **both** `network_security_config.xml` and `SecureHttpClient.kt` with a real backup SPKI digest.
2. Enable **App Check enforcement** for Firebase AI Logic in the Firebase console.
3. Register the release **and** Play App Signing SHA-256 certificates with the Play Integrity provider.
4. Provision `google-services.json`; switch `MissingGoogleServicesStrategy` to `FAIL` for release builds.
5. **Rotate the Gemini API key.** The key that shipped inside prior APK builds must be treated as compromised and revoked in Google AI Studio — this is the only step that actually nullifies S1 for already-distributed builds.
6. Move the release keystore off the `${rootDir}/my-upload-key.jks` default and supply credentials from CI secrets.
7. Rename `namespace` off the placeholder `com.example` before any store submission.
8. Confirm `HttpLoggingInterceptor.Level.BODY` never appears in release; the patched code sets `NONE`, and R8 strips `Log.v/d/i` regardless.

---

## 6. Residual risks and accepted trade-offs

| Risk | Assessment | Mitigation path |
|---|---|---|
| Decrypted photo copies in `cacheDir` while the app runs | Accepted, documented (§3.4) | In-memory `ByteArrayImageSource` decrypt |
| Backup pin still a placeholder | Blocking for release | §5 step 1 |
| No compile verification | Blocking for release | First `assembleDebug` in Android Studio |
| App Check enforcement not yet enabled server-side | Blocking for prod security | §5 step 2 |
| Previously-shipped APKs leak the old key | Cannot be fixed client-side | §5 step 5 — rotate/revoke |
| `androidx.security:security-crypto` deprecated upstream | Accepted | Hand-rolled Keystore AES/GCM stream |
| Rooted device memory inspection | Out of scope (§1) | Full-disk/strongbox defence beyond app control |
| `fail-closed` delete gate on devices without enrolled biometrics | Accepted | Gate reports unavailable and aborts; add a documented passphrase fallback if support requests arise |

---

## 7. Reporting a vulnerability

Because StyleDrop is a client-only application with no server-side data store, the only externally reachable surface is the model endpoint — and it is protected by App Check attestation, not by a secret in the client. Nevertheless, reports are welcome.

- **Contact:** open a private GitHub Security Advisory on `github.com/ibrahimsidic12-afk/styledropv2` (Security → Report a vulnerability), or email the maintainer listed on the repository profile.
- **Please include:** affected commit SHA, device/OS version, a reproduction path, and whether any personally identifying wardrobe data was exposed.
- **Target acknowledgement:** 72 hours. **Target assessment:** 7 days. **Target fix for a High/Critical:** 30 days.
- **Please do not** open public issues containing wardrobe photos, API material, or reproduction steps that leak user data.
- **Out of scope for reports:** findings that assume a rooted device or an unlocked bootloader (§1).

---

*Generated by static analysis + live TLS/dependency probes on the cloned tree at `a53013bd477c8b6834d4a2b987163ce300c1dbea`. No upstream file was modified outside this working tree; the patch set is delivered separately as `security-patches.diff`.*
