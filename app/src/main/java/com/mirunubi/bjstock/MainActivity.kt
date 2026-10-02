package com.mirunubi.bjstock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mirunubi.bjstock.feature.admin.AdminScreen
import com.mirunubi.bjstock.feature.ai.AiAdvisorScreen
import com.mirunubi.bjstock.feature.dashboard.DashboardScreen
import com.mirunubi.bjstock.feature.dashboard.DatabaseInfoScreen
import com.mirunubi.bjstock.feature.factor.FactorTestScreen
import com.mirunubi.bjstock.feature.home.HomeScreen
import com.mirunubi.bjstock.feature.hub.TabHubScreen
import com.mirunubi.bjstock.feature.instrument.InstrumentMasterScreen
import com.mirunubi.bjstock.feature.kis.KisSettingsScreen
import com.mirunubi.bjstock.feature.market.MarketDataTestScreen
import com.mirunubi.bjstock.feature.paper.PaperTradingLabScreen
import com.mirunubi.bjstock.feature.paper.PaperTradingScreen
import com.mirunubi.bjstock.feature.performance.CompareRunsScreen
import com.mirunubi.bjstock.feature.performance.ForwardTestDashboardScreen
import com.mirunubi.bjstock.feature.performance.PerformanceScreen
import com.mirunubi.bjstock.feature.settings.SettingsScreen
import com.mirunubi.bjstock.feature.stocks.StocksScreen
import com.mirunubi.bjstock.feature.strategy.StrategyLabScreen
import com.mirunubi.bjstock.feature.strategy.StrategyScreen
import com.mirunubi.bjstock.feature.theme.ThemesScreen
import com.mirunubi.bjstock.ui.navigation.BJStockRoutes
import com.mirunubi.bjstock.ui.navigation.PrimaryTab
import com.mirunubi.bjstock.ui.navigation.StocksLinks
import com.mirunubi.bjstock.ui.navigation.TabHubs
import com.mirunubi.bjstock.ui.navigation.navigateToTab
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
    val selectTab: (PrimaryTab) -> Unit = { navController.navigateToTab(it) }
    val openSettings: () -> Unit = { navController.navigate(BJStockRoutes.SETTINGS) { launchSingleTop = true } }
    val open: (String) -> Unit = { route -> navController.navigate(route) { launchSingleTop = true } }
    val back: () -> Unit = { navController.popBackStack() }

    NavHost(navController = navController, startDestination = BJStockRoutes.START) {
        composable(BJStockRoutes.HOME) {
            HomeScreen(onSelectTab = selectTab, onOpenSettings = openSettings, onOpenAdmin = { open(BJStockRoutes.ADMIN) })
        }
        composable(BJStockRoutes.STOCKS) {
            StocksScreen(
                onSelectTab = selectTab,
                onOpenSettings = openSettings,
                onOpenThemeManagement = { open(StocksLinks.THEME_MANAGEMENT) },
            )
        }
        composable(BJStockRoutes.STRATEGY) {
            StrategyScreen(onSelectTab = selectTab, onOpenSettings = openSettings)
        }
        composable(BJStockRoutes.PAPER_TRADING) {
            PaperTradingScreen(onSelectTab = selectTab, onOpenSettings = openSettings)
        }
        composable(BJStockRoutes.PERFORMANCE) {
            PerformanceScreen(onSelectTab = selectTab, onOpenSettings = openSettings)
        }
        PrimaryTab.entries.filterNot { it in TabHubs.dedicated }.forEach { tab ->
            composable(tab.route) {
                TabHubScreen(tab = tab, onSelectTab = selectTab, onOpenSettings = openSettings, onNavigate = open)
            }
        }
        composable(BJStockRoutes.SETTINGS) {
            SettingsScreen(onBack = back, onNavigate = open)
        }
        composable(BJStockRoutes.ADMIN) {
            AdminScreen(onBack = back)
        }

        composable(BJStockRoutes.DEV_DASHBOARD) {
            DashboardScreen(
                onOpenDatabaseInfo = { open(BJStockRoutes.DATABASE_INFO) },
                onOpenKisSettings = { open(BJStockRoutes.KIS_SETTINGS) },
                onOpenMarketData = { open(BJStockRoutes.MARKET_DATA) },
                onOpenInstrumentMaster = { open(BJStockRoutes.INSTRUMENT_MASTER) },
                onOpenThemes = { open(BJStockRoutes.THEMES) },
                onOpenFactorTest = { open(BJStockRoutes.FACTOR_TEST) },
                onOpenStrategyLab = { open(BJStockRoutes.STRATEGY_LAB) },
                onOpenPaperLab = { open(BJStockRoutes.PAPER_LAB) },
                onOpenForwardTest = { open(BJStockRoutes.FORWARD_TEST) },
                onOpenAiAdvisor = { open(BJStockRoutes.AI_ADVISOR) },
            )
        }
        composable(BJStockRoutes.DATABASE_INFO) {
            DatabaseInfoScreen(onBack = back)
        }
        composable(BJStockRoutes.THEMES) {
            ThemesScreen(onBack = back)
        }
        composable(BJStockRoutes.KIS_SETTINGS) {
            KisSettingsScreen(onBack = back)
        }
        composable(BJStockRoutes.MARKET_DATA) {
            MarketDataTestScreen(onBack = back)
        }
        composable(BJStockRoutes.INSTRUMENT_MASTER) {
            InstrumentMasterScreen(onBack = back)
        }
        composable(BJStockRoutes.FACTOR_TEST) {
            FactorTestScreen(onBack = back)
        }
        composable(BJStockRoutes.STRATEGY_LAB) {
            StrategyLabScreen(onBack = back)
        }
        composable(BJStockRoutes.PAPER_LAB) {
            PaperTradingLabScreen(onBack = back)
        }
        composable(BJStockRoutes.FORWARD_TEST) {
            ForwardTestDashboardScreen(
                onBack = back,
                onOpenCompare = { open(BJStockRoutes.COMPARE_RUNS) },
            )
        }
        composable(BJStockRoutes.COMPARE_RUNS) {
            CompareRunsScreen(onBack = back)
        }
        composable(BJStockRoutes.AI_ADVISOR) {
            AiAdvisorScreen(onBack = back)
        }
    }
}
