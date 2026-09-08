package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.LoginScreen
import com.example.ui.WardrobeScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        val navController = rememberNavController()
        Box(modifier = Modifier.fillMaxSize()) {
            NavHost(navController = navController, startDestination = "login") {
                composable("login") {
                    LoginScreen(
                        onLoginClick = {
                            navController.navigate("main_shell") {
                                popUpTo("login") { inclusive = true }
                            }
                        }
                    )
                }
                composable("main_shell") {
                    com.example.ui.MainShell(
                        onItemClick = { item -> 
                            navController.navigate("item_detail/${item.id}")
                        },
                        onAddItemClick = { category -> 
                            navController.navigate("add_item/$category")
                        },
                        onAnalyticsClick = { 
                            navController.navigate("analytics")
                        }
                    )
                }
                composable("item_detail/{itemId}") { backStackEntry ->
                    val itemId = backStackEntry.arguments?.getString("itemId") ?: return@composable
                    com.example.ui.ItemDetailScreen(
                        itemId = itemId,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
                composable("add_item/{category}") { backStackEntry ->
                    val category = backStackEntry.arguments?.getString("category") ?: com.example.models.ItemCategory.TOP
                    com.example.ui.AddItemScreen(
                        initialCategory = category,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
                composable("analytics") {
                    com.example.ui.AnalyticsScreen(
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
            }
        }
      }
    }
  }
}
