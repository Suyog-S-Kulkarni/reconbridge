package com.suyog.reconbridge.application;

import com.suyog.reconbridge.application.exception.ReconciliationRunNotFoundException;
import com.suyog.reconbridge.application.model.BankReconciliationPreview;
import com.suyog.reconbridge.application.model.StoredReconciliationRun;
import com.suyog.reconbridge.persistence.repository.ReconciliationRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class ReconciliationRunService {

    private static final String RULE_VERSION = "EXACT_SETTLEMENT_BANK_V1";
    private static final int REPORT_VERSION = 1;

    private final BankReconciliationPreviewService preview;
    private final ReconciliationRunRepository repository;

    public ReconciliationRunService(
            BankReconciliationPreviewService preview,
            ReconciliationRunRepository repository
    ) {
        this.preview = preview;
        this.repository = repository;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public StoredReconciliationRun create(
            UUID tenantId,
            String createdBy,
            UUID settlementImportId,
            UUID bankImportId
    ) {
        if (createdBy == null
                || createdBy.isBlank()
                || createdBy.length() > 255) {
            throw new IllegalArgumentException(
                    "A valid creator subject is required."
            );
        }

        BankReconciliationPreview report = preview.preview(
                tenantId,
                settlementImportId,
                bankImportId
        );

        StoredReconciliationRun run = new StoredReconciliationRun(
                UUID.randomUUID(),
                RULE_VERSION,
                REPORT_VERSION,
                createdBy,
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                report
        );

        repository.save(tenantId, run);

        return run;
    }

    @Transactional(
            readOnly = true,
            isolation = Isolation.REPEATABLE_READ
    )
    public StoredReconciliationRun get(
            UUID tenantId,
            UUID runId
    ) {
        return repository.find(tenantId, runId)
                .orElseThrow(
                        () -> new ReconciliationRunNotFoundException(runId)
                );
    }
}