CREATE TABLE reconciliation_run_request (
                                            tenant_id CHAR(36)
                                                          CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                            idempotency_key VARCHAR(128)
                                                          CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                            request_hash CHAR(64)
                                                          CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                                            run_id CHAR(36)
                                                          CHARACTER SET ascii COLLATE ascii_bin NULL,

                                            CONSTRAINT pk_reconciliation_run_request
                                                PRIMARY KEY (tenant_id, idempotency_key),

                                            CONSTRAINT fk_reconciliation_run_request_run
                                                FOREIGN KEY (tenant_id, run_id)
                                                    REFERENCES reconciliation_run (tenant_id, id)
                                                    ON DELETE CASCADE
) ENGINE = InnoDB;