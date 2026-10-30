ALTER TABLE exception_case
DROP CHECK chk_exception_case_status,
    ADD CONSTRAINT chk_exception_case_status_v8
        CHECK (
            (status = 'OPEN' AND assigned_to IS NULL)
            OR
            (
                status IN (
                    'IN_REVIEW',
                    'AWAITING_APPROVAL',
                    'RESOLVED'
                )
                AND assigned_to IS NOT NULL
                AND CHAR_LENGTH(TRIM(assigned_to)) > 0
            )
        );

CREATE TABLE resolution_proposal (
                                     tenant_id CHAR(36)
                                                   CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                     id CHAR(36)
                                                   CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                     case_id CHAR(36)
                                                   CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                     resolution_type VARCHAR(40)
                                                   CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                     rationale VARCHAR(4000)
                                                   CHARACTER SET utf8mb4 NOT NULL,

                                     proposed_by VARCHAR(255)
                                                   CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,

                                     proposed_at_utc DATETIME(6) NOT NULL,

                                     status VARCHAR(20)
                                                   CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                     decided_by VARCHAR(255)
                                                   CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,

                                     decision_comment VARCHAR(4000)
                                                   CHARACTER SET utf8mb4 NULL,

                                     decided_at_utc DATETIME(6) NULL,

                                     pending_slot TINYINT
                                         GENERATED ALWAYS AS (
                                             CASE WHEN status = 'PENDING' THEN 1 ELSE NULL END
                                             ) STORED,

                                     CONSTRAINT pk_resolution_proposal
                                         PRIMARY KEY (tenant_id, id),

                                     CONSTRAINT fk_resolution_proposal_case
                                         FOREIGN KEY (tenant_id, case_id)
                                             REFERENCES exception_case (tenant_id, id),

                                     CONSTRAINT uq_resolution_proposal_pending
                                         UNIQUE (tenant_id, case_id, pending_slot),

                                     CONSTRAINT chk_resolution_proposal_type
                                         CHECK (
                                             resolution_type IN (
                                                                 'DATA_CORRECTION_CONFIRMED',
                                                                 'TIMING_DIFFERENCE_CONFIRMED',
                                                                 'ACCEPTED_VARIANCE'
                                                 )
                                             ),

                                     CONSTRAINT chk_resolution_proposal_rationale
                                         CHECK (
                                             CHAR_LENGTH(TRIM(rationale)) BETWEEN 1 AND 4000
                                             ),

                                     CONSTRAINT chk_resolution_proposal_actor
                                         CHECK (
                                             CHAR_LENGTH(TRIM(proposed_by)) BETWEEN 1 AND 255
                                             ),

                                     CONSTRAINT chk_resolution_proposal_decision
                                         CHECK (
                                             (
                                                 status = 'PENDING'
                                                     AND decided_by IS NULL
                                                     AND decision_comment IS NULL
                                                     AND decided_at_utc IS NULL
                                                 )
                                                 OR
                                             (
                                                 status IN ('APPROVED', 'REJECTED')
                                                     AND decided_by IS NOT NULL
                                                     AND CHAR_LENGTH(TRIM(decided_by)) BETWEEN 1 AND 255
                                                     AND CAST(decided_by AS BINARY)
                                                     <> CAST(proposed_by AS BINARY)
                                                     AND decision_comment IS NOT NULL
                                                     AND CHAR_LENGTH(TRIM(decision_comment))
                                                     BETWEEN 1 AND 4000
                                                     AND decided_at_utc IS NOT NULL
                                                     AND decided_at_utc >= proposed_at_utc
                                                 )
                                             ),

                                     INDEX ix_resolution_proposal_history (
        tenant_id, case_id, proposed_at_utc, id
    )
) ENGINE = InnoDB;