package com.mirunubi.bjstock.core.instrument

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.model.Board
import com.mirunubi.bjstock.core.model.InstrumentType
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InstrumentMasterSynchronizerTest {
    private lateinit var database: BJStockDatabase
    private val downloader = FakeInstrumentMasterDownloader()
    private val policy = InstrumentMasterSyncPolicy(
        firstSyncMinParsed = 1,
        existingMinRatio = 0.8,
        maxInvalidRatio = 0.20,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BJStockDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun update_keepsIdAndReplacesName() = runBlocking {
        val originalId = database.instrumentDao().insert(
            InstrumentEntity(
                market = "KRX",
                symbol = "005930",
                name = "OLD",
                board = Board.KOSPI,
                instrumentType = InstrumentType.COMMON_STOCK,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )
        downloader.bytes[Board.KOSPI] = MstFixtures.join(
            MstFixtures.kospiLine("005930", "KR7005930003", "삼성전자"),
        )
        val result = synchronizer().sync(Board.KOSPI)
        val stored = database.instrumentDao().findByMarketAndSymbol("KRX", "005930")!!
        assertTrue(result.success)
        assertEquals(originalId, stored.id)
        assertEquals("삼성전자", stored.name)
        assertEquals("KR7005930003", stored.standardCode)
        assertEquals(0, result.inserted)
        assertEquals(1, result.updated)
    }

    @Test
    fun boardMovement_keepsInstrumentId() = runBlocking {
        val originalId = database.instrumentDao().insert(
            InstrumentEntity(
                market = "KRX",
                symbol = "123456",
                name = "이동종목",
                board = Board.KOSDAQ,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )
        downloader.bytes[Board.KOSPI] = MstFixtures.join(
            MstFixtures.kospiLine("123456", "KR7123450001", "이동종목"),
        )
        synchronizer().sync(Board.KOSPI)
        val stored = database.instrumentDao().findByMarketAndSymbol("KRX", "123456")!!
        assertEquals(originalId, stored.id)
        assertEquals(Board.KOSPI, stored.board)
        assertEquals(1, database.instrumentDao().count())
    }

    @Test
    fun missingInstrument_isDeactivatedOnlyAfterCompleteSync() = runBlocking {
        val kept = listOf("000001", "000002", "000003", "000004")
        kept.forEach { symbol ->
            database.instrumentDao().insert(
                InstrumentEntity(
                    market = "KRX",
                    symbol = symbol,
                    name = "유지$symbol",
                    board = Board.KOSPI,
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                ),
            )
        }
        database.instrumentDao().insert(
            InstrumentEntity(
                market = "KRX",
                symbol = "000660",
                name = "사라질종목",
                board = Board.KOSPI,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )
        downloader.bytes[Board.KOSPI] = MstFixtures.join(
            *kept.map { symbol ->
                MstFixtures.kospiLine(symbol, "KR7${symbol}0001", "유지$symbol")
            }.toTypedArray(),
        )
        val result = synchronizer().sync(Board.KOSPI)
        assertTrue(result.success)
        assertEquals(1, result.deactivated)
        kept.forEach { symbol ->
            assertTrue(database.instrumentDao().findByMarketAndSymbol("KRX", symbol)!!.isActive)
        }
        assertFalse(database.instrumentDao().findByMarketAndSymbol("KRX", "000660")!!.isActive)
        assertEquals(5, database.instrumentDao().count())
    }

    @Test
    fun incompleteMaster_doesNotDeactivateExistingRows() = runBlocking {
        repeat(5) { index ->
            database.instrumentDao().insert(
                InstrumentEntity(
                    market = "KRX",
                    symbol = "%06d".format(index + 1),
                    name = "기존$index",
                    board = Board.KOSPI,
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                ),
            )
        }
        downloader.bytes[Board.KOSPI] = MstFixtures.join(
            MstFixtures.kospiLine("000001", "KR7000000001", "일부만"),
        )
        val result = synchronizer().sync(Board.KOSPI)
        assertFalse(result.success)
        assertEquals(0, result.deactivated)
        assertEquals(0, result.updated)
        val stillActive = database.instrumentDao().findActiveByMarketAndBoard("KRX", Board.KOSPI)
        assertEquals(5, stillActive.size)
        assertTrue(stillActive.all { it.isActive })
    }

    @Test
    fun parserFailureFixture_doesNotDeactivate() = runBlocking {
        database.instrumentDao().insert(
            InstrumentEntity(
                market = "KRX",
                symbol = "005930",
                name = "삼성전자",
                board = Board.KOSPI,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )
        downloader.bytes[Board.KOSPI] = MstFixtures.join(
            MstFixtures.kospiLine("ABC123", "KR7000000001", "잘못된코드"),
            MstFixtures.kospiLine("12", "KR7000000002", "짧음"),
        )
        val result = synchronizer().sync(Board.KOSPI)
        assertFalse(result.success)
        assertTrue(database.instrumentDao().findByMarketAndSymbol("KRX", "005930")!!.isActive)
        assertEquals(0, result.deactivated)
    }

    private fun synchronizer() = InstrumentMasterSynchronizer(
        downloader = downloader,
        parser = KisMstParser(),
        database = database,
        instrumentDao = database.instrumentDao(),
        policy = policy,
        now = { Instant.ofEpochMilli(1_000) },
    )
}

private class FakeInstrumentMasterDownloader : InstrumentMasterDownloader {
    val bytes = mutableMapOf<Board, ByteArray>()

    override suspend fun downloadMstBytes(board: Board): ByteArray {
        return bytes[board] ?: error("no fixture for $board")
    }
}
