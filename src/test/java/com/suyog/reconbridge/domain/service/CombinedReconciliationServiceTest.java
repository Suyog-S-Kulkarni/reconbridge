package com.suyog.reconbridge.domain.service;

import com.suyog.reconbridge.domain.matching.ExactReferenceMatchingStrategy;
import com.suyog.reconbridge.domain.matching.ExactSettlementBankMatchingStrategy;
import com.suyog.reconbridge.domain.model.BankCredit;
import com.suyog.reconbridge.domain.model.CombinedReconciliationReport;
import com.suyog.reconbridge.domain.model.MatchKey;
import com.suyog.reconbridge.domain.model.MatchOutcome;
import com.suyog.reconbridge.domain.model.Money;
import com.suyog.reconbridge.domain.model.PaymentRecord;
import com.suyog.reconbridge.domain.model.SettlementBatch;
import com.suyog.reconbridge.domain.model.SettlementKey;
import com.suyog.reconbridge.domain.model.SettlementLine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombinedReconciliationServiceTest {

    private static final UUID TENANT =
            UUID.fromString(
                    "11111111-1111-4111-8111-111111111111"
            );

    private final CombinedReconciliationService service =
            new CombinedReconciliationService(
                    new PaymentReconciliationService(
                            new ExactReferenceMatchingStrategy()
                    ),
                    new ExactSettlementBankMatchingStrategy()
            );

    @Test
    void fullyReconcilesBothPasses() {
        SettlementBatch batch = batch();

        CombinedReconciliationReport report = service.reconcile(
                payments(),
                List.of(batch),
                List.of(credit(batch.settlementKey(), 294_000))
        );

        assertTrue(report.paymentReport().fullyReconciled());
        assertEquals(2L, report.paymentReport().totalPayments());

        assertEquals(
                2L,
                report.paymentReport()
                        .outcomeCounts()
                        .get(MatchOutcome.MATCHED)
                        .longValue()
        );

        assertEquals(1L, report.totalSettlementBatches());

        assertEquals(
                1L,
                report.bankOutcomeCounts()
                        .get(MatchOutcome.MATCHED)
                        .longValue()
        );

        assertEquals(0L, report.orphanBankCreditCount());
        assertTrue(report.fullyReconciled());
    }

    @Test
    void reportsBankShortfallDespiteSuccessfulPaymentPass() {
        SettlementBatch batch = batch();

        CombinedReconciliationReport report = service.reconcile(
                payments(),
                List.of(batch),
                List.of(credit(batch.settlementKey(), 290_000))
        );

        assertTrue(report.paymentReport().fullyReconciled());

        assertEquals(
                MatchOutcome.AMOUNT_MISMATCH,
                report.bankResults().get(0).outcome()
        );

        assertEquals(
                Money.ofMinor(4_000, "INR"),
                report.bankResults()
                        .get(0)
                        .difference()
                        .orElseThrow()
        );

        assertEquals(1L, report.unresolvedBatchCount());

        // The mismatched credit has a corresponding batch.
        assertEquals(0L, report.orphanBankCreditCount());
        assertFalse(report.fullyReconciled());
    }

    @Test
    void reportsBankCreditWithoutSettlementBatch() {
        SettlementBatch batch = batch();

        BankCredit orphan = credit(
                settlementKey("SETTLEMENT-999"),
                50_000
        );

        CombinedReconciliationReport report = service.reconcile(
                payments(),
                List.of(batch),
                List.of(
                        credit(batch.settlementKey(), 294_000),
                        orphan
                )
        );

        assertTrue(report.paymentReport().fullyReconciled());
        assertEquals(0L, report.unresolvedBatchCount());

        assertEquals(
                List.of(orphan),
                report.orphanBankCredits()
        );

        assertEquals(1L, report.orphanBankCreditCount());
        assertFalse(report.fullyReconciled());
    }

    @Test
    void bankMatchDoesNotHideMissingInternalPayment() {
        SettlementBatch batch = batch();

        CombinedReconciliationReport report = service.reconcile(
                List.of(payment("PAY-001", 100_000)),
                List.of(batch),
                List.of(credit(batch.settlementKey(), 294_000))
        );

        assertEquals(
                MatchOutcome.MATCHED,
                report.bankResults().get(0).outcome()
        );

        assertEquals(
                1L,
                report.paymentReport().orphanSettlementCount()
        );

        assertEquals(
                "PAY-002",
                report.paymentReport()
                        .orphanSettlementLines()
                        .get(0)
                        .matchKey()
                        .paymentReference()
        );

        assertFalse(report.paymentReport().fullyReconciled());
        assertFalse(report.fullyReconciled());
    }

    @Test
    void ambiguousBankCandidatesAreNotAlsoOrphans() {
        SettlementBatch batch = batch();

        BankCredit first = credit(
                batch.settlementKey(),
                294_000
        );

        BankCredit second = credit(
                batch.settlementKey(),
                290_000
        );

        CombinedReconciliationReport report = service.reconcile(
                payments(),
                List.of(batch),
                List.of(first, second)
        );

        assertEquals(
                MatchOutcome.AMBIGUOUS,
                report.bankResults().get(0).outcome()
        );

        assertEquals(
                List.of(first.id(), second.id()),
                report.bankResults()
                        .get(0)
                        .candidateBankCreditIds()
        );

        assertEquals(0L, report.orphanBankCreditCount());
        assertEquals(1L, report.unresolvedBatchCount());
        assertFalse(report.fullyReconciled());
    }

    @Test
    void reportsMissingBankCredit() {
        CombinedReconciliationReport report = service.reconcile(
                payments(),
                List.of(batch()),
                List.of()
        );

        assertTrue(report.paymentReport().fullyReconciled());

        assertEquals(
                MatchOutcome.MISSING_COUNTERPART,
                report.bankResults().get(0).outcome()
        );

        assertEquals(1L, report.unresolvedBatchCount());
        assertEquals(0L, report.orphanBankCreditCount());
        assertFalse(report.fullyReconciled());
    }

    @Test
    void emptyInputIsNotFullyReconciled() {
        CombinedReconciliationReport report = service.reconcile(
                List.of(),
                List.of(),
                List.of()
        );

        assertEquals(0L, report.paymentReport().totalPayments());
        assertEquals(0L, report.totalSettlementBatches());
        assertEquals(0L, report.orphanBankCreditCount());

        for (MatchOutcome outcome : MatchOutcome.values()) {
            assertEquals(
                    0L,
                    report.bankOutcomeCounts()
                            .get(outcome)
                            .longValue()
            );
        }

        assertFalse(report.fullyReconciled());
    }

    @Test
    void returnsImmutableBankReportCollections() {
        SettlementBatch batch = batch();

        CombinedReconciliationReport report = service.reconcile(
                payments(),
                List.of(batch),
                List.of(
                        credit(batch.settlementKey(), 294_000),
                        credit(
                                settlementKey("SETTLEMENT-999"),
                                50_000
                        )
                )
        );

        assertThrows(
                UnsupportedOperationException.class,
                () -> report.bankResults().clear()
        );

        assertThrows(
                UnsupportedOperationException.class,
                () -> report.orphanBankCredits().clear()
        );

        assertThrows(
                UnsupportedOperationException.class,
                () -> report.bankOutcomeCounts().put(
                        MatchOutcome.MATCHED,
                        999L
                )
        );
    }

    private List<PaymentRecord> payments() {
        return List.of(
                payment("PAY-001", 100_000),
                payment("PAY-002", 200_000)
        );
    }

    private PaymentRecord payment(
            String reference,
            long amountMinor
    ) {
        return new PaymentRecord(
                UUID.randomUUID(),
                new MatchKey(
                        TENANT,
                        "PROCESSOR_A",
                        reference
                ),
                Money.ofMinor(amountMinor, "INR")
        );
    }

    private SettlementBatch batch() {
        return new SettlementBatch(
                UUID.randomUUID(),
                settlementKey("SETTLEMENT-001"),
                List.of(
                        line("PAY-001", 100_000, 2_000),
                        line("PAY-002", 200_000, 4_000)
                ),
                Money.ofMinor(294_000, "INR")
        );
    }

    private SettlementLine line(
            String reference,
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
                        reference
                ),
                gross,
                fee,
                gross.subtract(fee)
        );
    }

    private SettlementKey settlementKey(String reference) {
        return new SettlementKey(
                TENANT,
                "PROCESSOR_A",
                "BANK-OPERATING-001",
                reference
        );
    }

    private BankCredit credit(
            SettlementKey key,
            long amountMinor
    ) {
        return new BankCredit(
                UUID.randomUUID(),
                key,
                Money.ofMinor(amountMinor, "INR")
        );
    }
}