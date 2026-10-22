package com.suyog.reconbridge.application;

import com.suyog.reconbridge.application.exception.ImportNotFoundException;
import com.suyog.reconbridge.application.exception.ImportProcessingConflictException;
import com.suyog.reconbridge.application.exception.StaleImportVersionException;
import com.suyog.reconbridge.application.model.ClaimedSettlementImport;
import com.suyog.reconbridge.domain.model.ImportStatus;
import com.suyog.reconbridge.domain.model.SourceType;
import com.suyog.reconbridge.persistence.entity.ImportBatchEntity;
import com.suyog.reconbridge.persistence.repository.ImportBatchRepository;
import com.suyog.reconbridge.persistence.repository.UploadedFileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class SettlementImportClaimService {
    private final ImportBatchRepository imports;
    private final UploadedFileRepository uploadedFiles;

    public SettlementImportClaimService(
            ImportBatchRepository imports, UploadedFileRepository uploadedFiles
    ) {
        this.imports = imports;
        this.uploadedFiles = uploadedFiles;
    }

    @Transactional
    public ClaimedSettlementImport claim(UUID tenantId, UUID importId, long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        ImportBatchEntity imported = imports.findByTenantIdAndId(tenantId, importId)
                .orElseThrow(() -> new ImportNotFoundException(importId));
        if (imported.getVersion() != expectedVersion) {
            throw new StaleImportVersionException(importId, expectedVersion, imported.getVersion());
        }
        if (imported.getSourceType() != SourceType.PROCESSOR_SETTLEMENT) {
            throw conflict("UNSUPPORTED_SOURCE", "Only processor settlement imports can be processed.");
        }
        if (imported.getStatus() != ImportStatus.RECEIVED) {
            throw conflict("IMPORT_NOT_RECEIVED", "The import must be RECEIVED before processing.");
        }
        UploadedFileRepository.OwnedFile file = uploadedFiles.find(tenantId, importId)
                .orElseThrow(() -> conflict("UPLOAD_REQUIRED",
                        "Upload the file through the multipart API before processing."));
        if (!file.uri().equals(imported.getFileUri())
                || !file.sha256().equals(imported.getFileHash())) {
            throw conflict("UPLOAD_METADATA_MISMATCH", "Registered file ownership metadata is inconsistent.");
        }

        imported.startProcessing();
        ImportBatchEntity saved = imports.saveAndFlush(imported);
        return new ClaimedSettlementImport(
                tenantId, importId, imported.getSourceSystem(),
                file.uri(), file.sha256(), saved.getVersion());
    }

    private ImportProcessingConflictException conflict(String code, String message) {
        return new ImportProcessingConflictException(code, message);
    }
}
