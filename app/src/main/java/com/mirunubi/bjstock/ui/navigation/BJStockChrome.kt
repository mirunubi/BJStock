package com.mirunubi.bjstock.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.mirunubi.bjstock.ui.icons.BJStockIcons

/**
 * Chrome state provided by the adaptive shell to the shared top / bottom bars.
 * [openDrawer] is null when no drawer may be opened from a top bar (rail layouts, or outside the shell).
 * [drawerOpen] is read-only drawer state for screens; their layer back handlers are disabled while it is true.
 * [reportInScreenLayer] tells the shell whether the screen owning a back-stack entry has an in-screen layer open.
 */
@Immutable
data class NavChrome(
    val widthClass: NavWidthClass,
    val openDrawer: (() -> Unit)?,
    val drawerOpen: Boolean = false,
    val reportInScreenLayer: (owner: Any, open: Boolean) -> Unit = { _, _ -> },
)

val LocalNavChrome = compositionLocalOf { NavChrome(NavWidthClass.COMPACT, openDrawer = null) }

/** Leading top-bar icon of a top-level screen: an in-screen layer's back arrow always wins over the hamburger. */
enum class TopBarNavIcon {
    BACK,
    MENU,
    NONE,
    ;

    companion object {
        fun resolve(hasBack: Boolean, drawerAvailable: Boolean): TopBarNavIcon = when {
            hasBack -> BACK
            drawerAvailable -> MENU
            else -> NONE
        }
    }
}

/** Back handler for a screen's in-screen layer (Stocks detail, Strategy detail / template, 성과 comparison); off while the drawer is open. */
@Composable
fun LayerBackHandler(hasInScreenLayer: Boolean, onBack: () -> Unit) {
    val drawerOpen = LocalNavChrome.current.drawerOpen
    BackHandler(enabled = BackPriority.layerHandlerEnabled(hasInScreenLayer, drawerOpen), onBack = onBack)
}

/** Compact only; rail layouts render no bottom bar. 종목 / 성과 show it with no item selected. */
@Composable
fun BJStockBottomBar(selected: PrimaryTab, onSelect: (PrimaryTab) -> Unit) {
    if (LocalNavChrome.current.widthClass.usesRail) return
    val active = BottomNav.selectedItem(selected)
    NavigationBar {
        BottomNav.tabs.forEach { tab ->
            NavigationBarItem(
                selected = tab == active,
                onClick = { if (tab != selected) onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label, fontWeight = if (tab == active) FontWeight.Bold else FontWeight.Normal) },
            )
        }
    }
}

/** Top bar of the top-level screens. [onBack] is set while an in-screen layer (e.g. a stock detail) is open. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabTopBar(title: String, onBack: (() -> Unit)? = null) {
    val chrome = LocalNavChrome.current
    val openDrawer = chrome.openDrawer
    val owner = LocalLifecycleOwner.current
    val hasInScreenLayer = onBack != null
    DisposableEffect(owner, hasInScreenLayer, chrome.reportInScreenLayer) {
        chrome.reportInScreenLayer(owner, hasInScreenLayer)
        onDispose { chrome.reportInScreenLayer(owner, false) }
    }
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.Bold) },
        navigationIcon = {
            when (TopBarNavIcon.resolve(hasBack = hasInScreenLayer, drawerAvailable = openDrawer != null)) {
                TopBarNavIcon.BACK -> IconButton(onClick = { onBack?.invoke() }) {
                    Icon(BJStockIcons.Back, contentDescription = "뒤로")
                }
                TopBarNavIcon.MENU -> IconButton(onClick = { openDrawer?.invoke() }) {
                    Icon(BJStockIcons.Menu, contentDescription = "메뉴 열기")
                }
                TopBarNavIcon.NONE -> Unit
            }
        },
    )
}

// NAV-CLEANUP-05:
// The legacy developer screens keep their own private TopAppBars instead of BackTopBar.
// Unify them only in a separately approved navigation cleanup gate.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: () -> Unit) {
    CenterAlignedTopAppBar(
        title = { Text(title, fontWeight = FontWeight.Bold) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(BJStockIcons.Back, contentDescription = "뒤로")
            }
        },
    )
}

/**
 * Tab switch without stacking duplicates: back from any tab returns to Home, back on Home leaves the app.
 * Home always lands on its root (HD-NAV-T1): the non-inclusive pop also maps the saved stack to the start destination,
 * so restoring on Home would bring back a destination pushed above it. Other tabs keep their saved-stack restore.
 * A stack owned by Home is popped without saving (HD-NAV-T1-02): Home never restores it, so a saved copy would only
 * retain unreachable back-stack entries and their ViewModels.
 */
fun NavController.navigateToTab(tab: PrimaryTab) {
    val ownedByHome = stackOwner() == PrimaryTab.HOME
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = !ownedByHome }
        launchSingleTop = true
        restoreState = tab != PrimaryTab.HOME
    }
}

/**
 * The tab owning the current back stack. [navigateToTab] always pops to the start destination (Home) first, so the
 * stack is Home, at most one other tab, then pushed destinations: that tab owns them, or Home does when there is none.
 * Null when more than one other tab is on the stack, which [navigateToTab] never produces.
 */
private fun NavController.stackOwner(): PrimaryTab? {
    val tabs = PrimaryTab.entries.filter { it != PrimaryTab.HOME && hasBackStackEntry(it.route) }
    return when (tabs.size) {
        0 -> PrimaryTab.HOME
        1 -> tabs.single()
        else -> null
    }
}

private fun NavController.hasBackStackEntry(route: String): Boolean = try {
    getBackStackEntry(route)
    true
} catch (notOnStack: IllegalArgumentException) {
    false
}
