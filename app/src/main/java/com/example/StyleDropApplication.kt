package com.example

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import org.conscrypt.Conscrypt
import java.security.Security

/**
 * SECURITY (Agent #14) — item 1 (App Check) + item 9 (crypto provider).
 *
 * This Application class exists for one reason: App Check must be installed
 * BEFORE any Firebase AI Logic request is made. Without it the client would be
 * an un-attested caller and the backend would accept anonymous traffic.
 */
class StyleDropApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // ---- item 9: modern TLS/crypto provider with CT-aware TrustManager ----
        Security.insertProviderAt(Conscrypt.newProvider(), 1)

        FirebaseApp.initializeApp(this)

        val factory = if (BuildConfig.DEBUG) {
            // Debug builds: token is printed to logcat and registered in the
            // Firebase console. Never ship this provider to production.
            DebugAppCheckProviderFactory.getInstance()
        } else {
            // Release builds: hardware-backed Play Integrity attestation.
            PlayIntegrityAppCheckProviderFactory.getInstance()
        }
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(factory)

        // Warm the token so the first AI call is not delayed by attestation.
        FirebaseAppCheck.getInstance().getAppCheckToken(false)
            .addOnFailureListener { e -> Log.w(TAG, "App Check token warm-up failed", e) }
    }

    private companion object {
        const val TAG = "StyleDropApp"
    }
}
