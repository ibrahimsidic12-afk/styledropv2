package com.example.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.models.ItemCategory
import com.example.ui.theme.premiumCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(
    viewModel: WardrobeViewModel = viewModel(
        factory = AppViewModelProvider(LocalContext.current.applicationContext as Application)
    ),
    onNavigateBack: () -> Unit
) {
    val allItems by viewModel.allItems.collectAsStateWithLifecycle()

    val totalItems = allItems.size
    val mostCommonColor = allItems.groupingBy { it.color }.eachCount().maxByOrNull { it.value }?.key ?: "N/A"
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Analytics", style = MaterialTheme.typography.displayMedium) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Wardrobe Stats", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(24.dp))
            
            StatCard(title = "Total Items", value = totalItems.toString())
            Spacer(modifier = Modifier.height(16.dp))
            StatCard(title = "Most Common Color", value = mostCommonColor)
            Spacer(modifier = Modifier.height(24.dp))
            
            Text("By Category", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(16.dp))
            
            ItemCategory.all.forEach { category ->
                val count = allItems.count { it.category == category }
                if (count > 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${ItemCategory.emoji(category)} $category", style = MaterialTheme.typography.bodyLarge)
                        Text(count.toString(), style = MaterialTheme.typography.bodyLarge)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Composable
fun StatCard(title: String, value: String) {
    Column(
        modifier = Modifier.fillMaxWidth().premiumCard(RoundedCornerShape(20.dp)).padding(16.dp)
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(8.dp))
        Text(value, style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
