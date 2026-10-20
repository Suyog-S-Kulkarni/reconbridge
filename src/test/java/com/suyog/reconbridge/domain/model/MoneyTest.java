package com.suyog.reconbridge.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoneyTest {

    @Test
    void calculatesExpectedSettlementAfterFees() {
        Money firstPayment = Money.ofMinor(100_000, "INR");
        Money secondPayment = Money.ofMinor(200_000, "INR");
        Money fees = Money.ofMinor(6_000, "INR");

        Money expectedSettlement = firstPayment
                .add(secondPayment)
                .subtract(fees);

        assertEquals(
                Money.ofMinor(294_000, "INR"),
                expectedSettlement
        );
    }

    @Test
    void calculatesFortyRupeeShortfall() {
        Money expected = Money.ofMinor(294_000, "INR");
        Money actual = Money.ofMinor(290_000, "INR");

        Money difference = expected.subtract(actual);

        assertEquals(
                Money.ofMinor(4_000, "INR"),
                difference
        );
    }

    @Test
    void preservesNegativeDifferenceWhenActualExceedsExpected() {
        Money expected = Money.ofMinor(294_000, "INR");
        Money actual = Money.ofMinor(295_000, "INR");

        assertEquals(
                Money.ofMinor(-1_000, "INR"),
                expected.subtract(actual)
        );
    }

    @Test
    void rejectsAdditionAcrossCurrencies() {
        Money rupees = Money.ofMinor(10_000, "INR");
        Money dollars = Money.ofMinor(10_000, "USD");

        assertThrows(
                IllegalArgumentException.class,
                () -> rupees.add(dollars)
        );
    }

    @Test
    void rejectsSubtractionAcrossCurrencies() {
        Money rupees = Money.ofMinor(10_000, "INR");
        Money dollars = Money.ofMinor(10_000, "USD");

        assertThrows(
                IllegalArgumentException.class,
                () -> rupees.subtract(dollars)
        );
    }

    @Test
    void rejectsAdditionOverflow() {
        Money maximum = Money.ofMinor(Long.MAX_VALUE, "INR");
        Money oneMinorUnit = Money.ofMinor(1, "INR");

        assertThrows(
                ArithmeticException.class,
                () -> maximum.add(oneMinorUnit)
        );
    }

    @Test
    void rejectsSubtractionOverflow() {
        Money minimum = Money.ofMinor(Long.MIN_VALUE, "INR");
        Money oneMinorUnit = Money.ofMinor(1, "INR");

        assertThrows(
                ArithmeticException.class,
                () -> minimum.subtract(oneMinorUnit)
        );
    }

    @Test
    void recognizesZeroDifference() {
        Money expected = Money.ofMinor(294_000, "INR");
        Money actual = Money.ofMinor(294_000, "INR");

        assertTrue(expected.subtract(actual).isZero());
    }

    @Test
    void rejectsInvalidCurrencyCode() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Money.ofMinor(100, "INVALID")
        );
    }

    @Test
    void rejectsMissingCurrency() {
        assertThrows(
                NullPointerException.class,
                () -> new Money(100, null)
        );
    }
}