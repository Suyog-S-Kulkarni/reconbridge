CREATE TABLE exception_case (
                                tenant_id CHAR(36)
                                              CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                id CHAR(36)
                                              CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                run_id CHAR(36)
                                              CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                item_type VARCHAR(20)
                                              CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                evidence_id CHAR(36)
                                              CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                reason VARCHAR(40)
                                              CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                status VARCHAR(30)
                                              CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                created_by VARCHAR(255)
                                              CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,

                                assigned_to VARCHAR(255)
                                              CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,

                                created_at_utc DATETIME(6) NOT NULL,
                                updated_at_utc DATETIME(6) NOT NULL,

                                version BIGINT NOT NULL,

                                CONSTRAINT pk_exception_case
                                    PRIMARY KEY (tenant_id, id),

                                CONSTRAINT uq_exception_case_evidence
                                    UNIQUE (tenant_id, run_id, item_type, evidence_id),

                                CONSTRAINT fk_exception_case_evidence
                                    FOREIGN KEY (tenant_id, run_id, item_type, evidence_id)
                                        REFERENCES reconciliation_run_item (
                                                                            tenant_id, run_id, item_type, evidence_id
                                            ),

                                CONSTRAINT chk_exception_case_status
                                    CHECK (
                                        (status = 'OPEN' AND assigned_to IS NULL)
                                            OR
                                        (
                                            status = 'IN_REVIEW'
                                                AND assigned_to IS NOT NULL
                                                AND CHAR_LENGTH(TRIM(assigned_to)) > 0
                                            )
                                        ),

                                CONSTRAINT chk_exception_case_reason
                                    CHECK (
                                        (
                                            item_type = 'BATCH'
                                                AND reason IN (
                                                               'MISSING_COUNTERPART',
                                                               'AMBIGUOUS',
                                                               'AMOUNT_MISMATCH',
                                                               'CURRENCY_MISMATCH'
                                                )
                                            )
                                            OR
                                        (
                                            item_type = 'ORPHAN'
                                                AND reason = 'ORPHAN_BANK_CREDIT'
                                            )
                                        ),

                                CONSTRAINT chk_exception_case_version
                                    CHECK (version >= 0),

                                CONSTRAINT chk_exception_case_times
                                    CHECK (updated_at_utc >= created_at_utc),

                                INDEX ix_exception_case_status (
        tenant_id, status, created_at_utc, id
    ),

                                INDEX ix_exception_case_assignee (
        tenant_id, assigned_to, status, created_at_utc, id
    )
) ENGINE = InnoDB;


CREATE TABLE investigation_note (
                                    tenant_id CHAR(36)
                                                  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                    id CHAR(36)
                                                  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                    case_id CHAR(36)
                                                  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                    author_subject VARCHAR(255)
                                                  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,

                                    note_text VARCHAR(4000)
                                                  CHARACTER SET utf8mb4 NOT NULL,

                                    created_at_utc DATETIME(6) NOT NULL,

                                    CONSTRAINT pk_investigation_note
                                        PRIMARY KEY (tenant_id, id),

                                    CONSTRAINT fk_investigation_note_case
                                        FOREIGN KEY (tenant_id, case_id)
                                            REFERENCES exception_case (tenant_id, id),

                                    CONSTRAINT chk_investigation_note_text
                                        CHECK (CHAR_LENGTH(TRIM(note_text)) BETWEEN 1 AND 4000),

                                    INDEX ix_investigation_note_case (
        tenant_id, case_id, created_at_utc, id
    )
) ENGINE = InnoDB;