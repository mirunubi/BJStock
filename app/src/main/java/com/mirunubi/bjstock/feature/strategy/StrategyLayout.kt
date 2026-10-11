package com.mirunubi.bjstock.feature.strategy

import com.mirunubi.bjstock.feature.strategy.template.TemplatePreviewViewModel
import com.mirunubi.bjstock.ui.navigation.NavWidthClass

enum class StrategyLayoutMode { SINGLE_PANE, LIST_DETAIL }

/** The open layer of the 전략 tab, derived only from the existing ViewModel state. */
sealed interface StrategyDetail {
    data object None : StrategyDetail

    data class Template(val templateId: Long) : StrategyDetail

    data class Version(val layer: VersionLayer) : StrategyDetail

    data class Strategy(val panel: StrategyPanel) : StrategyDetail
}

/** Highlighted master items. At most one family is ever set. */
data class StrategyMasterSelection(val templateId: Long?, val strategyId: Long?)

/** Strategy arranges the same layers by width class only; strategy semantics and state never depend on the layout. */
object StrategyLayout {
    const val MASTER_PANE_WIDTH_DP = 360
    const val EMPTY_DETAIL = "왼쪽에서 미리보기 템플릿 또는 전략을 선택하세요."

    fun modeFor(widthClass: NavWidthClass): StrategyLayoutMode =
        if (widthClass == NavWidthClass.EXPANDED) StrategyLayoutMode.LIST_DETAIL else StrategyLayoutMode.SINGLE_PANE

    /** Template preview, then version, then strategy; [StrategyDetail.None] only when nothing is open (no auto-selection). */
    fun detailOf(previewSelectedId: Long?, version: VersionLayer?, strategy: StrategyPanel?): StrategyDetail = when {
        previewSelectedId != null -> StrategyDetail.Template(previewSelectedId)
        version != null -> StrategyDetail.Version(version)
        strategy != null -> StrategyDetail.Strategy(strategy)
        else -> StrategyDetail.None
    }

    /** Identity of the open item; the detail pane's scroll position restarts when it changes. */
    fun detailKey(detail: StrategyDetail): String = when (detail) {
        StrategyDetail.None -> "none"
        is StrategyDetail.Template -> "template:${detail.templateId}"
        is StrategyDetail.Version -> "version:${detail.layer.versionId}"
        is StrategyDetail.Strategy -> "strategy:${detail.panel.strategyId}"
    }

    /** Presentation only, and only beside the detail pane; the single-pane list keeps its current look. */
    fun masterSelection(mode: StrategyLayoutMode, previewSelectedId: Long?, strategy: StrategyPanel?): StrategyMasterSelection = when {
        mode != StrategyLayoutMode.LIST_DETAIL -> StrategyMasterSelection(null, null)
        previewSelectedId != null -> StrategyMasterSelection(previewSelectedId, null)
        else -> StrategyMasterSelection(null, strategy?.strategyId)
    }
}

/**
 * Opening one family closes the other first, so the template preview and the real strategy / version layers are never
 * open together. Only what is open is closed, so with nothing open these are the plain existing actions.
 */
class StrategyFamilies(
    private val strategies: StrategyViewModel,
    private val preview: TemplatePreviewViewModel,
) {
    fun openTemplate(templateId: Long) {
        closeReal()
        preview.open(templateId)
    }

    fun createTemplate() {
        closeReal()
        preview.create()
    }

    fun openStrategy(strategyId: Long) {
        closePreview()
        strategies.openStrategy(strategyId)
    }

    fun openVersion(versionId: Long) {
        closePreview()
        strategies.openVersion(versionId)
    }

    fun showCreateStrategy() {
        closePreview()
        strategies.showCreateStrategy()
    }

    private fun closeReal() {
        while (strategies.back()) Unit
    }

    private fun closePreview() {
        if (preview.uiState.value.selectedId != null) preview.close()
    }
}
