package com.mirunubi.bjstock.feature.kis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mirunubi.bjstock.core.kis.KisAuthRepository
import com.mirunubi.bjstock.core.kis.KisAuthState
import com.mirunubi.bjstock.core.kis.KisEnvironment
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class KisSettingsUiState(
    val environment: KisEnvironment = KisEnvironment.PRODUCTION,
    val appKeyInput: String = "",
    val appSecretInput: String = "",
    val credentialsSaved: Boolean = false,
    val appKeyMask: String? = null,
    val authState: KisAuthState = KisAuthState.NOT_CONFIGURED,
    val message: String? = null,
)

@HiltViewModel
class KisSettingsViewModel @Inject constructor(
    private val repository: KisAuthRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(KisSettingsUiState())
    val uiState: StateFlow<KisSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { refresh() }
        viewModelScope.launch {
            repository.observeAuthState().collect { state ->
                _uiState.update { it.copy(authState = state) }
            }
        }
    }

    fun onEnvironmentSelected(environment: KisEnvironment) {
        viewModelScope.launch {
            repository.setSelectedEnvironment(environment)
            _uiState.update {
                it.copy(
                    environment = environment,
                    appKeyInput = "",
                    appSecretInput = "",
                    message = null,
                )
            }
            loadDisplay(environment)
        }
    }

    fun onAppKeyChanged(value: String) {
        _uiState.update { it.copy(appKeyInput = value) }
    }

    fun onAppSecretChanged(value: String) {
        _uiState.update { it.copy(appSecretInput = value) }
    }

    fun save() {
        val state = _uiState.value
        if (state.appKeyInput.isBlank() || state.appSecretInput.isBlank()) {
            _uiState.update { it.copy(message = "App Key and App Secret are required") }
            return
        }
        viewModelScope.launch {
            repository.saveCredentials(state.environment, state.appKeyInput, state.appSecretInput)
            _uiState.update {
                it.copy(
                    appKeyInput = "",
                    appSecretInput = "",
                    message = "Saved",
                )
            }
            loadDisplay(state.environment)
        }
    }

    fun testConnection() {
        if (_uiState.value.authState == KisAuthState.AUTHENTICATING) {
            return
        }
        viewModelScope.launch {
            val environment = _uiState.value.environment
            val result = repository.testConnection(environment)
            _uiState.update {
                it.copy(
                    authState = result,
                    message = when (result) {
                        KisAuthState.AUTHENTICATED -> "Connection test succeeded"
                        KisAuthState.NOT_CONFIGURED -> "Connection test failed: credentials missing"
                        else -> "Connection test failed"
                    },
                )
            }
        }
    }

    fun deleteCredentials() {
        viewModelScope.launch {
            val environment = _uiState.value.environment
            repository.deleteCredentials(environment)
            _uiState.update {
                it.copy(
                    appKeyInput = "",
                    appSecretInput = "",
                    credentialsSaved = false,
                    appKeyMask = null,
                    authState = KisAuthState.NOT_CONFIGURED,
                    message = "Credentials deleted",
                )
            }
        }
    }

    private suspend fun refresh() {
        val environment = repository.selectedEnvironment()
        _uiState.update { it.copy(environment = environment) }
        loadDisplay(environment)
    }

    private suspend fun loadDisplay(environment: KisEnvironment) {
        val display = repository.credentialDisplay(environment)
        val authState = repository.refreshState(environment)
        _uiState.update {
            it.copy(
                credentialsSaved = display.saved,
                appKeyMask = display.appKeyMask,
                authState = authState,
            )
        }
    }
}
