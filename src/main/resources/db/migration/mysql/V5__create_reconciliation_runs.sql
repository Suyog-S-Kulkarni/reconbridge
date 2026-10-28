CREATE TABLE reconciliation_run (
                                    tenant_id CHAR(36)
                                                  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                    id CHAR(36)
                                                  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                    settlement_import_id CHAR(36)
                                                  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                    bank_import_id CHAR(36)
                                                  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                    rule_version VARCHAR(100) NOT NULL,
                                    report_version INT NOT NULL,
                                    created_by VARCHAR(255) NOT NULL,

                                    created_at_utc DATETIME(6) NOT NULL,

                                    settlement_batch_count BIGINT NOT NULL,
                                    bank_credit_count BIGINT NOT NULL,
                                    matched_batch_count BIGINT NOT NULL,
                                    unresolved_batch_count BIGINT NOT NULL,
                                    orphan_bank_credit_count BIGINT NOT NULL,
                                    fully_reconciled BOOLEAN NOT NULL,

                                    CONSTRAINT pk_reconciliation_run
                                        PRIMARY KEY (tenant_id, id),

                                    CONSTRAINT fk_reconciliation_run_settlement_import
                                        FOREIGN KEY (tenant_id, settlement_import_id)
                                            REFERENCES import_batch (tenant_id, id),

                                    CONSTRAINT fk_reconciliation_run_bank_import
                                        FOREIGN KEY (tenant_id, bank_import_id)
                                            REFERENCES import_batch (tenant_id, id),

                                    CONSTRAINT chk_reconciliation_run_counts
                                        CHECK (
                                            settlement_batch_count >= 0
                                                AND bank_credit_count >= 0
                                                AND matched_batch_count >= 0
                                                AND unresolved_batch_count >= 0
                                                AND orphan_bank_credit_count >= 0
                                                AND matched_batch_count + unresolved_batch_count
                                                = settlement_batch_count
                                                AND orphan_bank_credit_count <= bank_credit_count
                                            ),

                                    CONSTRAINT chk_reconciliation_run_report_version
                                        CHECK (report_version > 0),

                                    INDEX ix_reconciliation_run_created (
        tenant_id,
        created_at_utc,
        id
    )
) ENGINE = InnoDB;


CREATE TABLE reconciliation_run_item (
                                         tenant_id CHAR(36)
                                                       CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                         run_id CHAR(36)
                                                       CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                         item_type VARCHAR(20)
                                                       CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                         evidence_id CHAR(36)
                                                       CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                         outcome VARCHAR(40)
                                                       CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                         payload JSON NOT NULL,

                                         CONSTRAINT pk_reconciliation_run_item
                                             PRIMARY KEY (
                                                          tenant_id,
                                                          run_id,
                                                          item_type,
                                                          evidence_id
                                                 ),

                                         CONSTRAINT fk_reconciliation_run_item_run
                                             FOREIGN KEY (tenant_id, run_id)
                                                 REFERENCES reconciliation_run (tenant_id, id)
                                                 ON DELETE CASCADE,

                                         CONSTRAINT chk_reconciliation_run_item_type
                                             CHECK (
                                                 (
                                                     item_type = 'BATCH'
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
                                                 ),

                                         INDEX ix_reconciliation_run_item_outcome (
        tenant_id,
        run_id,
        outcome
    )
) ENGINE = InnoDB;