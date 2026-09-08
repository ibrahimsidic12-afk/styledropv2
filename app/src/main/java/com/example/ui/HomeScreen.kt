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
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.models.ItemCategory

@Composable
fun HomeScreen(
    viewModel: WardrobeViewModel = viewModel(
        factory = AppViewModelProvider(LocalContext.current.applicationContext as Application)
    ),
    onNavigateToAI: () -> Unit
) {
    val allItems by viewModel.allItems.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            // Invisible or blank top app bar just to reserve the top inset space, 
            // or we can let the content flow but pad it using window insets.
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Good Morning, StyleDrop",
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = "Here's your style overview for today.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(24.dp))

        // Weather Card Placeholder
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("⛅", style = MaterialTheme.typography.displayMedium)
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = "Today's Weather • Unknown",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "22°C • Partly Cloudy",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Wardrobe Status",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val topCount = allItems.count { it.category == ItemCategory.TOP }
            val bottomCount = allItems.count { it.category == ItemCategory.BOTTOM }
            val shoeCount = allItems.count { it.category == ItemCategory.SHOES }

            StatTile(modifier = Modifier.weight(1f), emoji = ItemCategory.emoji(ItemCategory.TOP), label = "Tops", count = topCount)
            StatTile(modifier = Modifier.weight(1f), emoji = ItemCategory.emoji(ItemCategory.BOTTOM), label = "Bottoms", count = bottomCount)
            StatTile(modifier = Modifier.weight(1f), emoji = ItemCategory.emoji(ItemCategory.SHOES), label = "Shoes", count = shoeCount)
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Recommended Outfit",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(12.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(20.dp)
        ) {
            Text(
                text = "Add a top, a bottom and a pair of shoes to get your first recommended outfit here.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = onNavigateToAI,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("TRY GENERATING")
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
    }
    }
}

@Composable
fun StatTile(modifier: Modifier = Modifier, emoji: String, label: String, count: Int) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = emoji, style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = count.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
