package com.mirunubi.bjstock.core.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InstrumentRoomMigrationTest {
    @Test
    fun migrate1To2_keepsExistingInstrumentAndDefaultsNewColumns() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(TEST_DB)

        context.openOrCreateDatabase(TEST_DB, Context.MODE_PRIVATE, null).use { sqlite ->
            sqlite.execSQL(V1_INSTRUMENTS)
            sqlite.execSQL(
                """
                INSERT INTO instruments
                    (market, symbol, name, currency, is_active, created_at, updated_at)
                VALUES
                    ('KRX', '005930', '삼성전자', 'KRW', 1, 0, 0)
                """.trimIndent(),
            )
            sqlite.version = 1
        }

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DB)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(2) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            error("v1 database should already exist")
                        }

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) {
                            assertEquals(1, oldVersion)
                            assertEquals(2, newVersion)
                            BJStockMigrations.MIGRATION_1_2.migrate(db)
                        }
                    },
                )
                .build(),
        )

        helper.writableDatabase.use { migrated ->
            val cursor = migrated.query(
                "SELECT id, market, symbol, name, board, instrument_type FROM instruments",
            )
            cursor.use {
                assertEquals(true, it.moveToFirst())
                assertEquals(1L, it.getLong(it.getColumnIndexOrThrow("id")))
                assertEquals("KRX", it.getString(it.getColumnIndexOrThrow("market")))
                assertEquals("005930", it.getString(it.getColumnIndexOrThrow("symbol")))
                assertEquals("삼성전자", it.getString(it.getColumnIndexOrThrow("name")))
                assertEquals("OTHER", it.getString(it.getColumnIndexOrThrow("board")))
                assertEquals("OTHER", it.getString(it.getColumnIndexOrThrow("instrument_type")))
                assertEquals(false, it.moveToNext())
            }
        }
    }

    companion object {
        private const val TEST_DB = "instrument-migration-test"
        private const val V1_INSTRUMENTS =
            "CREATE TABLE IF NOT EXISTS `instruments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `market` TEXT NOT NULL, `symbol` TEXT NOT NULL, `name` TEXT NOT NULL, `sector` TEXT, `industry` TEXT, `currency` TEXT NOT NULL, `is_active` INTEGER NOT NULL, `listed_date` INTEGER, `delisted_date` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)"
    }
}
