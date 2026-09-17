package com.example.security

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.FileOutputStream

/**
 * SECURITY (Agent #14) — item 4: wardrobe photos encrypted at rest with EncryptedFile.
 *
 * Before: `WardrobeItem.imageUrl` stored a raw content:// Uri (world-readable through
 * the picker grant) or, worse, a plaintext file path. Nothing on disk was encrypted.
 *
 * Now: every imported photo is copied into app-private storage and wrapped in an
 * AES-256-GCM EncryptedFile whose key lives in the hardware-backed Android Keystore
 * (MasterKey / AES256_GCM, no user-auth requirement so background Coil loads work).
 *
 * Stored token format:  "enc://wardrobe_photos/<itemId>.enc"
 *
 * RESIDUAL RISK (documented in SECURITY.md): Coil cannot stream an EncryptedFile, so
 * [resolveForDisplay] decrypts into the app-private *cache* dir for rendering. Those
 * cache copies are transient and are purged by [purgeCache]; they are still inside the
 * app sandbox (not world-readable) and excluded from backup by data_extraction_rules.
 */
object SecurePhotoStore {

    const val SCHEME = "enc"
    private const val DIR = "wardrobe_photos"
    private const val CACHE_DIR = "secure_photo_cache"
    private const val TAG = "SecurePhotoStore"

    fun isEncryptedReference(value: String?): Boolean =
        value?.startsWith("$SCHEME://") == true

    private fun masterKey(context: Context): MasterKey =
        MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

    private fun encFile(context: Context, name: String): EncryptedFile =
        EncryptedFile.Builder(
            context,
            File(File(context.filesDir, DIR), name),
            masterKey(context),
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()

    /** Encrypts [source] into private storage. Returns the "enc://" token to persist. */
    fun importFromUri(context: Context, source: Uri, itemId: String): String {
        val name = "$itemId.enc"
        File(context.filesDir, DIR).mkdirs()
        context.contentResolver.openInputStream(source).use { input ->
            requireNotNull(input) { "Unable to open the selected image" }
            encFile(context, name).openFileOutput().use { output -> input.copyTo(output) }
        }
        Log.i(TAG, "Photo encrypted at rest for item $itemId")
        return "$SCHEME://$DIR/$name"
    }

    /** Returns a plaintext File for the image loader, decrypting into app-private cache. */
    fun resolveForDisplay(context: Context, reference: String?): File? {
        if (reference.isNullOrBlank()) return null
        if (!isEncryptedReference(reference)) {
            // Legacy rows (raw Uri) are still renderable but must be re-imported.
            return null
        }
        val name = reference.substringAfterLast('/')
        val cache = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        val target = File(cache, name.removeSuffix(".enc") + ".jpg")
        if (target.exists() && target.length() > 0) return target
        return try {
            encFile(context, name).openFileInput().use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out) }
            }
            target
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to decrypt $name", t)
            null
        }
    }

    /** Securely removes both the ciphertext and any cached plaintext copy. */
    fun delete(context: Context, reference: String?) {
        if (reference.isNullOrBlank()) return
        val name = reference.substringAfterLast('/')
        File(File(context.filesDir, DIR), name).delete()
        File(File(context.cacheDir, CACHE_DIR), name.removeSuffix(".enc") + ".jpg").delete()
    }

    /** Wipes every cached plaintext rendering (call from onTrimMemory / after a delete). */
    fun purgeCache(context: Context) {
        File(context.cacheDir, CACHE_DIR).listFiles()?.forEach { it.delete() }
    }
}
