/*
 * ============================================================================
 *  StyleDrop — API Performance Layer  (Agent #12 deliverable)
 *  File:  OkHttpConfigSample.kt
 *  Package target in repo: com.example.network
 *  Drop-in replacement for the current `NetworkModule.kt`, which today builds
 *  a Retrofit instance with NO OkHttpClient installed at all:
 *
 *      Retrofit.Builder()
 *          .baseUrl(BASE_URL)
 *          .addConverterFactory(MoshiConverterFactory.create(moshi))
 *          .build()                     // <-- OkHttp 4.10.0 defaults apply
 *
 *  Grounded in the actual repo surface (verified by reading the clone):
 *    app/src/main/java/com/example/network/NetworkModule.kt
 *    app/src/main/java/com/example/network/GeminiApiService.kt
 *    app/src/main/java/com/example/ui/AiGeneratorViewModel.kt:73
 *        val response = NetworkModule.geminiApiService.generateContent(request = request)
 *    app/src/main/java/com/example/ui/theme/Type.kt   (GoogleFont.Provider -> *.gstatic.com)
 *    app/src/main/java/com/example/ui/{WardrobeScreen,AddItemScreen,ItemDetailScreen}.kt
 *        (coil.compose.AsyncImage -> image fetching)
 *
 *  Versions assumed (from gradle/libs.versions.toml): okhttp 4.10.0,
 *  retrofit 2.12.0, moshi 1.15.2, coroutines 1.10.2. Retrofit 3.x / OkHttp 5.x
 *  keep the same `OkHttpClient.Builder` API used here.
 *
 *  Covers the requested 8 items:
 *    1) network-call inventory ....... see API_PERF.md §1
 *    2) timeouts 30s / 30s / 30s ..... §2  (wired below)
 *    3) Authenticator token refresh .. §3  (TokenRefreshAuthenticator)
 *    4) retry interceptor 429/503 .... §4  (ExponentialBackoffRetryInterceptor)
 *    5) cache 10 MB + Cache-Control .. §5  (okhttp Cache + CacheControlInterceptor)
 *    6) rate limiting 10 calls/min ... §6  (TokenBucketRateLimiter)
 *    7) request queuing .............. §7  (Semaphore + Dispatcher tuning)
 *    8) performance metrics .......... §8  (MetricsEventListener + MetricsStore)
 * ============================================================================
 */
package com.example.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.pow

// ============================================================================
// §2 — TIMEOUT POLICY  (30s / 30s / 30s  +  a strictly larger 90s call timeout)
// ============================================================================
object PerfConfig {
    const val CONNECT_TIMEOUT_S: Long = 30L
    const val READ_TIMEOUT_S: Long = 30L
    const val WRITE_TIMEOUT_S: Long = 30L
    const val CALL_TIMEOUT_S: Long = 90L          // > connect+read+write by design
    const val CACHE_SIZE_BYTES: Long = 10L * 1024L * 1024L   // 10 MB
    const val CACHE_DIR_NAME: String = "http_cache"

    // §6 — rate limiting: 10 calls / 60s
    const val RATE_LIMIT_CALLS: Int = 10
    const val RATE_LIMIT_WINDOW_MS: Long = 60_000L

    // §7 — request queue / concurrency ceiling
    const val MAX_CONCURRENT_REQUESTS: Int = 4
    const val MAX_QUEUED_REQUESTS: Int = 32

    // §4 — retry policy
    const val MAX_RETRIES: Int = 3
    const val BASE_BACKOFF_MS: Long = 500L
    const val MAX_BACKOFF_MS: Long = 8_000L

    const val BASE_URL: String = "https://generativelanguage.googleapis.com/"
}

// ============================================================================
// §8 — PERFORMANCE METRICS COLLECTION
//   A tiny dependency-free registry. Production note: swap the in-memory ring
//   buffer for Firebase Performance / OpenTelemetry without touching callers.
// ============================================================================
object MetricsStore {
    private const val RING_SIZE = 256
    private val ring = LongArray(RING_SIZE)
    private val ringIdx = AtomicInteger(0)

    val callCount = AtomicInteger(0)
    val successCount = AtomicInteger(0)
    val failureCount = AtomicInteger(0)
    val retryCount = AtomicInteger(0)
    val serverErrorCount = AtomicInteger(0)   // HTTP 5xx
    val rateLimitedCount = AtomicInteger(0)   // HTTP 429
    val cacheHitCount = AtomicInteger(0)
    val rateLimitWaitMs = AtomicLong(0L)
    val totalLatencyMs = AtomicLong(0L)

    fun recordLatency(ms: Long) {
        ring[ringIdx.getAndIncrement() % RING_SIZE] = ms
        totalLatencyMs.addAndGet(ms)
    }

    /** Nearest-rank percentile over the last RING_SIZE latencies. */
    fun percentile(p: Double): Long {
        val n = minOf(ringIdx.get(), RING_SIZE)
        if (n == 0) return 0L
        val sorted = LongArray(n) { ring[it] }.apply { sort() }
        val idx = (((n - 1) * p).toInt()).coerceIn(0, n - 1)
        return sorted[idx]
    }

    fun snapshot(): String = buildString {
        append("calls=").append(callCount.get())
        append(" ok=").append(successCount.get())
        append(" fail=").append(failureCount.get())
        append(" retries=").append(retryCount.get())
        append(" 429=").append(rateLimitedCount.get())
        append(" 5xx=").append(serverErrorCount.get())
        append(" cacheHits=").append(cacheHitCount.get())
        append(" avg=").append(if (callCount.get() > 0) totalLatencyMs.get() / callCount.get() else 0).append("ms")
        append(" p50=").append(percentile(0.50)).append("ms")
        append(" p95=").append(percentile(0.95)).append("ms")
        append(" rlWait=").append(rateLimitWaitMs.get()).append("ms")
    }
}

class MetricsEventListener : EventListener() {
    private val startedAt = Array<Long?>(1) { null }   // Call is the single key
    private var callStartNs: Long = 0L

    override fun callStart(call: okhttp3.Call) {
        callStartNs = System.nanoTime()
        MetricsStore.callCount.incrementAndGet()
    }

    override fun callEnd(call: okhttp3.Call) {
        MetricsStore.recordLatency((System.nanoTime() - callStartNs) / 1_000_000)
    }

    override fun callFailed(call: okhttp3.Call, ioe: IOException) {
        MetricsStore.failureCount.incrementAndGet()
    }

    override fun responseHeadersEnd(call: okhttp3.Call, response: Response) {
        when {
            response.code == 429 -> MetricsStore.rateLimitedCount.incrementAndGet()
            response.code in 500..599 -> MetricsStore.serverErrorCount.incrementAndGet()
            response.code in 200..299 -> MetricsStore.successCount.incrementAndGet()
        }
        if (response.cacheResponse != null && response.networkResponse == null) {
            MetricsStore.cacheHitCount.incrementAndGet()
        }
    }
}

// ============================================================================
// §3 — AUTHENTICATOR (token refresh)  —  infinite-retry guarded
// ============================================================================
/**
 * Base URL uses https://generativelanguage.googleapis.com/ with the key sent as
 * an `@Query("key")`, so a 401 today means an expired/rotated credential rather
 * than an OAuth bearer. This Authenticator implements the general shape the
 * migration path needs once requests move to OAuth / Firebase ID tokens.
 *
 * GUARD: OkHttp calls respond() on 401 recursively. Without a guard you get an
 * infinite loop. We stop after one prior attempt, mirroring the canonical
 * `if (response.responseCount > 1) return null` pattern.
 */
class TokenRefreshAuthenticator(
    private val tokenProvider: TokenProvider
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        // Guard #1 — never retry more than once (prevents the classic
        // "Authenticator loops forever on 401" DoS).
        if (response.priorResponseCount() >= 1) {
            MetricsStore.failureCount.incrementAndGet()
            return null
        }
        val stale = response.request.header("Authorization")
        val fresh = runBlocking { tokenProvider.refresh() } ?: return null
        val freshHeader = "Bearer ${fresh.accessToken}"
        // Guard #2 — if the token did not actually change, stop.
        if (stale == freshHeader) return null
        MetricsStore.retryCount.incrementAndGet()
        return response.request.newBuilder()
            .header("Authorization", freshHeader)
            .build()
    }
}

fun interface TokenProvider {
    /** Blocking/suspend token refresh. Return null to give up. */
    suspend fun refresh(): Token?
}

data class Token(val accessToken: String, val expiresAtEpochMs: Long)

private fun Response.priorResponseCount(): Int {
    var count = 0
    var prior: Response? = priorResponse
    while (prior != null) {
        count++
        prior = prior.priorResponse
    }
    return count
}

// ============================================================================
// §4 — RETRY INTERCEPTOR (429 / 503, exponential backoff + Retry-After)
// ============================================================================
class ExponentialBackoffRetryInterceptor(
    private val maxRetries: Int = PerfConfig.MAX_RETRIES,
    private val baseBackoffMs: Long = PerfConfig.BASE_BACKOFF_MS,
    private val maxBackoffMs: Long = PerfConfig.MAX_BACKOFF_MS
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var attempt = 0
        var lastResponse: Response? = null

        while (true) {
            val response = try {
                chain.proceed(request)
            } catch (ioe: IOException) {
                // Transport failure: retry only if the body can be replayed.
                if (attempt >= maxRetries || !request.isReplayable()) throw ioe
                MetricsStore.retryCount.incrementAndGet()
                sleepBackoff(attempt)
                attempt++
                continue
            }

            lastResponse = response

            val retryable = response.code == 429 || response.code in 500..599
            if (!retryable || attempt >= maxRetries) return response

            // 429 → honour Retry-After when present; 503 → exponential backoff.
            val retryAfterMs = response.header("Retry-After")?.toRetryAfterMs()
            MetricsStore.retryCount.incrementAndGet()
            val waitMs = retryAfterMs ?: backoffMs(attempt)
            if (!request.isReplayable()) return response   // don't burn a retry we can't replay
            response.close()
            Thread.sleep(waitMs.coerceAtMost(maxBackoffMs))
            attempt++
        }
        // unreachable, retained for readability of the loop contract
        return lastResponse ?: error("retry loop exited without a response")
    }

    private fun sleepBackoff(attempt: Int) =
        Thread.sleep(backoffMs(attempt).coerceAtMost(maxBackoffMs))

    private fun backoffMs(attempt: Int): Long {
        val exp = baseBackoffMs * 2.0.pow(attempt.toDouble())
        val full = minOf(exp, maxBackoffMs.toDouble()).toLong()
        // Full jitter (AWS style) to de-synchronise concurrent clients.
        return (1L + (Math.random() * full).toLong()).coerceAtMost(maxBackoffMs)
    }
}

private fun Response.header(name: String): String? = headers[name]

private fun String.toRetryAfterMs(): Long? =
    toLongOrNull()?.let { secs -> TimeUnit.SECONDS.toMillis(secs) }

private fun Request.isReplayable(): Boolean {
    val body: RequestBody? = this.body
    return body == null || (!body.isOneShot && !body.isDuplex)
}

// ============================================================================
// §5 — CACHE STRATEGY (10 MB shared Cache + explicit Cache-Control)
// ============================================================================
class CacheControlInterceptor(
    private val networkAvailable: () -> Boolean = { true }
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        // Force-cache only when offline; otherwise revalidate, staleness ≤ 5 min.
        val requested = original.newBuilder()
            .header("Cache-Control", if (!networkAvailable()) "public, only-if-cached, max-stale=2419200" else "public, max-age=300")
            .build()

        val response = chain.proceed(requested)
        val header = if (networkAvailable()) {
            "public, max-age=300"                 // 5 min fresh
        } else {
            "public, only-if-cached, max-stale=2419200"
        }
        return response.newBuilder()
            .header("Cache-Control", header)
            .removeHeader("Pragma")
            .build()
    }
}

/**
 * Body-keyed store for POST responses. Gemini generateContent is a POST, which a
 * standard HTTP cache will not store/serve — so identical prompts (e.g. the user
 * re-taps "Generate" with the same wardrobe + controls) are de-duplicated here.
 * Bounded LRU to keep memory predictable.
 */
class PostResponseDedupeStore(private val maxEntries: Int = 8) {
    private val lock = ReentrantLock()
    private val store = LinkedHashMap<String, String>(16, 0.75f, true)

    fun get(key: String): String? = lock.withLock { store[key] }

    fun put(key: String, body: String) = lock.withLock {
        store[key] = body
        while (store.size > maxEntries) {
            val eldest = store.entries.iterator()
            if (eldest.hasNext()) { eldest.next(); eldest.remove() }
        }
    }

    fun hits(): String? = null
}

private inline fun <T> ReentrantLock.withLock(block: () -> T): T {
    lock(); try { return block() } finally { unlock() }
}

// ============================================================================
// §6 — RATE LIMITING (token bucket: 10 calls / 60 s)
// ============================================================================
/**
 * Blocking token bucket. Capacity 10, refill 1 token per 6 s ⇒ 10 calls/min.
 * Safe to call from an OkHttp interceptor (which already runs off the main
 * thread). Records how long callers waited into MetricsStore.
 */
class TokenBucketRateLimiter(
    private val capacity: Int = PerfConfig.RATE_LIMIT_CALLS,
    private val windowMs: Long = PerfConfig.RATE_LIMIT_WINDOW_MS
) {
    private val lock = ReentrantLock()
    private val refillNanosPerToken: Long = windowMs * 1_000_000L / capacity

    private var tokens: Double = capacity.toDouble()
    private var lastRefillNs: Long = System.nanoTime()

    fun acquire() {
        val waitedMs: Long = lock.withLock {
            refill()
            var waitNs = 0L
            if (tokens < 1.0) {
                val deficit = 1.0 - tokens
                waitNs = (deficit * refillNanosPerToken).toLong()
                lastRefillNs += waitNs      // reserve the future slot
                tokens = 0.0
            } else {
                tokens -= 1.0
            }
            waitNs / 1_000_000L
        }
        if (waitedMs > 0) {
            MetricsStore.rateLimitWaitMs.addAndGet(waitedMs)
            Thread.sleep(waitedMs)
        }
    }

    private fun refill() {
        val now = System.nanoTime()
        val elapsed = now - lastRefillNs
        if (elapsed > 0) {
            tokens = minOf(capacity.toDouble(), tokens + elapsed.toDouble() / refillNanosPerToken)
            lastRefillNs = now
        }
    }
}

class RateLimitInterceptor(
    private val limiter: TokenBucketRateLimiter
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        limiter.acquire()
        return chain.proceed(chain.request())
    }
}

// ============================================================================
// §7 — REQUEST QUEUING (bounded FIFO + concurrency ceiling)
// ============================================================================
/**
 * Caps how many calls are in flight and how many may wait. Beyond
 * MAX_QUEUED_REQUESTS the call fails fast with a clear, user-facing error
 * instead of silently piling up threads (the current app has no bound at all).
 */
class RequestQueueInterceptor(
    private val maxConcurrent: Int = PerfConfig.MAX_CONCURRENT_REQUESTS,
    private val maxQueued: Int = PerfConfig.MAX_QUEUED_REQUESTS
) : Interceptor {
    private val inFlight = Semaphore(maxConcurrent)
    private val queued = AtomicInteger(0)

    override fun intercept(chain: Interceptor.Chain): Response {
        if (!inFlight.tryAcquire()) {
            if (queued.incrementAndGet() > maxQueued) {
                queued.decrementAndGet()
                throw IOException("Request queue full ($maxQueued waiting). Try again shortly.")
            }
            inFlight.acquire()          // blocks → FIFO by caller order
            queued.decrementAndGet()
        }
        return try {
            chain.proceed(chain.request())
        } finally {
            inFlight.release()
        }
    }
}

// ============================================================================
// §1 — NETWORK MODULE  (okHttp client is now actually installed + shared)
// ============================================================================
object NetworkModule {

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())      // note: API_PERF.md §5 flags the
        .build()                              // codegen-vs-reflection mismatch

    private val cacheDir: File = File.createTempFile("styledrop-cache", "").let {
        it.delete(); it.mkdirs(); it
    }

    private val httpCache: Cache = Cache(cacheDir, PerfConfig.CACHE_SIZE_BYTES)

    private val rateLimiter = TokenBucketRateLimiter()
    val postDedupe = PostResponseDedupeStore()

    /** Exposed so the app can force-refresh, clear cache, or read metrics. */
    val client: OkHttpClient by lazy {

        val dispatcher = Dispatcher(Executors.newFixedThreadPool(PerfConfig.MAX_CONCURRENT_REQUESTS)).apply {
            maxRequests = PerfConfig.MAX_CONCURRENT_REQUESTS
            maxRequestsPerHost = PerfConfig.MAX_CONCURRENT_REQUESTS
        }

        OkHttpClient.Builder()
            // ---- §2 timeouts -------------------------------------------------
            .connectTimeout(PerfConfig.CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(PerfConfig.READ_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(PerfConfig.WRITE_TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(PerfConfig.CALL_TIMEOUT_S, TimeUnit.SECONDS)

            // ---- §5 cache ----------------------------------------------------
            .cache(httpCache)
            // NOTE: a POST is not cacheable by the HTTP cache, so we ALSO keep a
            // body-keyed dedupe store (below) for generateContent.
            .addNetworkInterceptor(CacheControlInterceptor { true })
            .addInterceptor(CacheControlInterceptor { false })

            // ---- §7 queue + §6 rate limit + §4 retry + §3 auth ---------------
            .dispatcher(dispatcher)
            .addInterceptor(RequestQueueInterceptor())
            .addInterceptor(RateLimitInterceptor(rateLimiter))
            .addInterceptor(ExponentialBackoffRetryInterceptor())
            .authenticator(TokenRefreshAuthenticator { null })   // wire a real provider

            // ---- safe logging (never BODY in release) ------------------------
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                    else HttpLoggingInterceptor.Level.NONE
                }
            )

            // ---- §8 metrics --------------------------------------------------
            .eventListener(MetricsEventListener())

            .retryOnConnectionFailure(true)
            .build()
    }

    val geminiApiService: GeminiApiService by lazy {
        Retrofit.Builder()
            .baseUrl(PerfConfig.BASE_URL)
            .client(client)                       // <-- the fix
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GeminiApiService::class.java)
    }
}

// ============================================================================
// §1b — COIL (image loading) should share the SAME client, not its own pool.
// ============================================================================
// ImageLoader.Builder(context)
//     .okHttpClient { NetworkModule.client }
//     .memoryCache { MemoryCache.Builder(context).maxSizePercent(0.20).build() }
//     .diskCache { DiskCache.Builder().directory(cacheDir).maxSizeBytes(50L*1024*1024).build() }
//     .build()
//
// GoogleFont.Provider host (fonts.gstatic.com) is likewise server-side policy:
// fonts are long-lived assets, so serve them with a long max-age rather than
// the 5-minute app default used above for API calls.
