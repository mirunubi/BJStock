package com.mirunubi.bjstock.feature.performance

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.mirunubi.bjstock.ui.navigation.AdaptiveRender
import com.mirunubi.bjstock.ui.navigation.BJStockRoutes
import com.mirunubi.bjstock.ui.navigation.ComposeHostActivityRule
import com.mirunubi.bjstock.ui.navigation.PrimaryTab
import com.mirunubi.bjstock.ui.navigation.ShellCalls
import com.mirunubi.bjstock.ui.navigation.assertDpEquals
import com.mirunubi.bjstock.ui.navigation.assertListDetailPanes
import com.mirunubi.bjstock.ui.navigation.assertNoHorizontalOverflow
import com.mirunubi.bjstock.ui.navigation.assertNoNestedScroll
import com.mirunubi.bjstock.ui.navigation.backArrow
import com.mirunubi.bjstock.ui.navigation.bounds
import com.mirunubi.bjstock.ui.navigation.hamburger
import com.mirunubi.bjstock.ui.navigation.railDevToolsEnabled
import com.mirunubi.bjstock.ui.navigation.sameColumn
import com.mirunubi.bjstock.ui.navigation.scrollContaining
import com.mirunubi.bjstock.ui.navigation.setShellContent
import com.mirunubi.bjstock.ui.navigation.visibleNodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 성과 rendered inside the real adaptive shell at each width class (docs/165 §P), with the read-only fake data source. */
@RunWith(RobolectricTestRunner::class)
class PerformanceRenderTest {
    @get:Rule(order = 0)
    val host = ComposeHostActivityRule()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val source = FakePerformanceDataSource()
    private lateinit var viewModel: PerformanceViewModel

    private fun render(): ShellCalls {
        val vm = PerformanceViewModel(source).also { viewModel = it }
        return compose.setShellContent(BJStockRoutes.PERFORMANCE) { PerformanceScreen(onSelectTab = {}, viewModel = vm) }
    }

    private fun assertSingleColumn() {
        compose.onNode(scrollContaining(SELECTOR)).assertExists()
        compose.onNode(scrollContaining(SELECTOR) and hasAnyText(DETAIL)).assertExists()
        val column = compose.onNode(scrollContaining(SELECTOR)).bounds()
        val selector = compose.onNodeWithText(SELECTOR).bounds()
        val detail = compose.onNodeWithText(DETAIL).bounds()
        assertTrue("detail below the selector", detail.top >= selector.bottom)
        assertTrue("selector in the single column", sameColumn(column, selector))
        assertTrue("detail in the same column ($column / $detail)", sameColumn(column, detail))
        compose.assertNoNestedScroll()
        compose.assertNoHorizontalOverflow()
    }

    private fun assertListDetail() {
        val left = compose.onNode(scrollContaining(SELECTOR)).bounds()
        val right = compose.onNode(scrollContaining(DETAIL)).bounds()
        compose.onNode(scrollContaining(SELECTOR) and hasAnyText(DETAIL)).assertDoesNotExist()
        assertListDetailPanes("PERF", left, right, PerformanceLayout.LEFT_PANE_WIDTH_DP.dp)
        compose.assertNoNestedScroll()
        compose.assertNoHorizontalOverflow()
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W411)
    fun compact411_singleColumn_selectorAboveDetail_hamburger() {
        render()
        assertSingleColumn()
        compose.onNode(hamburger).assertExists()
        compose.onNode(backArrow).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W599)
    fun compact599_isStillSingleColumnWithTheDrawer() {
        render()
        assertSingleColumn()
        compose.onNode(hamburger).assertExists()
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W600)
    fun medium600_singleColumnBesideTheRail() {
        render()
        assertSingleColumn()
        compose.onNode(hamburger).assertDoesNotExist()
        assertEquals(1, compose.visibleNodes(hasText(AdaptiveRender.DEV_TOOLS)).size)
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W839)
    fun medium839_isStillSingleColumn() {
        render()
        assertSingleColumn()
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W840)
    fun expanded840_listDetail_360LeftPane_usableRightPane() {
        render()
        assertListDetail()
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W1280)
    fun expanded1280_listDetail() {
        render()
        assertListDetail()
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W840)
    fun expanded_selectingAnotherRun_rendersItsDetailOnTheRight() {
        render()
        compose.onNode(hasText("지난 운영") and hasClickAction()).performClick()
        compose.waitForIdle()
        assertEquals(1L, viewModel.uiState.value.selectedRunId)
        assertListDetail()
        assertEquals(listOf("runs", "detail:3", "detail:1"), source.reads)
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W840)
    fun expanded_noRuns_rightPaneIsBlank() {
        source.runs = emptyList()
        render()
        compose.onNode(scrollContaining(SELECTOR)).assertExists()
        compose.onNodeWithText(DETAIL).assertDoesNotExist()
        assertEquals(PerformanceDetailState.None, viewModel.uiState.value.detail)
        compose.assertNoHorizontalOverflow()
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W840)
    fun expanded_comparison_replacesBothPanesFullWidth_andIsALayer() {
        render()
        assertTrue(compose.railDevToolsEnabled())
        val leftPane = compose.onNode(scrollContaining(SELECTOR)).bounds()
        val rightPane = compose.onNode(scrollContaining(DETAIL)).bounds()

        compose.onAllNodes(hasText(COMPARE) and hasClickAction()).fetchSemanticsNodes().let { assertEquals(1, it.size) }
        compose.onNode(hasText(COMPARE) and hasClickAction()).performClick()
        compose.waitForIdle()

        assertTrue(viewModel.uiState.value.comparison.open)
        compose.onNodeWithText(SELECTOR).assertDoesNotExist()
        compose.onNodeWithText(DETAIL).assertDoesNotExist()
        // The comparison column pads inside its scroll container; the panes' Row pads outside them.
        val comparison = compose.onNode(scrollContaining(CLOSE)).bounds()
        assertDpEquals("comparison spans from the left content edge", leftPane.left - 16.dp, comparison.left)
        assertDpEquals("comparison spans to the right content edge", rightPane.right + 16.dp, comparison.right)
        compose.onNode(backArrow).assertExists()
        assertFalse(compose.railDevToolsEnabled())
        compose.assertNoHorizontalOverflow()

        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertTrue(!viewModel.uiState.value.comparison.open)
        assertEquals(3L, viewModel.uiState.value.selectedRunId)
        compose.onNode(backArrow).assertDoesNotExist()
        assertTrue(compose.railDevToolsEnabled())
        assertListDetail()
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W411)
    fun compact_comparison_replacesTheRoot_backArrowWinsOverTheHamburger_andBackClosesIt() {
        val calls = render()
        compose.onNode(hasText(COMPARE) and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(SELECTOR).assertDoesNotExist()
        compose.onNode(backArrow).assertExists()
        compose.onNode(hamburger).assertDoesNotExist()

        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertTrue(!viewModel.uiState.value.comparison.open)
        compose.onNode(hamburger).assertExists()
        assertEquals(emptyList<PrimaryTab>(), calls.tabs)
        assertEquals(emptyList<String>(), calls.pushes)
    }

    private fun hasAnyText(text: String) = androidx.compose.ui.test.hasAnyDescendant(hasText(text))

    private companion object {
        const val SELECTOR = "모의투자 선택"
        const val COMPARE = "모의투자 비교"
        const val DETAIL = "핵심 성과"
        const val CLOSE = "닫기"
    }
}
