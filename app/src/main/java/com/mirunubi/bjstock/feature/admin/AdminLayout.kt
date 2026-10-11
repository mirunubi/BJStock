package com.mirunubi.bjstock.feature.admin

import com.mirunubi.bjstock.ui.navigation.NavWidthClass

enum class AdminLayoutMode { SINGLE_PANE, TWO_PANE }

/** The root blocks of 운영 · 감사, in the single-pane order. */
enum class AdminSection { INFO, STATUS, OPERATIONS, AUDIT, ERRORS, ENVIRONMENT }

/** What the right pane shows in [AdminLayoutMode.TWO_PANE]; the open detail is the existing [AdminUiState.detail]. */
sealed interface AdminRightPane {
    data object Root : AdminRightPane

    data class Detail(val detail: AdminDetail) : AdminRightPane
}

/** 운영 · 감사 arranges the same sections by width class only; data, selection and Back never depend on the layout. */
object AdminLayout {
    const val LEFT_PANE_WIDTH_DP = 360

    val singlePane: List<AdminSection> = AdminSection.entries

    val left: List<AdminSection> = listOf(AdminSection.INFO, AdminSection.STATUS, AdminSection.OPERATIONS)

    val rightRoot: List<AdminSection> = listOf(AdminSection.AUDIT, AdminSection.ERRORS, AdminSection.ENVIRONMENT)

    fun modeFor(widthClass: NavWidthClass): AdminLayoutMode =
        if (widthClass == NavWidthClass.EXPANDED) AdminLayoutMode.TWO_PANE else AdminLayoutMode.SINGLE_PANE

    /** The detail only when one is open; nothing is auto-selected. */
    fun rightPane(detail: AdminDetail?): AdminRightPane = detail?.let(AdminRightPane::Detail) ?: AdminRightPane.Root

    /** The right pane's scroll position restarts when this changes (root, or another operation). */
    fun rightPaneKey(detail: AdminDetail?): String = detail?.let { "detail:${it.operationId}" } ?: "root"

    /** Presentation only, and only beside the detail pane; the single-pane list keeps its current look. */
    fun selectedOperationId(mode: AdminLayoutMode, detail: AdminDetail?): Long? =
        if (mode == AdminLayoutMode.TWO_PANE) detail?.operationId else null
}
