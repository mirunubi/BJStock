-- Identify strategy versions whose enabled factor weights do not sum to 1.0.
-- Trigger is intentionally not used. This is a verification query.

SET search_path TO bjstock, public;

SELECT
    sv.id AS strategy_version_id,
    s.strategy_code,
    sv.version_no,
    sv.status,
    COALESCE(SUM(sfw.weight), 0) AS enabled_weight_sum,
    COUNT(sfw.id) AS enabled_factor_count
FROM bjstock.strategy_versions sv
JOIN bjstock.strategies s
    ON s.id = sv.strategy_id
LEFT JOIN bjstock.strategy_factor_weights sfw
    ON sfw.strategy_version_id = sv.id
    AND sfw.enabled = TRUE
GROUP BY
    sv.id,
    s.strategy_code,
    sv.version_no,
    sv.status
HAVING COALESCE(SUM(sfw.weight), 0) <> 1.0
ORDER BY
    s.strategy_code,
    sv.version_no;
