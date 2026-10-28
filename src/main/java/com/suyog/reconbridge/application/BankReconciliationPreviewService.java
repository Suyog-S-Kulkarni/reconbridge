package com.suyog.reconbridge.application;

import com.suyog.reconbridge.application.exception.BankPreviewException;
import com.suyog.reconbridge.application.exception.ImportNotFoundException;
import com.suyog.reconbridge.application.model.BankReconciliationPreview;
import com.suyog.reconbridge.domain.matching.BankMatchingStrategy;
import com.suyog.reconbridge.domain.matching.ExactSettlementBankMatchingStrategy;
import com.suyog.reconbridge.domain.model.BankCredit;
import com.suyog.reconbridge.domain.model.BankMatchResult;
import com.suyog.reconbridge.domain.model.ImportStatus;
import com.suyog.reconbridge.domain.model.MatchOutcome;
import com.suyog.reconbridge.domain.model.Money;
import com.suyog.reconbridge.domain.model.SettlementBatch;
import com.suyog.reconbridge.domain.model.SettlementKey;
import com.suyog.reconbridge.domain.model.SourceType;
import com.suyog.reconbridge.persistence.entity.ImportBatchEntity;
import com.suyog.reconbridge.persistence.repository.BankPreviewReadRepository;
import com.suyog.reconbridge.persistence.repository.ImportBatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional(
        readOnly = true,
        isolation = Isolation.REPEATABLE_READ
)
public class BankReconciliationPreviewService {

    private final ImportBatchRepository imports;
    private final BankPreviewReadRepository evidence;

    private final BankMatchingStrategy matching =
            new ExactSettlementBankMatchingStrategy();

    public BankReconciliationPreviewService(
            ImportBatchRepository imports,
            BankPreviewReadRepository evidence
    ) {
        this.imports = imports;
        this.evidence = evidence;
    }

    public BankReconciliationPreview preview(
            UUID tenantId,
            UUID settlementImportId,
            UUID bankImportId
    ) {
        /*
         * Check ownership of both imports before reading evidence.
         */
        ImportBatchEntity settlementImport = load(
                tenantId,
                settlementImportId
        );

        ImportBatchEntity bankImport = load(
                tenantId,
                bankImportId
        );

        requireReady(
                settlementImport,
                SourceType.PROCESSOR_SETTLEMENT
        );

        requireReady(
                bankImport,
                SourceType.BANK_STATEMENT
        );

        List<SettlementBatch> batches = evidence.loadBatches(
                tenantId,
                settlementImportId
        );

        List<BankCredit> credits = evidence.loadCredits(
                tenantId,
                bankImportId
        );

        if (batches.isEmpty() || credits.isEmpty()) {
            throw new BankPreviewException(
                    "EVIDENCE_MISSING",
                    "A selected READY import has no persisted reconciliation evidence."
            );
        }

        List<BankMatchResult> matches = matching.match(
                batches,
                credits
        );

        Set<SettlementKey> settlementKeys = new HashSet<>();

        for (SettlementBatch batch : batches) {
            settlementKeys.add(batch.settlementKey());
        }

        List<BankCredit> orphanCredits = credits.stream()
                .filter(credit ->
                        !settlementKeys.contains(
                                credit.settlementKey()
                        )
                )
                .toList();

        long matchedCount = matches.stream()
                .filter(result ->
                        result.outcome() == MatchOutcome.MATCHED
                )
                .count();

        long unresolvedCount = matches.size() - matchedCount;

        List<BankReconciliationPreview.BatchResult> results =
                matches.stream()
                        .map(this::toBatchResult)
                        .toList();

        List<BankReconciliationPreview.OrphanCredit> orphans =
                orphanCredits.stream()
                        .map(this::toOrphanCredit)
                        .toList();

        return new BankReconciliationPreview(
                settlementImportId,
                bankImportId,
                batches.size(),
                credits.size(),
                matchedCount,
                unresolvedCount,
                orphanCredits.size(),
                unresolvedCount == 0 && orphanCredits.isEmpty(),
                results,
                orphans
        );
    }

    private ImportBatchEntity load(
            UUID tenantId,
            UUID importId
    ) {
        return imports
                .findByTenantIdAndId(tenantId, importId)
                .orElseThrow(
                        () -> new ImportNotFoundException(importId)
                );
    }

    private void requireReady(
            ImportBatchEntity imported,
            SourceType expectedSource
    ) {
        if (imported.getSourceType() != expectedSource) {
            throw new BankPreviewException(
                    "INVALID_IMPORT_SOURCE",
                    "Selected import must have source type "
                            + expectedSource
            );
        }

        if (imported.getStatus() != ImportStatus.READY) {
            throw new BankPreviewException(
                    "IMPORT_NOT_READY",
                    "Both selected imports must be READY."
            );
        }
    }

    private BankReconciliationPreview.BatchResult toBatchResult(
            BankMatchResult match
    ) {
        Money difference = match.difference().orElse(null);

        return new BankReconciliationPreview.BatchResult(
                match.settlementBatchId(),
                match.settlementKey().provider(),
                match.settlementKey().bankAccountReference(),
                match.settlementKey().settlementReference(),
                match.outcome(),
                match.candidateBankCreditIds(),
                difference == null
                        ? null
                        : difference.amountMinor(),
                difference == null
                        ? null
                        : difference.currency().getCurrencyCode()
        );
    }

    private BankReconciliationPreview.OrphanCredit toOrphanCredit(
            BankCredit credit
    ) {
        return new BankReconciliationPreview.OrphanCredit(
                credit.id(),
                credit.settlementKey().provider(),
                credit.settlementKey().bankAccountReference(),
                credit.settlementKey().settlementReference(),
                credit.amount().amountMinor(),
                credit.amount().currency().getCurrencyCode()
        );
    }
}