package com.mirunubi.bjstock.feature.paper

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.mirunubi.bjstock.ui.navigation.AdaptiveRender
import com.mirunubi.bjstock.ui.navigation.BJStockRoutes
import com.mirunubi.bjstock.ui.navigation.ComposeHostActivityRule
import com.mirunubi.bjstock.ui.navigation.assertListDetailPanes
import com.mirunubi.bjstock.ui.navigation.assertNoHorizontalOverflow
import com.mirunubi.bjstock.ui.navigation.assertNoNestedScroll
import com.mirunubi.bjstock.ui.navigation.backArrow
import com.mirunubi.bjstock.ui.navigation.bounds
import com.mirunubi.bjstock.ui.navigation.countInside
import com.mirunubi.bjstock.ui.navigation.dpBounds
import com.mirunubi.bjstock.ui.navigation.hamburger
import com.mirunubi.bjstock.ui.navigation.onVisible
import com.mirunubi.bjstock.ui.navigation.railDevToolsEnabled
import com.mirunubi.bjstock.ui.navigation.sameColumn
import com.mirunubi.bjstock.ui.navigation.scrollContaining
import com.mirunubi.bjstock.ui.navigation.setShellContent
import com.mirunubi.bjstock.ui.navigation.visibleNodes
import com.mirunubi.bjstock.ui.navigation.width
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 모의투자 rendered inside the real adaptive shell at each width class (docs/165 §O), with the fake paper-trading source. */
@RunWith(RobolectricTestRunner::class)
class PaperRenderTest {
    @get:Rule(order = 0)
    val host = ComposeHostActivityRule()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val source = FakePaperTradingDataSource()
    private lateinit var viewModel: PaperTradingViewModel

    private fun render() {
        val vm = PaperTradingViewModel(source).also { viewModel = it }
        compose.setShellContent(BJStockRoutes.PAPER_TRADING) { PaperTradingScreen(onSelectTab = {}, viewModel = vm) }
    }

    private fun showNotice() {
        compose.runOnIdle {
            viewModel.requestRunNow()
            viewModel.confirmRunNow()
        }
        compose.waitForIdle()
        checkNotNull(viewModel.uiState.value.notice)
    }

    private val selectedMark = hasContentDescription(SELECTED)
    private val scrollable = SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy)

    private fun leftPane() = compose.onNode(scrollContaining(LIST)).bounds()

    /** Section anchors in PaperLayout.singlePane order (NOTICE is absent until an action reports one). */
    private fun assertSinglePane() {
        val column = compose.onNode(scrollContaining(LIST)).bounds()
        val anchors = listOf(LIST, AUTOMATION, ACCOUNT, RECENT_OPERATIONS).map { compose.onVisible(hasText(it)).bounds() }
        anchors.zipWithNext().forEach { (above, below) -> assertTrue("sections in PaperLayout.singlePane order", below.top >= above.bottom) }
        anchors.forEach { assertTrue("section inside the single column ($column / $it)", sameColumn(column, it)) }
        compose.onNode(scrollContaining(LIST) and hasAnyDescendant(hasText(ACCOUNT))).assertExists()
        assertEquals("selected run marked once", 1, compose.countInside(column, selectedMark))
        compose.assertNoNestedScroll()
        compose.assertNoHorizontalOverflow()
    }

    private fun assertListDetail() {
        val left = leftPane()
        val right = compose.onNode(scrollContaining(ACCOUNT)).bounds()
        listOf(AUTOMATION, RECENT_OPERATIONS).forEach { compose.onNode(scrollContaining(LIST) and hasAnyDescendant(hasText(it))).assertExists() }
        compose.onNode(scrollContaining(LIST) and hasAnyDescendant(hasText(ACCOUNT))).assertDoesNotExist()
        assertListDetailPanes("PAPER", left, right, PaperLayout.LEFT_PANE_WIDTH_DP.dp)
        assertEquals("selected run marked in the left pane", 1, compose.countInside(left, selectedMark))
        assertEquals(0, compose.countInside(right, selectedMark))
        compose.assertNoNestedScroll()
        compose.assertNoHorizontalOverflow()
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W411)
    fun compact411_singleColumn_sectionsInOrder() {
        render()
        assertSinglePane()
        compose.onNode(hamburger).assertExists()
        compose.onNode(backArrow).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W599)
    fun compact599_isStillSingleColumn() {
        render()
        assertSinglePane()
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W600)
    fun medium600_singleColumnBesideTheRail() {
        render()
        assertSinglePane()
        compose.onNode(hamburger).assertDoesNotExist()
        assertTrue(compose.railDevToolsEnabled())
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W839)
    fun medium839_isStillSingleColumn() {
        render()
        assertSinglePane()
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W840)
    fun expanded840_listDetail_selectedRunDetailOnTheRight() {
        render()
        assertEquals(3L, viewModel.uiState.value.selectedRunId)
        assertListDetail()
        assertTrue(compose.railDevToolsEnabled())
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W1280)
    fun expanded1280_listDetail() {
        render()
        assertListDetail()
    }

    /** HD-UI-T04: phone landscape is a validation candidate only; Robolectric applies no cutout / navigation-bar insets. */
    @Test
    @Config(qualifiers = AdaptiveRender.PHONE_LANDSCAPE_CANDIDATE)
    fun phoneLandscapeCandidate_classifiesAsExpanded_rightPaneStillUsable() {
        render()
        assertListDetail()
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W840)
    fun expanded_selectingAnotherRun_movesTheSelectionAndTheDetail() {
        render()
        compose.onNode(hasText(RUN_1) and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1L, viewModel.uiState.value.selectedRunId)
        assertListDetail()
        assertEquals("the mark moved to the 모의투자 1 row", 1, compose.visibleNodes(hasText(RUN_1) and selectedMark).size)
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W840)
    fun expanded_noRuns_rightPaneIsBlankButKeepsItsWidth() {
        source.runList = emptyList()
        render()
        assertNull(viewModel.uiState.value.selectedRunId)
        val left = leftPane()
        compose.onNodeWithText(ACCOUNT).assertDoesNotExist()
        val rightPanes = compose.visibleNodes(scrollable).map { it to compose.dpBounds(it) }.filter { it.second.left >= left.right }
        assertEquals("one pane right of the list", 1, rightPanes.size)
        val (node, right) = rightPanes.single()
        assertTrue("blank right pane is empty", node.children.isEmpty())
        assertTrue("blank right pane keeps a usable width (${right.width})", right.width >= 300.dp)
        compose.assertNoHorizontalOverflow()
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W840)
    fun expanded_notice_spansBothPanesAboveThem() {
        render()
        showNotice()
        val notice = checkNotNull(viewModel.uiState.value.notice)
        val left = leftPane()
        val message = compose.onNodeWithText(notice.message).bounds()
        val close = compose.onNode(hasContentDescription(NOTICE_CLOSE)).bounds()
        assertTrue("notice sits above the panes", close.bottom <= left.top + AdaptiveRender.TOLERANCE)
        assertTrue("notice starts over the left pane", message.left < left.right)
        assertTrue("notice extends past the left pane (${close.left} vs ${left.right})", close.left > left.right + 16.dp)
        assertListDetail()

        compose.onNode(hasContentDescription(NOTICE_CLOSE)).performClick()
        compose.waitForIdle()
        assertNull(viewModel.uiState.value.notice)
        assertTrue("panes move up once the notice is dismissed", leftPane().top < left.top)
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W411)
    fun compact_notice_isTheFirstSection() {
        render()
        showNotice()
        val notice = checkNotNull(viewModel.uiState.value.notice)
        val message = compose.onNodeWithText(notice.message).bounds()
        val list = compose.onNodeWithText(LIST).bounds()
        assertTrue("notice above 모의투자 목록", message.bottom <= list.top)
        compose.onNode(scrollContaining(LIST) and hasAnyDescendant(hasText(notice.message))).assertExists()
        assertSinglePane()
    }

    @Test
    @Config(qualifiers = SHORT_EXPANDED)
    fun expanded_detailPaneScrollResetsWhenTheSelectedRunChanges() {
        render()
        val detail = compose.onNode(scrollContaining(ACCOUNT))
        detail.performTouchInput { swipeUp() }
        compose.waitForIdle()
        val scrolled = detail.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("detail pane scrolled ($scrolled)", scrolled > 0f)

        compose.onNode(hasText(RUN_1) and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1L, viewModel.uiState.value.selectedRunId)
        val reset = compose.onNode(scrollContaining(ACCOUNT)).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals("new run detail starts at the top", 0f, reset)
    }

    private companion object {
        const val LIST = "모의투자 목록"
        const val AUTOMATION = "자동운영"
        const val ACCOUNT = "모의계좌"
        const val RECENT_OPERATIONS = "최근 실행 기록"
        const val SELECTED = "선택됨"
        const val NOTICE_CLOSE = "알림 닫기"
        const val RUN_1 = "모의투자 1"
        const val SHORT_EXPANDED = "w840dp-h480dp"
    }
}
