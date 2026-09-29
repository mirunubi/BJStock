-- BJStock Phase 11 / Gate 5 — explicit forward operation kind
-- Room parity: MIGRATION_8_9. Existing rows become FORWARD_RUN.
-- See docs/150_OPERATIONAL_RELIABILITY_STANDARD.md

SET search_path TO bjstock, public;

ALTER TABLE bjstock.forward_operations
    ADD COLUMN operation_kind TEXT NOT NULL DEFAULT 'FORWARD_RUN';

ALTER TABLE bjstock.forward_operations
    ADD CONSTRAINT ck_forward_operations_operation_kind CHECK (operation_kind IN (
        'FORWARD_RUN',
        'RETRY_FAILED_CYCLE'
    ));

COMMENT ON COLUMN bjstock.forward_operations.operation_kind IS
    'What the invocation does (FORWARD_RUN / RETRY_FAILED_CYCLE). Independent of trigger (MANUAL / WORKER).';
