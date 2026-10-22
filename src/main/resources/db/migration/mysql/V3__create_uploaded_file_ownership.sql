CREATE TABLE import_uploaded_file (
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    import_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    storage_uri VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_import_uploaded_file PRIMARY KEY (tenant_id, import_id),
    CONSTRAINT uk_import_uploaded_file_uri UNIQUE (storage_uri),
    CONSTRAINT fk_import_uploaded_file_import
        FOREIGN KEY (tenant_id, import_id)
        REFERENCES import_batch (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_import_uploaded_file_size CHECK (size_bytes > 0),
    CONSTRAINT chk_import_uploaded_file_hash CHECK (
        REGEXP_LIKE(sha256, '^[0-9a-f]{64}$', 'c')
    )
) ENGINE = InnoDB;

-- Deliberately do not infer ownership from existing caller-supplied file_uri.
-- Ownership records are created only by the server upload registration path.
