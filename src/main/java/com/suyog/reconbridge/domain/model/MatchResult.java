package com.suyog.reconbridge.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record MatchResult(
        UUID paymentId,
        MatchKey matchKey,
        MatchOutcome outcome,
        List<UUID> candidateSettlementIds
) {

    public MatchResult {
        Objects.requireNonNull(
                paymentId,
                "paymentId must not be null"
        );
        Objects.requireNonNull(
                matchKey,
                "matchKey must not be null"
        );
        Objects.requireNonNull(
                outcome,
                "outcome must not be null"
        );
        Objects.requireNonNull(
                candidateSettlementIds,
                "candidateSettlementIds must not be null"
        );

        candidateSettlementIds = List.copyOf(
                candidateSettlementIds
        );

        int candidateCount = candidateSettlementIds.size();

        switch (outcome) {
            case MISSING_COUNTERPART -> {
                if (candidateCount != 0) {
                    throw new IllegalArgumentException(
                            "Missing counterpart must have no candidates"
                    );
                }
            }

            case AMBIGUOUS -> {
                if (candidateCount < 2) {
                    throw new IllegalArgumentException(
                            "Ambiguous result must have multiple candidates"
                    );
                }
            }

            case MATCHED, AMOUNT_MISMATCH, CURRENCY_MISMATCH -> {
                if (candidateCount != 1) {
                    throw new IllegalArgumentException(
                            "This outcome requires exactly one candidate"
                    );
                }
            }
        }
    }
}