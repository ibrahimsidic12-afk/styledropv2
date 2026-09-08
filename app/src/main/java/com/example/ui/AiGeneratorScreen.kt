package com.example.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.models.AppConstants

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiGeneratorScreen(
    wardrobeViewModel: WardrobeViewModel = viewModel(
        factory = AppViewModelProvider(LocalContext.current.applicationContext as Application)
    ),
    aiViewModel: AiGeneratorViewModel = viewModel()
) {
    val allItems by wardrobeViewModel.allItems.collectAsStateWithLifecycle()
    val isGenerating by aiViewModel.isGenerating.collectAsStateWithLifecycle()
    val generatedResult by aiViewModel.generatedResult.collectAsStateWithLifecycle()
    val errorMsg by aiViewModel.error.collectAsStateWithLifecycle()

    var occasion by remember { mutableStateOf(AppConstants.occasions.first()) }
    var style by remember { mutableStateOf(AppConstants.styles.first()) }
    var colorPref by remember { mutableStateOf(AppConstants.colorPreferences.first()) }
    var shoePref by remember { mutableStateOf(AppConstants.shoePreferences.first()) }
    var autoWeather by remember { mutableStateOf(true) }
    var onlyMyWardrobe by remember { mutableStateOf(true) }
    
    val numberOfOutfits = listOf("1 outfit", "3 outfits", "5 outfits")
    var selectedCount by remember { mutableStateOf(numberOfOutfits.first()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("✨ AI Stylist") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Create your outfit",
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(20.dp))

            if (errorMsg != null) {
                Text(
                    text = errorMsg!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            if (generatedResult != null) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "✨ Stylist Recommendation", 
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = generatedResult!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            DropdownField("Occasion", occasion, AppConstants.occasions) { occasion = it }
            Spacer(modifier = Modifier.height(16.dp))
            DropdownField("Style", style, AppConstants.styles) { style = it }
            Spacer(modifier = Modifier.height(16.dp))
            DropdownField("Color preference", colorPref, AppConstants.colorPreferences) { colorPref = it }
            Spacer(modifier = Modifier.height(16.dp))

            Text("Weather", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Automatic", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = autoWeather,
                    onCheckedChange = { autoWeather = it }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            DropdownField("Shoes", shoePref, AppConstants.shoePreferences) { shoePref = it }
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Use only my wardrobe", style = MaterialTheme.typography.bodyLarge)
                    Text("Never suggests items you don't own", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = onlyMyWardrobe,
                    onCheckedChange = { onlyMyWardrobe = it },
                    enabled = false
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            DropdownField("Number of outfits", selectedCount, numberOfOutfits) { selectedCount = it }
            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = { 
                    val countStr = selectedCount.split(" ").firstOrNull() ?: "1"
                    val count = countStr.toIntOrNull() ?: 1
                    val weatherStr = if (autoWeather) "22°C Partly Cloudy" else "Mild"
                    
                    aiViewModel.generateOutfit(
                        wardrobeItems = allItems,
                        occasion = occasion,
                        style = style,
                        colorPref = colorPref,
                        shoePref = shoePref,
                        weather = weatherStr,
                        count = count
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                contentPadding = PaddingValues(vertical = 16.dp),
                enabled = !isGenerating
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("GENERATING...")
                } else {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("GENERATE")
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
