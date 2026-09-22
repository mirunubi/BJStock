package com.mirunubi.bjstock.feature.strategy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mirunubi.bjstock.core.strategy.StrategyEvaluationStatus
import com.mirunubi.bjstock.core.strategy.StrategyScoreMath
import java.math.BigDecimal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StrategyLabScreen(
    onBack: () -> Unit,
    viewModel: StrategyLabViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Strategy Lab") },
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
                    "Development editor. BUY/HOLD/SELL is a judgment, not a paper order.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.newCode,
                    onValueChange = viewModel::onNewCodeChanged,
                    label = { Text("Strategy code") },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.newName,
                    onValueChange = viewModel::onNewNameChanged,
                    label = { Text("Strategy name") },
                    singleLine = true,
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = viewModel::createStrategy,
                ) { Text("Create Strategy + Draft") }
            }
            item {
                Text("Strategies", style = MaterialTheme.typography.titleSmall)
            }
            items(state.strategies, key = { it.id }) { strategy ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.selectStrategy(strategy.id) },
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("${strategy.strategyCode} — ${strategy.strategyName}")
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::createDraftVersion) { Text("New Draft") }
                    Button(onClick = viewModel::copySelectedVersion) { Text("Copy Version") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.versions.forEach { version ->
                        FilterChip(
                            selected = version.id == state.selectedVersionId,
                            onClick = { viewModel.selectVersion(version.id) },
                            label = { Text("V${version.versionNo} ${version.status}") },
                        )
                    }
                }
            }
            item {
                val version = state.selectedVersion
                if (version != null) {
                    Text("V${version.versionNo} ${version.status}")
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = state.sellThreshold,
                        onValueChange = viewModel::onSellChanged,
                        label = { Text("Sell threshold") },
                        enabled = state.isDraft,
                        singleLine = true,
                    )
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = state.buyThreshold,
                        onValueChange = viewModel::onBuyChanged,
                        label = { Text("Buy threshold") },
                        enabled = state.isDraft,
                        singleLine = true,
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = viewModel::saveDraftThresholds,
                        enabled = state.isDraft,
                    ) { Text("Save Thresholds") }
                    Text(state.weightTotalLabel, style = MaterialTheme.typography.titleSmall)
                    if (state.enabledWeightPercent.compareTo(BigDecimal.valueOf(100)) != 0) {
                        Text("Activation requires Total Weight 100%")
                    }
                }
            }
            items(state.factors, key = { it.factorCode }) { factor ->
                FactorEditorCard(
                    factor = factor,
                    enabled = state.isDraft,
                    onEnabled = { viewModel.onFactorEnabled(factor.factorCode, it) },
                    onWeight = { viewModel.onFactorWeight(factor.factorCode, it) },
                    onWeightDone = { viewModel.persistFactorWeight(factor.factorCode) },
                    onVersion = { viewModel.onFactorVersion(factor.factorCode, it) },
                    onMin = { viewModel.onFactorMin(factor.factorCode, it) },
                    onMax = { viewModel.onFactorMax(factor.factorCode, it) },
                    onGateDone = { viewModel.persistFactorGate(factor.factorCode) },
                )
            }
            item {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = viewModel::activate,
                    enabled = state.isDraft,
                ) { Text("Activate Version") }
                Text("Preview Evaluation", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.query,
                    onValueChange = viewModel::onQueryChanged,
                    label = { Text("Instrument search") },
                    singleLine = true,
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = viewModel::searchInstruments,
                ) { Text("Search") }
            }
            items(state.searchResults, key = { "inst-${it.id}" }) { instrument ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.selectInstrument(instrument) },
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("${instrument.name} ${instrument.symbol}")
                    }
                }
            }
            item {
                val selected = state.selectedInstrument
                if (selected != null) {
                    Text("Selected ${selected.name}")
                }
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.evaluationDate,
                    onValueChange = viewModel::onEvaluationDateChanged,
                    label = { Text("Evaluation Date YYYY-MM-DD") },
                    singleLine = true,
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = viewModel::preview,
                ) { Text("Preview Evaluation") }
                PreviewCard(state)
                state.message?.let { Text(it) }
            }
        }
    }
}

@Composable
private fun FactorEditorCard(
    factor: StrategyFactorEditorUi,
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    onWeight: (String) -> Unit,
    onWeightDone: () -> Unit,
    onVersion: (String) -> Unit,
    onMin: (String) -> Unit,
    onMax: (String) -> Unit,
    onGateDone: () -> Unit,
) {
    var versionMenu by remember(factor.factorCode) { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = factor.enabled,
                    onCheckedChange = onEnabled,
                    enabled = enabled,
                )
                Column {
                    Text(factor.factorName, style = MaterialTheme.typography.titleSmall)
                    Text(factor.factorCode, style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = factor.weightPercent,
                onValueChange = onWeight,
                label = { Text("Weight %") },
                enabled = enabled,
                singleLine = true,
            )
            Button(onClick = onWeightDone, enabled = enabled) { Text("Save Weight") }
            Text("Calculation Version")
            TextButton(
                onClick = { versionMenu = true },
                enabled = enabled && factor.availableVersions.size > 1,
            ) { Text(factor.calculationVersion) }
            DropdownMenu(expanded = versionMenu, onDismissRequest = { versionMenu = false }) {
                factor.availableVersions.forEach { version ->
                    DropdownMenuItem(
                        text = { Text(version) },
                        onClick = {
                            versionMenu = false
                            onVersion(version)
                        },
                    )
                }
            }
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = factor.minScore,
                onValueChange = onMin,
                label = { Text("Optional min score") },
                enabled = enabled,
                singleLine = true,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = factor.maxScore,
                onValueChange = onMax,
                label = { Text("Optional max score") },
                enabled = enabled,
                singleLine = true,
            )
            Button(onClick = onGateDone, enabled = enabled) { Text("Save Gates") }
        }
    }
}

@Composable
private fun PreviewCard(state: StrategyLabUiState) {
    val preview = state.preview ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val instrument = state.selectedInstrument
            if (instrument != null) {
                Text("${instrument.name}")
                Text(state.evaluationDate)
            }
            when (preview.status) {
                StrategyEvaluationStatus.INSUFFICIENT_FACTORS -> {
                    preview.missingFactorCodes.forEach { code ->
                        Text("$code")
                        Text("INSUFFICIENT FACTOR DATA")
                    }
                    Text("Quant score and BUY/HOLD/SELL are not confirmed.")
                }
                StrategyEvaluationStatus.FACTOR_GATE_FAILED,
                StrategyEvaluationStatus.SUCCESS,
                -> {
                    StrategyLabViewModel.previewRows(preview).forEach { row ->
                        Text(row.factorCode, style = MaterialTheme.typography.titleSmall)
                        Text("Score ${row.score}")
                        Text("Weight ${row.weightPercent}%")
                        Text("Contribution ${row.contribution}")
                        if (row.gateFailed) {
                            row.gateNote?.let { Text(it) }
                            Text("Factor Gate Failed")
                        }
                    }
                    preview.quantScoreStored?.let { stored ->
                        Text(
                            "Quant Score ${StrategyScoreMath.scoreToDisplay(stored).stripTrailingZeros().toPlainString()}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    Text("Decision ${preview.quantDecision}")
                }
                else -> {
                    Text(preview.status.name)
                    preview.message?.let { Text(it) }
                }
            }
        }
    }
}
