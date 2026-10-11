package com.mirunubi.bjstock.feature.strategy

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.mirunubi.bjstock.feature.strategy.template.FakeTemplatePreviewReadSource
import com.mirunubi.bjstock.feature.strategy.template.TemplatePreviewPresenter
import com.mirunubi.bjstock.feature.strategy.template.TemplatePreviewViewModel
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
import com.mirunubi.bjstock.ui.navigation.selectedInside
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

/** 전략 rendered inside the real adaptive shell at each width class (docs/165 §M), with the fake strategy / template sources. */
@RunWith(RobolectricTestRunner::class)
class StrategyRenderTest {
    @get:Rule(order = 0)
    val host = ComposeHostActivityRule()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val source = FakeStrategyDataSource()
    private lateinit var viewModel: StrategyViewModel
    private lateinit var preview: TemplatePreviewViewModel

    private fun render(): ShellCalls {
        val vm = StrategyViewModel(source).also { viewModel = it }
        val pvm = TemplatePreviewViewModel(FakeTemplatePreviewReadSource()).also { preview = it }
        return compose.setShellContent(BJStockRoutes.STRATEGY) {
            StrategyScreen(onSelectTab = {}, viewModel = vm, previewViewModel = pvm)
        }
    }

    private fun assertSinglePaneRoot() {
        val column = compose.onNode(scrollContaining(LIST)).bounds()
        compose.onNode(scrollContaining(LIST) and hasAnyDescendant(hasText(TEMPLATES))).assertExists()
        val templates = compose.onNodeWithText(TEMPLATES).bounds()
        val list = compose.onNodeWithText(LIST).bounds()
        assertTrue("template preview above the strategy list", list.top >= templates.bottom)
        assertTrue("templates in the single column", sameColumn(column, templates))
        assertTrue("strategy list in the single column", sameColumn(column, list))
        compose.onNodeWithText(StrategyLayout.EMPTY_DETAIL).assertDoesNotExist()
        assertEquals("no selected card in a single pane", 0, compose.selectedInside(column))
        compose.assertNoNestedScroll()
        compose.assertNoHorizontalOverflow()
    }

    private fun assertMasterDetail(detailText: String) {
        val master = compose.onNode(scrollContaining(LIST)).bounds()
        val detail = compose.onNode(scrollContaining(detailText)).bounds()
        compose.onNode(scrollContaining(LIST) and hasAnyDescendant(hasText(TEMPLATES))).assertExists()
        compose.onNode(scrollContaining(LIST) and hasAnyDescendant(hasText(detailText, substring = true))).assertDoesNotExist()
        assertListDetailPanes("STRATEGY", master, detail, StrategyLayout.MASTER_PANE_WIDTH_DP.dp)
        compose.assertNoNestedScroll()
        compose.assertNoHorizontalOverflow()
    }

    private fun masterPane() = compose.onNode(scrollContaining(LIST)).bounds()

    private fun openStrategyCard() {
        compose.onNode(hasText(CARD) and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()
        assertNotNull(viewModel.uiState.value.strategy)
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W411)
    fun compact411_singlePane_templatesAboveList_hamburger() {
        render()
        assertSinglePaneRoot()
        compose.onNode(hamburger).assertExists()
        compose.onNode(backArrow).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W599)
    fun compact599_isStillSinglePaneWithTheDrawer() {
        render()
        assertSinglePaneRoot()
        compose.onNode(hamburger).assertExists()
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W600)
    fun medium600_singlePaneBesideTheRail() {
        render()
        assertSinglePaneRoot()
        compose.onNode(hamburger).assertDoesNotExist()
        assertTrue(compose.railDevToolsEnabled())
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W839)
    fun medium839_isStillSinglePane() {
        render()
        assertSinglePaneRoot()
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W840)
    fun expanded840_masterDetail_emptyDetailOnTheRight() {
        render()
        assertMasterDetail(StrategyLayout.EMPTY_DETAIL)
        assertEquals(0, compose.selectedInside(masterPane()))
        compose.onNode(backArrow).assertDoesNotExist()
        assertTrue(compose.railDevToolsEnabled())
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W1280)
    fun expanded1280_masterDetail() {
        render()
        assertMasterDetail(StrategyLayout.EMPTY_DETAIL)
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W840)
    fun expanded_openStrategy_detailOnTheRight_selectedCardInTheMaster_isALayer_backClosesIt() {
        render()
        openStrategyCard()

        assertMasterDetail(VERSIONS_ACTION)
        assertEquals("one selected master row", 1, compose.selectedInside(masterPane()))
        compose.onNode(backArrow).assertExists()
        assertFalse(compose.railDevToolsEnabled())

        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertNull(viewModel.uiState.value.strategy)
        assertMasterDetail(StrategyLayout.EMPTY_DETAIL)
        assertEquals(0, compose.selectedInside(masterPane()))
        compose.onNode(backArrow).assertDoesNotExist()
        assertTrue(compose.railDevToolsEnabled())
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W840)
    fun expanded_openTemplate_detailOnTheRight_backClosesIt() {
        render()
        val template = preview.uiState.value.templates.first()
        compose.onNode(hasText(template.name) and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(template.id, preview.uiState.value.selectedId)

        assertMasterDetail(TEMPLATE_DETAIL)
        assertEquals("one selected master row", 1, compose.selectedInside(masterPane()))
        compose.onNode(backArrow).assertExists()
        assertFalse(compose.railDevToolsEnabled())

        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertNull(preview.uiState.value.selectedId)
        assertMasterDetail(StrategyLayout.EMPTY_DETAIL)
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W840)
    fun expanded_notice_spansBothPanesAboveThem() {
        render()
        openStrategyCard()
        compose.onNode(hasText(VERSIONS_ACTION) and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()
        val notice = checkNotNull(viewModel.uiState.value.notice)

        val master = masterPane()
        val message = compose.onNodeWithText(notice.message).bounds()
        val close = compose.onNode(hasContentDescription(NOTICE_CLOSE)).bounds()
        assertTrue("notice sits above the panes", close.bottom <= master.top + AdaptiveRender.TOLERANCE)
        assertTrue("notice starts over the master pane", message.left < master.right)
        assertTrue("notice extends past the master pane (${close.left} vs ${master.right})", close.left > master.right + 16.dp)
        compose.assertNoHorizontalOverflow()

        compose.onNode(hasContentDescription(NOTICE_CLOSE)).performClick()
        compose.waitForIdle()
        assertNull(viewModel.uiState.value.notice)
        assertTrue("panes move up once the notice is dismissed", masterPane().top < master.top)
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W411)
    fun compact_openStrategy_replacesTheList_backArrowWinsOverTheHamburger_backClosesIt() {
        val calls = render()
        openStrategyCard()
        compose.onNodeWithText(LIST).assertDoesNotExist()
        compose.onNode(scrollContaining(VERSIONS_ACTION)).assertExists()
        compose.onNode(backArrow).assertExists()
        compose.onNode(hamburger).assertDoesNotExist()
        compose.assertNoHorizontalOverflow()

        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertNull(viewModel.uiState.value.strategy)
        assertSinglePaneRoot()
        compose.onNode(hamburger).assertExists()
        assertEquals(emptyList<PrimaryTab>(), calls.tabs)
        assertEquals(emptyList<String>(), calls.pushes)
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W411)
    fun compact_hamburgerOpensTheDrawer_andBackClosesTheDrawerFirst() {
        render()
        assertEquals(0, compose.visibleNodes(hasText(AdaptiveRender.DEV_TOOLS)).size)
        compose.onNode(hamburger).performClick()
        compose.waitForIdle()
        assertEquals("drawer sheet slides in", 1, compose.visibleNodes(hasText(AdaptiveRender.DEV_TOOLS)).size)

        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertEquals("Back closes the drawer first", 0, compose.visibleNodes(hasText(AdaptiveRender.DEV_TOOLS)).size)
        compose.onNodeWithText(LIST).assertExists()
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W600)
    fun medium_openStrategy_disablesRailDevTools_untilBackClosesTheLayer() {
        render()
        assertTrue(compose.railDevToolsEnabled())
        openStrategyCard()
        compose.onNodeWithText(LIST).assertDoesNotExist()
        compose.onNode(backArrow).assertExists()
        assertFalse(compose.railDevToolsEnabled())

        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertTrue(compose.railDevToolsEnabled())
        assertSinglePaneRoot()
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W840)
    fun expanded_masterPaneKeepsItsBoundsWhenTheDetailChanges() {
        render()
        val before = masterPane()
        openStrategyCard()
        val after = masterPane()
        assertDpEquals("master left", before.left, after.left)
        assertDpEquals("master right", before.right, after.right)
    }

    private companion object {
        const val LIST = "전략 목록"
        const val CARD = "기본 모멘텀 전략"
        const val VERSIONS_ACTION = "새 작성본"
        const val TEMPLATE_DETAIL = "기본 정보"
        const val NOTICE_CLOSE = "알림 닫기"
        val TEMPLATES = TemplatePreviewPresenter.SECTION_TITLE
    }
}
