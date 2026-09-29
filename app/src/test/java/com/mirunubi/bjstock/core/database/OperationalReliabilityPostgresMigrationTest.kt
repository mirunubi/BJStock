package com.mirunubi.bjstock.core.database

import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies PostgreSQL migration 0009 keeps parity with Room v8.
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

    private fun resolveMigration(): File {
        val relative = "db/migrations/0009_operational_reliability_foundation.sql"
        val found = listOf(File(relative), File("../$relative"), File("../../$relative")).firstOrNull { it.isFile }
        assertNotNull("migration file not found from ${System.getProperty("user.dir")}", found)
        return found!!
    }
}
