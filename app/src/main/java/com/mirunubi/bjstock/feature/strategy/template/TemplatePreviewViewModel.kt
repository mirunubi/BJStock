package com.mirunubi.bjstock.feature.strategy.template

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PickerState<out T> {
    data object Idle : PickerState<Nothing>
    data object Loading : PickerState<Nothing>
    data class Empty(val message: String) : PickerState<Nothing>
    data class Failed(val message: String) : PickerState<Nothing>
    data class Loaded<T>(val items: List<T>) : PickerState<T>
}

sealed interface TemplateDialog {
    data class Rename(val templateId: Long, val text: String) : TemplateDialog
    data class Delete(val templateId: Long, val name: String) : TemplateDialog
}

data class TemplatePreviewUiState(
    val templates: List<PreviewTemplate> = TemplatePreviewPresenter.initialTemplates(),
    val selectedId: Long? = null,
    val form: TemplateForm? = null,
    val stockQuery: String = "",
    val stockSearch: PickerState<InstrumentOption> = PickerState.Idle,
    val themes: PickerState<ThemeOption> = PickerState.Idle,
    val dialog: TemplateDialog? = null,
    val message: String? = null,
) {
    val selected: PreviewTemplate? get() = templates.firstOrNull { it.id == selectedId }
    val cards: List<TemplateCard> get() = templates.map(TemplatePreviewPresenter::card)
    val validation: FormValidation? get() = form?.let(TemplatePreviewPresenter::validate)
    val dirty: Boolean get() = selected?.let { t -> form?.let { TemplatePreviewPresenter.isDirty(t, it) } } ?: false
}

/**
 * State holder for 전략 템플릿 (미리보기). Everything lives in this ViewModel's memory; the only dependency is a
 * read-only lookup used by the stock and theme pickers.
 */
@HiltViewModel
class TemplatePreviewViewModel @Inject constructor(
    private val readSource: TemplatePreviewReadSource,
) : ViewModel() {
    private val _uiState = MutableStateFlow(TemplatePreviewUiState())
    val uiState: StateFlow<TemplatePreviewUiState> = _uiState.asStateFlow()

    private var nextId = (_uiState.value.templates.maxOfOrNull { it.id } ?: 0L) + 1
    private var createdCount = 0
    private var searchJob: Job? = null
    private var themesJob: Job? = null

    // region List operations

    fun open(templateId: Long) {
        val template = _uiState.value.templates.firstOrNull { it.id == templateId } ?: return
        _uiState.update {
            it.copy(
                selectedId = template.id,
                form = TemplatePreviewPresenter.formOf(template),
                stockQuery = "",
                stockSearch = PickerState.Idle,
                message = null,
            )
        }
        if (template.targetType == TemplateTargetType.THEME) loadThemes()
    }

    fun close() {
        searchJob?.cancel()
        _uiState.update { it.copy(selectedId = null, form = null, stockQuery = "", stockSearch = PickerState.Idle, message = null) }
    }

    fun create() {
        createdCount += 1
        val template = TemplatePreviewPresenter.newTemplate(nextId++, "새 템플릿 $createdCount")
        _uiState.update { it.copy(templates = it.templates + template) }
        open(template.id)
    }

    fun duplicate(templateId: Long) {
        val source = _uiState.value.templates.firstOrNull { it.id == templateId } ?: return
        val copy = source.copy(
            id = nextId++,
            name = TemplatePreviewPresenter.duplicateName(source.name),
            example = false,
            unsaved = true,
        )
        _uiState.update { state ->
            val index = state.templates.indexOfFirst { it.id == templateId }
            val list = state.templates.toMutableList().apply { add(index + 1, copy) }
            state.copy(templates = list, message = "'${copy.name}'을(를) 미리보기에 추가했습니다. (미저장)")
        }
    }

    fun requestRename(templateId: Long) {
        val template = _uiState.value.templates.firstOrNull { it.id == templateId } ?: return
        _uiState.update { it.copy(dialog = TemplateDialog.Rename(template.id, template.name)) }
    }

    fun updateRenameText(text: String) {
        _uiState.update { state ->
            val dialog = state.dialog as? TemplateDialog.Rename ?: return@update state
            state.copy(dialog = dialog.copy(text = text))
        }
    }

    fun confirmRename() {
        val dialog = _uiState.value.dialog as? TemplateDialog.Rename ?: return
        val name = dialog.text.trim()
        if (name.isEmpty()) return
        _uiState.update { state ->
            state.copy(
                templates = state.templates.map { if (it.id == dialog.templateId) it.copy(name = name, unsaved = true) else it },
                form = if (state.selectedId == dialog.templateId) state.form?.copy(name = name) else state.form,
                dialog = null,
            )
        }
    }

    fun requestDelete(templateId: Long) {
        val template = _uiState.value.templates.firstOrNull { it.id == templateId } ?: return
        _uiState.update { it.copy(dialog = TemplateDialog.Delete(template.id, template.name)) }
    }

    fun confirmDelete() {
        val dialog = _uiState.value.dialog as? TemplateDialog.Delete ?: return
        _uiState.update { state ->
            val closing = state.selectedId == dialog.templateId
            state.copy(
                templates = state.templates.filterNot { it.id == dialog.templateId },
                selectedId = if (closing) null else state.selectedId,
                form = if (closing) null else state.form,
                dialog = null,
                message = "'${dialog.name}'을(를) 미리보기에서 지웠습니다.",
            )
        }
    }

    fun dismissDialog() {
        _uiState.update { it.copy(dialog = null) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    // endregion

    // region Editor

    fun updateForm(transform: (TemplateForm) -> TemplateForm) {
        _uiState.update { state -> state.form?.let { state.copy(form = transform(it)) } ?: state }
    }

    fun setTargetType(type: TemplateTargetType) {
        updateForm { TemplatePreviewPresenter.withTargetType(it, type) }
        if (type == TemplateTargetType.THEME && _uiState.value.themes !is PickerState.Loaded) loadThemes()
    }

    fun updateStockQuery(query: String) {
        _uiState.update { it.copy(stockQuery = query) }
    }

    fun searchStocks() {
        val query = _uiState.value.stockQuery.trim()
        if (query.isEmpty()) {
            _uiState.update { it.copy(stockSearch = PickerState.Empty("종목명이나 종목코드를 입력하세요.")) }
            return
        }
        searchJob?.cancel()
        _uiState.update { it.copy(stockSearch = PickerState.Loading) }
        searchJob = viewModelScope.launch {
            val next = try {
                val items = readSource.searchStocks(query)
                if (items.isEmpty()) PickerState.Empty(TemplatePreviewPresenter.SEARCH_EMPTY) else PickerState.Loaded(items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PickerState.Failed(TemplatePreviewPresenter.LOAD_FAILED)
            }
            _uiState.update { it.copy(stockSearch = next) }
        }
    }

    fun selectStock(option: InstrumentOption) {
        updateForm { TemplatePreviewPresenter.withStock(it, option) }
    }

    fun removeStock(symbol: String) {
        updateForm { TemplatePreviewPresenter.withoutStock(it, symbol) }
    }

    fun loadThemes() {
        themesJob?.cancel()
        _uiState.update { it.copy(themes = PickerState.Loading) }
        themesJob = viewModelScope.launch {
            val next = try {
                val items = readSource.themes()
                if (items.isEmpty()) PickerState.Empty(TemplatePreviewPresenter.THEMES_EMPTY) else PickerState.Loaded(items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PickerState.Failed(TemplatePreviewPresenter.LOAD_FAILED)
            }
            _uiState.update { it.copy(themes = next) }
        }
    }

    fun selectTheme(option: ThemeOption) {
        updateForm { TemplatePreviewPresenter.withTheme(it, option) }
    }

    /** Copies the form into the in-memory template only. */
    fun applyPreview() {
        val state = _uiState.value
        val template = state.selected ?: return
        val form = state.form ?: return
        val updated = TemplatePreviewPresenter.apply(template, form) ?: return
        _uiState.update { s ->
            s.copy(
                templates = s.templates.map { if (it.id == updated.id) updated else it },
                form = TemplatePreviewPresenter.formOf(updated),
                message = "미리보기에 반영했습니다. 저장되지 않습니다.",
            )
        }
    }

    fun revert() {
        val template = _uiState.value.selected ?: return
        _uiState.update { it.copy(form = TemplatePreviewPresenter.formOf(template), message = null) }
    }

    // endregion
}
