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

    @Test
    fun migrate3To4_createsCashLedgerTable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(V3_TEST_DB)

        context.openOrCreateDatabase(V3_TEST_DB, Context.MODE_PRIVATE, null).use { sqlite ->
            sqlite.execSQL(
                """
                CREATE TABLE IF NOT EXISTS strategy_runs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    run_name TEXT NOT NULL
                )
                """.trimIndent(),
            )
            sqlite.version = 3
        }

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(V3_TEST_DB)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(4) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            error("v3 database should already exist")
                        }

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) {
                            assertEquals(3, oldVersion)
                            assertEquals(4, newVersion)
                            BJStockMigrations.MIGRATION_3_4.migrate(db)
                        }
                    },
                )
                .build(),
        )

        helper.writableDatabase.use { migrated ->
            migrated.query("SELECT name FROM sqlite_master WHERE type='table' AND name='cash_ledger'")
                .use {
                    assertEquals(true, it.moveToFirst())
                    assertEquals("cash_ledger", it.getString(0))
                }
        }
    }

    @Test
    fun migrate4To5_backfillsPaperTradingPolicyAndKeepsStrategyRun() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(V4_TEST_DB)

        context.openOrCreateDatabase(V4_TEST_DB, Context.MODE_PRIVATE, null).use { sqlite ->
            sqlite.execSQL(
                """
                CREATE TABLE IF NOT EXISTS strategy_runs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    run_name TEXT NOT NULL,
                    strategy_version_id INTEGER NOT NULL,
                    start_date INTEGER NOT NULL,
                    end_date INTEGER,
                    initial_cash INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            sqlite.execSQL(
                """
                INSERT INTO strategy_runs (
                    id, run_name, strategy_version_id, start_date, initial_cash, status, created_at, updated_at
                ) VALUES (42, 'legacy-run', 1, 20354, 100000000, 'READY', 0, 0)
                """.trimIndent(),
            )
            sqlite.version = 4
        }

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(V4_TEST_DB)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(5) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            error("v4 database should already exist")
                        }

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) {
                            assertEquals(4, oldVersion)
                            assertEquals(5, newVersion)
                            BJStockMigrations.MIGRATION_4_5.migrate(db)
                        }
                    },
                )
                .build(),
        )

        helper.writableDatabase.use { migrated ->
            migrated.query("SELECT id, run_name FROM strategy_runs WHERE id = 42").use {
                assertEquals(true, it.moveToFirst())
                assertEquals(42L, it.getLong(0))
                assertEquals("legacy-run", it.getString(1))
            }
            migrated.query(
                """
                SELECT strategy_run_id, policy_version, buy_allocation_rate, commission_rate,
                       sell_tax_rate, slippage_bps, execution_price_policy, additional_buy_policy,
                       sell_policy, short_selling_allowed
                FROM paper_trading_policies WHERE strategy_run_id = 42
                """.trimIndent(),
            ).use {
                assertEquals(true, it.moveToFirst())
                assertEquals(42L, it.getLong(0))
                assertEquals("v1", it.getString(1))
                assertEquals(100_000L, it.getLong(2))
                assertEquals(150L, it.getLong(3))
                assertEquals(2_000L, it.getLong(4))
                assertEquals(0L, it.getLong(5))
                assertEquals("NEXT_TRADING_DAY_OPEN", it.getString(6))
                assertEquals("DISALLOW", it.getString(7))
                assertEquals("FULL_POSITION", it.getString(8))
                assertEquals(0, it.getInt(9))
                assertEquals(false, it.moveToNext())
            }
        }
    }

    @Test
    fun migrate5To6_createsUniverseAndForwardCycleTables() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(V5_TEST_DB)

        context.openOrCreateDatabase(V5_TEST_DB, Context.MODE_PRIVATE, null).use { sqlite ->
            sqlite.execSQL(
                """
                CREATE TABLE IF NOT EXISTS strategy_runs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    run_name TEXT NOT NULL
                )
                """.trimIndent(),
            )
            sqlite.execSQL(
                """
                CREATE TABLE IF NOT EXISTS instruments (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    symbol TEXT NOT NULL
                )
                """.trimIndent(),
            )
            sqlite.execSQL("INSERT INTO strategy_runs (id, run_name) VALUES (7, 'legacy')")
            sqlite.execSQL("INSERT INTO instruments (id, symbol) VALUES (3, '005930')")
            sqlite.version = 5
        }

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(V5_TEST_DB)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(6) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            error("v5 database should already exist")
                        }

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) {
                            assertEquals(5, oldVersion)
                            assertEquals(6, newVersion)
                            BJStockMigrations.MIGRATION_5_6.migrate(db)
                        }
                    },
                )
                .build(),
        )

        helper.writableDatabase.use { migrated ->
            migrated.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='strategy_run_instruments'",
            ).use {
                assertEquals(true, it.moveToFirst())
            }
            migrated.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='forward_test_cycles'",
            ).use {
                assertEquals(true, it.moveToFirst())
            }
            migrated.execSQL(
                """
                INSERT INTO strategy_run_instruments (strategy_run_id, instrument_id, created_at)
                VALUES (7, 3, 0)
                """.trimIndent(),
            )
            migrated.execSQL(
                """
                INSERT INTO forward_test_cycles (
                    strategy_run_id, market_date, status, current_stage, attempt_count,
                    retryable, created_at, updated_at
                ) VALUES (7, 20354, 'COMPLETE', 'COMPLETE', 1, 0, 0, 0)
                """.trimIndent(),
            )
            migrated.query("SELECT strategy_run_id, instrument_id FROM strategy_run_instruments")
                .use {
                    assertEquals(true, it.moveToFirst())
                    assertEquals(7L, it.getLong(0))
                    assertEquals(3L, it.getLong(1))
                }
            migrated.query(
                "SELECT strategy_run_id, status FROM forward_test_cycles WHERE strategy_run_id = 7",
            ).use {
                assertEquals(true, it.moveToFirst())
                assertEquals(7L, it.getLong(0))
                assertEquals("COMPLETE", it.getString(1))
            }
            migrated.query("SELECT id, run_name FROM strategy_runs WHERE id = 7").use {
                assertEquals(true, it.moveToFirst())
                assertEquals("legacy", it.getString(1))
            }
        }
    }


    @Test
    fun migrate6To7_createsThemeRuleAuditTables() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(V6_TEST_DB)

        context.openOrCreateDatabase(V6_TEST_DB, Context.MODE_PRIVATE, null).use { sqlite ->
            sqlite.execSQL(
                """
                CREATE TABLE IF NOT EXISTS strategy_versions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL
                )
                """.trimIndent(),
            )
            sqlite.execSQL(
                """
                CREATE TABLE IF NOT EXISTS strategy_runs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL
                )
                """.trimIndent(),
            )
            sqlite.execSQL(
                """
                CREATE TABLE IF NOT EXISTS instruments (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL
                )
                """.trimIndent(),
            )
            sqlite.execSQL("INSERT INTO strategy_versions (id) VALUES (1)")
            sqlite.execSQL("INSERT INTO strategy_runs (id) VALUES (9)")
            sqlite.execSQL("INSERT INTO instruments (id) VALUES (5)")
            sqlite.version = 6
        }

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(V6_TEST_DB)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(7) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            error("v6 database should already exist")
                        }

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) {
                            assertEquals(6, oldVersion)
                            assertEquals(7, newVersion)
                            BJStockMigrations.MIGRATION_6_7.migrate(db)
                        }
                    },
                )
                .build(),
        )

        helper.writableDatabase.use { migrated ->
            for (table in listOf(
                "themes",
                "theme_instruments",
                "strategy_signal_rules",
                "trade_audit_logs",
                "api_error_logs",
            )) {
                migrated.query(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='$table'",
                ).use {
                    assertEquals(true, it.moveToFirst())
                }
            }
            migrated.execSQL(
                """
                INSERT INTO themes (name, description, is_active, created_at, updated_at)
                VALUES ('HBM', NULL, 1, 0, 0)
                """.trimIndent(),
            )
            migrated.execSQL(
                """
                INSERT INTO theme_instruments (theme_id, instrument_id, note, created_at)
                VALUES (1, 5, NULL, 0)
                """.trimIndent(),
            )
            migrated.execSQL(
                """
                INSERT INTO strategy_signal_rules (
                    strategy_version_id, rule_code, metric_code, operator, threshold_value,
                    action, priority, enabled, rule_version, description, created_at
                ) VALUES (1, 'SELL_3', 'DAILY_CHANGE_PCT', 'GTE', '3.0', 'SELL', 10, 1, 'v1', NULL, 0)
                """.trimIndent(),
            )
            migrated.execSQL(
                """
                INSERT INTO trade_audit_logs (
                    strategy_run_id, event_type, event_key, created_at
                ) VALUES (9, 'EVALUATION_DECIDED', 'evaluation:1:decision', 0)
                """.trimIndent(),
            )
            migrated.execSQL(
                """
                INSERT INTO api_error_logs (
                    provider, operation, error_type, safe_message, retryable, occurred_at
                ) VALUES ('KIS', 'KIS_OAUTH', 'AUTH_ERROR', 'auth failed', 0, 0)
                """.trimIndent(),
            )
            migrated.query("SELECT name FROM themes").use {
                assertEquals(true, it.moveToFirst())
                assertEquals("HBM", it.getString(0))
            }
            migrated.query("SELECT COUNT(*) FROM theme_instruments").use {
                assertEquals(true, it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
            migrated.query("SELECT rule_code FROM strategy_signal_rules").use {
                assertEquals(true, it.moveToFirst())
                assertEquals("SELL_3", it.getString(0))
            }
            migrated.query("SELECT event_key FROM trade_audit_logs").use {
                assertEquals(true, it.moveToFirst())
                assertEquals("evaluation:1:decision", it.getString(0))
            }
            migrated.query("SELECT operation FROM api_error_logs").use {
                assertEquals(true, it.moveToFirst())
                assertEquals("KIS_OAUTH", it.getString(0))
            }
        }
    }

    companion object {
        private const val TEST_DB = "instrument-migration-test"
        private const val V2_TEST_DB = "strategy-weight-migration-test"
        private const val V3_TEST_DB = "cash-ledger-migration-test"
        private const val V4_TEST_DB = "paper-policy-migration-test"
        private const val V5_TEST_DB = "forward-orch-migration-test"
        private const val V6_TEST_DB = "theme-rule-audit-migration-test"
        private const val V1_INSTRUMENTS =
            "CREATE TABLE IF NOT EXISTS `instruments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `market` TEXT NOT NULL, `symbol` TEXT NOT NULL, `name` TEXT NOT NULL, `sector` TEXT, `industry` TEXT, `currency` TEXT NOT NULL, `is_active` INTEGER NOT NULL, `listed_date` INTEGER, `delisted_date` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)"
        private const val V2_STRATEGY_FACTOR_WEIGHTS =
            "CREATE TABLE IF NOT EXISTS `strategy_factor_weights` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `strategy_version_id` INTEGER NOT NULL, `factor_id` INTEGER NOT NULL, `weight` INTEGER NOT NULL, `min_score` INTEGER, `max_score` INTEGER, `enabled` INTEGER NOT NULL, `created_at` INTEGER NOT NULL)"
    }
}
