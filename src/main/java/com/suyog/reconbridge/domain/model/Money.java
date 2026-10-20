package com.suyog.reconbridge.domain.model;

import java.util.Currency;
import java.util.Objects;

public record Money(long amountMinor, Currency currency) {

    public Money {
        Objects.requireNonNull(currency, "currency must not be null");
    }

    public static Money ofMinor(long amountMinor, String currencyCode) {
        if (currencyCode == null || currencyCode.isBlank()) {
            throw new IllegalArgumentException(
                    "currencyCode must not be blank"
            );
        }

        Currency currency = Currency.getInstance(currencyCode.trim());
        return new Money(amountMinor, currency);
    }

    public Money add(Money other) {
        requireSameCurrency(other);

        return new Money(
                Math.addExact(amountMinor, other.amountMinor),
                currency
        );
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);

        return new Money(
                Math.subtractExact(amountMinor, other.amountMinor),
                currency
        );
    }

    public boolean isZero() {
        return amountMinor == 0;
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other money must not be null");

        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: "
                            + currency.getCurrencyCode()
                            + " and "
                            + other.currency.getCurrencyCode()
            );
        }
    }
}