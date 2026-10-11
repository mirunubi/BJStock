package com.mirunubi.bjstock.feature.admin

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.mirunubi.bjstock.core.database.entity.ForwardOperationEntity
import com.mirunubi.bjstock.ui.navigation.AdaptiveRender
import com.mirunubi.bjstock.ui.navigation.BJStockRoutes
import com.mirunubi.bjstock.ui.navigation.ComposeHostActivityRule
import com.mirunubi.bjstock.ui.navigation.ShellCalls
import com.mirunubi.bjstock.ui.navigation.assertListDetailPanes
import com.mirunubi.bjstock.ui.navigation.assertNoHorizontalOverflow
import com.mirunubi.bjstock.ui.navigation.assertNoNestedScroll
import com.mirunubi.bjstock.ui.navigation.backArrow
import com.mirunubi.bjstock.ui.navigation.bounds
import com.mirunubi.bjstock.ui.navigation.hamburger
import com.mirunubi.bjstock.ui.navigation.onVisible
import com.mirunubi.bjstock.ui.navigation.railDevToolsEnabled
import com.mirunubi.bjstock.ui.navigation.railTab
import com.mirunubi.bjstock.ui.navigation.sameColumn
import com.mirunubi.bjstock.ui.navigation.scrollContaining
import com.mirunubi.bjstock.ui.navigation.countInside
import com.mirunubi.bjstock.ui.navigation.isSelected
import com.mirunubi.bjstock.ui.navigation.setShellContent
import com.mirunubi.bjstock.ui.navigation.visibleNodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 운영 · 감사 rendered inside the real adaptive shell at each width class (docs/165 §N), with a read-only fake data source. */
@RunWith(RobolectricTestRunner::class)
class AdminRenderTest {
    @get:Rule(order = 0)
    val host = ComposeHostActivityRule()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val f = AdminFixtures
    private val source = FakeSource(listOf(f.operation(3), f.operation(4)))
    private lateinit var viewModel: AdminViewModel

    private fun render(): ShellCalls {
        val calls = ShellCalls()
        val vm = AdminViewModel(source).also { viewModel = it }
        return compose.setShellContent(BJStockRoutes.ADMIN, calls) { AdminScreen(onBack = { calls.backs++ }, viewModel = vm) }
    }

    private fun title(text: String) = compose.visibleNodes(hasText(text) and !railTab).size

    /** Section titles are plain text; 오류 is also a filter-chip label (Role.Checkbox). */
    private val hasRole = SemanticsMatcher.keyIsDefined(SemanticsProperties.Role)

    /** Selected 최근 실행 rows in [pane]; the Audit / 오류 filter chips carry their own selected state. */
    private fun selectedRows(pane: DpRect) =
        compose.countInside(pane, isSelected and hasContentDescription(OPEN_DETAIL))

    private fun operationRows(): List<Pair<Int, Float>> =
        compose.onAllNodes(hasContentDescription(OPEN_DETAIL) and hasClickAction()).fetchSemanticsNodes()
            .mapIndexed { index, node -> index to node.boundsInRoot.top }

    /** The topmost 최근 실행 row; Audit / 오류 rows render below it in a single pane and in the right pane when expanded. */
    private fun firstOperationRow(): SemanticsNodeInteraction {
        val index = operationRows().minBy { it.second }.first
        return compose.onAllNodes(hasContentDescription(OPEN_DETAIL) and hasClickAction())[index]
    }

    private fun openFirstOperation() {
        firstOperationRow().performScrollTo().performClick()
        compose.waitForIdle()
        assertNotNull(viewModel.uiState.value.detail)
    }

    private fun assertSinglePaneRoot() {
        val column = compose.onNode(scrollContaining(STATUS)).bounds()
        val anchors = listOf(INFO, STATUS, OPERATIONS, AUDIT, ERRORS, ENVIRONMENT).map { compose.onVisible(hasText(it) and !hasRole).bounds() }
        anchors.zipWithNext().forEach { (above, below) -> assertTrue("sections in AdminLayout.singlePane order", below.top >= above.bottom) }
        anchors.forEach { assertTrue("section inside the single column ($column / $it)", sameColumn(column, it)) }
        compose.onNode(scrollContaining(STATUS) and hasAnyDescendant(hasText(ENVIRONMENT))).assertExists()
        assertEquals(0, selectedRows(column))
        assertEquals(1, title(ROOT_TITLE))
        compose.assertNoNestedScroll()
        compose.assertNoHorizontalOverflow()
    }

    private fun assertTwoPane(rightAnchor: String) {
        val left = compose.onNode(scrollContaining(STATUS)).bounds()
        val right = compose.onNode(scrollContaining(rightAnchor)).bounds()
        listOf(INFO, OPERATIONS).forEach { compose.onNode(scrollContaining(STATUS) and hasAnyDescendant(hasText(it, substring = true))).assertExists() }
        compose.onNode(scrollContaining(STATUS) and hasAnyDescendant(hasText(rightAnchor, substring = true))).assertDoesNotExist()
        assertListDetailPanes("ADMIN", left, right, AdminLayout.LEFT_PANE_WIDTH_DP.dp)
        compose.assertNoNestedScroll()
        compose.assertNoHorizontalOverflow()
    }

    private fun leftPane() = compose.onNode(scrollContaining(STATUS)).bounds()

    @Test
    @Config(qualifiers = AdaptiveRender.W411)
    fun compact411_singlePane_allSectionsInOrder_backArrowNeverAHamburger() {
        render()
        assertSinglePaneRoot()
        compose.onNode(backArrow).assertExists()
        compose.onNode(hamburger).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W599)
    fun compact599_isStillSinglePane() {
        render()
        assertSinglePaneRoot()
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W600)
    fun medium600_singlePaneBesideTheRail_devToolsDisabled() {
        render()
        assertSinglePaneRoot()
        compose.onNode(hamburger).assertDoesNotExist()
        assertFalse(compose.railDevToolsEnabled())
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W839)
    fun medium839_isStillSinglePane() {
        render()
        assertSinglePaneRoot()
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W840)
    fun expanded840_twoPane_rootSectionsOnTheRightWithoutADetail() {
        render()
        assertTwoPane(AUDIT)
        compose.onNode(scrollContaining(AUDIT) and hasAnyDescendant(hasText(ENVIRONMENT))).assertExists()
        assertEquals(0, selectedRows(leftPane()))
        assertEquals(1, title(ROOT_TITLE))
        assertFalse(compose.railDevToolsEnabled())
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W1280)
    fun expanded1280_twoPane() {
        render()
        assertTwoPane(AUDIT)
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W840)
    fun expanded_openOperation_detailOnTheRight_selectedRowOnTheLeft_backClosesDetailOnly() {
        val calls = render()
        openFirstOperation()

        assertTwoPane(TIMELINE)
        compose.onNodeWithText(AUDIT).assertDoesNotExist()
        assertEquals("one selected 최근 실행 row", 1, selectedRows(leftPane()))
        assertEquals(1, title(DETAIL_TITLE))
        assertEquals(0, title(ROOT_TITLE))

        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertNull(viewModel.uiState.value.detail)
        assertTwoPane(AUDIT)
        assertEquals(0, selectedRows(leftPane()))
        assertEquals(1, title(ROOT_TITLE))
        assertEquals("Back closed the detail, not the route", 0, calls.backs)
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W411)
    fun compact_openOperation_detailReplacesTheRoot_topBarArrowClosesIt_thenLeavesTheRoute() {
        val calls = render()
        openFirstOperation()
        compose.onNodeWithText(STATUS).assertDoesNotExist()
        compose.onNode(scrollContaining(TIMELINE)).assertExists()
        assertEquals(1, title(DETAIL_TITLE))
        assertEquals(0, selectedRows(compose.onNode(scrollContaining(TIMELINE)).bounds()))
        compose.assertNoHorizontalOverflow()

        compose.onNode(backArrow).performClick()
        compose.waitForIdle()
        assertNull(viewModel.uiState.value.detail)
        assertSinglePaneRoot()
        assertEquals(0, calls.backs)

        compose.onNode(backArrow).performClick()
        compose.waitForIdle()
        assertEquals("the arrow without a detail is the route's onBack", 1, calls.backs)
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W600)
    fun medium_systemBackClosesTheDetail() {
        val calls = render()
        openFirstOperation()
        compose.onNodeWithText(STATUS).assertDoesNotExist()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertNull(viewModel.uiState.value.detail)
        assertSinglePaneRoot()
        assertEquals(0, calls.backs)
        assertFalse(compose.railDevToolsEnabled())
    }

    private inner class FakeSource(private val operations: List<ForwardOperationEntity>) : AdminDataSource {
        override suspend fun status(): AdminStatusData = f.statusData(operations = operations)

        override suspend fun recentOperations(): List<ForwardOperationEntity> = operations

        override suspend fun operationDetail(operationId: Long): AdminOperationDetailData? =
            operations.firstOrNull { it.id == operationId }?.let { AdminOperationDetailData(it, emptyList(), emptyList()) }

        override suspend fun audit(): AdminAuditData = AdminAuditData(emptyList(), emptyList(), operations, emptyList())

        override suspend fun errors(): AdminErrorData = AdminErrorData(emptyList(), operations, emptyList(), emptyList())

        override suspend fun environment(): AdminEnvironmentData = f.environmentData()
    }

    private companion object {
        const val INFO = "읽기 전용 화면입니다. 실행이나 설정을 변경하지 않습니다."
        const val STATUS = "운영 상태"
        const val OPERATIONS = "최근 실행"
        const val AUDIT = "Audit"
        const val ERRORS = "오류"
        const val ENVIRONMENT = "앱 정보 · 환경"
        const val TIMELINE = "이벤트 타임라인"
        const val OPEN_DETAIL = "실행 상세 보기"
        const val ROOT_TITLE = "운영 · 감사"
        const val DETAIL_TITLE = "실행 상세"
    }
}
