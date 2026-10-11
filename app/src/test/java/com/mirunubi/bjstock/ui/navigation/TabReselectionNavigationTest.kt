package com.mirunubi.bjstock.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mirunubi.bjstock.ui.theme.BJStockTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Rail tab reselection with a pushed subordinate route (UI-ADAPT-REVIEW-01 L-3): the real shell and [navigateToTab], and
 * MainActivity's `open` (`navigate(route) { launchSingleTop = true }`). Destinations are placeholders holding a
 * `rememberSaveable` counter, so saved / restored back-stack state is observable, and a counted ViewModel, so cleared vs.
 * retained back-stack entries are too. 홈 always lands on its root (HD-NAV-T1) and a stack owned by 홈 is discarded, not
 * saved (HD-NAV-T1-02); every other tab restores its saved stack.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = AdaptiveRender.W840)
class TabReselectionNavigationTest {
    @get:Rule(order = 0)
    val host = ComposeHostActivityRule()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: NavHostController
    private val ledger = ViewModelLedger()

    private fun render(restoration: StateRestorationTester? = null) {
        val content: @Composable () -> Unit = {
            BJStockTheme(darkTheme = false) {
                val navController = rememberNavController().also { nav = it }
                val open: (String) -> Unit = { route -> navController.navigate(route) { launchSingleTop = true } }
                BJStockAdaptiveShell(navController = navController, onSelectTab = { navController.navigateToTab(it) }, onOpen = open) {
                    NavHost(navController = navController, startDestination = BJStockRoutes.START) {
                        PrimaryTab.entries.forEach { tab -> composable(tab.route) { Destination(tab.route, ledger) } }
                        composable(BJStockRoutes.ADMIN) { Destination(BJStockRoutes.ADMIN, ledger) }
                        composable(BJStockRoutes.SETTINGS) { Destination(BJStockRoutes.SETTINGS, ledger) }
                    }
                }
            }
        }
        if (restoration != null) restoration.setContent(content) else compose.setContent(content)
        compose.waitForIdle()
    }

    private fun route() = nav.currentDestination?.route

    private fun tap(label: String) {
        compose.railItem(label).performClick()
        compose.waitForIdle()
    }

    private fun bump(route: String, times: Int) = repeat(times) {
        compose.onNodeWithText("count:$route:", substring = true).performClick()
        compose.waitForIdle()
    }

    private fun assertCount(route: String, count: Int) {
        assertEquals(route, route())
        compose.onNodeWithText("count:$route:$count").assertExists()
    }

    private fun back() {
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
    }

    @Test
    fun sameTabWithoutAPush_isANoOp_andKeepsItsState() {
        render()
        tap(STRATEGY)
        bump(BJStockRoutes.STRATEGY, 2)
        tap(STRATEGY)
        assertCount(BJStockRoutes.STRATEGY, 2)
        back()
        assertEquals(BJStockRoutes.HOME, route())
    }

    @Test
    fun switchingTabs_savesAndRestoresEachTabsState() {
        render()
        tap(STRATEGY)
        bump(BJStockRoutes.STRATEGY, 2)
        tap(PAPER)
        bump(BJStockRoutes.PAPER_TRADING, 1)
        tap(STRATEGY)
        assertCount(BJStockRoutes.STRATEGY, 2)
        tap(PAPER)
        assertCount(BJStockRoutes.PAPER_TRADING, 1)
        back()
        assertEquals("one tab above the start destination", BJStockRoutes.HOME, route())
    }

    @Test
    fun sameTabReselect_withAPushedAdmin_restoresThePushedStack_insteadOfReturningToTheTabRoot() {
        render()
        tap(STRATEGY)
        bump(BJStockRoutes.STRATEGY, 2)
        tap(ADMIN)
        assertEquals(BJStockRoutes.ADMIN, route())
        compose.railItem(ADMIN).assertIsSelected()
        compose.railItem(STRATEGY).assertIsNotSelected()

        tap(STRATEGY)
        assertEquals("navigateToTab pops to start with saveState, then restores the saved [전략, 운영 · 감사] stack", BJStockRoutes.ADMIN, route())
        back()
        assertCount(BJStockRoutes.STRATEGY, 2)
        back()
        assertEquals(BJStockRoutes.HOME, route())
    }

    @Test
    fun anotherTab_fromAPushedAdmin_thenBackToTheTab_restoresTheAdminAboveIt() {
        render()
        tap(STRATEGY)
        bump(BJStockRoutes.STRATEGY, 2)
        tap(ADMIN)
        tap(PAPER)
        assertEquals(BJStockRoutes.PAPER_TRADING, route())

        tap(STRATEGY)
        assertEquals(BJStockRoutes.ADMIN, route())
        back()
        assertCount(BJStockRoutes.STRATEGY, 2)
        back()
        assertEquals(BJStockRoutes.HOME, route())
    }

    @Test
    fun paperReselect_withAPushedAdmin_restoresThePushedStack_likeEveryNonHomeTab() {
        render()
        tap(PAPER)
        bump(BJStockRoutes.PAPER_TRADING, 1)
        tap(ADMIN)
        tap(PAPER)
        assertEquals(BJStockRoutes.ADMIN, route())
        back()
        assertCount(BJStockRoutes.PAPER_TRADING, 1)
    }

    @Test
    fun home_fromAnotherTab_landsOnTheHomeRoot() {
        render()
        tap(STRATEGY)
        tap(HOME)
        assertEquals(BJStockRoutes.HOME, route())
    }

    /** HD-NAV-T1: 홈 always lands on its root; a destination pushed above 홈 is not restored. */
    @Test
    fun home_fromAnAdminPushedOnHome_landsOnTheHomeRoot_evenWhenTappedTwice() {
        render()
        bump(BJStockRoutes.HOME, 1)
        tap(ADMIN)
        bump(BJStockRoutes.ADMIN, 3)

        tap(HOME)
        assertCount(BJStockRoutes.HOME, 1)
        tap(HOME)
        assertCount(BJStockRoutes.HOME, 1)

        tap(ADMIN)
        assertCount(BJStockRoutes.ADMIN, 0)
        back()
        assertEquals("nothing left between the reopened 운영 · 감사 and 홈", BJStockRoutes.HOME, route())
    }

    /** HD-NAV-T1: 홈 lands on its root, and the tab left behind keeps its saved stack. */
    @Test
    fun home_fromAnAdminPushedOnAnotherTab_landsOnTheHomeRoot_andThatTabKeepsItsStack() {
        render()
        tap(STRATEGY)
        bump(BJStockRoutes.STRATEGY, 2)
        tap(ADMIN)
        bump(BJStockRoutes.ADMIN, 1)

        tap(HOME)
        assertEquals(BJStockRoutes.HOME, route())

        tap(STRATEGY)
        assertCount(BJStockRoutes.ADMIN, 1)
        back()
        assertCount(BJStockRoutes.STRATEGY, 2)
        back()
        assertEquals(BJStockRoutes.HOME, route())
    }

    /** HD-NAV-T1-02: a subordinate pushed above 홈 is discarded, not saved, so repeated 홈 cycles retain no ViewModels. */
    @Test
    fun home_fromAnAdminPushedOnHome_clearsItsViewModel_everyCycle() {
        render()
        repeat(CYCLES) {
            tap(ADMIN)
            assertEquals(BJStockRoutes.ADMIN, route())
            tap(HOME)
            assertEquals(BJStockRoutes.HOME, route())
        }
        assertEquals(CYCLES, ledger.created(BJStockRoutes.ADMIN))
        assertEquals("no saved, unreachable 운영 · 감사 entry keeps its ViewModel", 0, ledger.live(BJStockRoutes.ADMIN))
        assertEquals("the 홈 root entry is never popped", 1, ledger.created(BJStockRoutes.HOME))
        assertEquals(1, ledger.live(BJStockRoutes.HOME))
    }

    @Test
    fun home_fromSettingsPushedOnHome_clearsItsViewModel_everyCycle() {
        render()
        bump(BJStockRoutes.HOME, 1)
        repeat(CYCLES) {
            tap(SETTINGS)
            assertEquals(BJStockRoutes.SETTINGS, route())
            tap(HOME)
            assertCount(BJStockRoutes.HOME, 1)
        }
        assertEquals(CYCLES, ledger.created(BJStockRoutes.SETTINGS))
        assertEquals(0, ledger.live(BJStockRoutes.SETTINGS))
    }

    /** Leaving a 홈-owned subordinate for another tab discards it too; the other tab's own saved stack is still reused. */
    @Test
    fun anotherTab_fromAnAdminPushedOnHome_clearsItsViewModel_andThatTabStillRestoresItsOwn() {
        render()
        tap(STRATEGY)
        bump(BJStockRoutes.STRATEGY, 2)
        repeat(CYCLES) {
            tap(HOME)
            tap(ADMIN)
            tap(STRATEGY)
            assertCount(BJStockRoutes.STRATEGY, 2)
        }
        assertEquals(CYCLES, ledger.created(BJStockRoutes.ADMIN))
        assertEquals(0, ledger.live(BJStockRoutes.ADMIN))
        assertEquals("전략 is restored, never recreated", 1, ledger.created(BJStockRoutes.STRATEGY))
        assertEquals(1, ledger.live(BJStockRoutes.STRATEGY))
    }

    /** A subordinate owned by another tab stays saved with that tab, and restoring reuses the very same ViewModels. */
    @Test
    fun home_fromAnAdminPushedOnAnotherTab_keepsThatTabsSavedViewModels_andRestoreReusesThem() {
        render()
        tap(STRATEGY)
        tap(ADMIN)
        bump(BJStockRoutes.ADMIN, 1)
        repeat(CYCLES) {
            tap(HOME)
            assertEquals(1, ledger.live(BJStockRoutes.STRATEGY))
            assertEquals("saved with 전략's stack", 1, ledger.live(BJStockRoutes.ADMIN))
            tap(STRATEGY)
            assertCount(BJStockRoutes.ADMIN, 1)
        }
        assertEquals(1, ledger.created(BJStockRoutes.STRATEGY))
        assertEquals(1, ledger.created(BJStockRoutes.ADMIN))

        tap(PAPER)
        tap(ADMIN)
        tap(PAPER)
        assertEquals(BJStockRoutes.ADMIN, route())
        assertEquals("Paper's own 운영 · 감사 is a second entry, restored on reselect", 2, ledger.created(BJStockRoutes.ADMIN))
        assertEquals(2, ledger.live(BJStockRoutes.ADMIN))
    }

    /** Saved-state recreation: the 홈 root and 전략's saved stack survive it; no discarded subordinate comes back. */
    @Test
    fun stateRestoration_keepsTheHomeRootAndAnotherTabsSavedStack_andNoHomeOwnedSubordinate() {
        val restoration = StateRestorationTester(compose)
        render(restoration)
        bump(BJStockRoutes.HOME, 1)
        repeat(CYCLES) {
            tap(ADMIN)
            tap(HOME)
        }
        tap(STRATEGY)
        bump(BJStockRoutes.STRATEGY, 2)
        tap(ADMIN)
        bump(BJStockRoutes.ADMIN, 3)
        tap(HOME)

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        assertCount(BJStockRoutes.HOME, 1)
        assertEquals("only 전략's saved 운영 · 감사 is alive", 1, ledger.live(BJStockRoutes.ADMIN))
        tap(STRATEGY)
        assertCount(BJStockRoutes.ADMIN, 3)
        back()
        assertCount(BJStockRoutes.STRATEGY, 2)
        back()
        assertCount(BJStockRoutes.HOME, 1)
    }

    private companion object {
        const val CYCLES = 5
        val HOME = PrimaryTab.HOME.label
        val STRATEGY = PrimaryTab.STRATEGY.label
        val PAPER = PrimaryTab.PAPER_TRADING.label
        val ADMIN = DrawerMenu.admin.label
        val SETTINGS = DrawerMenu.settings.label
    }
}

/** Per-route count of placeholder ViewModels created, and of those not yet cleared. */
private class ViewModelLedger {
    private val created = mutableMapOf<String, Int>()
    private val live = mutableMapOf<String, Int>()

    fun created(route: String) = created[route] ?: 0
    fun live(route: String) = live[route] ?: 0

    fun onCreated(route: String) {
        created[route] = created(route) + 1
        live[route] = live(route) + 1
    }

    fun onCleared(route: String) {
        live[route] = live(route) - 1
    }
}

private class TrackedViewModel(private val route: String, private val ledger: ViewModelLedger) : ViewModel() {
    init {
        ledger.onCreated(route)
    }

    override fun onCleared() = ledger.onCleared(route)
}

@Composable
private fun Destination(route: String, ledger: ViewModelLedger) {
    viewModel { TrackedViewModel(route, ledger) }
    var count by rememberSaveable { mutableIntStateOf(0) }
    Column {
        Text("route:$route")
        Button(onClick = { count++ }) { Text("count:$route:$count") }
    }
}
