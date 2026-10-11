package com.mirunubi.bjstock.feature.strategy.template

import com.mirunubi.bjstock.feature.strategy.template.TemplatePreviewPresenter as P
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class FakeTemplatePreviewReadSource : TemplatePreviewReadSource {
    val calls = mutableListOf<String>()
    var stocks = listOf(
        InstrumentOption(1, "005930", "삼성전자"),
        InstrumentOption(2, "000660", "SK하이닉스"),
    )
    var themes = listOf(ThemeOption(7, "반도체", 3))
    var failure: Exception? = null

    override suspend fun searchStocks(query: String): List<InstrumentOption> {
        calls += "searchStocks:$query"
        failure?.let { throw it }
        return stocks.filter { it.name.contains(query) || it.symbol.contains(query) }
    }

    override suspend fun themes(): List<ThemeOption> {
        calls += "themes"
        failure?.let { throw it }
        return themes
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TemplatePreviewViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val source = FakeTemplatePreviewReadSource()
    private lateinit var viewModel: TemplatePreviewViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = TemplatePreviewViewModel(source)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val state get() = viewModel.uiState.value

    @Test
    fun startsWithBuiltInTemplates_andReadsNothing() {
        assertEquals(listOf("삼성전자 종가돌파", "HBM 종가돌파 예시", "방산 종가돌파 예시"), state.cards.map { it.name })
        assertNull(state.selectedId)
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun onlyDependency_isTheReadOnlySource() {
        val params = TemplatePreviewViewModel::class.java.constructors.single().parameterTypes.toList()
        assertEquals(listOf(TemplatePreviewReadSource::class.java), params)
        val methods = TemplatePreviewReadSource::class.java.declaredMethods.map { it.name }.toSet()
        assertEquals(setOf("searchStocks", "themes"), methods)
    }

    @Test
    fun editingAndApplying_onlyChangesMemory() = runTest(dispatcher) {
        val a = state.templates.first()
        viewModel.open(a.id)
        viewModel.updateForm { it.copy(volumeAverageLookbackDays = "7", stopLossPercent = "3") }
        assertTrue(state.dirty)

        viewModel.applyPreview()
        advanceUntilIdle()

        val updated = state.templates.first()
        assertEquals(7, updated.volumeAverageLookbackDays)
        assertTrue(updated.unsaved)
        assertFalse(state.dirty)
        assertTrue(P.BADGE_UNSAVED in state.cards.first().badges)
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun invalidForm_isNotApplied_andRevertRestores() {
        val a = state.templates.first()
        viewModel.open(a.id)
        viewModel.updateForm { it.copy(stopLossPercent = "-5") }

        viewModel.applyPreview()
        assertEquals(a, state.templates.first())
        assertFalse(state.validation!!.canApply)

        viewModel.revert()
        assertEquals("5", state.form!!.stopLossPercent)
        assertFalse(state.dirty)
    }

    @Test
    fun create_addsUnsavedDefaultTemplate_andOpensIt() {
        viewModel.create()

        val created = state.templates.last()
        assertEquals("새 템플릿 1", created.name)
        assertTrue(created.unsaved)
        assertNull(created.volumeAverageLookbackDays)
        assertTrue(created.stocks.isEmpty())
        assertEquals(created.id, state.selectedId)
        assertTrue(state.validation!!.targetMissing)
    }

    @Test
    fun duplicate_insertsUnsavedCopyAfterSource() {
        val b = state.templates[1]

        viewModel.duplicate(b.id)

        val copy = state.templates[2]
        assertEquals("HBM 종가돌파 예시 복사본", copy.name)
        assertFalse(copy.example)
        assertTrue(copy.unsaved)
        assertTrue(copy.id != b.id)
        assertEquals(4, state.templates.size)
    }

    @Test
    fun rename_updatesNameAndOpenForm_blankIsIgnored() {
        val a = state.templates.first()
        viewModel.open(a.id)
        viewModel.requestRename(a.id)
        viewModel.updateRenameText("  ")
        viewModel.confirmRename()
        assertEquals(a.name, state.templates.first().name)

        viewModel.updateRenameText("내 종가돌파")
        viewModel.confirmRename()

        assertEquals("내 종가돌파", state.templates.first().name)
        assertEquals("내 종가돌파", state.form!!.name)
        assertTrue(state.templates.first().unsaved)
        assertNull(state.dialog)
    }

    @Test
    fun delete_removesFromMemory_andClosesOpenDetail() {
        val c = state.templates[2]
        viewModel.open(c.id)
        viewModel.requestDelete(c.id)
        viewModel.confirmDelete()

        assertEquals(2, state.templates.size)
        assertNull(state.selectedId)
        assertNull(state.form)
    }

    @Test
    fun stockSearch_loadsResults_andSingleSelectionReplaces() = runTest(dispatcher) {
        viewModel.create()
        viewModel.updateStockQuery("삼성")
        viewModel.searchStocks()
        assertEquals(PickerState.Loading, state.stockSearch)
        advanceUntilIdle()

        val loaded = state.stockSearch as PickerState.Loaded
        viewModel.selectStock(loaded.items.single())

        assertEquals(listOf("005930"), state.form!!.stocks.map { it.symbol })
        assertFalse(state.validation!!.targetMissing)
        assertEquals(listOf("searchStocks:삼성"), source.calls)
    }

    @Test
    fun stockSet_acceptsMultipleStocks_andRemoval() = runTest(dispatcher) {
        viewModel.create()
        viewModel.setTargetType(TemplateTargetType.STOCK_SET)
        viewModel.selectStock(source.stocks[0])
        viewModel.selectStock(source.stocks[1])
        assertEquals(2, state.form!!.stocks.size)

        viewModel.removeStock("005930")

        assertEquals(listOf("000660"), state.form!!.stocks.map { it.symbol })
    }

    @Test
    fun stockSearch_emptyFailedAndBlankStates() = runTest(dispatcher) {
        viewModel.create()
        viewModel.searchStocks()
        assertTrue(state.stockSearch is PickerState.Empty)
        assertTrue(source.calls.isEmpty())

        viewModel.updateStockQuery("없는종목")
        viewModel.searchStocks()
        advanceUntilIdle()
        assertEquals(PickerState.Empty(P.SEARCH_EMPTY), state.stockSearch)

        source.failure = IllegalStateException("boom")
        viewModel.searchStocks()
        advanceUntilIdle()
        assertEquals(PickerState.Failed(P.LOAD_FAILED), state.stockSearch)
    }

    @Test
    fun themeTarget_loadsThemes_andSelectsOne() = runTest(dispatcher) {
        viewModel.create()
        viewModel.setTargetType(TemplateTargetType.THEME)
        assertEquals(PickerState.Loading, state.themes)
        advanceUntilIdle()

        val loaded = state.themes as PickerState.Loaded
        viewModel.selectTheme(loaded.items.single())

        assertEquals(TemplateTheme(7, "반도체", 3), state.form!!.theme)
        assertFalse(state.validation!!.targetMissing)
        assertEquals(listOf("themes"), source.calls)
    }

    @Test
    fun themes_emptyAndFailedStates() = runTest(dispatcher) {
        source.themes = emptyList()
        viewModel.loadThemes()
        advanceUntilIdle()
        assertEquals(PickerState.Empty(P.THEMES_EMPTY), state.themes)

        source.failure = IllegalStateException("boom")
        viewModel.loadThemes()
        advanceUntilIdle()
        assertEquals(PickerState.Failed(P.LOAD_FAILED), state.themes)
    }

    @Test
    fun openingExampleThemeTemplate_loadsThemeList() = runTest(dispatcher) {
        viewModel.open(state.templates[1].id)
        advanceUntilIdle()

        assertTrue(state.themes is PickerState.Loaded)
        assertEquals("HBM", state.form!!.theme?.name)
    }

    @Test
    fun pickingRealTheme_andApplying_removesUnlinkedMarker() = runTest(dispatcher) {
        val b = state.templates[1]
        assertTrue(state.cards[1].target.endsWith(P.UNLINKED_MARKER))
        viewModel.open(b.id)
        advanceUntilIdle()

        viewModel.selectTheme((state.themes as PickerState.Loaded).items.single())
        viewModel.applyPreview()

        assertEquals("테마 · 반도체 (등록 종목 3개)", state.cards[1].target)
        assertTrue(P.BADGE_EXAMPLE in state.cards[1].badges)
    }

    @Test
    fun pickingRealStock_andApplying_removesUnlinkedMarker() = runTest(dispatcher) {
        val a = state.templates.first()
        viewModel.open(a.id)
        viewModel.updateStockQuery("삼성")
        viewModel.searchStocks()
        advanceUntilIdle()

        viewModel.selectStock((state.stockSearch as PickerState.Loaded).items.single())
        viewModel.applyPreview()

        assertEquals("종목 · 삼성전자 005930", state.cards.first().target)
        assertFalse(P.BADGE_EXAMPLE in state.cards.first().badges)
    }

    @Test
    fun close_leavesDetail_withoutChangingTemplates() {
        val before = state.templates
        viewModel.open(before.first().id)
        viewModel.updateForm { it.copy(name = "변경") }

        viewModel.close()

        assertNull(state.selectedId)
        assertNull(state.form)
        assertEquals(before, state.templates)
    }

    @Test
    fun viewModel_hasNoStrategyVersionCreationAction() {
        val names = TemplatePreviewViewModel::class.java.declaredMethods.map { it.name.lowercase() }
        assertTrue(names.none { it.contains("version") || it.contains("save") || it.contains("persist") || it.contains("strategyrun") })
    }
}
