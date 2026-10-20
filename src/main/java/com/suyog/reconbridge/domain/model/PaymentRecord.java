package com.suyog.reconbridge.domain.model;

import java.util.Objects;
import java.util.UUID;

public record PaymentRecord(
        UUID id,
        MatchKey matchKey,
        Money amount
) {

    public PaymentRecord {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(
                matchKey,
                "matchKey must not be null"
        );
        Objects.requireNonNull(
                amount,
                "amount must not be null"
        );

        if (amount.amountMinor() <= 0) {
            throw new IllegalArgumentException(
                    "Payment amount must be greater than zero"
            );
        }
    }
}