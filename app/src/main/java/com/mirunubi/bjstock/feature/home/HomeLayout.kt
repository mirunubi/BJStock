package com.mirunubi.bjstock.feature.home

import com.mirunubi.bjstock.ui.navigation.NavWidthClass

/** Home sections in their canonical single-column order. Core slots come from HomeViewModel, the rest from HomeActivityViewModel. */
enum class HomeSlot(val title: String, val activity: HomeActivitySection? = null) {
    PORTFOLIO("모의자산"),
    RUNS(HomeActivitySection.RUNS.title, HomeActivitySection.RUNS),
    DECISION(HomePresenter.DECISION_TITLE),
    SIGNALS(HomeActivitySection.SIGNALS.title, HomeActivitySection.SIGNALS),
    HOLDINGS(HomePresenter.HOLDINGS_TITLE),
    TRADES(HomeActivitySection.TRADES.title, HomeActivitySection.TRADES),
    AUTO(HomePresenter.AUTO_TITLE),
    ALERTS(HomePresenter.ALERTS_TITLE),
    ERRORS(HomeActivitySection.ERRORS.title, HomeActivitySection.ERRORS),
    AUDIT(HomeActivitySection.AUDIT.title, HomeActivitySection.AUDIT),
    ;

    val isCore: Boolean
        get() = activity == null
}

enum class HomeLayoutMode { SINGLE_COLUMN, TWO_COLUMN }

/** Home arranges the same sections by width class only; state, callbacks and content never depend on the layout. */
object HomeLayout {
    val singleColumn: List<HomeSlot> = HomeSlot.entries

    /** 계좌 / 거래 */
    val left: List<HomeSlot> = listOf(HomeSlot.PORTFOLIO, HomeSlot.RUNS, HomeSlot.HOLDINGS, HomeSlot.TRADES)

    /** 판단 / 운영 */
    val right: List<HomeSlot> =
        listOf(HomeSlot.DECISION, HomeSlot.SIGNALS, HomeSlot.AUTO, HomeSlot.ALERTS, HomeSlot.ERRORS, HomeSlot.AUDIT)

    fun modeFor(widthClass: NavWidthClass): HomeLayoutMode =
        if (widthClass == NavWidthClass.EXPANDED) HomeLayoutMode.TWO_COLUMN else HomeLayoutMode.SINGLE_COLUMN

    /**
     * Two-column only. The core error card (message + retry) stays in the left column, but most hidden core cards belong
     * to the right one, so it repeats the disclosure once; their absence must not read as "nothing to show".
     */
    fun rightColumnCoreNotice(core: HomeUiState): String? =
        HomePresenter.CORE_SECTIONS_UNAVAILABLE.takeIf { core is HomeUiState.Error }
}
