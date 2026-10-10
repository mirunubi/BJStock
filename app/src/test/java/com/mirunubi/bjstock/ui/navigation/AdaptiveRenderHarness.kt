package com.mirunubi.bjstock.ui.navigation

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.mirunubi.bjstock.ui.theme.BJStockTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.Shadows.shadowOf

/**
 * Rendered adaptive tests (UI-ADAPT-TEST-01). The window width comes from the Robolectric `w…dp` qualifier, so the width
 * class is resolved by the real shell exactly as on a device: `screenWidthDp` -> [NavWidthClass] -> [LocalNavChrome].
 */
object AdaptiveRender {
    /** Window qualifiers used by the rendered tests; the height is generous so pane content is laid out, not scrolled. */
    const val W411 = "w411dp-h1600dp"
    const val W599 = "w599dp-h1600dp"
    const val W600 = "w600dp-h1600dp"
    const val W839 = "w839dp-h1600dp"
    const val W840 = "w840dp-h1600dp"
    const val W1280 = "w1280dp-h1600dp"

    /** Phone landscape is a validation candidate only (HD-UI-T04), not a supported Expanded target. */
    const val PHONE_LANDSCAPE_CANDIDATE = "w891dp-h411dp"

    const val BACK = "뒤로"
    const val MENU = "메뉴 열기"
    const val DEV_TOOLS = "개발자도구"

    /** Layout tolerance for Dp comparisons after pixel rounding. */
    val TOLERANCE: Dp = 1.dp
}

/**
 * Registers the Compose test host activity with Robolectric's package manager. The resource APK Robolectric reads carries
 * the app's own debug manifest, so a test-library manifest cannot declare it, and adding it to the debug APK is out of
 * scope. Must run before the compose rule launches the activity (`@get:Rule(order = 0)`).
 */
class ComposeHostActivityRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app.packageManager).addOrUpdateActivity(
                ActivityInfo().apply {
                    name = ComponentActivity::class.java.name
                    packageName = app.packageName
                },
            )
            base.evaluate()
        }
    }
}

/** Records what the screen asked the shell to do; the single-route NavHost does not navigate anywhere. */
class ShellCalls {
    val tabs = mutableListOf<PrimaryTab>()
    val pushes = mutableListOf<String>()
    var backs = 0
}

/** The real [BJStockAdaptiveShell] around a NavHost with [route] as its only destination, the way MainActivity hosts screens. */
fun ComposeContentTestRule.setShellContent(route: String, calls: ShellCalls = ShellCalls(), screen: @Composable () -> Unit): ShellCalls {
    setContent {
        BJStockTheme(darkTheme = false) {
            val navController: NavHostController = rememberNavController()
            BJStockAdaptiveShell(
                navController = navController,
                onSelectTab = { calls.tabs += it },
                onOpen = { calls.pushes += it },
            ) {
                NavHost(navController = navController, startDestination = route) {
                    composable(route) { screen() }
                }
            }
        }
    }
    waitForIdle()
    return calls
}

fun SemanticsNodeInteraction.bounds(): DpRect = getUnclippedBoundsInRoot()

val DpRect.width: Dp get() = right - left

fun assertDpEquals(message: String, expected: Dp, actual: Dp) {
    assertTrue("$message: expected $expected, was $actual", (expected - actual).value.let { kotlin.math.abs(it) } <= AdaptiveRender.TOLERANCE.value)
}

/** A vertically scrollable container (a pane or the single-pane column) that contains text [text]. The rail is also a
 *  scroll container, so [text] must not be a substring of a rail label. */
fun scrollContaining(text: String): SemanticsMatcher =
    SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy) and hasAnyDescendant(hasText(text, substring = true))

/** A scrollable container nested inside another scrollable container; rendered screens must have none in their content. */
val nestedScroll: SemanticsMatcher =
    SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy) and hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy))

val backArrow: SemanticsMatcher = hasContentDescription(AdaptiveRender.BACK)
val hamburger: SemanticsMatcher = hasContentDescription(AdaptiveRender.MENU)

/** Layout bounds before clipping; `boundsInRoot` is clipped to the scroll viewport, which hides content below the fold. */
private val SemanticsNode.unclippedBounds: Rect get() = Rect(positionInRoot, size.toSize())

private fun ComposeContentTestRule.isVisible(): (SemanticsNode) -> Boolean {
    val rootWidth = onAllNodes(SemanticsMatcher("root") { it.isRoot }).fetchSemanticsNodes().first().size.width
    return { node -> node.unclippedBounds.let { it.right > 0f && it.left < rootWidth && it.width > 0f } }
}

/** Nodes laid out horizontally inside the window, including scrolled-out content; the closed modal drawer sheet sits
 *  entirely left of x = 0. */
fun ComposeContentTestRule.visibleNodes(matcher: SemanticsMatcher, useUnmergedTree: Boolean = false): List<SemanticsNode> =
    onAllNodes(matcher, useUnmergedTree).fetchSemanticsNodes().filter(isVisible())

/** The first visible node matching [matcher], for labels that the off-screen drawer sheet repeats. */
fun ComposeContentTestRule.onVisible(matcher: SemanticsMatcher): SemanticsNodeInteraction {
    val index = onAllNodes(matcher).fetchSemanticsNodes().indexOfFirst(isVisible())
    assertTrue("a visible node matching ${matcher.description}", index >= 0)
    return onAllNodes(matcher)[index]
}

val railTab: SemanticsMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

/** The visible rail item labelled [label]; the drawer copy of the same label is composed off-screen and ignored. */
fun ComposeContentTestRule.railItem(label: String): SemanticsNodeInteraction = onVisible(hasText(label) and railTab)

/** The rail's Developer Tools item; the drawer copy of the same label is composed off-screen and ignored. */
fun ComposeContentTestRule.railDevToolsEnabled(): Boolean {
    val items = visibleNodes(hasText(AdaptiveRender.DEV_TOOLS) and railTab)
    assertEquals("one visible rail Developer Tools item", 1, items.size)
    return !items.single().config.contains(SemanticsProperties.Disabled)
}

val isSelected: SemanticsMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)

fun ComposeContentTestRule.dpBounds(node: SemanticsNode): DpRect =
    with(density) { node.unclippedBounds.let { DpRect(it.left.toDp(), it.top.toDp(), it.right.toDp(), it.bottom.toDp()) } }

fun DpRect.encloses(inner: DpRect): Boolean {
    val tol = AdaptiveRender.TOLERANCE
    return inner.left >= left - tol && inner.right <= right + tol && inner.top >= top - tol && inner.bottom <= bottom + tol
}

/** Visible nodes matching [matcher] laid out inside [pane]. */
fun ComposeContentTestRule.countInside(pane: DpRect, matcher: SemanticsMatcher): Int =
    visibleNodes(matcher).count { pane.encloses(dpBounds(it)) }

/** Selected nodes laid out inside [pane]; rail and bottom-bar items are selectable too, so a pane bound is required. */
fun ComposeContentTestRule.selectedInside(pane: DpRect): Int = countInside(pane, isSelected)

fun ComposeContentTestRule.assertNoNestedScroll() {
    assertEquals("nested scroll containers", 0, onAllNodes(nestedScroll).fetchSemanticsNodes().count { it.boundsInRoot.right > 0f })
}

/** Expanded list-detail geometry (docs/165 §M–§P): fixed-width left pane, 16dp gap, right pane beside it with usable width. */
fun assertListDetailPanes(tag: String, left: DpRect, right: DpRect, leftWidth: Dp) {
    assertDpEquals("$tag left pane width", leftWidth, left.width)
    assertDpEquals("$tag gap", 16.dp, right.left - left.right)
    assertTrue("$tag right pane renders beside the left pane, not over it", right.left >= left.right)
    assertTrue("$tag right pane has a usable width (${right.width})", right.width >= 300.dp)
    assertDpEquals("$tag panes share a top edge", left.top, right.top)
    println("$tag-RENDER left=${left.width} gap=${right.left - left.right} right=${right.width} railAndPadding=${left.left}")
}

/** True when [inner] lies horizontally inside [outer]'s column (cards and icons add their own inner offsets). */
fun sameColumn(outer: DpRect, inner: DpRect): Boolean =
    inner.left >= outer.left - AdaptiveRender.TOLERANCE && inner.right <= outer.right + AdaptiveRender.TOLERANCE

/**
 * No laid-out node extends past the window's right edge or starts left of it (unclipped bounds; the window clip would
 * otherwise hide the overflow). Content of horizontal scrollers and the closed drawer sheet (entirely at x <= 0) is exempt.
 */
fun ComposeContentTestRule.assertNoHorizontalOverflow() {
    val root = onAllNodes(SemanticsMatcher("root") { it.isRoot }).fetchSemanticsNodes().first()
    val width = root.size.width.toFloat()
    val slack = 1.5f * density.density
    val inHorizontalScroller = hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
    val offenders = onAllNodes(!inHorizontalScroller, useUnmergedTree = true).fetchSemanticsNodes()
        .filter { it.unclippedBounds.right > 0f }
        .filter { it.unclippedBounds.right > width + slack || it.unclippedBounds.left < -slack }
        .map { it.config.toString().take(80) + " " + it.unclippedBounds }
    assertEquals("nodes past the window edge", emptyList<String>(), offenders)
}
