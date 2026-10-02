package com.mirunubi.bjstock.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/** Navigation chrome width classes. They choose the navigation chrome only; screen content stays single-column. */
enum class NavWidthClass {
    COMPACT,
    MEDIUM,
    EXPANDED,
    ;

    /** Medium and Expanded show the navigation rail instead of the drawer + bottom bar. */
    val usesRail: Boolean
        get() = this != COMPACT

    companion object {
        // NAV-CLEANUP-06:
        // NAV-1 layout breakpoints (Material window-size-class widths), kept local so NAV-1 adds no dependency.
        // Replace with the official WindowSizeClass / Material3 Adaptive APIs in a separately approved gate.
        const val MEDIUM_MIN_WIDTH_DP = 600
        const val EXPANDED_MIN_WIDTH_DP = 840

        fun fromWidthDp(widthDp: Int): NavWidthClass = when {
            widthDp >= EXPANDED_MIN_WIDTH_DP -> EXPANDED
            widthDp >= MEDIUM_MIN_WIDTH_DP -> MEDIUM
            else -> COMPACT
        }
    }
}

@Composable
fun currentNavWidthClass(): NavWidthClass = NavWidthClass.fromWidthDp(LocalConfiguration.current.screenWidthDp)
