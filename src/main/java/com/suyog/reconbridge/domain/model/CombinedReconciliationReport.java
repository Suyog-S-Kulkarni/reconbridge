package com.suyog.reconbridge.domain.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record CombinedReconciliationReport(
        ReconciliationReport paymentReport,
        List<BankMatchResult> bankResults,
        List<BankCredit> orphanBankCredits
) {

    public CombinedReconciliationReport {
        Objects.requireNonNull(
                paymentReport,
                "paymentReport must not be null"
        );

        Objects.requireNonNull(
                bankResults,
                "bankResults must not be null"
        );

        Objects.requireNonNull(
                orphanBankCredits,
                "orphanBankCredits must not be null"
        );

        bankResults = List.copyOf(bankResults);
        orphanBankCredits = List.copyOf(orphanBankCredits);
    }

    public Map<MatchOutcome, Long> bankOutcomeCounts() {
        EnumMap<MatchOutcome, Long> counts =
                new EnumMap<>(MatchOutcome.class);

        for (MatchOutcome outcome : MatchOutcome.values()) {
            counts.put(outcome, 0L);
        }

        for (BankMatchResult result : bankResults) {
            counts.merge(result.outcome(), 1L, Long::sum);
        }

        return Collections.unmodifiableMap(counts);
    }

    public long totalSettlementBatches() {
        return bankResults.size();
    }

    public long unresolvedBatchCount() {
        return bankResults.stream()
                .filter(result ->
                        result.outcome() != MatchOutcome.MATCHED
                )
                .count();
    }

    public long orphanBankCreditCount() {
        return orphanBankCredits.size();
    }

    public boolean fullyReconciled() {
        return paymentReport.fullyReconciled()
                && !bankResults.isEmpty()
                && unresolvedBatchCount() == 0
                && orphanBankCredits.isEmpty();
    }
}