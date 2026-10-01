package com.mirunubi.bjstock.feature.strategy

import com.mirunubi.bjstock.core.database.entity.StrategyEntity
import com.mirunubi.bjstock.core.database.entity.StrategySignalRuleEntity
import com.mirunubi.bjstock.core.database.entity.StrategyVersionEntity
import com.mirunubi.bjstock.core.factor.FactorCalculationVersions
import com.mirunubi.bjstock.core.factor.FactorCodes
import com.mirunubi.bjstock.core.model.DecisionSource
import com.mirunubi.bjstock.core.model.SignalAction
import com.mirunubi.bjstock.core.model.SignalMetricCode
import com.mirunubi.bjstock.core.model.SignalOperator
import com.mirunubi.bjstock.core.model.StrategyVersionStatus
import com.mirunubi.bjstock.core.model.TradeDecision
import com.mirunubi.bjstock.core.strategy.SignalRuleEngine
import com.mirunubi.bjstock.core.strategy.StrategyActivationFailure
import com.mirunubi.bjstock.core.strategy.StrategyErrorKind
import com.mirunubi.bjstock.core.strategy.StrategyEvaluationResult
import com.mirunubi.bjstock.core.strategy.StrategyEvaluationStatus
import com.mirunubi.bjstock.core.strategy.StrategyFactorDetail
import com.mirunubi.bjstock.core.strategy.StrategyScoreMath
import com.mirunubi.bjstock.core.strategy.StrategyVersionException
import com.mirunubi.bjstock.ui.text.KoreanLabels
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

data class StrategyUiState(
    val list: ListState = ListState.Loading,
    val strategy: StrategyPanel? = null,
    val version: VersionLayer? = null,
    val dialog: StrategyDialog? = null,
    val notice: Notice? = null,
    val busy: Boolean = false,
)

sealed interface ListState {
    data object Loading : ListState
    data class Loaded(val cards: List<StrategyCard>) : ListState
    data class Failed(val message: String) : ListState
}

data class StrategyCard(
    val strategyId: Long,
    val name: String,
    val code: String,
    val versionCount: String,
    val statusSummary: String,
)

data class StrategyPanel(
    val strategyId: Long,
    val name: String,
    val code: String,
    val versions: VersionsState,
)

sealed interface VersionsState {
    data object Loading : VersionsState
    data class Loaded(val rows: List<VersionRow>) : VersionsState
    data class Failed(val message: String) : VersionsState
}

data class StatusBadge(val status: StrategyVersionStatus, val label: String)

data class VersionRow(
    val versionId: Long,
    val label: String,
    val badge: StatusBadge,
    val thresholdSummary: String,
    val description: String?,
)

sealed interface VersionLayer {
    val versionId: Long

    data class Loading(override val versionId: Long) : VersionLayer
    data class Failed(override val versionId: Long, val message: String) : VersionLayer
    data class Loaded(val panel: VersionPanel) : VersionLayer {
        override val versionId: Long get() = panel.versionId
    }
}

data class ThresholdBand(val decision: TradeDecision, val label: String, val range: String)

/** One system factor row; text fields hold display values (percent, score 0..100). */
data class FactorInput(
    val code: String,
    val name: String,
    val enabled: Boolean,
    val weightPercent: String,
    val calculationVersion: String,
    val availableVersions: List<String>,
    val minScore: String,
    val maxScore: String,
    /** A strategy_factor_weights row exists for this factor. */
    val stored: Boolean,
)

data class TotalWeight(val text: String, val complete: Boolean, val warning: String?)

data class RuleView(
    val ruleId: Long,
    val name: String,
    val description: String?,
    val condition: String,
    val action: SignalAction,
    val actionLabel: String,
    val priority: String,
    val enabled: Boolean,
    val operator: SignalOperator,
    val thresholdValue: String,
    val priorityValue: Int,
)

data class RuleForm(
    val ruleCode: String = "",
    val operator: SignalOperator = SignalOperator.LTE,
    val threshold: String = "",
    val action: SignalAction = SignalAction.BUY,
    val priority: String = "10",
    /** Editing an existing rule: same rule_code updates it (existing service semantics). */
    val editing: Boolean = false,
)

data class VersionPanel(
    val strategyId: Long,
    val strategyName: String,
    val versionId: Long,
    val versionNo: Int,
    val label: String,
    val badge: StatusBadge,
    val editable: Boolean,
    val lockedReason: String?,
    val savedSell: String,
    val savedBuy: String,
    val sellInput: String,
    val buyInput: String,
    val savedFactors: List<FactorInput>,
    val factors: List<FactorInput>,
    val expandedFactors: Set<String> = emptySet(),
    val rules: List<RuleView>,
    val ruleForm: RuleForm? = null,
    val preview: PreviewPanel = PreviewPanel(),
) {
    val thresholdsDirty: Boolean get() = sellInput != savedSell || buyInput != savedBuy
    val factorsDirty: Boolean get() = factors != savedFactors
    val hasUnsavedChanges: Boolean get() = thresholdsDirty || factorsDirty
}

data class PreviewPanel(
    val query: String = "",
    val search: PreviewSearch = PreviewSearch.Idle,
    val instrument: PreviewInstrument? = null,
    val dates: List<LocalDate> = emptyList(),
    val dateInput: String = "",
    val result: PreviewResultState = PreviewResultState.Idle,
)

sealed interface PreviewSearch {
    data object Idle : PreviewSearch
    data object Searching : PreviewSearch
    data class Results(val instruments: List<PreviewInstrument>) : PreviewSearch
    data object NoResults : PreviewSearch
    data class Failed(val message: String) : PreviewSearch
}

sealed interface PreviewResultState {
    data object Idle : PreviewResultState
    data object Loading : PreviewResultState
    data class Failed(val message: String) : PreviewResultState
    data class Shown(val view: PreviewView) : PreviewResultState
}

data class PreviewFactorView(
    val name: String,
    val code: String,
    val score: String,
    val weight: String,
    val contribution: String,
    val gateNote: String?,
)

data class PreviewView(
    val instrument: String,
    val date: String,
    val decision: TradeDecision?,
    val decisionLabel: String,
    val sourceLabel: String?,
    /** Null when no factor quant score applies (signal rule) or none was computed. */
    val score: String?,
    val ruleLine: String?,
    val notice: String?,
    val factorRows: List<PreviewFactorView>,
    val missingFactors: List<String>,
)

sealed interface StrategyDialog {
    data class CreateStrategy(val name: String = "", val code: String = "", val error: String? = null) : StrategyDialog
    data class ConfirmActivate(val versionId: Long, val title: String, val body: String) : StrategyDialog
    data class ConfirmDeleteRule(val ruleId: Long, val ruleName: String) : StrategyDialog
}

data class Notice(val message: String, val isError: Boolean)

/** Pure mapping from domain values to Korean presentation. Canonical codes stay internal. */
object StrategyPresenter {
    const val LIST_EMPTY = "등록된 전략이 없습니다. '새 전략'으로 시작해 보세요."
    const val LIST_FAILED = "전략 목록을 불러오지 못했습니다."
    const val VERSIONS_FAILED = "버전 목록을 불러오지 못했습니다."
    const val VERSION_FAILED = "버전 정보를 불러오지 못했습니다."
    const val VERSIONS_EMPTY = "버전이 없습니다. '새 작성본'으로 만들어 보세요."
    const val LOCKED_ACTIVE = "사용 중인 버전은 수정할 수 없습니다.\n변경하려면 새 작성본을 만드세요."
    const val LOCKED_RETIRED = "종료된 버전은 수정할 수 없습니다."
    const val PRIORITY_HELP = "숫자가 작은 우선순위가 먼저 적용됩니다."
    const val RULES_EMPTY = "신호 규칙이 없습니다. 팩터 점수로만 판단합니다."
    const val WEIGHT_TOTAL_WARNING = "활성화하려면 사용 팩터의 총 비중이 100%여야 합니다."
    const val NO_ENABLED_FACTOR_HINT = "사용할 팩터를 하나 이상 선택해 주세요."
    const val WEIGHT_NOT_NUMBER = "비중을 숫자로 입력해 주세요."
    const val SCORE_NOT_NUMBER = "기준 점수를 숫자로 입력해 주세요."
    const val GATE_NOT_NUMBER = "최소·최대 점수는 숫자로 입력하거나 비워 두세요."
    const val THRESHOLD_ORDER = "매도 기준은 매수 기준보다 낮아야 합니다."
    const val THRESHOLD_RANGE = "기준 점수는 0~100 사이여야 합니다."
    const val UNSAVED_BLOCKS_ACTIVATION = "저장하지 않은 변경 사항이 있습니다. 먼저 저장하거나 되돌려 주세요."
    const val PREVIEW_USES_SAVED = "저장된 설정으로 미리보기합니다. 저장하지 않은 변경 사항은 반영되지 않습니다."
    const val PREVIEW_NOT_ORDER = "미리보기는 판단만 보여 주며 주문을 만들지 않고 저장하지도 않습니다."
    const val PREVIEW_FAILED = "미리보기를 하지 못했습니다. 다시 시도해 주세요."
    const val PREVIEW_DATE_INVALID = "평가일을 2026-09-29 형식으로 입력해 주세요."
    const val PREVIEW_NO_DATES = "저장된 시세 데이터가 없습니다. 평가일을 직접 입력해 주세요."
    const val PREVIEW_INSUFFICIENT = "평가에 필요한 팩터 데이터가 부족합니다."
    const val PREVIEW_INVALID_STRATEGY = "전략 설정이 완성되지 않아 미리보기할 수 없습니다. 판단 기준과 사용 팩터 비중을 확인해 주세요."
    const val PREVIEW_GATE_FAILED = "팩터 조건 미충족"
    const val PREVIEW_RULE_NO_SCORE = "신호 규칙으로 판단되어 팩터 점수는 사용하지 않았습니다."
    const val SEARCH_NO_RESULTS = "검색 결과가 없습니다."
    const val SEARCH_FAILED = "종목을 검색하지 못했습니다."
    const val RULE_NAME_REQUIRED = "규칙 이름을 입력해 주세요."
    const val RULE_THRESHOLD_INVALID = "기준 %를 숫자로 입력해 주세요."
    const val RULE_PRIORITY_INVALID = "우선순위는 0 이상의 정수로 입력해 주세요."
    const val NAME_REQUIRED = "전략 이름을 입력해 주세요."
    const val CODE_REQUIRED = "전략 코드를 입력해 주세요."
    const val CODE_EXISTS = "이미 사용 중인 전략 코드입니다."
    const val INPUT_INVALID = "입력값을 확인해 주세요."
    const val GENERIC_FAILED = "처리하지 못했습니다. 다시 시도해 주세요."
    const val NOT_AVAILABLE = "—"

    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd")
    private val HUNDRED = BigDecimal(100)

    fun badge(status: StrategyVersionStatus) = StatusBadge(status, KoreanLabels.versionStatus(status))

    fun versionLabel(versionNo: Int) = "V$versionNo"

    fun card(strategy: StrategyEntity, versions: List<StrategyVersionEntity>) = StrategyCard(
        strategyId = strategy.id,
        name = strategy.strategyName,
        code = strategy.strategyCode,
        versionCount = "버전 ${versions.size}개",
        statusSummary = statusSummary(versions),
    )

    /** e.g. `사용중 2개 · 작성중 1개`; every ACTIVE version is counted, none is singled out. */
    fun statusSummary(versions: List<StrategyVersionEntity>): String {
        if (versions.isEmpty()) return "버전 없음"
        val counts = versions.groupingBy { it.status }.eachCount()
        return listOf(StrategyVersionStatus.ACTIVE, StrategyVersionStatus.DRAFT, StrategyVersionStatus.RETIRED)
            .mapNotNull { status -> counts[status]?.let { "${KoreanLabels.versionStatus(status)} ${it}개" } }
            .joinToString(" · ")
    }

    /** Highest version number first. */
    fun versionRows(versions: List<StrategyVersionEntity>): List<VersionRow> =
        versions.sortedByDescending { it.versionNo }.map { version ->
            val sell = score(version.sellThreshold)
            val buy = score(version.buyThreshold)
            VersionRow(
                versionId = version.id,
                label = versionLabel(version.versionNo),
                badge = badge(version.status),
                thresholdSummary = "매도 $sell 이하 · 매수 $buy 이상",
                description = version.description?.takeIf { it.isNotBlank() },
            )
        }

    fun lockedReason(status: StrategyVersionStatus): String? = when (status) {
        StrategyVersionStatus.DRAFT -> null
        StrategyVersionStatus.ACTIVE -> LOCKED_ACTIVE
        StrategyVersionStatus.RETIRED -> LOCKED_RETIRED
    }

    fun panel(strategyName: String, snapshot: VersionSnapshot, versionsFor: (String) -> List<String>): VersionPanel {
        val version = snapshot.version
        val sell = score(version.sellThreshold)
        val buy = score(version.buyThreshold)
        val factors = factorInputs(snapshot, versionsFor)
        return VersionPanel(
            strategyId = version.strategyId,
            strategyName = strategyName,
            versionId = version.id,
            versionNo = version.versionNo,
            label = versionLabel(version.versionNo),
            badge = badge(version.status),
            editable = version.status == StrategyVersionStatus.DRAFT,
            lockedReason = lockedReason(version.status),
            savedSell = sell,
            savedBuy = buy,
            sellInput = sell,
            buyInput = buy,
            savedFactors = factors,
            factors = factors,
            rules = snapshot.rules.map(::rule),
        )
    }

    /**
     * Bands with the exact StrategyScoreMath boundaries: score <= sell is SELL, score >= buy is BUY,
     * anything strictly between is HOLD. Null while the inputs are not numbers.
     */
    fun bands(sellText: String, buyText: String): List<ThresholdBand>? {
        val sell = decimal(sellText) ?: return null
        val buy = decimal(buyText) ?: return null
        val s = plain(sell)
        val b = plain(buy)
        return listOf(
            ThresholdBand(TradeDecision.SELL, KoreanLabels.decision(TradeDecision.SELL), "$s 이하"),
            ThresholdBand(TradeDecision.HOLD, KoreanLabels.decision(TradeDecision.HOLD), "$s 초과 ~ $b 미만"),
            ThresholdBand(TradeDecision.BUY, KoreanLabels.decision(TradeDecision.BUY), "$b 이상"),
        )
    }

    /** Immediate input hint only; saving still goes through the service, which stays authoritative. */
    fun thresholdHint(sellText: String, buyText: String): String? {
        val sell = decimal(sellText) ?: return SCORE_NOT_NUMBER
        val buy = decimal(buyText) ?: return SCORE_NOT_NUMBER
        if (sell < BigDecimal.ZERO || buy < BigDecimal.ZERO || sell > HUNDRED || buy > HUNDRED) return THRESHOLD_RANGE
        if (sell >= buy) return THRESHOLD_ORDER
        return null
    }

    fun factorInputs(snapshot: VersionSnapshot, versionsFor: (String) -> List<String>): List<FactorInput> {
        val byCode = snapshot.weights.associateBy { snapshot.factorCodes[it.factorId] }
        return FactorCodes.SYSTEM.map { code ->
            val weight = byCode[code]
            val calcVersion = weight?.factorCalculationVersion ?: FactorCalculationVersions.V1
            FactorInput(
                code = code,
                name = KoreanLabels.factorName(code),
                enabled = weight?.enabled ?: false,
                weightPercent = weight?.let { plain(StrategyScoreMath.weightStoredToPercent(it.weight)) } ?: "0",
                calculationVersion = calcVersion,
                availableVersions = (versionsFor(code) + calcVersion).distinct(),
                minScore = weight?.minScore?.let(::score).orEmpty(),
                maxScore = weight?.maxScore?.let(::score).orEmpty(),
                stored = weight != null,
            )
        }
    }

    fun weightLabel(input: FactorInput): String = "비중 ${input.weightPercent.trim().ifEmpty { "0" }}%"

    fun calculationLabel(input: FactorInput): String = "계산버전 ${input.calculationVersion}"

    fun gateLabel(input: FactorInput): String {
        val min = input.minScore.trim()
        val max = input.maxScore.trim()
        if (min.isEmpty() && max.isEmpty()) return "점수 조건 없음"
        return "최소 점수 ${min.ifEmpty { NOT_AVAILABLE }} · 최대 점수 ${max.ifEmpty { NOT_AVAILABLE }}"
    }

    /** Sum of enabled factor weights as typed; the warning mirrors the activation requirement as text only. */
    fun totalWeight(factors: List<FactorInput>): TotalWeight {
        val enabled = factors.filter { it.enabled }
        if (enabled.isEmpty()) return TotalWeight("0%", complete = false, warning = NO_ENABLED_FACTOR_HINT)
        var sum = BigDecimal.ZERO
        enabled.forEach { factor ->
            val value = decimal(factor.weightPercent) ?: return TotalWeight(NOT_AVAILABLE, complete = false, warning = WEIGHT_NOT_NUMBER)
            sum = sum.add(value)
        }
        val complete = sum.compareTo(HUNDRED) == 0
        return TotalWeight("${plain(sum)}%", complete, if (complete) null else WEIGHT_TOTAL_WARNING)
    }

    fun rule(entity: StrategySignalRuleEntity) = RuleView(
        ruleId = entity.id,
        name = entity.ruleCode,
        description = entity.description?.takeIf { it.isNotBlank() },
        condition = condition(entity.metricCode, entity.operator, entity.thresholdValue),
        action = entity.action,
        actionLabel = actionLabel(entity.action),
        priority = "우선순위 ${entity.priority}",
        enabled = entity.enabled,
        operator = entity.operator,
        thresholdValue = entity.thresholdValue,
        priorityValue = entity.priority,
    )

    /** e.g. `일간 등락률 -5% 이하`. */
    fun condition(metric: SignalMetricCode, operator: SignalOperator, thresholdValue: String): String =
        "${metricLabel(metric)} ${signedPercent(thresholdValue)} ${operatorLabel(operator)}"

    fun metricLabel(metric: SignalMetricCode): String = when (metric) {
        SignalMetricCode.DAILY_CHANGE_PCT -> "일간 등락률"
    }

    fun operatorLabel(operator: SignalOperator): String = when (operator) {
        SignalOperator.GTE -> "이상"
        SignalOperator.LTE -> "이하"
    }

    fun actionLabel(action: SignalAction): String = when (action) {
        SignalAction.BUY -> "매수"
        SignalAction.SELL -> "매도"
    }

    fun sourceLabel(source: DecisionSource): String = when (source) {
        DecisionSource.SIGNAL_RULE -> "신호 규칙"
        DecisionSource.FACTOR_STRATEGY -> "팩터 전략"
    }

    /** `-5` -> `-5%`, `3.0` -> `+3%`; non-numeric text is shown as stored. */
    fun signedPercent(raw: String): String {
        val value = SignalRuleEngine.parseThreshold(raw) ?: return raw
        val text = plain(value)
        return if (value > BigDecimal.ZERO) "+$text%" else "$text%"
    }

    fun ruleForm(rule: RuleView) = RuleForm(
        ruleCode = rule.name,
        operator = rule.operator,
        threshold = SignalRuleEngine.parseThreshold(rule.thresholdValue)?.let(::plain) ?: rule.thresholdValue,
        action = rule.action,
        priority = rule.priorityValue.toString(),
        editing = true,
    )

    fun ruleFormError(form: RuleForm): String? = when {
        form.ruleCode.isBlank() -> RULE_NAME_REQUIRED
        SignalRuleEngine.parseThreshold(form.threshold) == null -> RULE_THRESHOLD_INVALID
        form.priority.trim().toIntOrNull()?.takeIf { it >= 0 } == null -> RULE_PRIORITY_INVALID
        else -> null
    }

    fun activationDialog(panel: VersionPanel) = StrategyDialog.ConfirmActivate(
        versionId = panel.versionId,
        title = "${panel.label}${objectParticle(panel.versionNo)} 사용 시작하시겠습니까?",
        body = "전략: ${panel.strategyName}\n버전: ${panel.label}\n\n" +
            "사용 시작 후에는 이 버전의 판단 기준, 팩터 가중치, 팩터 조건(최소·최대 점수), 신호 규칙을 수정할 수 없습니다.\n" +
            "이후 변경하려면 새 작성본을 만들어야 합니다.",
    )

    /** 을/를 after a version number read in Sino-Korean (일, 이, 삼 …). */
    fun objectParticle(number: Int): String = when (number % 10) {
        2, 4, 5, 9 -> "를"
        else -> "을"
    }

    fun activationFailure(kind: StrategyActivationFailure): String = when (kind) {
        StrategyActivationFailure.NOT_FOUND -> "버전을 찾을 수 없습니다. 목록을 새로 불러와 주세요."
        StrategyActivationFailure.NOT_DRAFT -> "작성중인 버전만 사용 시작할 수 있습니다."
        StrategyActivationFailure.NO_ENABLED_FACTOR -> "사용할 팩터를 하나 이상 선택해 주세요."
        StrategyActivationFailure.INVALID_WEIGHT_SUM -> "사용 팩터의 총 비중을 100%로 맞춰 주세요."
        StrategyActivationFailure.INVALID_THRESHOLDS -> "매도 기준은 매수 기준보다 낮아야 합니다."
        StrategyActivationFailure.UNSUPPORTED_FACTOR_VERSION -> "지원하지 않는 팩터 계산 버전이 포함되어 있습니다."
        StrategyActivationFailure.INVALID_GATE -> "팩터 최소·최대 점수 조건을 확인해 주세요."
        StrategyActivationFailure.CONFLICTING_SIGNAL_RULES -> "서로 충돌하는 신호 규칙이 있어 사용할 수 없습니다. 신호 규칙을 확인해 주세요."
    }

    fun errorKind(kind: StrategyErrorKind): String = when (kind) {
        StrategyErrorKind.NOT_FOUND -> "대상을 찾을 수 없습니다. 목록을 새로 불러와 주세요."
        StrategyErrorKind.NOT_DRAFT, StrategyErrorKind.IMMUTABLE -> "사용 중이거나 종료된 버전은 수정할 수 없습니다."
        StrategyErrorKind.INVALID_STATE -> INPUT_INVALID
        StrategyErrorKind.INVALID_THRESHOLDS -> "매도 기준은 매수 기준보다 낮아야 하며, 0~100 사이여야 합니다."
        StrategyErrorKind.INVALID_WEIGHT -> "비중은 0%에서 100% 사이로 입력해 주세요."
        StrategyErrorKind.INVALID_GATE -> "최소·최대 점수는 0~100이며, 최소 점수가 최대 점수보다 클 수 없습니다."
        StrategyErrorKind.INVALID_CALCULATION_VERSION -> "계산 버전을 선택해 주세요."
        StrategyErrorKind.VERSION_NOT_ACTIVE -> "사용중인 버전이 아닙니다."
    }

    /** Fixed Korean text only; exception messages, ids, and SQL text are never shown. */
    fun failure(error: Throwable): String = when (error) {
        is StrategyVersionException -> errorKind(error.kind)
        is IllegalArgumentException -> INPUT_INVALID
        else -> GENERIC_FAILED
    }

    fun preview(
        result: StrategyEvaluationResult,
        instrument: PreviewInstrument,
        date: LocalDate,
        rules: List<RuleView>,
    ): PreviewView {
        val base = PreviewView(
            instrument = "${instrument.name} · ${instrument.symbol}",
            date = date(date),
            decision = null,
            decisionLabel = "판단 불가",
            sourceLabel = null,
            score = null,
            ruleLine = null,
            notice = null,
            factorRows = emptyList(),
            missingFactors = emptyList(),
        )
        return when (result.status) {
            StrategyEvaluationStatus.SUCCESS -> when (result.decisionSource) {
                DecisionSource.SIGNAL_RULE -> base.copy(
                    decision = result.quantDecision,
                    decisionLabel = result.quantDecision?.let(KoreanLabels::decision) ?: base.decisionLabel,
                    sourceLabel = sourceLabel(DecisionSource.SIGNAL_RULE),
                    ruleLine = ruleLine(result, rules),
                    notice = PREVIEW_RULE_NO_SCORE,
                )
                DecisionSource.FACTOR_STRATEGY -> base.copy(
                    decision = result.quantDecision,
                    decisionLabel = result.quantDecision?.let(KoreanLabels::decision) ?: base.decisionLabel,
                    sourceLabel = sourceLabel(DecisionSource.FACTOR_STRATEGY),
                    score = result.quantScoreStored?.let(::score),
                    factorRows = result.factorDetails.map(::previewFactor),
                )
            }
            StrategyEvaluationStatus.FACTOR_GATE_FAILED -> base.copy(
                decision = TradeDecision.NO_ACTION,
                decisionLabel = KoreanLabels.decision(TradeDecision.NO_ACTION),
                sourceLabel = sourceLabel(DecisionSource.FACTOR_STRATEGY),
                score = result.quantScoreStored?.let(::score),
                notice = listOfNotNull(PREVIEW_GATE_FAILED, result.failedFactorCode?.let(KoreanLabels::factorName))
                    .joinToString(" · "),
                factorRows = result.factorDetails.map(::previewFactor),
            )
            StrategyEvaluationStatus.INSUFFICIENT_FACTORS -> base.copy(
                sourceLabel = sourceLabel(DecisionSource.FACTOR_STRATEGY),
                notice = PREVIEW_INSUFFICIENT,
                missingFactors = result.missingFactorCodes.map(KoreanLabels::factorName),
            )
            StrategyEvaluationStatus.INVALID_STRATEGY -> base.copy(notice = PREVIEW_INVALID_STRATEGY)
            StrategyEvaluationStatus.ALREADY_EVALUATED -> base.copy(notice = PREVIEW_FAILED)
        }
    }

    /** Built from the triggered rule and the observed / threshold values the engine returned. */
    private fun ruleLine(result: StrategyEvaluationResult, rules: List<RuleView>): String? {
        val observed = result.observedValue?.let { "일간 등락률 ${signedPercent(it)}" }
        val rule = rules.firstOrNull { it.ruleId == result.triggeredRuleId }
        val condition = rule?.let { "'${it.name}' ${it.condition} → ${it.actionLabel}" }
            ?: result.thresholdValue?.let { "기준 ${signedPercent(it)}" }
        return listOfNotNull(observed, condition).joinToString(" · ").ifEmpty { null }
    }

    private fun previewFactor(detail: StrategyFactorDetail): PreviewFactorView {
        val contribution = StrategyScoreMath.quantScoreStored(listOf(detail.weightedScoreStored))
        return PreviewFactorView(
            name = KoreanLabels.factorName(detail.factorCode),
            code = detail.factorCode,
            score = "점수 ${score(detail.factorScoreStored)}",
            weight = "비중 ${plain(StrategyScoreMath.weightStoredToPercent(detail.weightStored))}%",
            contribution = "기여도 ${score(contribution)}",
            gateNote = when {
                !detail.gateFailed -> null
                detail.minScoreStored != null && detail.factorScoreStored < detail.minScoreStored ->
                    "최소 점수 ${score(detail.minScoreStored)} 미만"
                detail.maxScoreStored != null && detail.factorScoreStored > detail.maxScoreStored ->
                    "최대 점수 ${score(detail.maxScoreStored)} 초과"
                else -> PREVIEW_GATE_FAILED
            },
        )
    }

    fun date(date: LocalDate): String = date.format(DATE)

    fun dateChip(date: LocalDate): String = "${date.monthValue}.${date.dayOfMonth}"

    fun parseDate(raw: String): LocalDate? = try {
        LocalDate.parse(raw.trim())
    } catch (_: DateTimeParseException) {
        null
    }

    /** Stored score -> display text, e.g. 400000 -> `40`. */
    fun score(stored: Long): String = plain(StrategyScoreMath.scoreToDisplay(stored))

    fun decimal(raw: String): BigDecimal? = raw.trim().takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()

    private fun plain(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()
}
