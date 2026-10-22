package com.suyog.reconbridge.application;

import com.suyog.reconbridge.application.exception.ImportNotFoundException;
import com.suyog.reconbridge.application.exception.SettlementEvidenceException;
import com.suyog.reconbridge.application.exception.SettlementEvidenceException.Reason;
import com.suyog.reconbridge.application.model.SettlementEvidence;
import com.suyog.reconbridge.domain.model.ImportStatus;
import com.suyog.reconbridge.domain.model.SourceType;
import com.suyog.reconbridge.persistence.entity.ImportBatchEntity;
import com.suyog.reconbridge.persistence.repository.ImportBatchRepository;
import com.suyog.reconbridge.persistence.repository.SettlementReadRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(
        readOnly = true,
        isolation = Isolation.REPEATABLE_READ
)
public class SettlementEvidenceService {

    private final ImportBatchRepository imports;
    private final SettlementReadRepository settlements;

    public SettlementEvidenceService(
            ImportBatchRepository imports,
            SettlementReadRepository settlements
    ) {
        this.imports = imports;
        this.settlements = settlements;
    }

    public SettlementEvidence.Page<SettlementEvidence.Batch> batches(
            UUID tenantId,
            UUID importId,
            int page,
            int size
    ) {
        validatePagination(page, size);
        requireReadyImport(tenantId, importId);

        long count = settlements.countBatches(
                tenantId,
                importId
        );

        long offset = (long) page * size;

        List<SettlementEvidence.Batch> items = settlements.batches(
                tenantId,
                importId,
                size,
                offset
        );

        return result(items, page, size, count);
    }

    public SettlementEvidence.Page<SettlementEvidence.Line> lines(
            UUID tenantId,
            UUID importId,
            UUID batchId,
            int page,
            int size
    ) {
        validatePagination(page, size);
        requireReadyImport(tenantId, importId);

        if (!settlements.batchExists(tenantId, importId, batchId)) {
            throw new SettlementEvidenceException(
                    Reason.BATCH_NOT_FOUND,
                    "Settlement batch not found."
            );
        }

        long count = settlements.countLines(
                tenantId,
                importId,
                batchId
        );

        long offset = (long) page * size;

        List<SettlementEvidence.Line> items = settlements.lines(
                tenantId,
                importId,
                batchId,
                size,
                offset
        );

        return result(items, page, size, count);
    }

    private void requireReadyImport(
            UUID tenantId,
            UUID importId
    ) {
        ImportBatchEntity imported = imports
                .findByTenantIdAndId(tenantId, importId)
                .orElseThrow(
                        () -> new ImportNotFoundException(importId)
                );

        if (imported.getSourceType() != SourceType.PROCESSOR_SETTLEMENT) {
            throw new SettlementEvidenceException(
                    Reason.UNSUPPORTED_SOURCE,
                    "Settlement evidence requires a processor settlement import."
            );
        }

        if (imported.getStatus() != ImportStatus.READY) {
            throw new SettlementEvidenceException(
                    Reason.IMPORT_NOT_READY,
                    "Settlement evidence is available only after the import reaches READY."
            );
        }
    }

    private void validatePagination(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new SettlementEvidenceException(
                    Reason.INVALID_PAGINATION,
                    "page must be zero or greater; size must be between 1 and 100."
            );
        }
    }

    private <T> SettlementEvidence.Page<T> result(
            List<T> items,
            int page,
            int size,
            long count
    ) {
        long totalPages = count / size;

        if (count % size != 0) {
            totalPages++;
        }

        return new SettlementEvidence.Page<>(
                items,
                page,
                size,
                count,
                totalPages
        );
    }
}