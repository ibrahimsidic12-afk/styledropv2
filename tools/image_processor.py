#!/usr/bin/env python3
"""
styledropv2 -- IMAGE ENHANCEMENT PIPELINE (Python reference prototype)
=====================================================================

Implements the executable stages of IMAGE_PIPELINE.md:
    STAGE 1  ingest      -- copy source into app-private storage (Uri analogue)
    STAGE 2  auto-crop   -- garment bounding box (center-weighted heuristic; the
                            MediaPipe Image Segmenter path is Kotlin/Android only)
    STAGE 3  auto-rotate -- EXIF orientation correction
    STAGE 4  compress    -- iterative JPEG quality loop to < 500 KB @ quality 85
    STAGE 5  thumbnails  -- 256x256 square + 1024x1024 max
    STAGE 6  bg removal  -- chroma-key-lite alpha mask (coverage report)
    STAGE 7  enhance     -- wardrobe-CONSISTENT WB / contrast (fixed params)

Grounded in the real repo contract:
    models/WardrobeItem.kt   val imageUrl: String          <- becomes a file path
    ui/AddItemScreen.kt      ActivityResultContracts.PickVisualMedia()
    ui/WardrobeScreen.kt     AsyncImage(model = item.imageUrl)   (coil-compose 2.7.0)

Dependencies: Pillow only. No numpy, no OpenCV, no network.
Run:  python3 image_processor.py --demo
      python3 image_processor.py --input photo.jpg --out . --item-id abc123
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import shutil
import statistics
import sys
import time
from PIL import Image, ImageDraw, ImageEnhance, ImageOps, ImageStat

# ----------------------------------------------------------------------------
# Tunables -- these are the constants the Kotlin port must mirror 1:1
# ----------------------------------------------------------------------------
MAX_JPEG_BYTES   = 500 * 1024      # STAGE 4 target  (<500 KB)
START_QUALITY    = 85              # STAGE 4 start (quality 85)
QUALITY_FLOOR    = 45
QUALITY_STEP     = 5
THUMB_SMALL      = (256, 256)      # STAGE 5 grid thumbnail (square, cover crop)
THUMB_LARGE_MAX  = 1024            # STAGE 5 detail-view max edge
PRIVATE_DIR      = "app_private/wardrobe_images"
THUMB_DIR        = "app_private/wardrobe_images/thumbs"

# STAGE 7: FIXED, constant enhancement params. Deliberately NOT per-image
# autocontrast -- a per-image stretch makes every garment sit on a different
# tone curve, which is exactly what destroys cross-wardrobe visual consistency.
WB_MAX_GAIN      = 0.08            # gray-world white balance, clamped +-8%
FIX_CONTRAST     = 1.06            # fixed contrast multiplier
FIX_COLOR        = 1.04            # fixed saturation multiplier
FIX_BRIGHTNESS   = 1.00            # never touched -- protects exposure consistency

# STAGE 2 heuristic
CROP_THRESHOLD   = 28              # per-channel Manhattan distance from bg
CROP_PAD_FRAC    = 0.04            # 4% breathing room around the bbox
CROP_MIN_AREA    = 0.08            # < 8% coverage -> assume crop failed
FALLBACK_KEEP    = 0.88            # center-weighted fallback: central 88%

# STAGE 6 chroma-key-lite
CHROMA_SIM_MAX   = 34              # max RGB distance from key colour to keep
CHROMA_LUMA_LO   = 22              # shadow guard


# ----------------------------------------------------------------------------
# helpers
# ----------------------------------------------------------------------------
def _clamp(v, lo, hi):
    return max(lo, min(hi, v))


def ensure_dirs(root: str) -> None:
    os.makedirs(os.path.join(root, PRIVATE_DIR), exist_ok=True)
    os.makedirs(os.path.join(root, THUMB_DIR), exist_ok=True)


def _sha8(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()[:8]


def _kb(n: int) -> str:
    return f"{n/1024:.1f} KB"


# ----------------------------------------------------------------------------
# STAGE 1 -- ingest: source -> app-private storage
# ----------------------------------------------------------------------------
def ingest(src_path: str, root: str, item_id: str) -> dict:
    """
    Kotlin analogue:
        contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)      # survives reboot
        source = contentResolver.openInputStream(uri)
        dest   = File(filesDir, "wardrobe_images/$itemId.jpg")
        source.copyTo(dest)                                  # copy, never keep the Uri

    Why copy: a bare content:// Uri (what AddItemScreen stores today) is revoked
    on reboot/process death -> the card goes blank. The copy is the fix.
    """
    ensure_dirs(root)
    src_size = os.path.getsize(src_path)
    src_sha = _sha8(src_path)
    dest_rel = os.path.join(PRIVATE_DIR, f"{item_id}_original.jpg")
    dest_abs = os.path.join(root, dest_rel)

    tmp = dest_abs + ".tmp"
    shutil.copyfile(src_path, tmp)          # atomic-ish: write tmp, then rename
    os.replace(tmp, dest_abs)

    ok = os.path.getsize(dest_abs) == src_size and _sha8(dest_abs) == src_sha
    return {
        "stage": 1,
        "name": "ingest / private storage",
        "source_path": src_path,
        "source_bytes": src_size,
        "source_sha256_8": src_sha,
        "private_path": dest_abs,
        "private_bytes": os.path.getsize(dest_abs),
        "bytes_match": ok,
        "persist_permission": "takePersistableUriPermission(FLAG_GRANT_READ_URI_PERMISSION)",
        "note": "copy verified byte-identical to source",
    }


# ----------------------------------------------------------------------------
# STAGE 3 -- EXIF auto-rotate
# ----------------------------------------------------------------------------
def read_exif_orientation(img: Image.Image):
    try:
        exif = img.getexif()
        return exif.get(0x0112, None)
    except Exception:
        return None


def auto_rotate(img: Image.Image) -> tuple[Image.Image, dict]:
    before = img.size
    tag = read_exif_orientation(img)
    rotated = ImageOps.exif_transpose(img)
    # exif_transpose already clears/rewrites the tag; make it explicit.
    try:
        rotated.info.pop("exif", None)
    except Exception:
        pass
    return rotated, {
        "stage": 3,
        "name": "auto-rotate (EXIF)",
        "exif_orientation_tag": tag,
        "size_before": f"{before[0]}x{before[1]}",
        "size_after": f"{rotated.size[0]}x{rotated.size[1]}",
        "changed": before != rotated.size,
        "action": {None: "no EXIF orientation tag -- left as-is",
                   1: "orientation 1 (normal) -- no rotation needed",
                   3: "orientation 3 -> rotate 180 deg",
                   6: "orientation 6 -> rotate 90 deg CW",
                   8: "orientation 8 -> rotate 90 deg CCW"}.get(
                       tag, f"orientation {tag} -> transposed"),
    }


# ----------------------------------------------------------------------------
# STAGE 2 -- auto-crop to garment bounding box (center-weighted heuristic)
# ----------------------------------------------------------------------------
def auto_crop_heuristic(img: Image.Image) -> tuple[Image.Image, dict]:
    """
    Kotlin/Android production path = MediaPipe Image Segmenter (deeplab_v3 /
    selfie_segmenter), whose mask feeds the same bbox math below. This function
    is the NO-ML FALLBACK that must always ship, because MediaPipe needs a model
    asset and a GPU-ish device.

    Algorithm (all on a 256px proxy so it is O(1) in source resolution):
      1. background = per-channel MEDIAN of the image border ring
      2. mask pixel = Manhattan RGB distance from background > CROP_THRESHOLD
      3. row/col projections, trimmed at 20% of peak  (kills stray specks)
      4. area sanity check -> else center-weighted fallback
      5. pad 4%, scale bbox back to full resolution
    """
    rgb = img.convert("RGB")
    w, h = rgb.size
    scale = 256.0 / max(w, h)
    sw, sh = max(1, int(w * scale)), max(1, int(h * scale))
    small = rgb.resize((sw, sh), Image.BILINEAR)
    px = small.load()

    ring = [px[x, 0] for x in range(sw)] + [px[x, sh - 1] for x in range(sw)]
    ring += [px[0, y] for y in range(sh)] + [px[sw - 1, y] for y in range(sh)]
    bg = tuple(int(statistics.median([c[i] for c in ring])) for i in range(3))

    colsum = [0] * sw
    rowsum = [0] * sh
    total = 0
    for y in range(sh):
        for x in range(sw):
            p = px[x, y]
            if (abs(p[0] - bg[0]) + abs(p[1] - bg[1]) + abs(p[2] - bg[2])) > CROP_THRESHOLD * 3:
                colsum[x] += 1
                rowsum[y] += 1
                total += 1

    coverage = total / float(sw * sh)
    peak_c, peak_r = max(colsum) or 1, max(rowsum) or 1
    thr_c, thr_r = peak_c * 0.20, peak_r * 0.20

    def span(proj, thr):
        idx = [i for i, v in enumerate(proj) if v >= thr]
        return (idx[0], idx[-1]) if idx else None

    sx, sy = span(colsum, thr_c), span(rowsum, thr_r)

    if sx is None or sy is None or coverage < CROP_MIN_AREA:
        method = "fallback/center-weighted"
        keep = FALLBACK_KEEP
        cx0, cy0 = int(w * (1 - keep) / 2), int(h * (1 - keep) / 2)
        box = (cx0, cy0, w - cx0, h - cy0)
        seg_coverage = coverage
    else:
        method = "mask-projection @256px proxy"
        seg_coverage = coverage
        x0 = int(sx[0] / scale); x1 = int(sx[1] / scale) + 1
        y0 = int(sy[0] / scale); y1 = int(sy[1] / scale) + 1
        pad = int(CROP_PAD_FRAC * min(w, h))
        box = (_clamp(x0 - pad, 0, w - 1), _clamp(y0 - pad, 0, h - 1),
               _clamp(x1 + pad, 1, w), _clamp(y1 + pad, 1, h))

    cropped = img.crop(box)
    removed = 1.0 - ((box[2] - box[0]) * (box[3] - box[1])) / float(w * h)
    return cropped, {
        "stage": 2,
        "name": "auto-crop (garment bbox)",
        "method": method,
        "background_median": bg,
        "proxy_size": f"{sw}x{sh}",
        "mask_coverage": f"{seg_coverage*100:.1f}%",
        "bbox": box,
        "size_before": f"{w}x{h}",
        "size_after": f"{cropped.size[0]}x{cropped.size[1]}",
        "margins_removed": f"{removed*100:.1f}%",
    }


# ----------------------------------------------------------------------------
# STAGE 7 -- consistent enhancement
# ----------------------------------------------------------------------------
def enhance_consistent(img: Image.Image) -> tuple[Image.Image, dict]:
    rgb = img.convert("RGB")

    # (a) gray-world white balance, gains CLAMPED so a single vivid garment
    #     cannot push the whole wardrobe off-white.
    r, g, b = rgb.split()
    means = [ImageStat.Stat(c).mean[0] for c in (r, g, b)]
    target = sum(means) / 3.0
    gains = [_clamp(target / m, 1 - WB_MAX_GAIN, 1 + WB_MAX_GAIN) if m > 0 else 1.0
             for m in means]
    luts = [[_clamp(int(round(i * gn)), 0, 255) for i in range(256)] for gn in gains]
    out = Image.merge("RGB", [c.point(l) for c, l in zip((r, g, b), luts)])

    # (b) FIXED contrast / saturation. Same numbers for every garment -> the
    #     grid reads as one collection instead of 20 unrelated photos.
    out = ImageEnhance.Contrast(out).enhance(FIX_CONTRAST)
    out = ImageEnhance.Color(out).enhance(FIX_COLOR)
    out = ImageEnhance.Brightness(out).enhance(FIX_BRIGHTNESS)

    return out, {
        "stage": 7,
        "name": "auto-enhance (wardrobe-consistent)",
        "mode": "gray-world WB (clamped) + FIXED contrast/saturation",
        "channel_means_rgb": [round(m, 1) for m in means],
        "wb_gains_rgb": [round(gn, 4) for gn in gains],
        "wb_max_gain": WB_MAX_GAIN,
        "fixed_contrast": FIX_CONTRAST,
        "fixed_color": FIX_COLOR,
        "fixed_brightness": FIX_BRIGHTNESS,
        "per_image_autocontrast": False,
        "consistency_note": "constant params -> identical tone curve for all items",
    }


# ----------------------------------------------------------------------------
# STAGE 6 -- chroma-key-lite background isolation
# ----------------------------------------------------------------------------
def chroma_key_lite(img: Image.Image, key=None) -> tuple[Image.Image, dict]:
    """
    Kotlin port runs the MediaPipe mask; this is the cheap fallback. Key colour
    is sampled from the border ring (same as STAGE 2's background), alpha is
    faded smoothly instead of hard-thresholded so edges do not alias.
    """
    rgb = img.convert("RGB")
    w, h = rgb.size
    px = rgb.load()
    if key is None:
        ring = [px[x, 0] for x in range(w)] + [px[x, h - 1] for x in range(w)]
        ring += [px[0, y] for y in range(h)] + [px[w - 1, y] for y in range(h)]
        key = tuple(int(statistics.median([c[i] for c in ring])) for i in range(3))

    mask = Image.new("L", (w, h), 0)
    mp = mask.load()
    kept = 0
    for y in range(h):
        for x in range(w):
            p = px[x, y]
            d = abs(p[0] - key[0]) + abs(p[1] - key[1]) + abs(p[2] - key[2])
            luma = (p[0] * 299 + p[1] * 587 + p[2] * 114) // 1000
            if d <= CHROMA_SIM_MAX and luma > CHROMA_LUMA_LO:
                a = 255
                kept += 1
            elif d <= CHROMA_SIM_MAX + 60 and luma > CHROMA_LUMA_LO:
                a = int(255 * (d - CHROMA_SIM_MAX) / 60.0)
            else:
                a = 0
            mp[x, y] = a

    out = rgb.copy()
    out.putalpha(mask)
    return out, {
        "stage": 6,
        "name": "background removal (chroma-key-lite)",
        "key_color_rgb": key,
        "keep_threshold": CHROMA_SIM_MAX,
        "fade_band": 60,
        "foreground_coverage": f"{kept/(w*h)*100:.1f}%",
        "production_path": "MediaPipe ImageSegmenter mask -> same alpha math",
    }


# ----------------------------------------------------------------------------
# STAGE 4 -- compress to target
# ----------------------------------------------------------------------------
def compress_to_target(img: Image.Image, target=MAX_JPEG_BYTES,
                       start=START_QUALITY, floor=QUALITY_FLOOR,
                       step=QUALITY_STEP):
    rgb = img.convert("RGB")
    q = start
    tries = []
    data = None
    while q >= floor:
        buf = io.BytesIO()
        rgb.save(buf, "JPEG", quality=q, optimize=True, progressive=True,
                 subsampling="4:2:0")
        data = buf.getvalue()
        tries.append((q, len(data)))
        if len(data) <= target:
            return data, q, tries, rgb.size
        q -= step

    # still too big at the floor -> downscale iteratively (keeps quality sane)
    cur = rgb
    final_q = floor
    while min(cur.size) > 320:
        w, h = cur.size
        cur = cur.resize((int(w * 0.85), int(h * 0.85)), Image.LANCZOS)
        buf = io.BytesIO()
        cur.save(buf, "JPEG", quality=floor, optimize=True, progressive=True)
        data = buf.getvalue()
        tries.append((floor, len(data)))
        if len(data) <= target:
            return data, floor, tries, cur.size
    return data, final_q, tries, cur.size


# ----------------------------------------------------------------------------
# STAGE 5 -- thumbnails
# ----------------------------------------------------------------------------
def make_thumbnails(img: Image.Image):
    small = ImageOps.fit(img.convert("RGB"), THUMB_SMALL, Image.LANCZOS,
                         centering=(0.5, 0.5))
    large = img.convert("RGB").copy()
    large.thumbnail((THUMB_LARGE_MAX, THUMB_LARGE_MAX), Image.LANCZOS)
    out = {}
    for name, im in (("small", small), ("large", large)):
        buf = io.BytesIO()
        im.save(buf, "JPEG", quality=88, optimize=True, progressive=True)
        out[name] = {"image": im, "data": buf.getvalue(),
                     "byte_len": len(buf.getvalue()),
                     "size": f"{im.size[0]}x{im.size[1]}"}
    return out


# ----------------------------------------------------------------------------
# orchestration
# ----------------------------------------------------------------------------
def process(src_path: str, root: str, item_id: str) -> dict:
    t0 = time.time()
    report = {"item_id": item_id, "stages": []}

    s1 = ingest(src_path, root, item_id)
    report["stages"].append(s1)

    img = Image.open(s1["private_path"])
    img.load()

    img, s3 = auto_rotate(img)
    report["stages"].append(s3)

    img, s2 = auto_crop_heuristic(img)
    report["stages"].append(s2)

    img, s7 = enhance_consistent(img)
    report["stages"].append(s7)

    alpha_img, s6 = chroma_key_lite(img)
    report["stages"].append(s6)

    data, q, tries, final_size = compress_to_target(img)
    full_rel = os.path.join(PRIVATE_DIR, f"{item_id}.jpg")
    full_abs = os.path.join(root, full_rel)
    with open(full_abs, "wb") as fh:
        fh.write(data)
    report["stages"].append({
        "stage": 4, "name": "compress (<500KB JPEG)",
        "target_bytes": MAX_JPEG_BYTES, "target_human": "500.0 KB",
        "start_quality": START_QUALITY, "final_quality": q,
        "iterations": [(qq, _kb(bb)) for qq, bb in tries],
        "final_bytes": len(data), "final_human": _kb(len(data)),
        "final_dimensions": f"{final_size[0]}x{final_size[1]}",
        "under_target": len(data) <= MAX_JPEG_BYTES,
        "output_path": full_abs,
    })

    thumbs = make_thumbnails(img)
    t_paths = {}
    for name, t in thumbs.items():
        p = os.path.join(root, THUMB_DIR, f"{item_id}_{name}.jpg")
        with open(p, "wb") as fh:
            fh.write(t["data"])
        t_paths[name] = p
    report["stages"].append({
        "stage": 5, "name": "thumbnails",
        "small": {"size": thumbs["small"]["size"], "bytes": _kb(thumbs["small"]["byte_len"]),
                  "path": t_paths["small"], "fit": "square cover-crop 256x256"},
        "large": {"size": thumbs["large"]["size"], "bytes": _kb(thumbs["large"]["byte_len"]),
                  "path": t_paths["large"], "fit": "max edge 1024, aspect preserved"},
    })

    # persist the alpha-cutout for inspection
    cut_path = os.path.join(root, PRIVATE_DIR, f"{item_id}_cutout.png")
    alpha_img.save(cut_path, "PNG")
    report["cutout_path"] = cut_path

    report["total_ms"] = round((time.time() - t0) * 1000, 1)
    report["artifact_paths"] = [full_abs, t_paths["small"], t_paths["large"], cut_path]
    return report


def print_report(rep: dict) -> None:
    print("=" * 78)
    print(f"  STYLEDROP IMAGE PIPELINE  --  item {rep['item_id']}")
    print("=" * 78)
    for s in rep["stages"]:
        print(f"\n[STAGE {s['stage']}] {s['name']}")
        for k, v in s.items():
            if k in ("stage", "name"):
                continue
            print(f"    {k:<24} : {v}")
    print("\n" + "-" * 78)
    print(f"  artifacts:")
    for p in rep["artifact_paths"]:
        print(f"    {os.path.getsize(p):>9,} B  {p}")
    print(f"\n  pipeline wall time: {rep['total_ms']} ms")
    print("=" * 78)


def build_demo_source(path: str) -> str:
    """Synthetic 'photo of a navy bomber jacket on a light surface', 2400x1800,
    saved with EXIF Orientation=6 so STAGE 3 has real work to do."""
    W, H = 2400, 1800
    img = Image.new("RGB", (W, H), (233, 231, 227))       # light surface
    d = ImageDraw.Draw(img)
    # off-centre garment so auto-crop visibly recentres it
    gx, gy, gw, gh = 520, 300, 1180, 1180
    d.rounded_rectangle((gx, gy, gx + gw, gy + gh), radius=90, fill=(27, 42, 74))
    d.rounded_rectangle((gx + 180, gy + 210, gx + gw - 180, gy + gh - 190),
                        radius=60, fill=(38, 56, 94))       # front panel
    d.polygon([(gx - 260, gy + 120), (gx + 120, gy + 40),
               (gx + 120, gy + 470), (gx - 260, gy + 560)], fill=(22, 35, 62))   # L sleeve
    d.polygon([(gx + gw + 260, gy + 120), (gx + gw - 120, gy + 40),
               (gx + gw - 120, gy + 470), (gx + gw + 260, gy + 560)], fill=(22, 35, 62))
    d.rounded_rectangle((gx + gw // 2 - 40, gy - 90, gx + gw // 2 + 40, gy + 90),
                        radius=30, fill=(176, 138, 79))      # brass zipper pull
    # slight sensor noise so enhancement is not operating on flat synthetic fills
    px = img.load()
    for y in range(0, H, 2):
        for x in range(0, W, 2):
            p = px[x, y]
            j = ((x * 7 + y * 13) % 11) - 5
            px[x, y] = (_clamp(p[0] + j, 0, 255), _clamp(p[1] + j, 0, 255),
                        _clamp(p[2] + j, 0, 255))
    exif = Image.Exif()
    exif[0x0112] = 6            # Orientation = rotate 90 CW
    img.save(path, "JPEG", quality=96, exif=exif)
    return path


def main() -> int:
    ap = argparse.ArgumentParser(description="styledropv2 image pipeline prototype")
    ap.add_argument("--input", help="source image path")
    ap.add_argument("--out", default=".", help="app-private storage root")
    ap.add_argument("--item-id", default="demo_item")
    ap.add_argument("--demo", action="store_true",
                    help="synthesise a garment photo and run the full pipeline")
    ap.add_argument("--json", action="store_true", help="emit JSON report")
    a = ap.parse_args()

    if a.demo:
        tmp = os.path.join(a.out, "demo_source.jpg")
        os.makedirs(a.out, exist_ok=True)
        build_demo_source(tmp)
        print(f"[demo] synthesised source: {tmp} "
              f"({os.path.getsize(tmp)/1024:.1f} KB, EXIF Orientation=6)")
        src = tmp
    elif a.input:
        src = a.input
    else:
        ap.error("--input or --demo required")
        return 2

    report = process(src, a.out, a.item_id)
    if a.json:
        print(json.dumps(report, indent=2, default=str))
    else:
        print_report(report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
