package com.suyog.reconbridge.application;

import com.suyog.reconbridge.application.exception.DuplicateImportException;
import com.suyog.reconbridge.application.exception.TenantNotProvisionedException;
import com.suyog.reconbridge.application.model.ImportBatchSnapshot;
import com.suyog.reconbridge.domain.model.SourceType;
import com.suyog.reconbridge.persistence.entity.ImportBatchEntity;
import com.suyog.reconbridge.persistence.repository.ImportBatchRepository;
import com.suyog.reconbridge.persistence.repository.TenantRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Service
public class ImportRegistrationService {

    private static final String CONTENT_CONSTRAINT =
            "uk_import_batch_content";

    private final ImportBatchRepository importBatchRepository;
    private final TenantRepository tenantRepository;

    public ImportRegistrationService(
            ImportBatchRepository importBatchRepository,
            TenantRepository tenantRepository
    ) {
        this.importBatchRepository = importBatchRepository;
        this.tenantRepository = tenantRepository;
    }

    @Transactional
    public ImportBatchSnapshot register(
            UUID tenantId,
            SourceType sourceType,
            String sourceSystem,
            String fileName,
            String fileUri,
            String fileHash,
            LocalDate businessDate
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");

        tenantRepository.findById(tenantId)
                .orElseThrow(TenantNotProvisionedException::new);

        ImportBatchEntity batch = new ImportBatchEntity(
                tenantId,
                sourceType,
                sourceSystem,
                fileName,
                fileUri,
                fileHash,
                businessDate
        );

        try {
            ImportBatchEntity saved =
                    importBatchRepository.saveAndFlush(batch);

            return new ImportBatchSnapshot(
                    saved.getId(),
                    saved.getTenantId(),
                    saved.getSourceType(),
                    saved.getSourceSystem(),
                    saved.getStatus(),
                    saved.getRowCount(),
                    saved.getBusinessDate(),
                    saved.getVersion()
            );
        } catch (DataIntegrityViolationException exception) {
            if (isDuplicateContent(exception)) {
                throw new DuplicateImportException(exception);
            }

            throw exception;
        }
    }

    private boolean isDuplicateContent(Throwable exception) {
        Throwable current = exception;

        while (current != null) {
            if (current instanceof ConstraintViolationException violation) {
                String constraintName = violation.getConstraintName();

                if (constraintName != null) {
                    String normalized = constraintName
                            .replace("`", "")
                            .replace("\"", "");

                    int separator = normalized.lastIndexOf('.');
                    String unqualifiedName =
                            normalized.substring(separator + 1);

                    if (CONTENT_CONSTRAINT.equalsIgnoreCase(unqualifiedName)) {
                        return true;
                    }
                }
            }

            current = current.getCause();
        }

        return false;
    }
}