package com.mirunubi.bjstock.core.database

import com.mirunubi.bjstock.core.model.ApiErrorType
import com.mirunubi.bjstock.core.model.ForwardOperationKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies PostgreSQL migrations 0009 / 0010 / 0011 / 0012 keep parity with Room v8 / v9 / v10.
 * Runtime application of the file is done via scripts/db-migrate.ps1.
 */
class OperationalReliabilityPostgresMigrationTest {
    @Test
    fun migration0009_declaresOperationalTablesAndCorrelation() {
        val sql = resolveMigration().readText()
        listOf(
            "CREATE TABLE bjstock.forward_operations",
            "uq_forward_operations_operation_key UNIQUE (operation_key)",
            "ck_forward_operations_trigger CHECK (trigger IN ('MANUAL', 'WORKER'))",
            "ck_forward_operations_status",
            "'RUNNING'", "'SUCCEEDED'", "'NO_OP'", "'PARTIAL'", "'BLOCKED'", "'FAILED'",
            "ck_forward_operations_worker_identity",
            "ck_forward_operations_finished_consistency",
            "CREATE TABLE bjstock.operational_events",
            "uq_operational_events_event_key UNIQUE (event_key)",
            "fk_operational_events_operation",
            "REFERENCES bjstock.forward_operations (id)",
            "ON DELETE RESTRICT",
            "ck_operational_events_operation_required",
            "'WORKER_SCHEDULE_CHANGED'",
            "ALTER TABLE bjstock.trade_audit_logs ADD COLUMN operation_id BIGINT",
            "ALTER TABLE bjstock.api_error_logs ADD COLUMN operation_id BIGINT",
            "idx_trade_audit_logs_operation",
            "idx_api_error_logs_operation",
        ).forEach { assertTrue("0009 missing: $it", sql.contains(it)) }
    }

    @Test
    fun migration0010_apiErrorTypeCheckMatchesKotlinEnumExactly() {
        val sql = resolveMigration(MIGRATION_0010).readText()
        assertTrue(sql.contains("DROP CONSTRAINT ck_api_error_logs_error_type;"))
        assertTrue(sql.contains("ADD CONSTRAINT ck_api_error_logs_error_type CHECK (error_type IN ("))
        val allowed = Regex("'([A-Z_]+)'").findAll(sql.substringAfter("ADD CONSTRAINT"))
            .map { it.groupValues[1] }
            .toList()
        assertEquals(ApiErrorType.entries.map { it.name }, allowed)
        listOf("DROP TABLE", "DROP COLUMN", "ALTER COLUMN", "UPDATE ", "DELETE ", "CREATE ").forEach {
            assertFalse("0010 must only replace the CHECK: $it", sql.contains(it))
        }
    }

    @Test
    fun migration0011_operationKindMatchesRoomAndKotlinEnumExactly() {
        val sql = resolveMigration(MIGRATION_0011).readText().replace("\r\n", "\n")
        assertTrue(
            sql.contains(
                "ALTER TABLE bjstock.forward_operations\n    ADD COLUMN operation_kind TEXT NOT NULL DEFAULT 'FORWARD_RUN';",
            ),
        )
        assertTrue(sql.contains("ADD CONSTRAINT ck_forward_operations_operation_kind CHECK (operation_kind IN ("))
        val allowed = Regex("'([A-Z_]+)'").findAll(sql.substringAfter("ADD CONSTRAINT").substringBefore("));"))
            .map { it.groupValues[1] }
            .toList()
        assertEquals(ForwardOperationKind.entries.map { it.name }, allowed)
        assertTrue(
            BJStockMigrations.MIGRATION_8_9.startVersion == 8 && BJStockMigrations.MIGRATION_8_9.endVersion == 9,
        )
        listOf("DROP ", "ALTER COLUMN", "UPDATE ", "DELETE ", "CREATE ", "TRUNCATE").forEach {
            assertFalse("0011 must be additive: $it", sql.contains(it))
        }
    }

    @Test
    fun migration0012_financialKeysMatchRoomV10() {
        val sql = resolveMigration(MIGRATION_0012).readText().replace("\r\n", "\n")
        listOf(
            "BEGIN;",
            "ALTER TABLE bjstock.executions\n    ADD COLUMN execution_key TEXT;",
            "SET execution_key = 'paper:order:' || order_id || ':fill:1';",
            "ALTER COLUMN execution_key SET NOT NULL;",
            "ADD CONSTRAINT uq_executions_execution_key UNIQUE (execution_key);",
            "MIGRATION_0012_AMBIGUOUS_EXECUTION_KEY",
            "ALTER TABLE bjstock.cash_ledger\n    ADD COLUMN event_key TEXT;",
            "'run:' || c.strategy_run_id || ':initial-deposit'",
            "':buy-principal'", "':buy-commission'", "':sell-proceeds'", "':sell-commission'", "':sell-tax'",
            "MIGRATION_0012_UNRECOGNIZED_LEDGER_ROW",
            "ALTER COLUMN event_key SET NOT NULL;",
            "ADD CONSTRAINT uq_cash_ledger_event_key UNIQUE (event_key);",
            "COMMIT;",
        ).forEach { assertTrue("0012 missing: $it", sql.contains(it)) }
        listOf("DROP ", "DELETE ", "TRUNCATE", "SET amount", "SET balance_after", "UNIQUE (order_id)").forEach {
            assertFalse("0012 must not rewrite money or drop data: $it", sql.contains(it))
        }
        val roomMigration = BJStockMigrations.MIGRATION_9_10
        assertTrue(roomMigration.startVersion == 9 && roomMigration.endVersion == 10)
        assertEquals(10, BJStockDatabase.VERSION)
    }

    private fun resolveMigration(
        relative: String = "db/migrations/0009_operational_reliability_foundation.sql",
    ): File {
        val found = listOf(File(relative), File("../$relative"), File("../../$relative")).firstOrNull { it.isFile }
        assertNotNull("migration file not found from ${System.getProperty("user.dir")}", found)
        return found!!
    }

    private companion object {
        const val MIGRATION_0010 = "db/migrations/0010_api_error_type_taxonomy.sql"
        const val MIGRATION_0011 = "db/migrations/0011_forward_operation_kind.sql"
        const val MIGRATION_0012 = "db/migrations/0012_financial_event_keys.sql"
    }
}
