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
import com.mirunubi.bjstock.feature.factor.FactorTestScreen
import com.mirunubi.bjstock.feature.instrument.InstrumentMasterScreen
import com.mirunubi.bjstock.feature.kis.KisSettingsScreen
import com.mirunubi.bjstock.feature.market.MarketDataTestScreen
import com.mirunubi.bjstock.feature.paper.PaperTradingLabScreen
import com.mirunubi.bjstock.feature.strategy.StrategyLabScreen
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
                onOpenKisSettings = { navController.navigate("kis_settings") },
                onOpenMarketData = { navController.navigate("market_data_test") },
                onOpenInstrumentMaster = { navController.navigate("instrument_master") },
                onOpenFactorTest = { navController.navigate("factor_test") },
                onOpenStrategyLab = { navController.navigate("strategy_lab") },
                onOpenPaperLab = { navController.navigate("paper_lab") },
            )
        }
        composable("database_info") {
            DatabaseInfoScreen(onBack = { navController.popBackStack() })
        }
        composable("kis_settings") {
            KisSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable("market_data_test") {
            MarketDataTestScreen(onBack = { navController.popBackStack() })
        }
        composable("instrument_master") {
            InstrumentMasterScreen(onBack = { navController.popBackStack() })
        }
        composable("factor_test") {
            FactorTestScreen(onBack = { navController.popBackStack() })
        }
        composable("strategy_lab") {
            StrategyLabScreen(onBack = { navController.popBackStack() })
        }
        composable("paper_lab") {
            PaperTradingLabScreen(onBack = { navController.popBackStack() })
        }
    }
}
