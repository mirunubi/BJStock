-- BJStock Phase 5 — pin factor calculation version on strategy weights
-- Does not modify 0001, 0002, or 0003.
--
-- Strategy versions must record which Factor Engine version they use.
-- Existing rows, if any, receive v1.

SET search_path TO bjstock, public;

ALTER TABLE bjstock.strategy_factor_weights
    ADD COLUMN factor_calculation_version TEXT NOT NULL DEFAULT 'v1';

ALTER TABLE bjstock.strategy_factor_weights
    ADD CONSTRAINT ck_strategy_factor_weights_factor_calculation_version_not_empty
        CHECK (TRIM(factor_calculation_version) <> '');

COMMENT ON COLUMN bjstock.strategy_factor_weights.factor_calculation_version IS
    'Pinned Factor Engine calculation_version, e.g. v1. ACTIVE versions must not change this.';
COMMENT ON TABLE bjstock.strategy_factor_weights IS
    'Per-version factor weights with pinned calculation version. SUM(enabled weight)=1.0 is application-enforced.';
