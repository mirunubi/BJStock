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
            assertEquals(BJStockDatabase.VERSION, db.version)
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
            assertEquals(BJStockDatabase.VERSION, db.version)
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

    @Test
    fun migrate9To10_assignsDeterministicFinancialKeys_andPreservesMoney() = runBlocking<Unit> {
        context.deleteDatabase(V9_TEST_DB)
        val executionsBefore: List<String>
        val ledgerBefore: List<String>
        context.openOrCreateDatabase(V9_TEST_DB, Context.MODE_PRIVATE, null).use { sqlite ->
            createSchemaFromExport(sqlite, version = 9)
            seedV9FinancialRows(sqlite)
            sqlite.version = 9
            executionsBefore = rows(sqlite, EXECUTION_FINANCIAL_COLUMNS, "executions")
            ledgerBefore = rows(sqlite, LEDGER_FINANCIAL_COLUMNS, "cash_ledger")
        }

        val database = openWithAllMigrations(V9_TEST_DB)
        try {
            val db = database.openHelper.writableDatabase
            assertEquals(BJStockDatabase.VERSION, db.version)
            assertEquals(executionsBefore, rows(db, EXECUTION_FINANCIAL_COLUMNS, "executions"))
            assertEquals(ledgerBefore, rows(db, LEDGER_FINANCIAL_COLUMNS, "cash_ledger"))
            assertEquals(
                listOf("paper:order:1:fill:1", "paper:order:2:fill:1"),
                database.executionDao().findByRun(3).map { it.executionKey },
            )
            assertEquals(
                listOf(
                    "run:3:initial-deposit",
                    "execution:1:buy-principal",
                    "execution:1:buy-commission",
                    "execution:2:sell-proceeds",
                    "execution:2:sell-commission",
                    "execution:2:sell-tax",
                ),
                database.cashLedgerDao().findByRun(3).map { it.eventKey },
            )
            assertEquals(20L, scalar(db, "SELECT seq FROM sqlite_sequence WHERE name = 'cash_ledger'"))
            assertEquals(
                100_000_000L - 9_843_476L - 1_476L + 10_000_000L - 1_500L - 20_000L,
                com.mirunubi.bjstock.core.paper.CashLedgerService(database.cashLedgerDao()).reconstructCash(3),
            )
            db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
            val duplicate = runCatching {
                db.execSQL(
                    """
                    INSERT INTO cash_ledger (strategy_run_id, event_type, amount, balance_after, event_date, created_at, event_key)
                    VALUES (3, 'BUY', -1, 1, 20726, 0, 'execution:1:buy-principal')
                    """.trimIndent(),
                )
            }
            assertTrue(duplicate.isFailure)
        } finally {
            database.close()
        }
    }

    @Test
    fun migrate9To10_ambiguousLegacyExecution_abortsAndLeavesV9Untouched() {
        context.deleteDatabase(V9_AMBIGUOUS_DB)
        context.openOrCreateDatabase(V9_AMBIGUOUS_DB, Context.MODE_PRIVATE, null).use { sqlite ->
            createSchemaFromExport(sqlite, version = 9)
            seedV9FinancialRows(sqlite)
            sqlite.execSQL(
                """
                INSERT INTO executions (id, order_id, execution_price, quantity, commission, tax, slippage, executed_at, created_at)
                VALUES (3, 1, 266000, 37, 1476, 0, 0, 0, 0)
                """.trimIndent(),
            )
            sqlite.version = 9
        }

        val database = openWithAllMigrations(V9_AMBIGUOUS_DB)
        val failure = runCatching { database.openHelper.writableDatabase }.exceptionOrNull()
        database.close()
        assertEquals("MIGRATION_9_10_AMBIGUOUS_EXECUTION_KEY", failure?.message)

        val path = context.getDatabasePath(V9_AMBIGUOUS_DB).path
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            assertEquals(9, sqlite.version)
            sqlite.rawQuery("SELECT COUNT(*) FROM executions", null).use {
                it.moveToFirst()
                assertEquals(3L, it.getLong(0))
            }
            sqlite.rawQuery("PRAGMA table_info(executions)", null).use { cursor ->
                val columns = buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
                assertTrue("execution_key" !in columns)
            }
        }
    }

    @Test
    fun migrate10To11_appendsMissingTerminalOrderAudit_only() = runBlocking<Unit> {
        context.deleteDatabase(V10_TEST_DB)
        val ordersBefore: List<String>
        context.openOrCreateDatabase(V10_TEST_DB, Context.MODE_PRIVATE, null).use { sqlite ->
            createSchemaFromExport(sqlite, version = 10)
            seedV10TerminalOrders(sqlite)
            sqlite.version = 10
            ordersBefore = rows(sqlite, ORDER_COLUMNS, "orders")
        }

        val database = openWithAllMigrations(V10_TEST_DB)
        try {
            val db = database.openHelper.writableDatabase
            assertEquals(11, db.version)
            assertEquals(ordersBefore, rows(db, ORDER_COLUMNS, "orders"))
            val audits = database.tradeAuditLogDao().findByRun(3).associateBy { it.eventKey }
            assertEquals(5, audits.size)
            assertEquals("INSUFFICIENT_CASH", audits.getValue("order:11:rejected").reasonCode)
            assertEquals("NO_POSITION_TO_SELL", audits.getValue("order:12:rejected").reasonCode)
            assertEquals("RUN_END_REACHED", audits.getValue("order:13:cancelled").reasonCode)
            assertEquals("LEGACY_REASON_UNKNOWN", audits.getValue("order:14:cancelled").reasonCode)
            val existing = audits.getValue("order:15:rejected")
            assertEquals("INSUFFICIENT_CASH", existing.reasonCode)
            assertEquals(99L, existing.operationId)
            assertEquals(Instant.ofEpochMilli(7), existing.createdAt)
            listOf("order:11:rejected", "order:12:rejected", "order:13:cancelled", "order:14:cancelled").forEach {
                val restored = audits.getValue(it)
                assertNull(it, restored.operationId)
                assertNull(it, restored.decisionSource)
                assertNull(it, restored.marketDate)
            }
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM trade_audit_logs WHERE decision_source = ''"))

            BJStockMigrations.reconcileLegacyTerminalOrderAudits(db)
            assertEquals(5L, scalar(db, "SELECT COUNT(*) FROM trade_audit_logs"))
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
        val executionsBefore: List<String>
        val ledgerBefore: List<String>
        val auditsBefore: List<String>
        val reconciledTerminalAudits: Long
        SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
            assertEquals(7, sqlite.version)
            userTables(sqlite).forEach { table ->
                sqlite.rawQuery("SELECT COUNT(*) FROM `$table`", null).use {
                    it.moveToFirst()
                    before[table] = it.getLong(0)
                }
            }
            executionsBefore = rows(sqlite, EXECUTION_FINANCIAL_COLUMNS, "executions")
            ledgerBefore = rows(sqlite, LEDGER_FINANCIAL_COLUMNS, "cash_ledger")
            auditsBefore = rows(sqlite, AUDIT_V7_COLUMNS, "trade_audit_logs")
            sqlite.rawQuery("SELECT COUNT(*) FROM trade_audit_logs WHERE decision_source = ''", null).use {
                it.moveToFirst()
                assertEquals("legacy empty-string decision_source rows", 0L, it.getLong(0))
            }
            sqlite.rawQuery(MISSING_TERMINAL_AUDIT_COUNT, null).use {
                it.moveToFirst()
                reconciledTerminalAudits = it.getLong(0)
            }
        }
        assertTrue(before.containsKey("trade_audit_logs"))
        before["trade_audit_logs"] = before.getValue("trade_audit_logs") + reconciledTerminalAudits

        val database = openWithAllMigrations(REAL_COPY_DB)
        try {
            val db = database.openHelper.writableDatabase
            assertEquals(BJStockDatabase.VERSION, db.version)
            before.forEach { (table, count) ->
                assertEquals("row count for $table", count, scalar(db, "SELECT COUNT(*) FROM `$table`"))
            }
            assertEquals(executionsBefore, rows(db, EXECUTION_FINANCIAL_COLUMNS, "executions"))
            assertEquals(ledgerBefore, rows(db, LEDGER_FINANCIAL_COLUMNS, "cash_ledger"))
            assertEquals(
                0L,
                scalar(
                    db,
                    "SELECT COUNT(*) FROM executions WHERE execution_key <> 'paper:order:' || order_id || ':fill:1'",
                ),
            )
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM cash_ledger WHERE event_key NOT LIKE 'run:%' AND event_key NOT LIKE 'execution:%'"))
            val cash = com.mirunubi.bjstock.core.paper.CashLedgerService(database.cashLedgerDao())
            database.strategyRunDao().findAll().forEach { run ->
                if (database.cashLedgerDao().countByRun(run.id) > 0) {
                    assertEquals(
                        database.cashLedgerDao().findLatest(run.id)!!.balanceAfter,
                        cash.reconstructCash(run.id),
                    )
                }
            }
            assertEquals(auditsBefore, rows(db, AUDIT_V7_COLUMNS, "trade_audit_logs").take(auditsBefore.size))
            assertEquals(0L, scalar(db, MISSING_TERMINAL_AUDIT_COUNT))
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM trade_audit_logs WHERE decision_source = ''"))
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
                BJStockMigrations.MIGRATION_9_10,
                BJStockMigrations.MIGRATION_10_11,
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

    private fun seedV9FinancialRows(sqlite: SQLiteDatabase) {
        seedPhase10Rows(sqlite)
        listOf(
            "DELETE FROM cash_ledger",
            """
            INSERT INTO orders (id, client_order_id, strategy_run_id, instrument_id, side, order_type, quantity, status, created_at)
            VALUES (2, 'paper-run-3-eval-2-SELL', 3, 3, 'SELL', 'MARKET', 37, 'VIRTUAL_FILLED', 0)
            """,
            """
            INSERT INTO executions (id, order_id, execution_price, quantity, commission, tax, slippage, executed_at, created_at)
            VALUES (2, 2, 270270, 37, 1500, 20000, 0, 0, 0)
            """,
            """
            INSERT INTO cash_ledger (id, strategy_run_id, event_type, amount, balance_after, reference_type, reference_id, event_date, created_at)
            VALUES (1, 3, 'INITIAL_DEPOSIT', 100000000, 100000000, 'STRATEGY_RUN', 3, 20714, 0),
                   (2, 3, 'BUY', -9843476, 90156524, 'EXECUTION', 1, 20725, 0),
                   (3, 3, 'COMMISSION', -1476, 90155048, 'EXECUTION', 1, 20725, 0),
                   (4, 3, 'SELL', 10000000, 100155048, 'EXECUTION', 2, 20726, 0),
                   (5, 3, 'COMMISSION', -1500, 100153548, 'EXECUTION', 2, 20726, 0),
                   (6, 3, 'TAX', -20000, 100133548, 'EXECUTION', 2, 20726, 0)
            """,
            "UPDATE sqlite_sequence SET seq = 20 WHERE name = 'cash_ledger'",
        ).forEach { sqlite.execSQL(it.trimIndent()) }
    }

    private fun seedV10TerminalOrders(sqlite: SQLiteDatabase) {
        listOf(
            """
            INSERT INTO instruments (id, market, symbol, name, currency, is_active, created_at, updated_at, board, instrument_type)
            VALUES (3, 'KRX', '005930', 'Samsung Electronics', 'KRW', 1, 0, 0, 'KOSPI', 'COMMON_STOCK')
            """,
            """
            INSERT INTO strategies (id, strategy_code, strategy_name, is_active, created_at, updated_at)
            VALUES (1, 'LEGACY', 'Legacy', 1, 0, 0)
            """,
            """
            INSERT INTO strategy_versions (id, strategy_id, version_no, buy_threshold, sell_threshold, status, created_at)
            VALUES (1, 1, 1, 700000, 400000, 'ACTIVE', 0)
            """,
            """
            INSERT INTO strategy_runs (id, run_name, strategy_version_id, run_type, start_date, initial_cash, status, created_at, updated_at)
            VALUES (3, 'Run 3', 1, 'PAPER', 20714, 100000000, 'COMPLETED', 0, 0)
            """,
            """
            INSERT INTO orders (id, client_order_id, strategy_run_id, instrument_id, side, order_type, quantity, status, created_at, cancelled_at)
            VALUES (11, 'legacy-11', 3, 3, 'BUY', 'MARKET', 0, 'REJECTED', 0, NULL),
                   (12, 'legacy-12', 3, 3, 'SELL', 'MARKET', 0, 'REJECTED', 0, NULL),
                   (13, 'legacy-13', 3, 3, 'BUY', 'MARKET', 0, 'CANCELLED', 0, 5000),
                   (14, 'legacy-14', 3, 3, 'SELL', 'MARKET', 4, 'CANCELLED', 0, NULL),
                   (15, 'gate6-15', 3, 3, 'BUY', 'MARKET', 0, 'REJECTED', 0, NULL),
                   (16, 'filled-16', 3, 3, 'BUY', 'MARKET', 1, 'VIRTUAL_FILLED', 0, NULL)
            """,
            """
            INSERT INTO trade_audit_logs (id, strategy_run_id, order_id, event_type, reason_code, event_key, created_at, operation_id)
            VALUES (1, 3, 15, 'ORDER_REJECTED', 'INSUFFICIENT_CASH', 'order:15:rejected', 7, 99)
            """,
        ).forEach { sqlite.execSQL(it.trimIndent()) }
    }

    private fun rows(sqlite: SQLiteDatabase, columns: String, table: String): List<String> =
        sqlite.rawQuery("SELECT $columns FROM `$table` ORDER BY id", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add((0 until cursor.columnCount).joinToString("|") { cursor.getString(it) ?: "NULL" })
                }
            }
        }

    private fun rows(db: androidx.sqlite.db.SupportSQLiteDatabase, columns: String, table: String): List<String> =
        db.query("SELECT $columns FROM `$table` ORDER BY id").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add((0 until cursor.columnCount).joinToString("|") { cursor.getString(it) ?: "NULL" })
                }
            }
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
        private const val V9_TEST_DB = "financial-keys-v9-migration-test"
        private const val V9_AMBIGUOUS_DB = "financial-keys-v9-ambiguous-migration-test"
        private const val V10_TEST_DB = "terminal-audit-v10-migration-test"
        private const val ORDER_COLUMNS =
            "id, client_order_id, strategy_run_id, instrument_id, evaluation_id, side, order_type, " +
                "requested_price, quantity, status, created_at, executed_at, cancelled_at"
        private const val AUDIT_V7_COLUMNS =
            "id, strategy_run_id, instrument_id, evaluation_id, order_id, execution_id, market_date, event_type, " +
                "decision_source, rule_id, reason_code, reason_text, metric_code, observed_value, threshold_value, " +
                "event_key, created_at"
        private const val MISSING_TERMINAL_AUDIT_COUNT = """
            SELECT COUNT(*) FROM orders o
            WHERE (o.status = 'REJECTED' AND NOT EXISTS (
                      SELECT 1 FROM trade_audit_logs a WHERE a.order_id = o.id AND a.event_type = 'ORDER_REJECTED'))
               OR (o.status = 'CANCELLED' AND NOT EXISTS (
                      SELECT 1 FROM trade_audit_logs a WHERE a.order_id = o.id AND a.event_type = 'ORDER_CANCELLED'))
        """
        private const val EXECUTION_FINANCIAL_COLUMNS =
            "id, order_id, execution_price, quantity, commission, tax, slippage, executed_at, created_at"
        private const val LEDGER_FINANCIAL_COLUMNS =
            "id, strategy_run_id, event_type, amount, balance_after, reference_type, reference_id, event_date, created_at"
        private const val REAL_COPY_DB = "operational-reliability-phase10-copy-test"
        private const val PHASE10_DB_ENV = "BJSTOCK_PHASE10_DB"
    }
}
