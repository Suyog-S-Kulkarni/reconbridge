package com.suyog.reconbridge.application;

import com.suyog.reconbridge.application.model.ImportBatchSnapshot;
import com.suyog.reconbridge.domain.model.SourceType;
import com.suyog.reconbridge.persistence.repository.UploadedFileRepository;
import com.suyog.reconbridge.storage.StoredFile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

@Service
public class UploadedImportRegistrationService {
    private final ImportRegistrationService registration;
    private final UploadedFileRepository uploadedFiles;

    public UploadedImportRegistrationService(
            ImportRegistrationService registration,
            UploadedFileRepository uploadedFiles
    ) {
        this.registration = registration;
        this.uploadedFiles = uploadedFiles;
    }

    @Transactional
    public ImportBatchSnapshot register(
            UUID tenantId,
            SourceType sourceType,
            String sourceSystem,
            String fileName,
            LocalDate businessDate,
            StoredFile file
    ) {
        ImportBatchSnapshot created = registration.register(
                tenantId, sourceType, sourceSystem, fileName,
                file.uri(), file.sha256(), businessDate);
        uploadedFiles.insert(tenantId, created.id(), file);
        return created;
    }
}
