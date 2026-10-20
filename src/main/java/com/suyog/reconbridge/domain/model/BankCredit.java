package com.suyog.reconbridge.domain.model;

import java.util.Objects;
import java.util.UUID;

public record BankCredit(
        UUID id,
        SettlementKey settlementKey,
        Money amount
) {

    public BankCredit {
        Objects.requireNonNull(id, "id must not be null");

        Objects.requireNonNull(
                settlementKey,
                "settlementKey must not be null"
        );

        Objects.requireNonNull(
                amount,
                "amount must not be null"
        );

        if (amount.amountMinor() <= 0) {
            throw new IllegalArgumentException(
                    "Bank credit amount must be greater than zero"
            );
        }
    }
}