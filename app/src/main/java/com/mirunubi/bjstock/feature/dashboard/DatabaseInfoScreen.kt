package com.mirunubi.bjstock.feature.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mirunubi.bjstock.core.database.BJStockDatabase

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatabaseInfoScreen(
    onBack: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Database Info") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
        ) {
            InfoLine("Database", BJStockDatabase.NAME)
            InfoLine("Room Version", BJStockDatabase.VERSION.toString())
            InfoLine("Schema", "${BJStockDatabase.ENTITY_COUNT} business entities")
            InfoLine("Runtime", "Local SQLite / Room")
            InfoLine("Server", "None")
            InfoLine("Open", if (state.databaseOpen) "YES" else "NO")
            InfoLine("Paper Trading", "Only")
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Text("$label")
    Text(value, modifier = Modifier.padding(bottom = 12.dp))
}
