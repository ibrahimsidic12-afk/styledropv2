package com.example.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "wardrobe_items")
data class WardrobeItem(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val imageUrl: String,
    val category: String, // Tops, Bottoms, Shoes, Outerwear, Accessories, Bags, Watches, Jewelry
    val type: String, // e.g. "Oversized T-Shirt"
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
    val createdAt: Long = System.currentTimeMillis()
)

object ItemCategory {
    const val TOP = "Tops"
    const val BOTTOM = "Bottoms"
    const val SHOES = "Shoes"
    const val OUTERWEAR = "Outerwear"
    const val ACCESSORY = "Accessories"
    const val BAG = "Bags"
    const val WATCH = "Watches"
    const val JEWELRY = "Jewelry"

    val all = listOf(TOP, BOTTOM, SHOES, OUTERWEAR, ACCESSORY, BAG, WATCH, JEWELRY)

    fun emoji(category: String): String {
        return when (category) {
            TOP -> "👕"
            BOTTOM -> "👖"
            SHOES -> "👟"
            OUTERWEAR -> "🧥"
            ACCESSORY -> "🧢"
            BAG -> "👜"
            WATCH -> "⌚"
            JEWELRY -> "💍"
            else -> "👕"
        }
    }
}
