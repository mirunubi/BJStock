package com.mirunubi.bjstock.ui.navigation

import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.mirunubi.bjstock.ui.icons.BJStockIcons

@Composable
fun BJStockBottomBar(selected: PrimaryTab, onSelect: (PrimaryTab) -> Unit) {
    NavigationBar {
        PrimaryTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { if (tab != selected) onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label, fontWeight = if (tab == selected) FontWeight.Bold else FontWeight.Normal) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabTopBar(title: String, onOpenSettings: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.Bold) },
        actions = {
            IconButton(onClick = onOpenSettings) {
                Icon(BJStockIcons.Settings, contentDescription = "설정")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: () -> Unit) {
    CenterAlignedTopAppBar(
        title = { Text(title, fontWeight = FontWeight.Bold) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(BJStockIcons.Back, contentDescription = "뒤로")
            }
        },
    )
}

/** Tab switch without stacking duplicates: back from any tab returns to Home, back on Home leaves the app. */
fun NavController.navigateToTab(tab: PrimaryTab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
