package com.mirunubi.bjstock.feature.performance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import com.mirunubi.bjstock.core.analytics.PerformanceMath

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareRunsScreen(
    onBack: () -> Unit,
    viewModel: ForwardTestViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Compare Runs") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
                actions = {
                    TextButton(onClick = { viewModel.loadComparison() }) {
                        Text("Compare")
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
            Text(
                "Select up to 3 runs. Read-only — strategy/policy/history are not edited here.",
                style = MaterialTheme.typography.bodyMedium,
            )
            state.message?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            state.compareCandidates.forEach { run ->
                FilterChip(
                    selected = run.id in state.selectedCompareIds,
                    onClick = { viewModel.toggleCompare(run.id) },
                    label = { Text("${run.id} ${run.runName}") },
                )
            }
            state.comparisonRows.forEach { row ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "${row.runName} (${row.runId})",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text("${row.strategyName} ${row.strategyVersion}")
                        Text(
                            "Period ${row.startDate ?: "?"} ~ ${row.endDate ?: "?"} " +
                                "(${row.tradingDays} trading days)",
                        )
                        Text("Initial ${PerformanceMath.formatWon(row.initialCash)}")
                        Text("Latest ${ForwardTestViewModel.formatWon(row.latestAsset)}")
                        Text(
                            "Return ${ForwardTestViewModel.formatPercent(row.cumulativeReturn)}",
                        )
                        Text("MDD ${ForwardTestViewModel.formatPercent(row.maxDrawdown)}")
                        Text("Closed Trades ${row.closedTrades}")
                        Text("Win Rate ${ForwardTestViewModel.formatPercent(row.winRate)}")
                        Text("Policy ${row.policyVersion ?: "N/A"}")
                        row.buyAllocationRate?.let {
                            Text("BUY Allocation ${ForwardTestViewModel.formatPercent(it)}")
                        }
                        row.commissionRate?.let {
                            Text(
                                "Commission Assumption " +
                                    ForwardTestViewModel.formatRateAsAssumption(it),
                            )
                        }
                        row.sellTaxRate?.let {
                            Text(
                                "Sell Tax Assumption " +
                                    ForwardTestViewModel.formatRateAsAssumption(it),
                            )
                        }
                        Text("Simulation Assumption")
                    }
                }
            }
            if (state.comparisonRows.isEmpty()) {
                Text("Tap Compare after selecting 2–3 runs.")
            }
        }
    }
}
