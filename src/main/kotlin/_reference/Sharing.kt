package com.example.community

/**
 * StyleDrop — Community Sharing wire format (Android side).
 *
 * Mirrors community/sharing.py EXACTLY (same constants, same order of
 * operations) so a link created by the Python tooling / web builder decodes
 * on-device, and vice versa. Agent #18 — COMMUNITY + SHARING.
 *
 * sd2 wire format:
 *   "sd2:" + base64url( zlib( json ) ) without padding + "." + checksum(8)
 *   checksum = base64url( sha256("styledrop::v2" + body).first 6 bytes )[:8]
 * json (compact keys): {"v":2,"i":[[slot,itemId,z],...],"t","o","w","n","c","u"}
 *
 * Legacy sd1 (outfit_builder.html): bare base64( JSON [[slot,itemId,z],...] ),
 * standard alphabet WITH padding — read-only here.
 *
 * Uses org.json (bundled with Android) and java.util.zip — no new dependencies.
 */
object Sharing {

    // ---- constants (MUST match sharing.py) --------------------------------
    private const val WIRE_PREFIX = "sd2:"
    private const val CHECKSUM_SEP = "."
    private const val SIG_SALT = "styledrop::v2"
    private const val MAX_COMPRESSED_BYTES = 4096
    private const val MAX_DECOMPRESSED_BYTES = 8192
    private const val WIRE_VERSION = 2

    /** Builder slots (outfit_builder.html) ↔ ItemCategory (WardrobeItem.kt). */
    val SLOT_CATEGORY: Map<String, String> = mapOf(
        "TOP" to "Tops", "BOTTOM" to "Bottoms", "SHOES" to "Shoes",
        "OUTER" to "Outerwear", "HAT" to "Accessories", "BAG" to "Bags",
        "WATCH" to "Watches", "JEWELRY" to "Jewelry"
    )
    val CATEGORY_SLOT: Map<String, String> =
        SLOT_CATEGORY.entries.associate { (k, v) -> v to k }

    // ---- public model -------------------------------------------------------
    /** (slotId, itemId, z) — same triple shape the JS builder encodes. */
    data class Placed(val slot: String, val itemId: String, val z: Int)

    data class SharedLook(
        val items: List<Placed>,
        val title: String = "",
        val occasion: String = "",
        val weather: String = "",
        val note: String = "",
        val created: Long? = null,
        val handle: String = ""
    ) {
        fun toShareCode(): String = encode(items, title, occasion, weather, note, created, handle)

        companion object {
            fun fromShareCode(code: String): SharedLook = decode(code)
        }
    }

    class ShareDecodeException(message: String) : Exception(message)

    // ---- encode -------------------------------------------------------------
    fun encode(
        items: List<Placed>,
        title: String = "",
        occasion: String = "",
        weather: String = "",
        note: String = "",
        created: Long? = null,
        handle: String = ""
    ): String {
        require(items.isNotEmpty()) { "cannot encode an empty look" }
        require(items.size <= SLOT_CATEGORY.size) { "too many items for one look" }
        val obj = org.json.JSONObject()
        obj.put("v", WIRE_VERSION)
        obj.put("i", org.json.JSONArray().apply {
            items.forEach {
                // NB: build per-item arrays with put(); Android's org.json.JSONArray
                // has no Collection constructor (only JSONArray(Object array)).
                put(org.json.JSONArray().apply {
                    put(it.slot); put(it.itemId); put(it.z)
                })
            }
        })
        if (title.isNotEmpty()) obj.put("t", title.take(64))
        if (occasion.isNotEmpty()) obj.put("o", occasion.take(24))
        if (weather.isNotEmpty()) obj.put("w", weather.take(8))
        if (note.isNotEmpty()) obj.put("n", note.take(140))
        created?.let { obj.put("c", it) }
        if (handle.isNotEmpty()) obj.put("u", handle.take(32))

        val raw = obj.toString().toByteArray(Charsets.UTF_8)
        require(raw.size <= MAX_DECOMPRESSED_BYTES) { "look payload too large" }
        val body = b64UrlEncode(zlibCompress(raw))
        return "$WIRE_PREFIX$body$CHECKSUM_SEP${checksum(body)}"
    }

    // ---- decode -------------------------------------------------------------
    fun decode(code: String): SharedLook {
        val trimmed = code.trim()
        if (!trimmed.startsWith(WIRE_PREFIX)) return decodeLegacy(trimmed)

        val bodyAndSig = trimmed.removePrefix(WIRE_PREFIX)
        val sepIdx = bodyAndSig.lastIndexOf(CHECKSUM_SEP)
        if (sepIdx < 0) throw ShareDecodeException("missing checksum separator")
        val body = bodyAndSig.substring(0, sepIdx)
        val sig = bodyAndSig.substring(sepIdx + 1)
        if (sig.length != 8 || !constantTimeEq(checksum(body), sig))
            throw ShareDecodeException("checksum mismatch — payload tampered or truncated")

        val compressed = b64UrlDecode(body)
        if (compressed.size > MAX_COMPRESSED_BYTES)
            throw ShareDecodeException("compressed payload exceeds size cap")
        val raw = zlibDecompress(compressed)

        val payload = try {
            org.json.JSONObject(String(raw, Charsets.UTF_8))
        } catch (e: Exception) {
            throw ShareDecodeException("decompressed payload is not valid JSON")
        }
        if (!payload.has("v") || payload.getInt("v") != WIRE_VERSION)
            throw ShareDecodeException("unsupported wire version")

        val arr = payload.getJSONArray("i")
        if (arr.length() !in 1..SLOT_CATEGORY.size)
            throw ShareDecodeException("look has an invalid number of items")
        val seen = HashSet<String>()
        val items = ArrayList<Placed>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.getJSONArray(i)
            val slot = e.getString(0)
            requireSlot(slot)
            require(seen.add(slot)) { "duplicate slot: $slot" }
            items.add(Placed(slot, e.getString(1), e.getInt(2)))
        }
        return SharedLook(
            items = items,
            title = payload.optString("t").take(64),
            occasion = payload.optString("o").take(24),
            weather = payload.optString("w").take(8),
            note = payload.optString("n").take(140),
            created = if (payload.has("c")) payload.getLong("c") else null,
            handle = payload.optString("u").take(32)
        )
    }

    /** Legacy builder format: btoa(JSON [[slot,itemId,z],...]), padded std b64. */
    private fun decodeLegacy(code: String): SharedLook {
        val json = try {
            val padded = code + "=".repeat((4 - code.length % 4) % 4)
            String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) {
            throw ShareDecodeException("unrecognized share code (not sd2, not sd1)")
        }
        val arr = try {
            org.json.JSONArray(json)
        } catch (e: Exception) {
            throw ShareDecodeException("legacy payload must be a JSON array")
        }
        val items = ArrayList<Placed>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.getJSONArray(i)
            requireSlot(e.getString(0))
            items.add(Placed(e.getString(0), e.getString(1), e.optInt(2, 0)))
        }
        return SharedLook(items = items)
    }

    private fun requireSlot(slot: String) {
        if (slot !in SLOT_CATEGORY) throw ShareDecodeException("unknown slot: $slot")
    }

    // ---- primitives ----------------------------------------------------------
    private fun b64UrlEncode(data: ByteArray): String =
        android.util.Base64.encodeToString(data, android.util.Base64.URL_SAFE or
                android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING).trimEnd('=')

    private fun b64UrlDecode(text: String): ByteArray {
        val padded = text + "=".repeat((4 - text.length % 4) % 4)
        return try {
            android.util.Base64.decode(padded, android.util.Base64.URL_SAFE)
        } catch (e: IllegalArgumentException) {
            throw ShareDecodeException("payload is not valid base64url")
        }
    }

    private fun zlibCompress(data: ByteArray): ByteArray {
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION, false)
        deflater.setInput(data)
        deflater.finish()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(512)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return out.toByteArray()
    }

    private fun zlibDecompress(data: ByteArray): ByteArray {
        val inflater = java.util.zip.Inflater(false)   // zlib-wrapped (with header)
        inflater.setInput(data)
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(512)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && inflater.needsInput())
                    throw ShareDecodeException("truncated zlib stream")
                out.write(buf, 0, n)
                if (out.size() > MAX_DECOMPRESSED_BYTES)
                    throw ShareDecodeException("decompressed payload exceeds size cap (zip bomb?)")
            }
        } catch (e: java.util.zip.DataFormatException) {
            throw ShareDecodeException("corrupt zlib stream")
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    private fun checksum(body: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest((SIG_SALT + body).toByteArray(Charsets.UTF_8))
        return b64UrlEncode(digest.copyOf(6)).take(8)
    }

    private fun constantTimeEq(a: String, b: String): Boolean {
        val da = java.security.MessageDigest.getInstance("SHA-256").digest(a.toByteArray())
        val db = java.security.MessageDigest.getInstance("SHA-256").digest(b.toByteArray())
        return da.contentEquals(db)
    }
}
