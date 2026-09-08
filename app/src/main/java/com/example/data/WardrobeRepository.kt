package com.example.data

import com.example.models.WardrobeItem
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
}
