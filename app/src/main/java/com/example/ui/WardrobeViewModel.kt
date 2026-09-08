package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.WardrobeRepository
import com.example.models.ItemCategory
import com.example.models.WardrobeItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class WardrobeViewModel(private val repository: WardrobeRepository) : ViewModel() {
    
    val allItems: StateFlow<List<WardrobeItem>> = repository.allItems
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun getItemsByCategory(category: String): StateFlow<List<WardrobeItem>> {
        return allItems.map { items -> items.filter { it.category == category } }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )
    }

    fun getCategoryCount(category: String): StateFlow<Int> {
        return allItems.map { items -> items.count { it.category == category } }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = 0
            )
    }

    fun addItem(item: WardrobeItem) {
        viewModelScope.launch {
            repository.insert(item)
        }
    }

    suspend fun getItemById(id: String): WardrobeItem? {
        return repository.getItemById(id)
    }

    fun updateItem(item: WardrobeItem) {
        viewModelScope.launch {
            repository.update(item)
        }
    }

    fun deleteItem(id: String) {
        viewModelScope.launch {
            repository.delete(id)
        }
    }
}
