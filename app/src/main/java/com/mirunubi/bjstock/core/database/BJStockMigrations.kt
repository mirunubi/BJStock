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
}
