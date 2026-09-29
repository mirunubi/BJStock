-- BJStock Phase 11 Gate 4 - API error type taxonomy
-- Expands ck_api_error_logs_error_type only. Existing values and rows are unchanged.
-- See docs/150_OPERATIONAL_RELIABILITY_STANDARD.md and docs/148_API_ERROR_LOGGING.md

SET search_path TO bjstock, public;

ALTER TABLE bjstock.api_error_logs
    DROP CONSTRAINT ck_api_error_logs_error_type;

ALTER TABLE bjstock.api_error_logs
    ADD CONSTRAINT ck_api_error_logs_error_type CHECK (error_type IN (
        'NETWORK_TIMEOUT',
        'HTTP_ERROR',
        'AUTH_ERROR',
        'KIS_BUSINESS_ERROR',
        'MALFORMED_RESPONSE',
        'MASTER_DOWNLOAD_ERROR',
        'RATE_LIMIT',
        'LOCAL_INVARIANT',
        'UNEXPECTED'
    ));
