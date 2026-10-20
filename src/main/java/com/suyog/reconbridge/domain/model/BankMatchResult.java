package com.suyog.reconbridge.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record BankMatchResult(
        UUID settlementBatchId,
        SettlementKey settlementKey,
        MatchOutcome outcome,
        List<UUID> candidateBankCreditIds,
        Optional<Money> difference
) {

    public BankMatchResult {
        Objects.requireNonNull(
                settlementBatchId,
                "settlementBatchId must not be null"
        );

        Objects.requireNonNull(
                settlementKey,
                "settlementKey must not be null"
        );

        Objects.requireNonNull(
                outcome,
                "outcome must not be null"
        );

        Objects.requireNonNull(
                candidateBankCreditIds,
                "candidateBankCreditIds must not be null"
        );

        Objects.requireNonNull(
                difference,
                "difference must not be null"
        );

        candidateBankCreditIds = List.copyOf(
                candidateBankCreditIds
        );

        int candidateCount = candidateBankCreditIds.size();

        switch (outcome) {
            case MISSING_COUNTERPART -> {
                if (candidateCount != 0 || difference.isPresent()) {
                    throw new IllegalArgumentException(
                            "Missing counterpart requires no candidates "
                                    + "and no calculated difference"
                    );
                }
            }

            case AMBIGUOUS -> {
                if (candidateCount < 2 || difference.isPresent()) {
                    throw new IllegalArgumentException(
                            "Ambiguous outcome requires multiple candidates "
                                    + "and no calculated difference"
                    );
                }
            }

            case CURRENCY_MISMATCH -> {
                if (candidateCount != 1 || difference.isPresent()) {
                    throw new IllegalArgumentException(
                            "Currency mismatch requires one candidate "
                                    + "and no calculated difference"
                    );
                }
            }

            case MATCHED, AMOUNT_MISMATCH -> {
                if (candidateCount != 1 || difference.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Amount comparison requires one candidate "
                                    + "and a calculated difference"
                    );
                }

                boolean zeroDifference =
                        difference.orElseThrow().isZero();

                if (outcome == MatchOutcome.MATCHED
                        && !zeroDifference) {
                    throw new IllegalArgumentException(
                            "Matched outcome requires zero difference"
                    );
                }

                if (outcome == MatchOutcome.AMOUNT_MISMATCH
                        && zeroDifference) {
                    throw new IllegalArgumentException(
                            "Amount mismatch requires a nonzero difference"
                    );
                }
            }
        }
    }
}