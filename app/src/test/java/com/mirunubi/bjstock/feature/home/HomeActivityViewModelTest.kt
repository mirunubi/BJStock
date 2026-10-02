package com.mirunubi.bjstock.feature.home

import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.model.TradeAuditEventType
import com.mirunubi.bjstock.feature.admin.AdminAuditData
import com.mirunubi.bjstock.feature.admin.AdminErrorData
import com.mirunubi.bjstock.feature.admin.AdminFixtures
import com.mirunubi.bjstock.feature.admin.SectionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class FakeHomeActivitySource : HomeActivitySource {
    val calls = mutableListOf<String>()
    var failing: Set<HomeActivitySection> = emptySet()
    var runsGate: CompletableDeferred<Unit>? = null

    private fun check(section: HomeActivitySection) {
        calls += section.name
        if (section in failing) throw IllegalStateException("near appsecret TEST_APP_SECRET: SELECT * FROM orders")
    }

    override suspend fun activeRuns(): List<HomeRunRef> {
        runsGate?.await()
        check(HomeActivitySection.RUNS)
        return listOf(HomeActivityFixtures.ref(3, RunStatus.RUNNING))
    }

    override suspend fun signals(): HomeSignalData {
        check(HomeActivitySection.SIGNALS)
        return HomeSignalData(
            HomeActivityFixtures.DAY,
            listOf(HomeActivityFixtures.signalLog(1, TradeAuditEventType.EVALUATION_DECIDED)),
            emptyMap(),
            emptyMap(),
            emptyMap(),
        )
    }

    override suspend fun trades(): HomeTradeData {
        check(HomeActivitySection.TRADES)
        return HomeTradeData(emptyList(), 0, emptyMap(), emptyMap())
    }

    override suspend fun errors(): AdminErrorData {
        check(HomeActivitySection.ERRORS)
        return AdminErrorData(listOf(AdminFixtures.apiError(1)), emptyList(), emptyList(), emptyList())
    }

    override suspend fun audit(): AdminAuditData {
        check(HomeActivitySection.AUDIT)
        return AdminAuditData(emptyList(), listOf(AdminFixtures.audit(1, TradeAuditEventType.ORDER_CREATED)), emptyList(), emptyList())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HomeActivityViewModelTest {
    private val source = FakeHomeActivitySource()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun startsLoading_thenLoadsEverySection() {
        val viewModel = HomeActivityViewModel(source)
        HomeActivitySection.entries.forEach { assertEquals(SectionState.Loading, viewModel.uiState.value.section(it)) }

        viewModel.refresh()

        val state = viewModel.uiState.value
        assertTrue(state.runs is SectionState.Loaded)
        assertTrue(state.signals is SectionState.Loaded)
        assertEquals(SectionState.Empty(HomeActivityPresenter.TRADES_EMPTY), state.trades)
        assertTrue(state.errors is SectionState.Loaded)
        assertTrue(state.audit is SectionState.Loaded)
    }

    @Test
    fun oneBrokenSource_failsOnlyItsOwnSection_withSafeText() {
        source.failing = setOf(HomeActivitySection.SIGNALS)
        val viewModel = HomeActivityViewModel(source)

        viewModel.refresh()

        val state = viewModel.uiState.value
        assertEquals(SectionState.Failed("이 항목을 불러오지 못했습니다."), state.signals)
        listOf(state.runs, state.errors, state.audit).forEach { assertTrue(it is SectionState.Loaded) }
        listOf("appsecret", "SELECT", "IllegalStateException").forEach { assertFalse(state.toString().contains(it)) }
    }

    @Test
    fun everySourceFailing_stillLeavesEachSectionWithAMessage() {
        source.failing = HomeActivitySection.entries.toSet()
        val viewModel = HomeActivityViewModel(source)

        viewModel.refresh()

        HomeActivitySection.entries.forEach {
            assertEquals(SectionState.Failed(HomeActivityPresenter.LOAD_FAILED), viewModel.uiState.value.section(it))
        }
    }

    @Test
    fun retry_recoversFailedSection() {
        source.failing = setOf(HomeActivitySection.AUDIT)
        val viewModel = HomeActivityViewModel(source)
        viewModel.refresh()
        assertTrue(viewModel.uiState.value.audit is SectionState.Failed)

        source.failing = emptySet()
        viewModel.refresh()

        assertTrue(viewModel.uiState.value.audit is SectionState.Loaded)
    }

    @Test
    fun refreshWhileLoading_isIgnored_andLoadedSectionsStayVisible() {
        val viewModel = HomeActivityViewModel(source)
        viewModel.refresh()
        val loadedRuns = viewModel.uiState.value.runs
        source.runsGate = CompletableDeferred()

        viewModel.refresh()
        assertEquals(loadedRuns, viewModel.uiState.value.runs)
        viewModel.refresh()
        source.runsGate!!.complete(Unit)

        assertEquals(2, source.calls.count { it == HomeActivitySection.RUNS.name })
    }

    @Test
    fun coreHomeFailure_doesNotAffectActivitySections() {
        val core = HomeViewModel { throw IllegalStateException("x") }
        val activity = HomeActivityViewModel(source)

        core.refresh()
        activity.refresh()

        assertTrue(core.uiState.value is HomeUiState.Error)
        assertTrue(activity.uiState.value.runs is SectionState.Loaded)
    }

    @Test
    fun existingHomeCards_areStillProducedAlongsideActivity() {
        val core = HomeViewModel { HomeFixtures.snapshot(auto = HomeFixtures.AUTO_ON, operation = HomeFixtures.operation()) }
        core.refresh()

        val content = core.uiState.value as HomeUiState.Content
        assertTrue(content.portfolio is PortfolioCard.Summary)
        assertTrue(content.decision is DecisionCard.Latest)
        assertTrue(content.holdings is HoldingsCard.Holdings)
        assertTrue(content.auto.enabled)
        assertTrue(content.alerts.isEmpty())
    }

    @Test
    fun onlyDependency_isTheReadOnlyActivitySource() {
        assertEquals(
            listOf(HomeActivitySource::class.java),
            HomeActivityViewModel::class.java.constructors.single().parameterTypes.toList(),
        )
        assertEquals(
            setOf("activeRuns", "signals", "trades", "errors", "audit"),
            HomeActivitySource::class.java.declaredMethods.map { it.name }.toSet(),
        )
        val vmMethods = HomeActivityViewModel::class.java.declaredMethods.map { it.name.lowercase() }
        listOf("runnow", "retry", "setauto", "toggle", "save", "insert", "update", "delete").forEach { word ->
            assertTrue(vmMethods.none { it.contains(word) })
        }
    }
}
