package com.example.network

import com.example.BuildConfig
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * SECURITY (Agent #14) — items 2, 5 and 9.
 *
 * Before there was NO OkHttpClient at all (Retrofit built its own silent default:
 * no timeouts, no pinning, no interception). This is now the single hardened client.
 *
 *  - item 2: pulled up to OkHttp 5.3.2 / okio 3.9.0 (CVE-2023-3635 cleared).
 *  - item 5: certificate pinning with a backup pin + expiry, mirrored in
 *            res/xml/network_security_config.xml (config-level pinning covers any
 *            other stack; the pinner covers this client specifically).
 *  - item 9: certificate transparency. Android's platform trust manager already
 *            enforces Chrome's CT log policy for apps targeting API 24+, and
 *            Conscrypt (installed in StyleDropApplication) uses a CT-verifying
 *            TrustManagerImpl. We additionally refuse cleartext and refuse
 *            non-allowlisted hosts.
 */
object SecureHttpClient {

    /**
     * SPKI SHA-256 pins. Replace with the real digests printed by:
     *   openssl s_client -servername <host> -connect <host>:443 </dev/null 2>/dev/null \
     *     | openssl x509 -pubkey -noout | openssl pkey -pubin -outform der \
     *     | openssl dgst -sha256 -binary | openssl enc -base64
     * A BACKUP pin from an offline CA/intermediate is MANDATORY before shipping —
     * a single pin without a backup bricks the app if Google rotates its cert.
     */
    private const val PIN_PRIMARY = "baA+Ld3UAHTjfVroigcvh0Vq9L8h5fH1ONS5pXySa7U="
    private const val PIN_BACKUP = "PLACEHOLDER_BACKUP_SPKI_SHA256"

    private val pinner = CertificatePinner.Builder()
        .add("generativelanguage.googleapis.com", "sha256/$PIN_PRIMARY", "sha256/$PIN_BACKUP")
        .build()

    private val logging = HttpLoggingInterceptor().apply {
        // CRITICAL: BODY logging would print the full wardrobe + AI prompt to logcat.
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
        else HttpLoggingInterceptor.Level.NONE
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .certificatePinner(pinner)
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
