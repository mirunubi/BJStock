package com.mirunubi.bjstock.feature.paper

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
fun PaperTradingLabScreen(
    onBack: () -> Unit,
    viewModel: PaperTradingLabViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Paper Trading Lab") },
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
                Text(
                    "Virtual fills only. No KIS order API. No WorkManager.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Strategy Runs", style = MaterialTheme.typography.titleSmall)
            }
            items(state.runs, key = { it.id }) { run ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.selectRun(run.id) },
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("${run.runName} (#${run.id}) ${run.status}")
                    }
                }
            }
            item {
                if (state.selectedRunId != null) {
                    Text(state.strategyLabel, style = MaterialTheme.typography.titleMedium)
                    Text("Initial Cash: ${PaperTradingLabViewModel.formatWon(state.initialCash)}")
                    Text("Cash: ${PaperTradingLabViewModel.formatWon(state.currentCash)}")
                    Text("Market Value: ${PaperTradingLabViewModel.formatWon(state.marketValue)}")
                    Text("Total: ${PaperTradingLabViewModel.formatWon(state.totalAsset)}")
                    Text("Return: ${state.cumulativeReturnLabel}")
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = viewModel::processEvaluations,
                    ) { Text("Process Evaluations") }
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = viewModel::processPendingOrders,
                    ) { Text("Process Pending Orders") }
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = state.snapshotDate,
                        onValueChange = viewModel::onSnapshotDateChanged,
                        label = { Text("Snapshot Date YYYY-MM-DD") },
                        singleLine = true,
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = viewModel::createDailySnapshot,
                    ) { Text("Create Daily Snapshot") }
                    Text("Positions", style = MaterialTheme.typography.titleSmall)
                }
            }
            items(state.positions, key = { "pos-${it.symbol}-${it.quantity}" }) { position ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("${position.name}")
                        Text("${position.quantity}주")
                        Text("Average ${PaperTradingLabViewModel.formatWon(position.averagePrice)}")
                        position.latestClose?.let {
                            Text("Latest Close ${PaperTradingLabViewModel.formatWon(it)}")
                        }
                        position.marketValue?.let {
                            Text("Market Value ${PaperTradingLabViewModel.formatWon(it)}")
                        }
                    }
                }
            }
            item {
                if (state.selectedRunId != null) {
                    Text("Orders", style = MaterialTheme.typography.titleSmall)
                }
            }
            items(state.orders, key = { it.id }) { order ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Signal Date ${order.signalDate ?: "-"}")
                        Text("Execution Date ${order.executionDate ?: "-"}")
                        Text("${order.symbol} ${order.side}")
                        Text("Status ${order.status}")
                        Text("Quantity ${order.quantity}")
                        order.executionPrice?.let {
                            Text("Execution Price ${PaperTradingLabViewModel.formatWon(it)}")
                        }
                    }
                }
            }
            item {
                state.message?.let { Text(it) }
            }
        }
    }
}
