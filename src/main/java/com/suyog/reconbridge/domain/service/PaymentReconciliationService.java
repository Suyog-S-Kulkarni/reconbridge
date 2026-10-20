package com.suyog.reconbridge.domain.service;

import com.suyog.reconbridge.domain.matching.MatchingStrategy;
import com.suyog.reconbridge.domain.model.MatchKey;
import com.suyog.reconbridge.domain.model.MatchResult;
import com.suyog.reconbridge.domain.model.PaymentRecord;
import com.suyog.reconbridge.domain.model.ReconciliationReport;
import com.suyog.reconbridge.domain.model.SettlementLine;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class PaymentReconciliationService {

    private final MatchingStrategy matchingStrategy;

    public PaymentReconciliationService(
            MatchingStrategy matchingStrategy
    ) {
        this.matchingStrategy = Objects.requireNonNull(
                matchingStrategy,
                "matchingStrategy must not be null"
        );
    }

    public ReconciliationReport reconcile(
            List<PaymentRecord> payments,
            List<SettlementLine> settlementLines
    ) {
        Objects.requireNonNull(
                payments,
                "payments must not be null"
        );

        Objects.requireNonNull(
                settlementLines,
                "settlementLines must not be null"
        );

        List<PaymentRecord> paymentSnapshot =
                List.copyOf(payments);

        List<SettlementLine> settlementSnapshot =
                List.copyOf(settlementLines);

        List<MatchResult> paymentResults =
                matchingStrategy.match(
                        paymentSnapshot,
                        settlementSnapshot
                );

        Set<MatchKey> paymentKeys = new HashSet<>();

        for (PaymentRecord payment : paymentSnapshot) {
            paymentKeys.add(payment.matchKey());
        }

        List<SettlementLine> orphanSettlementLines =
                settlementSnapshot.stream()
                        .filter(line ->
                                !paymentKeys.contains(
                                        line.matchKey()
                                )
                        )
                        .toList();

        return new ReconciliationReport(
                paymentResults,
                orphanSettlementLines
        );
    }
}