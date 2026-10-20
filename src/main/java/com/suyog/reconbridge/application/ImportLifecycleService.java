package com.suyog.reconbridge.application;

import com.suyog.reconbridge.application.exception.ImportNotFoundException;
import com.suyog.reconbridge.application.exception.StaleImportVersionException;
import com.suyog.reconbridge.application.model.ImportBatchSnapshot;
import com.suyog.reconbridge.persistence.entity.ImportBatchEntity;
import com.suyog.reconbridge.persistence.repository.ImportBatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

@Service
@Transactional(readOnly = true)
public class ImportLifecycleService {

    private final ImportBatchRepository repository;

    public ImportLifecycleService(
            ImportBatchRepository repository
    ) {
        this.repository = repository;
    }

    public ImportBatchSnapshot get(
            UUID tenantId,
            UUID importId
    ) {
        return snapshot(load(tenantId, importId));
    }

    @Transactional
    public ImportBatchSnapshot startProcessing(
            UUID tenantId,
            UUID importId,
            long expectedVersion
    ) {
        return transition(
                tenantId,
                importId,
                expectedVersion,
                ImportBatchEntity::startProcessing
        );
    }

    @Transactional
    public ImportBatchSnapshot markReady(
            UUID tenantId,
            UUID importId,
            long expectedVersion,
            long validatedRowCount
    ) {
        return transition(
                tenantId,
                importId,
                expectedVersion,
                batch -> batch.markReady(validatedRowCount)
        );
    }

    @Transactional
    public ImportBatchSnapshot reject(
            UUID tenantId,
            UUID importId,
            long expectedVersion
    ) {
        return transition(
                tenantId,
                importId,
                expectedVersion,
                ImportBatchEntity::reject
        );
    }

    @Transactional
    public ImportBatchSnapshot fail(
            UUID tenantId,
            UUID importId,
            long expectedVersion
    ) {
        return transition(
                tenantId,
                importId,
                expectedVersion,
                ImportBatchEntity::fail
        );
    }

    @Transactional
    public ImportBatchSnapshot retryProcessing(
            UUID tenantId,
            UUID importId,
            long expectedVersion
    ) {
        return transition(
                tenantId,
                importId,
                expectedVersion,
                ImportBatchEntity::retryProcessing
        );
    }

    private ImportBatchSnapshot transition(
            UUID tenantId,
            UUID importId,
            long expectedVersion,
            Consumer<ImportBatchEntity> change
    ) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException(
                    "expectedVersion must not be negative"
            );
        }

        ImportBatchEntity batch = load(tenantId, importId);

        long actualVersion = batch.getVersion();

        if (actualVersion != expectedVersion) {
            throw new StaleImportVersionException(
                    importId,
                    expectedVersion,
                    actualVersion
            );
        }

        change.accept(batch);

        ImportBatchEntity saved = repository.saveAndFlush(batch);

        return snapshot(saved);
    }

    private ImportBatchEntity load(
            UUID tenantId,
            UUID importId
    ) {
        Objects.requireNonNull(
                tenantId,
                "tenantId must not be null"
        );

        Objects.requireNonNull(
                importId,
                "importId must not be null"
        );

        return repository.findByTenantIdAndId(tenantId, importId)
                .orElseThrow(
                        () -> new ImportNotFoundException(importId)
                );
    }

    private ImportBatchSnapshot snapshot(ImportBatchEntity batch) {
        return new ImportBatchSnapshot(
                batch.getId(),
                batch.getTenantId(),
                batch.getSourceType(),
                batch.getSourceSystem(),
                batch.getStatus(),
                batch.getRowCount(),
                batch.getBusinessDate(),
                batch.getVersion()
        );
    }
}