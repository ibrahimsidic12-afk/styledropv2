package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.data.entity.FavoriteItem
import com.example.data.entity.ItemWithLogs
import com.example.data.entity.Outfit
import com.example.data.entity.OutfitItem
import com.example.data.entity.OutfitLog
import com.example.data.entity.OutfitWithItems
import com.example.data.entity.TrendScore
import com.example.data.entity.TrendSignal
import com.example.data.entity.WardrobeItem
import kotlinx.coroutines.flow.Flow

/**
 * StyleDrop v3 — WardrobeDao.
 *
 * Rules applied throughout:
 *  1. Every read returns `Flow` (reactive) or is `suspend` (one-shot). NO main-thread access.
 *  2. Multi-step writes are wrapped in `@Transaction` so a half-written outfit
 *     can never be observed by a reader.
 *  3. SQL is pre-computed by SQLite (Room runs it off the main thread on its
 *     own IO dispatcher via `room-ktx`'s `Flow` support).
 */
@Dao
interface WardrobeDao {

    // ─────────────────────────── WARDROBE ITEMS ───────────────────────────

    @Query("SELECT * FROM wardrobe_items ORDER BY createdAt DESC")
    fun getAllItems(): Flow<List<WardrobeItem>>

    @Query(
        "SELECT * FROM wardrobe_items WHERE category = :category " +
            "ORDER BY createdAt DESC"
    )
    fun getItemsByCategory(category: String): Flow<List<WardrobeItem>>

    @Query("SELECT * FROM wardrobe_items WHERE id = :id LIMIT 1")
    suspend fun getItemById(id: String): WardrobeItem?

    /** One-shot COUNT per category — cheaper than loading every row into memory. */
    @Query("SELECT COUNT(*) FROM wardrobe_items WHERE category = :category")
    fun getCategoryCount(category: String): Flow<Int>

    /** Every category's count in a single indexed GROUP BY (replaces 8 separate flows). */
    @Query("SELECT category AS category, COUNT(*) AS count FROM wardrobe_items GROUP BY category")
    fun getCategoryCounts(): Flow<List<CategoryCount>>

    /** Favourites via the boolean back-compat column + the composite index. */
    @Query(
        "SELECT * FROM wardrobe_items WHERE isFavoriteItem = 1 " +
            "ORDER BY createdAt DESC"
    )
    fun getFavoriteItems(): Flow<List<WardrobeItem>>

    @Query(
        "SELECT * FROM wardrobe_items WHERE season = :season AND category = :category " +
            "ORDER BY createdAt DESC"
    )
    fun getItemsBySeasonAndCategory(season: String, category: String): Flow<List<WardrobeItem>>

    @Query(
        "SELECT * FROM wardrobe_items WHERE color = :color OR secondaryColor = :color " +
            "ORDER BY createdAt DESC"
    )
    fun getItemsByColor(color: String): Flow<List<WardrobeItem>>

    @Query(
        "SELECT * FROM wardrobe_items WHERE style = :style AND color = :color " +
            "ORDER BY createdAt DESC"
    )
    fun getItemsByStyleAndColor(style: String, color: String): Flow<List<WardrobeItem>>

    /** Never-worn items — the "rotate me" nudge. Uses the colour index for the projection. */
    @Query("SELECT * FROM wardrobe_items WHERE timesWorn = 0 ORDER BY createdAt DESC")
    fun getNeverWornItems(): Flow<List<WardrobeItem>>

    @Query("SELECT * FROM wardrobe_items WHERE lastWorn < :cutoff ORDER BY lastWorn ASC")
    fun getStaleItems(cutoff: Long): Flow<List<WardrobeItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: WardrobeItem)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<WardrobeItem>)

    @Update
    suspend fun updateItem(item: WardrobeItem)

    @Query("DELETE FROM wardrobe_items WHERE id = :id")
    suspend fun deleteItem(id: String)

    @Query("UPDATE wardrobe_items SET timesWorn = :count, lastWorn = :lastWorn WHERE id = :id")
    suspend fun updateWearStats(id: String, count: Int, lastWorn: Long)

    @Query("UPDATE wardrobe_items SET isFavoriteItem = :favorite WHERE id = :id")
    suspend fun setFavoriteFlag(id: String, favorite: Boolean)

    // ─────────────────────────── OUTFITS ───────────────────────────

    @Transaction
    @Query("SELECT * FROM outfits ORDER BY createdAt DESC")
    fun getOutfitsWithItems(): Flow<List<OutfitWithItems>>

    @Transaction
    @Query("SELECT * FROM outfits WHERE id = :outfitId LIMIT 1")
    suspend fun getOutfitWithItemsById(outfitId: String): OutfitWithItems?

    @Query("SELECT * FROM outfits WHERE occasion = :occasion ORDER BY createdAt DESC")
    fun getOutfitsByOccasion(occasion: String): Flow<List<Outfit>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOutfit(outfit: Outfit)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOutfitItems(items: List<OutfitItem>)

    @Query("DELETE FROM outfit_items WHERE outfitId = :outfitId")
    suspend fun clearOutfitItems(outfitId: String)

    @Query("DELETE FROM outfits WHERE id = :outfitId")
    suspend fun deleteOutfitById(outfitId: String)

    /**
     * Saves an outfit AND its junction rows atomically.
     * If any insert fails the whole transaction rolls back — the v2 code had no
     * such guarantee because outfit tables did not exist at all.
     */
    @Transaction
    suspend fun saveOutfitWithItems(outfit: Outfit, itemIds: List<String>) {
        insertOutfit(outfit)
        clearOutfitItems(outfit.id) // idempotent re-save
        insertOutfitItems(itemIds.mapIndexed { index, itemId ->
            OutfitItem(outfitId = outfit.id, itemId = itemId, slotOrder = index)
        })
    }

    /** Delete an outfit; CASCADE removes its junction rows automatically. */
    @Transaction
    suspend fun deleteOutfitCascade(outfitId: String) {
        deleteOutfitById(outfitId)
    }

    // ─────────────────────────── WEAR LOGS ───────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: OutfitLog)

    @Transaction
    @Query("SELECT * FROM wardrobe_items WHERE id = :itemId LIMIT 1")
    suspend fun getItemWithLogs(itemId: String): ItemWithLogs?

    @Query("SELECT * FROM outfit_logs WHERE itemId = :itemId ORDER BY wornAt DESC")
    fun getLogsForItem(itemId: String): Flow<List<OutfitLog>>

    @Query("SELECT COUNT(*) FROM outfit_logs WHERE itemId = :itemId")
    suspend fun countLogsForItem(itemId: String): Int

    /** Everything worn since a cutoff — cost-per-wear & "what did I wear this month". */
    @Query("SELECT * FROM outfit_logs WHERE wornAt >= :since ORDER BY wornAt DESC")
    fun getLogsSince(since: Long): Flow<List<OutfitLog>>

    /**
     * Logs a wear AND denormalises the counters in ONE transaction, so
     * `timesWorn` can never drift from `outfit_logs`.
     */
    @Transaction
    suspend fun logWear(itemId: String, outfitId: String?, occasion: String, wornAt: Long) {
        insertLog(OutfitLog(itemId = itemId, outfitId = outfitId, wornAt = wornAt, occasion = occasion))
        val current = getItemById(itemId) ?: return
        updateWearStats(itemId, current.timesWorn + 1, wornAt)
    }

    // ─────────────────────────── FAVOURITES ───────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(favorite: FavoriteItem)

    @Query("DELETE FROM favorite_items WHERE itemId = :itemId")
    suspend fun deleteFavoriteByItemId(itemId: String)

    /** Keeps the legacy boolean and the new table in lock-step. */
    @Transaction
    suspend fun setFavorite(itemId: String, favorite: Boolean, at: Long) {
        if (favorite) {
            insertFavorite(FavoriteItem(itemId = itemId, favoritedAt = at))
        } else {
            deleteFavoriteByItemId(itemId)
        }
        setFavoriteFlag(itemId, favorite)
    }

    // ─────────────────────────── TRENDS ───────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSignals(signals: List<TrendSignal>)

    @Query("SELECT * FROM trend_signals WHERE entity = :entity ORDER BY week ASC")
    fun getSignalsForEntity(entity: String): Flow<List<TrendSignal>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertScores(scores: List<TrendScore>)

    @Query("SELECT * FROM trend_scores ORDER BY score DESC LIMIT :limit")
    fun getTopTrends(limit: Int): Flow<List<TrendScore>>

    @Query("SELECT * FROM trend_scores WHERE stage IN ('BREAKOUT', 'RISING') ORDER BY score DESC")
    fun getBreakoutTrends(): Flow<List<TrendScore>>
}

/** Small projection row for the single-query category tally. */
data class CategoryCount(
    val category: String,
    val count: Int
)
