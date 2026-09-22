package com.mirunubi.bjstock.feature.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mirunubi.bjstock.core.audit.ApiErrorLogService
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.entity.ApiErrorLogEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatabaseInfoScreen(
    onBack: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
    diagnosticsViewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val apiErrors by diagnosticsViewModel.recentErrors.collectAsStateWithLifecycle()

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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            InfoLine("Database", BJStockDatabase.NAME)
            InfoLine("Room Version", BJStockDatabase.VERSION.toString())
            InfoLine("Schema", "${BJStockDatabase.ENTITY_COUNT} business entities")
            InfoLine("Runtime", "Local SQLite / Room")
            InfoLine("Server", "None")
            InfoLine("Open", if (state.databaseOpen) "YES" else "NO")
            InfoLine("Paper Trading", "Only")

            Text("Recent API Errors (7 days)", style = MaterialTheme.typography.titleSmall)
            Text(
                "Diagnostics only. Secrets are never stored. Rolling 7-day retention.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (apiErrors.isEmpty()) {
                Text("No API errors in the last 7 days")
            } else {
                apiErrors.forEach { error ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(formatOccurredAt(error))
                            Text("${error.provider} ${error.operation}")
                            Text(error.errorType.name)
                            Text(error.safeMessage)
                            if (error.retryable) Text("Retryable")
                            error.httpStatus?.let { Text("HTTP $it") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Text(label)
    Text(value, modifier = Modifier.padding(bottom = 12.dp))
}

private val occurredFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"))

private fun formatOccurredAt(error: ApiErrorLogEntity): String =
    occurredFormatter.format(error.occurredAt)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val apiErrorLogService: ApiErrorLogService,
) : ViewModel() {
    private val _recentErrors = MutableStateFlow<List<ApiErrorLogEntity>>(emptyList())
    val recentErrors: StateFlow<List<ApiErrorLogEntity>> = _recentErrors.asStateFlow()

    init {
        viewModelScope.launch {
            _recentErrors.value = apiErrorLogService.findRecentSevenDays(limit = 50)
        }
    }
}
