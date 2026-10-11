package com.mirunubi.bjstock.feature.admin

import com.mirunubi.bjstock.core.database.entity.ForwardOperationEntity
import com.mirunubi.bjstock.core.model.ForwardOperationStatus
import com.mirunubi.bjstock.ui.navigation.NavWidthClass
import java.io.File
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
class AdminLayoutTest {
    private val f = AdminFixtures
    private val loading = AdminDetail.Loading(3)
    private val failed = AdminDetail.Failed(3, AdminPresenter.LOAD_FAILED)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // region A–C mode mapping

    @Test
    fun a_compact_isSinglePane() {
        assertEquals(AdminLayoutMode.SINGLE_PANE, AdminLayout.modeFor(NavWidthClass.COMPACT))
    }

    @Test
    fun b_medium_isSinglePane() {
        assertEquals(AdminLayoutMode.SINGLE_PANE, AdminLayout.modeFor(NavWidthClass.MEDIUM))
    }

    @Test
    fun c_expanded_isTwoPane() {
        assertEquals(AdminLayoutMode.TWO_PANE, AdminLayout.modeFor(NavWidthClass.EXPANDED))
        assertEquals(360, AdminLayout.LEFT_PANE_WIDTH_DP)
    }

    // endregion

    // region D–I section assignment

    @Test
    fun d_singlePane_keepsTheCanonicalOrder() {
        assertEquals(
            listOf(
                AdminSection.INFO,
                AdminSection.STATUS,
                AdminSection.OPERATIONS,
                AdminSection.AUDIT,
                AdminSection.ERRORS,
                AdminSection.ENVIRONMENT,
            ),
            AdminLayout.singlePane,
        )
    }

    @Test
    fun e_expandedLeft_isInfoStatusOperations() {
        assertEquals(listOf(AdminSection.INFO, AdminSection.STATUS, AdminSection.OPERATIONS), AdminLayout.left)
    }

    @Test
    fun f_expandedRightRoot_isAuditErrorsEnvironment() {
        assertEquals(listOf(AdminSection.AUDIT, AdminSection.ERRORS, AdminSection.ENVIRONMENT), AdminLayout.rightRoot)
    }

    @Test
    fun g_leftAndRightRoot_coverEverySectionExactlyOnce() {
        val both = AdminLayout.left + AdminLayout.rightRoot
        assertEquals(AdminLayout.singlePane.size, both.size)
        assertEquals(AdminLayout.singlePane.toSet(), both.toSet())
        assertEquals(AdminSection.entries.toSet(), both.toSet())
    }

    @Test
    fun h_panes_doNotOverlap() {
        assertTrue(AdminLayout.left.intersect(AdminLayout.rightRoot.toSet()).isEmpty())
    }

    @Test
    fun i_readingLeftThenRight_isTheSinglePaneOrder() {
        assertEquals(AdminLayout.singlePane, AdminLayout.left + AdminLayout.rightRoot)
    }

    // endregion

    // region J–M right pane

    @Test
    fun j_noDetail_showsTheRootSections() {
        assertEquals(AdminRightPane.Root, AdminLayout.rightPane(null))
        assertEquals("root", AdminLayout.rightPaneKey(null))
    }

    @Test
    fun k_loadingDetail_showsTheDetail() {
        assertEquals(AdminRightPane.Detail(loading), AdminLayout.rightPane(loading))
    }

    @Test
    fun l_failedDetail_showsTheDetail() {
        assertEquals(AdminRightPane.Detail(failed), AdminLayout.rightPane(failed))
    }

    @Test
    fun m_loadedDetail_showsTheDetail_andKeysTheScrollByOperation() {
        val viewModel = AdminViewModel(FakeSource(listOf(f.operation(3), f.operation(4))))
        viewModel.openOperation(3)
        val loaded = viewModel.uiState.value.detail as AdminDetail.Loaded

        assertEquals(AdminRightPane.Detail(loaded), AdminLayout.rightPane(loaded))
        assertEquals(AdminLayout.rightPaneKey(loading), AdminLayout.rightPaneKey(loaded))
        assertEquals(AdminLayout.rightPaneKey(failed), AdminLayout.rightPaneKey(loaded))
        assertFalse(AdminLayout.rightPaneKey(loaded) == AdminLayout.rightPaneKey(AdminDetail.Loading(4)))
        assertFalse(AdminLayout.rightPaneKey(loaded) == AdminLayout.rightPaneKey(null))
    }

    @Test
    fun n_loadedOperations_doNotAutoSelect() {
        val source = FakeSource(listOf(f.operation(3), f.operation(4)))
        val viewModel = AdminViewModel(source)
        viewModel.refresh()

        val state = viewModel.uiState.value
        assertTrue(state.operations is SectionState.Loaded)
        assertNull(state.detail)
        assertEquals(AdminRightPane.Root, AdminLayout.rightPane(state.detail))
        assertNull(AdminLayout.selectedOperationId(AdminLayoutMode.TWO_PANE, state.detail))
        assertTrue(source.calls.none { it.startsWith("detail") })
    }

    // endregion

    // region O–P highlight

    @Test
    fun o_twoPane_highlightsTheOpenOperation() {
        assertEquals(3L, AdminLayout.selectedOperationId(AdminLayoutMode.TWO_PANE, loading))
        assertEquals(3L, AdminLayout.selectedOperationId(AdminLayoutMode.TWO_PANE, failed))
        assertNull(AdminLayout.selectedOperationId(AdminLayoutMode.TWO_PANE, null))
    }

    @Test
    fun p_singlePane_hasNoAdaptiveHighlight() {
        assertNull(AdminLayout.selectedOperationId(AdminLayoutMode.SINGLE_PANE, loading))
        assertNull(AdminLayout.selectedOperationId(AdminLayoutMode.SINGLE_PANE, null))
    }

    // endregion

    // region Q–S real ViewModel

    @Test
    fun q_openThenClose_returnsToTheRootSections() {
        val viewModel = AdminViewModel(FakeSource(listOf(f.operation(3))))
        viewModel.refresh()

        viewModel.openOperation(3)
        assertTrue(AdminLayout.rightPane(viewModel.uiState.value.detail) is AdminRightPane.Detail)
        viewModel.closeDetail()

        assertEquals(AdminRightPane.Root, AdminLayout.rightPane(viewModel.uiState.value.detail))
        assertTrue(viewModel.uiState.value.operations is SectionState.Loaded)
    }

    @Test
    fun r_openingAnotherOperation_replacesTheDetail() {
        val viewModel = AdminViewModel(FakeSource(listOf(f.operation(3), f.operation(4, ForwardOperationStatus.FAILED))))
        viewModel.openOperation(3)

        viewModel.openOperation(4)

        val detail = viewModel.uiState.value.detail as AdminDetail.Loaded
        assertEquals(4L, detail.operationId)
        assertEquals(4L, AdminLayout.selectedOperationId(AdminLayoutMode.TWO_PANE, detail))
    }

    @Test
    fun s_openAndClose_onlyReadTheOpenedDetail() {
        val source = FakeSource(listOf(f.operation(3), f.operation(4)))
        val viewModel = AdminViewModel(source)
        viewModel.refresh()
        val afterRefresh = source.calls.toList()

        viewModel.openOperation(3)
        viewModel.closeDetail()
        viewModel.openOperation(4)
        viewModel.closeDetail()

        assertEquals(afterRefresh + listOf("detail:3", "detail:4"), source.calls)
        assertNull(viewModel.uiState.value.detail)
    }

    // endregion

    @Test
    fun adminSources_keepTheShellAndRefreshContract() {
        val adminDir = listOf(
            File("src/main/java/com/mirunubi/bjstock/feature/admin"),
            File("app/src/main/java/com/mirunubi/bjstock/feature/admin"),
        ).first { it.isDirectory }
        val screen = File(adminDir, "AdminScreen.kt").readText()
        assertTrue(screen.contains("AdminLayout.modeFor(LocalNavChrome.current.widthClass)"))
        assertTrue(screen.contains("BackHandler(enabled = detail != null) { viewModel.closeDetail() }"))
        assertTrue(screen.contains("title = if (detail != null) \"실행 상세\" else \"운영 · 감사\""))
        assertTrue(screen.contains("onBack = if (detail != null) viewModel::closeDetail else onBack"))
        assertEquals(1, Regex("""LaunchedEffect\(""").findAll(screen).count())
        assertTrue(
            screen.indexOf("LaunchedEffect(Unit) { viewModel.refresh() }") in 0 until screen.indexOf("when (mode)"),
        )

        adminDir.listFiles().orEmpty().filter { it.extension == "kt" }.forEach { file ->
            val source = file.readText()
            listOf("fromWidthDp(", "currentNavWidthClass(", "screenWidthDp", "material3.adaptive").forEach {
                assertFalse("${file.name}: $it", source.contains(it))
            }
            assertFalse(file.name, Regex("""\b(600|840)\b""").containsMatchIn(source))
        }
    }

    /** Read-only fake; every access is recorded so the tests can compare access patterns. */
    private inner class FakeSource(private val operations: List<ForwardOperationEntity>) : AdminDataSource {
        val calls = mutableListOf<String>()

        override suspend fun status(): AdminStatusData {
            calls += "status"
            return f.statusData(operations = operations)
        }

        override suspend fun recentOperations(): List<ForwardOperationEntity> {
            calls += "operations"
            return operations
        }

        override suspend fun operationDetail(operationId: Long): AdminOperationDetailData? {
            calls += "detail:$operationId"
            val operation = operations.firstOrNull { it.id == operationId } ?: return null
            return AdminOperationDetailData(operation, emptyList(), emptyList())
        }

        override suspend fun audit(): AdminAuditData {
            calls += "audit"
            return AdminAuditData(emptyList(), emptyList(), operations, emptyList())
        }

        override suspend fun errors(): AdminErrorData {
            calls += "errors"
            return AdminErrorData(emptyList(), operations, emptyList(), emptyList())
        }

        override suspend fun environment(): AdminEnvironmentData {
            calls += "environment"
            return f.environmentData()
        }
    }
}
