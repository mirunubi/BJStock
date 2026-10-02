package com.mirunubi.bjstock.feature.home

import com.mirunubi.bjstock.core.analytics.PerformanceMath
import com.mirunubi.bjstock.core.database.entity.TradeAuditLogEntity
import com.mirunubi.bjstock.core.model.TradeAuditEventType
import com.mirunubi.bjstock.feature.admin.AdminAuditData
import com.mirunubi.bjstock.feature.admin.AdminErrorData
import com.mirunubi.bjstock.feature.admin.AdminPresenter
import com.mirunubi.bjstock.feature.admin.AuditCategory
import com.mirunubi.bjstock.feature.admin.SectionState
import com.mirunubi.bjstock.feature.paper.InstrumentLabel
import com.mirunubi.bjstock.feature.paper.PaperTradingPresenter
import com.mirunubi.bjstock.ui.text.KoreanLabels

enum class HomeDestination { PAPER_TRADING, ADMIN }

data class HomeLink(val label: String, val destination: HomeDestination)

/** Summary cards Home adds around the existing cards; each has one optional detail link. */
enum class HomeActivitySection(val title: String, val link: HomeLink?) {
    RUNS("실행 중 전략", HomeLink("모의투자에서 보기", HomeDestination.PAPER_TRADING)),
    SIGNALS("최근 처리일 신호", HomeLink("모의투자에서 보기", HomeDestination.PAPER_TRADING)),
    TRADES("최근 주문 · 체결", HomeLink("모의투자에서 보기", HomeDestination.PAPER_TRADING)),
    ERRORS("최근 오류", HomeLink("운영 · 감사에서 보기", HomeDestination.ADMIN)),
    AUDIT("최근 Audit", HomeLink("운영 · 감사에서 보기", HomeDestination.ADMIN)),
}

data class HomeSummaryRow(val headline: String, val detail: String?, val problem: Boolean = false)

data class HomeSummary(
    val headline: String?,
    val rows: List<HomeSummaryRow>,
    val moreNote: String?,
    val note: String?,
)

/** Pure mapping for the Home summary cards. Detail and filtering stay in 운영 · 감사 and 모의투자. */
object HomeActivityPresenter {
    const val RUNS_EMPTY = "실행 중인 전략이 없습니다."
    const val SIGNALS_EMPTY = "최근 처리일 신호가 없습니다."
    const val TRADES_EMPTY = "최근 주문·체결이 없습니다."
    const val ERRORS_EMPTY = AdminPresenter.ERRORS_EMPTY
    const val AUDIT_EMPTY = "최근 Audit 기록이 없습니다."
    const val LOAD_FAILED = "이 항목을 불러오지 못했습니다."
    const val SIGNALS_NOTE = "자동운영은 직전 거래일 데이터를 처리합니다 · 최근 기록 기준"
    const val ERRORS_NOTE = "조회 범위 내 최근 기록 기준"
    const val AUDIT_NOTE = "최근 기록 기준"
    const val AUTO_LINK_LABEL = "운영 · 감사에서 보기"

    const val MAX_ROWS = 3
    private const val REASON_MAX = 40

    // region 실행 중 전략

    fun runs(runs: List<HomeRunRef>): SectionState<HomeSummary> {
        if (runs.isEmpty()) return SectionState.Empty(RUNS_EMPTY)
        return SectionState.Loaded(
            HomeSummary(
                headline = AdminPresenter.runCounts(runs.map { it.status }),
                rows = runs.take(MAX_ROWS).map { run ->
                    HomeSummaryRow(
                        headline = "Run #${run.runId} · ${run.runName}",
                        detail = listOf(
                            "${run.strategyName} ${run.versionLabel}",
                            KoreanLabels.runStatus(run.status),
                            "초기 자본 ${PerformanceMath.formatWon(run.initialCash)}",
                        ).joinToString(" · "),
                    )
                },
                moreNote = more(runs.size, "개"),
                note = null,
            ),
        )
    }

    // endregion

    // region 최근 처리일 신호

    fun signals(data: HomeSignalData): SectionState<HomeSummary> {
        val date = data.marketDate
        if (date == null || data.logs.isEmpty()) return SectionState.Empty(SIGNALS_EMPTY)
        val groups = data.logs.groupBy { it.evaluationId?.let { id -> "evaluation:$id" } ?: "audit:${it.id}" }.values.toList()
        return SectionState.Loaded(
            HomeSummary(
                headline = "처리일 ${PaperTradingPresenter.dotDate(date)}",
                rows = groups.take(MAX_ROWS).map { signalRow(it, data) },
                moreNote = more(groups.size, "건"),
                note = SIGNALS_NOTE,
            ),
        )
    }

    private fun signalRow(logs: List<TradeAuditLogEntity>, data: HomeSignalData): HomeSummaryRow {
        val first = logs.first()
        val rule = logs.firstOrNull { it.eventType == TradeAuditEventType.RULE_TRIGGERED }
        val decision = first.evaluationId?.let { data.decisions[it] }
            ?.let { "${KoreanLabels.decision(it)}(${it.name})" }
            ?: PaperTradingPresenter.auditEvent(first.eventType)
        val instrument = first.instrumentId?.let { data.instruments[it] }?.let(::instrumentLabel) ?: "종목 정보 없음"
        val run = data.runs[first.strategyRunId]?.let { "Run #${it.runId} · ${it.strategyName} ${it.versionLabel}" }
            ?: "Run #${first.strategyRunId}"
        return HomeSummaryRow(
            headline = listOfNotNull(instrument, decision, "신호 규칙 발생".takeIf { rule != null }).joinToString(" · "),
            detail = listOfNotNull(run, reason(rule ?: first)).joinToString(" · "),
        )
    }

    private fun reason(log: TradeAuditLogEntity): String? {
        val text = AdminPresenter.codeMessage(log.reasonCode) ?: AdminPresenter.safeText(log.reasonText) ?: return null
        return if (text.length > REASON_MAX) text.take(REASON_MAX) + "…" else text
    }

    // endregion

    // region 최근 주문 · 체결

    fun trades(data: HomeTradeData): SectionState<HomeSummary> {
        if (data.orders.isEmpty()) return SectionState.Empty(TRADES_EMPTY)
        return SectionState.Loaded(
            HomeSummary(
                headline = null,
                rows = data.orders.take(MAX_ROWS).map { order ->
                    val execution = data.executions[order.id]
                    val instrument = data.instruments[order.instrumentId]?.let(::instrumentLabel) ?: "종목 #${order.instrumentId}"
                    HomeSummaryRow(
                        headline = "${PaperTradingPresenter.side(order.side)}(${order.side.name}) · $instrument · ${"%,d".format(order.quantity)}주",
                        detail = listOfNotNull(
                            PaperTradingPresenter.orderStatus(order.status),
                            execution?.let { "체결가 ${PerformanceMath.formatWon(it.executionPrice)}" },
                            KoreanLabels.dateTime(execution?.executedAt ?: order.createdAt),
                        ).joinToString(" · "),
                    )
                },
                moreNote = more(data.totalOrders, "건"),
                note = null,
            ),
        )
    }

    // endregion

    // region 최근 오류 / 최근 Audit (운영 · 감사 read model)

    fun errors(data: AdminErrorData): SectionState<HomeSummary> {
        val rows = (AdminPresenter.errors(data) as? SectionState.Loaded)?.value.orEmpty()
        if (rows.isEmpty()) return SectionState.Empty(ERRORS_EMPTY)
        return SectionState.Loaded(
            HomeSummary(
                headline = "조회 범위 내 ${rows.size}건",
                rows = rows.take(MAX_ROWS).map { row ->
                    HomeSummaryRow(
                        headline = row.description,
                        detail = listOf(row.time, row.source, row.severity).joinToString(" · "),
                        problem = true,
                    )
                },
                moreNote = null,
                note = ERRORS_NOTE,
            ),
        )
    }

    /** Lifecycle-only operation events are skipped on Home unless they recorded a problem. */
    fun audit(data: AdminAuditData): SectionState<HomeSummary> {
        val rows = (AdminPresenter.audit(data) as? SectionState.Loaded)?.value.orEmpty()
            .filter { it.category != AuditCategory.OPERATION || it.problem }
        if (rows.isEmpty()) return SectionState.Empty(AUDIT_EMPTY)
        return SectionState.Loaded(
            HomeSummary(
                headline = null,
                rows = rows.take(MAX_ROWS).map { row ->
                    HomeSummaryRow(
                        headline = "${row.category.label} · ${row.title} · ${row.result}",
                        detail = listOfNotNull(row.time, row.run).joinToString(" · "),
                        problem = row.problem,
                    )
                },
                moreNote = null,
                note = AUDIT_NOTE,
            ),
        )
    }

    // endregion

    private fun instrumentLabel(label: InstrumentLabel) = "${label.name} ${label.symbol}"

    private fun more(total: Int, unit: String): String? = (total - MAX_ROWS).takeIf { it > 0 }?.let { "외 $it$unit" }
}
