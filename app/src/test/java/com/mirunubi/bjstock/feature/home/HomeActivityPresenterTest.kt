package com.mirunubi.bjstock.feature.home

import com.mirunubi.bjstock.core.analytics.PerformanceMath
import com.mirunubi.bjstock.core.database.entity.ExecutionEntity
import com.mirunubi.bjstock.core.database.entity.OrderEntity
import com.mirunubi.bjstock.core.database.entity.TradeAuditLogEntity
import com.mirunubi.bjstock.core.model.ForwardOperationStatus
import com.mirunubi.bjstock.core.model.OperationalEventType
import com.mirunubi.bjstock.core.model.OrderSide
import com.mirunubi.bjstock.core.model.OrderStatus
import com.mirunubi.bjstock.core.model.OrderType
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.model.TradeAuditEventType
import com.mirunubi.bjstock.core.model.TradeDecision
import com.mirunubi.bjstock.feature.admin.AdminAuditData
import com.mirunubi.bjstock.feature.admin.AdminErrorData
import com.mirunubi.bjstock.feature.admin.AdminFixtures
import com.mirunubi.bjstock.feature.admin.SectionState
import com.mirunubi.bjstock.feature.home.HomeActivityPresenter as P
import com.mirunubi.bjstock.feature.paper.InstrumentLabel
import com.mirunubi.bjstock.feature.paper.PaperTradingPresenter
import com.mirunubi.bjstock.ui.text.KoreanLabels
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

object HomeActivityFixtures {
    val SAMSUNG = InstrumentLabel(11, "005930", "삼성전자")
    val HYNIX = InstrumentLabel(12, "000660", "SK하이닉스")
    val DAY: LocalDate = LocalDate.of(2026, 9, 29)
    val AT: Instant = Instant.parse("2026-09-29T22:34:00Z")

    fun ref(id: Long, status: RunStatus, name: String = "Run $id") =
        HomeRunRef(runId = id, runName = name, strategyName = "Momentum", versionLabel = "V2", status = status, initialCash = 100_000_000)

    fun signalLog(
        id: Long,
        type: TradeAuditEventType,
        evaluationId: Long? = 70,
        instrumentId: Long? = SAMSUNG.instrumentId,
        runId: Long = 3,
        date: LocalDate = DAY,
        reasonText: String? = null,
    ) = TradeAuditLogEntity(
        id = id,
        strategyRunId = runId,
        instrumentId = instrumentId,
        evaluationId = evaluationId,
        marketDate = date,
        eventType = type,
        reasonText = reasonText,
        eventKey = "k$id",
        createdAt = AT.minusSeconds(id),
    )

    fun order(
        id: Long,
        side: OrderSide = OrderSide.BUY,
        status: OrderStatus = OrderStatus.VIRTUAL_FILLED,
        instrumentId: Long = SAMSUNG.instrumentId,
        createdAt: Instant = AT.minusSeconds(60 * id),
    ) = OrderEntity(
        id = id,
        clientOrderId = "c$id",
        strategyRunId = 3,
        instrumentId = instrumentId,
        side = side,
        orderType = OrderType.MARKET,
        quantity = 37,
        status = status,
        createdAt = createdAt,
    )

    fun execution(orderId: Long, price: Long = 271_000, at: Instant = AT.plusSeconds(3_600)) =
        ExecutionEntity(id = orderId * 10, orderId = orderId, executionPrice = price, quantity = 37, executedAt = at, executionKey = "e$orderId")
}

class HomeActivityPresenterTest {
    private val f = HomeActivityFixtures

    private fun loaded(state: SectionState<HomeSummary>): HomeSummary = (state as SectionState.Loaded).value

    // region sections and navigation

    @Test
    fun sections_areSummaryCardsWithSimpleDetailLinks() {
        assertEquals(
            listOf("실행 중 전략", "최근 처리일 신호", "최근 주문 · 체결", "최근 오류", "최근 Audit"),
            HomeActivitySection.entries.map { it.title },
        )
        assertEquals(HomeDestination.PAPER_TRADING, HomeActivitySection.RUNS.link?.destination)
        assertEquals(HomeDestination.PAPER_TRADING, HomeActivitySection.SIGNALS.link?.destination)
        assertEquals(HomeDestination.PAPER_TRADING, HomeActivitySection.TRADES.link?.destination)
        assertEquals(HomeDestination.ADMIN, HomeActivitySection.ERRORS.link?.destination)
        assertEquals(HomeDestination.ADMIN, HomeActivitySection.AUDIT.link?.destination)
        assertEquals("운영 · 감사에서 보기", P.AUTO_LINK_LABEL)
    }

    @Test
    fun noSecurityHealthCardsAreInvented() {
        val words = listOf("Safe Mode", "무결성", "digest", "checkpoint", "anchor", "provenance", "서명")
        HomeActivitySection.entries.forEach { section -> words.forEach { assertFalse(section.title.contains(it, ignoreCase = true)) } }
    }

    // endregion

    // region 실행 중 전략

    @Test
    fun runs_emptyState() {
        assertEquals(SectionState.Empty("실행 중인 전략이 없습니다."), P.runs(emptyList()))
    }

    @Test
    fun runs_showIdLabelStatusAndInitialCapital() {
        val summary = loaded(P.runs(listOf(f.ref(3, RunStatus.RUNNING, "go hbm"), f.ref(4, RunStatus.READY))))

        assertEquals("실행 준비 1개 · 운영 중 1개", summary.headline)
        assertEquals("Run #3 · go hbm", summary.rows[0].headline)
        assertEquals(
            "Momentum V2 · ${KoreanLabels.runStatus(RunStatus.RUNNING)} · 초기 자본 ${PerformanceMath.formatWon(100_000_000)}",
            summary.rows[0].detail,
        )
        assertEquals(KoreanLabels.runStatus(RunStatus.READY), summary.rows[1].detail!!.split(" · ")[1])
        assertNull(summary.moreNote)
    }

    @Test
    fun runs_areCappedAtThreeRows() {
        val summary = loaded(P.runs((1L..5L).map { f.ref(it, RunStatus.RUNNING) }))
        assertEquals(3, summary.rows.size)
        assertEquals("외 2개", summary.moreNote)
    }

    // endregion

    // region 최근 처리일 신호

    @Test
    fun signals_useProcessedDayWording_notToday() {
        val data = HomeSignalData(
            marketDate = f.DAY,
            logs = listOf(
                f.signalLog(1, TradeAuditEventType.EVALUATION_DECIDED),
                f.signalLog(2, TradeAuditEventType.RULE_TRIGGERED, reasonText = "종가가 5일선 위"),
            ),
            decisions = mapOf(70L to TradeDecision.BUY),
            instruments = mapOf(f.SAMSUNG.instrumentId to f.SAMSUNG),
            runs = mapOf(3L to f.ref(3, RunStatus.RUNNING)),
        )

        val summary = loaded(P.signals(data))

        assertEquals("처리일 2026.09.29", summary.headline)
        assertEquals("삼성전자 005930 · 매수(BUY) · 신호 규칙 발생", summary.rows.single().headline)
        assertEquals("Run #3 · Momentum V2 · 종가가 5일선 위", summary.rows.single().detail)
        assertTrue(summary.note!!.contains("직전 거래일"))
        val texts = listOf(HomeActivitySection.SIGNALS.title, summary.headline!!, summary.note!!) + summary.rows.map { it.headline }
        texts.forEach { assertFalse(it.contains("오늘")) }
    }

    @Test
    fun signals_groupByEvaluation_andCapRows() {
        val logs = (1L..5L).map { f.signalLog(it, TradeAuditEventType.EVALUATION_DECIDED, evaluationId = 100 + it) }
        val summary = loaded(
            P.signals(HomeSignalData(f.DAY, logs, emptyMap(), mapOf(f.SAMSUNG.instrumentId to f.SAMSUNG), emptyMap())),
        )

        assertEquals(3, summary.rows.size)
        assertEquals("외 2건", summary.moreNote)
        assertEquals("삼성전자 005930 · 전략 판단", summary.rows.first().headline)
        assertEquals("Run #3", summary.rows.first().detail)
    }

    @Test
    fun signals_longReasonIsShortened() {
        val long = "가".repeat(80)
        val data = HomeSignalData(
            f.DAY,
            listOf(f.signalLog(1, TradeAuditEventType.EVALUATION_DECIDED, reasonText = long)),
            emptyMap(),
            emptyMap(),
            emptyMap(),
        )
        val detail = loaded(P.signals(data)).rows.single().detail!!
        assertTrue(detail.endsWith("…"))
        assertTrue(detail.length < 60)
    }

    @Test
    fun signals_emptyState() {
        assertEquals(SectionState.Empty("최근 처리일 신호가 없습니다."), P.signals(HomeSignalData(null, emptyList(), emptyMap(), emptyMap(), emptyMap())))
    }

    // endregion

    // region 최근 주문 · 체결

    @Test
    fun trades_showSideStockQuantityStatusFillPriceAndTime() {
        val filled = f.order(1)
        val pending = f.order(2, side = OrderSide.SELL, status = OrderStatus.PENDING_EXECUTION, instrumentId = f.HYNIX.instrumentId)
        val data = HomeTradeData(
            orders = listOf(filled, pending),
            totalOrders = 2,
            executions = mapOf(1L to f.execution(1)),
            instruments = mapOf(f.SAMSUNG.instrumentId to f.SAMSUNG, f.HYNIX.instrumentId to f.HYNIX),
        )

        val rows = loaded(P.trades(data)).rows

        assertEquals("매수(BUY) · 삼성전자 005930 · 37주", rows[0].headline)
        assertEquals(
            "${PaperTradingPresenter.orderStatus(OrderStatus.VIRTUAL_FILLED)} · 체결가 ${PerformanceMath.formatWon(271_000)} · " +
                KoreanLabels.dateTime(f.AT.plusSeconds(3_600)),
            rows[0].detail,
        )
        assertEquals("매도(SELL) · SK하이닉스 000660 · 37주", rows[1].headline)
        assertEquals("체결 대기 · ${KoreanLabels.dateTime(pending.createdAt)}", rows[1].detail)
    }

    @Test
    fun trades_moreNoteAndEmptyState() {
        val data = HomeTradeData((1L..3L).map { f.order(it) }, totalOrders = 5, executions = emptyMap(), instruments = emptyMap())
        val summary = loaded(P.trades(data))
        assertEquals("외 2건", summary.moreNote)
        assertEquals("매수(BUY) · 종목 #11 · 37주", summary.rows.first().headline)
        assertEquals(SectionState.Empty("최근 주문·체결이 없습니다."), P.trades(HomeTradeData(emptyList(), 0, emptyMap(), emptyMap())))
    }

    // endregion

    // region 최근 오류 / 최근 Audit

    @Test
    fun errors_reuseAdminRows_withBoundedWording() {
        val data = AdminErrorData(
            apiErrors = listOf(AdminFixtures.apiError(1)),
            operations = listOf(AdminFixtures.operation(2, ForwardOperationStatus.FAILED, finalCode = "NETWORK_FAILURE")),
            failedCycles = emptyList(),
            events = emptyList(),
        )

        val summary = loaded(P.errors(data))

        assertEquals("조회 범위 내 2건", summary.headline)
        assertEquals(2, summary.rows.size)
        assertTrue(summary.rows.all { it.problem })
        assertEquals("조회 범위 내 최근 기록 기준", summary.note)
        assertTrue(summary.rows.none { it.headline.contains("timed out") })
    }

    @Test
    fun errors_emptyStateIsBounded() {
        val empty = P.errors(AdminErrorData(emptyList(), emptyList(), emptyList(), emptyList()))
        assertEquals(SectionState.Empty("조회 범위 내 최근 오류가 없습니다."), empty)
    }

    @Test
    fun audit_showsMeaningfulRecentEntries_andSkipsLifecycleNoise() {
        val data = AdminAuditData(
            events = listOf(
                AdminFixtures.event(1, OperationalEventType.OPERATION_STARTED),
                AdminFixtures.event(2, OperationalEventType.MARKET_SYNC_RESULT, result = "FAILED"),
            ),
            audits = listOf(AdminFixtures.audit(3, TradeAuditEventType.ORDER_CREATED, runId = 3)),
            operations = emptyList(),
            apiErrors = emptyList(),
        )

        val summary = loaded(P.audit(data))

        assertEquals(2, summary.rows.size)
        assertTrue(summary.rows.none { it.headline.contains("실행 시작") })
        assertTrue(summary.rows[0].headline.startsWith("Provider"))
        assertTrue(summary.rows[0].problem)
        assertTrue(summary.rows[1].headline.startsWith("주문 · 체결 · 모의주문 생성"))
        assertTrue(summary.rows[1].detail!!.endsWith("Run #3"))
        assertEquals("최근 기록 기준", summary.note)
    }

    @Test
    fun audit_emptyState() {
        val onlyLifecycle = AdminAuditData(listOf(AdminFixtures.event(1, OperationalEventType.CYCLE_STARTED)), emptyList(), emptyList(), emptyList())
        assertEquals(SectionState.Empty("최근 Audit 기록이 없습니다."), P.audit(onlyLifecycle))
        assertEquals(SectionState.Empty("최근 Audit 기록이 없습니다."), P.audit(AdminAuditData(emptyList(), emptyList(), emptyList(), emptyList())))
    }

    @Test
    fun windowNotes_neverClaimFullHistory() {
        listOf(P.SIGNALS_NOTE, P.ERRORS_NOTE, P.AUDIT_NOTE).forEach { note ->
            assertTrue(note.contains("최근") || note.contains("조회 범위"))
            listOf("전체", "모든", "없음 보장").forEach { assertFalse(note.contains(it)) }
        }
    }

    // endregion
}
