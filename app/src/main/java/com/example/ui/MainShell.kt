package com.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Checkroom
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun MainShell(
    onItemClick: (com.example.models.WardrobeItem) -> Unit,
    onAddItemClick: (String) -> Unit,
    onAnalyticsClick: () -> Unit
) {
    var selectedIndex by remember { mutableIntStateOf(0) }

    val items = listOf(
        NavigationItem("Home", Icons.Filled.Home, Icons.Outlined.Home),
        NavigationItem("Wardrobe", Icons.Filled.Checkroom, Icons.Outlined.Checkroom),
        NavigationItem("AI", Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome),
        NavigationItem("Saved", Icons.Filled.Favorite, Icons.Outlined.FavoriteBorder),
        NavigationItem("Profile", Icons.Filled.Person, Icons.Outlined.Person)
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                items.forEachIndexed { index, item ->
                    NavigationBarItem(
                        icon = {
                            Icon(
                                if (selectedIndex == index) item.selectedIcon else item.unselectedIcon,
                                contentDescription = item.label
                            )
                        },
                        label = { Text(item.label) },
                        selected = selectedIndex == index,
                        onClick = { selectedIndex = index }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()).fillMaxSize()) {
            when (selectedIndex) {
                0 -> HomeScreen(
                    onNavigateToAI = { selectedIndex = 2 }
                )
                1 -> WardrobeScreen(
                    onItemClick = onItemClick,
                    onAddItemClick = onAddItemClick,
                    onAnalyticsClick = onAnalyticsClick
                )
                2 -> AiGeneratorScreen()
                3 -> SavedOutfitsScreen()
                4 -> ProfileScreen()
            }
        }
    }
}

@Composable
fun PlaceholderScreen(title: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = title, style = MaterialTheme.typography.headlineMedium)
    }
}

data class NavigationItem(
    val label: String,
    val selectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    val unselectedIcon: androidx.compose.ui.graphics.vector.ImageVector
)
