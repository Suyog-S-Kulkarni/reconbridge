package com.suyog.reconbridge.domain.model;

import java.util.Objects;
import java.util.UUID;

public record SettlementLine(
        UUID id,
        MatchKey matchKey,
        Money grossAmount,
        Money feeAmount,
        Money netAmount
) {

    public SettlementLine {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(
                matchKey,
                "matchKey must not be null"
        );
        Objects.requireNonNull(
                grossAmount,
                "grossAmount must not be null"
        );
        Objects.requireNonNull(
                feeAmount,
                "feeAmount must not be null"
        );
        Objects.requireNonNull(
                netAmount,
                "netAmount must not be null"
        );

        if (grossAmount.amountMinor() <= 0) {
            throw new IllegalArgumentException(
                    "Gross amount must be greater than zero"
            );
        }

        if (feeAmount.amountMinor() < 0) {
            throw new IllegalArgumentException(
                    "Fee amount must not be negative"
            );
        }

        if (netAmount.amountMinor() < 0) {
            throw new IllegalArgumentException(
                    "Net amount must not be negative"
            );
        }

        Money calculatedNet = grossAmount.subtract(feeAmount);

        if (!calculatedNet.equals(netAmount)) {
            throw new IllegalArgumentException(
                    "Net amount must equal gross amount minus fees "
                            + "in the same currency"
            );
        }
    }
}