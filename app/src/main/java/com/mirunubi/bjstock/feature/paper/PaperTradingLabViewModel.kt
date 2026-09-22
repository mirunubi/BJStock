package com.mirunubi.bjstock.feature.paper

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mirunubi.bjstock.core.database.dao.ExecutionDao
import com.mirunubi.bjstock.core.database.dao.InstrumentDao
import com.mirunubi.bjstock.core.database.dao.MarketDailyBarDao
import com.mirunubi.bjstock.core.database.dao.OrderDao
import com.mirunubi.bjstock.core.database.dao.PositionDao
import com.mirunubi.bjstock.core.database.dao.StockEvaluationDao
import com.mirunubi.bjstock.core.database.dao.StrategyDao
import com.mirunubi.bjstock.core.database.entity.OrderEntity
import com.mirunubi.bjstock.core.database.entity.StrategyRunEntity
import com.mirunubi.bjstock.core.paper.CashLedgerService
import com.mirunubi.bjstock.core.paper.MarketExecutionTime
import com.mirunubi.bjstock.core.paper.PaperTradingEngine
import com.mirunubi.bjstock.core.strategy.StrategyRunService
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeParseException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PaperPositionRow(
    val name: String,
    val symbol: String,
    val quantity: Long,
    val averagePrice: Long,
    val latestClose: Long?,
    val marketValue: Long?,
)

data class PaperOrderRow(
    val id: Long,
    val side: String,
    val status: String,
    val symbol: String,
    val quantity: Long,
    val signalDate: String?,
    val executionDate: String?,
    val executionPrice: Long?,
)

data class PaperLabUiState(
    val runs: List<StrategyRunEntity> = emptyList(),
    val selectedRunId: Long? = null,
    val strategyLabel: String = "",
    val initialCash: Long = 0,
    val currentCash: Long = 0,
    val marketValue: Long = 0,
    val totalAsset: Long = 0,
    val cumulativeReturnLabel: String = "",
    val positions: List<PaperPositionRow> = emptyList(),
    val orders: List<PaperOrderRow> = emptyList(),
    val snapshotDate: String = "",
    val message: String? = null,
)

@HiltViewModel
class PaperTradingLabViewModel @Inject constructor(
    private val strategyRunService: StrategyRunService,
    private val strategyDao: StrategyDao,
    private val engine: PaperTradingEngine,
    private val cashLedger: CashLedgerService,
    private val positionDao: PositionDao,
    private val orderDao: OrderDao,
    private val executionDao: ExecutionDao,
    private val evaluationDao: StockEvaluationDao,
    private val instrumentDao: InstrumentDao,
    private val marketDailyBarDao: MarketDailyBarDao,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PaperLabUiState())
    val uiState: StateFlow<PaperLabUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { reloadRuns() }
    }

    fun onSnapshotDateChanged(value: String) {
        _uiState.update { it.copy(snapshotDate = value) }
    }

    fun selectRun(runId: Long) {
        viewModelScope.launch { loadRun(runId) }
    }

    fun processEvaluations() {
        val runId = _uiState.value.selectedRunId ?: return
        viewModelScope.launch {
            val results = engine.processEvaluations(runId)
            loadRun(runId)
            _uiState.update {
                it.copy(message = "Process Evaluations: ${results.joinToString { r -> r.action.name }}")
            }
        }
    }

    fun processPendingOrders() {
        val runId = _uiState.value.selectedRunId ?: return
        viewModelScope.launch {
            val results = engine.processPendingOrders(runId)
            loadRun(runId)
            _uiState.update {
                it.copy(message = "Process Pending: ${results.joinToString { r -> r.action.name }}")
            }
        }
    }

    fun createDailySnapshot() {
        val runId = _uiState.value.selectedRunId ?: return
        val date = parseDate(_uiState.value.snapshotDate) ?: run {
            _uiState.update { it.copy(message = "Snapshot Date YYYY-MM-DD") }
            return
        }
        viewModelScope.launch {
            val result = engine.createDailySnapshot(runId, date)
            loadRun(runId)
            _uiState.update { it.copy(message = "${result.action}: ${result.message ?: ""}") }
        }
    }

    private suspend fun reloadRuns() {
        val runs = strategyRunService.findAll()
        _uiState.update { it.copy(runs = runs) }
        runs.lastOrNull()?.let { loadRun(it.id) }
    }

    private suspend fun loadRun(runId: Long) {
        val run = strategyRunService.findById(runId) ?: return
        val version = strategyDao.findVersionById(run.strategyVersionId)
        val strategy = version?.let { strategyDao.findStrategyById(it.strategyId) }
        val cash = cashLedger.currentCash(runId)
        val positions = positionDao.findByRun(runId)
        val positionRows = positions.map { position ->
            val instrument = instrumentDao.findById(position.instrumentId)
            val latest = marketDailyBarDao.findLatest(position.instrumentId)
            val close = if (position.quantity > 0L) latest?.closePrice else null
            PaperPositionRow(
                name = instrument?.name ?: "instrument-${position.instrumentId}",
                symbol = instrument?.symbol ?: "",
                quantity = position.quantity,
                averagePrice = position.averagePrice,
                latestClose = close,
                marketValue = close?.let { it * position.quantity },
            )
        }
        val marketValue = positionRows.mapNotNull { it.marketValue }.sum()
        val total = cash + marketValue
        val returnLabel = if (run.initialCash > 0L) {
            val pct = BigDecimal(total - run.initialCash)
                .multiply(BigDecimal(100))
                .divide(BigDecimal(run.initialCash), 2, RoundingMode.HALF_UP)
            "${if (pct >= BigDecimal.ZERO) "+" else ""}${pct.toPlainString()}%"
        } else {
            "n/a"
        }
        val orders = orderDao.findByRun(runId).map { toOrderRow(it) }
        _uiState.update {
            it.copy(
                runs = strategyRunService.findAll(),
                selectedRunId = runId,
                strategyLabel = buildString {
                    append(strategy?.strategyCode ?: "STRATEGY")
                    if (version != null) append(" V${version.versionNo}")
                },
                initialCash = run.initialCash,
                currentCash = cash,
                marketValue = marketValue,
                totalAsset = total,
                cumulativeReturnLabel = returnLabel,
                positions = positionRows,
                orders = orders,
            )
        }
    }

    private suspend fun toOrderRow(order: OrderEntity): PaperOrderRow {
        val instrument = instrumentDao.findById(order.instrumentId)
        val signalDate = order.evaluationId
            ?.let { evaluationDao.findEvaluationById(it)?.evaluationDate?.toString() }
        val execution = executionDao.findByOrderId(order.id)
        return PaperOrderRow(
            id = order.id,
            side = order.side.name,
            status = order.status.name,
            symbol = instrument?.symbol ?: "",
            quantity = order.quantity,
            signalDate = signalDate,
            executionDate = order.executedAt?.let { MarketExecutionTime.toTradeDate(it).toString() },
            executionPrice = execution?.executionPrice,
        )
    }

    companion object {
        fun parseDate(raw: String): LocalDate? = try {
            LocalDate.parse(raw.trim())
        } catch (_: DateTimeParseException) {
            null
        }

        fun formatWon(value: Long): String = "%,d".format(value)
    }
}
