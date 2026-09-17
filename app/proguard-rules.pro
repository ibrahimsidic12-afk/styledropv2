# ============================================================================
# SECURITY (Agent #14) — item 8: R8/ProGuard rules.
#
# Why this file is load-bearing: before this change `isMinifyEnabled = false` and
# every rule below was commented out. Turning on R8 WITHOUT these keeps would have
# broken Moshi (reflection), Room, Retrofit and Firebase at runtime. Shrinking +
# obfuscation is now ON, so the rules are mandatory.
# ============================================================================

# ---- keep source/line info for readable crash reports in Play Console ----
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- Kotlin metadata / coroutines ----
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, EnclosingMethod
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ---- Moshi: reflection adapter (KotlinJsonAdapterFactory) + codegen ----
# Without these, KotlinJsonAdapterFactory cannot find the synthetic
# `$serializer`/JsonAdapter members and every DTO fails to deserialize.
-keep class com.squareup.moshi.** { *; }
-keep interface com.squareup.moshi.** { *; }
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <methods>;
}
-keep @com.squareup.moshi.JsonQualifier @interface *
-keep @com.squareup.moshi.JsonClass class * { *; }
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}
-keepnames @com.squareup.moshi.JsonClass class *
# Generated adapters (moshi-kotlin-codegen via KSP)
-if @com.squareup.moshi.JsonClass class **$*
-keep class <1>_JsonAdapter { *; }
-dontwarn com.squareup.moshi.**
# App DTOs are already superseded by Firebase AI Logic, but keep them safe.
-keep class com.example.network.** { *; }

# ---- Retrofit / OkHttp ----
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- Room ----
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ---- androidx.security EncryptedFile / MasterKey (Tink + Keystore reflection) ----
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.api.client.http.**
-dontwarn com.google.api.client.googleapis.**

# ---- androidx.biometric ----
-keep class androidx.biometric.** { *; }
-keep class android.hardware.biometrics.** { *; }

# ---- Firebase / App Check / AI Logic (all reflection-driven) ----
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-keep class com.google.android.play.core.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**
-dontwarn com.google.android.play.core.**
-keepnames class com.example.StyleDropApplication

# ---- App model classes referenced by name in SQL/annotations ----
-keep class com.example.models.** { *; }
-keep class com.example.data.** { *; }

# ---- strip logging in release (no wardrobe/AI data in logcat) ----
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}
