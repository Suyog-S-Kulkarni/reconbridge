package com.suyog.reconbridge.application.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ReconciliationReadModels {

    private ReconciliationReadModels() {
    }

    public record Page<T>(
            List<T> items,
            int page,
            int size,
            long totalElements,
            long totalPages
    ) {
        public Page {
            items = List.copyOf(items);
        }

        public static <T> Page<T> of(
                List<T> items,
                int page,
                int size,
                long totalElements
        ) {
            long pages = totalElements / size;

            if (totalElements % size != 0) {
                pages++;
            }

            return new Page<>(
                    items,
                    page,
                    size,
                    totalElements,
                    pages
            );
        }
    }

    public record RunSummary(
            UUID id,
            UUID settlementImportId,
            UUID bankImportId,
            String ruleVersion,
            String createdBy,
            Instant createdAt,
            long matchedBatchCount,
            long unresolvedBatchCount,
            long orphanBankCreditCount,
            boolean fullyReconciled
    ) {
    }

    public record UnresolvedResult(
            String itemType,
            UUID evidenceId,
            String outcome,
            BankReconciliationPreview.BatchResult batchResult,
            BankReconciliationPreview.OrphanCredit orphanCredit
    ) {
    }

    public enum UnresolvedOutcome {
        MISSING_COUNTERPART,
        AMBIGUOUS,
        AMOUNT_MISMATCH,
        CURRENCY_MISMATCH,
        ORPHAN_BANK_CREDIT
    }
}