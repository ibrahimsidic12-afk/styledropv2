package com.example.data

import androidx.room.Dao
import androidx.room.RawQuery
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import com.example.models.WardrobeItem
import kotlinx.coroutines.flow.Flow

@Dao
interface WardrobeDao {
    @Query("SELECT * FROM wardrobe_items ORDER BY createdAt DESC")
    fun getAllItems(): Flow<List<WardrobeItem>>

    @Query("SELECT * FROM wardrobe_items WHERE category = :category ORDER BY createdAt DESC")
    fun getItemsByCategory(category: String): Flow<List<WardrobeItem>>

    @Query("SELECT * FROM wardrobe_items WHERE id = :id LIMIT 1")
    suspend fun getItemById(id: String): WardrobeItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: WardrobeItem)

    @Update
    suspend fun updateItem(item: WardrobeItem)

    @Query("DELETE FROM wardrobe_items WHERE id = :id")
    suspend fun deleteItem(id: String)

    // ------------------------------------------------------------------
    // SECURITY (Agent #14) — item 7: injection-hardened surface.
    // ------------------------------------------------------------------

    /**
     * LIKE search. Every user-supplied value is BOUND (`:query`), never concatenated.
     * The caller MUST pass an already-escaped pattern via SqlGuard.likeParam() so a
     * literal '%' typed by the user cannot widen the result set.
     * `ESCAPE '\'` makes the escape character authoritative in SQLite.
     */
    @Query(
        "SELECT * FROM wardrobe_items " +
            "WHERE type LIKE :query ESCAPE '\\' " +
            "OR color LIKE :query ESCAPE '\\' " +
            "OR brand LIKE :query ESCAPE '\\' " +
            "ORDER BY createdAt DESC"
    )
    fun searchItems(query: String): Flow<List<WardrobeItem>>

    /** Bound multi-parameter filter — no string building anywhere. */
    @Query(
        "SELECT * FROM wardrobe_items " +
            "WHERE category = :category AND season = :season " +
            "ORDER BY createdAt DESC"
    )
    fun getItemsByCategoryAndSeason(category: String, season: String): Flow<List<WardrobeItem>>

    /**
     * Parameterised sort. [sortSql] is NOT user input: it MUST be the output of
     * SqlGuard.safeOrderBy(), which returns one of a fixed set of hard-coded
     * fragments. Anything else is a programming error by definition.
     */
    @RawQuery(observedEntities = [WardrobeItem::class])
    fun getItemsSorted(sortSql: SupportSQLiteQuery): Flow<List<WardrobeItem>>

    @Query("SELECT COUNT(*) FROM wardrobe_items WHERE category = :category")
    fun countByCategory(category: String): Flow<Int>
}
