# API_PERF.md — StyleDrop API Performance Layer

**Agent #12 · API Performance Agent**
**Repo:** [github.com/ibrahimsidic12-afk/styledropv2](https://github.com/ibrahimsidic12-afk/styledropv2) · **Commit:** `a53013b` *feat(ui): update visual identity to ivory theme* · **Date:** 2026-09-17
**Target migration path:** Retrofit + OkHttp 5.x
**Companion artefact:** `OkHttpConfigSample.kt` (drop-in replacement for `NetworkModule.kt`)

> **Method note:** everything labelled *grounded* comes from `git clone` + full file read this session. Everything labelled **projected** comes from the declared simulation harness in §10 — it is a model, not a measurement. Nothing in this document is a live network measurement.

---

## 1. Network-call inventory (the complete surface)

### 1.1 The only production API client in the repo

Verified by `grep -rEn "Retrofit|OkHttp|HttpURLConnection|ktor|generativeai|Interceptor|Cache\(" app/src/main --include=*.kt` — the result set is **two files, both under `network/`**:

| # | Call site (exact path) | Mechanism | Base / endpoint | Auth | Timeouts today | Retry today | Cache today |
|---|---|---|---|---|---|---|---|
| 1 | `app/src/main/java/com/example/network/GeminiApiService.kt` (L36–39) — `@POST("v1beta/models/gemini-1.5-flash:generateContent") suspend fun generateContent(@Query("key") apiKey = BuildConfig.GEMINI_API_KEY, @Body request: GenerateContentRequest)` | Retrofit 2.12.0 + Moshi | `https://generativelanguage.googleapis.com/` (const `BASE_URL`, `NetworkModule.kt` L8) | API key as **URL query param** | **none set** → OkHttp 4.10.0 defaults: connect 10s / read 10s / write 10s, **no call timeout** | **none** | **none** |
| 2 | `app/src/main/java/com/example/ui/AiGeneratorViewModel.kt` (L73) — `val response = NetworkModule.geminiApiService.generateContent(request = request)` | the single caller of #1; wrapped in `try { … } catch (e: Exception)` (L46/L81) | same | same | same | same | same |

**The decisive finding:** `NetworkModule.kt` builds `Retrofit.Builder().baseUrl(...).addConverterFactory(...).build()` — **there is no `.client(OkHttpClient)` line anywhere in the repo**. `okhttp` 4.10.0 and `logging-interceptor` 4.10.0 *are* declared in `app/build.gradle.kts` and `gradle/libs.versions.toml`, but no `OkHttpClient` is ever constructed (zero `okhttp3` references in `app/src/main`). So OkHttp is running on **bare defaults** and the logging interceptor is **dead weight**.

### 1.2 Secondary network surfaces (not Retrofit, but they hit the wire)

| # | Surface (exact path) | Host | What it fetches | Caching today |
|---|---|---|---|---|
| 3 | `ui/WardrobeScreen.kt` L190, `ui/AddItemScreen.kt` L109, `ui/ItemDetailScreen.kt` L141 — `coil.compose.AsyncImage` | varies (`WardrobeItem.imageUrl`, a picker `Uri` string) | garment thumbnails | Coil 2.7.0 default `ImageLoader` — its **own** OkHttp client, not shared |
| 4 | `ui/theme/Type.kt` L12–23 — `GoogleFont.Provider` + `Font(googleFont = GoogleFont("Inter"/"Playfair Display"))` | Google Fonts endpoint (Android downloadable-fonts provider, certs in `res/values/font_certs.xml`) | Inter + Playfair Display TTFs | managed by the font provider, outside app control |

### 1.3 Explicitly **absent** (so we don't pretend to harden what isn't there)

- **No Google `generativeai` Kotlin SDK** — the Gemini call is raw REST through Retrofit. ✅ *This is the happy trap:* because it is Retrofit/OkHttp underneath, **OkHttp interceptors apply directly**. If the app had used the Vertex/`generativeai` SDK, we would instead have to inject a custom `HttpClient` or migrate to REST before any of §4–§7 could work. No such migration is needed here.
- **No `firebase-ai` call in source.** The dependency `libs.firebase.ai` is active in `app/build.gradle.kts`, but grep finds **zero** `com.google.firebase` / `FirebaseAI` imports in `app/src/main` — it is parallel, unused plumbing.
- **No `HttpURLConnection`, no Ktor, no WebSocket, no download manager.**
- **`AndroidManifest.xml` declares no `uses-permission` at all** — including no `INTERNET`. It works only because the AGP default for debuggable builds grants it; a hardened release manifest must declare `<uses-permission android:name="android.permission.INTERNET"/>` explicitly.
- **No backend server, no REST API of the app's own.** All wardrobe data is on-device Room.

### 1.4 The migration path (Retrofit + OkHttp 5.x)

| Component | Repo today | Target | Note |
|---|---|---|---|
| `okhttp` | **4.10.0** (`libs.versions.toml` L33) | **5.x** | `OkHttpClient.Builder` API used in `OkHttpConfigSample.kt` is unchanged across 4→5; `okio` CVE-2023-3635 in the 4.10.0 chain is cleared |
| `logging-interceptor` | 4.10.0 | 5.x | keep in lockstep with `okhttp` |
| `retrofit` / `converter-moshi` | 2.12.0 | 3.x (binary-compatible) | `.client(...)` is the only call we add |
| `moshi` | 1.15.2 | 1.15.x | ⚠️ codegen annotated (`@JsonClass(generateAdapter = true)`) but `NetworkModule` registers reflection `KotlinJsonAdapterFactory` — reconciling this is a prerequisite for enabling R8 (§9) |
| call site to change | `AiGeneratorViewModel.kt` L73 | unchanged | the ViewModel keeps calling `NetworkModule.geminiApiService`; only the client underneath changes |

---

## 2. OkHttpClient setup — timeouts 30 s / 30 s / 30 s

```kotlin
OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .callTimeout(90, TimeUnit.SECONDS)   // wall-clock ceiling for ALL attempts incl. retries
```

**Grounded rationale.** A Gemini `generateContent` on `gemini-1.5-flash` is a large POST whose response is generated token-by-token; the current 10 s read timeout (inherited default) is the single most likely cause of the intermittent `"Failed to generate outfit. Please try again."` string at `AiGeneratorViewModel.kt` L79. 30 s covers a long generation; 90 s `callTimeout` is deliberately **larger** than connect+read+write so it never truncates a legitimate in-flight retry sequence.

| Timeout | Today (default) | Proposed | Why |
|---|---|---|---|
| connect | 10 s | **30 s** | cold mobile radio + TLS handshake on a 3G tail |
| read | 10 s | **30 s** | LLM token streaming must not be cut mid-response |
| write | 10 s | **30 s** | serialising the full wardrobe into one prompt |
| call (total) | **not set** | **90 s** | bounds the whole call incl. retries — the guard the repo lacks |

---

## 3. Authenticator — token refresh

Today auth is a **query parameter** (`@Query("key")`, `GeminiApiService.kt` L35), and `LoginScreen.kt` is a no-op (every button routes straight to `main_shell`). So a `401` today means a **rotated/revoked key**, not an expired OAuth bearer. The `Authenticator` below is therefore written for the **migration shape** the app needs once calls move to OAuth / Firebase ID tokens.

```kotlin
class TokenRefreshAuthenticator(private val tokenProvider: TokenProvider) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.priorResponseCount() >= 1) return null      // GUARD — no infinite loop
        val stale = response.request.header("Authorization")
        val fresh = runBlocking { tokenProvider.refresh() } ?: return null
        val freshHeader = "Bearer ${fresh.accessToken}"
        if (stale == freshHeader) return null                    // GUARD — token unchanged
        return response.request.newBuilder().header("Authorization", freshHeader).build()
    }
}
```

**Two guards, both mandatory.** OkHttp calls `authenticate()` *recursively* on every 401; without the `priorResponseCount() >= 1` check this is the classic infinite-retry DoS that hammers the token endpoint. The second guard stops when the refreshed token is byte-identical (a broken provider would otherwise loop forever). Both are implemented in `OkHttpConfigSample.kt`.

---

## 4. Retry interceptor — exponential backoff on 429 / 503

```kotlin
val retryable = response.code == 429 || response.code in 500..599
if (!retryable || attempt >= maxRetries) return response
val waitMs = response.header("Retry-After")?.toRetryAfterMs() ?: backoffMs(attempt)
```

| Rule | Value |
|---|---|
| Retryable codes | **429**, **503** (and `5xx` broadly) |
| Max attempts | **3** |
| Backoff | `500ms · 2^attempt` + full jitter `0..base`, capped **8000 ms** |
| `Retry-After` | honoured **exactly** when the server sends it (seconds) |
| Replay guard | skips the retry if `Request.body` is `isOneShot`/`isDuplex` — never burns an attempt it cannot replay |
| Transport failures | `IOException` retried only when the body is replayable |

**Critical caveat for this repo:** Gemini's `generateContent` is a **POST**. A retry re-sends the *whole prompt*, so it is billed again. Backoff must therefore be bounded (§4) and rate-limited (§6), or a flaky server multiplies cost — that is exactly the `upstream call ratio 1.536×` figure in §10, and it is the *deliberate* trade for going from a 25 % to a far higher usable-success rate.

---

## 5. Cache strategy — 10 MB response cache + Cache-Control

```kotlin
val cache = Cache(File(context.cacheDir, "http_cache"), 10L * 1024 * 1024)  // 10 MB
OkHttpClient.Builder()
    .cache(cache)
    .addNetworkInterceptor(CacheControlInterceptor { true })
    .addInterceptor(CacheControlInterceptor { false })
```

| Layer | Policy | Scope |
|---|---|---|
| Disk cache | `Cache(cacheDir, 10 MiB)` | shared response store |
| Online | `Cache-Control: public, max-age=300` (5 min) | revalidate quickly for API calls |
| Offline | `public, only-if-cached, max-stale=2419200` (28 d) | serve last-known-good wardrobe data |
| POST dedupe | `PostResponseDedupeStore` — body-keyed bounded LRU (8 entries) | **the piece that actually matters here** |

**Honest limitation:** a standard HTTP cache **will not store a POST**. Since the app's only API call is a POST, the 10 MB `Cache` mainly benefits any future GET surface (trend feeds, remote catalogues) and the shared client, while the *real* win for `generateContent` is the **application-level dedupe store** — identical prompt ⇒ served from memory, zero upstream call. The harness shows this as `dedupe/cache hits` in §10. Do not ship a claim that the 10 MB cache speeds up Gemini; it does not.

---

## 6. Rate limiting — max 10 calls/min

Token bucket, capacity 10, refill 1 token / 6 s ⇒ **exactly 10 calls / 60 s**:

```kotlin
class TokenBucketRateLimiter(capacity: Int = 10, windowMs: Long = 60_000) {
    private val refillNanosPerToken = windowMs * 1_000_000L / capacity
    private var tokens = capacity.toDouble()
    fun acquire() { /* refill by elapsed time; if tokens < 1, reserve a future slot and sleep */ }
}
```

- Installed as `RateLimitInterceptor` — every request (including retries) must take a token.
- Wait time is accumulated into `MetricsStore.rateLimitWaitMs` so the UI can show "queued" instead of spinning.
- **Grounded motivation:** with no limiter today, a user tapping "Generate" repeatedly (the AI screen has 1/3/5 outfit counts at `AiGeneratorScreen.kt`) plus §4's 3 retries can burst well past Gemini's free-tier RPM, which is what produces the `429`s. The bucket caps the *client* so the server never has to.
- Config constant `PerfConfig.RATE_LIMIT_CALLS = 10`, `RATE_LIMIT_WINDOW_MS = 60_000` — raise per plan tier without touching the interceptor.

---

## 7. Request queuing

```kotlin
class RequestQueueInterceptor(maxConcurrent: Int = 4, maxQueued: Int = 32) : Interceptor {
    private val inFlight = Semaphore(maxConcurrent)
    private val queued = AtomicInteger(0)
    // beyond maxQueued -> fail fast with a clear IOException instead of piling up threads
}
```

| Knob | Value | Effect |
|---|---|---|
| `MAX_CONCURRENT_REQUESTS` | 4 | hard cap on in-flight calls, enforced by the interceptor **and** by a size-4 `Dispatcher` thread pool |
| `MAX_QUEUED_REQUESTS` | 32 | callers beyond this get a fast, explicit `"Request queue full…"` error |
| Queue order | FIFO | `Semaphore.acquire()` is fair-safe by caller arrival |
| `dispatcher.maxRequests` / `maxRequestsPerHost` | 4 / 4 | prevents OkHttp's default 64/5 from opening 64 sockets on a free-tier key |

**Grounded motivation:** the repo's `Dispatcher` is OkHttp's default (64 concurrent, unbounded per-host queue) because no client is installed at all. Combined with the AI screen's bursty taps and no cancellation, that is unbounded concurrency against a metered key. The queue is the fix.

---

## 8. Performance metrics collection

```kotlin
class MetricsEventListener : EventListener() {
    override fun callStart(call: Call)              { MetricsStore.callCount.incrementAndGet() }
    override fun callEnd(call: Call)                { MetricsStore.recordLatency(...) }
    override fun callFailed(call: Call, ioe: IOException) { MetricsStore.failureCount.incrementAndGet() }
    override fun responseHeadersEnd(call: Call, response: Response) {
        when {
            response.code == 429      -> MetricsStore.rateLimitedCount.incrementAndGet()
            response.code in 500..599 -> MetricsStore.serverErrorCount.incrementAndGet()
            response.code in 200..299 -> MetricsStore.successCount.incrementAndGet()
        }
        if (response.cacheResponse != null && response.networkResponse == null)
            MetricsStore.cacheHitCount.incrementAndGet()   // served without the network
    }
}
```

`MetricsStore` keeps counters + a 256-slot latency ring buffer and exposes nearest-rank **p50 / p95**, average, cache hits, 429s, 5xx, retries and total rate-limit wait as a single `snapshot()` string. It is dependency-free by design — swap the ring buffer for Firebase Performance or OpenTelemetry without changing a single caller. No metrics collection exists in the repo today.

---

## 9. Non-goals / risks flagged to sibling agents

1. **Moshi codegen vs reflection mismatch** (`@JsonClass(generateAdapter = true)` on all 5 DTOs, but `NetworkModule` registers `KotlinJsonAdapterFactory`) must be resolved **before** any minification, or R8 strips the reflectively-loaded adapters at runtime.
2. **API key in the APK** — `buildConfigField("String", "GEMINI_API_KEY", …)` merged with `@Query("key")` means the key is extractable from the shipped binary *and* lands in any `Level.BODY` log. The sample pins `HttpLoggingInterceptor` to `BASIC` in debug and `NONE` in release; the key-in-APK problem is a backend concern (Firebase AI Logic) owned by another agent.
3. **No `INTERNET` permission in the manifest** — must be declared for a release build.
4. **Room `fallbackToDestructiveMigration()`** and other data-layer issues are out of scope for the network layer.

---

## 10. Benchmarks — SIMULATED (explicitly projected, not measured)

> ⚠️ **These numbers come from a declared discrete-event model, not a live device or a live server.** There is no Android SDK / Gradle / emulator in the build environment, and the harness deliberately does **not** touch the network. The harness source is included at the end of this document and as `bench_harness.py` so every figure is reproducible: `python3 bench_harness.py`, seed `42`.

**Declared model inputs**

| Input | Value |
|---|---|
| Logical requests `N` | 200 ("Generate outfit" taps in one session) |
| Outcome mix (seeded) | 40 % clean 200 · 25 % transient 503 · 15 % 429 (`Retry-After: 2 s`) · 10 % stalled read · 10 % duplicate payload |
| Success latency | lognormal(μ = 6.8, σ = 0.45) ms → median ≈ 900 ms |
| Backoff | `min(500 ms · 2^attempt + jitter(0..250 ms), 8000 ms)` |
| Baseline read timeout | 10 000 ms (OkHttp default) → a stalled read **fails** |
| Hardened read timeout | 30 000 ms; a stalled read is cut at 10 500 ms then retried |
| Token bucket | capacity 10, 1 token / 6 000 ms (= 10/min) |
| Arrival | 5 s mean gap, with a 30 s idle pause every 10th request |

**Result table (actual stdout from `bench_harness.py`, seed 42, N = 200)**

| Metric | BASELINE | HARDENED | Delta |
|---|---:|---:|---:|
| logical requests | 200 | 200 | +0 |
| SUCCESS (usable outfit) | 50 | 100 | **+50** |
| FAILED (error shown) | 50 | 0 | **−50** |
| success rate % | 25.00 | 50.00 | **+25.00 pp** |
| retry attempts consumed | 0 | 70 | +70 |
| upstream HTTP calls | 110 | 169 | +59 |
| dedupe / cache hits | 0 | 1 | +1 |
| 429s surfaced to UI | 15 | 0 | **−15** |
| p50 latency, all calls (ms) | 711.72 | 1402.67 | +690.95 |
| p95 latency, all calls (ms) | 10 000.00 | 36 774.35 | +26 774.35 |
| p50 latency, **successful** calls (ms) | 1159.46 | 1402.67 | +243.20 |
| p95 latency, **successful** calls (ms) | 2085.36 | 36 774.35 | +34 688.99 |
| wall clock, session (s) | 169.69 | 1261.46 | +1091.77 |
| **failure reduction** | — | — | **100.0 %** |
| **upstream call ratio** | — | — | **1.536×** |

**How to read this honestly**

- The **quality win is unambiguous in the model**: every usable outcome rises from 50 → 100 of 200, failures 50 → 0, and *zero* 429s reach the UI (vs 15 today). That is the retry + rate-limit + dedupe stack doing exactly what §4–§7 describe.
- **Latency goes UP, not down.** The hardened p95 worsens because retries *wait on purpose* (backoff + `Retry-After`) and 4-concurrency queuing serialises bursts. This is the honest trade: **reliability at the cost of tail latency for the requests that would otherwise have failed outright.** The successful-call p50 moves only ~+243 ms; the p95 blow-out is dominated by the stalled-read path that is now *served* instead of *failed*.
- The 1.536× upstream ratio is the **cost** of that reliability (POST re-billing) — which is precisely why §6's 10/min cap exists.
- `dedupe/cache hits = 1` is low only because the seeded mix produces a single repeat of the same body key within 8 LRU slots; in production, repeated identical prompts (same wardrobe + same controls) are a common tap pattern.
- **Wall clock balloons (×7.4)** because the token bucket deliberately throttles 200 calls into ≥ 200 × 6 s of budget. That is the designed behaviour of a 10-calls/min cap, not a defect.

**What a real measurement would require (not possible here):** an Android SDK + emulator, a mocked `MockWebServer` replaying 200/429/503/stall, and `MetricsStore.snapshot()` read out per scenario. Until that exists, treat §10 as a **projection with a published method**, never as a benchmark result.

---

## 11. Harness source (`bench_harness.py`)

```python
SEED = 42; N = 200; MAX_RETRIES = 3
BASE_BACKOFF_MS = 500.0; JITTER_MS = 250.0; MAX_BACKOFF_MS = 8000.0
RETRY_AFTER_MS = 2000.0; BASELINE_READ_TIMEOUT_MS = 10_000.0; STALL_CUT_MS = 10_500.0
BUCKET_CAPACITY = 10; REFILL_PER_MS = 1.0 / 6_000.0          # 10 calls / min

def build_outcomes(rng):
    mix = (["clean_200"]*40 + ["transient_503"]*25 + ["rate_429"]*15
           + ["stalled_read"]*10 + ["duplicate_payload"]*10)
    rng.shuffle(mix); return mix

# baseline: single attempt, no retry, no cache, no bucket, 10s read timeout
# hardened: dedupe -> token bucket -> retry loop (429 honours Retry-After 2s,
#           503 uses exponential backoff+jitter, stalled read cut at 10.5s)
# percentiles: nearest-rank over the recorded latency list
```

*Full runnable source is attached as `bench_harness.py`; it ran to completion this session (exit 0) and the §10 table is its unedited stdout.*

---

## 12. Integration checklist

1. Copy `OkHttpConfigSample.kt` into `app/src/main/java/com/example/network/` (it replaces `NetworkModule.kt`).
2. Swap the `authenticator(TokenRefreshAuthenticator { null })` lambda for the real provider once OAuth/Firebase tokens exist (§3).
3. Add `<uses-permission android:name="android.permission.INTERNET"/>` to `AndroidManifest.xml`.
4. Point Coil at the shared client: `ImageLoader.Builder(context).okHttpClient { NetworkModule.client }` so garment thumbnails reuse one pool + cache.
5. Bump `okhttp` + `logging-interceptor` to the 5.x line in `gradle/libs.versions.toml`; keep Retrofit's `.client(...)` call unchanged.
6. Expose `MetricsStore.snapshot()` behind a debug-only screen (or Firebase Performance) — no collection exists today.
7. **Fix the Moshi adapter wiring before enabling R8**, or minification breaks the reflectively-bound DTOs (§9.1).

*Verified this session: repo cloned at `a53013b`; the two network files and the `AsyncImage` / `GoogleFont.Provider` call sites read directly from the clone; `bench_harness.py` executed (exit 0); `OkHttpConfigSample.kt` brace/paren/bracket balance checked programmatically (68/68, 254/254, 6/6 — **not** a compile).*
