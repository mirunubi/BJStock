package com.mirunubi.bjstock.feature.factor

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
fun FactorTestScreen(
    onBack: () -> Unit,
    viewModel: FactorTestViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Factor Test") },
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
                Text("Development only. Local Room market data. No weights, BUY/SELL, or AI.")
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.query,
                    onValueChange = viewModel::onQueryChanged,
                    label = { Text("Search symbol or name") },
                    singleLine = true,
                )
                Button(
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
                    }
                }
            }
            item {
                val selected = state.selected
                if (selected != null) {
                    Text("${selected.name}")
                    Text("${selected.symbol}")
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = state.evaluationDate,
                        onValueChange = viewModel::onEvaluationDateChanged,
                        label = { Text("Evaluation Date") },
                        singleLine = true,
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = viewModel::calculate,
                        enabled = !state.calculating,
                    ) {
                        Text(if (state.calculating) "Calculating..." else "Calculate Factors")
                    }
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            items(state.rows, key = { it.code }) { row ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(row.code, style = MaterialTheme.typography.titleSmall)
                        Text("Status: ${row.status}")
                        Text("Raw: ${row.raw}")
                        Text("Score: ${row.score}")
                    }
                }
            }
        }
    }
}
