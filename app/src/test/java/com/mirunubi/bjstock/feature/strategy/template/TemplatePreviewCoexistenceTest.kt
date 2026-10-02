package com.mirunubi.bjstock.feature.strategy.template

import com.mirunubi.bjstock.feature.strategy.FakeStrategyDataSource
import com.mirunubi.bjstock.feature.strategy.ListState
import com.mirunubi.bjstock.feature.strategy.StrategyViewModel
import com.mirunubi.bjstock.ui.navigation.BJStockRoutes
import com.mirunubi.bjstock.ui.navigation.PrimaryTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The preview lives beside the real strategy list; it must not disturb it. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TemplatePreviewCoexistenceTest {
    private val dispatcher = StandardTestDispatcher()
    private val strategySource = FakeStrategyDataSource()
    private val readSource = FakeTemplatePreviewReadSource()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun previewOperations_leaveRealStrategyFlowReachableAndUnwritten() = runTest(dispatcher) {
        val strategies = StrategyViewModel(strategySource)
        val preview = TemplatePreviewViewModel(readSource)
        advanceUntilIdle()
        val listBefore = strategies.uiState.value.list

        preview.create()
        preview.updateForm { it.copy(volumeAverageLookbackDays = "7") }
        preview.applyPreview()
        preview.duplicate(preview.uiState.value.templates.first().id)
        preview.requestDelete(preview.uiState.value.templates.last().id)
        preview.confirmDelete()
        preview.close()
        advanceUntilIdle()

        assertTrue(strategySource.writes.isEmpty())
        assertEquals(listBefore, strategies.uiState.value.list)
        val loaded = strategies.uiState.value.list as ListState.Loaded
        strategies.openStrategy(loaded.cards.first().strategyId)
        advanceUntilIdle()
        assertNotNull(strategies.uiState.value.strategy)
    }

    @Test
    fun strategyTabRoute_isUnchanged() {
        assertEquals(BJStockRoutes.STRATEGY, PrimaryTab.STRATEGY.route)
        assertEquals("전략", PrimaryTab.STRATEGY.label)
    }
}
