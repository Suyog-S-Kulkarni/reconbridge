package com.suyog.reconbridge.domain.matching;

import com.suyog.reconbridge.domain.model.MatchKey;
import com.suyog.reconbridge.domain.model.MatchOutcome;
import com.suyog.reconbridge.domain.model.MatchResult;
import com.suyog.reconbridge.domain.model.Money;
import com.suyog.reconbridge.domain.model.PaymentRecord;
import com.suyog.reconbridge.domain.model.SettlementLine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExactReferenceMatchingStrategyTest {

    private static final UUID TENANT_A =
            UUID.fromString(
                    "11111111-1111-4111-8111-111111111111"
            );

    private static final UUID TENANT_B =
            UUID.fromString(
                    "22222222-2222-4222-8222-222222222222"
            );

    private final MatchingStrategy strategy =
            new ExactReferenceMatchingStrategy();

    @Test
    void matchesPaymentAgainstGrossAmountRatherThanNetAmount() {
        PaymentRecord payment = payment(
                TENANT_A, "PAY-001", 100_000, "INR"
        );

        SettlementLine line = settlement(
                TENANT_A, "PAY-001", 100_000, 2_000, "INR"
        );

        MatchResult result = onlyResult(payment, List.of(line));

        assertEquals(MatchOutcome.MATCHED, result.outcome());
        assertEquals(payment.id(), result.paymentId());
        assertEquals(
                List.of(line.id()),
                result.candidateSettlementIds()
        );
    }

    @Test
    void reportsMissingCounterpart() {
        PaymentRecord payment = payment(
                TENANT_A, "PAY-001", 100_000, "INR"
        );

        MatchResult result = onlyResult(payment, List.of());

        assertEquals(
                MatchOutcome.MISSING_COUNTERPART,
                result.outcome()
        );
        assertTrue(result.candidateSettlementIds().isEmpty());
    }

    @Test
    void preservesAllCandidatesWhenReferenceIsAmbiguous() {
        PaymentRecord payment = payment(
                TENANT_A, "PAY-001", 100_000, "INR"
        );

        SettlementLine first = settlement(
                TENANT_A, "PAY-001", 100_000, 2_000, "INR"
        );

        SettlementLine second = settlement(
                TENANT_A, "PAY-001", 90_000, 2_000, "INR"
        );

        MatchResult result = onlyResult(
                payment,
                List.of(first, second)
        );

        assertEquals(MatchOutcome.AMBIGUOUS, result.outcome());
        assertEquals(
                List.of(first.id(), second.id()),
                result.candidateSettlementIds()
        );
    }

    @Test
    void reportsAmountMismatch() {
        PaymentRecord payment = payment(
                TENANT_A, "PAY-001", 100_000, "INR"
        );

        SettlementLine line = settlement(
                TENANT_A, "PAY-001", 99_000, 2_000, "INR"
        );

        MatchResult result = onlyResult(payment, List.of(line));

        assertEquals(
                MatchOutcome.AMOUNT_MISMATCH,
                result.outcome()
        );
    }

    @Test
    void reportsCurrencyMismatchBeforeAmountMismatch() {
        PaymentRecord payment = payment(
                TENANT_A, "PAY-001", 100_000, "INR"
        );

        SettlementLine line = settlement(
                TENANT_A, "PAY-001", 50_000, 1_000, "USD"
        );

        MatchResult result = onlyResult(payment, List.of(line));

        assertEquals(
                MatchOutcome.CURRENCY_MISMATCH,
                result.outcome()
        );
    }

    @Test
    void doesNotMatchAcrossTenants() {
        PaymentRecord payment = payment(
                TENANT_A, "PAY-001", 100_000, "INR"
        );

        SettlementLine line = settlement(
                TENANT_B, "PAY-001", 100_000, 2_000, "INR"
        );

        MatchResult result = onlyResult(payment, List.of(line));

        assertEquals(
                MatchOutcome.MISSING_COUNTERPART,
                result.outcome()
        );
        assertTrue(result.candidateSettlementIds().isEmpty());
    }

    @Test
    void doesNotMatchAcrossProviders() {
        PaymentRecord payment = payment(
                TENANT_A, "PAY-001", 100_000, "INR"
        );

        SettlementLine line = new SettlementLine(
                UUID.randomUUID(),
                new MatchKey(
                        TENANT_A,
                        "PROCESSOR_B",
                        "PAY-001"
                ),
                Money.ofMinor(100_000, "INR"),
                Money.ofMinor(2_000, "INR"),
                Money.ofMinor(98_000, "INR")
        );

        MatchResult result = onlyResult(payment, List.of(line));

        assertEquals(
                MatchOutcome.MISSING_COUNTERPART,
                result.outcome()
        );
    }

    @Test
    void rejectsDuplicateInternalPaymentReferences() {
        PaymentRecord first = payment(
                TENANT_A, "PAY-001", 100_000, "INR"
        );

        PaymentRecord second = payment(
                TENANT_A, "PAY-001", 100_000, "INR"
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> strategy.match(
                        List.of(first, second),
                        List.of()
                )
        );
    }

    @Test
    void rejectsRepeatedSettlementRecord() {
        PaymentRecord payment = payment(
                TENANT_A, "PAY-001", 100_000, "INR"
        );

        SettlementLine line = settlement(
                TENANT_A, "PAY-001", 100_000, 2_000, "INR"
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> strategy.match(
                        List.of(payment),
                        List.of(line, line)
                )
        );
    }

    @Test
    void preservesPaymentInputOrder() {
        PaymentRecord first = payment(
                TENANT_A, "PAY-002", 200_000, "INR"
        );

        PaymentRecord second = payment(
                TENANT_A, "PAY-001", 100_000, "INR"
        );

        List<MatchResult> results = strategy.match(
                List.of(first, second),
                List.of()
        );

        assertEquals(
                List.of(first.id(), second.id()),
                results.stream()
                        .map(MatchResult::paymentId)
                        .toList()
        );
    }

    @Test
    void returnsImmutableResultsAndCandidateLists() {
        PaymentRecord payment = payment(
                TENANT_A, "PAY-001", 100_000, "INR"
        );

        SettlementLine line = settlement(
                TENANT_A, "PAY-001", 100_000, 2_000, "INR"
        );

        List<MatchResult> results = strategy.match(
                List.of(payment),
                List.of(line)
        );

        assertThrows(
                UnsupportedOperationException.class,
                results::clear
        );

        assertThrows(
                UnsupportedOperationException.class,
                () -> results.get(0)
                        .candidateSettlementIds()
                        .clear()
        );
    }

    private MatchResult onlyResult(
            PaymentRecord payment,
            List<SettlementLine> lines
    ) {
        List<MatchResult> results = strategy.match(
                List.of(payment),
                lines
        );

        assertEquals(1, results.size());
        return results.get(0);
    }

    private PaymentRecord payment(
            UUID tenant,
            String reference,
            long amountMinor,
            String currency
    ) {
        return new PaymentRecord(
                UUID.randomUUID(),
                new MatchKey(
                        tenant,
                        "PROCESSOR_A",
                        reference
                ),
                Money.ofMinor(amountMinor, currency)
        );
    }

    private SettlementLine settlement(
            UUID tenant,
            String reference,
            long grossMinor,
            long feeMinor,
            String currency
    ) {
        Money gross = Money.ofMinor(grossMinor, currency);
        Money fee = Money.ofMinor(feeMinor, currency);

        return new SettlementLine(
                UUID.randomUUID(),
                new MatchKey(
                        tenant,
                        "PROCESSOR_A",
                        reference
                ),
                gross,
                fee,
                gross.subtract(fee)
        );
    }
}