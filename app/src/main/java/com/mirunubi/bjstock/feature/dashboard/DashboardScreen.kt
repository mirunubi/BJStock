package com.mirunubi.bjstock.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onOpenDatabaseInfo: () -> Unit,
    onOpenKisSettings: () -> Unit,
    onOpenMarketData: () -> Unit,
    onOpenInstrumentMaster: () -> Unit,
    onOpenFactorTest: () -> Unit,
    onOpenStrategyLab: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BJStock") },
                actions = {
                    TextButton(onClick = onOpenStrategyLab) {
                        Text("Strategy Lab")
                    }
                    TextButton(onClick = onOpenFactorTest) {
                        Text("Factors")
                    }
                    TextButton(onClick = onOpenInstrumentMaster) {
                        Text("Instrument Master")
                    }
                    TextButton(onClick = onOpenMarketData) {
                        Text("Market Data")
                    }
                    TextButton(onClick = onOpenKisSettings) {
                        Text("KIS Settings")
                    }
                    TextButton(onClick = onOpenDatabaseInfo) {
                        Text("Database Info")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Paper Trading Strategy Lab", style = MaterialTheme.typography.titleMedium)
            Text(
                "Paper Trading Only",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            StatusCard(title = "Trading Mode", value = "PAPER ONLY")
            StatusCard(title = "KIS", value = state.kisAuthState.name.replace('_', ' '))
            StatusCard(title = "AI Advisor", value = "OFF")
            StatusCard(
                title = "Local Database",
                value = if (state.databaseOpen) "READY" else "OPENING",
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("DB Summary", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    CountRow("Instruments", state.instrumentCount)
                    CountRow("Strategies", state.strategyCount)
                    CountRow("Strategy Runs", state.strategyRunCount)
                }
            }
        }
    }
}

@Composable
private fun StatusCard(title: String, value: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun CountRow(label: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Text(count.toString())
    }
}
