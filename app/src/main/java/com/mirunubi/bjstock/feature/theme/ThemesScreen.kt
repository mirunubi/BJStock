package com.mirunubi.bjstock.feature.theme

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemesScreen(
    onBack: () -> Unit,
    viewModel: ThemesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Themes") },
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
                    "Interest baskets from local instrument master. Changes do not mutate Forward Test universes.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.newName,
                    onValueChange = viewModel::onNewNameChanged,
                    label = { Text("Theme name") },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.newDescription,
                    onValueChange = viewModel::onNewDescriptionChanged,
                    label = { Text("Description (optional)") },
                    singleLine = true,
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = viewModel::createTheme,
                    enabled = !state.busy,
                ) { Text("Create Theme") }
            }
            item {
                Text("Themes", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.themes.forEach { theme ->
                        FilterChip(
                            selected = theme.id == state.selectedThemeId,
                            onClick = { viewModel.selectTheme(theme.id) },
                            label = {
                                Text(
                                    "${theme.name}${if (!theme.isActive) " (inactive)" else ""}",
                                )
                            },
                        )
                    }
                }
            }
            item {
                val theme = state.selectedTheme ?: return@item
                Text("${theme.name} — ${state.membershipCount} instruments")
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.renameName,
                    onValueChange = viewModel::onRenameChanged,
                    label = { Text("Rename") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = viewModel::renameTheme) { Text("Save Name") }
                    TextButton(onClick = { viewModel.setActive(!theme.isActive) }) {
                        Text(if (theme.isActive) "Deactivate" else "Activate")
                    }
                }
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.searchQuery,
                    onValueChange = viewModel::onSearchChanged,
                    label = { Text("Search instruments") },
                    singleLine = true,
                )
            }
            items(state.searchResults, key = { "search-${it.id}" }) { instrument ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.addInstrument(instrument.id) },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${instrument.symbol} ${instrument.name}")
                        Text("Tap to add", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Text("Members", style = MaterialTheme.typography.titleSmall)
            }
            items(state.memberships, key = { it.instrumentId }) { row ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("${row.symbol} ${row.name}")
                        TextButton(onClick = { viewModel.removeInstrument(row.instrumentId) }) {
                            Text("Remove")
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
