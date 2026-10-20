package com.suyog.reconbridge.domain.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ReconciliationReport(
        List<MatchResult> paymentResults,
        List<SettlementLine> orphanSettlementLines
) {

    public ReconciliationReport {
        Objects.requireNonNull(
                paymentResults,
                "paymentResults must not be null"
        );

        Objects.requireNonNull(
                orphanSettlementLines,
                "orphanSettlementLines must not be null"
        );

        paymentResults = List.copyOf(paymentResults);
        orphanSettlementLines = List.copyOf(
                orphanSettlementLines
        );
    }

    public Map<MatchOutcome, Long> outcomeCounts() {
        EnumMap<MatchOutcome, Long> counts =
                new EnumMap<>(MatchOutcome.class);

        for (MatchOutcome outcome : MatchOutcome.values()) {
            counts.put(outcome, 0L);
        }

        for (MatchResult result : paymentResults) {
            counts.merge(result.outcome(), 1L, Long::sum);
        }

        return Collections.unmodifiableMap(counts);
    }

    public long totalPayments() {
        return paymentResults.size();
    }

    public long unresolvedPaymentCount() {
        return paymentResults.stream()
                .filter(result ->
                        result.outcome() != MatchOutcome.MATCHED
                )
                .count();
    }

    public long orphanSettlementCount() {
        return orphanSettlementLines.size();
    }

    public boolean fullyReconciled() {
        return !paymentResults.isEmpty()
                && unresolvedPaymentCount() == 0
                && orphanSettlementLines.isEmpty();
    }
}