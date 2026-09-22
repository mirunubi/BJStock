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
    fun migrate2To3_keepsStrategyWeightAndPinsV1() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(V2_TEST_DB)

        context.openOrCreateDatabase(V2_TEST_DB, Context.MODE_PRIVATE, null).use { sqlite ->
            sqlite.execSQL(V2_STRATEGY_FACTOR_WEIGHTS)
            sqlite.execSQL(
                """
                INSERT INTO strategy_factor_weights
                    (id, strategy_version_id, factor_id, weight, min_score, max_score, enabled, created_at)
                VALUES
                    (17, 9, 3, 250000, 400000, NULL, 1, 0)
                """.trimIndent(),
            )
            sqlite.version = 2
        }

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(V2_TEST_DB)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(3) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            error("v2 database should already exist")
                        }

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) {
                            assertEquals(2, oldVersion)
                            assertEquals(3, newVersion)
                            BJStockMigrations.MIGRATION_2_3.migrate(db)
                        }
                    },
                )
                .build(),
        )

        helper.writableDatabase.use { migrated ->
            migrated.query(
                """
                SELECT id, strategy_version_id, factor_id, weight, factor_calculation_version
                FROM strategy_factor_weights
                """.trimIndent(),
            ).use {
                assertEquals(true, it.moveToFirst())
                assertEquals(17L, it.getLong(it.getColumnIndexOrThrow("id")))
                assertEquals(9L, it.getLong(it.getColumnIndexOrThrow("strategy_version_id")))
                assertEquals(3L, it.getLong(it.getColumnIndexOrThrow("factor_id")))
                assertEquals(250000L, it.getLong(it.getColumnIndexOrThrow("weight")))
                assertEquals("v1", it.getString(it.getColumnIndexOrThrow("factor_calculation_version")))
            }
        }
    }

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
        private const val V2_TEST_DB = "strategy-weight-migration-test"
        private const val V1_INSTRUMENTS =
            "CREATE TABLE IF NOT EXISTS `instruments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `market` TEXT NOT NULL, `symbol` TEXT NOT NULL, `name` TEXT NOT NULL, `sector` TEXT, `industry` TEXT, `currency` TEXT NOT NULL, `is_active` INTEGER NOT NULL, `listed_date` INTEGER, `delisted_date` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)"
        private const val V2_STRATEGY_FACTOR_WEIGHTS =
            "CREATE TABLE IF NOT EXISTS `strategy_factor_weights` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `strategy_version_id` INTEGER NOT NULL, `factor_id` INTEGER NOT NULL, `weight` INTEGER NOT NULL, `min_score` INTEGER, `max_score` INTEGER, `enabled` INTEGER NOT NULL, `created_at` INTEGER NOT NULL)"
    }
}
