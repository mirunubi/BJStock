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
 * Verifies PostgreSQL migrations 0009 / 0010 / 0011 keep parity with Room v8 / v9.
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
    }
}
