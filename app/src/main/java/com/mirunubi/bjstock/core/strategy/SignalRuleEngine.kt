package com.mirunubi.bjstock.core.strategy

import com.mirunubi.bjstock.core.database.dao.MarketDailyBarDao
import com.mirunubi.bjstock.core.database.entity.StrategySignalRuleEntity
import com.mirunubi.bjstock.core.model.DecisionSource
import com.mirunubi.bjstock.core.model.SignalAction
import com.mirunubi.bjstock.core.model.SignalMetricCode
import com.mirunubi.bjstock.core.model.SignalOperator
import com.mirunubi.bjstock.core.model.TradeDecision
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.Locale

data class SignalRuleTrigger(
    val rule: StrategySignalRuleEntity,
    val observedValue: BigDecimal,
    val thresholdValue: BigDecimal,
    val reasonText: String,
)

class SignalRuleEngine(
    private val marketDailyBarDao: MarketDailyBarDao,
) {
    suspend fun evaluateDailyChangePct(
        instrumentId: Long,
        asOfDate: LocalDate,
        rules: List<StrategySignalRuleEntity>,
    ): SignalRuleTrigger? {
        val enabled = rules
            .filter { it.enabled && it.metricCode == SignalMetricCode.DAILY_CHANGE_PCT }
            .sortedWith(compareBy({ it.priority }, { it.id }))
        if (enabled.isEmpty()) return null

        val today = marketDailyBarDao.findByInstrumentAndDate(instrumentId, asOfDate) ?: return null
        val previous = marketDailyBarDao.findPreviousTradingBar(instrumentId, asOfDate) ?: return null
        if (previous.closePrice <= 0L) return null

        val observed = BigDecimal.valueOf(today.closePrice - previous.closePrice)
            .multiply(BigDecimal(100))
            .divide(BigDecimal.valueOf(previous.closePrice), 8, RoundingMode.HALF_UP)

        for (rule in enabled) {
            val threshold = parseThreshold(rule.thresholdValue) ?: continue
            val matched = when (rule.operator) {
                SignalOperator.GTE -> observed >= threshold
                SignalOperator.LTE -> observed <= threshold
            }
            if (matched) {
                val op = when (rule.operator) {
                    SignalOperator.GTE -> ">="
                    SignalOperator.LTE -> "<="
                }
                return SignalRuleTrigger(
                    rule = rule,
                    observedValue = observed,
                    thresholdValue = threshold,
                    reasonText = String.format(
                        Locale.US,
                        "DAILY_CHANGE_PCT %s%% %s %s%%, %s rule triggered",
                        formatPct(observed),
                        op,
                        formatPct(threshold),
                        rule.action.name,
                    ),
                )
            }
        }
        return null
    }

    companion object {
        fun parseThreshold(raw: String): BigDecimal? =
            raw.trim().removeSuffix("%").toBigDecimalOrNull()

        fun formatPct(value: BigDecimal): String =
            value.setScale(2, RoundingMode.HALF_UP).toPlainString()

        fun toDecision(action: SignalAction): TradeDecision = when (action) {
            SignalAction.BUY -> TradeDecision.BUY
            SignalAction.SELL -> TradeDecision.SELL
        }

        fun conflictError(rules: List<StrategySignalRuleEntity>): String? {
            val enabled = rules.filter { it.enabled }
            val byPriority = enabled.groupBy { it.priority }
            for ((priority, group) in byPriority) {
                val actions = group.map { it.action }.distinct()
                if (actions.size > 1) {
                    return "conflicting actions at priority $priority: ${actions.joinToString()}"
                }
            }
            return null
        }
    }
}

fun SignalRuleTrigger.toDecisionResult(): StrategyEvaluationResult =
    StrategyEvaluationResult(
        status = StrategyEvaluationStatus.SUCCESS,
        // Signal rules do not produce a factor quant score; persist 0 as placeholder.
        quantScoreStored = 0L,
        quantDecision = SignalRuleEngine.toDecision(rule.action),
        decisionSource = DecisionSource.SIGNAL_RULE,
        triggeredRuleId = rule.id,
        reasonText = reasonText,
        metricCode = rule.metricCode.name,
        observedValue = SignalRuleEngine.formatPct(observedValue),
        thresholdValue = SignalRuleEngine.formatPct(thresholdValue),
        message = reasonText,
    )
