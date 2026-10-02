package com.mirunubi.bjstock.feature.admin

import com.mirunubi.bjstock.core.error.AppErrorCode
import com.mirunubi.bjstock.core.kis.KisEnvironment
import com.mirunubi.bjstock.core.model.ApiErrorType
import com.mirunubi.bjstock.core.model.ForwardOperationStatus
import com.mirunubi.bjstock.core.model.ForwardOperationTrigger
import com.mirunubi.bjstock.core.model.OperationalEventType
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.model.TradeAuditEventType
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminPresenterTest {
    private val f = AdminFixtures

    // region 운영 상태

    @Test
    fun status_autoOn_showsNextSlot_workState_andLatestOutcomes() {
        val view = AdminPresenter.status(
            f.statusData(
                operations = listOf(
                    f.operation(1, ForwardOperationStatus.BLOCKED, finalCode = "AUTH_REQUIRED"),
                    f.operation(2, ForwardOperationStatus.NO_OP),
                    f.operation(3, ForwardOperationStatus.SUCCEEDED),
                ),
                runs = listOf(f.run(1, RunStatus.RUNNING), f.run(2, RunStatus.READY), f.run(3, RunStatus.RUNNING), f.run(4, RunStatus.DRAFT)),
            ),
        )

        assertTrue(view.autoEnabled)
        assertEquals("켜짐", view.autoLabel)
        assertEquals("다음 자동 실행 10월 2일 오전 7:00 이후", view.nextRun)
        assertFalse(view.nextRunPastDue)
        assertEquals("대기 중 (ENQUEUED)", view.workState)
        assertEquals("없음", view.runningOperation)
        assertEquals("10월 1일 오전 5:30:05 · 변경 없음", view.lastSuccess)
        assertEquals("10월 1일 오전 6:30:05 · 차단 · AUTH_REQUIRED", view.lastProblem)
        assertEquals("실행 준비 1개 · 운영 중 2개", view.runs)
        assertEquals("모의투자 (VIRTUAL)", view.kisEnvironment)
        assertEquals("있음", view.kisCredential)
        assertNull(view.scheduleWarning)
    }

    @Test
    fun status_autoOff_noHistory_isExplicitlyEmpty() {
        val view = AdminPresenter.status(
            f.statusData(auto = f.AUTO_OFF, kis = KisPresence(KisEnvironment.PRODUCTION, credentialPresent = false)),
        )

        assertEquals("꺼짐", view.autoLabel)
        assertEquals("예약 없음 (자동운영 꺼짐)", view.workState)
        assertEquals(AdminPresenter.NONE_IN_WINDOW, view.lastSuccess)
        assertEquals(AdminPresenter.NONE_IN_WINDOW, view.lastProblem)
        assertEquals(AdminPresenter.NO_ACTIVE_RUNS, view.runs)
        assertEquals("실전투자 (PRODUCTION)", view.kisEnvironment)
        assertEquals("없음", view.kisCredential)
    }

    @Test
    fun status_runningOperation_andScheduleFailure_areShownWithoutRawDetail() {
        val view = AdminPresenter.status(
            f.statusData(
                auto = f.AUTO_ON.copy(workState = "RUNNING", lastScheduleFailure = "SCHEDULE_EVENT_NOT_PERSISTED:IOException"),
                operations = listOf(f.operation(1, ForwardOperationStatus.RUNNING)),
            ),
        )

        assertEquals("실행 중 (RUNNING)", view.workState)
        assertEquals("실행 중 · 10월 1일 오전 6:30:05 시작", view.runningOperation)
        assertEquals("최근 자동 예약 변경을 기록하지 못했습니다.", view.scheduleWarning)
        assertFalse(view.toString().contains("IOException"))
    }

    @Test
    fun workState_mapsWorkManagerStates() {
        assertEquals("선행 조건 대기 (BLOCKED)", AdminPresenter.workState("BLOCKED", true))
        assertEquals("예약된 작업 없음", AdminPresenter.workState(null, true))
        assertEquals("-", AdminPresenter.workState("not a code", true))
    }

    // endregion

    // region 최근 실행

    @Test
    fun operationStatus_koreanLabels_areDistinct_andNotColorOnly() {
        val labels = ForwardOperationStatus.entries.associateWith(AdminPresenter::operationStatus)
        assertEquals(
            mapOf(
                ForwardOperationStatus.RUNNING to "실행 중",
                ForwardOperationStatus.SUCCEEDED to "성공",
                ForwardOperationStatus.NO_OP to "변경 없음",
                ForwardOperationStatus.PARTIAL to "부분 완료",
                ForwardOperationStatus.BLOCKED to "차단",
                ForwardOperationStatus.FAILED to "실패",
            ),
            labels,
        )
        assertEquals(labels.size, labels.values.toSet().size)
        assertEquals(AdminTone.ERROR, AdminPresenter.statusTone(ForwardOperationStatus.BLOCKED))
        assertEquals(AdminTone.ERROR, AdminPresenter.statusTone(ForwardOperationStatus.FAILED))
    }

    @Test
    fun operations_emptyAndLoaded() {
        assertEquals(SectionState.Empty("최근 실행 기록이 없습니다."), AdminPresenter.operations(emptyList()))

        val loaded = AdminPresenter.operations(
            listOf(f.operation(4, ForwardOperationStatus.FAILED, trigger = ForwardOperationTrigger.MANUAL, finalCode = "NETWORK_FAILURE")),
        ) as SectionState.Loaded
        val row = loaded.value.single()
        assertEquals(4L, row.id)
        assertEquals("수동 실행", row.title)
        assertEquals("수동", row.trigger)
        assertEquals("실패", row.statusLabel)
        assertEquals("NETWORK_FAILURE", row.code)
        assertEquals("네트워크에 연결할 수 없어 실행하지 못했습니다.", row.message)
        assertEquals("1.2초", row.elapsed)
    }

    @Test
    fun detail_listsAllFields_events_andAudits() {
        val op = f.operation(
            9,
            ForwardOperationStatus.PARTIAL,
            finalCode = "PRIOR_RUN_BLOCKED",
            safeMessage = "One run blocked",
            scheduleInstanceId = "auto:2026-10-01:0700:KST",
        )
        val view = AdminPresenter.detail(
            AdminOperationDetailData(
                operation = op,
                events = listOf(
                    f.event(1, OperationalEventType.OPERATION_STARTED, result = "WORKER", operationId = 9),
                    f.event(2, OperationalEventType.RUN_RESULT, result = "BLOCKED", reasonCode = "AUTH_REQUIRED", operationId = 9, runId = 3),
                ),
                audits = listOf(f.audit(5, TradeAuditEventType.ORDER_CREATED, operationId = 9)),
            ),
        )

        val fields = view.fields.associate { it.label to it.value }
        assertEquals("#9", fields["ID"])
        assertEquals("자동 (WORKER)", fields["트리거"])
        assertEquals("부분 완료", fields["상태"])
        assertEquals("1.2초", fields["소요 시간"])
        assertEquals("PRIOR_RUN_BLOCKED", fields["결과 코드"])
        assertEquals("One run blocked", fields["안전 메시지"])
        assertEquals("대상 2 · 처리 1 · 건너뜀 1", fields["Run 처리"])
        assertEquals("완료 1 · 실패 0", fields["거래일 처리"])
        assertEquals("auto:2026-10-01:0700:KST", fields["예약 ID"])
        assertTrue(fields.containsKey("시작") && fields.containsKey("종료"))

        assertEquals(listOf("실행 시작", "Run 처리 결과"), view.events.map { it.title })
        val blocked = view.events[1]
        assertEquals("차단", blocked.result)
        assertTrue(blocked.problem)
        assertEquals("AUTH_REQUIRED", blocked.reasonCode)
        assertEquals("Run #3", blocked.ids)
        assertEquals("자동", view.events[0].result)
        assertEquals(listOf("audit:5"), view.audits.map { it.key })
    }

    @Test
    fun detail_withMalformedScheduleId_hidesIt() {
        val view = AdminPresenter.detail(
            AdminOperationDetailData(f.operation(1, scheduleInstanceId = "worker:raw-token-value"), emptyList(), emptyList()),
        )
        assertEquals("-", view.fields.single { it.label == "예약 ID" }.value)
        assertTrue(view.events.isEmpty())
    }

    // endregion

    // region Audit

    private fun auditSample() = AdminPresenter.audit(
        AdminAuditData(
            events = listOf(
                f.event(1, OperationalEventType.MARKET_SYNC_RESULT, result = "FAILED", reasonCode = "NETWORK_UNAVAILABLE"),
                f.event(2, OperationalEventType.WORKER_SCHEDULE_CHANGED, result = "SLOT_ENQUEUED"),
                f.event(3, OperationalEventType.RUN_RESULT, result = "PROCESSED", operationId = 1),
            ),
            audits = listOf(
                f.audit(4, TradeAuditEventType.EVALUATION_DECIDED),
                f.audit(5, TradeAuditEventType.RULE_TRIGGERED),
                f.audit(6, TradeAuditEventType.ORDER_REJECTED, reasonCode = "INSUFFICIENT_CASH"),
                f.audit(7, TradeAuditEventType.EXECUTION_FILLED),
            ),
            operations = listOf(
                f.operation(1, ForwardOperationStatus.SUCCEEDED, trigger = ForwardOperationTrigger.WORKER),
                f.operation(2, ForwardOperationStatus.FAILED, trigger = ForwardOperationTrigger.MANUAL),
            ),
            apiErrors = listOf(f.apiError(8)),
        ),
    ) as SectionState.Loaded

    @Test
    fun audit_mergesAllSources_newestFirst() {
        val rows = auditSample().value
        assertEquals(10, rows.size)
        assertEquals(rows.sortedByDescending { it.at }.map { it.at }, rows.map { it.at })
        assertEquals(
            setOf("audit", "event", "operation", "api"),
            rows.map { it.key.substringBefore(':') }.toSet(),
        )
    }

    @Test
    fun auditFilters_selectByCategory_andErrorsByOutcome() {
        val rows = auditSample().value
        fun keys(filter: AuditFilter) = AdminPresenter.filter(rows, filter).map { it.key }.toSet()

        assertEquals(rows.map { it.key }.toSet(), keys(AuditFilter.ALL))
        assertEquals(setOf("audit:4", "audit:5"), keys(AuditFilter.STRATEGY))
        assertEquals(setOf("audit:6", "audit:7"), keys(AuditFilter.ORDER))
        assertEquals(setOf("event:1", "api:8"), keys(AuditFilter.PROVIDER))
        assertEquals(setOf("event:2", "operation:1"), keys(AuditFilter.SCHEDULER))
        assertEquals(setOf("event:1", "operation:2", "api:8"), keys(AuditFilter.ERROR))
    }

    @Test
    fun auditFilter_labels_areTheSixRequiredFilters() {
        assertEquals(
            listOf("전체", "전략 판단", "주문 · 체결", "Provider", "Scheduler", "오류"),
            AuditFilter.entries.map { it.label },
        )
    }

    @Test
    fun auditRow_columns_andExpandedDetail() {
        val row = AdminPresenter.auditRow(
            f.audit(6, TradeAuditEventType.ORDER_REJECTED, runId = 3, operationId = 11, reasonCode = "INSUFFICIENT_CASH")
                .copy(metricCode = "DAILY_CHANGE_PCT", observedValue = "3.2", thresholdValue = "3.0"),
        )
        assertEquals("10월 1일 오전 7:24:05", row.time)
        assertEquals(AuditCategory.ORDER, row.category)
        assertEquals("주문 거절", row.title)
        assertEquals("거절", row.result)
        assertEquals("모의 현금이 부족해 주문이 거절되었습니다.", row.reason)
        assertEquals("Run #3", row.run)
        assertEquals("Operation #11", row.operation)
        val details = row.details.associate { it.label to it.value }
        assertEquals("INSUFFICIENT_CASH", details["사유 코드"])
        assertEquals("DAILY_CHANGE_PCT", details["지표"])
        assertEquals("3.2", details["관측값"])
        assertEquals("3.0", details["기준값"])
        assertEquals("-", details["판단 근거"])
    }

    @Test
    fun audit_empty() {
        assertEquals(
            SectionState.Empty("Audit 기록이 없습니다."),
            AdminPresenter.audit(AdminAuditData(emptyList(), emptyList(), emptyList(), emptyList())),
        )
    }

    // endregion

    // region 오류 / AppErrorCode

    @Test
    fun appErrorCode_resolvesCatalogAndForwardCodes_only() {
        assertEquals(AppErrorCode.LEDGER_MISMATCH, AdminPresenter.appErrorCode("LEDGER_MISMATCH"))
        assertEquals(AppErrorCode.NETWORK_UNAVAILABLE, AdminPresenter.appErrorCode("NETWORK_FAILURE"))
        assertEquals(AppErrorCode.INVALID_RUN_STATE, AdminPresenter.appErrorCode("INVALID_RUN"))
        assertNull(AdminPresenter.appErrorCode("ALREADY_RUNNING"))
        assertNull(AdminPresenter.appErrorCode("SOMETHING_ELSE"))
        assertNull(AdminPresenter.appErrorCode(null))
    }

    @Test
    fun appErrorPresentation_categorySeverityRetryImpact() {
        val ledger = AdminPresenter.operationErrorRow(f.operation(1, ForwardOperationStatus.FAILED, finalCode = "LEDGER_MISMATCH"))
        assertEquals("데이터 무결성", ledger.category)
        assertEquals("재무 무결성", ledger.severity)
        assertEquals(AdminTone.ERROR, ledger.tone)
        assertEquals("재시도하지 않음", ledger.retry)
        assertEquals("작업 중단", ledger.impact)
        assertEquals("모의계좌 재무 무결성 오류입니다. 확인이 필요합니다.", ledger.description)

        val rateLimit = AdminPresenter.eventErrorRow(
            f.event(2, OperationalEventType.MARKET_SYNC_RESULT, result = "FAILED", reasonCode = "KIS_RATE_LIMIT", operationId = 4),
        )
        assertEquals("일시적 오류", rateLimit.category)
        assertEquals("성능 저하", rateLimit.severity)
        assertEquals("자동 재시도 가능", rateLimit.retry)
        assertEquals("작업 재시도 대상", rateLimit.impact)
        assertTrue(rateLimit.details.any { it.label == "Operation" && it.value == "#4" })

        val credential = AdminPresenter.operationErrorRow(f.operation(3, ForwardOperationStatus.BLOCKED, finalCode = "CREDENTIAL_MISSING"))
        assertEquals("인증 설정", credential.category)
        assertEquals("사용자 조치 후 재시도", credential.retry)
        assertEquals("사용자 조치 필요", credential.impact)
    }

    @Test
    fun everyAppErrorCode_hasKoreanPresentation() {
        AppErrorCode.entries.forEach { code ->
            val row = AdminPresenter.operationErrorRow(f.operation(1, ForwardOperationStatus.FAILED, finalCode = code.name))
            listOf(row.category, row.severity, row.retry, row.impact, row.description).forEach { text ->
                assertTrue("${code.name}: $text", text.any { it in '\uAC00'..'\uD7A3' })
            }
            assertEquals(code.name, row.code)
        }
    }

    @Test
    fun outcomeReasonCode_isNotMislabelledAsUnexpected() {
        val row = AdminPresenter.operationErrorRow(f.operation(1, ForwardOperationStatus.BLOCKED, finalCode = "ALREADY_RUNNING"))
        assertEquals("실행 결과", row.category)
        assertEquals(AdminPresenter.UNCLASSIFIED, row.severity)
        assertEquals("다른 자동/수동 작업이 이미 실행 중입니다.", row.description)
        assertEquals("실행 차단", row.impact)
        assertEquals("기록 없음", row.retry)
    }

    @Test
    fun errors_collectsProblemSources_newestFirst_andSkipsDuplicatesAndSuccesses() {
        val state = AdminPresenter.errors(
            AdminErrorData(
                apiErrors = listOf(f.apiError(1, operationId = 7)),
                operations = listOf(
                    f.operation(2, ForwardOperationStatus.FAILED, finalCode = "NETWORK_FAILURE"),
                    f.operation(3, ForwardOperationStatus.SUCCEEDED),
                ),
                failedCycles = listOf(f.cycle(4)),
                events = listOf(
                    f.event(5, OperationalEventType.RUN_RESULT, result = "FAILED", reasonCode = "SNAPSHOT_MISSING_PRICE"),
                    f.event(6, OperationalEventType.OPERATION_FINISHED, result = "FAILED", reasonCode = "NETWORK_FAILURE"),
                    f.event(7, OperationalEventType.MARKET_SYNC_RESULT, result = "SUCCESS"),
                ),
            ),
        ) as SectionState.Loaded

        val keys = state.value.map { it.key }
        assertEquals(setOf("api:1", "operation:2", "cycle:4", "event:5"), keys.toSet())
        assertEquals(state.value.sortedByDescending { it.at }.map { it.key }, keys)
    }

    @Test
    fun cycleError_usesRecordedRetryability_andRunImpact() {
        val row = AdminPresenter.cycleErrorRow(f.cycle(4, errorCode = "NETWORK_FAILURE", retryable = true, runId = 3))
        assertEquals("NETWORK_FAILURE", row.code)
        assertEquals("실패 재시도 가능으로 기록됨", row.retry)
        assertEquals("작업 재시도 대상", row.impact)
        assertTrue(row.source.startsWith("거래일 처리"))
        assertTrue(row.details.any { it.label == "Run" && it.value == "#3" })
    }

    @Test
    fun apiError_isNotGivenAnInventedSeverity_andShowsOnlySafeIdentifiers() {
        val row = AdminPresenter.apiErrorRow(
            f.apiError(1, ApiErrorType.KIS_BUSINESS_ERROR, operationId = 7, httpStatus = 500, businessCode = "EGW00201", retryable = false),
        )
        assertEquals(AdminPresenter.UNCLASSIFIED, row.severity)
        assertEquals("KIS_BUSINESS_ERROR", row.code)
        assertEquals("KIS 업무 오류", row.description)
        assertEquals("재시도 불가로 기록됨", row.retry)
        assertEquals("Operation #7 중 발생", row.impact)
        val details = row.details.associate { it.label to it.value }
        assertEquals("500", details["HTTP 상태"])
        assertEquals("EGW00201", details["업무 코드"])

        val unsafe = AdminPresenter.apiErrorRow(f.apiError(2, httpStatus = 9999, businessCode = "bad code with spaces"))
        assertFalse(unsafe.details.any { it.label == "HTTP 상태" || it.label == "업무 코드" })
        assertEquals("실행과 연결되지 않은 호출", unsafe.impact)
    }

    @Test
    fun errors_empty() {
        assertEquals(
            SectionState.Empty("조회 범위 내 최근 오류가 없습니다."),
            AdminPresenter.errors(AdminErrorData(emptyList(), listOf(f.operation(1)), emptyList(), emptyList())),
        )
    }

    @Test
    fun auditAndErrorSections_discloseBoundedSourceWindows() {
        assertEquals("최근 기록을 표시합니다. 항목별 조회 범위가 서로 다를 수 있습니다.", AdminPresenter.AUDIT_WINDOW_NOTE)
        assertEquals("최근 오류 기록을 표시하며, 데이터 종류별 조회 범위가 다를 수 있습니다.", AdminPresenter.ERRORS_WINDOW_NOTE)
        assertEquals(
            "실행 최근 20건 · 운영 이벤트 최근 100건 · 거래 Audit 최근 100건 · API 오류 최근 7일(최대 100건)",
            AdminPresenter.AUDIT_WINDOW_DETAIL,
        )
        assertEquals(
            "실행 최근 20건 · 운영 이벤트 최근 100건 · API 오류 최근 7일(최대 100건) · 거래일 처리 Run별 최근 30건",
            AdminPresenter.ERRORS_WINDOW_DETAIL,
        )
        val disclosures = listOf(
            AdminPresenter.AUDIT_WINDOW_NOTE, AdminPresenter.ERRORS_WINDOW_NOTE,
            AdminPresenter.AUDIT_WINDOW_DETAIL, AdminPresenter.ERRORS_WINDOW_DETAIL, AdminPresenter.ERRORS_EMPTY,
        )
        disclosures.forEach { text ->
            listOf("전체", "모든", "영구", "전 기간").forEach { assertFalse("$text contains $it", text.contains(it)) }
        }
    }

    @Test
    fun recent20Note_isUnchanged() {
        assertEquals("최근 실행 20건 기준", AdminPresenter.status(f.statusData()).windowNote)
    }

    // endregion

    // region 앱 정보 · 환경

    @Test
    fun environment_showsBuildDbKisVersionsAndActiveRuns() {
        val view = AdminPresenter.environment(
            f.environmentData(
                runs = listOf(f.run(1, RunStatus.RUNNING, "A"), f.run(2, RunStatus.READY, "B"), f.run(3, RunStatus.COMPLETED, "C")),
            ),
        )
        assertEquals(
            listOf(
                AdminField("패키지", "com.mirunubi.bjstock"),
                AdminField("버전 이름", "0.1.0"),
                AdminField("버전 코드", "1"),
                AdminField("디버그 빌드", "예"),
                AdminField("DB 버전", "12"),
                AdminField("KIS 환경", "모의투자 (VIRTUAL)"),
                AdminField("KIS 인증정보", "있음"),
            ),
            view.fields,
        )
        assertEquals(listOf("기본 모멘텀 전략 v2"), view.activeVersions)
        assertEquals(listOf("B · 실행 준비", "A · 운영 중"), view.runs)
    }

    @Test
    fun environment_neverShowsProvenanceOrSecrets() {
        val text = AdminPresenter.environment(f.environmentData(versions = emptyList())).toString()
        listOf("commit", "커밋", "SHA", "서명", "signing", "App Key", "Secret", "토큰", "token", "만료").forEach {
            assertFalse(it, text.contains(it, ignoreCase = true))
        }
    }

    // endregion

    // region Formatting and safety

    @Test
    fun koreanDateTime_inSeoul_withSeconds() {
        assertEquals("10월 1일 오전 7:30:05", AdminPresenter.dateTime(f.NOW))
        assertEquals("10월 1일 오후 3:05:09", AdminPresenter.dateTime(Instant.parse("2026-10-01T06:05:09Z")))
    }

    @Test
    fun elapsed_formatting() {
        assertEquals("-", AdminPresenter.elapsed(null))
        assertEquals("850ms", AdminPresenter.elapsed(850))
        assertEquals("1.2초", AdminPresenter.elapsed(1_234))
        assertEquals("1분 5초", AdminPresenter.elapsed(65_000))
    }

    @Test
    fun safeText_withholdsSecretLikeText_andBoundsLength() {
        assertEquals(AdminPresenter.WITHHELD, AdminPresenter.safeText("Bearer abc.def"))
        assertEquals(AdminPresenter.WITHHELD, AdminPresenter.safeText("appsecret=XYZ"))
        assertEquals(AdminPresenter.WITHHELD, AdminPresenter.safeText("details withheld"))
        assertNull(AdminPresenter.safeText("   "))
        assertEquals(200, AdminPresenter.safeText("a".repeat(300))!!.length)
        assertEquals("Market data sync failed", AdminPresenter.safeText("Market data  sync failed"))
    }

    @Test
    fun noRenderedRow_leaksSecretLikeStoredText() {
        val secret = "appkey=PSabcdef0123456789 access_token eyJhbGciOi"
        val rendered = listOf(
            AdminPresenter.operationRow(f.operation(1, safeMessage = secret)),
            AdminPresenter.event(f.event(2, OperationalEventType.RUN_RESULT, safeMessage = secret)),
            AdminPresenter.auditRow(f.audit(3, TradeAuditEventType.ORDER_CREATED, reasonText = secret)),
            AdminPresenter.apiErrorRow(f.apiError(4, safeMessage = secret)),
        ).joinToString()
        listOf("PSabcdef", "eyJhbGci", "appkey", "access_token").forEach { assertFalse(it, rendered.contains(it)) }
    }

    @Test
    fun noFakeSecurityOrIntegrityStatus_inStaticWording() {
        val texts = listOf(
            AdminPresenter.LOADING, AdminPresenter.LOAD_FAILED, AdminPresenter.OPERATIONS_EMPTY, AdminPresenter.ERRORS_EMPTY,
            AdminPresenter.AUDIT_EMPTY, AdminPresenter.EVENTS_EMPTY,
        ) + AuditFilter.entries.map { it.label } + ForwardOperationStatus.entries.map(AdminPresenter::operationStatus)
        texts.forEach { text ->
            listOf("정상", "PASS", "OK", "보안 모듈").forEach { assertFalse("$text contains $it", text.contains(it)) }
        }
    }

    @Test
    fun requiredEmptyAndLoadingMessages() {
        assertEquals("최근 실행 기록이 없습니다.", AdminPresenter.OPERATIONS_EMPTY)
        assertEquals("조회 범위 내 최근 오류가 없습니다.", AdminPresenter.ERRORS_EMPTY)
        assertEquals("Audit 기록이 없습니다.", AdminPresenter.AUDIT_EMPTY)
        assertEquals("관련 이벤트가 없습니다.", AdminPresenter.EVENTS_EMPTY)
        assertEquals("데이터를 불러오는 중입니다.", AdminPresenter.LOADING)
        assertEquals("데이터를 불러오지 못했습니다.", AdminPresenter.LOAD_FAILED)
    }

    @Test
    fun resultLabels_forPersistedEventResults() {
        assertEquals("처리됨", AdminPresenter.resultLabel("PROCESSED"))
        assertEquals("건너뜀", AdminPresenter.resultLabel("SKIPPED"))
        assertEquals("성공", AdminPresenter.resultLabel("SUCCESS"))
        assertEquals("완료", AdminPresenter.resultLabel("COMPLETE"))
        assertEquals("실패", AdminPresenter.resultLabel("FAILED"))
        assertEquals("예약됨", AdminPresenter.resultLabel("SLOT_ENQUEUED"))
        assertEquals("NEW_CODE", AdminPresenter.resultLabel("NEW_CODE"))
        assertEquals("-", AdminPresenter.resultLabel("free text"))
        assertEquals("-", AdminPresenter.resultLabel(null))
    }

    // endregion
}
