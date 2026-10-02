package com.mirunubi.bjstock.feature.strategy.template

import com.mirunubi.bjstock.feature.strategy.template.TemplatePreviewPresenter as P
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplatePreviewPresenterTest {
    private val forbiddenLabels = listOf("DRAFT", "ACTIVE", "READY", "RETIRED", "저장됨", "활성", "적용됨", "사용중", "작성중")

    @Test
    fun initialTemplates_areSamsungHbmAndDefense() {
        val templates = P.initialTemplates()

        assertEquals(listOf("삼성전자 종가돌파", "HBM 종가돌파 예시", "방산 종가돌파 예시"), templates.map { it.name })
        assertEquals(listOf(false, true, true), templates.map { it.example })
        assertTrue(templates.none { it.unsaved })
    }

    @Test
    fun templateA_isSamsungStockWithColleagueValues() {
        val a = P.initialTemplates().first()

        assertEquals(TemplateTargetType.STOCK, a.targetType)
        assertEquals(listOf("삼성전자"), a.stocks.map { it.name })
        assertEquals(5, a.maPeriodDays)
        assertEquals(5, a.belowMaConsecutiveDays)
        assertNull(a.volumeAverageLookbackDays)
        assertEquals(200, a.volumeThresholdPercent)
        assertEquals(0, BigDecimal("5").compareTo(a.takeProfitPercent))
        assertEquals(0, BigDecimal("5").compareTo(a.stopLossPercent))
        assertEquals(10, a.maxHoldingTradingDays)
        assertEquals(TemplateEntryMode.PRE_CLOSE_ENTRY, a.entryMode)
    }

    @Test
    fun examples_useThemeTargetsAndBaselineValues() {
        val (_, b, c) = P.initialTemplates()

        assertEquals(TemplateTargetType.THEME, b.targetType)
        assertEquals("HBM", b.theme?.name)
        assertEquals(TemplateTargetType.THEME, c.targetType)
        assertEquals("방산", c.theme?.name)
        listOf(b, c).forEach { t ->
            assertEquals(5, t.maPeriodDays)
            assertNull(t.volumeAverageLookbackDays)
            assertEquals(200, t.volumeThresholdPercent)
            assertTrue(P.BADGE_EXAMPLE in P.card(t).badges)
        }
    }

    @Test
    fun templateA_card_readsLikeTheSpecExample() {
        val card = P.card(P.initialTemplates().first())

        assertEquals("삼성전자 종가돌파", card.name)
        assertEquals("종목 · 삼성전자 005930 · 데이터 미연결", card.target)
        assertEquals(
            listOf("5일선 / 아래 5일", "거래량 200% · 평균 기간 미정", "익절 +5%", "손절 -5%", "최대 10일"),
            card.lines,
        )
        assertEquals(listOf(P.BADGE_PREVIEW), card.badges)
        assertTrue(card.complete)
    }

    @Test
    fun volumeLookback_isUnresolvedNotFive() {
        val template = P.initialTemplates().first()

        assertEquals("미정", P.lookback(template.volumeAverageLookbackDays))
        assertEquals("", P.formOf(template).volumeAverageLookbackDays)
        assertEquals("아직 확정되지 않은 항목입니다.", P.LOOKBACK_HINT)
        assertEquals("7일", P.lookback(7))
    }

    @Test
    fun takeProfitAndStopLoss_arePositiveMagnitudes() {
        assertEquals("+5%", P.takeProfit(BigDecimal("5")))
        assertEquals("-5%", P.stopLoss(BigDecimal("5")))
        assertEquals("-2.5%", P.stopLoss(BigDecimal("2.50")))
        val form = P.formOf(P.initialTemplates().first())
        assertEquals("5", form.takeProfitPercent)
        assertEquals("5", form.stopLossPercent)
    }

    @Test
    fun negativeStopLoss_isRejectedWithMagnitudeHint() {
        val form = P.formOf(P.initialTemplates().first()).copy(stopLossPercent = "-5")

        val error = P.validate(form).errors[TemplateField.STOP_LOSS]

        assertNotNull(error)
        assertTrue(error!!.contains("크기만"))
        assertNull(P.apply(P.initialTemplates().first(), form))
    }

    @Test
    fun stopLoss_mustStayBelowHundred() {
        val form = P.formOf(P.initialTemplates().first()).copy(stopLossPercent = "100")
        assertTrue(TemplateField.STOP_LOSS in P.validate(form).errors)
    }

    @Test
    fun newTemplate_hasDefaultsNoTargetAndIsUnsaved() {
        val t = P.newTemplate(9, "새 템플릿 1")

        assertTrue(t.unsaved)
        assertFalse(t.example)
        assertTrue(t.stocks.isEmpty())
        assertNull(t.theme)
        assertNull(t.volumeAverageLookbackDays)
        assertEquals(listOf(5, 5, 200, 10), listOf(t.maPeriodDays, t.belowMaConsecutiveDays, t.volumeThresholdPercent, t.maxHoldingTradingDays))
        assertEquals(TemplateEntryMode.PRE_CLOSE_ENTRY, t.entryMode)
        val card = P.card(t)
        assertEquals("종목 · 대상 미정", card.target)
        assertFalse(card.complete)
        assertEquals(listOf(P.BADGE_PREVIEW, P.BADGE_UNSAVED), card.badges)
    }

    @Test
    fun badges_useOnlyPreviewLifecycleWords() {
        val allowed = setOf(P.BADGE_PREVIEW, P.BADGE_EXAMPLE, P.BADGE_UNSAVED, P.BADGE_EDITING)
        val samples = P.initialTemplates() + P.newTemplate(9, "x")
        samples.forEach { t ->
            listOf(true, false).forEach { editing ->
                val badges = P.badges(t, editing)
                assertTrue(allowed.containsAll(badges))
                assertTrue(badges.none { b -> forbiddenLabels.any { b.contains(it) } })
            }
        }
        assertEquals(setOf("미리보기", "예시", "미저장", "편집 중"), allowed)
    }

    @Test
    fun wording_staysInsideTheDemoBoundary() {
        assertEquals("데모 — 저장되지 않으며 실제 전략이나 Run에 적용되지 않습니다.", P.DEMO_BANNER)
        assertEquals("미리보기 반영", P.APPLY_LABEL)
        assertEquals("저장형 템플릿과 실행 규칙은 아직 구현되지 않았습니다.", P.CREATE_VERSION_DISABLED)
        assertEquals("전략 템플릿 (미리보기)", P.SECTION_TITLE)
        listOf(P.APPLY_LABEL, P.SECTION_TITLE, P.BADGE_PREVIEW, P.BADGE_UNSAVED).forEach { label ->
            assertTrue(forbiddenLabels.none { label.contains(it) })
        }
    }

    @Test
    fun entryMode_isOnlyTheBOption() {
        assertEquals(listOf(TemplateEntryMode.PRE_CLOSE_ENTRY), TemplateEntryMode.entries)
        assertEquals("장마감 전 판단 후 종가진입 시도", TemplateEntryMode.PRE_CLOSE_ENTRY.label)
    }

    @Test
    fun targetTypes_areKoreanLabelled() {
        assertEquals(listOf("종목", "종목 묶음", "테마"), TemplateTargetType.entries.map { it.label })
    }

    @Test
    fun validation_requiresPositiveIntegersAndName() {
        val base = P.formOf(P.initialTemplates().first())
        val bad = base.copy(
            name = "  ",
            maPeriodDays = "0",
            belowMaConsecutiveDays = "",
            volumeThresholdPercent = "2.5",
            maxHoldingTradingDays = "-1",
            takeProfitPercent = "abc",
            volumeAverageLookbackDays = "0",
        )

        val errors = P.validate(bad).errors

        assertEquals(
            setOf(
                TemplateField.NAME,
                TemplateField.MA_PERIOD,
                TemplateField.BELOW_MA_DAYS,
                TemplateField.VOLUME_THRESHOLD,
                TemplateField.MAX_HOLDING,
                TemplateField.TAKE_PROFIT,
                TemplateField.VOLUME_LOOKBACK,
            ),
            errors.keys,
        )
        assertTrue(P.validate(base).errors.isEmpty())
    }

    @Test
    fun validation_lookbackIsOptional_targetGatesCompleteness() {
        val noTarget = P.formOf(P.newTemplate(9, "새 템플릿"))

        val v = P.validate(noTarget)

        assertTrue(v.canApply)
        assertTrue(v.targetMissing)
        assertFalse(v.complete)
        assertTrue(P.validate(P.formOf(P.initialTemplates().first())).complete)
    }

    @Test
    fun apply_copiesFormIntoTemplate_andMarksUnsaved() {
        val a = P.initialTemplates().first()
        val form = P.formOf(a).copy(volumeAverageLookbackDays = "7", takeProfitPercent = "6.5", stopLossPercent = "3")

        val updated = P.apply(a, form)!!

        assertEquals(7, updated.volumeAverageLookbackDays)
        assertEquals(0, BigDecimal("6.5").compareTo(updated.takeProfitPercent))
        assertEquals(0, BigDecimal("3").compareTo(updated.stopLossPercent))
        assertTrue(updated.unsaved)
        assertEquals("손절 -3%", P.card(updated).lines[3])
    }

    @Test
    fun dirty_ignoresEquivalentNumbers() {
        val a = P.initialTemplates().first()
        assertFalse(P.isDirty(a, P.formOf(a).copy(takeProfitPercent = "5.0")))
        assertTrue(P.isDirty(a, P.formOf(a).copy(takeProfitPercent = "6")))
    }

    @Test
    fun stockSelection_singleReplaces_setToggles() {
        val samsung = InstrumentOption(1, "005930", "삼성전자")
        val hynix = InstrumentOption(2, "000660", "SK하이닉스")
        val single = P.formOf(P.newTemplate(9, "x"))

        val one = P.withStock(P.withStock(single, samsung), hynix)
        assertEquals(listOf("000660"), one.stocks.map { it.symbol })

        val set = P.withTargetType(single, TemplateTargetType.STOCK_SET)
        val two = P.withStock(P.withStock(set, samsung), hynix)
        assertEquals(listOf("005930", "000660"), two.stocks.map { it.symbol })
        assertEquals(listOf("000660"), P.withStock(two, samsung).stocks.map { it.symbol })
        assertEquals("종목 묶음 · 삼성전자, SK하이닉스 (2개)", P.targetLabel(two.targetType, two.stocks, two.theme))

        val backToSingle = P.withTargetType(two, TemplateTargetType.STOCK)
        assertEquals(listOf("005930"), backToSingle.stocks.map { it.symbol })
    }

    @Test
    fun themeSelection_showsNameAndMemberCount() {
        val form = P.withTargetType(P.formOf(P.newTemplate(9, "x")), TemplateTargetType.THEME)
        assertTrue(P.validate(form).targetMissing)

        val chosen = P.withTheme(form, ThemeOption(themeId = 4, name = "반도체", memberCount = 12))

        assertFalse(P.validate(chosen).targetMissing)
        assertEquals("테마 · 반도체 (등록 종목 12개)", P.targetLabel(chosen.targetType, chosen.stocks, chosen.theme))
        assertFalse(P.hasUnlinkedTarget(chosen))
        assertTrue(P.hasUnlinkedTarget(P.formOf(P.initialTemplates()[1])))
    }

    @Test
    fun builtInCards_discloseUnlinkedTargets_withoutChangingBadgeSemantics() {
        val (a, b, c) = P.initialTemplates().map(P::card)

        assertEquals("종목 · 삼성전자 005930 · 데이터 미연결", a.target)
        assertEquals("테마 · HBM · 데이터 미연결", b.target)
        assertEquals("테마 · 방산 · 데이터 미연결", c.target)
        assertEquals(listOf(P.BADGE_PREVIEW), a.badges)
        assertEquals(listOf(P.BADGE_PREVIEW, P.BADGE_EXAMPLE), b.badges)
        assertEquals(listOf(P.BADGE_PREVIEW, P.BADGE_EXAMPLE), c.badges)
        listOf(a, b, c).forEach { card -> listOf("오류", "실패", "깨짐").forEach { assertFalse(card.target.contains(it)) } }
    }

    @Test
    fun linkedStock_hasNoMarker() {
        val a = P.initialTemplates().first()
        val linked = a.copy(stocks = listOf(TemplateStock(instrumentId = 1, symbol = "005930", name = "삼성전자")))

        assertEquals("종목 · 삼성전자 005930", P.card(linked).target)
    }

    @Test
    fun linkedTheme_hasNoMarker() {
        val b = P.initialTemplates()[1]
        val linked = b.copy(theme = TemplateTheme(themeId = 7, name = "HBM", memberCount = 4))

        assertEquals("테마 · HBM (등록 종목 4개)", P.card(linked).target)
    }

    @Test
    fun marker_followsLinkageState_notTemplateName() {
        val renamedUnlinked = P.initialTemplates().first().copy(name = "아무 이름")
        val sameNameLinked = P.initialTemplates()[1].copy(theme = TemplateTheme(themeId = 7, name = "HBM", memberCount = null))
        val custom = P.newTemplate(9, "새 템플릿").copy(
            targetType = TemplateTargetType.THEME,
            theme = TemplateTheme(themeId = null, name = "임의 테마", memberCount = null),
        )

        assertTrue(P.card(renamedUnlinked).target.endsWith(P.UNLINKED_MARKER))
        assertFalse(P.card(sameNameLinked).target.contains(P.UNLINKED_MARKER))
        assertTrue(P.card(custom).target.endsWith(P.UNLINKED_MARKER))
        assertFalse(P.card(P.newTemplate(10, "삼성전자 종가돌파")).target.contains(P.UNLINKED_MARKER))
    }

    @Test
    fun stockSet_isUnlinkedWhileAnySelectedStockLacksAnId() {
        val set = P.newTemplate(9, "묶음").copy(
            targetType = TemplateTargetType.STOCK_SET,
            stocks = listOf(TemplateStock(1, "005930", "삼성전자"), TemplateStock(null, "000660", "SK하이닉스")),
        )

        assertTrue(P.card(set).target.endsWith(P.UNLINKED_MARKER))
        val linked = set.copy(stocks = set.stocks.map { it.copy(instrumentId = it.instrumentId ?: 2) })
        assertFalse(P.card(linked).target.contains(P.UNLINKED_MARKER))
    }

    @Test
    fun duplicateName_appendsCopySuffix() {
        assertEquals("삼성전자 종가돌파 복사본", P.duplicateName("삼성전자 종가돌파"))
    }
}
