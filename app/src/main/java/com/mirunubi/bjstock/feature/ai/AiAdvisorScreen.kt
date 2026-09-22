package com.mirunubi.bjstock.feature.ai

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mirunubi.bjstock.core.ai.AiAdvisoryMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAdvisorScreen(
    onBack: () -> Unit,
    viewModel: AiAdvisorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Advisor") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Advisory only. AI never places paper or live orders and never changes quant decisions.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Mode", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiAdvisoryMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.mode == mode,
                        onClick = { viewModel.setMode(mode) },
                        label = { Text(mode.name) },
                    )
                }
            }
            if (state.mode == AiAdvisoryMode.OFF) {
                Text("AI Advisor OFF", style = MaterialTheme.typography.titleMedium)
            }
            if (state.mode == AiAdvisoryMode.OPENAI_API_FUTURE) {
                Text(
                    "OPENAI_API_FUTURE is an architecture placeholder. No network calls. " +
                        "Future path: Android → BJStock Gateway → OpenAI.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Text("Strategy Run", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.runs.forEach { run ->
                    FilterChip(
                        selected = state.selectedRunId == run.id,
                        onClick = { viewModel.selectRun(run.id) },
                        label = { Text("${run.id}:${run.runName}") },
                    )
                }
            }

            Text("Evaluation", style = MaterialTheme.typography.titleSmall)
            state.evaluations.forEach { option ->
                val selected = option.evaluation.id == state.selectedEvaluationId
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.selectEvaluation(option.evaluation.id) },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "${option.symbol} ${option.evaluation.evaluationDate} " +
                                "Quant ${option.evaluation.quantDecision}" +
                                if (selected) " [selected]" else "",
                        )
                    }
                }
            }
            if (state.evaluations.isEmpty()) {
                Text("No persisted evaluations for this run.")
            }

            if (state.mode == AiAdvisoryMode.CHATGPT_MANUAL) {
                Text(
                    "ChatGPT Manual: BJStock builds a prompt; you paste it into ChatGPT yourself. " +
                        "No ChatGPT login and no OpenAI API key in this app.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = viewModel::generatePrompt,
                        enabled = !state.busy && state.selectedEvaluationId != null,
                    ) { Text("Generate AI Prompt") }
                    Button(
                        onClick = viewModel::askAgain,
                        enabled = !state.busy && state.selectedEvaluationId != null,
                    ) { Text("Ask Again") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = viewModel::copyPrompt,
                        enabled = state.prompt != null,
                    ) { Text("Copy Prompt") }
                    Button(
                        onClick = {
                            viewModel.sharePromptIntent()?.let { intent ->
                                context.startActivity(Intent.createChooser(intent, "Share Prompt"))
                            }
                        },
                        enabled = state.prompt != null,
                    ) { Text("Share Prompt") }
                }

                state.prompt?.let { prompt ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Prompt (${prompt.promptVersion})", style = MaterialTheme.typography.titleSmall)
                            Text("Fingerprint ${prompt.requestFingerprint}")
                            Text(
                                prompt.promptText,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.heightIn(max = 280.dp),
                            )
                        }
                    }
                }

                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    value = state.pasteBuffer,
                    onValueChange = viewModel::onPasteChanged,
                    label = { Text("Paste AI Response (JSON)") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::validateOnly) { Text("Validate") }
                    Button(
                        onClick = viewModel::saveAdvice,
                        enabled = !state.busy && state.activeRequestId != null,
                    ) { Text("Save Advice") }
                }
                state.validationMessage?.let { Text(it) }
            }

            Text("Advice History", style = MaterialTheme.typography.titleSmall)
            if (state.history.isEmpty()) {
                Text("No AI requests yet")
            } else {
                state.history.forEach { row ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.selectHistoryRequest(row.requestId) },
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${row.provider} ${row.promptVersion}")
                            Text("Request ${row.requestId} @ ${row.requestedAt}")
                            if (row.hasResult) {
                                Text("${row.stance} Confidence ${row.confidence ?: "N/A"}")
                                row.summary?.let { Text(it) }
                            } else {
                                Text("No result yet")
                            }
                        }
                    }
                }
            }

            state.message?.let {
                Text(it, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
