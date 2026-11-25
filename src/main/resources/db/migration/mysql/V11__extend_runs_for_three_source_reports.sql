ALTER TABLE reconciliation_run
    ADD COLUMN payment_import_id CHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NULL,

    ADD COLUMN payment_count BIGINT NOT NULL DEFAULT 0,

    ADD COLUMN settlement_line_count BIGINT NOT NULL DEFAULT 0,

    ADD COLUMN matched_payment_count BIGINT NOT NULL DEFAULT 0,

    ADD COLUMN unresolved_payment_count BIGINT NOT NULL DEFAULT 0,

    ADD COLUMN orphan_settlement_line_count BIGINT NOT NULL DEFAULT 0,

    ADD CONSTRAINT fk_reconciliation_run_payment_import
        FOREIGN KEY (tenant_id, payment_import_id)
            REFERENCES import_batch (tenant_id, id),

    ADD CONSTRAINT chk_reconciliation_run_payment_counts
        CHECK (
            payment_count >= 0
            AND settlement_line_count >= 0
            AND matched_payment_count >= 0
            AND unresolved_payment_count >= 0
            AND orphan_settlement_line_count >= 0

            AND matched_payment_count + unresolved_payment_count
                = payment_count

            AND orphan_settlement_line_count
                <= settlement_line_count
        ),

    ADD CONSTRAINT chk_reconciliation_run_source_shape
        CHECK (
            (
                report_version = 1
                AND payment_import_id IS NULL
                AND payment_count = 0
                AND settlement_line_count = 0
                AND matched_payment_count = 0
                AND unresolved_payment_count = 0
                AND orphan_settlement_line_count = 0
            )
            OR
            (
                report_version = 2
                AND payment_import_id IS NOT NULL
                AND payment_import_id <> settlement_import_id
                AND payment_import_id <> bank_import_id
                AND settlement_import_id <> bank_import_id
            )
        );


ALTER TABLE reconciliation_run_item
DROP CHECK chk_reconciliation_run_item_type,

    ADD CONSTRAINT chk_reconciliation_run_item_type
        CHECK (
            (
                item_type IN ('BATCH', 'PAYMENT')
                AND outcome IN (
                    'MATCHED',
                    'MISSING_COUNTERPART',
                    'AMOUNT_MISMATCH',
                    'CURRENCY_MISMATCH',
                    'AMBIGUOUS'
                )
            )
            OR
            (
                item_type = 'ORPHAN'
                AND outcome = 'ORPHAN_BANK_CREDIT'
            )
            OR
            (
                item_type = 'ORPHAN_LINE'
                AND outcome = 'ORPHAN_SETTLEMENT_LINE'
            )
        );