package com.mirunubi.bjstock.feature.kis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mirunubi.bjstock.core.kis.KisAuthState
import com.mirunubi.bjstock.core.kis.KisEnvironment

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KisSettingsScreen(
    onBack: () -> Unit,
    viewModel: KisSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var environmentExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KIS API Settings") },
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
            Text("Paper Trading Only", color = MaterialTheme.colorScheme.primary)
            Text("Market data authentication only. No account number and no orders.")

            ExposedDropdownMenuBox(
                expanded = environmentExpanded,
                onExpandedChange = { environmentExpanded = it },
            ) {
                OutlinedTextField(
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                    readOnly = true,
                    value = state.environment.name,
                    onValueChange = {},
                    label = { Text("Environment") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = environmentExpanded) },
                )
                ExposedDropdownMenu(
                    expanded = environmentExpanded,
                    onDismissRequest = { environmentExpanded = false },
                ) {
                    KisEnvironment.entries.forEach { environment ->
                        DropdownMenuItem(
                            text = { Text(environment.name) },
                            onClick = {
                                viewModel.onEnvironmentSelected(environment)
                                environmentExpanded = false
                            },
                        )
                    }
                }
            }

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = state.appKeyInput,
                onValueChange = viewModel::onAppKeyChanged,
                label = { Text("App Key") },
                placeholder = {
                    Text(if (state.credentialsSaved) state.appKeyMask ?: "Saved" else "")
                },
                singleLine = true,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = state.appSecretInput,
                onValueChange = viewModel::onAppSecretChanged,
                label = { Text("App Secret") },
                placeholder = {
                    Text(if (state.credentialsSaved) "••••••••••••••" else "")
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )

            Text("Credential Status: ${if (state.credentialsSaved) "Saved" else "Not saved"}")
            Text("Authentication Status: ${state.authState.name}")
            state.message?.let { Text(it) }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = viewModel::save,
            ) { Text("저장") }
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = viewModel::testConnection,
                enabled = state.authState != KisAuthState.AUTHENTICATING,
            ) { Text("연결 테스트") }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = viewModel::deleteCredentials,
            ) { Text("인증정보 삭제") }
        }
    }
}
