package com.mirunubi.bjstock.feature.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mirunubi.bjstock.core.database.dao.InstrumentDao
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.database.entity.ThemeEntity
import com.mirunubi.bjstock.core.theme.ThemeService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ThemeInstrumentRow(
    val instrumentId: Long,
    val symbol: String,
    val name: String,
    val note: String?,
)

data class ThemesUiState(
    val themes: List<ThemeEntity> = emptyList(),
    val selectedThemeId: Long? = null,
    val memberships: List<ThemeInstrumentRow> = emptyList(),
    val membershipCount: Int = 0,
    val newName: String = "",
    val newDescription: String = "",
    val renameName: String = "",
    val searchQuery: String = "",
    val searchResults: List<InstrumentEntity> = emptyList(),
    val message: String? = null,
    val busy: Boolean = false,
) {
    val selectedTheme: ThemeEntity?
        get() = themes.firstOrNull { it.id == selectedThemeId }
}

@HiltViewModel
class ThemesViewModel @Inject constructor(
    private val themeService: ThemeService,
    private val instrumentDao: InstrumentDao,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ThemesUiState())
    val uiState: StateFlow<ThemesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { reload() }
    }

    fun onNewNameChanged(value: String) = _uiState.update { it.copy(newName = value) }
    fun onNewDescriptionChanged(value: String) = _uiState.update { it.copy(newDescription = value) }
    fun onRenameChanged(value: String) = _uiState.update { it.copy(renameName = value) }
    fun onSearchChanged(value: String) {
        _uiState.update { it.copy(searchQuery = value) }
        viewModelScope.launch {
            val results = if (value.isBlank()) {
                emptyList()
            } else {
                instrumentDao.searchActive("%${value.trim()}%", limit = 30)
            }
            _uiState.update { it.copy(searchResults = results) }
        }
    }

    fun selectTheme(themeId: Long) {
        viewModelScope.launch { loadTheme(themeId) }
    }

    fun createTheme() {
        val name = _uiState.value.newName
        val description = _uiState.value.newDescription.ifBlank { null }
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, message = null) }
            runCatching { themeService.createTheme(name, description) }
                .onSuccess { id ->
                    _uiState.update {
                        it.copy(newName = "", newDescription = "", busy = false, message = "Theme created")
                    }
                    reload(selectId = id)
                }
                .onFailure { e ->
                    _uiState.update { it.copy(busy = false, message = e.message) }
                }
        }
    }

    fun renameTheme() {
        val themeId = _uiState.value.selectedThemeId ?: return
        val name = _uiState.value.renameName
        viewModelScope.launch {
            runCatching { themeService.renameTheme(themeId, name) }
                .onSuccess {
                    _uiState.update { it.copy(message = "Renamed") }
                    reload(selectId = themeId)
                }
                .onFailure { e ->
                    _uiState.update { it.copy(message = e.message) }
                }
        }
    }

    fun setActive(active: Boolean) {
        val themeId = _uiState.value.selectedThemeId ?: return
        viewModelScope.launch {
            runCatching { themeService.setActive(themeId, active) }
                .onSuccess { reload(selectId = themeId) }
                .onFailure { e -> _uiState.update { it.copy(message = e.message) } }
        }
    }

    fun addInstrument(instrumentId: Long) {
        val themeId = _uiState.value.selectedThemeId ?: return
        viewModelScope.launch {
            runCatching { themeService.addInstrument(themeId, instrumentId) }
                .onSuccess { loadTheme(themeId) }
                .onFailure { e -> _uiState.update { it.copy(message = e.message) } }
        }
    }

    fun removeInstrument(instrumentId: Long) {
        val themeId = _uiState.value.selectedThemeId ?: return
        viewModelScope.launch {
            runCatching { themeService.removeInstrument(themeId, instrumentId) }
                .onSuccess { loadTheme(themeId) }
                .onFailure { e -> _uiState.update { it.copy(message = e.message) } }
        }
    }

    private suspend fun reload(selectId: Long? = _uiState.value.selectedThemeId) {
        val themes = themeService.listThemes()
        val selected = selectId ?: themes.firstOrNull()?.id
        _uiState.update { it.copy(themes = themes, selectedThemeId = selected) }
        if (selected != null) loadTheme(selected) else {
            _uiState.update { it.copy(memberships = emptyList(), membershipCount = 0, renameName = "") }
        }
    }

    private suspend fun loadTheme(themeId: Long) {
        val theme = themeService.findById(themeId)
        val memberships = themeService.listInstrumentIds(themeId).mapNotNull { instrumentId ->
            val instrument = instrumentDao.findById(instrumentId) ?: return@mapNotNull null
            ThemeInstrumentRow(
                instrumentId = instrument.id,
                symbol = instrument.symbol,
                name = instrument.name,
                note = null,
            )
        }
        _uiState.update {
            it.copy(
                selectedThemeId = themeId,
                memberships = memberships,
                membershipCount = memberships.size,
                renameName = theme?.name.orEmpty(),
            )
        }
    }
}
