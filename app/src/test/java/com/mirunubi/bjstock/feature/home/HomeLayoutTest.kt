package com.mirunubi.bjstock.feature.home

import com.mirunubi.bjstock.ui.navigation.NavWidthClass
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** HOME-ADAPT-02: Compact / Medium keep the Phase 3 single column; Expanded splits the same sections into two columns. */
class HomeLayoutTest {
    private val canonical = listOf(
        "모의자산", "실행 중 전략", "최근 전략 판단", "최근 처리일 신호", "보유현황",
        "최근 주문 · 체결", "자동운영", "운영 경고", "최근 오류", "최근 Audit",
    )

    @Test
    fun singleColumn_keepsTheCanonicalPhase3Order() {
        assertEquals(canonical, HomeLayout.singleColumn.map { it.title })
    }

    @Test
    fun compactAndMedium_staySingleColumn_expandedIsTwoColumn() {
        assertEquals(HomeLayoutMode.SINGLE_COLUMN, HomeLayout.modeFor(NavWidthClass.COMPACT))
        assertEquals(HomeLayoutMode.SINGLE_COLUMN, HomeLayout.modeFor(NavWidthClass.MEDIUM))
        assertEquals(HomeLayoutMode.TWO_COLUMN, HomeLayout.modeFor(NavWidthClass.EXPANDED))
    }

    @Test
    fun expandedColumns_matchTheApprovedGrouping() {
        assertEquals(listOf("모의자산", "실행 중 전략", "보유현황", "최근 주문 · 체결"), HomeLayout.left.map { it.title })
        assertEquals(
            listOf("최근 전략 판단", "최근 처리일 신호", "자동운영", "운영 경고", "최근 오류", "최근 Audit"),
            HomeLayout.right.map { it.title },
        )
    }

    @Test
    fun expandedColumns_neitherOmitNorDuplicateASection() {
        val placed = HomeLayout.left + HomeLayout.right
        assertEquals(HomeSlot.entries.size, placed.size)
        assertEquals(HomeSlot.entries.toSet(), placed.toSet())
        assertTrue(HomeLayout.left.intersect(HomeLayout.right.toSet()).isEmpty())
    }

    @Test
    fun eachColumn_keepsTheCanonicalRelativeOrder() {
        listOf(HomeLayout.left, HomeLayout.right).forEach { column ->
            assertEquals(column.sortedBy { it.ordinal }, column)
        }
    }

    @Test
    fun slotTitles_comeFromTheExistingSectionConstants() {
        HomeActivitySection.entries.forEach { section ->
            val slot = HomeSlot.entries.single { it.activity == section }
            assertEquals(section.title, slot.title)
        }
        assertEquals(
            listOf(HomeSlot.PORTFOLIO.title) + HomePresenter.CORE_DETAIL_SECTIONS,
            HomeSlot.entries.filter { it.isCore }.map { it.title },
        )
    }

    @Test
    fun rightColumnCoreNotice_onlyOnCoreFailure_withTheExistingDisclosure() {
        assertEquals(
            HomePresenter.CORE_SECTIONS_UNAVAILABLE,
            HomeLayout.rightColumnCoreNotice(HomeUiState.Error(HomePresenter.LOAD_FAILED)),
        )
        assertNull(HomeLayout.rightColumnCoreNotice(HomeUiState.Loading))
        assertNull(HomeLayout.rightColumnCoreNotice(HomePresenter.present(HomeFixtures.snapshot())))
    }

    @Test
    fun rightColumn_holdsCoreCardsThatAreHiddenOnCoreFailure() {
        assertEquals(
            listOf(HomeSlot.DECISION, HomeSlot.AUTO, HomeSlot.ALERTS),
            HomeLayout.right.filter { it.isCore },
        )
        HomeLayout.right.filter { it.isCore }.forEach {
            assertTrue(it.title, HomePresenter.CORE_SECTIONS_UNAVAILABLE.contains(it.title))
        }
        assertEquals(
            listOf(HomeSlot.SIGNALS, HomeSlot.ERRORS, HomeSlot.AUDIT),
            HomeLayout.right.filterNot { it.isCore },
        )
    }

    @Test
    fun homeScreen_usesOneScroll_noGrid_andTheNavWidthClass() {
        val homeDir = listOf(
            File("src/main/java/com/mirunubi/bjstock/feature/home"),
            File("app/src/main/java/com/mirunubi/bjstock/feature/home"),
        ).first { it.isDirectory }
        val screen = File(homeDir, "HomeScreen.kt").readText()
        assertEquals(1, Regex("""rememberScrollState\(""").findAll(screen).count())
        assertEquals(1, Regex("""verticalScroll\(""").findAll(screen).count())
        assertTrue(screen.contains("HomeLayout.modeFor(LocalNavChrome.current.widthClass)"))
        assertTrue(screen.contains("HomeLayout.rightColumnCoreNotice(core)?.let { StatusLine(icon = true, isError = true, text = it) }"))
        assertEquals(1, Regex("""onClick = onRetryCore""").findAll(screen).count())
        homeDir.listFiles().orEmpty().filter { it.extension == "kt" }.forEach { file ->
            val source = file.readText()
            listOf("LazyVerticalGrid", "LazyVerticalStaggeredGrid", "fromWidthDp(", "currentNavWidthClass(", "screenWidthDp").forEach {
                assertFalse("${file.name}: $it", source.contains(it))
            }
            assertFalse(file.name, Regex("""\b(600|840)\b""").containsMatchIn(source))
        }
    }
}
