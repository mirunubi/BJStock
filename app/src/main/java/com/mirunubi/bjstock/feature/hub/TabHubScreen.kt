package com.mirunubi.bjstock.feature.hub

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mirunubi.bjstock.ui.icons.BJStockIcons
import com.mirunubi.bjstock.ui.navigation.BJStockBottomBar
import com.mirunubi.bjstock.ui.navigation.NavEntry
import com.mirunubi.bjstock.ui.navigation.PrimaryTab
import com.mirunubi.bjstock.ui.navigation.TabHubs
import com.mirunubi.bjstock.ui.navigation.TabTopBar

/** UI-1 tab shell: links to the existing screens for this tab until the tab is redesigned. */
@Composable
fun TabHubScreen(
    tab: PrimaryTab,
    onSelectTab: (PrimaryTab) -> Unit,
    onNavigate: (String) -> Unit,
) {
    Scaffold(
        topBar = { TabTopBar(title = tab.label) },
        bottomBar = { BJStockBottomBar(selected = tab, onSelect = onSelectTab) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "이 탭은 정식 화면을 준비 중입니다. 지금은 기존 화면으로 연결됩니다.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TabHubs.forTab(tab).forEach { entry -> NavEntryCard(entry, onNavigate) }
        }
    }
}

@Composable
fun NavEntryCard(entry: NavEntry, onNavigate: (String) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable { onNavigate(entry.route) },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(entry.description, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(BJStockIcons.ChevronRight, contentDescription = null)
        }
    }
}
