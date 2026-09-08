package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.models.WardrobeItem
import com.example.network.Content
import com.example.network.GenerateContentRequest
import com.example.network.NetworkModule
import com.example.network.Part
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class AiGeneratorViewModel : ViewModel() {
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

                val request = GenerateContentRequest(
                    contents = listOf(
                        Content(parts = listOf(Part(text = prompt)))
                    )
                )

                val response = NetworkModule.geminiApiService.generateContent(request = request)
                val textResponse = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                
                if (textResponse != null) {
                    _generatedResult.value = textResponse
                } else {
                    _error.value = "Failed to generate outfit. Please try again."
                }
            } catch (e: Exception) {
                _error.value = "Error connecting to AI Stylist: ${e.message}\nMake sure your Gemini API Key is set in the Secrets panel."
            } finally {
                _isGenerating.value = false
            }
        }
    }
}
