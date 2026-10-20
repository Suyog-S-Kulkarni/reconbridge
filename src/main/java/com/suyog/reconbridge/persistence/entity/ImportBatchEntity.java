package com.suyog.reconbridge.persistence.entity;

import com.suyog.reconbridge.domain.model.ImportStatus;
import com.suyog.reconbridge.domain.model.SourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "import_batch")
public class ImportBatchEntity {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(
            name = "id",
            nullable = false,
            length = 36,
            updatable = false
    )
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(
            name = "tenant_id",
            nullable = false,
            length = 36,
            updatable = false
    )
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(
            name = "source_type",
            nullable = false,
            length = 40,
            updatable = false
    )
    private SourceType sourceType;

    @Column(
            name = "source_system",
            nullable = false,
            length = 100,
            updatable = false
    )
    private String sourceSystem;

    @Column(
            name = "file_name",
            nullable = false,
            length = 255,
            updatable = false
    )
    private String fileName;

    @Column(
            name = "file_uri",
            nullable = false,
            length = 1024,
            updatable = false
    )
    private String fileUri;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(
            name = "file_hash",
            nullable = false,
            length = 64,
            updatable = false
    )
    private String fileHash;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(
            name = "status",
            nullable = false,
            length = 30
    )
    private ImportStatus status;

    @Column(
            name = "business_date",
            nullable = false,
            updatable = false
    )
    private LocalDate businessDate;

    @Column(name = "row_count", nullable = false)
    private long rowCount;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected ImportBatchEntity() {
        // Required by JPA.
    }

    public ImportBatchEntity(
            UUID tenantId,
            SourceType sourceType,
            String sourceSystem,
            String fileName,
            String fileUri,
            String fileHash,
            LocalDate businessDate
    ) {
        this.id = UUID.randomUUID();

        this.tenantId = Objects.requireNonNull(
                tenantId,
                "tenantId must not be null"
        );

        this.sourceType = Objects.requireNonNull(
                sourceType,
                "sourceType must not be null"
        );

        this.sourceSystem = requireText(
                sourceSystem,
                "sourceSystem",
                100
        );

        this.fileName = requireText(
                fileName,
                "fileName",
                255
        );

        this.fileUri = requireText(
                fileUri,
                "fileUri",
                1024
        );

        if (fileHash == null
                || !fileHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "fileHash must contain exactly "
                            + "64 lowercase hexadecimal characters"
            );
        }

        this.fileHash = fileHash;

        this.businessDate = Objects.requireNonNull(
                businessDate,
                "businessDate must not be null"
        );

        this.status = ImportStatus.RECEIVED;
        this.rowCount = 0;
    }

    public void startProcessing() {
        requireStatus(ImportStatus.RECEIVED);
        status = ImportStatus.PROCESSING;
    }

    public void markReady(long validatedRowCount) {
        requireStatus(ImportStatus.PROCESSING);

        if (validatedRowCount < 0) {
            throw new IllegalArgumentException(
                    "Validated row count must not be negative"
            );
        }

        rowCount = validatedRowCount;
        status = ImportStatus.READY;
    }

    public void reject() {
        requireStatus(ImportStatus.PROCESSING);
        status = ImportStatus.REJECTED;
    }

    public void fail() {
        requireStatus(ImportStatus.PROCESSING);
        status = ImportStatus.FAILED;
    }

    public void retryProcessing() {
        requireStatus(ImportStatus.FAILED);
        status = ImportStatus.PROCESSING;
    }

    private void requireStatus(ImportStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(
                    "Import " + id
                            + " must be " + expected
                            + " for this operation, but is " + status
            );
        }
    }

    private static String requireText(
            String value,
            String fieldName,
            int maximumLength
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " must not be blank"
            );
        }

        String normalized = value.trim();

        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(
                    fieldName + " must not exceed "
                            + maximumLength + " characters"
            );
        }

        return normalized;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public String getSourceSystem() {
        return sourceSystem;
    }

    public String getFileName() {
        return fileName;
    }

    public String getFileUri() {
        return fileUri;
    }

    public String getFileHash() {
        return fileHash;
    }

    public ImportStatus getStatus() {
        return status;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public long getRowCount() {
        return rowCount;
    }

    public Long getVersion() {
        return version;
    }
}