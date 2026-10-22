CREATE TABLE settlement_batch (
                                  tenant_id CHAR(36)
                                                CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                  import_id CHAR(36)
                                                CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                  batch_id CHAR(36)
                                                CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                  provider VARCHAR(100)
                                                CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                  settlement_reference VARCHAR(255)
                                                CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,

                                  bank_account_reference VARCHAR(255)
                                                CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,

                                  declared_net_minor BIGINT NOT NULL,

                                  currency CHAR(3)
                                                CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                  created_at TIMESTAMP(6)
                                      NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                                  CONSTRAINT pk_settlement_batch
                                      PRIMARY KEY (tenant_id, import_id, batch_id),

                                  CONSTRAINT fk_settlement_batch_import
                                      FOREIGN KEY (tenant_id, import_id)
                                          REFERENCES import_batch (tenant_id, id),

                                  CONSTRAINT uk_settlement_batch_key
                                      UNIQUE (
                                              tenant_id,
                                              import_id,
                                              provider,
                                              bank_account_reference,
                                              settlement_reference
                                          ),

                                  CONSTRAINT chk_settlement_batch_net
                                      CHECK (declared_net_minor > 0),

                                  CONSTRAINT chk_settlement_batch_currency
                                      CHECK (
                                          REGEXP_LIKE(currency, '^[A-Z]{3}$', 'c')
                                          )
) ENGINE = InnoDB;


CREATE TABLE settlement_line (
                                 tenant_id CHAR(36)
                                               CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                 import_id CHAR(36)
                                               CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                 line_id CHAR(36)
                                               CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                 batch_id CHAR(36)
                                               CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                 payment_reference VARCHAR(255)
                                               CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,

                                 gross_minor BIGINT NOT NULL,

                                 fee_minor BIGINT NOT NULL,

                                 net_minor BIGINT NOT NULL,

                                 created_at TIMESTAMP(6)
                                     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                                 CONSTRAINT pk_settlement_line
                                     PRIMARY KEY (tenant_id, import_id, line_id),

                                 CONSTRAINT fk_settlement_line_batch
                                     FOREIGN KEY (tenant_id, import_id, batch_id)
                                         REFERENCES settlement_batch (
                                                                      tenant_id,
                                                                      import_id,
                                                                      batch_id
                                             ),

                                 CONSTRAINT chk_settlement_line_gross
                                     CHECK (gross_minor > 0),

                                 CONSTRAINT chk_settlement_line_fee
                                     CHECK (fee_minor >= 0),

                                 CONSTRAINT chk_settlement_line_net
                                     CHECK (net_minor >= 0),

                                 CONSTRAINT chk_settlement_line_arithmetic
                                     CHECK (net_minor = gross_minor - fee_minor),

                                 INDEX idx_settlement_line_batch (
        tenant_id,
        import_id,
        batch_id
    ),

                                 INDEX idx_settlement_line_payment_reference (
        tenant_id,
        import_id,
        payment_reference
    )
) ENGINE = InnoDB;