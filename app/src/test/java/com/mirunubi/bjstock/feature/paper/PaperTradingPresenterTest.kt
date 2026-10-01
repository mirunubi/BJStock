package com.mirunubi.bjstock.feature.paper

import com.mirunubi.bjstock.core.analytics.PerformanceStatus
import com.mirunubi.bjstock.core.forward.ForwardErrorCode
import com.mirunubi.bjstock.core.forward.ForwardOrchestratorResult
import com.mirunubi.bjstock.core.model.ForwardCycleStatus
import com.mirunubi.bjstock.core.model.ForwardOperationKind
import com.mirunubi.bjstock.core.model.ForwardOperationStatus
import com.mirunubi.bjstock.core.model.ForwardOperationTrigger
import com.mirunubi.bjstock.core.model.OrderSide
import com.mirunubi.bjstock.core.model.OrderStatus
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.model.TradeAuditEventType
import com.mirunubi.bjstock.core.strategy.StrategyErrorKind
import com.mirunubi.bjstock.core.strategy.StrategyVersionException
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperTradingPresenterTest {
    private val presenter = PaperTradingPresenter

    @Test
    fun runStatus_mapsEveryStatusToTheProductLabel() {
        assertEquals(
            listOf("설정중", "실행 준비", "운영 중", "일시정지", "완료", "취소"),
            listOf(RunStatus.DRAFT, RunStatus.READY, RunStatus.RUNNING, RunStatus.PAUSED, RunStatus.COMPLETED, RunStatus.CANCELLED)
                .map(presenter::runStatus),
        )
        assertEquals(RunStatus.entries.size, RunStatus.entries.map(presenter::runStatus).toSet().size)
    }

    @Test
    fun runRows_keepEveryRun_sortedByStatusThenNewest_withNameAndStrategyAsPrimaryText() {
        val runs = listOf(
            PaperFixtures.runData(1, RunStatus.COMPLETED),
            PaperFixtures.runData(2, RunStatus.DRAFT, name = "go hbm"),
            PaperFixtures.runData(3, RunStatus.RUNNING, totalAsset = 100_147_524, name = "모멘텀 운영"),
            PaperFixtures.runData(4, RunStatus.CANCELLED),
            PaperFixtures.runData(5, RunStatus.READY),
            PaperFixtures.runData(6, RunStatus.PAUSED),
            PaperFixtures.runData(7, RunStatus.RUNNING),
        )

        val rows = presenter.runRows(runs)

        assertEquals(listOf(7L, 3L, 5L, 6L, 2L, 1L, 4L), rows.map { it.runId })
        val running = rows.single { it.runId == 3L }
        assertEquals("모멘텀 운영", running.name)
        assertEquals("기본 모멘텀 전략 V2", running.strategyLabel)
        assertEquals("운영 중", running.badge.label)
        assertEquals("2026.09.18부터", running.startLabel)
        assertEquals("총 모의자산 100,147,524원 · +0.15%", running.assetLabel)
        assertEquals("초기자금 100,000,000원 · 평가 전", rows.single { it.runId == 2L }.assetLabel)
        assertFalse(rows.any { it.name == it.runId.toString() })
        assertEquals(7L, presenter.defaultSelection(runs))
    }

    @Test
    fun header_showsStrategyVersionStatusStartEndAndInitialCapital() {
        val data = PaperFixtures.runData(3, RunStatus.RUNNING, name = "모멘텀 운영")
        val header = presenter.header(data.copy(run = data.run.copy(endDate = LocalDate.of(2026, 12, 31))))

        assertEquals("모멘텀 운영", header.name)
        assertEquals("기본 모멘텀 전략", header.strategyName)
        assertEquals("V2", header.versionLabel)
        assertEquals("운영 중", header.badge.label)
        assertEquals("2026.09.18부터 · 2026.12.31까지", header.period)
        assertEquals("100,000,000원", header.initialCash)
        assertEquals("2026.09.18부터", presenter.header(data).period)
    }

    @Test
    fun account_withoutSnapshot_showsInitialCapital_neverZero() {
        val account = presenter.account(PaperFixtures.runData(5, RunStatus.READY))

        assertEquals(LabeledValue("총 모의자산", "100,000,000원"), account.rows.first())
        assertEquals("아직 평가 기록이 없어 초기자금을 표시합니다.", account.note)
        assertFalse(account.rows.any { it.value == "0원" })
        assertNull(account.warning)
    }

    @Test
    fun account_withSnapshot_usesTheAnalyticsSummaryFigures() {
        val account = presenter.account(PaperFixtures.runData(3, RunStatus.RUNNING, totalAsset = 100_147_524))

        assertEquals(
            listOf(
                LabeledValue("총 모의자산", "100,147,524원"),
                LabeledValue("현금", "90,157,524원"),
                LabeledValue("보유주식 평가액", "9,990,000원"),
                LabeledValue("누적 손익", "+147,524원"),
                LabeledValue("누적 수익률", "+0.15%"),
            ),
            account.rows,
        )
        assertNull(account.note)
    }

    @Test
    fun account_dataError_warns_andFallsBackToInitialCapital() {
        val base = PaperFixtures.runData(3, RunStatus.RUNNING)
        val account = presenter.account(base.copy(summary = PaperFixtures.summary(base.run, status = PerformanceStatus.DATA_ERROR)))

        assertEquals(PaperTradingPresenter.DATA_ERROR_WARNING, account.warning)
        assertEquals("100,000,000원", account.rows.first().value)
    }

    @Test
    fun holdings_showNameCodeQuantityPrices_andPriceBasisPnl() {
        val view = presenter.detail(PaperFixtures.detail(PaperFixtures.runData(3, RunStatus.RUNNING)))
        val holding = view.holdings.single()

        assertEquals("삼성전자", holding.name)
        assertEquals("005930", holding.symbol)
        assertEquals("37주", holding.quantity)
        assertEquals("266,000원", holding.averagePrice)
        assertEquals("270,000원", holding.latestClose)
        assertEquals("9,990,000원", holding.marketValue)
        assertEquals("+148,000원", holding.pricePnl)
        assertTrue(PaperTradingPresenter.PRICE_PNL_NOTE.contains("수수료"))
    }

    @Test
    fun executions_areVirtualFills_withSideQuantityPriceCommission_andTaxOnlyWhenPresent() {
        val data = PaperFixtures.detail(PaperFixtures.runData(3, RunStatus.RUNNING))
            .copy(executions = listOf(PaperFixtures.BUY_EXECUTION, PaperFixtures.SELL_EXECUTION))
        val (buy, sell) = presenter.detail(data).executions

        assertEquals("09.29 · 매수 · 가상 체결", buy.headline)
        assertEquals("삼성전자 37주 × 266,000원", buy.detail)
        assertEquals("수수료 1,476원", buy.costs)
        assertEquals(OrderSide.BUY, buy.side)
        assertEquals("09.30 · 매도 · 가상 체결", sell.headline)
        assertEquals("수수료 1,498원 · 매도세 19,980원", sell.costs)
    }

    @Test
    fun orders_mapSideAndEveryStatusToKorean() {
        assertEquals(
            listOf("생성", "체결 대기", "가상 체결", "취소", "거절"),
            OrderStatus.entries.map(presenter::orderStatus),
        )
        assertEquals("매수", presenter.side(OrderSide.BUY))
        assertEquals("매도", presenter.side(OrderSide.SELL))
        val view = presenter.detail(PaperFixtures.detail(PaperFixtures.runData(3, RunStatus.RUNNING)))
        assertEquals("09.26 · 매수 · 가상 체결", view.orders.single().headline)
        assertEquals("삼성전자 37주", view.orders.single().detail)
    }

    @Test
    fun policy_rendersTheStoredSnapshotValues() {
        val rows = presenter.policy(PaperFixtures.POLICY).associate { it.label to it.value }

        assertEquals("10%", rows["1회 매수 비중"])
        assertEquals("0.015%", rows["수수료 가정"])
        assertEquals("0.2%", rows["매도세 가정"])
        assertEquals("없음 (0bp)", rows["슬리피지"])
        assertEquals("다음 거래일 시가", rows["체결가격 정책"])
        assertEquals("허용 안 함", rows["추가매수"])
        assertEquals("전량 매도", rows["매도방식"])
        assertEquals("허용 안 함", rows["공매도"])

        val custom = presenter.policy(PaperFixtures.POLICY.copy(buyAllocationRate = BigDecimal("0.25"), slippageBps = 5))
            .associate { it.label to it.value }
        assertEquals("25%", custom["1회 매수 비중"])
        assertEquals("5bp", custom["슬리피지"])
    }

    @Test
    fun autoSlot_future_showsNextAutoRunAsEarliestTime() {
        val (line, pastDue) = presenter.slot(PaperFixtures.autoStatus(), PaperFixtures.NOW)

        assertEquals("다음 자동 실행 10월 2일 오전 7:00 이후", line)
        assertFalse(pastDue)
    }

    @Test
    fun autoSlot_pastDueEnqueued_showsWaiting_neverAFutureTime() {
        val status = PaperFixtures.autoStatus(scheduledAt = PaperFixtures.kst(2026, 10, 1, 7, 0), workState = "ENQUEUED")
        val (line, pastDue) = presenter.slot(status, PaperFixtures.NOW)

        assertEquals("10월 1일 오전 7:00 예약 작업 · 실행/재시도 대기 중", line)
        assertTrue(pastDue)
        assertFalse(line.startsWith("다음 자동 실행"))
    }

    @Test
    fun autoSlot_offRunningAndMissing() {
        assertEquals("자동운영이 꺼져 있습니다.", presenter.slot(PaperFixtures.autoStatus(enabled = false), PaperFixtures.NOW).first)
        assertEquals("예약된 자동 실행 작업이 없습니다.", presenter.slot(PaperFixtures.autoStatus(scheduledAt = null), PaperFixtures.NOW).first)
        val running = PaperFixtures.autoStatus(scheduledAt = PaperFixtures.kst(2026, 10, 1, 7, 0), workState = "RUNNING")
        assertEquals("10월 1일 오전 7:00 예약 작업 · 실행 중", presenter.slot(running, PaperFixtures.NOW).first)
        val view = presenter.automation(PaperAutomationData(PaperFixtures.autoStatus(failure = "SCHEDULE_UPDATE_FAILED:X"), emptyList(), PaperFixtures.NOW))
        assertEquals("켜짐", view.stateLabel)
        assertEquals(PaperTradingPresenter.AUTO_SCHEDULE_FAILURE, view.warning)
    }

    @Test
    fun operations_showTriggerStatusTimesAndSafeCodes_withoutKeysOrWorkIds() {
        val operations = (1L..3L).map { PaperFixtures.blockedOperation(it, it.toInt()) }
        val views = presenter.automation(PaperAutomationData(PaperFixtures.autoStatus(), operations, PaperFixtures.NOW)).operations

        assertEquals(3, views.size)
        views.forEach { view ->
            assertEquals("자동 실행", view.title)
            assertEquals("실행 차단", view.badge.label)
            assertEquals("기준일 2026.09.30", view.throughDate)
            assertEquals("네트워크에 연결할 수 없어 실행하지 못했습니다.", view.message)
            assertEquals("NETWORK_FAILURE", view.code)
            val text = listOfNotNull(view.title, view.badge.label, view.timing, view.throughDate, view.message, view.code, view.safeMessage)
            assertFalse(text.any { it.contains(PaperFixtures.WORK_ID) || it.contains("attempt:") || it.contains("auto:2026") })
        }
        assertEquals("시작 10월 1일 오후 12:51 · 종료 10월 1일 오후 12:51", views.first().timing)
    }

    @Test
    fun operationLabels_coverTriggerKindAndEveryStatus() {
        assertEquals("자동 실행", presenter.operationTitle(ForwardOperationTrigger.WORKER, ForwardOperationKind.FORWARD_RUN))
        assertEquals("수동 실행", presenter.operationTitle(ForwardOperationTrigger.MANUAL, ForwardOperationKind.FORWARD_RUN))
        assertEquals("실패 재시도", presenter.operationTitle(ForwardOperationTrigger.MANUAL, ForwardOperationKind.RETRY_FAILED_CYCLE))
        assertEquals(
            listOf("실행 중", "완료", "처리할 항목 없음", "일부 처리", "실행 차단", "실패"),
            ForwardOperationStatus.entries.map(presenter::operationStatus),
        )
    }

    @Test
    fun retry_onlyForARealFailedCycle_andNeverForNonRetryable() {
        val date = LocalDate.of(2026, 9, 29)
        val retryable = presenter.retry(PaperFixtures.cycle(9, date, ForwardCycleStatus.FAILED, retryable = true, errorCode = "NETWORK_FAILURE"))
        assertEquals(RetryView.Retryable(9, date, "실패한 날짜 다시 처리 (2026.09.29)", "네트워크에 연결할 수 없어 실행하지 못했습니다."), retryable)

        val blocked = presenter.retry(PaperFixtures.cycle(9, date, ForwardCycleStatus.FAILED, retryable = false, errorCode = "DATA_INTEGRITY_ERROR"))
        assertTrue(blocked is RetryView.NotRetryable)
        assertEquals("자동 복구할 수 없는 오류입니다.", (blocked as RetryView.NotRetryable).message)

        assertNull(presenter.retry(PaperFixtures.cycle(9, date, ForwardCycleStatus.COMPLETE)))
        val view = presenter.detail(PaperFixtures.detail(PaperFixtures.runData(3, RunStatus.RUNNING)))
        assertNull(view.retry)
    }

    @Test
    fun cycles_mapStatus_andNeverShowTheRawErrorMessage() {
        assertEquals(listOf("대기", "처리 중", "완료", "실패"), ForwardCycleStatus.entries.map(presenter::cycleStatus))
        val view = presenter.cycle(PaperFixtures.cycle(9, LocalDate.of(2026, 9, 29), ForwardCycleStatus.FAILED, errorCode = "NETWORK_FAILURE"))
        assertEquals("09.29 · 실패", view.headline)
        assertFalse(view.detail.orEmpty().contains("raw provider"))
    }

    @Test
    fun auditTimeline_mapsEveryEventTypeToKorean() {
        assertEquals(
            listOf("신호 규칙 발생", "전략 판단", "모의주문 생성", "주문 건너뜀", "주문 거절", "주문 취소", "가상 체결"),
            TradeAuditEventType.entries.map(presenter::auditEvent),
        )
        val view = presenter.timeline(PaperFixtures.audit(TradeAuditEventType.EXECUTION_FILLED, "filled at open"), mapOf(PaperFixtures.SAMSUNG_ID to PaperFixtures.SAMSUNG))
        assertEquals("09.26 · 가상 체결 · 삼성전자", view.headline)
        assertEquals("filled at open", view.reason)
    }

    @Test
    fun universe_draftIsEditable_readyIsReadOnlyWithTheLockNote() {
        val draft = presenter.detail(PaperFixtures.detail(PaperFixtures.runData(2, RunStatus.DRAFT)))
        assertTrue(draft.universe.editable)
        assertTrue(draft.canMarkReady)
        assertNull(draft.universe.lockedNote)
        assertEquals(listOf(ThemeOption(5, "반도체")), draft.universe.themes)
        assertEquals("삼성전자 005930", draft.universe.items.single().label)

        val ready = presenter.detail(PaperFixtures.detail(PaperFixtures.runData(5, RunStatus.READY)))
        assertFalse(ready.universe.editable)
        assertFalse(ready.canMarkReady)
        assertEquals("실행 준비가 완료된 모의투자의 투자 대상은 변경할 수 없습니다.", ready.universe.lockedNote)
    }

    @Test
    fun readyConfirmation_listsNameStrategyStartCashAndUniverseCount() {
        val view = presenter.detail(PaperFixtures.detail(PaperFixtures.runData(2, RunStatus.DRAFT, name = "go hbm")))

        assertEquals(
            listOf(
                LabeledValue("모의투자 이름", "go hbm"),
                LabeledValue("전략", "기본 모멘텀 전략 V2"),
                LabeledValue("시작일", "2026.09.18"),
                LabeledValue("초기자금", "100,000,000원"),
                LabeledValue("투자 대상", "1종목"),
            ),
            view.readyConfirmation.rows,
        )
        assertTrue(PaperTradingPresenter.READY_BODY.contains("투자 대상과 거래 정책이 고정"))
    }

    @Test
    fun readyFailure_mapsTheActualDomainCategories_andNeverEchoesTheMessage() {
        fun invalid(message: String) = StrategyVersionException(StrategyErrorKind.INVALID_STATE, message)

        assertEquals("투자 대상 종목을 하나 이상 추가해 주세요.", presenter.readyFailure(invalid(ForwardErrorCode.EMPTY_UNIVERSE.name)))
        assertEquals("KIS 연결 설정을 확인해 주세요.", presenter.readyFailure(invalid(ForwardErrorCode.AUTH_REQUIRED.name)))
        assertEquals("전략 계산에 필요한 과거 시세 데이터가 부족합니다.", presenter.readyFailure(invalid(ForwardErrorCode.INSUFFICIENT_WARMUP_DATA.name)))
        assertEquals(
            "사용 중인 전략 버전이 필요합니다.",
            presenter.readyFailure(StrategyVersionException(StrategyErrorKind.VERSION_NOT_ACTIVE, "strategy version must be ACTIVE")),
        )
        val other = presenter.readyFailure(invalid("factor definition missing for weight 9"))
        assertFalse(other.contains("factor"))
        assertFalse(presenter.readyFailure(IllegalStateException("SELECT * FROM secret")).contains("SELECT"))
    }

    @Test
    fun codeMessages_areKorean_andUnknownCodesAreNotEchoed() {
        assertEquals("다른 자동/수동 작업이 이미 실행 중입니다.", presenter.codeMessage("ALREADY_RUNNING"))
        assertEquals("앞선 전략 실행이 중단되어 이 전략은 실행하지 않았습니다.", presenter.codeMessage("PRIOR_RUN_BLOCKED"))
        assertEquals("네트워크에 연결할 수 없어 실행하지 못했습니다.", presenter.codeMessage("NETWORK_FAILURE"))
        assertEquals("KIS 연결 설정을 확인해 주세요.", presenter.codeMessage("CREDENTIAL_MISSING"))
        assertNull(presenter.codeMessage("java.io.IOException: /data/user/0/secret"))
    }

    @Test
    fun outcome_reportsStatusAndProcessedDays_orTheBlockedReason() {
        assertEquals(PaperNotice("실행 결과: 완료 · 1개 거래일 처리", false), presenter.outcome(PaperFixtures.outcome()))
        val blocked = presenter.outcome(
            PaperFixtures.outcome(
                status = ForwardOperationStatus.BLOCKED,
                finalCode = "NETWORK_FAILURE",
                display = ForwardOrchestratorResult.Blocked(null, "NETWORK_FAILURE", "raw", retryable = true),
            ),
        )
        assertEquals(PaperNotice("실행 결과: 실행 차단 · 네트워크에 연결할 수 없어 실행하지 못했습니다.", true), blocked)
    }

    @Test
    fun visibleLabels_areKorean_withoutEnglishPrimaryTerms() {
        val banned = listOf(
            "Forward Test", "Strategy Run", "Mark Ready", "Universe", "Run Now", "Retry Failed Cycle",
            "Open Positions", "Recent Executions",
        )
        val view = presenter.detail(PaperFixtures.detail(PaperFixtures.runData(2, RunStatus.DRAFT)))
        val labels = listOf(
            PaperTradingPresenter.AUTO_ON_TITLE, PaperTradingPresenter.AUTO_ON_BODY, PaperTradingPresenter.AUTO_OFF_TITLE,
            PaperTradingPresenter.AUTO_OFF_BODY, PaperTradingPresenter.RUN_NOW_TITLE, PaperTradingPresenter.RUN_NOW_BODY,
            PaperTradingPresenter.READY_TITLE, PaperTradingPresenter.READY_BODY, PaperTradingPresenter.RETRY_TITLE,
            PaperTradingPresenter.UNIVERSE_LOCKED, PaperTradingPresenter.NOT_RETRYABLE,
        ) + view.readyConfirmation.rows.map { it.label } + view.header.badge.label
        labels.forEach { label -> banned.forEach { assertFalse("$label contains $it", label.contains(it, ignoreCase = true)) } }
        assertEquals(
            "현재까지 수집 가능한 시세를 기준으로 모의투자 포워드 테스트를 실행합니다. 실제 주식 주문은 발생하지 않습니다. " +
                "모의 평가·주문·체결·계좌 기록은 생성될 수 있습니다.",
            PaperTradingPresenter.RUN_NOW_BODY,
        )
    }
}
