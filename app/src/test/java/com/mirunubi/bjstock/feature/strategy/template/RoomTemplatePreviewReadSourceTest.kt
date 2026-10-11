package com.mirunubi.bjstock.feature.strategy.template

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.model.Board
import com.mirunubi.bjstock.core.theme.ThemeService
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomTemplatePreviewReadSourceTest {
    private lateinit var database: BJStockDatabase
    private lateinit var source: RoomTemplatePreviewReadSource
    private var semiconductor = 0L

    private val watchedTables = listOf(
        "instruments",
        "themes",
        "theme_instruments",
        "strategies",
        "strategy_versions",
        "strategy_runs",
        "strategy_run_instruments",
    )

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BJStockDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val instruments = database.instrumentDao()
        val samsung = instruments.insert(InstrumentEntity(market = "KRX", symbol = "005930", name = "삼성전자", board = Board.KOSPI))
        val hynix = instruments.insert(InstrumentEntity(market = "KRX", symbol = "000660", name = "SK하이닉스", board = Board.KOSPI))
        instruments.insert(InstrumentEntity(market = "KRX", symbol = "005935", name = "삼성전자우", board = Board.KOSPI, isActive = false))
        val themes = ThemeService(database.themeDao(), instruments) { Instant.EPOCH }
        semiconductor = themes.createTheme("반도체")
        themes.addInstrument(semiconductor, samsung)
        themes.addInstrument(semiconductor, hynix)
        val retired = themes.createTheme("종료 테마")
        themes.setActive(retired, false)
        source = RoomTemplatePreviewReadSource(instruments, themes)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun counts(): Map<String, Long> = watchedTables.associateWith { table ->
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }
    }

    @Test
    fun searchStocks_returnsActiveMatchesByNameOrSymbol() = runBlocking {
        assertEquals(listOf("005930"), source.searchStocks("삼성").map { it.symbol })
        assertEquals(listOf("000660"), source.searchStocks("0006").map { it.symbol })
        assertEquals(emptyList<InstrumentOption>(), source.searchStocks("없는종목"))
    }

    @Test
    fun themes_listsActiveThemesWithMemberCount() = runBlocking {
        assertEquals(listOf(ThemeOption(semiconductor, "반도체", 2)), source.themes())
    }

    @Test
    fun lookups_writeNothing() = runBlocking {
        val before = counts()

        source.searchStocks("삼성")
        source.searchStocks("")
        source.themes()

        assertEquals(before, counts())
    }
}
