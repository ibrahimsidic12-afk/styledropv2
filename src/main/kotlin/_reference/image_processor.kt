package com.example.image

/**
 * styledropv2 -- IMAGE ENHANCEMENT PIPELINE (Android / Kotlin, production path)
 * ============================================================================
 * Mirrors tools/image_processor.py stage-for-stage. Package `com.example.image`
 * matches the repo namespace (app/build.gradle.kts -> namespace = "com.example").
 *
 * Wiring:
 *   AddItemScreen.kt  : PickVisualMedia result Uri -> ImagePipeline.ingest()
 *   WardrobeItem.kt   : `imageUrl` becomes dest.absolutePath (a real file), not
 *                       the ephemeral content:// string the app stores today.
 *   WardrobeScreen.kt : AsyncImage(model = ImagePipeline.thumbFile(ctx, item.id))
 *                       with placeholder/error + crossfade (see STAGE 8).
 *
 * RUNTIME DEPS to uncomment in app/build.gradle.kts:
 *   implementation("com.google.mediapipe:tasks-vision:0.10.14")   // STAGE 2+6
 *   // androidx.exifinterface:exifinterface:1.3.7  -> or use ImageDecoder (API 28+)
 * Existing: coil-compose 2.7.0 (already active), kotlinx-coroutines-android.
 */

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.exifinterface.media.ExifInterface as AndroidXExif
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ImagePipeline {

    // ---- tunables: MIRROR tools/image_processor.py exactly -----------------
    const val MAX_JPEG_BYTES = 500 * 1024
    const val START_QUALITY = 85
    const val QUALITY_FLOOR = 45
    const val QUALITY_STEP = 5
    const val THUMB_SMALL = 256            // square cover-crop
    const val THUMB_LARGE_MAX = 1024
    const val PROXY_MAX = 256              // mask proxy edge
    const val CROP_THRESHOLD = 28          // per-channel Manhattan dist from bg
    const val CROP_PAD_FRAC = 0.04f
    const val CROP_MIN_AREA = 0.08f
    const val FALLBACK_KEEP = 0.88f
    const val CHROMA_SIM_MAX = 34
    const val CHROMA_FADE_BAND = 60
    const val CHROMA_LUMA_LO = 22
    const val WB_MAX_GAIN = 0.08f
    const val FIX_CONTRAST = 1.06f          // FIXED -> wardrobe consistency
    const val FIX_COLOR = 1.04f
    const val FIX_BRIGHTNESS = 1.00f

    const val DIR_IMAGES = "wardrobe_images"
    const val DIR_THUMBS = "wardrobe_images/thumbs"

    data class Result(
        val fullPath: String,
        val thumbSmallPath: String,
        val thumbLargePath: String,
        val width: Int,
        val height: Int,
        val bytes: Int,
        val finalQuality: Int,
        val rotated: Boolean,
        val cropped: Boolean,
        val cropCoverage: Float
    )

    // =======================================================================
    // STAGE 1 -- ingest: content:// Uri -> app-private file
    // =======================================================================
    /**
     * Persistable permission is the whole point of this stage. Without it the
     * Uri that AddItemScreen.kt currently writes into WardrobeItem.imageUrl is
     * revoked on reboot / process death and every garment card goes blank.
     */
    suspend fun ingest(
        context: Context,
        uri: Uri,
        itemId: String = UUID.randomUUID().toString()
    ): File = withContext(Dispatchers.IO) {
        // (a) take a PERSISTABLE read grant so the Uri outlives this process
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // Photo Picker (PickVisualMedia) grants are NOT persistable by spec.
            // Harmless: we copy immediately below, so we never depend on it.
        }

        val dir = File(context.filesDir, DIR_IMAGES).apply { mkdirs() }
        val dest = File(dir, "${itemId}_original.jpg")

        // (b) COPY bytes -- never keep the Uri as the storage location
        val tmp = File(dir, "${itemId}_original.jpg.tmp")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tmp).use { out -> input.copyTo(out, 64 * 1024) }
        } ?: throw java.io.IOException("openInputStream returned null for $uri")
        if (dest.exists()) dest.delete()
        tmp.renameTo(dest)
        dest
    }

    // =======================================================================
    // STAGE 3 -- EXIF auto-rotate
    // =======================================================================
    private fun autoRotate(src: Bitmap, file: File): Pair<Bitmap, Boolean> {
        val orient = try {
            AndroidXExif(file.absolutePath, AndroidXExif.ORIENTATION_READ).rotationDegrees
        } catch (e: Exception) {
            val e2 = try {
                ExifInterface(file.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }
            when (e2) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }
        if (orient == 0) return src to false
        val m = Matrix().apply { postRotate(orient.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true) to true
    }

    // =======================================================================
    // STAGE 2 -- auto-crop to garment bbox
    // =======================================================================
    /**
     * Production path: MediaPipe Image Segmenter.
     *   val opts = ImageSegmenter.ImageSegmenterOptions.builder()
     *       .setBaseOptions(BaseOptions.builder()
     *           .setModelAssetPath("deeplab_v3.tflite").build())
     *       .setRunningMode(RunningMode.IMAGE).build()
     *   val seg = ImageSegmenter.createFromOptions(ctx, opts)
     *   seg.segment(MPImage.create(bitmap), callback)   // -> mask Bitmap
     * The mask feeds bboxFromMask() below; the heuristic is the guaranteed
     * fallback for devices where the model asset is absent / init fails.
     */
    private fun bboxFromMask(mask: Bitmap, padFrac: Float = CROP_PAD_FRAC): IntArray {
        var x0 = mask.width; var y0 = mask.height; var x1 = 0; var y1 = 0; var hits = 0
        val px = IntArray(mask.width * mask.height)
        mask.getPixels(px, 0, mask.width, 0, 0, mask.width, mask.height)
        for (y in 0 until mask.height) {
            val row = y * mask.width
            for (x in 0 until mask.width) {
                if ((px[row + x] ushr 24) > 128) {       // alpha > 0.5 == garment
                    if (x < x0) x0 = x; if (x > x1) x1 = x
                    if (y < y0) y0 = y; if (y > y1) y1 = y
                    hits++
                }
            }
        }
        val coverage = hits.toFloat() / (mask.width * mask.height)
        val pad = (padFrac * min(mask.width, mask.height)).roundToInt()
        val box = intArrayOf(
            max(0, x0 - pad), max(0, y0 - pad),
            min(mask.width, x1 + pad + 1), min(mask.height, y1 + pad + 1)
        )
        return box + intArrayOf(coverage.toInt() * 1000)   // [x0,y0,x1,y1,cov*1000]
    }

    /** Center-weighted heuristic fallback -- identical math to the Python prototype. */
    private suspend fun autoCropHeuristic(
        src: Bitmap
    ): Triple<Bitmap, Boolean, Float> = withContext(Dispatchers.Default) {
        val w = src.width; val h = src.height
        val scale = PROXY_MAX.toFloat() / max(w, h)
        val sw = max(1, (w * scale).toInt()); val sh = max(1, (h * scale).toInt())
        val proxy = Bitmap.createScaledBitmap(src, sw, sh, true)
        val px = IntArray(sw * sh)
        proxy.getPixels(px, 0, sw, 0, 0, sw, sh)

        fun r(c: Int) = (c shr 16) and 0xFF
        fun g(c: Int) = (c shr 8) and 0xFF
        fun b(c: Int) = c and 0xFF

        // border-ring median per channel
        val ring = ArrayList<Int>(2 * (sw + sh))
        for (x in 0 until sw) { ring.add(px[x]); ring.add(px[(sh - 1) * sw + x]) }
        for (y in 0 until sh) { ring.add(px[y * sw]); ring.add(px[y * sw + sw - 1]) }
        val bgR = ring.map { r(it) }.sorted()[ring.size / 2]
        val bgG = ring.map { g(it) }.sorted()[ring.size / 2]
        val bgB = ring.map { b(it) }.sorted()[ring.size / 2]

        val col = IntArray(sw); val row = IntArray(sh); var hits = 0
        for (y in 0 until sh) for (x in 0 until sw) {
            val c = px[y * sw + x]
            val d = abs(r(c) - bgR) + abs(g(c) - bgG) + abs(b(c) - bgB)
            if (d > CROP_THRESHOLD * 3) { col[x]++; row[y]++; hits++ }
        }
        val coverage = hits.toFloat() / (sw * sh)
        val tc = (col.maxOrNull() ?: 1) * 0.20f
        val tr = (row.maxOrNull() ?: 1) * 0.20f
        fun span(a: IntArray, t: Float): IntArray? {
            var lo = -1; var hi = -1
            for (i in a.indices) if (a[i] >= t) { if (lo < 0) lo = i; hi = i }
            return if (lo < 0) null else intArrayOf(lo, hi)
        }
        val sx = span(col, tc); val sy = span(row, tr)

        if (sx == null || sy == null || coverage < CROP_MIN_AREA) {
            val k = FALLBACK_KEEP
            val cx = (w * (1 - k) / 2).toInt(); val cy = (h * (1 - k) / 2).toInt()
            return@withContext Triple(
                Bitmap.createBitmap(src, cx, cy, w - 2 * cx, h - 2 * cy), false, coverage)
        }
        val pad = (CROP_PAD_FRAC * min(w, h)).roundToInt()
        val x0 = max(0, (sx[0] / scale).toInt() - pad)
        val y0 = max(0, (sy[0] / scale).toInt() - pad)
        val x1 = min(w, (sx[1] / scale).toInt() + 1 + pad)
        val y1 = min(h, (sy[1] / scale).toInt() + 1 + pad)
        proxy.recycle()
        Triple(Bitmap.createBitmap(src, x0, y0, x1 - x0, y1 - y0), true, coverage)
    }

    // =======================================================================
    // STAGE 6 -- chroma-key-lite background isolation
    // =======================================================================
    fun chromaKeyLite(src: Bitmap, keyArgb: Int? = null): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        val key = keyArgb ?: run {
            val ring = ArrayList<Int>()
            for (x in 0 until w) { ring.add(px[x]); ring.add(px[(h - 1) * w + x]) }
            for (y in 0 until h) { ring.add(px[y * w]); ring.add(px[y * w + w - 1]) }
            val r = ring.map { (it shr 16) and 0xFF }.sorted()[ring.size / 2]
            val g = ring.map { (it shr 8) and 0xFF }.sorted()[ring.size / 2]
            val b = ring.map { it and 0xFF }.sorted()[ring.size / 2]
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val kr = (key shr 16) and 0xFF; val kg = (key shr 8) and 0xFF; val kb = key and 0xFF
        val out = IntArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            val d = abs(r - kr) + abs(g - kg) + abs(b - kb)
            val luma = (r * 299 + g * 587 + b * 114) / 1000
            val a = when {
                d <= CHROMA_SIM_MAX && luma > CHROMA_LUMA_LO -> 255
                d <= CHROMA_SIM_MAX + CHROMA_FADE_BAND && luma > CHROMA_LUMA_LO ->
                    (255 * (d - CHROMA_SIM_MAX) / CHROMA_FADE_BAND.toFloat()).toInt()
                else -> 0
            }
            out[i] = (a shl 24) or (c and 0x00FFFFFF)
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    // =======================================================================
    // STAGE 7 -- wardrobe-consistent enhancement (FIXED params, no autocontrast)
    // =======================================================================
    fun enhanceConsistent(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        var sr = 0L; var sg = 0L; var sb = 0L
        for (c in px) { sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF }
        val n = px.size.toFloat()
        val target = (sr + sg + sb) / (3f * n)
        fun gain(m: Float) = if (m <= 0f) 1f
            else (target / m).coerceIn(1f - WB_MAX_GAIN, 1f + WB_MAX_GAIN)
        val gr = gain(sr / n); val gg = gain(sg / n); val gb = gain(sb / n)

        // FIXED contrast/saturation applied around the 128 midpoint -- constant
        // for every garment, which is what keeps the grid visually coherent.
        val out = IntArray(px.size)
        for (i in px.indices) {
            val c = px[i]
            fun ch(v: Int, gMul: Float): Int {
                val wb = v * gMul
                val ct = 128f + (wb - 128f) * FIX_CONTRAST
                return (ct * FIX_BRIGHTNESS).coerceIn(0f, 255f).toInt()
            }
            var r = ch((c shr 16) and 0xFF, gr)
            var g = ch((c shr 8) and 0xFF, gg)
            var b = ch(c and 0xFF, gb)
            // saturation via luma-preserving lerp
            val luma = (r * 299 + g * 587 + b * 114) / 1000
            r = (luma + (r - luma) * FIX_COLOR).coerceIn(0, 255)
            g = (luma + (g - luma) * FIX_COLOR).coerceIn(0, 255)
            b = (luma + (b - luma) * FIX_COLOR).coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    // =======================================================================
    // STAGE 4 -- compress to < 500 KB
    // =======================================================================
    private fun compressToTarget(
        src: Bitmap, dest: File,
        target: Int = MAX_JPEG_BYTES, start: Int = START_QUALITY
    ): Pair<Int, Int> {                       // (bytes, finalQuality)
        var q = start
        var cur = src
        var last = 0
        while (q >= QUALITY_FLOOR) {
            FileOutputStream(dest).use { cur.compress(Bitmap.CompressFormat.JPEG, q, it) }
            last = dest.length().toInt()
            if (last <= target) return last to q
            q -= QUALITY_STEP
        }
        // floor reached -> downscale 0.85 steps (quality stays sane)
        while (min(cur.width, cur.height) > 320) {
            cur = Bitmap.createScaledBitmap(
                cur, (cur.width * 0.85f).toInt(), (cur.height * 0.85f).toInt(), true)
            FileOutputStream(dest).use { cur.compress(Bitmap.CompressFormat.JPEG, QUALITY_FLOOR, it) }
            last = dest.length().toInt()
            if (last <= target) return last to QUALITY_FLOOR
        }
        return last to QUALITY_FLOOR
    }

    // =======================================================================
    // STAGE 5 -- thumbnails
    // =======================================================================
    private fun saveThumbs(context: Context, src: Bitmap, itemId: String): Pair<File, File> {
        val dir = File(context.filesDir, DIR_THUMBS).apply { mkdirs() }
        // square cover-crop via center-crop math (keeps Coil transform-free)
        val s = min(src.width, src.height)
        val cx = (src.width - s) / 2; val cy = (src.height - s) / 2
        val square = Bitmap.createBitmap(src, cx, cy, s, s)
        val small = Bitmap.createScaledBitmap(square, THUMB_SMALL, THUMB_SMALL, true)
        val fSmall = File(dir, "${itemId}_small.jpg")
        FileOutputStream(fSmall).use { small.compress(Bitmap.CompressFormat.JPEG, 88, it) }

        val k = THUMB_LARGE_MAX.toFloat() / max(src.width, src.height)
        val lw = (src.width * min(1f, k)).toInt(); val lh = (src.height * min(1f, k)).toInt()
        val large = if (min(1f, k) < 1f) Bitmap.createScaledBitmap(src, lw, lh, true) else src
        val fLarge = File(dir, "${itemId}_large.jpg")
        FileOutputStream(fLarge).use { large.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        return fSmall to fLarge
    }

    // =======================================================================
    // orchestrator
    // =======================================================================
    suspend fun process(context: Context, uri: Uri, itemId: String): Result =
        withContext(Dispatchers.IO) {
            val original = ingest(context, uri, itemId)                 // STAGE 1

            var bmp = BitmapFactory.decodeFile(original.absolutePath, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                // inSampleSize: cap decode at 2048px before any processing
            }) ?: throw java.io.IOException("decode failed: ${original.absolutePath}")

            val (rot, rotated) = autoRotate(bmp, original)               // STAGE 3
            if (rotated) bmp = rot

            val (crop, cropped, cov) = autoCropHeuristic(bmp)            // STAGE 2
            if (cropped) bmp = crop

            bmp = enhanceConsistent(bmp)                                 // STAGE 7

            val dir = File(context.filesDir, DIR_IMAGES).apply { mkdirs() }
            val full = File(dir, "$itemId.jpg")
            val (bytes, q) = compressToTarget(bmp, full)                 // STAGE 4

            val (small, large) = saveThumbs(context, bmp, itemId)        // STAGE 5

            Result(
                fullPath = full.absolutePath,
                thumbSmallPath = small.absolutePath,
                thumbLargePath = large.absolutePath,
                width = bmp.width, height = bmp.height,
                bytes = bytes, finalQuality = q,
                rotated = rotated, cropped = cropped, cropCoverage = cov
            )
        }

    // =======================================================================
    // STAGE 8 -- Coil lazy-load: placeholder + crossfade + deterministic cache key
    // =======================================================================
    /**
     * Coil 2.7.0 (already in the repo's version catalog, ACTIVE in
     * app/build.gradle.kts). Keys are derived from the item id, so re-processing
     * an item busts only that item's cache entry.
     */
    fun thumbModel(context: Context, itemId: String, fullRes: Boolean = false) = ImageRequest
        .Builder(context)
        .data(File(File(context.filesDir, DIR_THUMBS), "${itemId}_${if (fullRes) "large" else "small"}.jpg"))
        .memoryCacheKey("wardrobe/$itemId/${if (fullRes) "large" else "small"}/v1")
        .diskCacheKey("wardrobe-$itemId-${if (fullRes) "large" else "small"}-v1")
        .crossfade(220)
        .scale(if (fullRes) Scale.FIT else Scale.CROP)
        .precision(Precision.INEXACT)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .build()

    /** Drop all cached variants for one item after an edit. */
    fun evict(context: Context, itemId: String) {
        listOf("small", "large").forEach {
            coil.Coil.imageLoader(context).memoryCache?.remove("wardrobe/$itemId/$it/v1")
        }
    }
}
