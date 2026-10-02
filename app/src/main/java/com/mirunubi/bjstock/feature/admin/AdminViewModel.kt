package com.mirunubi.bjstock.feature.admin

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mirunubi.bjstock.core.error.SafeLogText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Read-only: every action here only loads or changes what is displayed. */
@HiltViewModel
class AdminViewModel @Inject constructor(
    private val source: AdminDataSource,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AdminUiState())
    val uiState: StateFlow<AdminUiState> = _uiState.asStateFlow()
    private var loading: Job? = null
    private var detailLoading: Job? = null

    /** Each section loads and fails independently; loaded sections stay visible while refreshing. */
    fun refresh() {
        if (loading?.isActive == true) return
        _uiState.update { state ->
            state.copy(
                status = state.status.retrying(),
                operations = state.operations.retrying(),
                audit = state.audit.retrying(),
                errors = state.errors.retrying(),
                environment = state.environment.retrying(),
            )
        }
        loading = viewModelScope.launch {
            coroutineScope {
                launch { section("status", { SectionState.Loaded(AdminPresenter.status(source.status())) }) { copy(status = it) } }
                launch { section("operations", { AdminPresenter.operations(source.recentOperations()) }) { copy(operations = it) } }
                launch { section("audit", { AdminPresenter.audit(source.audit()) }) { copy(audit = it) } }
                launch { section("errors", { AdminPresenter.errors(source.errors()) }) { copy(errors = it) } }
                launch {
                    section("environment", { SectionState.Loaded(AdminPresenter.environment(source.environment())) }) {
                        copy(environment = it)
                    }
                }
            }
        }
    }

    fun openOperation(operationId: Long) {
        detailLoading?.cancel()
        _uiState.update { it.copy(detail = AdminDetail.Loading(operationId)) }
        detailLoading = viewModelScope.launch {
            val detail = try {
                source.operationDetail(operationId)
                    ?.let { AdminDetail.Loaded(operationId, AdminPresenter.detail(it)) }
                    ?: AdminDetail.Failed(operationId, AdminPresenter.DETAIL_NOT_FOUND)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logFailure("detail", failure)
                AdminDetail.Failed(operationId, AdminPresenter.LOAD_FAILED)
            }
            _uiState.update { state -> if (state.detail?.operationId == operationId) state.copy(detail = detail) else state }
        }
    }

    fun closeDetail() {
        detailLoading?.cancel()
        _uiState.update { it.copy(detail = null) }
    }

    fun selectAuditFilter(filter: AuditFilter) {
        _uiState.update { it.copy(auditFilter = filter) }
    }

    fun toggleAudit(key: String) {
        _uiState.update { it.copy(expandedAudit = it.expandedAudit.toggle(key)) }
    }

    fun toggleError(key: String) {
        _uiState.update { it.copy(expandedErrors = it.expandedErrors.toggle(key)) }
    }

    private suspend fun <T> section(
        name: String,
        load: suspend () -> SectionState<T>,
        apply: AdminUiState.(SectionState<T>) -> AdminUiState,
    ) {
        val result = try {
            load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logFailure(name, failure)
            SectionState.Failed(AdminPresenter.LOAD_FAILED)
        }
        _uiState.update { it.apply(result) }
    }

    private fun <T> SectionState<T>.retrying(): SectionState<T> = if (this is SectionState.Failed) SectionState.Loading else this

    private fun Set<String>.toggle(key: String): Set<String> = if (key in this) this - key else this + key

    private fun logFailure(section: String, failure: Exception) {
        val type = SafeLogText.exceptionType(failure.javaClass.simpleName) ?: "Exception"
        Log.w(TAG, "Admin $section load failed ($type)")
    }

    private companion object {
        const val TAG = "BJStockAdmin"
    }
}
