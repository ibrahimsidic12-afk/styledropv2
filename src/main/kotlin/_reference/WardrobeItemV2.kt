package com.example.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.migration.Migration
import androidx.room.sqlite.SQLiteDatabase
import java.util.UUID

/**
 * StyleDrop wardrobe item — v2 of the core model.
 * CHANGES vs. the current repo (all labelled [NEW]):
 *  - formality: String  [NEW]  Casual | Smart Casual | Formal | Athleisure
 *  - fabric: String     [NEW]  Cotton | Linen | Wool | Denim | Leather | Silk | Cashmere | Technical
 *  - ItemCategory: adds Dresses + Activewear; "Shoes" is DISPLAYED as "Footwear"
 *    (stored value kept for data compatibility); Bags / Watches / Jewelry stay valid
 *    stored values and render under the Accessories tab.
 */
@Entity(tableName = "wireframe_placeholder_wardrobe_items")
data class WardrobeItem(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val imageUrl: String,
    val category: String,
    val type: String,
    val color: String,
    val secondaryColor: String = "",
    val style: String,
    val fit: String,
    val pattern: String,
    val season: String = "All Season",
    val brand: String = "",
    val timesWorn: Int = 0,
    val isFavoriteItem: Boolean = false,
    val lastWorn: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val formality: String = "Casual",   // [NEW]
    val fabric: String = "Cotton"       // [NEW]
)

object ItemCategory {
    const val TOP = "Tops"
    const val BOTTOM = "Bottoms"
    const val SHOES = "Shoes"            // stored value kept for data compat
    const val OUTERWEAR = "Outerwear"
    const val ACCESSORY = "Accessories"
    const val BAG = "Bags"               // legacy value, grouped under Accessories tab
    const val WATCH = "Watches"          // legacy
    const val JEWELRY = "Jewelry"        // legacy
    const val DRESS = "Dresses"          // [NEW]
    const val ACTIVEWEAR = "Activewear"  // [NEW]

    /** The 7 tabs of the new wardrobe screen, in order. */
    val tabs = listOf(TOP, BOTTOM, OUTERWEAR, SHOES, ACCESSORY, DRESS, ACTIVEWEAR)

    /** All values that can exist in the `category` column (tabs + legacy groupings). */
    val all = tabs + listOf(BAG, WATCH, JEWELRY)

    /** Display name — "Shoes" renders as "Footwear" on the tab. */
    fun display(category: String): String =
        if (category == SHOES) "Footwear" else category

    /** Which tab a stored category belongs to (legacy Bags/Watches/Jewelry -> Accessories). */
    fun tabOf(category: String): String = when (category) {
        BAG, WATCH, JEWELRY -> ACCESSORY
        else -> category
    }

    fun emoji(category: String): String = when (category) {
        TOP -> "👕"
        BOTTOM -> "👖"
        SHOES -> "👟"
        OUTERWEAR -> "🧥"
        ACCESSORY, BAG, WATCH, JEWELRY -> "🧢"
        DRESS -> "👗"
        ACTIVEWEAR -> "🏃"
        else -> "👕"
    }
}

/** Room migration for the two new columns (bump the database version by 1). */
val WARDROBE_V2_MIGRATION = object : Migration(1, 2) {
    override fun migrate(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE wardrobe_items ADD COLUMN formality TEXT NOT NULL DEFAULT 'Casual'")
        db.execSQL("ALTER TABLE wardrobe_items ADD COLUMN fabric TEXT NOT NULL DEFAULT 'Cotton'")
    }
}
