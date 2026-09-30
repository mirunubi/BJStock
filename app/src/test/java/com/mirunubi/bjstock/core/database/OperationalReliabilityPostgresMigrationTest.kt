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
 * Verifies PostgreSQL migrations 0009 / 0010 / 0011 / 0012 / 0013 keep parity with Room v8 / v9 / v10 / v11.
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
    }

    @Test
    fun migration0013_terminalAuditReconciliationMatchesRoomV11() {
        val sql = resolveMigration(MIGRATION_0013).readText().replace("\r\n", "\n")
        listOf(
            "BEGIN;",
            "INSERT INTO bjstock.trade_audit_logs (",
            "WHERE o.status = 'REJECTED'",
            "WHERE o.status = 'CANCELLED'",
            "'order:' || o.id || ':rejected'",
            "'order:' || o.id || ':cancelled'",
            "WHEN o.quantity = 0 AND o.side = 'BUY' THEN 'INSUFFICIENT_CASH'",
            "WHEN o.quantity = 0 AND o.side = 'SELL' THEN 'NO_POSITION_TO_SELL'",
            "CASE WHEN o.cancelled_at IS NOT NULL THEN 'RUN_END_REACHED' ELSE 'LEGACY_REASON_UNKNOWN' END",
            "ELSE 'LEGACY_REASON_UNKNOWN'",
            "OR (a.order_id = o.id AND a.event_type = 'ORDER_REJECTED')",
            "OR (a.order_id = o.id AND a.event_type = 'ORDER_CANCELLED')",
            "COMMIT;",
        ).forEach { assertTrue("0013 missing: $it", sql.contains(it)) }
        val statements = withoutComments(sql)
        listOf(
            "UPDATE ", "DELETE ", "DROP ", "TRUNCATE", "ALTER ", "CREATE ", "CONSTRAINT",
            "decision_source", "operation_id", "market_date",
        ).forEach { assertFalse("0013 must be append-only data: $it", statements.contains(it)) }
        val roomMigration = BJStockMigrations.MIGRATION_10_11
        assertTrue(roomMigration.startVersion == 10 && roomMigration.endVersion == 11)
    }

    @Test
    fun migration0014_scheduleInstanceMatchesRoomV12_nullableWithoutBackfillOrCheck() {
        val sql = resolveMigration(MIGRATION_0014).readText().replace("\r\n", "\n")
        assertTrue(
            sql.contains("ALTER TABLE bjstock.forward_operations\n    ADD COLUMN schedule_instance_id TEXT;"),
        )
        assertTrue(sql.contains("COMMENT ON COLUMN bjstock.forward_operations.schedule_instance_id IS"))
        val statements = withoutComments(sql).substringBefore("COMMENT ON COLUMN")
        listOf("NOT NULL", "DEFAULT", "CHECK", "CONSTRAINT", "UPDATE ", "DELETE ", "DROP ", "TRUNCATE", "INSERT ")
            .forEach { assertFalse("0014 must only add a nullable column: $it", statements.contains(it)) }
        val roomMigration = BJStockMigrations.MIGRATION_11_12
        assertTrue(roomMigration.startVersion == 11 && roomMigration.endVersion == 12)
        assertEquals(12, BJStockDatabase.VERSION)
        val roomColumn = com.mirunubi.bjstock.core.database.entity.ForwardOperationEntity::class.java
            .getDeclaredField("scheduleInstanceId")
        assertEquals(String::class.java, roomColumn.type)
    }

    @Test
    fun decisionSourceCheck_isUnchangedSince0008() {
        val sql0008 = resolveMigration(MIGRATION_0008).readText().replace("\r\n", "\n")
        assertTrue(
            sql0008.contains(
                "CONSTRAINT ck_trade_audit_logs_decision_source CHECK (\n" +
                    "        decision_source IS NULL OR decision_source IN ('SIGNAL_RULE', 'FACTOR_STRATEGY')\n    )",
            ),
        )
        val migrations = resolveMigration(MIGRATION_0008).parentFile!!.listFiles { file -> file.name.endsWith(".sql") }!!
        migrations.filter { it.name.take(4) > "0008" }.forEach { file ->
            val text = withoutComments(file.readText())
            assertFalse("${file.name} must not touch decision_source", text.contains("decision_source"))
            assertFalse("${file.name} must not add LEGACY_UNKNOWN", text.contains("LEGACY_UNKNOWN"))
        }
        assertEquals(
            listOf("SIGNAL_RULE", "FACTOR_STRATEGY"),
            com.mirunubi.bjstock.core.model.DecisionSource.entries.map { it.name },
        )
    }

    private fun withoutComments(sql: String): String =
        sql.lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")

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
        const val MIGRATION_0013 = "db/migrations/0013_legacy_terminal_order_audit.sql"
        const val MIGRATION_0014 = "db/migrations/0014_forward_operation_schedule_instance.sql"
        const val MIGRATION_0008 = "db/migrations/0008_theme_rule_audit_logging.sql"
    }
}
