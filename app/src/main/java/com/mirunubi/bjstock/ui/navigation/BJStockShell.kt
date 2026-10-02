package com.mirunubi.bjstock.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import kotlinx.coroutines.launch

/**
 * Adaptive navigation chrome around the app's single NavHost ([content], docs/165 HD-NAV-01).
 * Compact: modal drawer + the screens' 3-item bottom bar. Medium / Expanded: navigation rail.
 * [content] is called from exactly one position, so a width change never recreates the NavHost.
 */
@Composable
fun BJStockAdaptiveShell(
    navController: NavController,
    onSelectTab: (PrimaryTab) -> Unit,
    onOpen: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    val widthClass = currentNavWidthClass()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var page by rememberSaveable { mutableStateOf(DrawerPage.ROOT) }
    val scope = rememberCoroutineScope()
    val currentEntry = navController.currentBackStackEntryAsState().value
    val currentRoute = currentEntry?.destination?.route
    val drawerOpen = drawerState.targetValue == DrawerValue.Open
    // Child back dispatcher for the NavHost subtree: disabled while the drawer is open, so neither NavHost's own
    // handler nor any screen handler can take Back from the drawer.
    val contentBackOwner = rememberNavigationEventDispatcherOwner(enabled = BackPriority.contentBackEnabled(drawerOpen))

    // Back-stack entries whose screen currently shows an in-screen layer, as reported by TabTopBar.
    val layeredEntries = remember { mutableStateMapOf<Any, Boolean>() }
    val reportInScreenLayer: (Any, Boolean) -> Unit = remember {
        { owner, open -> if (open) layeredEntries[owner] = true else layeredEntries.remove(owner) }
    }
    val drawerCapable = DrawerAccess.isDrawerCapable(
        route = currentRoute,
        hasInScreenLayer = currentEntry != null && currentEntry in layeredEntries,
    )
    val drawerCapableNow by rememberUpdatedState(drawerCapable)

    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.currentValue == DrawerValue.Closed) page = DrawerNavigation.pageAfterClose
    }
    LaunchedEffect(drawerOpen, drawerCapable) {
        if (DrawerAccess.mustClose(drawerOpen, drawerCapable)) drawerState.close()
    }

    val openDrawer: (DrawerPage) -> Unit = { start ->
        if (drawerCapableNow) {
            page = start
            scope.launch { drawerState.open() }
        }
    }
    val navigate: (ShellTarget) -> Unit = { target ->
        when (target) {
            is ShellTarget.Tab -> onSelectTab(target.tab)
            is ShellTarget.Push -> onOpen(target.route)
            ShellTarget.DevTools -> openDrawer(DrawerPage.DEV_TOOLS)
        }
    }
    val chrome = remember(widthClass, drawerOpen) {
        NavChrome(
            widthClass = widthClass,
            openDrawer = if (widthClass.usesRail) null else ({ openDrawer(DrawerPage.ROOT) }),
            drawerOpen = drawerOpen,
            reportInScreenLayer = reportInScreenLayer,
        )
    }
    // The rail pads the start inset itself; screens inside must not apply it a second time.
    val railStartInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Start)

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = DrawerNavigation.gesturesEnabled(drawerOpen),
        drawerContent = {
            BJStockDrawerSheet(
                page = page,
                currentRoute = currentRoute,
                onShowPage = { page = it },
                onSelect = { target ->
                    scope.launch { drawerState.close() }
                    navigate(target)
                },
            )
        },
    ) {
        Row(Modifier.fillMaxSize()) {
            if (widthClass.usesRail) {
                BJStockRail(currentRoute = currentRoute, drawerCapable = drawerCapable, onSelect = navigate)
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(if (widthClass.usesRail) Modifier.consumeWindowInsets(railStartInsets) else Modifier),
            ) {
                CompositionLocalProvider(
                    LocalNavChrome provides chrome,
                    LocalNavigationEventDispatcherOwner provides contentBackOwner,
                ) { content() }
            }
        }
    }

    BackHandler(enabled = BackPriority.drawerHandlerEnabled(drawerOpen)) {
        when (DrawerNavigation.back(page)) {
            DrawerBack.SHOW_ROOT -> page = DrawerPage.ROOT
            DrawerBack.CLOSE -> scope.launch { drawerState.close() }
        }
    }
}

@Composable
private fun BJStockRail(currentRoute: String?, drawerCapable: Boolean, onSelect: (ShellTarget) -> Unit) {
    val selected = ShellSelection.targetFor(currentRoute)
    NavigationRail(windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RailMenu.sections.forEachIndexed { index, section ->
                if (index > 0) HorizontalDivider(Modifier.width(48.dp).padding(vertical = 8.dp))
                section.forEach { item ->
                    NavigationRailItem(
                        selected = item.target == selected,
                        onClick = { onSelect(item.target) },
                        enabled = DrawerAccess.railItemEnabled(item.target, drawerCapable),
                        icon = { item.icon?.let { Icon(it, contentDescription = null) } },
                        label = { Text(item.label, maxLines = 2, textAlign = TextAlign.Center) },
                    )
                }
            }
        }
    }
}
