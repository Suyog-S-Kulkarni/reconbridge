CREATE TABLE bank_credit (
                             tenant_id CHAR(36)
                                           CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                             import_id CHAR(36)
                                           CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                             credit_id CHAR(36)
                                           CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                             provider VARCHAR(100)
                                           CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,

                             bank_account_reference VARCHAR(255)
                                           CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,

                             settlement_reference VARCHAR(255)
                                           CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,

                             amount_minor BIGINT NOT NULL,

                             currency CHAR(3)
                                           CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                             created_at TIMESTAMP(6)
                                 NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                             CONSTRAINT pk_bank_credit
                                 PRIMARY KEY (tenant_id, import_id, credit_id),

                             CONSTRAINT fk_bank_credit_import
                                 FOREIGN KEY (tenant_id, import_id)
                                     REFERENCES import_batch (tenant_id, id),

                             CONSTRAINT chk_bank_credit_amount
                                 CHECK (amount_minor > 0),

                             CONSTRAINT chk_bank_credit_currency
                                 CHECK (
                                     CHAR_LENGTH(currency) = 3
                                         AND currency = UPPER(currency)
                                     ),

                             INDEX ix_bank_credit_match (
        tenant_id,
        import_id,
        provider,
        bank_account_reference,
        settlement_reference
    )
) ENGINE = InnoDB;