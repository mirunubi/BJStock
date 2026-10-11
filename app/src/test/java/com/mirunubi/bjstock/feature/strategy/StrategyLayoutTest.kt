package com.mirunubi.bjstock.feature.strategy

import com.mirunubi.bjstock.feature.strategy.template.FakeTemplatePreviewReadSource
import com.mirunubi.bjstock.feature.strategy.template.TemplatePreviewViewModel
import com.mirunubi.bjstock.ui.navigation.NavWidthClass
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StrategyLayoutTest {
    private val dispatcher = StandardTestDispatcher()
    private val strategySource = FakeStrategyDataSource()
    private val panel = StrategyPanel(StrategyFixtures.STRATEGY_ID, "기본 모멘텀 전략", "MOMENTUM_BASIC", VersionsState.Loading)
    private val versionLayer = VersionLayer.Loading(20)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // region A–C mode mapping

    @Test
    fun a_compact_isSinglePane() {
        assertEquals(StrategyLayoutMode.SINGLE_PANE, StrategyLayout.modeFor(NavWidthClass.COMPACT))
    }

    @Test
    fun b_medium_isSinglePane() {
        assertEquals(StrategyLayoutMode.SINGLE_PANE, StrategyLayout.modeFor(NavWidthClass.MEDIUM))
    }

    @Test
    fun c_expanded_isListDetail() {
        assertEquals(StrategyLayoutMode.LIST_DETAIL, StrategyLayout.modeFor(NavWidthClass.EXPANDED))
        assertEquals(360, StrategyLayout.MASTER_PANE_WIDTH_DP)
    }

    // endregion

    // region D–H detail resolver

    @Test
    fun d_nothingOpen_resolvesToNone_withoutAutoSelection() = runTest(dispatcher) {
        val (strategies, preview) = viewModels()
        assertTrue((strategies.uiState.value.list as ListState.Loaded).cards.isNotEmpty())
        assertTrue(preview.uiState.value.templates.isNotEmpty())

        assertEquals(StrategyDetail.None, resolve(strategies, preview))
        assertEquals(StrategyDetail.None, StrategyLayout.detailOf(null, null, null))
        assertEquals("왼쪽에서 미리보기 템플릿 또는 전략을 선택하세요.", StrategyLayout.EMPTY_DETAIL)
    }

    @Test
    fun e_template_winsOverVersionAndStrategy() {
        assertEquals(StrategyDetail.Template(7), StrategyLayout.detailOf(7, versionLayer, panel))
        assertEquals(StrategyDetail.Template(7), StrategyLayout.detailOf(7, null, null))
    }

    @Test
    fun f_version_winsOverStrategy() {
        assertEquals(StrategyDetail.Version(versionLayer), StrategyLayout.detailOf(null, versionLayer, panel))
    }

    @Test
    fun g_strategyAlone_resolvesToStrategy() {
        assertEquals(StrategyDetail.Strategy(panel), StrategyLayout.detailOf(null, null, panel))
    }

    @Test
    fun h_detailKey_followsTheOpenItem() {
        val keys = listOf(
            StrategyDetail.None,
            StrategyDetail.Template(7),
            StrategyDetail.Template(8),
            StrategyDetail.Version(versionLayer),
            StrategyDetail.Version(VersionLayer.Failed(30, "x")),
            StrategyDetail.Strategy(panel),
        ).map(StrategyLayout::detailKey)
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(
            StrategyLayout.detailKey(StrategyDetail.Version(VersionLayer.Loading(20))),
            StrategyLayout.detailKey(StrategyDetail.Version(VersionLayer.Failed(20, "x"))),
        )
    }

    // endregion

    // region I–N exclusive open

    @Test
    fun i_openTemplate_closesOpenStrategyAndVersion() = runTest(dispatcher) {
        val (strategies, preview) = viewModels()
        val families = StrategyFamilies(strategies, preview)
        families.openStrategy(StrategyFixtures.STRATEGY_ID)
        advanceUntilIdle()
        families.openVersion(20)
        advanceUntilIdle()
        assertNotNull(strategies.uiState.value.version)

        val templateId = preview.uiState.value.templates.first().id
        families.openTemplate(templateId)
        advanceUntilIdle()

        assertNull(strategies.uiState.value.version)
        assertNull(strategies.uiState.value.strategy)
        assertEquals(templateId, preview.uiState.value.selectedId)
        assertEquals(StrategyDetail.Template(templateId), resolve(strategies, preview))
        assertTrue(strategySource.writes.isEmpty())
    }

    @Test
    fun j_openStrategy_closesOpenTemplate() = runTest(dispatcher) {
        val (strategies, preview) = viewModels()
        val families = StrategyFamilies(strategies, preview)
        families.openTemplate(preview.uiState.value.templates.first().id)

        families.openStrategy(StrategyFixtures.STRATEGY_ID)
        advanceUntilIdle()

        assertNull(preview.uiState.value.selectedId)
        assertEquals(StrategyFixtures.STRATEGY_ID, strategies.uiState.value.strategy?.strategyId)
        assertTrue(resolve(strategies, preview) is StrategyDetail.Strategy)
        assertTrue(strategySource.writes.isEmpty())
    }

    @Test
    fun k_openVersion_closesOpenTemplate() = runTest(dispatcher) {
        val (strategies, preview) = viewModels()
        val families = StrategyFamilies(strategies, preview)
        families.openStrategy(StrategyFixtures.STRATEGY_ID)
        advanceUntilIdle()
        preview.open(preview.uiState.value.templates.first().id)

        families.openVersion(20)
        advanceUntilIdle()

        assertNull(preview.uiState.value.selectedId)
        assertEquals(20L, strategies.uiState.value.version?.versionId)
        assertTrue(resolve(strategies, preview) is StrategyDetail.Version)
        assertTrue(strategySource.writes.isEmpty())
    }

    @Test
    fun l_newStrategy_closesOpenTemplate_andOnlyShowsTheDialog() = runTest(dispatcher) {
        val (strategies, preview) = viewModels()
        val families = StrategyFamilies(strategies, preview)
        families.openTemplate(preview.uiState.value.templates.first().id)

        families.showCreateStrategy()
        advanceUntilIdle()

        assertNull(preview.uiState.value.selectedId)
        assertTrue(strategies.uiState.value.dialog is StrategyDialog.CreateStrategy)
        assertEquals(StrategyDetail.None, resolve(strategies, preview))
        assertTrue(strategySource.writes.isEmpty())
    }

    @Test
    fun m_newTemplate_closesOpenStrategy_andStaysInMemory() = runTest(dispatcher) {
        val (strategies, preview) = viewModels()
        val families = StrategyFamilies(strategies, preview)
        families.openStrategy(StrategyFixtures.STRATEGY_ID)
        advanceUntilIdle()
        val before = preview.uiState.value.templates.size

        families.createTemplate()
        advanceUntilIdle()

        assertNull(strategies.uiState.value.strategy)
        assertEquals(before + 1, preview.uiState.value.templates.size)
        assertEquals(preview.uiState.value.templates.last().id, preview.uiState.value.selectedId)
        assertTrue(strategySource.writes.isEmpty())
    }

    @Test
    fun n_anySequence_keepsAtMostOneFamilyOpen_andWritesNothing() = runTest(dispatcher) {
        val (strategies, preview) = viewModels()
        val families = StrategyFamilies(strategies, preview)
        val templateId = preview.uiState.value.templates.first().id
        preview.duplicate(templateId)
        val message = preview.uiState.value.message
        assertNotNull(message)

        families.openStrategy(StrategyFixtures.STRATEGY_ID)
        advanceUntilIdle()
        assertEquals(message, preview.uiState.value.message)

        val steps: List<() -> Unit> = listOf(
            { families.openTemplate(templateId) },
            { families.openVersion(20) },
            { families.createTemplate() },
            { families.openStrategy(StrategyFixtures.STRATEGY_ID) },
            { families.openTemplate(templateId) },
            { families.showCreateStrategy() },
            { strategies.dismissDialog() },
            { families.openVersion(30) },
            { families.openTemplate(templateId) },
        )
        steps.forEach { step ->
            step()
            advanceUntilIdle()
            val templateOpen = preview.uiState.value.selectedId != null
            val realOpen = strategies.uiState.value.strategy != null || strategies.uiState.value.version != null
            assertFalse("both families open", templateOpen && realOpen)
        }
        assertTrue(strategySource.writes.isEmpty())
    }

    // endregion

    @Test
    fun o_deletingTheOpenTemplate_resolvesToNone() = runTest(dispatcher) {
        val (strategies, preview) = viewModels()
        val families = StrategyFamilies(strategies, preview)
        val templateId = preview.uiState.value.templates.first().id
        families.openTemplate(templateId)

        preview.requestDelete(templateId)
        preview.confirmDelete()

        assertNull(preview.uiState.value.selectedId)
        assertEquals(StrategyDetail.None, resolve(strategies, preview))
        assertTrue(strategySource.writes.isEmpty())
    }

    // region P–R highlight

    @Test
    fun p_singlePane_highlightsNothing() {
        assertEquals(StrategyMasterSelection(null, null), StrategyLayout.masterSelection(StrategyLayoutMode.SINGLE_PANE, 7, null))
        assertEquals(StrategyMasterSelection(null, null), StrategyLayout.masterSelection(StrategyLayoutMode.SINGLE_PANE, null, panel))
    }

    @Test
    fun q_listDetail_highlightsTheOpenTemplateOnly() {
        assertEquals(StrategyMasterSelection(7, null), StrategyLayout.masterSelection(StrategyLayoutMode.LIST_DETAIL, 7, null))
        assertEquals(StrategyMasterSelection(7, null), StrategyLayout.masterSelection(StrategyLayoutMode.LIST_DETAIL, 7, panel))
    }

    @Test
    fun r_listDetail_highlightsTheOpenStrategyOnly() {
        assertEquals(
            StrategyMasterSelection(null, StrategyFixtures.STRATEGY_ID),
            StrategyLayout.masterSelection(StrategyLayoutMode.LIST_DETAIL, null, panel),
        )
        assertEquals(StrategyMasterSelection(null, null), StrategyLayout.masterSelection(StrategyLayoutMode.LIST_DETAIL, null, null))
    }

    // endregion

    @Test
    fun strategySources_keepTheShellContract() {
        val strategyDir = listOf(
            File("src/main/java/com/mirunubi/bjstock/feature/strategy"),
            File("app/src/main/java/com/mirunubi/bjstock/feature/strategy"),
        ).first { it.isDirectory }
        val screen = File(strategyDir, "StrategyScreen.kt").readText()
        assertTrue(screen.contains("StrategyLayout.modeFor(LocalNavChrome.current.widthClass)"))
        assertTrue(screen.contains("val layered = previewOpen || state.strategy != null || state.version != null"))
        assertTrue(screen.contains("onBack = if (layered) back else null"))
        assertTrue(screen.contains("LayerBackHandler(hasInScreenLayer = layered)"))
        assertFalse(Regex("""(?<!Layer)BackHandler\(""").containsMatchIn(screen))

        val previewUi = File(strategyDir, "template/TemplatePreviewUi.kt").readText()
        assertTrue(previewUi.contains("OutlinedButton(onClick = {}, enabled = false"))

        strategyDir.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val source = file.readText()
            listOf("fromWidthDp(", "currentNavWidthClass(", "screenWidthDp", "material3.adaptive").forEach {
                assertFalse("${file.name}: $it", source.contains(it))
            }
            assertFalse(file.name, Regex("""\b(600|840)\b""").containsMatchIn(source))
        }
    }

    private fun TestScope.viewModels(): Pair<StrategyViewModel, TemplatePreviewViewModel> {
        val strategies = StrategyViewModel(strategySource)
        val preview = TemplatePreviewViewModel(FakeTemplatePreviewReadSource())
        advanceUntilIdle()
        return strategies to preview
    }

    private fun resolve(strategies: StrategyViewModel, preview: TemplatePreviewViewModel): StrategyDetail =
        StrategyLayout.detailOf(preview.uiState.value.selectedId, strategies.uiState.value.version, strategies.uiState.value.strategy)
}
