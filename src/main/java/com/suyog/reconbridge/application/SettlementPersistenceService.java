package com.suyog.reconbridge.application;

import com.suyog.reconbridge.application.exception.ImportNotFoundException;
import com.suyog.reconbridge.application.exception.StaleImportVersionException;
import com.suyog.reconbridge.application.model.ImportBatchSnapshot;
import com.suyog.reconbridge.domain.model.ImportStatus;
import com.suyog.reconbridge.domain.model.SettlementBatch;
import com.suyog.reconbridge.domain.model.SettlementKey;
import com.suyog.reconbridge.domain.model.SettlementLine;
import com.suyog.reconbridge.domain.model.SourceType;
import com.suyog.reconbridge.persistence.entity.ImportBatchEntity;
import com.suyog.reconbridge.persistence.repository.ImportBatchRepository;
import com.suyog.reconbridge.persistence.repository.SettlementWriteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class SettlementPersistenceService {

    private final ImportBatchRepository importRepository;
    private final SettlementWriteRepository settlementRepository;

    public SettlementPersistenceService(
            ImportBatchRepository importRepository,
            SettlementWriteRepository settlementRepository
    ) {
        this.importRepository = importRepository;
        this.settlementRepository = settlementRepository;
    }

    @Transactional
    public ImportBatchSnapshot complete(
            UUID tenantId,
            UUID importId,
            long expectedVersion,
            List<SettlementBatch> batches
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(importId, "importId must not be null");
        Objects.requireNonNull(batches, "batches must not be null");

        if (expectedVersion < 0) {
            throw new IllegalArgumentException(
                    "expectedVersion must not be negative"
            );
        }

        List<SettlementBatch> batchSnapshot = List.copyOf(batches);

        ImportBatchEntity imported = importRepository
                .findByTenantIdAndId(tenantId, importId)
                .orElseThrow(() -> new ImportNotFoundException(importId));

        if (imported.getVersion() != expectedVersion) {
            throw new StaleImportVersionException(
                    importId,
                    expectedVersion,
                    imported.getVersion()
            );
        }

        if (imported.getSourceType() != SourceType.PROCESSOR_SETTLEMENT) {
            throw new IllegalArgumentException(
                    "Settlement persistence requires PROCESSOR_SETTLEMENT."
            );
        }

        if (imported.getStatus() != ImportStatus.PROCESSING) {
            throw new IllegalStateException(
                    "Import must be PROCESSING before completion."
            );
        }

        long rowCount = validateBatches(imported, batchSnapshot);

        /*
         * Flush the versioned update first. Competing workers cannot both
         * advance the same import version.
         *
         * This is not a commit. If the settlement inserts fail afterward,
         * the status, row count and version update roll back with them.
         */
        imported.markReady(rowCount);

        ImportBatchEntity saved =
                importRepository.saveAndFlush(imported);

        settlementRepository.insert(
                tenantId,
                importId,
                batchSnapshot
        );

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
    }

    private long validateBatches(
            ImportBatchEntity imported,
            List<SettlementBatch> batches
    ) {
        if (batches.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one settlement batch is required."
            );
        }

        Set<UUID> batchIds = new HashSet<>();
        Set<SettlementKey> batchKeys = new HashSet<>();
        Set<UUID> lineIds = new HashSet<>();

        long rowCount = 0;

        for (SettlementBatch batch : batches) {
            SettlementKey key = batch.settlementKey();

            if (!imported.getTenantId().equals(key.tenantId())) {
                throw new IllegalArgumentException(
                        "Settlement batch belongs to a different tenant."
                );
            }

            if (!imported.getSourceSystem().equals(key.provider())) {
                throw new IllegalArgumentException(
                        "Settlement provider does not match import sourceSystem."
                );
            }

            if (!batchIds.add(batch.id())) {
                throw new IllegalArgumentException(
                        "Duplicate settlement batch ID."
                );
            }

            if (!batchKeys.add(key)) {
                throw new IllegalArgumentException(
                        "Duplicate settlement batch key."
                );
            }

            for (SettlementLine line : batch.lines()) {
                if (!lineIds.add(line.id())) {
                    throw new IllegalArgumentException(
                            "Duplicate settlement line ID across batches."
                    );
                }

                rowCount = Math.addExact(rowCount, 1);
            }
        }

        return rowCount;
    }
}