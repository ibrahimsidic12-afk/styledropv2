package com.example.ai

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend

/**
 * SECURITY (Agent #14) — item 1.
 *
 * All model traffic now goes through Firebase AI Logic. The request is authorized
 * by (a) the Firebase App Check attestation installed in StyleDropApplication and
 * (b) the project's server-side configuration. There is NO API key in the APK,
 * and no `key=` query parameter is ever constructed on the client.
 */
class StylistAiService(private val modelName: String = DEFAULT_MODEL) {

    private val model by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI())
            .generativeModel(modelName = modelName)
    }

    suspend fun generate(prompt: String): Result<String> = try {
        val text = model.generateContent(prompt).text
        if (text.isNullOrBlank()) {
            Result.failure(IllegalStateException("Model returned an empty response"))
        } else {
            Result.success(text)
        }
    } catch (t: Throwable) {
        // Do not surface raw server text to the UI; log locally only.
        Log.e(TAG, "AI request failed", t)
        Result.failure(t)
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-2.5-flash"
        private const val TAG = "StylistAiService"
    }
}
