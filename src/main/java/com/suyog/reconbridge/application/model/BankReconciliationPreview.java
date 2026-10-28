package com.suyog.reconbridge.application.model;

import com.suyog.reconbridge.domain.model.MatchOutcome;

import java.util.List;
import java.util.UUID;

public record BankReconciliationPreview(
        UUID settlementImportId,
        UUID bankImportId,
        long settlementBatchCount,
        long bankCreditCount,
        long matchedBatchCount,
        long unresolvedBatchCount,
        long orphanBankCreditCount,
        boolean fullyReconciled,
        List<BatchResult> results,
        List<OrphanCredit> orphanBankCredits
) {

    public BankReconciliationPreview {
        results = List.copyOf(results);
        orphanBankCredits = List.copyOf(orphanBankCredits);
    }

    public record BatchResult(
            UUID settlementBatchId,
            String provider,
            String bankAccountReference,
            String settlementReference,
            MatchOutcome outcome,
            List<UUID> candidateBankCreditIds,
            Long differenceMinor,
            String differenceCurrency
    ) {

        public BatchResult {
            candidateBankCreditIds = List.copyOf(
                    candidateBankCreditIds
            );
        }
    }

    public record OrphanCredit(
            UUID creditId,
            String provider,
            String bankAccountReference,
            String settlementReference,
            long amountMinor,
            String currency
    ) {
    }
}