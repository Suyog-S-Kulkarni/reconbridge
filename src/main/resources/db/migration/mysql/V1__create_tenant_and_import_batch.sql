CREATE TABLE tenant (
                        id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                        name VARCHAR(150) NOT NULL,
                        created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                        CONSTRAINT pk_tenant PRIMARY KEY (id)
) ENGINE = InnoDB;


CREATE TABLE import_batch (
                              id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                              tenant_id CHAR(36)
                                     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                              source_type VARCHAR(40)
                                     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                              source_system VARCHAR(100)
                                     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                              file_name VARCHAR(255) NOT NULL,
                              file_uri VARCHAR(1024) NOT NULL,

                              file_hash CHAR(64)
                                     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                              status VARCHAR(30)
                                     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

                              business_date DATE NOT NULL,
                              row_count BIGINT NOT NULL DEFAULT 0,
                              version BIGINT NOT NULL DEFAULT 0,

                              created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                              updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                  ON UPDATE CURRENT_TIMESTAMP(6),

                              CONSTRAINT pk_import_batch PRIMARY KEY (id),

                              CONSTRAINT fk_import_batch_tenant
                                  FOREIGN KEY (tenant_id)
                                      REFERENCES tenant (id),

                              CONSTRAINT uk_import_batch_tenant_id
                                  UNIQUE (tenant_id, id),

                              CONSTRAINT uk_import_batch_content
                                  UNIQUE (
                                          tenant_id,
                                          source_type,
                                          source_system,
                                          file_hash
                                      ),

                              CONSTRAINT chk_import_batch_source_type
                                  CHECK (
                                      source_type IN (
                                                      'INTERNAL_PAYMENT',
                                                      'PROCESSOR_SETTLEMENT',
                                                      'BANK_STATEMENT'
                                          )
                                      ),

                              CONSTRAINT chk_import_batch_status
                                  CHECK (
                                      status IN (
                                                 'RECEIVED',
                                                 'PROCESSING',
                                                 'READY',
                                                 'REJECTED',
                                                 'FAILED'
                                          )
                                      ),

                              CONSTRAINT chk_import_batch_row_count
                                  CHECK (row_count >= 0),

                              CONSTRAINT chk_import_batch_version
                                  CHECK (version >= 0),

                              CONSTRAINT chk_import_batch_file_hash
                                  CHECK (
                                      REGEXP_LIKE(
                                              file_hash,
                                              '^[0-9a-f]{64}$',
                                              'c'
                                      )
                                      ),

                              INDEX idx_import_batch_tenant_status (
        tenant_id,
        status,
        created_at
    ),

                              INDEX idx_import_batch_tenant_business_date (
        tenant_id,
        business_date
    )
) ENGINE = InnoDB;