package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.StylistAiService
import com.example.models.WardrobeItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AiGeneratorViewModel(
    // SECURITY (Agent #14): the only outbound AI path. No API key exists on the client;
    // the request is authorised by App Check (see StyleDropApplication).
    private val stylist: StylistAiService = StylistAiService()
) : ViewModel() {
    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _generatedResult = MutableStateFlow<String?>(null)
    val generatedResult: StateFlow<String?> = _generatedResult.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun generateOutfit(
        wardrobeItems: List<WardrobeItem>,
        occasion: String,
        style: String,
        colorPref: String,
        shoePref: String,
        weather: String,
        count: Int
    ) {
        if (wardrobeItems.isEmpty()) {
            _error.value = "Your wardrobe is empty! Add some items first."
            return
        }
        
        _isGenerating.value = true
        _error.value = null
        _generatedResult.value = null

        viewModelScope.launch {
            try {
                val wardrobeStr = wardrobeItems.joinToString("\n") { 
                    "- ${it.color} ${it.pattern} ${it.category} (${it.type}) [ID: ${it.id}]" 
                }
                
                val prompt = """
                    You are an expert AI Stylist. The user has the following wardrobe items:
                    $wardrobeStr
                    
                    They want $count outfit(s) for a '$occasion' occasion.
                    Preferred style: $style
                    Color preference: $colorPref
                    Weather context: $weather
                    Shoe preference: $shoePref
                    
                    Only use the exact items provided in the wardrobe list.
                    Create the outfits and return your response in a fun, conversational tone, 
                    listing the exact items (with their colors) you chose for each outfit, and 
                    a brief explanation of why this outfit works.
                """.trimIndent()

                stylist.generate(prompt)
                    .onSuccess { text -> _generatedResult.value = text }
                    .onFailure { _error.value = "The atelier is quiet right now. Please try again." }
            } catch (e: Exception) {
                // Never echo server/exception detail into the UI (information leak).
                _error.value = "The atelier is quiet right now. Please try again."
            } finally {
                _isGenerating.value = false
            }
        }
    }
}
