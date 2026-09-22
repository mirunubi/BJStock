package com.mirunubi.bjstock.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object BJStockMigrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE instruments ADD COLUMN standard_code TEXT")
            db.execSQL(
                "ALTER TABLE instruments ADD COLUMN board TEXT NOT NULL DEFAULT 'OTHER'",
            )
            db.execSQL(
                "ALTER TABLE instruments ADD COLUMN instrument_type TEXT NOT NULL DEFAULT 'OTHER'",
            )
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE strategy_factor_weights ADD COLUMN factor_calculation_version TEXT NOT NULL DEFAULT 'v1'",
            )
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `cash_ledger` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `strategy_run_id` INTEGER NOT NULL,
                    `event_type` TEXT NOT NULL,
                    `amount` INTEGER NOT NULL,
                    `balance_after` INTEGER NOT NULL,
                    `reference_type` TEXT,
                    `reference_id` INTEGER,
                    `event_date` INTEGER NOT NULL,
                    `created_at` INTEGER NOT NULL,
                    FOREIGN KEY(`strategy_run_id`) REFERENCES `strategy_runs`(`id`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `idx_cash_ledger_run_created` ON `cash_ledger` (`strategy_run_id`, `id`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `idx_cash_ledger_run_event_date` ON `cash_ledger` (`strategy_run_id`, `event_date`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `idx_cash_ledger_run_event_type` ON `cash_ledger` (`strategy_run_id`, `event_type`)",
            )
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `paper_trading_policies` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `strategy_run_id` INTEGER NOT NULL,
                    `policy_version` TEXT NOT NULL,
                    `buy_allocation_rate` INTEGER NOT NULL,
                    `commission_rate` INTEGER NOT NULL,
                    `sell_tax_rate` INTEGER NOT NULL,
                    `slippage_bps` INTEGER NOT NULL,
                    `execution_price_policy` TEXT NOT NULL,
                    `additional_buy_policy` TEXT NOT NULL,
                    `sell_policy` TEXT NOT NULL,
                    `short_selling_allowed` INTEGER NOT NULL,
                    `created_at` INTEGER NOT NULL,
                    FOREIGN KEY(`strategy_run_id`) REFERENCES `strategy_runs`(`id`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS `uq_paper_trading_policies_strategy_run`
                ON `paper_trading_policies` (`strategy_run_id`)
                """.trimIndent(),
            )
            // Phase 6 baseline v1 backfill (WEIGHT_FACTOR scale).
            db.execSQL(
                """
                INSERT INTO paper_trading_policies (
                    strategy_run_id,
                    policy_version,
                    buy_allocation_rate,
                    commission_rate,
                    sell_tax_rate,
                    slippage_bps,
                    execution_price_policy,
                    additional_buy_policy,
                    sell_policy,
                    short_selling_allowed,
                    created_at
                )
                SELECT
                    id,
                    'v1',
                    100000,
                    150,
                    2000,
                    0,
                    'NEXT_TRADING_DAY_OPEN',
                    'DISALLOW',
                    'FULL_POSITION',
                    0,
                    CAST(strftime('%s','now') AS INTEGER) * 1000
                FROM strategy_runs
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `strategy_run_instruments` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `strategy_run_id` INTEGER NOT NULL,
                    `instrument_id` INTEGER NOT NULL,
                    `created_at` INTEGER NOT NULL,
                    FOREIGN KEY(`strategy_run_id`) REFERENCES `strategy_runs`(`id`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT,
                    FOREIGN KEY(`instrument_id`) REFERENCES `instruments`(`id`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS `uq_strategy_run_instruments_run_instrument`
                ON `strategy_run_instruments` (`strategy_run_id`, `instrument_id`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS `idx_strategy_run_instruments_run`
                ON `strategy_run_instruments` (`strategy_run_id`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS `idx_strategy_run_instruments_instrument`
                ON `strategy_run_instruments` (`instrument_id`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `forward_test_cycles` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `strategy_run_id` INTEGER NOT NULL,
                    `market_date` INTEGER NOT NULL,
                    `status` TEXT NOT NULL,
                    `current_stage` TEXT NOT NULL,
                    `attempt_count` INTEGER NOT NULL,
                    `error_code` TEXT,
                    `error_message` TEXT,
                    `retryable` INTEGER NOT NULL,
                    `started_at` INTEGER,
                    `completed_at` INTEGER,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    FOREIGN KEY(`strategy_run_id`) REFERENCES `strategy_runs`(`id`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS `uq_forward_test_cycles_run_date`
                ON `forward_test_cycles` (`strategy_run_id`, `market_date`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS `idx_forward_test_cycles_run_status`
                ON `forward_test_cycles` (`strategy_run_id`, `status`)
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `themes` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `description` TEXT,
                    `is_active` INTEGER NOT NULL,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS `uq_themes_name` ON `themes` (`name`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `theme_instruments` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `theme_id` INTEGER NOT NULL,
                    `instrument_id` INTEGER NOT NULL,
                    `note` TEXT,
                    `created_at` INTEGER NOT NULL,
                    FOREIGN KEY(`theme_id`) REFERENCES `themes`(`id`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT,
                    FOREIGN KEY(`instrument_id`) REFERENCES `instruments`(`id`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS `uq_theme_instruments_theme_instrument`
                ON `theme_instruments` (`theme_id`, `instrument_id`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS `idx_theme_instruments_theme`
                ON `theme_instruments` (`theme_id`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS `idx_theme_instruments_instrument`
                ON `theme_instruments` (`instrument_id`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `strategy_signal_rules` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `strategy_version_id` INTEGER NOT NULL,
                    `rule_code` TEXT NOT NULL,
                    `metric_code` TEXT NOT NULL,
                    `operator` TEXT NOT NULL,
                    `threshold_value` TEXT NOT NULL,
                    `action` TEXT NOT NULL,
                    `priority` INTEGER NOT NULL,
                    `enabled` INTEGER NOT NULL,
                    `rule_version` TEXT NOT NULL,
                    `description` TEXT,
                    `created_at` INTEGER NOT NULL,
                    FOREIGN KEY(`strategy_version_id`) REFERENCES `strategy_versions`(`id`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS `uq_strategy_signal_rules_version_code`
                ON `strategy_signal_rules` (`strategy_version_id`, `rule_code`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS `idx_strategy_signal_rules_version`
                ON `strategy_signal_rules` (`strategy_version_id`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `trade_audit_logs` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `strategy_run_id` INTEGER NOT NULL,
                    `instrument_id` INTEGER,
                    `evaluation_id` INTEGER,
                    `order_id` INTEGER,
                    `execution_id` INTEGER,
                    `market_date` INTEGER,
                    `event_type` TEXT NOT NULL,
                    `decision_source` TEXT,
                    `rule_id` INTEGER,
                    `reason_code` TEXT,
                    `reason_text` TEXT,
                    `metric_code` TEXT,
                    `observed_value` TEXT,
                    `threshold_value` TEXT,
                    `event_key` TEXT NOT NULL,
                    `created_at` INTEGER NOT NULL,
                    FOREIGN KEY(`strategy_run_id`) REFERENCES `strategy_runs`(`id`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS `uq_trade_audit_logs_event_key`
                ON `trade_audit_logs` (`event_key`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS `idx_trade_audit_logs_run_date`
                ON `trade_audit_logs` (`strategy_run_id`, `market_date`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS `idx_trade_audit_logs_run_created`
                ON `trade_audit_logs` (`strategy_run_id`, `created_at`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `api_error_logs` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `provider` TEXT NOT NULL,
                    `operation` TEXT NOT NULL,
                    `error_type` TEXT NOT NULL,
                    `http_status` INTEGER,
                    `business_code` TEXT,
                    `safe_message` TEXT NOT NULL,
                    `retryable` INTEGER NOT NULL,
                    `strategy_run_id` INTEGER,
                    `forward_cycle_id` INTEGER,
                    `occurred_at` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS `idx_api_error_logs_occurred`
                ON `api_error_logs` (`occurred_at`)
                """.trimIndent(),
            )
        }
    }
}
