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
}
