package com.mirunubi.bjstock.core.ai

import com.mirunubi.bjstock.core.database.dao.FactorDao
import com.mirunubi.bjstock.core.database.dao.InstrumentDao
import com.mirunubi.bjstock.core.database.dao.MarketDailyBarDao
import com.mirunubi.bjstock.core.database.dao.PaperTradingPolicyDao
import com.mirunubi.bjstock.core.database.dao.StockEvaluationDao
import com.mirunubi.bjstock.core.database.dao.StrategyDao
import com.mirunubi.bjstock.core.database.dao.StrategyRunDao
import com.mirunubi.bjstock.core.database.mapping.NumericMapping
import com.mirunubi.bjstock.core.paper.PaperTradingPolicyService
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest

/**
 * Builds deterministic AI advisory prompts from immutable evaluation snapshots.
 * Never includes future market data (trade_date > evaluationDate).
 */
class AiAdvisoryPromptBuilder(
    private val evaluationDao: StockEvaluationDao,
    private val instrumentDao: InstrumentDao,
    private val factorDao: FactorDao,
    private val strategyRunDao: StrategyRunDao,
    private val strategyDao: StrategyDao,
    private val marketDailyBarDao: MarketDailyBarDao,
    private val policyDao: PaperTradingPolicyDao,
) {
    suspend fun build(
        evaluationId: Long,
        promptVersion: String = AiAdvisoryCodes.PROMPT_V1,
    ): AiPromptPackage {
        require(promptVersion == AiAdvisoryCodes.PROMPT_V1) {
            "unsupported prompt version: $promptVersion"
        }
        val evaluation = evaluationDao.findEvaluationById(evaluationId)
            ?: error("evaluation not found: $evaluationId")
        val instrument = instrumentDao.findById(evaluation.instrumentId)
            ?: error("instrument not found")
        val run = strategyRunDao.findById(evaluation.strategyRunId)
            ?: error("strategy run not found")
        val version = strategyDao.findVersionById(run.strategyVersionId)
        val strategy = version?.let { strategyDao.findStrategyById(it.strategyId) }
        val details = evaluationDao.findDetails(evaluationId).sortedBy { it.factorId }
        val bars = marketDailyBarDao.findBarsUpToDate(
            instrumentId = evaluation.instrumentId,
            asOfDate = evaluation.evaluationDate,
            limit = AiAdvisoryCodes.MAX_DAILY_BARS,
        ).sortedBy { it.tradeDate }
        val policy = policyDao.findByRun(evaluation.strategyRunId)

        val factorLines = details.map { detail ->
            val def = factorDao.findDefinitionById(detail.factorId)
            val code = def?.factorCode ?: "FACTOR_${detail.factorId}"
            buildString {
                append("- ")
                append(code)
                append(" raw=")
                append(detail.rawValue ?: "null")
                append(" score=")
                append(formatScore(detail.factorScore))
                append(" weight=")
                append(formatWeight(detail.weight))
                append(" contribution=")
                append(formatScore(detail.weightedScore))
            }
        }
        val barLines = bars.map { bar ->
            "${bar.tradeDate} O=${bar.openPrice} H=${bar.highPrice} L=${bar.lowPrice} " +
                "C=${bar.closePrice} V=${bar.volume}"
        }
        val policyLines = if (policy == null) {
            listOf("policy=none")
        } else {
            listOf(
                "policy_version=${policy.policyVersion}",
                "buy_allocation=${formatWeight(policy.buyAllocationRate)}",
                "commission=${formatWeight(policy.commissionRate)} (SIMULATION ASSUMPTION)",
                "sell_tax=${formatWeight(policy.sellTaxRate)} (SIMULATION ASSUMPTION)",
                "execution=${policy.executionPricePolicy}",
            )
        }

        val body = buildString {
            appendLine("BJSTOCK_AI_ADVISORY_PROMPT")
            appendLine("prompt_version=$promptVersion")
            appendLine("role=independent investment advisory analyst")
            appendLine("instruction=Provide an independent advisory stance. Do not blindly agree or disagree with quant.")
            appendLine("output_contract=JSON schema_version=${AiAdvisoryCodes.RESPONSE_SCHEMA_VERSION} with request_fingerprint,stance,confidence,summary,supporting_reasons,risk_factors")
            appendLine("stance_enum=BUY|HOLD|SELL|UNCERTAIN")
            appendLine("confidence_range=0..100")
            appendLine("evaluation_id=$evaluationId")
            appendLine("evaluation_date=${evaluation.evaluationDate}")
            appendLine("instrument_symbol=${instrument.symbol}")
            appendLine("instrument_name=${instrument.name}")
            appendLine("instrument_market=${instrument.market}")
            appendLine("instrument_board=${instrument.board}")
            appendLine("strategy_code=${strategy?.strategyCode ?: "?"}")
            appendLine("strategy_name=${strategy?.strategyName ?: "?"}")
            appendLine("strategy_version=${version?.versionNo ?: "?"}")
            appendLine("quant_score=${formatScore(evaluation.quantScore)}")
            appendLine("quant_decision=${evaluation.quantDecision}")
            appendLine("factors:")
            factorLines.forEach { appendLine(it) }
            appendLine("daily_bars_up_to_evaluation_date_max_${AiAdvisoryCodes.MAX_DAILY_BARS}:")
            barLines.forEach { appendLine(it) }
            appendLine("paper_trading_policy:")
            policyLines.forEach { appendLine(it) }
            appendLine("constraints:")
            appendLine("- Do not use any information after evaluation_date")
            appendLine("- Do not invent future prices or outcomes")
            appendLine("- Advisory only; this is not an order")
        }.trimEnd('\n') + "\n"

        val fingerprint = sha256Hex(body)
        val promptText = body + "request_fingerprint=$fingerprint\n"
        val canonicalPayload = promptText
        return AiPromptPackage(
            promptVersion = promptVersion,
            requestFingerprint = fingerprint,
            promptText = promptText,
            evaluationId = evaluationId,
            canonicalPayload = canonicalPayload,
        )
    }

    companion object {
        fun sha256Hex(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun formatScore(stored: Long): String =
            BigDecimal(stored)
                .divide(BigDecimal(NumericMapping.SCORE_FACTOR), NumericMapping.SCORE_SCALE, RoundingMode.HALF_UP)
                .toPlainString()

        private fun formatWeight(stored: Long): String =
            BigDecimal(stored)
                .divide(BigDecimal(NumericMapping.WEIGHT_FACTOR), NumericMapping.WEIGHT_SCALE, RoundingMode.HALF_UP)
                .toPlainString()
    }
}
