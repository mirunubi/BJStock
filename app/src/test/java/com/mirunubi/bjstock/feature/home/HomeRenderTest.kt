package com.mirunubi.bjstock.feature.home

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.mirunubi.bjstock.ui.navigation.AdaptiveRender
import com.mirunubi.bjstock.ui.navigation.BJStockRoutes
import com.mirunubi.bjstock.ui.navigation.ComposeHostActivityRule
import com.mirunubi.bjstock.ui.navigation.assertDpEquals
import com.mirunubi.bjstock.ui.navigation.assertNoHorizontalOverflow
import com.mirunubi.bjstock.ui.navigation.assertNoNestedScroll
import com.mirunubi.bjstock.ui.navigation.backArrow
import com.mirunubi.bjstock.ui.navigation.bounds
import com.mirunubi.bjstock.ui.navigation.dpBounds
import com.mirunubi.bjstock.ui.navigation.hamburger
import com.mirunubi.bjstock.ui.navigation.railDevToolsEnabled
import com.mirunubi.bjstock.ui.navigation.sameColumn
import com.mirunubi.bjstock.ui.navigation.scrollContaining
import com.mirunubi.bjstock.ui.navigation.setShellContent
import com.mirunubi.bjstock.ui.navigation.visibleNodes
import com.mirunubi.bjstock.ui.navigation.width
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 홈 rendered inside the real adaptive shell at each width class (docs/165 §L), with fake core / activity sources. */
@RunWith(RobolectricTestRunner::class)
class HomeRenderTest {
    @get:Rule(order = 0)
    val host = ComposeHostActivityRule()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun render(loader: HomeReadModelLoader = HomeReadModelLoader { HomeFixtures.snapshot() }) {
        val vm = HomeViewModel(loader)
        val activity = HomeActivityViewModel(FakeHomeActivitySource())
        compose.setShellContent(BJStockRoutes.HOME) { HomeScreen(onSelectTab = {}, viewModel = vm, activityViewModel = activity) }
    }

    /** The text a slot renders first: its card title, or the all-clear status line when 운영 경고 has nothing to show. */
    private fun anchorText(slot: HomeSlot): String =
        if (slot == HomeSlot.ALERTS && compose.visibleNodes(hasText(HomePresenter.ALERTS_TITLE)).isEmpty()) ALERTS_CLEAR else slot.title

    /** The slot's topmost text; 자동운영 also labels the first row of its own card, so that card carries the text twice. */
    private fun anchor(slot: HomeSlot): DpRect {
        val nodes = compose.visibleNodes(hasText(anchorText(slot))).map { compose.dpBounds(it) }.sortedBy { it.top.value }
        assertEquals("${slot.name} rendered exactly once", if (slot == HomeSlot.AUTO) 2 else 1, nodes.size)
        if (slot == HomeSlot.AUTO) assertTrue("자동운영 row label directly under the card title", nodes[1].top - nodes[0].bottom < 24.dp)
        return nodes.first()
    }

    private fun column() = compose.onNode(scrollContaining(HomeSlot.PORTFOLIO.title)).bounds()

    private fun assertSingleColumn() {
        val column = column()
        val anchors = HomeLayout.singleColumn.map { it to anchor(it) }
        anchors.zipWithNext().forEach { (above, below) ->
            assertTrue("${above.first} above ${below.first}", below.second.top >= above.second.bottom)
        }
        anchors.forEach { (slot, r) -> assertTrue("$slot in the single column ($column / $r)", sameColumn(column, r)) }
        compose.assertNoNestedScroll()
        compose.assertNoHorizontalOverflow()
    }

    /** Two equal columns inside the one scroll container: 16dp outer padding, 16dp gap. */
    private fun columns(): Pair<ClosedRange<Float>, ClosedRange<Float>> {
        val scroll = column()
        val columnWidth = (scroll.width - 32.dp - 16.dp) / 2
        val leftStart = scroll.left + 16.dp
        val rightStart = leftStart + columnWidth + 16.dp
        println("HOME-RENDER column=$columnWidth leftStart=$leftStart rightStart=$rightStart")
        return (leftStart.value..(leftStart + columnWidth).value) to (rightStart.value..(rightStart + columnWidth).value)
    }

    private fun DpRect.inside(range: ClosedRange<Float>): Boolean =
        left.value >= range.start - AdaptiveRender.TOLERANCE.value && right.value <= range.endInclusive + AdaptiveRender.TOLERANCE.value

    private fun assertTwoColumns() {
        val (leftColumn, rightColumn) = columns()
        val left = HomeLayout.left.map { it to anchor(it) }
        val right = HomeLayout.right.map { it to anchor(it) }
        left.forEach { (slot, r) -> assertTrue("$slot in the left column ($r)", r.inside(leftColumn)) }
        right.forEach { (slot, r) -> assertTrue("$slot in the right column ($r)", r.inside(rightColumn)) }
        (left.zipWithNext() + right.zipWithNext()).forEach { (above, below) ->
            assertTrue("${above.first} above ${below.first}", below.second.top >= above.second.bottom)
        }
        assertDpEquals("columns start together", left.first().second.top, right.first().second.top)
        assertEquals(HomeSlot.entries.toSet(), (HomeLayout.left + HomeLayout.right).toSet())
        compose.assertNoNestedScroll()
        compose.assertNoHorizontalOverflow()
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W411)
    fun compact411_singleColumn_allSlotsOnceInOrder() {
        render()
        assertSingleColumn()
        compose.onNode(hamburger).assertExists()
        compose.onNode(backArrow).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W599)
    fun compact599_isStillSingleColumn() {
        render()
        assertSingleColumn()
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W600)
    fun medium600_singleColumnBesideTheRail() {
        render()
        assertSingleColumn()
        compose.onNode(hamburger).assertDoesNotExist()
        assertTrue(compose.railDevToolsEnabled())
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W839)
    fun medium839_isStillSingleColumn() {
        render()
        assertSingleColumn()
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W840)
    fun expanded840_twoColumns_accountAndTradesLeft_signalsAndOperationsRight() {
        render()
        assertTwoColumns()
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W1280)
    fun expanded1280_twoColumns() {
        render()
        assertTwoColumns()
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W411)
    fun compact_coreFailure_noticeOnlyInThePortfolioCard_activityStillRendered() {
        render { throw IllegalStateException("core load failed") }
        assertEquals(1, compose.visibleNodes(hasText(HomePresenter.CORE_SECTIONS_UNAVAILABLE)).size)
        HomeSlot.entries.filter { it.isCore && it != HomeSlot.PORTFOLIO }.forEach {
            assertEquals("$it hidden on core failure", 0, compose.visibleNodes(hasText(it.title)).size)
        }
        HomeSlot.entries.filter { !it.isCore }.forEach { anchor(it) }
        compose.assertNoHorizontalOverflow()
    }

    @Test
    @Config(qualifiers = AdaptiveRender.W840)
    fun expanded_coreFailure_rightColumnLeadsWithTheNotice() {
        render { throw IllegalStateException("core load failed") }
        val (leftColumn, rightColumn) = columns()
        val notices = compose.visibleNodes(hasText(HomePresenter.CORE_SECTIONS_UNAVAILABLE)).map { compose.dpBounds(it) }
        assertEquals("portfolio card text + right-column notice", 2, notices.size)
        val leftNotice = notices.single { it.inside(leftColumn) }
        val rightNotice = notices.single { it.inside(rightColumn) }
        assertTrue("the notice leads the right column", rightNotice.top <= anchor(HomeSlot.PORTFOLIO).top + 4.dp)
        assertTrue("left copy sits inside the portfolio card, below its title", leftNotice.top > rightNotice.top)
        HomeLayout.right.filter { !it.isCore }.forEach { slot ->
            val r = anchor(slot)
            assertTrue("$slot in the right column", r.inside(rightColumn))
            assertTrue("$slot below the notice", r.top >= rightNotice.bottom)
        }
        HomeSlot.entries.filter { it.isCore && it != HomeSlot.PORTFOLIO }.forEach {
            assertEquals("$it hidden on core failure", 0, compose.visibleNodes(hasText(it.title)).size)
        }
        compose.assertNoHorizontalOverflow()
    }

    private companion object {
        const val ALERTS_CLEAR = "운영 상태 정상"
    }
}
