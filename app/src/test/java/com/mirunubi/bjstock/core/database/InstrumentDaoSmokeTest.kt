package com.mirunubi.bjstock.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InstrumentDaoSmokeTest {
    private lateinit var database: BJStockDatabase

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
    fun insertInstrument_countIsOne() = runBlocking {
        assertEquals(0, database.instrumentDao().count())
        database.instrumentDao().insert(
            InstrumentEntity(
                market = "KRX",
                symbol = "005930",
                name = "Smoke Test Instrument",
            ),
        )
        assertEquals(1, database.instrumentDao().count())
    }
}
