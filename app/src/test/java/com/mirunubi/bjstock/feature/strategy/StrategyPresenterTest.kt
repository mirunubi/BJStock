package com.mirunubi.bjstock.feature.strategy

import com.mirunubi.bjstock.core.factor.FactorCodes
import com.mirunubi.bjstock.core.model.DecisionSource
import com.mirunubi.bjstock.core.model.SignalAction
import com.mirunubi.bjstock.core.model.SignalOperator
import com.mirunubi.bjstock.core.model.StrategyVersionStatus
import com.mirunubi.bjstock.core.model.TradeDecision
import com.mirunubi.bjstock.core.strategy.StrategyActivationFailure
import com.mirunubi.bjstock.core.strategy.StrategyErrorKind
import com.mirunubi.bjstock.core.strategy.StrategyEvaluationResult
import com.mirunubi.bjstock.core.strategy.StrategyEvaluationStatus
import com.mirunubi.bjstock.core.strategy.StrategyFactorDetail
import com.mirunubi.bjstock.core.strategy.StrategyScoreMath
import com.mirunubi.bjstock.core.strategy.StrategyVersionException
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyPresenterTest {
    private val fixtures = StrategyFixtures
    private val date = LocalDate.of(2026, 9, 29)

    @Test
    fun strategyCard_isKorean_withCodeAsSecondaryText_andCountsEveryActiveVersion() {
        val versions = listOf(
            fixtures.version(1, 1, StrategyVersionStatus.ACTIVE),
            fixtures.version(2, 2, StrategyVersionStatus.ACTIVE),
            fixtures.version(3, 3, StrategyVersionStatus.DRAFT),
        )

        val card = StrategyPresenter.card(fixtures.strategy(), versions)

        assertEquals("기본 모멘텀 전략", card.name)
        assertEquals("MOMENTUM_BASIC", card.code)
        assertEquals("버전 3개", card.versionCount)
        assertEquals("사용중 2개 · 작성중 1개", card.statusSummary)
        assertEquals("버전 없음", StrategyPresenter.statusSummary(emptyList()))
    }

    @Test
    fun versionStatuses_translate_toKorean_withCanonicalEnumKept() {
        assertEquals("작성중", StrategyPresenter.badge(StrategyVersionStatus.DRAFT).label)
        assertEquals("사용중", StrategyPresenter.badge(StrategyVersionStatus.ACTIVE).label)
        assertEquals("종료", StrategyPresenter.badge(StrategyVersionStatus.RETIRED).label)
        assertEquals(StrategyVersionStatus.ACTIVE, StrategyPresenter.badge(StrategyVersionStatus.ACTIVE).status)
    }

    @Test
    fun multipleActiveVersions_areListedHighestFirst_noneCalledCurrent() {
        val rows = StrategyPresenter.versionRows(
            listOf(
                fixtures.version(1, 1, StrategyVersionStatus.ACTIVE),
                fixtures.version(2, 2, StrategyVersionStatus.ACTIVE),
                fixtures.version(3, 3, StrategyVersionStatus.DRAFT, sell = "35", buy = "75"),
            ),
        )

        assertEquals(listOf("V3", "V2", "V1"), rows.map { it.label })
        assertEquals(listOf("작성중", "사용중", "사용중"), rows.map { it.badge.label })
        assertEquals("매도 35 이하 · 매수 75 이상", rows.first().thresholdSummary)
        val visible = rows.flatMap { listOf(it.label, it.badge.label, it.thresholdSummary) }
        assertTrue(visible.none { "현재" in it })
    }

    @Test
    fun thresholdBands_matchStrategyScoreMathInclusivity() {
        val bands = StrategyPresenter.bands("40", "70")!!

        assertEquals(listOf("매도", "관망", "매수"), bands.map { it.label })
        assertEquals(listOf("40 이하", "40 초과 ~ 70 미만", "70 이상"), bands.map { it.range })
        assertEquals(listOf(TradeDecision.SELL, TradeDecision.HOLD, TradeDecision.BUY), bands.map { it.decision })

        val sell = fixtures.score("40")
        val buy = fixtures.score("70")
        assertEquals(TradeDecision.SELL, StrategyScoreMath.decide(fixtures.score("40"), sell, buy))
        assertEquals(TradeDecision.HOLD, StrategyScoreMath.decide(fixtures.score("40.0001"), sell, buy))
        assertEquals(TradeDecision.HOLD, StrategyScoreMath.decide(fixtures.score("69.9999"), sell, buy))
        assertEquals(TradeDecision.BUY, StrategyScoreMath.decide(fixtures.score("70"), sell, buy))
        assertNull(StrategyPresenter.bands("사십", "70"))
    }

    @Test
    fun thresholdHint_isPresentationOnly_forOrderRangeAndNumbers() {
        assertNull(StrategyPresenter.thresholdHint("40", "70"))
        assertEquals(StrategyPresenter.THRESHOLD_ORDER, StrategyPresenter.thresholdHint("70", "70"))
        assertEquals(StrategyPresenter.THRESHOLD_RANGE, StrategyPresenter.thresholdHint("40", "120"))
        assertEquals(StrategyPresenter.SCORE_NOT_NUMBER, StrategyPresenter.thresholdHint("", "70"))
    }

    @Test
    fun factorRows_useKoreanNames_percentWeights_calcVersion_andGates() {
        val snapshot = VersionSnapshot(
            fixtures.version(20, 2, StrategyVersionStatus.ACTIVE),
            fixtures.fullWeights(20),
            fixtures.FACTOR_CODES,
            emptyList(),
        )

        val rows = StrategyPresenter.factorInputs(snapshot) { listOf("v1") }

        assertEquals(
            listOf("20일 이동평균 대비", "60일 이동평균 대비", "20일 모멘텀", "60일 모멘텀", "20일 변동성", "20일 거래량 비율"),
            rows.map { it.name },
        )
        assertEquals(FactorCodes.SYSTEM, rows.map { it.code })
        val momentum = rows.single { it.code == FactorCodes.MOMENTUM_20D }
        assertEquals("비중 25%", StrategyPresenter.weightLabel(momentum))
        assertEquals("계산버전 v1", StrategyPresenter.calculationLabel(momentum))
        assertEquals("점수 조건 없음", StrategyPresenter.gateLabel(momentum))
        val volatility = rows.single { it.code == FactorCodes.VOLATILITY_20D }
        assertEquals("최소 점수 30 · 최대 점수 —", StrategyPresenter.gateLabel(volatility))
    }

    @Test
    fun totalWeight_sumsEnabledFactorsOnly_andWarnsWhenNot100() {
        val snapshot = VersionSnapshot(
            fixtures.version(30, 3, StrategyVersionStatus.DRAFT),
            fixtures.fullWeights(30),
            fixtures.FACTOR_CODES,
            emptyList(),
        )
        val rows = StrategyPresenter.factorInputs(snapshot) { listOf("v1") }

        val full = StrategyPresenter.totalWeight(rows)
        assertEquals("100%", full.text)
        assertTrue(full.complete)
        assertNull(full.warning)

        val oneDisabled = rows.map { if (it.code == FactorCodes.PRICE_VS_MA20) it.copy(enabled = false) else it }
        val ninety = StrategyPresenter.totalWeight(oneDisabled)
        assertEquals("90%", ninety.text)
        assertFalse(ninety.complete)
        assertEquals("활성화하려면 사용 팩터의 총 비중이 100%여야 합니다.", ninety.warning)

        assertEquals(StrategyPresenter.NO_ENABLED_FACTOR_HINT, StrategyPresenter.totalWeight(rows.map { it.copy(enabled = false) }).warning)
    }

    @Test
    fun signalRules_areKorean_withSignedPercent_andPriorityHelp() {
        val rules = fixtures.demoRules(20).map(StrategyPresenter::rule)

        assertEquals("일간 등락률 -5% 이하", rules[0].condition)
        assertEquals("매수", rules[0].actionLabel)
        assertEquals("우선순위 10", rules[0].priority)
        assertEquals("일간 등락률 +3% 이상", rules[1].condition)
        assertEquals("매도", rules[1].actionLabel)
        assertEquals("우선순위 20", rules[1].priority)
        assertEquals("숫자가 작은 우선순위가 먼저 적용됩니다.", StrategyPresenter.PRIORITY_HELP)
        assertTrue(rules.none { "DAILY_CHANGE_PCT" in it.condition || "LTE" in it.condition || "GTE" in it.condition })
        assertEquals("0%", StrategyPresenter.signedPercent("0.00"))
        assertEquals("+2.5%", StrategyPresenter.signedPercent("2.50%"))
    }

    @Test
    fun activationDialog_namesStrategyAndVersion_andStatesWhatBecomesImmutable() {
        val panel = StrategyPresenter.panel(
            "기본 모멘텀 전략",
            VersionSnapshot(fixtures.version(30, 3, StrategyVersionStatus.DRAFT), emptyList(), fixtures.FACTOR_CODES, emptyList()),
        ) { listOf("v1") }

        val dialog = StrategyPresenter.activationDialog(panel)

        assertEquals("V3을 사용 시작하시겠습니까?", dialog.title)
        listOf("기본 모멘텀 전략", "V3", "판단 기준", "팩터 가중치", "최소·최대 점수", "신호 규칙", "수정할 수 없습니다", "새 작성본")
            .forEach { assertTrue(it, it in dialog.body) }
        assertEquals("를", StrategyPresenter.objectParticle(2))
        assertEquals("을", StrategyPresenter.objectParticle(10))
    }

    @Test
    fun failures_mapToFixedKoreanText_neverTheExceptionMessage() {
        assertEquals("서로 충돌하는 신호 규칙이 있어 사용할 수 없습니다. 신호 규칙을 확인해 주세요.", StrategyPresenter.activationFailure(StrategyActivationFailure.CONFLICTING_SIGNAL_RULES))
        assertEquals("사용 팩터의 총 비중을 100%로 맞춰 주세요.", StrategyPresenter.activationFailure(StrategyActivationFailure.INVALID_WEIGHT_SUM))
        assertEquals("사용할 팩터를 하나 이상 선택해 주세요.", StrategyPresenter.activationFailure(StrategyActivationFailure.NO_ENABLED_FACTOR))
        assertEquals("매도 기준은 매수 기준보다 낮아야 합니다.", StrategyPresenter.activationFailure(StrategyActivationFailure.INVALID_THRESHOLDS))
        assertEquals("지원하지 않는 팩터 계산 버전이 포함되어 있습니다.", StrategyPresenter.activationFailure(StrategyActivationFailure.UNSUPPORTED_FACTOR_VERSION))
        StrategyActivationFailure.entries.forEach { assertTrue(StrategyPresenter.activationFailure(it).any { c -> c in '\uAC00'..'\uD7A3' }) }

        val raw = "ACTIVE/RETIRED strategy versions cannot change thresholds (version 42)"
        val mapped = StrategyPresenter.failure(StrategyVersionException(StrategyErrorKind.IMMUTABLE, raw))
        assertEquals("사용 중이거나 종료된 버전은 수정할 수 없습니다.", mapped)
        assertEquals(StrategyPresenter.GENERIC_FAILED, StrategyPresenter.failure(IllegalStateException("SQLITE_CONSTRAINT strategies.id=7")))
        assertEquals(StrategyPresenter.INPUT_INVALID, StrategyPresenter.failure(IllegalArgumentException("rule_code must not be blank")))
    }

    @Test
    fun preview_mapsDecisionsAndSource_withFactorContributions() {
        listOf(TradeDecision.BUY to "매수", TradeDecision.SELL to "매도", TradeDecision.HOLD to "관망").forEach { (decision, label) ->
            val view = StrategyPresenter.preview(factorResult(decision), fixtures.SAMSUNG, date, emptyList())
            assertEquals(label, view.decisionLabel)
            assertEquals("팩터 전략", view.sourceLabel)
        }

        val view = StrategyPresenter.preview(factorResult(TradeDecision.BUY), fixtures.SAMSUNG, date, emptyList())
        assertEquals("삼성전자 · 005930", view.instrument)
        assertEquals("2026.09.29", view.date)
        assertEquals("62.4", view.score)
        val row = view.factorRows.single()
        assertEquals("20일 모멘텀", row.name)
        assertEquals("점수 62.4", row.score)
        assertEquals("비중 100%", row.weight)
        assertEquals("기여도 62.4", row.contribution)
        assertNull(row.gateNote)
    }

    @Test
    fun preview_gateFailed_insufficient_andInvalid_areExplained_notZeroed() {
        val gate = StrategyPresenter.preview(
            factorResult(TradeDecision.NO_ACTION).copy(
                status = StrategyEvaluationStatus.FACTOR_GATE_FAILED,
                failedFactorCode = FactorCodes.MOMENTUM_20D,
                factorDetails = listOf(detail(gateFailed = true, min = fixtures.score("70"))),
            ),
            fixtures.SAMSUNG,
            date,
            emptyList(),
        )
        assertEquals("판단 없음", gate.decisionLabel)
        assertEquals("팩터 조건 미충족 · 20일 모멘텀", gate.notice)
        assertEquals("최소 점수 70 미만", gate.factorRows.single().gateNote)

        val missing = StrategyPresenter.preview(
            StrategyEvaluationResult(
                status = StrategyEvaluationStatus.INSUFFICIENT_FACTORS,
                missingFactorCodes = listOf(FactorCodes.MOMENTUM_60D),
            ),
            fixtures.SAMSUNG,
            date,
            emptyList(),
        )
        assertEquals("평가에 필요한 팩터 데이터가 부족합니다.", missing.notice)
        assertEquals(listOf("60일 모멘텀"), missing.missingFactors)
        assertNull(missing.score)
        assertNull(missing.decision)
        assertTrue(missing.factorRows.isEmpty())

        val invalid = StrategyPresenter.preview(
            StrategyEvaluationResult(status = StrategyEvaluationStatus.INVALID_STRATEGY, message = "enabled weight sum must be exactly 1.0"),
            fixtures.SAMSUNG,
            date,
            emptyList(),
        )
        assertEquals(StrategyPresenter.PREVIEW_INVALID_STRATEGY, invalid.notice)
        assertFalse(listOfNotNull(invalid.notice, invalid.ruleLine).any { "weight sum" in it })
    }

    @Test
    fun signalRulePreview_hasNoPlaceholderScore_andShowsTheTriggeredCondition() {
        val rules = fixtures.demoRules(20).map(StrategyPresenter::rule)
        val result = StrategyEvaluationResult(
            status = StrategyEvaluationStatus.SUCCESS,
            quantScoreStored = 0L,
            quantDecision = TradeDecision.BUY,
            decisionSource = DecisionSource.SIGNAL_RULE,
            triggeredRuleId = 101,
            reasonText = "DAILY_CHANGE_PCT -5.20% <= -5.00%, BUY rule triggered",
            metricCode = "DAILY_CHANGE_PCT",
            observedValue = "-5.20",
            thresholdValue = "-5.00",
        )

        val view = StrategyPresenter.preview(result, fixtures.SAMSUNG, date, rules)

        assertEquals("매수", view.decisionLabel)
        assertEquals("신호 규칙", view.sourceLabel)
        assertNull(view.score)
        assertTrue(view.factorRows.isEmpty())
        assertEquals("일간 등락률 -5.2% · 'DIP_BUY' 일간 등락률 -5% 이하 → 매수", view.ruleLine)
        assertEquals(StrategyPresenter.PREVIEW_RULE_NO_SCORE, view.notice)
        assertFalse(listOfNotNull(view.ruleLine, view.notice, view.score).any { "DAILY_CHANGE_PCT" in it })
    }

    @Test
    fun ruleForm_validation_isKorean_andEditKeepsTheRuleCode() {
        assertEquals(StrategyPresenter.RULE_NAME_REQUIRED, StrategyPresenter.ruleFormError(RuleForm(threshold = "3")))
        assertEquals(StrategyPresenter.RULE_THRESHOLD_INVALID, StrategyPresenter.ruleFormError(RuleForm(ruleCode = "A", threshold = "삼")))
        assertEquals(StrategyPresenter.RULE_PRIORITY_INVALID, StrategyPresenter.ruleFormError(RuleForm(ruleCode = "A", threshold = "3", priority = "-1")))
        assertNull(StrategyPresenter.ruleFormError(RuleForm(ruleCode = "A", threshold = "-5", priority = "0")))

        val form = StrategyPresenter.ruleForm(StrategyPresenter.rule(fixtures.demoRules(20)[1]))
        assertEquals(RuleForm("SPIKE_SELL", SignalOperator.GTE, "3", SignalAction.SELL, "20", editing = true), form)
    }

    private fun factorResult(decision: TradeDecision) = StrategyEvaluationResult(
        status = StrategyEvaluationStatus.SUCCESS,
        quantScoreStored = fixtures.score("62.4"),
        quantDecision = decision,
        factorDetails = listOf(detail()),
        decisionSource = DecisionSource.FACTOR_STRATEGY,
    )

    private fun detail(gateFailed: Boolean = false, min: Long? = null): StrategyFactorDetail {
        val score = fixtures.score("62.4")
        val weight = StrategyScoreMath.percentToWeightStored(BigDecimal("100"))
        return StrategyFactorDetail(
            factorId = 3,
            factorCode = FactorCodes.MOMENTUM_20D,
            calculationVersion = "v1",
            rawValue = "4.2",
            factorScoreStored = score,
            weightStored = weight,
            weightedScoreStored = StrategyScoreMath.weightedScoreStored(score, weight),
            minScoreStored = min,
            maxScoreStored = null,
            gateFailed = gateFailed,
        )
    }
}
