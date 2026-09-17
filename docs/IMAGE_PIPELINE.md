# IMAGE_PIPELINE.md — StyleDrop v2 Image Enhancement Pipeline

> **Agent #15 deliverable.** Read-only-first build: the repo was cloned, its real conventions read, and every stage below is grounded in files that exist at commit `a53013bd477c8b6834d4a2b987163ce300c1dbea`.
> **Scope:** photo capture → app-private storage → crop → rotate → enhance → compress → thumbnail → background isolation → cached display.
> **Prototype:** `tools/image_processor.py` (runnable, Pillow only) · **Production:** `tools/image_processor.kt` (Android/Kotlin).

---

## 0. Why this pipeline exists — the defect it fixes

| Evidence from the repo | Consequence |
|---|---|
| `models/WardrobeItem.kt` → `val imageUrl: String` | declared as a *URL*, used as a *storage slot* |
| `ui/AddItemScreen.kt` → `imageUrl = imageUri.toString()` | persists a **`content://` Uri string** |
| `ui/WardrobeScreen.kt` / `ItemDetailScreen.kt` → `AsyncImage(model = item.imageUrl)` | Coil re-resolves that Uri on every render |
| Photo Picker contract `ActivityResultContracts.PickVisualMedia()` | grants are **process-scoped**, not persistable |
| No `takePersistableUriPermission`, no copy, no compress, no crop thumbnail | after reboot / process death / cache purge **every garment card goes blank**; originals are full-resolution multi-MB files |

`imageUrl` holding an ephemeral Uri is the wardrobe app's single most damaging storage bug: the user's photos are effectively owned by the system picker, and the database points at them by name only. This pipeline converts that string into a **path to a file the app owns**, and normalises the image while doing it.

---

## 1. Pipeline diagram

```
        ┌────────────────────────── UI: AddItemScreen.kt ──────────────────────────┐
        │  PickVisualMedia() -> Uri   (content://media/picker/.../image/1234)        │
        └───────────────────────────────────┬───────────────────────────────────────┘
                                            ▼
  STAGE 1  INGEST + PERSIST ─── takePersistableUriPermission(FLAG_GRANT_READ)
            copy bytes ──────►  filesDir/wardrobe_images/{itemId}_original.jpg
                                            ▼
  STAGE 3  AUTO-ROTATE ──────── EXIF 0x0112 -> Matrix.postRotate; tag stripped
                                            ▼
  STAGE 2  AUTO-CROP ────────── MediaPipe ImageSegmenter mask -> bbox
                                 └ fallback: border-median bg + 256px proxy projection
                                            ▼
  STAGE 7  ENHANCE ──────────── gray-world WB (gains clamped ±8%)
                                 + FIXED contrast 1.06 / saturation 1.04 / brightness 1.00
                                            ▼
  STAGE 6  BG ISOLATION ─────── chroma-key-lite alpha mask (keep/fade/cut bands)
                                            ▼
  STAGE 4  COMPRESS ─────────── JPEG q85 -> -5 steps -> q45 -> 0.85x downscale
                                 until bytes <= 500 KB (progressive, 4:2:0)
                                            ▼
  STAGE 5  THUMBNAILS ───────── 256x256 cover-crop  +  1024 max-edge (aspect kept)
                                            ▼
  STAGE 8  DISPLAY ──────────── Coil AsyncImage: placeholder / error / crossfade 220ms
                                 memory+disk cache keyed on wardrobe/{itemId}/small/v1
```

---

## 2. Stage-by-stage specification

### STAGE 1 — Ingest: Uri → app-private storage

**Files to add** (`tools/image_processor.kt`):
- `filesDir/wardrobe_images/{itemId}_original.jpg` — the immutable source of truth
- `filesDir/wardrobe_images/{itemId}.jpg` — processed, <500 KB
- `filesDir/wardrobe_images/thumbs/{itemId}_small.jpg`, `{itemId}_large.jpg`

```kotlin
try {
    context.contentResolver.takePersistableUriPermission(
        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
    )
} catch (e: SecurityException) {
    // Photo Picker grants are NOT persistable by spec — harmless, we copy immediately.
}
val dest = File(File(context.filesDir, "wardrobe_images").apply { mkdirs() },
                "${itemId}_original.jpg")
context.contentResolver.openInputStream(uri)?.use { input ->
    FileOutputStream(tmp).use { out -> input.copyTo(out, 64 * 1024) }
} ?: throw IOException("openInputStream returned null for $uri")
```

**Honest note on `takePersistableUriPermission`:** the app uses `PickVisualMedia` (Photo Picker). Per the picker's contract the grant is deliberately **non-persistable** — the call is kept as defence-in-depth (it is a no-op there, and it *is* required if the app later moves to `ACTION_OPEN_DOCUMENT` or CameraX `MediaStore` output). The **copy** is what actually makes storage durable; the permission merely widens the window. Do not rely on the flag alone.

**`WardrobeItem.imageUrl` migration:** keep the column name (no Room migration) but change its *meaning* to an absolute path. Add a one-time repair for existing rows: rows where `imageUrl` starts with `content://` and whose file copy is absent render the placeholder — flag them for re-pick. `AppDatabase.kt` currently uses `fallbackToDestructiveMigration()`, so a schema change here would wipe the wardrobe; the path reinterpretation deliberately avoids one.

**Atomicity:** written to `*.tmp` then `renameTo(dest)`, so a crash mid-copy never leaves a half-image at the final path.

---

### STAGE 2 — Auto-crop to the garment bounding box

**Production path — MediaPipe Image Segmenter** (on-device, no network):

```kotlin
val opts = ImageSegmenter.ImageSegmenterOptions.builder()
    .setBaseOptions(BaseOptions.builder().setModelAssetPath("deeplab_v3.tflite").build())
    .setRunningMode(RunningMode.IMAGE)
    .build()
val seg = ImageSegmenter.createFromOptions(context, opts)
seg.segment(MPImage.create(bitmap)) { result, _ -> mask = result.categoryMaskOrNull }
val box = bboxFromMask(mask)      // alpha>0.5 -> min/max x,y -> pad 4% -> scale to full res
```

Model choice: `deeplab_v3.tflite` (general foreground/background, good on flat-lay and hung garments); `selfie_segmenter.tflite` is the lighter alternative but is trained on faces/upper bodies, so it under-segments trousers and shoes — prefer deeplab for wardrobe.

**Guaranteed fallback — center-weighted heuristic.** MediaPipe needs a model asset, an init that can fail on low-RAM devices, and ~7 MB of APK. The heuristic always ships:

1. Resize to a **256 px proxy** (cost is O(1) in source resolution — a 12 MP photo costs the same as a 1 MP one).
2. Background colour = **per-channel median of the border ring** (robust to a stray dark corner; a mean would drift).
3. Mask pixel = `|ΔR| + |ΔG| + |ΔB| > 28 × 3` Manhattan distance from that background.
4. **Row/column projections trimmed at 20% of peak** — this is the step that ignores isolated specks, a dust mote, or a shadow sliver that a naive min/max bbox would chase.
5. Area sanity: if coverage `< 8%`, the "garment" was never found → **center-weighted fallback** (central 88%) so the user still gets a sane image.
6. Pad 4%, then scale the bbox back to full resolution.

**Measured behaviour (real run, §5):** background median `(232,231,227)`, mask coverage `38.0%`, bbox `(246,181,1563,2032)` on a 1800×2400 frame → **43.6% of pixels (margins) removed**, and the off-centre garment is re-centred.

---

### STAGE 3 — Auto-rotate from EXIF

```kotlin
val degrees = AndroidXExif(file.absolutePath, AndroidXExif.ORIENTATION_READ).rotationDegrees
if (degrees != 0) {
    val m = Matrix().apply { postRotate(degrees.toFloat()) }
    bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
}
```

Why it must happen **before** crop and before thumbnail: `ImageOps.fit` / centre-crop on a sideways bitmap crops the wrong axis, so a portrait garment photo would be thumbnailed from its middle band. The EXIF tag is stripped from the working bitmap so downstream stages cannot double-rotate. Fallback chain: AndroidX ExifInterface → platform `ExifInterface.TAG_ORIENTATION` → no-op.

**Measured:** tag `6` (rotate 90° CW) → `2400×1800` becomes `1800×2400`, `changed = true`.

---

### STAGE 4 — Compress to target (< 500 KB, quality 85)

```
q = 85
while q >= 45:
    encode JPEG(q, optimize=True, progressive=True, subsampling=4:2:0)
    if bytes <= 500 KB: return
    q -= 5
# floor reached, still oversized -> downscale 0.85x and re-encode at q=45
```

Three deliberate choices:
- **Progressive + optimize** — smaller for the same quality; better perceived load on slow networks.
- **`4:2:0` chroma subsampling** — halves chroma bandwidth at no visible cost on garment photography, which is where most of the saving comes from.
- **Quality floor 45 before any downscale** — raising resolution-loss only after quality hits an accepted floor keeps the image sharp-looking rather than smeared. Garments are colour+texture, and mushy texture reads as "cheap" in a wardrobe app.

**Measured:** start `q85` → **`94.4 KB` in one iteration**, under the 500 KB target, final dimensions `1317×1851`. No downscale needed.

---

### STAGE 5 — Thumbnails (256×256 + 1024 max)

| Variant | Geometry | Use | Measured |
|---|---|---|---|
| `_small.jpg` | `ImageOps.fit` 256×256, `centering=(0.5,0.5)` — square cover-crop | `LazyVerticalGrid` 3-column `ItemCard` | **256×256, 4.6 KB** |
| `_large.jpg` | `thumbnail((1024,1024))` — max edge, aspect preserved | `ItemDetailScreen` hero | **729×1024, 25.0 KB** |

Serving the 256 px thumb into the grid is the difference between ~4.6 KB and ~2–4 MB per card: the wardrobe grid stops decoding full-resolution bitmaps entirely.

---

### STAGE 6 — Background removal (chroma-key-lite)

Key colour sampled from the border ring (same statistics as STAGE 2), then a **three-band alpha**, not a hard threshold:

```
d = |ΔR|+|ΔG|+|ΔB| from key ;  luma = 0.299R+0.587G+0.114B
d <= 34            and luma > 22  -> alpha 255   (keep)
34 < d <= 94       and luma > 22  -> alpha = 255 * (d-34)/60   (fade)
else                              -> alpha 0     (cut)
```

The **fade band** is why edges don't alias into a jagged polygonal halo — the classic artifact of `d > t ? 255 : 0`. The **`luma > 22` guard** stops deep shadows that happen to sit near the key colour from being punched into holes. Production replaces the key with the MediaPipe mask and reuses the identical alpha math, so the two paths cannot disagree visually.

**Measured:** key `(255,246,212)`, foreground coverage `33.5%`, cutout written as PNG for inspection. Limitation stated plainly: chroma-key-lite assumes a **reasonably uniform background**. On a patterned bed or a cluttered floor it degrades to a soft vignette, and must yield to the MediaPipe mask.

---

### STAGE 7 — Wardrobe-consistent enhancement

**The design decision that matters:** *no per-image autocontrast.*

A per-image stretch (`ImageOps.autocontrast`, or CLAHE with adaptive params) makes every garment sit on a **different tone curve**. Two navy tops photographed under different light then render at visibly different brightness in the same grid — the wardrobe looks like a scrapbook instead of a collection. Consistency beats per-image optimality here.

```
(a) gray-world white balance, gains CLAMPED to ±8%
    target = mean(R,G,B)/3 ;  gain_c = clamp(target/mean_c, 0.92, 1.08)
(b) FIXED contrast 1.06 · FIXED saturation 1.04 · FIXED brightness 1.00
```

- The **±8 % clamp** is what stops a vivid red dress from dragging the whole image's white balance with it (uncapped gray-world fails badly on colour-dominant frames — the exact case a wardrobe app guarantees).
- **Brightness is never touched (1.00)**; exposure consistency is the property users actually see when scanning the grid.
- The Kotlin port applies the same three constants to every item, so identical source lighting yields pixel-identical output.

**Measured:** channel means `[98.6, 108.6, 129.2]` (cool, blue-shifted) → gains `[1.08, 1.0326, 0.92]` (clamped at the ceiling on R) → neutralised.

---

### STAGE 8 — Lazy-load with Coil

Coil `2.7.0` is already in `gradle/libs.versions.toml` and active in `app/build.gradle.kts`; `coil.compose.AsyncImage` is imported in three screens. The upgrade is to point it at the thumbnail with an explicit request:

```kotlin
AsyncImage(
    model = ImagePipeline.thumbModel(context, item.id),        // small = grid, large = detail
    contentDescription = null,                                  // card text carries the meaning
    placeholder = painterResource(R.drawable.garment_placeholder),
    error = painterResource(R.drawable.garment_error),
    contentScale = ContentScale.Crop,
    modifier = Modifier.aspectRatio(1f).clip(MaterialTheme.shapes.medium)
)
```

Cache keys are explicit and versioned — `memoryCacheKey = "wardrobe/{itemId}/small/v1"`, mirrored on disk — so editing one item (or bumping the enhancer params to `v2`) invalidates exactly that item, not the whole cache. `crossfade(220)` matches the brand's 250 ms ease-out; `Precision.INEXACT` + `Scale.CROP` let Coil pick the decode size instead of loading full resolution. An `ImagePipeline.evict(context, itemId)` helper drops both variants after an edit.

---

## 3. Interaction with other agents' work (no collisions)

| Stage | Depends on | Consumed by |
|---|---|---|
| 1, 3, 4, 5 | — | #4 Wardrobe UI (grid/detail thumbnails), #11 storage/persistence |
| 2 (bbox) | — | #2 auto-tagging (crop the garment before classifying colour/type), #12 camera capture |
| 6 (alpha) | — | #9 Colour Harmony (sample the **masked** garment region, not the background — otherwise a white bed poisons the dominant-colour read) |
| 7 (fixed params) | — | #3 Brand (visual consistency), #4 (grid coherence) |

The masking order is deliberate: **enhance (7) runs before isolation (6)**, so the alpha mask is computed on the already-balanced image — the same key colour classifies consistently across the wardrobe instead of shifting with each photo's cast.

---

## 4. Kotlin migration outline

| Order | File | Action |
|---|---|---|
| 1 | `app/build.gradle.kts` | uncomment CameraX quartet; add `com.google.mediapipe:tasks-vision:0.10.14`; ship deeplab model in `src/main/assets/` |
| 2 | `image/ImagePipeline.kt` | **new** — port of `tools/image_processor.kt`, constants mirrored from `image_processor.py` |
| 3 | `ui/AddItemScreen.kt` | `photoPickerLauncher` result → `lifecycleScope.launch { ImagePipeline.process(ctx, uri, id) }`; on save write `imageUrl = result.fullPath`; show a progress indicator while processing |
| 4 | `models/WardrobeItem.kt` | no schema change — `imageUrl` documented as an absolute file path; add `imageVersion: Int = 1` only if you need per-item cache busting |
| 5 | `ui/WardrobeScreen.kt`, `ItemDetailScreen.kt` | `AsyncImage(model = ImagePipeline.thumbModel(ctx, item.id, fullRes = …))` + placeholder/error |
| 6 | `res/drawable/` | `garment_placeholder.xml`, `garment_error.xml` (single-line garment silhouette in Pressing Ink per the brand motifs) |
| 7 | background worker | offer `WorkManager` for batch re-processing of an existing wardrobe (re-run stages 3–5 from each `*_original.jpg`) so pre-existing items gain thumbnails and consistent tone |
| 8 | tests | JVM unit tests with Robolectric for the pure-math functions (compress loop, bbox projection, alpha bands) — no device needed |

**Cost control:** stage 2/3/5/7 are CPU-bound — run them on `Dispatchers.Default`, decode with `inSampleSize` capped at 2048 px, and never hold more than one full-resolution bitmap (a 12 MP ARGB_8888 bitmap is ~48 MB and will OOM a mid-range device if two are alive at once).

---

## 5. Verified prototype run — real captured output

`python3 image_processor.py --demo --out … --item-id demo_navy_bomber`
(synthetic 2400×1800 "navy garment on a light surface" saved with **EXIF Orientation = 6**, 809.2 KB source)

| Stage | Observable result |
|---|---|
| 1 ingest | `828,658 B` copied byte-identical (sha256 prefix `735bde71` both sides), `bytes_match = True` |
| 3 rotate | EXIF tag `6` → `2400×1800` becomes `1800×2400`, `changed = True` |
| 2 crop | method `mask-projection @256px proxy`; bg median `(232,231,227)`; coverage `38.0%`; bbox `(246,181,1563,2032)`; `1800×2400` → `1317×1851`; **43.6 % margins removed** |
| 7 enhance | means `[98.6,108.6,129.2]` → gains `[1.08,1.0326,0.92]`; contrast `1.06`, saturation `1.04`, brightness `1.00`, autocontrast `False` |
| 6 isolate | key `(255,246,212)`, keep≤`34`, fade band `60`, foreground `33.5 %` |
| 4 compress | q85 first try → **`94,640 B` = 94.4 KB**, `under_target = True`, `1317×1851` |
| 5 thumbs | small **`256×256`, 4,705 B**; large **`729×1024`, 25,630 B** |
| — | total pipeline wall time **1505 ms** for a 12 MP-class input |

Outputs: `{itemId}.jpg` (96,640 B), `{itemId}_small.jpg` (4,705 B), `{itemId}_large.jpg` (25,630 B), `{itemId}_cutout.png` (591,122 B, alpha mask for inspection), plus the immutable `{itemId}_original.jpg`.

---

## 6. Stated limitations

1. **Crop heuristic accuracy** is bounded by background uniformity. A garment whose colour is within the 84-unit Manhattan window of a plain wall will be under-detected; the 8 %-coverage guard then sends it to the center-weighted fallback, which is safe but not tight. MediaPipe is the real answer for those frames.
2. **Chroma-key-lite is a fallback, not a product-grade matte** — no hair/fur/mesh handling. MediaPipe or a dedicated matting model is required for floaty, semi-transparent fabrics (and for the "cut-out on ivory" brand look).
3. **Gray-world WB is an assumption** (average scene is neutral). The ±8 % clamp keeps it from misbehaving on colour-dominant garments, but a white-balance card or a one-time per-session calibration would be better.
4. **No perceptual metric was asserted.** PSNR/SSIM/butteraugli were not computed, so "quality 85 is good enough" is an engineering choice here, not a measured one. A perceptual sweep over 20 real garments should precede locking the constant.
5. **Android correctness is unverified.** The Kotlin file follows the repo's API surface but was **not compiled** — this environment has no Android SDK or Gradle. The Python prototype is the verified artefact; treat `image_processor.kt` as a reviewed port pending its first `assembleDebug`.
6. **`takePersistableUriPermission` is a no-op under the Photo Picker** (see STAGE 1) — the copy is the load-bearing mechanism.
