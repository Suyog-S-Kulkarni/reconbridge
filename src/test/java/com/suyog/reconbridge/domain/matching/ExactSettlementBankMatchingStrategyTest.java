package com.suyog.reconbridge.domain.matching;

import com.suyog.reconbridge.domain.model.BankCredit;
import com.suyog.reconbridge.domain.model.BankMatchResult;
import com.suyog.reconbridge.domain.model.MatchKey;
import com.suyog.reconbridge.domain.model.MatchOutcome;
import com.suyog.reconbridge.domain.model.Money;
import com.suyog.reconbridge.domain.model.SettlementBatch;
import com.suyog.reconbridge.domain.model.SettlementKey;
import com.suyog.reconbridge.domain.model.SettlementLine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExactSettlementBankMatchingStrategyTest {

    private static final UUID TENANT =
            UUID.fromString(
                    "11111111-1111-4111-8111-111111111111"
            );

    private final BankMatchingStrategy strategy =
            new ExactSettlementBankMatchingStrategy();

    @Test
    void matchesExpectedBankCredit() {
        SettlementBatch batch = batch();

        BankMatchResult result = result(
                batch,
                List.of(credit(batch.settlementKey(), 294_000, "INR"))
        );

        assertEquals(MatchOutcome.MATCHED, result.outcome());

        assertEquals(
                Money.ofMinor(0, "INR"),
                result.difference().orElseThrow()
        );
    }

    @Test
    void identifiesFortyRupeeShortfall() {
        SettlementBatch batch = batch();

        BankMatchResult result = result(
                batch,
                List.of(credit(batch.settlementKey(), 290_000, "INR"))
        );

        assertEquals(
                MatchOutcome.AMOUNT_MISMATCH,
                result.outcome()
        );

        assertEquals(
                Money.ofMinor(4_000, "INR"),
                result.difference().orElseThrow()
        );
    }

    @Test
    void preservesNegativeDifferenceForExcessCredit() {
        SettlementBatch batch = batch();

        BankMatchResult result = result(
                batch,
                List.of(credit(batch.settlementKey(), 295_000, "INR"))
        );

        assertEquals(
                MatchOutcome.AMOUNT_MISMATCH,
                result.outcome()
        );

        assertEquals(
                Money.ofMinor(-1_000, "INR"),
                result.difference().orElseThrow()
        );
    }

    @Test
    void reportsMissingBankCredit() {
        BankMatchResult result = result(batch(), List.of());

        assertEquals(
                MatchOutcome.MISSING_COUNTERPART,
                result.outcome()
        );

        assertTrue(result.difference().isEmpty());
    }

    @Test
    void doesNotGuessBetweenMultipleBankCredits() {
        SettlementBatch batch = batch();

        BankCredit first = credit(
                batch.settlementKey(), 294_000, "INR"
        );

        BankCredit second = credit(
                batch.settlementKey(), 290_000, "INR"
        );

        BankMatchResult result = result(
                batch,
                List.of(first, second)
        );

        assertEquals(MatchOutcome.AMBIGUOUS, result.outcome());

        assertEquals(
                List.of(first.id(), second.id()),
                result.candidateBankCreditIds()
        );

        assertTrue(result.difference().isEmpty());
    }

    @Test
    void doesNotSubtractDifferentCurrencies() {
        SettlementBatch batch = batch();

        BankMatchResult result = result(
                batch,
                List.of(credit(batch.settlementKey(), 294_000, "USD"))
        );

        assertEquals(
                MatchOutcome.CURRENCY_MISMATCH,
                result.outcome()
        );

        assertTrue(result.difference().isEmpty());
    }

    @Test
    void doesNotMatchCreditForDifferentBankAccount() {
        SettlementBatch batch = batch();

        SettlementKey otherAccount = new SettlementKey(
                TENANT,
                "PROCESSOR_A",
                "BANK-OTHER-001",
                "SETTLEMENT-001"
        );

        BankMatchResult result = result(
                batch,
                List.of(credit(otherAccount, 294_000, "INR"))
        );

        assertEquals(
                MatchOutcome.MISSING_COUNTERPART,
                result.outcome()
        );
    }

    @Test
    void rejectsIncorrectDeclaredBatchTotal() {
        SettlementBatch validBatch = batch();

        assertThrows(
                IllegalArgumentException.class,
                () -> new SettlementBatch(
                        UUID.randomUUID(),
                        validBatch.settlementKey(),
                        validBatch.lines(),
                        Money.ofMinor(293_000, "INR")
                )
        );
    }

    @Test
    void rejectsSettlementLineReusedAcrossBatches() {
        SettlementBatch first = batch();

        SettlementBatch second = new SettlementBatch(
                UUID.randomUUID(),
                new SettlementKey(
                        TENANT,
                        "PROCESSOR_A",
                        "BANK-OPERATING-001",
                        "SETTLEMENT-002"
                ),
                first.lines(),
                first.declaredNetAmount()
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
    void rejectsDuplicateBatchKeys() {
        SettlementBatch first = batch();
        SettlementBatch second = batch();

        assertThrows(
                IllegalArgumentException.class,
                () -> strategy.match(
                        List.of(first, second),
                        List.of()
                )
        );
    }

    @Test
    void rejectsRepeatedBankCreditRecord() {
        SettlementBatch batch = batch();

        BankCredit credit = credit(
                batch.settlementKey(), 294_000, "INR"
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> strategy.match(
                        List.of(batch),
                        List.of(credit, credit)
                )
        );
    }

    @Test
    void rejectsSettlementLineFromDifferentTenant() {
        SettlementBatch original = batch();

        SettlementKey otherTenant = new SettlementKey(
                UUID.fromString(
                        "22222222-2222-4222-8222-222222222222"
                ),
                "PROCESSOR_A",
                "BANK-OPERATING-001",
                "SETTLEMENT-001"
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new SettlementBatch(
                        UUID.randomUUID(),
                        otherTenant,
                        original.lines(),
                        original.declaredNetAmount()
                )
        );
    }

    private BankMatchResult result(
            SettlementBatch batch,
            List<BankCredit> credits
    ) {
        List<BankMatchResult> results = strategy.match(
                List.of(batch),
                credits
        );

        assertEquals(1, results.size());
        return results.get(0);
    }

    private SettlementBatch batch() {
        return new SettlementBatch(
                UUID.randomUUID(),
                new SettlementKey(
                        TENANT,
                        "PROCESSOR_A",
                        "BANK-OPERATING-001",
                        "SETTLEMENT-001"
                ),
                List.of(
                        line("PAY-001", 100_000, 2_000),
                        line("PAY-002", 200_000, 4_000)
                ),
                Money.ofMinor(294_000, "INR")
        );
    }

    private SettlementLine line(
            String paymentReference,
            long grossMinor,
            long feeMinor
    ) {
        Money gross = Money.ofMinor(grossMinor, "INR");
        Money fee = Money.ofMinor(feeMinor, "INR");

        return new SettlementLine(
                UUID.randomUUID(),
                new MatchKey(
                        TENANT,
                        "PROCESSOR_A",
                        paymentReference
                ),
                gross,
                fee,
                gross.subtract(fee)
        );
    }

    private BankCredit credit(
            SettlementKey key,
            long amountMinor,
            String currency
    ) {
        return new BankCredit(
                UUID.randomUUID(),
                key,
                Money.ofMinor(amountMinor, currency)
        );
    }
}