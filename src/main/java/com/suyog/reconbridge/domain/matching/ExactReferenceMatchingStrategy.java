package com.suyog.reconbridge.domain.matching;

import com.suyog.reconbridge.domain.model.MatchKey;
import com.suyog.reconbridge.domain.model.MatchOutcome;
import com.suyog.reconbridge.domain.model.MatchResult;
import com.suyog.reconbridge.domain.model.PaymentRecord;
import com.suyog.reconbridge.domain.model.SettlementLine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class ExactReferenceMatchingStrategy
        implements MatchingStrategy {

    @Override
    public List<MatchResult> match(
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

        validatePayments(payments);

        Map<MatchKey, List<SettlementLine>> settlementIndex =
                buildSettlementIndex(settlementLines);

        List<MatchResult> results = new ArrayList<>(
                payments.size()
        );

        for (PaymentRecord payment : payments) {
            List<SettlementLine> candidates =
                    settlementIndex.getOrDefault(
                            payment.matchKey(),
                            List.of()
                    );

            results.add(evaluate(payment, candidates));
        }

        return List.copyOf(results);
    }

    private void validatePayments(List<PaymentRecord> payments) {
        Set<UUID> paymentIds = new HashSet<>();
        Set<MatchKey> paymentKeys = new HashSet<>();

        for (PaymentRecord payment : payments) {
            Objects.requireNonNull(
                    payment,
                    "payment must not be null"
            );

            if (!paymentIds.add(payment.id())) {
                throw new IllegalArgumentException(
                        "Duplicate payment ID: " + payment.id()
                );
            }

            if (!paymentKeys.add(payment.matchKey())) {
                throw new IllegalArgumentException(
                        "Duplicate internal payment reference: "
                                + payment.matchKey()
                );
            }
        }
    }

    private Map<MatchKey, List<SettlementLine>>
    buildSettlementIndex(List<SettlementLine> settlementLines) {

        Map<MatchKey, List<SettlementLine>> index =
                new HashMap<>();

        Set<UUID> settlementIds = new HashSet<>();

        for (SettlementLine line : settlementLines) {
            Objects.requireNonNull(
                    line,
                    "settlement line must not be null"
            );

            if (!settlementIds.add(line.id())) {
                throw new IllegalArgumentException(
                        "Duplicate settlement line ID: " + line.id()
                );
            }

            index.computeIfAbsent(
                    line.matchKey(),
                    ignored -> new ArrayList<>()
            ).add(line);
        }

        return index;
    }

    private MatchResult evaluate(
            PaymentRecord payment,
            List<SettlementLine> candidates
    ) {
        if (candidates.isEmpty()) {
            return new MatchResult(
                    payment.id(),
                    payment.matchKey(),
                    MatchOutcome.MISSING_COUNTERPART,
                    List.of()
            );
        }

        List<UUID> candidateIds = candidates.stream()
                .map(SettlementLine::id)
                .toList();

        if (candidates.size() > 1) {
            return new MatchResult(
                    payment.id(),
                    payment.matchKey(),
                    MatchOutcome.AMBIGUOUS,
                    candidateIds
            );
        }

        SettlementLine candidate = candidates.get(0);

        MatchOutcome outcome;

        if (!payment.amount().currency()
                .equals(candidate.grossAmount().currency())) {

            outcome = MatchOutcome.CURRENCY_MISMATCH;

        } else if (payment.amount().amountMinor()
                != candidate.grossAmount().amountMinor()) {

            outcome = MatchOutcome.AMOUNT_MISMATCH;

        } else {
            outcome = MatchOutcome.MATCHED;
        }

        return new MatchResult(
                payment.id(),
                payment.matchKey(),
                outcome,
                candidateIds
        );
    }
}