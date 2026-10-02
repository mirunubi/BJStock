package com.mirunubi.bjstock.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mirunubi.bjstock.ui.icons.BJStockIcons

/** Drawer depth. Developer Tools is a page inside the drawer, not a NavHost destination. */
enum class DrawerPage { ROOT, DEV_TOOLS }

/**
 * What Back does while the drawer is open. The screen below never receives it:
 * Developer Tools returns to the drawer root, the root closes the drawer.
 */
enum class DrawerBack { SHOW_ROOT, CLOSE }

/**
 * Whether the drawer may be opened (docs/165 HD-NAV-08, HD-NAV-14), at every width class: only on a top-level
 * root, i.e. a [PrimaryTab] route whose screen has no in-screen layer (Stocks detail, Strategy detail / template).
 */
object DrawerAccess {
    fun isDrawerCapable(route: String?, hasInScreenLayer: Boolean): Boolean =
        !hasInScreenLayer && PrimaryTab.entries.any { it.route == route }

    /** Rail items stay visible on every screen; the drawer-backed Developer Tools item is disabled when not drawer-capable. */
    fun railItemEnabled(target: ShellTarget, drawerCapable: Boolean): Boolean =
        target != ShellTarget.DevTools || drawerCapable

    /** A drawer left open over a state that may not have one (layer opened, route changed) must be closed. */
    fun mustClose(drawerOpen: Boolean, drawerCapable: Boolean): Boolean = drawerOpen && !drawerCapable
}

/** The level that handles Back (docs/165 §G). Higher levels win because lower ones are disabled, not by registration order. */
enum class BackLevel { DRAWER_DEV_TOOLS, DRAWER_ROOT, SCREEN_LAYER, NAV_HOST }

object BackPriority {
    fun resolve(drawerOpen: Boolean, page: DrawerPage, hasInScreenLayer: Boolean): BackLevel = when {
        drawerOpen && page == DrawerPage.DEV_TOOLS -> BackLevel.DRAWER_DEV_TOOLS
        drawerOpen -> BackLevel.DRAWER_ROOT
        hasInScreenLayer -> BackLevel.SCREEN_LAYER
        else -> BackLevel.NAV_HOST
    }

    /** The shell's drawer handler. */
    fun drawerHandlerEnabled(drawerOpen: Boolean): Boolean = drawerOpen

    /** Back dispatch for the whole NavHost subtree (NavHost's own handler and every screen handler). */
    fun contentBackEnabled(drawerOpen: Boolean): Boolean = !drawerOpen

    /** A screen's in-screen layer handler (Stocks, Strategy). */
    fun layerHandlerEnabled(hasInScreenLayer: Boolean, drawerOpen: Boolean): Boolean = hasInScreenLayer && !drawerOpen
}

object DrawerNavigation {
    fun back(page: DrawerPage): DrawerBack = when (page) {
        DrawerPage.DEV_TOOLS -> DrawerBack.SHOW_ROOT
        DrawerPage.ROOT -> DrawerBack.CLOSE
    }

    /** Closing the drawer always resets its depth, so the next open starts at the root. */
    val pageAfterClose: DrawerPage = DrawerPage.ROOT

    /** The swipe gesture only closes an open drawer; it is opened by the hamburger or the rail's Developer Tools. */
    fun gesturesEnabled(drawerOpen: Boolean): Boolean = drawerOpen
}

@Composable
internal fun BJStockDrawerSheet(
    page: DrawerPage,
    currentRoute: String?,
    onShowPage: (DrawerPage) -> Unit,
    onSelect: (ShellTarget) -> Unit,
) {
    val selected = ShellSelection.targetFor(currentRoute)
    ModalDrawerSheet {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 16.dp),
        ) {
            when (page) {
                DrawerPage.ROOT -> {
                    Text(
                        "BJStock",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    DrawerMenu.groups.forEachIndexed { index, group ->
                        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        GroupTitle(group.title)
                        group.items.forEach { item ->
                            val opensGroup = item.target == ShellTarget.DevTools
                            DrawerItem(
                                item = item,
                                selected = item.target == selected,
                                trailing = opensGroup,
                                onClick = { if (opensGroup) onShowPage(DrawerPage.DEV_TOOLS) else onSelect(item.target) },
                            )
                        }
                    }
                }
                DrawerPage.DEV_TOOLS -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onShowPage(DrawerPage.ROOT) }) {
                            Icon(BJStockIcons.Back, contentDescription = "뒤로")
                        }
                        Text(DrawerMenu.devTools.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    DrawerMenu.developerTools.forEach { item ->
                        DrawerItem(
                            item = item,
                            selected = item.target == ShellTarget.Push(currentRoute.orEmpty()),
                            trailing = false,
                            onClick = { onSelect(item.target) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun DrawerItem(item: ShellItem, selected: Boolean, trailing: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(item.label) },
        selected = selected,
        onClick = onClick,
        icon = item.icon?.let { icon -> { Icon(icon, contentDescription = null) } },
        badge = if (trailing) ({ Icon(BJStockIcons.ChevronRight, contentDescription = null) }) else null,
    )
}
