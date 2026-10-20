package com.suyog.reconbridge.domain.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

class SettlementLineTest {

    private final MatchKey key = new MatchKey(
            UUID.fromString(
                    "11111111-1111-4111-8111-111111111111"
            ),
            "PROCESSOR_A",
            "PAY-001"
    );

    @Test
    void rejectsIncorrectNetAmount() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SettlementLine(
                        UUID.randomUUID(),
                        key,
                        Money.ofMinor(100_000, "INR"),
                        Money.ofMinor(2_000, "INR"),
                        Money.ofMinor(99_000, "INR")
                )
        );
    }

    @Test
    void rejectsNegativeFee() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SettlementLine(
                        UUID.randomUUID(),
                        key,
                        Money.ofMinor(100_000, "INR"),
                        Money.ofMinor(-2_000, "INR"),
                        Money.ofMinor(102_000, "INR")
                )
        );
    }

    @Test
    void rejectsFeeInDifferentCurrency() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SettlementLine(
                        UUID.randomUUID(),
                        key,
                        Money.ofMinor(100_000, "INR"),
                        Money.ofMinor(2_000, "USD"),
                        Money.ofMinor(98_000, "INR")
                )
        );
    }

    @Test
    void rejectsNetAmountInDifferentCurrency() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SettlementLine(
                        UUID.randomUUID(),
                        key,
                        Money.ofMinor(100_000, "INR"),
                        Money.ofMinor(2_000, "INR"),
                        Money.ofMinor(98_000, "USD")
                )
        );
    }
}