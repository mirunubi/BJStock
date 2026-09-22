package com.mirunubi.bjstock.core.paper

import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies PostgreSQL migration 0006 declares the Phase 6.1 CHECK / UNIQUE contracts.
 * Runtime application of the file is done via scripts/db-migrate.ps1.
 */
class PaperTradingPolicyPostgresConstraintTest {
    @Test
    fun migration0006_declaresRequiredConstraints() {
        val sql = resolveMigration().readText()
        assertTrue(sql.contains("CREATE TABLE bjstock.paper_trading_policies"))
        assertTrue(sql.contains("uq_paper_trading_policies_strategy_run"))
        assertTrue(sql.contains("UNIQUE (strategy_run_id)"))
        assertTrue(sql.contains("ck_paper_trading_policies_policy_version_not_empty"))
        assertTrue(sql.contains("ck_paper_trading_policies_execution_price_policy"))
        assertTrue(sql.contains("NEXT_TRADING_DAY_OPEN"))
        assertTrue(sql.contains("ck_paper_trading_policies_additional_buy_policy"))
        assertTrue(sql.contains("'DISALLOW'"))
        assertTrue(sql.contains("ck_paper_trading_policies_sell_policy"))
        assertTrue(sql.contains("'FULL_POSITION'"))
        assertTrue(sql.contains("ck_paper_trading_policies_short_selling_disallowed"))
        assertTrue(sql.contains("short_selling_allowed = FALSE"))
        assertTrue(sql.contains("SIMULATION ASSUMPTION"))
    }

    private fun resolveMigration(): File {
        val relative = "db/migrations/0006_paper_trading_policy_snapshot.sql"
        val candidates = listOf(
            File(relative),
            File("../$relative"),
            File("../../$relative"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertNotNull("migration file not found from ${System.getProperty("user.dir")}", found)
        return found!!
    }
}
