CREATE TABLE internal_payment (
                                  tenant_id CHAR(36)
                                                CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                  import_id CHAR(36)
                                                CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                  payment_id CHAR(36)
                                                CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                  provider VARCHAR(100)
                                                CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,

                                  payment_reference VARCHAR(255)
                                                CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,

                                  amount_minor BIGINT NOT NULL,

                                  currency CHAR(3)
                                                CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                  created_at TIMESTAMP(6)
                                      NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                                  CONSTRAINT pk_internal_payment
                                      PRIMARY KEY (tenant_id, import_id, payment_id),

                                  CONSTRAINT fk_internal_payment_import
                                      FOREIGN KEY (tenant_id, import_id)
                                          REFERENCES import_batch (tenant_id, id),

                                  CONSTRAINT uq_internal_payment_match
                                      UNIQUE (
                                              tenant_id,
                                              import_id,
                                              provider,
                                              payment_reference
                                          ),

                                  CONSTRAINT chk_internal_payment_amount
                                      CHECK (amount_minor > 0),

                                  CONSTRAINT chk_internal_payment_provider
                                      CHECK (CHAR_LENGTH(TRIM(provider)) > 0),

                                  CONSTRAINT chk_internal_payment_reference
                                      CHECK (CHAR_LENGTH(TRIM(payment_reference)) > 0),

                                  CONSTRAINT chk_internal_payment_currency
                                      CHECK (
                                          CHAR_LENGTH(currency) = 3
                                              AND ASCII(SUBSTRING(currency, 1, 1)) BETWEEN 65 AND 90
                                              AND ASCII(SUBSTRING(currency, 2, 1)) BETWEEN 65 AND 90
                                              AND ASCII(SUBSTRING(currency, 3, 1)) BETWEEN 65 AND 90
                                          )
) ENGINE = InnoDB;