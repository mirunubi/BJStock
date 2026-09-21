package com.mirunubi.bjstock.feature.instrument

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
fun InstrumentMasterScreen(
    onBack: () -> Unit,
    viewModel: InstrumentMasterViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Instrument Master") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Development only. Master sync and selected-instrument history.")
                Text("KOSPI Active Count: ${state.kospiActiveCount}")
                Text("KOSDAQ Active Count: ${state.kosdaqActiveCount}")
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = viewModel::syncKospi,
                    enabled = !state.kospiSyncing,
                ) {
                    Text(if (state.kospiSyncing) "KOSPI Syncing..." else "KOSPI Master Sync")
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = viewModel::syncKosdaq,
                    enabled = !state.kosdaqSyncing,
                ) {
                    Text(if (state.kosdaqSyncing) "KOSDAQ Syncing..." else "KOSDAQ Master Sync")
                }
                state.masterResult?.let { Text(it) }
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            item {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.query,
                    onValueChange = viewModel::onQueryChanged,
                    label = { Text("Search symbol or name") },
                    singleLine = true,
                )
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = viewModel::search,
                ) { Text("Search") }
            }
            items(state.searchResults, key = { it.id }) { instrument ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.select(instrument) },
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("${instrument.name} ${instrument.symbol} ${instrument.board}")
                        Text(
                            "${instrument.instrumentType} active=${instrument.isActive}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            item {
                val selected = state.selected
                if (selected != null) {
                    Text("${selected.name}")
                    Text("${selected.symbol}")
                    Text("${selected.board}")
                    Text("Latest: ${state.latestDate ?: "-"}")
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = state.startDate,
                        onValueChange = viewModel::onStartDateChanged,
                        label = { Text("Start Date") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = state.endDate,
                        onValueChange = viewModel::onEndDateChanged,
                        label = { Text("End Date") },
                        singleLine = true,
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = viewModel::syncHistory,
                        enabled = !state.historicalSyncing,
                    ) {
                        Text(if (state.historicalSyncing) "Syncing..." else "Historical Sync")
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = viewModel::syncFromLatest,
                        enabled = !state.incrementalSyncing && state.latestDate != null,
                    ) {
                        Text(if (state.incrementalSyncing) "Syncing..." else "Sync From Latest")
                    }
                    state.historicalResult?.let { Text(it) }
                }
            }
        }
    }
}
