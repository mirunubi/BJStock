-- BJStock Phase 11 / Gate 7B — Auto schedule instance identity
-- Room parity: MIGRATION_11_12. Nullable; existing rows stay NULL (no backfill, no CHECK change).
-- See docs/150_OPERATIONAL_RELIABILITY_STANDARD.md §20.9

SET search_path TO bjstock, public;

ALTER TABLE bjstock.forward_operations
    ADD COLUMN schedule_instance_id TEXT;

COMMENT ON COLUMN bjstock.forward_operations.schedule_instance_id IS
    'Intended Auto slot (auto:<YYYY-MM-DD>:0730:KST). NULL for MANUAL and pre-Gate-7B WORKER rows. Required for new WORKER rows by application validation; never parsed from operation_key.';
