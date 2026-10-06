package com.mirunubi.bjstock.feature.performance

import com.mirunubi.bjstock.ui.navigation.NavWidthClass

enum class PerformanceLayoutMode { SINGLE_PANE, LIST_DETAIL }

/** The root blocks of 성과, in the single-pane order. The 모의투자 비교 layer replaces all of them in every layout. */
enum class PerformanceSection { RUN_SELECTOR, DETAIL }

/** What the detail pane shows in [PerformanceLayoutMode.LIST_DETAIL]; the detail is the existing [PerformanceUiState.detail]. */
sealed interface PerformanceRightPane {
    data object Blank : PerformanceRightPane

    data class Detail(val detail: PerformanceDetailState) : PerformanceRightPane
}

/** 성과 arranges the same sections by width class only; selection, comparison and data loading never depend on it. */
object PerformanceLayout {
    const val LEFT_PANE_WIDTH_DP = 360

    val singlePane: List<PerformanceSection> = PerformanceSection.entries

    val left: List<PerformanceSection> = listOf(PerformanceSection.RUN_SELECTOR)

    val right: List<PerformanceSection> = listOf(PerformanceSection.DETAIL)

    fun modeFor(widthClass: NavWidthClass): PerformanceLayoutMode =
        if (widthClass == NavWidthClass.EXPANDED) PerformanceLayoutMode.LIST_DETAIL else PerformanceLayoutMode.SINGLE_PANE

    /** Blank when there is no detail (approved HD-PF-01); the Run selector already shows the empty, loading or failed list. */
    fun rightPane(detail: PerformanceDetailState): PerformanceRightPane =
        if (detail == PerformanceDetailState.None) PerformanceRightPane.Blank else PerformanceRightPane.Detail(detail)

    /** The detail pane's scroll position restarts when the selected Run changes. */
    fun rightPaneKey(selectedRunId: Long?): String = selectedRunId?.let { "run:$it" } ?: "none"
}
