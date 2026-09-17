package com.example.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * StyleDrop v3 — Room entity layer.
 *
 * Every column name below is IDENTICAL to the existing v2 `WardrobeItem` so the
 * 2 -> 3 migration is a pure "add indices" step (no table rewrite, no data loss).
 *
 * Reinforced changes vs v2:
 *  - `@Index` declarations (category, color, style, season + 4 composites)
 *  - `isFavoriteItem` kept for back-compat but superseded by [FavoriteItem] (a real row with a date)
 *  - `secondaryColor` kept and now actually indexed-readable
 */

@Entity(
    tableName = "wardrobe_items",
    indices = [
        Index(value = ["category"], name = "index_wardrobe_items_category"),
        Index(value = ["color"], name = "index_wardrobe_items_color"),
        Index(value = ["style"], name = "index_wardrobe_items_style"),
        Index(value = ["season"], name = "index_wardrobe_items_season"),
        Index(value = ["category", "createdAt"], name = "index_wardrobe_items_category_createdAt"),
        Index(value = ["season", "category"], name = "index_wardrobe_items_season_category"),
        Index(value = ["style", "color"], name = "index_wardrobe_items_style_color"),
        Index(value = ["isFavoriteItem", "createdAt"], name = "index_wardrobe_items_favorite_createdAt"),
    ]
)
data class WardrobeItem(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
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
    val createdAt: Long = System.currentTimeMillis()
)

/** A saved look ("Mix & Match" / AI result). Was a TODO stub in v2. */
@Entity(
    tableName = "outfits",
    indices = [
        Index(value = ["createdAt"], name = "index_outfits_createdAt"),
        Index(value = ["occasion"], name = "index_outfits_occasion"),
    ]
)
data class Outfit(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val occasion: String = "",
    val style: String = "",
    val weather: String = "",
    val score: Double = 0.0,
    val notes: String = "",
    val isFavorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Junction table: which wardrobe items compose an outfit, in which layering slot.
 * CASCADE on both sides — deleting an item or an outfit cleans the junction
 * automatically instead of leaving orphans (v2 had no junction, so this is new).
 */
@Entity(
    tableName = "outfit_items",
    primaryKeys = ["outfitId", "itemId"],
    foreignKeys = [
        ForeignKey(
            entity = Outfit::class,
            parentColumns = ["id"],
            childColumns = ["outfitId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = WardrobeItem::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE
        ),
    ],
    indices = [Index(value = ["itemId"], name = "index_outfit_items_itemId")]
)
data class OutfitItem(
    val outfitId: String,
    val itemId: String,
    @ColumnInfo(defaultValue = "0") val slotOrder: Int = 0
)

/**
 * Append-only wear log. This is what makes `timesWorn` / `lastWorn` on
 * [WardrobeItem] derivable and honest (v2 declared those fields but nothing
 * ever wrote them).
 */
@Entity(
    tableName = "outfit_logs",
    foreignKeys = [
        ForeignKey(
            entity = WardrobeItem::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["itemId", "wornAt"], name = "index_outfit_logs_itemId_wornAt"),
        Index(value = ["wornAt"], name = "index_outfit_logs_wornAt"),
    ]
)
data class OutfitLog(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val itemId: String,
    val outfitId: String? = null,
    val wornAt: Long = System.currentTimeMillis(),
    val occasion: String = ""
)

/** First-class favourite (v2 only had a boolean nobody toggled). */
@Entity(
    tableName = "favorite_items",
    foreignKeys = [
        ForeignKey(
            entity = WardrobeItem::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["itemId"], name = "index_favorite_items_itemId", unique = true)]
)
data class FavoriteItem(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val itemId: String,
    val favoritedAt: Long = System.currentTimeMillis(),
    val note: String = ""
)

/** Raw trend input (Agent #10). Unique on (entity, week, source) so re-ingest is idempotent. */
@Entity(
    tableName = "trend_signals",
    indices = [
        Index(
            value = ["entity", "week", "source"],
            name = "index_trend_signals_entity_week_source",
            unique = true
        ),
        Index(value = ["entity"], name = "index_trend_signals_entity"),
    ]
)
data class TrendSignal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    val entityType: String,
    val entity: String,
    val week: String,
    val count: Int = 0,
    val engagement: Int = 0,
    val region: String = "GLOBAL",
    val fetchedAt: Long = System.currentTimeMillis()
)

/** Materialized ranking so the Trending UI is a single indexed read. */
@Entity(tableName = "trend_scores")
data class TrendScore(
    @PrimaryKey val entity: String,
    val score: Double,
    val popularity: Double,
    val velocity: Double,
    val acceleration: Double,
    val freshness: Double,
    val crossSource: Double,
    val stage: String,
    val season: String,
    val updatedAt: Long
)

/** Projection for an outfit + its items in one @Relation read. */
data class OutfitWithItems(
    @androidx.room.Embedded val outfit: Outfit,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "outfitId")
    val items: List<OutfitItem>
)

/** Projection for a wardrobe item + its wear history. */
data class ItemWithLogs(
    @androidx.room.Embedded val item: WardrobeItem,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "itemId")
    val logs: List<OutfitLog>
)
