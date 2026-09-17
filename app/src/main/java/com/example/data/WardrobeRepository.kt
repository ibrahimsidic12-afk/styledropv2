package com.example.data

import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.models.WardrobeItem
import com.example.security.SecurePhotoStore
import com.example.security.SqlGuard
import kotlinx.coroutines.flow.Flow

class WardrobeRepository(private val wardrobeDao: WardrobeDao) {
    val allItems: Flow<List<WardrobeItem>> = wardrobeDao.getAllItems()

    fun getItemsByCategory(category: String): Flow<List<WardrobeItem>> {
        return wardrobeDao.getItemsByCategory(category)
    }

    suspend fun getItemById(id: String): WardrobeItem? {
        return wardrobeDao.getItemById(id)
    }

    suspend fun insert(item: WardrobeItem) {
        wardrobeDao.insertItem(item)
    }

    suspend fun update(item: WardrobeItem) {
        wardrobeDao.updateItem(item)
    }

    suspend fun delete(id: String) {
        wardrobeDao.deleteItem(id)
    }

    // ------------------------------------------------------------------
    // SECURITY (Agent #14)
    // ------------------------------------------------------------------

    /** Safe search: input is escaped here, bound in the DAO. */
    fun search(rawQuery: String): Flow<List<WardrobeItem>> =
        wardrobeDao.searchItems(SqlGuard.likeParam(SqlGuard.clamp(rawQuery)))

    /**
     * Safe sort: the caller passes a UI key ("recent"/"worn"/...), never SQL.
     * SqlGuard resolves it against an allowlist; unknown values fall back to default.
     * SimpleSQLiteQuery is used ONLY with a whitelisted static fragment — no `?`
     * placeholder is ever filled from user text, because ORDER BY cannot be bound.
     */
    fun sorted(requestedSort: String): Flow<List<WardrobeItem>> =
        wardrobeDao.getItemsSorted(
            SimpleSQLiteQuery("SELECT * FROM wardrobe_items ORDER BY ${SqlGuard.safeOrderBy(requestedSort)}")
        )

    fun countByCategory(category: String): Flow<Int> = wardrobeDao.countByCategory(category)

    /**
     * Deletes the row AND its encrypted photo. Before this, deleting an item left the
     * ciphertext (and any legacy plaintext file) orphaned on disk forever.
     */
    suspend fun deleteItemAndPhoto(context: android.content.Context, item: WardrobeItem) {
        wardrobeDao.deleteItem(item.id)
        SecurePhotoStore.delete(context, item.imageUrl)
    }
}
