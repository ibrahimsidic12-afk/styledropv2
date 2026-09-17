package com.example.network

import com.squareup.moshi.JsonClass
import retrofit2.http.Body
import retrofit2.http.POST

@JsonClass(generateAdapter = true)
data class GenerateContentRequest(
    val contents: List<Content>
)

@JsonClass(generateAdapter = true)
data class Content(
    val parts: List<Part>
)

@JsonClass(generateAdapter = true)
data class Part(
    val text: String
)

@JsonClass(generateAdapter = true)
data class GenerateContentResponse(
    val candidates: List<Candidate>?
)

@JsonClass(generateAdapter = true)
data class Candidate(
    val content: Content?
)

interface GeminiApiService {
    /**
     * SECURITY (Agent #14) item 1.
     *
     * The `@Query("key")` parameter is GONE. It shipped the Gemini key inside the
     * APK (readable from BuildConfig) and echoed it in every URL — where it lands in
     * proxies, crash reports and logcat. This interface is retained only for legacy
     * compatibility; production traffic now flows through
     * com.example.ai.StylistAiService (Firebase AI Logic + App Check), which holds
     * no key at all.
     *
     * Deprecated: do NOT add new call sites.
     */
    @Deprecated("Use StylistAiService (Firebase AI Logic + App Check). Never send an API key from the client.")
    @POST("v1beta/models/gemini-1.5-flash:generateContent")
    suspend fun generateContent(
        @Body request: GenerateContentRequest
    ): GenerateContentResponse
}
