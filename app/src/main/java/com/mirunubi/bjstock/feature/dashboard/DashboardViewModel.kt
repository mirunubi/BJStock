package com.mirunubi.bjstock.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.dao.InstrumentDao
import com.mirunubi.bjstock.core.database.dao.StrategyDao
import com.mirunubi.bjstock.core.database.dao.StrategyRunDao
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class DashboardUiState(
    val instrumentCount: Int = 0,
    val strategyCount: Int = 0,
    val strategyRunCount: Int = 0,
    val databaseOpen: Boolean = false,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    instrumentDao: InstrumentDao,
    strategyDao: StrategyDao,
    strategyRunDao: StrategyRunDao,
    database: BJStockDatabase,
) : ViewModel() {
    val uiState: StateFlow<DashboardUiState> = combine(
        instrumentDao.observeCount(),
        strategyDao.observeCount(),
        strategyRunDao.observeCount(),
    ) { instruments, strategies, runs ->
        DashboardUiState(
            instrumentCount = instruments,
            strategyCount = strategies,
            strategyRunCount = runs,
            databaseOpen = database.isOpen,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState(databaseOpen = database.isOpen),
    )
}
