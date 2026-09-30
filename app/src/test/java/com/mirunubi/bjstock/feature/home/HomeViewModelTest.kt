package com.mirunubi.bjstock.feature.home

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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HomeViewModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun startsLoading_thenShowsContent() {
        val gate = CompletableDeferred<HomeSnapshot>()
        val viewModel = HomeViewModel { gate.await() }
        assertEquals(HomeUiState.Loading, viewModel.uiState.value)

        viewModel.refresh()
        assertEquals(HomeUiState.Loading, viewModel.uiState.value)

        gate.complete(HomeFixtures.snapshot(auto = HomeFixtures.AUTO_ON))
        val content = viewModel.uiState.value as HomeUiState.Content
        assertEquals("10월 1일 오전 7:00 이후", content.auto.nextRun)
    }

    @Test
    fun loadFailure_showsSafeKoreanErrorOnly() {
        val raw = "near appsecret TEST_APP_SECRET: SELECT * FROM strategy_runs"
        val viewModel = HomeViewModel { throw IllegalStateException(raw) }

        viewModel.refresh()

        val error = viewModel.uiState.value as HomeUiState.Error
        assertEquals("홈 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.", error.message)
        listOf(raw, "IllegalStateException", "SELECT", "appsecret").forEach { assertFalse(error.toString().contains(it)) }
    }

    @Test
    fun retryAfterError_recovers() {
        var fail = true
        val viewModel = HomeViewModel {
            if (fail) throw IllegalStateException("x")
            HomeFixtures.snapshot(run = null)
        }
        viewModel.refresh()
        assertTrue(viewModel.uiState.value is HomeUiState.Error)

        fail = false
        viewModel.refresh()
        assertEquals(PortfolioCard.NoRun, (viewModel.uiState.value as HomeUiState.Content).portfolio)
    }

    @Test
    fun refresh_keepsExistingContentVisible() {
        var calls = 0
        val gate = CompletableDeferred<HomeSnapshot>()
        val viewModel = HomeViewModel {
            calls++
            if (calls == 1) HomeFixtures.snapshot() else gate.await()
        }
        viewModel.refresh()
        val first = viewModel.uiState.value
        assertTrue(first is HomeUiState.Content)

        viewModel.refresh()
        assertEquals(first, viewModel.uiState.value)
        viewModel.refresh()
        assertEquals(2, calls)
        gate.complete(HomeFixtures.snapshot(run = null))
        assertEquals(PortfolioCard.NoRun, (viewModel.uiState.value as HomeUiState.Content).portfolio)
    }
}
