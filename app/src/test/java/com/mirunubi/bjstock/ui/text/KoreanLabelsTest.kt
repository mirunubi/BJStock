package com.mirunubi.bjstock.ui.text

import com.mirunubi.bjstock.core.model.ForwardOperationStatus
import com.mirunubi.bjstock.core.model.ForwardOperationTrigger
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.model.StrategyVersionStatus
import com.mirunubi.bjstock.core.model.TradeDecision
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class KoreanLabelsTest {
    @Test
    fun operationStatus_isTranslated() {
        assertEquals(
            mapOf(
                ForwardOperationStatus.RUNNING to "실행중",
                ForwardOperationStatus.SUCCEEDED to "정상 완료",
                ForwardOperationStatus.NO_OP to "처리할 항목 없음",
                ForwardOperationStatus.PARTIAL to "일부 처리",
                ForwardOperationStatus.BLOCKED to "실행 중단",
                ForwardOperationStatus.FAILED to "오류",
            ),
            ForwardOperationStatus.entries.associateWith(KoreanLabels::operationStatus),
        )
    }

    @Test
    fun lifecycleStatuses_areTranslated() {
        assertEquals(
            mapOf(
                RunStatus.DRAFT to "설정중",
                RunStatus.READY to "실행 준비",
                RunStatus.RUNNING to "운영 중",
                RunStatus.PAUSED to "일시정지",
                RunStatus.COMPLETED to "완료",
                RunStatus.CANCELLED to "취소",
            ),
            RunStatus.entries.associateWith(KoreanLabels::runStatus),
        )
        assertEquals("작성중", KoreanLabels.versionStatus(StrategyVersionStatus.DRAFT))
        assertEquals("사용중", KoreanLabels.versionStatus(StrategyVersionStatus.ACTIVE))
        assertEquals("종료", KoreanLabels.versionStatus(StrategyVersionStatus.RETIRED))
        RunStatus.entries.forEach { assertNotEquals(it.name, KoreanLabels.runStatus(it)) }
    }

    @Test
    fun decisionsAndTriggers_areTranslated() {
        assertEquals("매수", KoreanLabels.decision(TradeDecision.BUY))
        assertEquals("매도", KoreanLabels.decision(TradeDecision.SELL))
        assertEquals("관망", KoreanLabels.decision(TradeDecision.HOLD))
        TradeDecision.entries.forEach { assertNotEquals(it.name, KoreanLabels.decision(it)) }
        assertEquals("자동", KoreanLabels.trigger(ForwardOperationTrigger.WORKER))
        assertEquals("수동", KoreanLabels.trigger(ForwardOperationTrigger.MANUAL))
    }

    @Test
    fun dateTime_usesSeoulAndKoreanMeridiem() {
        assertEquals("10월 1일 오전 7:30", KoreanLabels.dateTime(Instant.parse("2026-09-30T22:30:00Z")))
        assertEquals("9월 30일 오후 12:05", KoreanLabels.dateTime(Instant.parse("2026-09-30T03:05:00Z")))
        assertEquals("10월 1일 오전 12:00", KoreanLabels.dateTime(Instant.parse("2026-09-30T15:00:00Z")))
        assertEquals("9월 30일", KoreanLabels.date(LocalDate.of(2026, 9, 30)))
    }

    @Test
    fun autoSlot_isFutureOnlyBeforeTheSlot_waitingAfterIt_andRunningWhileRunning() {
        val slot = Instant.parse("2026-09-30T22:00:00Z")
        assertEquals("다음 자동 실행 10월 1일 오전 7:00 이후", KoreanLabels.autoSlot(slot, "ENQUEUED", slot.minusSeconds(60)))
        assertEquals("10월 1일 오전 7:00 예약 작업 · 실행/재시도 대기 중", KoreanLabels.autoSlot(slot, "ENQUEUED", slot))
        assertEquals("10월 1일 오전 7:00 예약 작업 · 실행 중", KoreanLabels.autoSlot(slot, "RUNNING", slot.plusSeconds(60)))
    }
}
