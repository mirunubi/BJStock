package com.mirunubi.bjstock.ui.text

import com.mirunubi.bjstock.core.factor.FactorCodes
import com.mirunubi.bjstock.core.model.ForwardOperationStatus
import com.mirunubi.bjstock.core.model.ForwardOperationTrigger
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.model.StrategyVersionStatus
import com.mirunubi.bjstock.core.model.TradeDecision
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Display-only Korean labels for canonical identifiers (docs/151 §2, §18).
 * Persisted values and internal codes stay English.
 */
object KoreanLabels {
    val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

    fun operationStatus(status: ForwardOperationStatus): String = when (status) {
        ForwardOperationStatus.RUNNING -> "실행중"
        ForwardOperationStatus.SUCCEEDED -> "정상 완료"
        ForwardOperationStatus.NO_OP -> "처리할 항목 없음"
        ForwardOperationStatus.PARTIAL -> "일부 처리"
        ForwardOperationStatus.BLOCKED -> "실행 중단"
        ForwardOperationStatus.FAILED -> "오류"
    }

    /** Canonical product wording for every product screen (Home, 모의투자, 성과). */
    fun runStatus(status: RunStatus): String = when (status) {
        RunStatus.DRAFT -> "설정중"
        RunStatus.READY -> "실행 준비"
        RunStatus.RUNNING -> "운영 중"
        RunStatus.PAUSED -> "일시정지"
        RunStatus.COMPLETED -> "완료"
        RunStatus.CANCELLED -> "취소"
    }

    fun versionStatus(status: StrategyVersionStatus): String = when (status) {
        StrategyVersionStatus.DRAFT -> "작성중"
        StrategyVersionStatus.ACTIVE -> "사용중"
        StrategyVersionStatus.RETIRED -> "종료"
    }

    fun decision(decision: TradeDecision): String = when (decision) {
        TradeDecision.BUY -> "매수"
        TradeDecision.SELL -> "매도"
        TradeDecision.HOLD -> "관망"
        TradeDecision.NO_ACTION -> "판단 없음"
    }

    fun trigger(trigger: ForwardOperationTrigger): String = when (trigger) {
        ForwardOperationTrigger.MANUAL -> "수동"
        ForwardOperationTrigger.WORKER -> "자동"
    }

    /** Korean name of a system factor; unknown codes are shown as-is. */
    fun factorName(code: String): String = when (code) {
        FactorCodes.PRICE_VS_MA20 -> "20일 이동평균 대비"
        FactorCodes.PRICE_VS_MA60 -> "60일 이동평균 대비"
        FactorCodes.MOMENTUM_20D -> "20일 모멘텀"
        FactorCodes.MOMENTUM_60D -> "60일 모멘텀"
        FactorCodes.VOLATILITY_20D -> "20일 변동성"
        FactorCodes.VOLUME_RATIO_20D -> "20일 거래량 비율"
        else -> code
    }

    /** e.g. `9월 30일`. */
    fun date(date: LocalDate): String = "${date.monthValue}월 ${date.dayOfMonth}일"

    /** e.g. `10월 1일 오전 7:30` in Asia/Seoul. */
    fun dateTime(instant: Instant): String {
        val local = instant.atZone(SEOUL)
        val meridiem = if (local.hour < 12) "오전" else "오후"
        val hour = (local.hour + 11) % 12 + 1
        return "${date(local.toLocalDate())} $meridiem $hour:${"%02d".format(local.minute)}"
    }

    /**
     * A scheduled Auto slot. 07:00 is the earliest eligible time, never an exact execution time; a slot whose
     * time has passed is shown as waiting, never as a future time, and no WorkManager backoff time is inferred.
     */
    fun autoSlot(scheduledAt: Instant, workState: String?, now: Instant): String = when {
        workState == "RUNNING" -> "${dateTime(scheduledAt)} 예약 작업 · 실행 중"
        !scheduledAt.isAfter(now) -> "${dateTime(scheduledAt)} 예약 작업 · 실행/재시도 대기 중"
        else -> "다음 자동 실행 ${dateTime(scheduledAt)} 이후"
    }

    fun isSlotPastDue(scheduledAt: Instant, workState: String?, now: Instant): Boolean =
        workState == "RUNNING" || !scheduledAt.isAfter(now)
}
