-- Phase 1 catalog verification.
-- Reports missing required objects as rows. Empty sections mean PASS.

SET search_path TO bjstock, public;

\echo '=== required tables ==='
WITH required(table_name) AS (
    VALUES
        ('instruments'),
        ('market_daily_bars'),
        ('factor_definitions'),
        ('factor_values'),
        ('strategies'),
        ('strategy_versions'),
        ('strategy_factor_weights'),
        ('strategy_runs'),
        ('stock_evaluations'),
        ('stock_evaluation_details'),
        ('positions'),
        ('orders'),
        ('executions'),
        ('portfolio_daily_snapshots'),
        ('ai_advice_requests'),
        ('ai_advice_results'),
        ('schema_migrations')
)
SELECT r.table_name AS missing_table
FROM required r
LEFT JOIN information_schema.tables t
    ON t.table_schema = 'bjstock'
    AND t.table_name = r.table_name
WHERE t.table_name IS NULL
ORDER BY r.table_name;

\echo '=== pk counts ==='
SELECT
    c.relname AS table_name,
    COUNT(con.oid) AS pk_count
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
LEFT JOIN pg_constraint con
    ON con.conrelid = c.oid
    AND con.contype = 'p'
WHERE n.nspname = 'bjstock'
  AND c.relkind = 'r'
GROUP BY c.relname
ORDER BY c.relname;

\echo '=== tables missing pk ==='
SELECT c.relname AS table_missing_pk
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'bjstock'
  AND c.relkind = 'r'
  AND NOT EXISTS (
      SELECT 1
      FROM pg_constraint con
      WHERE con.conrelid = c.oid
        AND con.contype = 'p'
  )
ORDER BY c.relname;

\echo '=== fk list ==='
SELECT
    con.conname AS fk_name,
    rel.relname AS table_name,
    confrel.relname AS referenced_table
FROM pg_constraint con
JOIN pg_class rel ON rel.oid = con.conrelid
JOIN pg_namespace n ON n.oid = rel.relnamespace
JOIN pg_class confrel ON confrel.oid = con.confrelid
WHERE n.nspname = 'bjstock'
  AND con.contype = 'f'
ORDER BY rel.relname, con.conname;

\echo '=== unique constraints ==='
SELECT
    con.conname AS unique_name,
    rel.relname AS table_name
FROM pg_constraint con
JOIN pg_class rel ON rel.oid = con.conrelid
JOIN pg_namespace n ON n.oid = rel.relnamespace
WHERE n.nspname = 'bjstock'
  AND con.contype = 'u'
ORDER BY rel.relname, con.conname;

\echo '=== check constraints ==='
SELECT
    con.conname AS check_name,
    rel.relname AS table_name
FROM pg_constraint con
JOIN pg_class rel ON rel.oid = con.conrelid
JOIN pg_namespace n ON n.oid = rel.relnamespace
WHERE n.nspname = 'bjstock'
  AND con.contype = 'c'
ORDER BY rel.relname, con.conname;

\echo '=== extra indexes ==='
SELECT
    i.relname AS index_name,
    t.relname AS table_name
FROM pg_index ix
JOIN pg_class t ON t.oid = ix.indrelid
JOIN pg_class i ON i.oid = ix.indexrelid
JOIN pg_namespace n ON n.oid = t.relnamespace
WHERE n.nspname = 'bjstock'
  AND t.relkind = 'r'
  AND NOT ix.indisprimary
  AND NOT ix.indisunique
ORDER BY t.relname, i.relname;

\echo '=== required extra indexes missing ==='
WITH required(index_name) AS (
    VALUES
        ('idx_factor_values_instrument_eval_date'),
        ('idx_factor_values_factor_eval_date'),
        ('idx_stock_evaluations_run_eval_date'),
        ('idx_stock_evaluations_instrument_eval_date'),
        ('idx_orders_run_created'),
        ('idx_orders_instrument_created'),
        ('idx_executions_order_id'),
        ('idx_ai_advice_requests_evaluation_id')
)
SELECT r.index_name AS missing_index
FROM required r
LEFT JOIN pg_class i ON i.relname = r.index_name
LEFT JOIN pg_namespace n ON n.oid = i.relnamespace AND n.nspname = 'bjstock'
WHERE i.oid IS NULL
ORDER BY r.index_name;

\echo '=== constraint totals ==='
SELECT
    con.contype,
    CASE con.contype
        WHEN 'p' THEN 'PK'
        WHEN 'f' THEN 'FK'
        WHEN 'u' THEN 'UNIQUE'
        WHEN 'c' THEN 'CHECK'
        ELSE con.contype::TEXT
    END AS constraint_type,
    COUNT(*) AS count
FROM pg_constraint con
JOIN pg_class rel ON rel.oid = con.conrelid
JOIN pg_namespace n ON n.oid = rel.relnamespace
WHERE n.nspname = 'bjstock'
  AND con.contype IN ('p', 'f', 'u', 'c')
GROUP BY con.contype
ORDER BY con.contype;

\echo '=== schema_migrations ==='
SELECT version, filename, applied_at
FROM bjstock.schema_migrations
ORDER BY version;

\echo '=== portability flags ==='
SELECT 'enum_types' AS check_name, COUNT(*) AS count
FROM pg_type t
JOIN pg_namespace n ON n.oid = t.typnamespace
WHERE n.nspname = 'bjstock'
  AND t.typtype = 'e'
UNION ALL
SELECT 'array_columns', COUNT(*)
FROM information_schema.columns
WHERE table_schema = 'bjstock'
  AND data_type = 'ARRAY'
UNION ALL
SELECT 'jsonb_columns', COUNT(*)
FROM information_schema.columns
WHERE table_schema = 'bjstock'
  AND udt_name = 'jsonb'
UNION ALL
SELECT 'generated_columns', COUNT(*)
FROM information_schema.columns
WHERE table_schema = 'bjstock'
  AND is_generated = 'ALWAYS'
UNION ALL
SELECT 'triggers', COUNT(*)
FROM information_schema.triggers
WHERE trigger_schema = 'bjstock';
