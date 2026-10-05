package com.mirunubi.bjstock.feature.paper

import com.mirunubi.bjstock.ui.navigation.NavWidthClass

enum class PaperLayoutMode { SINGLE_PANE, LIST_DETAIL }

/** The root blocks of 모의투자, in the single-pane order. */
enum class PaperSection { NOTICE, RUN_LIST, AUTOMATION, DETAIL, RECENT_OPERATIONS }

/** What the detail pane shows in [PaperLayoutMode.LIST_DETAIL]; the detail is the existing [PaperTradingUiState.detail]. */
sealed interface PaperRightPane {
    data object Blank : PaperRightPane

    data class Detail(val detail: DetailState) : PaperRightPane
}

/** 모의투자 arranges the same sections by width class only; selection, default selection and runtime never depend on it. */
object PaperLayout {
    const val LEFT_PANE_WIDTH_DP = 360

    val singlePane: List<PaperSection> = PaperSection.entries

    val fullWidth: List<PaperSection> = listOf(PaperSection.NOTICE)

    val left: List<PaperSection> = listOf(PaperSection.RUN_LIST, PaperSection.AUTOMATION, PaperSection.RECENT_OPERATIONS)

    val right: List<PaperSection> = listOf(PaperSection.DETAIL)

    fun modeFor(widthClass: NavWidthClass): PaperLayoutMode =
        if (widthClass == NavWidthClass.EXPANDED) PaperLayoutMode.LIST_DETAIL else PaperLayoutMode.SINGLE_PANE

    /** Blank when no Run is selected (approved HD-PA-01); the Run list already shows the empty state. */
    fun rightPane(detail: DetailState): PaperRightPane =
        if (detail == DetailState.None) PaperRightPane.Blank else PaperRightPane.Detail(detail)

    /** The detail pane's scroll position restarts when the selected Run changes. */
    fun rightPaneKey(selectedRunId: Long?): String = selectedRunId?.let { "run:$it" } ?: "none"
}
