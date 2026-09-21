-- BJStock Phase 3-D — instrument master metadata
-- Does not modify 0001_initial_business_schema.sql or 0002_phase1_schema_hardening.sql.
--
-- Adds master fields used by KOSPI/KOSDAQ MST sync:
--   standard_code    KIS 표준코드 (nullable)
--   board            KOSPI / KOSDAQ / OTHER
--   instrument_type  COMMON_STOCK / PREFERRED_STOCK / ETP / SPAC / OTHER
--
-- Existing rows receive board=OTHER and instrument_type=OTHER via column defaults.
-- market remains the exchange hierarchy (KRX). board is the listing board.

SET search_path TO bjstock, public;

ALTER TABLE bjstock.instruments
    ADD COLUMN standard_code TEXT;

ALTER TABLE bjstock.instruments
    ADD COLUMN board TEXT NOT NULL DEFAULT 'OTHER';

ALTER TABLE bjstock.instruments
    ADD COLUMN instrument_type TEXT NOT NULL DEFAULT 'OTHER';

ALTER TABLE bjstock.instruments
    ADD CONSTRAINT ck_instruments_board
        CHECK (board IN ('KOSPI', 'KOSDAQ', 'OTHER'));

ALTER TABLE bjstock.instruments
    ADD CONSTRAINT ck_instruments_instrument_type
        CHECK (instrument_type IN (
            'COMMON_STOCK',
            'PREFERRED_STOCK',
            'ETP',
            'SPAC',
            'OTHER'
        ));

COMMENT ON COLUMN bjstock.instruments.market IS
    'Exchange hierarchy, e.g. KRX. Not KOSPI/KOSDAQ; those belong in board.';
COMMENT ON COLUMN bjstock.instruments.standard_code IS
    'KIS master 표준코드 (ISIN-like). Nullable when unknown.';
COMMENT ON COLUMN bjstock.instruments.board IS
    'Listing board: KOSPI, KOSDAQ, OTHER. Default OTHER.';
COMMENT ON COLUMN bjstock.instruments.instrument_type IS
    'COMMON_STOCK, PREFERRED_STOCK, ETP, SPAC, OTHER. Default OTHER.';
