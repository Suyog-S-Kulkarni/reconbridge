package com.suyog.reconbridge.application.model;

import com.suyog.reconbridge.domain.model.MatchOutcome;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ThreeSourceReconciliationPreview(
        UUID paymentImportId,
        UUID settlementImportId,
        UUID bankImportId,
        long paymentCount,
        long settlementLineCount,
        long settlementBatchCount,
        long bankCreditCount,
        long unresolvedPaymentCount,
        long unresolvedBatchCount,
        long orphanSettlementLineCount,
        long orphanBankCreditCount,
        boolean fullyReconciled,
        Map<MatchOutcome, Long> paymentOutcomeCounts,
        Map<MatchOutcome, Long> bankOutcomeCounts,
        List<PaymentResult> paymentResults,
        List<OrphanSettlementLine> orphanSettlementLines,
        List<BankReconciliationPreview.BatchResult> bankResults,
        List<BankReconciliationPreview.OrphanCredit> orphanBankCredits
) implements ReconciliationReportSnapshot {

    public ThreeSourceReconciliationPreview {
        paymentOutcomeCounts = Map.copyOf(paymentOutcomeCounts);
        bankOutcomeCounts = Map.copyOf(bankOutcomeCounts);
        paymentResults = List.copyOf(paymentResults);
        orphanSettlementLines = List.copyOf(orphanSettlementLines);
        bankResults = List.copyOf(bankResults);
        orphanBankCredits = List.copyOf(orphanBankCredits);
    }

    public record PaymentResult(
            UUID paymentId,
            String provider,
            String paymentReference,
            MatchOutcome outcome,
            List<UUID> candidateSettlementLineIds
    ) {
        public PaymentResult {
            candidateSettlementLineIds = List.copyOf(
                    candidateSettlementLineIds
            );
        }
    }

    public record OrphanSettlementLine(
            UUID lineId,
            String provider,
            String paymentReference,
            long grossMinor,
            long feeMinor,
            long netMinor,
            String currency
    ) {
    }
}