package com.mirunubi.bjstock.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mirunubi.bjstock.core.database.entity.ForwardOperationEntity
import com.mirunubi.bjstock.core.database.entity.TradeAuditLogEntity
import com.mirunubi.bjstock.core.model.ForwardOperationKind
import com.mirunubi.bjstock.core.model.ForwardOperationStatus
import com.mirunubi.bjstock.core.model.ForwardOperationTrigger
import com.mirunubi.bjstock.core.model.TradeAuditEventType
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phase 10 (Room v7) -> Phase 11 (Room v8).
 * The v7 database is built from the committed schema export so the migrated result
 * is validated by Room against the real v8 entities.
 */
@RunWith(RobolectricTestRunner::class)
class OperationalReliabilityMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun migrate7To8_phase10Schema_preservesRowsAndAddsEmptyTables() = runBlocking {
        context.deleteDatabase(V7_TEST_DB)
        context.openOrCreateDatabase(V7_TEST_DB, Context.MODE_PRIVATE, null).use { sqlite ->
            createSchemaFromExport(sqlite, version = 7)
            seedPhase10Rows(sqlite)
            sqlite.version = 7
        }

        val database = openWithAllMigrations(V7_TEST_DB)
        try {
            val db = database.openHelper.writableDatabase
            assertEquals(9, db.version)
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM instruments"))
            assertEquals(2L, scalar(db, "SELECT COUNT(*) FROM strategy_runs"))
            assertEquals(2L, scalar(db, "SELECT COUNT(*) FROM forward_test_cycles"))
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM orders"))
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM executions"))
            assertEquals(2L, scalar(db, "SELECT COUNT(*) FROM cash_ledger"))
            assertEquals(90_156_524L, scalar(db, "SELECT balance_after FROM cash_ledger WHERE id = 2"))
            assertEquals(2L, scalar(db, "SELECT COUNT(*) FROM trade_audit_logs"))
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM api_error_logs"))

            assertEquals(0, database.forwardOperationDao().countAll())
            assertEquals(0, database.operationalEventDao().countAll())

            assertEquals(2L, scalar(db, "SELECT COUNT(*) FROM trade_audit_logs WHERE operation_id IS NULL"))
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM api_error_logs WHERE operation_id IS NULL"))

            val audits = database.tradeAuditLogDao().findByRun(3)
            assertEquals(listOf("evaluation:1:decision", "execution:1:filled"), audits.map { it.eventKey })
            assertTrue(audits.all { it.operationId == null })
            val apiError = database.apiErrorLogDao().findSince(Instant.EPOCH).single()
            assertEquals("KIS_DAILY_PRICE", apiError.operation)
            assertNull(apiError.operationId)

            val newId = database.tradeAuditLogDao().insert(
                TradeAuditLogEntity(
                    strategyRunId = 3,
                    eventType = TradeAuditEventType.EVALUATION_DECIDED,
                    eventKey = "evaluation:2:decision",
                    createdAt = Instant.EPOCH,
                    operationId = 77,
                ),
            )
            assertEquals(77L, scalar(db, "SELECT operation_id FROM trade_audit_logs WHERE id = $newId"))
        } finally {
            database.close()
        }
    }

    @Test
    fun migrate8To9_existingOperationsBecomeForwardRun_andRowsArePreserved() = runBlocking {
        context.deleteDatabase(V8_TEST_DB)
        context.openOrCreateDatabase(V8_TEST_DB, Context.MODE_PRIVATE, null).use { sqlite ->
            createSchemaFromExport(sqlite, version = 8)
            sqlite.execSQL(
                """
                INSERT INTO forward_operations (
                    id, operation_key, `trigger`, work_id, work_attempt, through_date, status, started_at,
                    runs_considered, runs_processed, runs_skipped, cycles_completed, cycles_failed
                ) VALUES
                    (1, 'manual:req-1', 'MANUAL', NULL, NULL, 20725, 'SUCCEEDED', 0, 1, 1, 0, 1, 0),
                    (2, 'worker:w-1:0', 'WORKER', 'w-1', 0, 20725, 'NO_OP', 0, 0, 0, 0, 0, 0)
                """.trimIndent(),
            )
            sqlite.version = 8
        }

        val database = openWithAllMigrations(V8_TEST_DB)
        try {
            val db = database.openHelper.writableDatabase
            assertEquals(9, db.version)
            assertEquals(2L, scalar(db, "SELECT COUNT(*) FROM forward_operations WHERE operation_kind = 'FORWARD_RUN'"))
            val legacy = database.forwardOperationDao().findById(2)!!
            assertEquals("worker:w-1:0", legacy.operationKey)
            assertEquals(ForwardOperationKind.FORWARD_RUN, legacy.operationKind)
            assertEquals(ForwardOperationStatus.NO_OP, legacy.status)

            val retryId = database.forwardOperationDao().insert(
                ForwardOperationEntity(
                    operationKey = "manual-retry:req-2",
                    trigger = ForwardOperationTrigger.MANUAL,
                    operationKind = ForwardOperationKind.RETRY_FAILED_CYCLE,
                    throughDate = java.time.LocalDate.of(2026, 9, 29),
                    status = ForwardOperationStatus.RUNNING,
                    startedAt = Instant.EPOCH,
                ),
            )
            assertEquals(
                ForwardOperationKind.RETRY_FAILED_CYCLE,
                database.forwardOperationDao().findById(retryId)!!.operationKind,
            )
            db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        } finally {
            database.close()
        }
    }

    /**
     * Opt-in: migrates a COPY of a real Phase 10 device DB.
     * Set BJSTOCK_PHASE10_DB to a checkpointed bjstock.db path. The source file is never opened for write.
     */
    @Test
    fun migrate7To8_realPhase10DatabaseCopy_preservesEveryRow() = runBlocking {
        val sourcePath = System.getenv(PHASE10_DB_ENV)
        assumeTrue("$PHASE10_DB_ENV not set", !sourcePath.isNullOrBlank())
        val source = File(sourcePath!!)
        assumeTrue("Phase 10 DB copy not found", source.isFile)

        context.deleteDatabase(REAL_COPY_DB)
        val target = context.getDatabasePath(REAL_COPY_DB)
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)

        val before = mutableMapOf<String, Long>()
        SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
            assertEquals(7, sqlite.version)
            userTables(sqlite).forEach { table ->
                sqlite.rawQuery("SELECT COUNT(*) FROM `$table`", null).use {
                    it.moveToFirst()
                    before[table] = it.getLong(0)
                }
            }
        }
        assertTrue(before.containsKey("trade_audit_logs"))

        val database = openWithAllMigrations(REAL_COPY_DB)
        try {
            val db = database.openHelper.writableDatabase
            assertEquals(9, db.version)
            before.forEach { (table, count) ->
                assertEquals("row count for $table", count, scalar(db, "SELECT COUNT(*) FROM `$table`"))
            }
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM forward_operations"))
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM operational_events"))
            assertEquals(
                before.getValue("trade_audit_logs"),
                scalar(db, "SELECT COUNT(*) FROM trade_audit_logs WHERE operation_id IS NULL"),
            )
            assertEquals(
                before.getValue("api_error_logs"),
                scalar(db, "SELECT COUNT(*) FROM api_error_logs WHERE operation_id IS NULL"),
            )
            db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
            db.query("PRAGMA integrity_check").use {
                it.moveToFirst()
                assertEquals("ok", it.getString(0))
            }
        } finally {
            database.close()
            context.deleteDatabase(REAL_COPY_DB)
        }
    }

    private fun openWithAllMigrations(name: String): BJStockDatabase =
        Room.databaseBuilder(context, BJStockDatabase::class.java, name)
            .addMigrations(
                BJStockMigrations.MIGRATION_1_2,
                BJStockMigrations.MIGRATION_2_3,
                BJStockMigrations.MIGRATION_3_4,
                BJStockMigrations.MIGRATION_4_5,
                BJStockMigrations.MIGRATION_5_6,
                BJStockMigrations.MIGRATION_6_7,
                BJStockMigrations.MIGRATION_7_8,
                BJStockMigrations.MIGRATION_8_9,
            )
            .allowMainThreadQueries()
            .build()

    private fun createSchemaFromExport(sqlite: SQLiteDatabase, version: Int) {
        val root = Json.parseToJsonElement(resolveSchema(version).readText()).jsonObject
        val database = root.getValue("database").jsonObject
        database.getValue("entities").jsonArray.forEach { entityElement ->
            val entity = entityElement.jsonObject
            val table = entity.getValue("tableName").jsonPrimitive.content
            sqlite.execSQL(
                entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table),
            )
            entity["indices"]?.jsonArray?.forEach { index ->
                sqlite.execSQL(
                    index.jsonObject.getValue("createSql").jsonPrimitive.content
                        .replace("\${TABLE_NAME}", table),
                )
            }
        }
        database.getValue("setupQueries").jsonArray.forEach {
            sqlite.execSQL(it.jsonPrimitive.content)
        }
    }

    private fun seedPhase10Rows(sqlite: SQLiteDatabase) {
        listOf(
            """
            INSERT INTO instruments (id, market, symbol, name, currency, is_active, created_at, updated_at, board, instrument_type)
            VALUES (3, 'KRX', '005930', 'Samsung Electronics', 'KRW', 1, 0, 0, 'KOSPI', 'COMMON_STOCK')
            """,
            """
            INSERT INTO strategies (id, strategy_code, strategy_name, is_active, created_at, updated_at)
            VALUES (1, 'PHASE10', 'Phase 10', 1, 0, 0)
            """,
            """
            INSERT INTO strategy_versions (id, strategy_id, version_no, buy_threshold, sell_threshold, status, created_at)
            VALUES (1, 1, 1, 700000, 400000, 'ACTIVE', 0)
            """,
            """
            INSERT INTO strategy_runs (id, run_name, strategy_version_id, run_type, start_date, initial_cash, status, created_at, updated_at)
            VALUES (1, 'Run 1', 1, 'PAPER', 20715, 100000000, 'RUNNING', 0, 0),
                   (3, 'Run 3', 1, 'PAPER', 20714, 100000000, 'RUNNING', 0, 0)
            """,
            """
            INSERT INTO forward_test_cycles (id, strategy_run_id, market_date, status, current_stage, attempt_count, retryable, created_at, updated_at)
            VALUES (1, 1, 20725, 'COMPLETE', 'COMPLETE', 1, 0, 0, 0),
                   (2, 3, 20725, 'COMPLETE', 'COMPLETE', 1, 0, 0, 0)
            """,
            """
            INSERT INTO orders (id, client_order_id, strategy_run_id, instrument_id, side, order_type, quantity, status, created_at)
            VALUES (1, 'paper-run-3-eval-1-BUY', 3, 3, 'BUY', 'MARKET', 37, 'VIRTUAL_FILLED', 0)
            """,
            """
            INSERT INTO executions (id, order_id, execution_price, quantity, commission, tax, slippage, executed_at, created_at)
            VALUES (1, 1, 266000, 37, 1476, 0, 0, 0, 0)
            """,
            """
            INSERT INTO cash_ledger (id, strategy_run_id, event_type, amount, balance_after, reference_type, reference_id, event_date, created_at)
            VALUES (1, 3, 'INITIAL_DEPOSIT', 100000000, 100000000, 'STRATEGY_RUN', 3, 20714, 0),
                   (2, 3, 'BUY', -9843476, 90156524, 'EXECUTION', 1, 20725, 0)
            """,
            """
            INSERT INTO trade_audit_logs (id, strategy_run_id, event_type, event_key, created_at)
            VALUES (1, 3, 'EVALUATION_DECIDED', 'evaluation:1:decision', 1),
                   (2, 3, 'EXECUTION_FILLED', 'execution:1:filled', 2)
            """,
            """
            INSERT INTO api_error_logs (id, provider, operation, error_type, http_status, safe_message, retryable, occurred_at)
            VALUES (1, 'KIS', 'KIS_DAILY_PRICE', 'KIS_BUSINESS_ERROR', 500, 'rate limited', 1, 1)
            """,
        ).forEach { sqlite.execSQL(it.trimIndent()) }
    }

    private fun userTables(sqlite: SQLiteDatabase): List<String> {
        val tables = mutableListOf<String>()
        sqlite.rawQuery(
            """
            SELECT name FROM sqlite_master
            WHERE type = 'table'
              AND name NOT IN ('android_metadata', 'sqlite_sequence', 'room_master_table')
            """.trimIndent(),
            null,
        ).use { while (it.moveToNext()) tables += it.getString(0) }
        return tables
    }

    private fun scalar(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Long =
        db.query(sql).use {
            assertTrue(it.moveToFirst())
            it.getLong(0)
        }

    private fun resolveSchema(version: Int): File {
        val relative = "schemas/com.mirunubi.bjstock.core.database.BJStockDatabase/$version.json"
        val found = listOf(File(relative), File("app/$relative"), File("../app/$relative"))
            .firstOrNull { it.isFile }
        assertNotNull("schema $version not found from ${System.getProperty("user.dir")}", found)
        return found!!
    }

    companion object {
        private const val V7_TEST_DB = "operational-reliability-v7-migration-test"
        private const val V8_TEST_DB = "operational-reliability-v8-migration-test"
        private const val REAL_COPY_DB = "operational-reliability-phase10-copy-test"
        private const val PHASE10_DB_ENV = "BJSTOCK_PHASE10_DB"
    }
}
