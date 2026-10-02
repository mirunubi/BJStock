package com.mirunubi.bjstock.feature.home

import com.mirunubi.bjstock.feature.admin.SectionState
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

/** Core Home failure must be disclosed explicitly while the independent activity cards keep rendering. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HomeCoreFailureTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun coreFailure_keepsTheGlobalMessage() {
        val core = HomeViewModel { throw IllegalStateException("x") }
        core.refresh()

        assertEquals(HomeUiState.Error("홈 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요."), core.uiState.value)
    }

    @Test
    fun disclosure_namesEveryCoreCardThatIsNotShown() {
        assertEquals(listOf("최근 전략 판단", "보유현황", "자동운영", "운영 경고"), HomePresenter.CORE_DETAIL_SECTIONS)
        assertEquals(
            "최근 전략 판단 · 보유현황 · 자동운영 · 운영 경고를 표시할 수 없습니다.",
            HomePresenter.CORE_SECTIONS_UNAVAILABLE,
        )
        HomePresenter.CORE_DETAIL_SECTIONS.forEach { assertTrue(HomePresenter.CORE_SECTIONS_UNAVAILABLE.contains(it)) }
    }

    @Test
    fun failureWording_neverImpliesNoAlerts() {
        val failureTexts = listOf(HomePresenter.LOAD_FAILED, HomePresenter.CORE_SECTIONS_UNAVAILABLE)
        listOf("정상", "문제 없음", "경고 없음", "경고가 없", "오류 없음").forEach { phrase ->
            failureTexts.forEach { assertFalse("'$it' contains '$phrase'", it.contains(phrase)) }
        }
    }

    @Test
    fun activityCards_remainAvailable_whenCoreFails() {
        val core = HomeViewModel { throw IllegalStateException("x") }
        val activity = HomeActivityViewModel(FakeHomeActivitySource())

        core.refresh()
        activity.refresh()

        assertTrue(core.uiState.value is HomeUiState.Error)
        HomeActivitySection.entries.forEach { section ->
            val state = activity.uiState.value.section(section)
            assertTrue("$section", state is SectionState.Loaded || state is SectionState.Empty)
        }
    }

    @Test
    fun coreRetry_reloadsCoreOnly() {
        var coreCalls = 0
        var fail = true
        val core = HomeViewModel {
            coreCalls++
            if (fail) throw IllegalStateException("x")
            HomeFixtures.snapshot()
        }
        val source = FakeHomeActivitySource()
        val activity = HomeActivityViewModel(source)
        core.refresh()
        activity.refresh()
        val activityCalls = source.calls.toList()

        fail = false
        core.refresh()

        assertEquals(2, coreCalls)
        assertTrue(core.uiState.value is HomeUiState.Content)
        assertEquals(activityCalls, source.calls)
    }
}
