package com.mirunubi.bjstock.feature.admin

import com.mirunubi.bjstock.core.database.entity.ForwardOperationEntity
import com.mirunubi.bjstock.core.database.entity.OperationalEventEntity
import com.mirunubi.bjstock.core.model.ForwardOperationStatus
import com.mirunubi.bjstock.core.model.OperationalEventType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AdminViewModelTest {
    private val f = AdminFixtures

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun startsLoading_thenEverySectionLoadsOrIsEmpty() {
        val gate = CompletableDeferred<Unit>()
        val source = FakeAdminDataSource(gate = gate)
        val viewModel = AdminViewModel(source)
        assertEquals(AdminUiState(), viewModel.uiState.value)

        viewModel.refresh()
        assertEquals(SectionState.Loading, viewModel.uiState.value.status)

        gate.complete(Unit)
        val state = viewModel.uiState.value
        assertTrue(state.status is SectionState.Loaded)
        assertEquals(SectionState.Empty(AdminPresenter.OPERATIONS_EMPTY), state.operations)
        assertEquals(SectionState.Empty(AdminPresenter.AUDIT_EMPTY), state.audit)
        assertEquals(SectionState.Empty(AdminPresenter.ERRORS_EMPTY), state.errors)
        assertTrue(state.environment is SectionState.Loaded)
    }

    @Test
    fun oneSectionFailure_isIsolated_andShowsSafeKoreanOnly() {
        val raw = "near appsecret TEST_APP_SECRET: SELECT * FROM api_error_logs"
        val source = FakeAdminDataSource(failAudit = IllegalStateException(raw))
        val viewModel = AdminViewModel(source)

        viewModel.refresh()

        val state = viewModel.uiState.value
        assertEquals(SectionState.Failed("데이터를 불러오지 못했습니다."), state.audit)
        assertTrue(state.status is SectionState.Loaded)
        assertTrue(state.environment is SectionState.Loaded)
        listOf(raw, "IllegalStateException", "SELECT", "appsecret").forEach { assertFalse(state.toString().contains(it)) }
    }

    @Test
    fun reloadAfterFailure_recovers() {
        val source = FakeAdminDataSource(failAudit = IllegalStateException("x"))
        val viewModel = AdminViewModel(source)
        viewModel.refresh()
        assertTrue(viewModel.uiState.value.audit is SectionState.Failed)

        source.failAudit = null
        source.operations = listOf(f.operation(1))
        viewModel.refresh()

        val state = viewModel.uiState.value
        assertTrue(state.audit is SectionState.Loaded)
        assertTrue(state.status is SectionState.Loaded)
        assertEquals(listOf(1L), (state.operations as SectionState.Loaded).value.map { it.id })
    }

    @Test
    fun refresh_keepsLoadedSectionsVisible_whileReloading() {
        val source = FakeAdminDataSource()
        val viewModel = AdminViewModel(source)
        viewModel.refresh()
        val loaded = viewModel.uiState.value

        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        viewModel.refresh()
        assertEquals(loaded, viewModel.uiState.value)
        viewModel.refresh()
        assertEquals(10, source.calls.size)

        gate.complete(Unit)
        assertEquals(loaded, viewModel.uiState.value)
    }

    @Test
    fun openOperation_loadsDetailWithEvents_andCloseReturnsToList() {
        val source = FakeAdminDataSource(operations = listOf(f.operation(3, ForwardOperationStatus.FAILED, finalCode = "NETWORK_FAILURE")))
        source.events = listOf(f.event(1, OperationalEventType.OPERATION_STARTED, result = "WORKER", operationId = 3))
        val viewModel = AdminViewModel(source)

        viewModel.openOperation(3)
        val detail = viewModel.uiState.value.detail as AdminDetail.Loaded
        assertEquals(3L, detail.operationId)
        assertEquals(listOf("실행 시작"), detail.view.events.map { it.title })

        viewModel.closeDetail()
        assertNull(viewModel.uiState.value.detail)
    }

    @Test
    fun openOperation_missingRow_showsNotFound() {
        val viewModel = AdminViewModel(FakeAdminDataSource())
        viewModel.openOperation(42)
        assertEquals(AdminDetail.Failed(42, "실행 기록을 찾을 수 없습니다."), viewModel.uiState.value.detail)
    }

    @Test
    fun openOperation_failure_showsSafeMessage() {
        val source = FakeAdminDataSource(failDetail = IllegalStateException("SELECT secret"))
        val viewModel = AdminViewModel(source)
        viewModel.openOperation(1)
        assertEquals(AdminDetail.Failed(1, AdminPresenter.LOAD_FAILED), viewModel.uiState.value.detail)
    }

    @Test
    fun filterAndExpansion_changeOnlyDisplayState() {
        val source = FakeAdminDataSource()
        val viewModel = AdminViewModel(source)
        viewModel.refresh()
        val callsBefore = source.calls.toList()

        viewModel.selectAuditFilter(AuditFilter.ERROR)
        viewModel.toggleAudit("api:1")
        viewModel.toggleError("operation:2")
        viewModel.toggleError("operation:2")

        val state = viewModel.uiState.value
        assertEquals(AuditFilter.ERROR, state.auditFilter)
        assertEquals(setOf("api:1"), state.expandedAudit)
        assertTrue(state.expandedErrors.isEmpty())
        assertEquals(callsBefore, source.calls)
    }

    private inner class FakeAdminDataSource(
        var operations: List<ForwardOperationEntity> = emptyList(),
        var failAudit: Exception? = null,
        val failDetail: Exception? = null,
        var gate: CompletableDeferred<Unit>? = null,
    ) : AdminDataSource {
        var events = emptyList<OperationalEventEntity>()
        val calls = mutableListOf<String>()

        override suspend fun status(): AdminStatusData {
            calls += "status"
            gate?.await()
            return f.statusData(operations = operations)
        }

        override suspend fun recentOperations(): List<ForwardOperationEntity> {
            calls += "operations"
            gate?.await()
            return operations
        }

        override suspend fun operationDetail(operationId: Long): AdminOperationDetailData? {
            calls += "detail"
            failDetail?.let { throw it }
            val operation = operations.firstOrNull { it.id == operationId } ?: return null
            return AdminOperationDetailData(operation, events.filter { it.operationId == operationId }, emptyList())
        }

        override suspend fun audit(): AdminAuditData {
            calls += "audit"
            gate?.await()
            failAudit?.let { throw it }
            return AdminAuditData(emptyList(), emptyList(), operations, emptyList())
        }

        override suspend fun errors(): AdminErrorData {
            calls += "errors"
            gate?.await()
            return AdminErrorData(emptyList(), operations, emptyList(), emptyList())
        }

        override suspend fun environment(): AdminEnvironmentData {
            calls += "environment"
            gate?.await()
            return f.environmentData()
        }
    }
}
