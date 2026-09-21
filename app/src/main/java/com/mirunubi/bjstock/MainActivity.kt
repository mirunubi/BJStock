package com.mirunubi.bjstock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mirunubi.bjstock.feature.dashboard.DashboardScreen
import com.mirunubi.bjstock.feature.dashboard.DatabaseInfoScreen
import com.mirunubi.bjstock.ui.theme.BJStockTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BJStockTheme {
                BJStockNavHost()
            }
        }
    }
}

@Composable
private fun BJStockNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "dashboard") {
        composable("dashboard") {
            DashboardScreen(
                onOpenDatabaseInfo = { navController.navigate("database_info") },
            )
        }
        composable("database_info") {
            DatabaseInfoScreen(onBack = { navController.popBackStack() })
        }
    }
}
