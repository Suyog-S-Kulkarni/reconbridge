package com.suyog.reconbridge.domain.service;

import com.suyog.reconbridge.domain.matching.ExactReferenceMatchingStrategy;
import com.suyog.reconbridge.domain.model.MatchKey;
import com.suyog.reconbridge.domain.model.MatchOutcome;
import com.suyog.reconbridge.domain.model.Money;
import com.suyog.reconbridge.domain.model.PaymentRecord;
import com.suyog.reconbridge.domain.model.ReconciliationReport;
import com.suyog.reconbridge.domain.model.SettlementLine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentReconciliationServiceTest {

    private static final UUID TENANT_A =
            UUID.fromString(
                    "11111111-1111-4111-8111-111111111111"
            );

    private static final UUID TENANT_B =
            UUID.fromString(
                    "22222222-2222-4222-8222-222222222222"
            );

    private final PaymentReconciliationService service =
            new PaymentReconciliationService(
                    new ExactReferenceMatchingStrategy()
            );

    @Test
    void reportsFullyReconciledWhenEveryPaymentMatches() {
        ReconciliationReport report = service.reconcile(
                List.of(
                        payment("PAY-001", 100_000),
                        payment("PAY-002", 200_000)
                ),
                List.of(
                        settlement(
                                TENANT_A,
                                "PAY-001",
                                100_000,
                                "INR"
                        ),
                        settlement(
                                TENANT_A,
                                "PAY-002",
                                200_000,
                                "INR"
                        )
                )
        );

        assertEquals(2L, report.totalPayments());
        assertEquals(0L, report.unresolvedPaymentCount());
        assertEquals(0L, report.orphanSettlementCount());

        assertEquals(
                2L,
                report.outcomeCounts()
                        .get(MatchOutcome.MATCHED)
                        .longValue()
        );

        assertTrue(report.fullyReconciled());
    }

    @Test
    void summarizesMixedOutcomesWithoutDoubleCountingCandidates() {
        SettlementLine orphan = settlement(
                TENANT_A,
                "PAY-999",
                100_000,
                "INR"
        );

        ReconciliationReport report = service.reconcile(
                List.of(
                        payment("PAY-001", 100_000),
                        payment("PAY-002", 100_000),
                        payment("PAY-003", 100_000),
                        payment("PAY-004", 100_000),
                        payment("PAY-005", 100_000)
                ),
                List.of(
                        settlement(
                                TENANT_A,
                                "PAY-001",
                                100_000,
                                "INR"
                        ),
                        settlement(
                                TENANT_A,
                                "PAY-003",
                                90_000,
                                "INR"
                        ),
                        settlement(
                                TENANT_A,
                                "PAY-004",
                                100_000,
                                "USD"
                        ),
                        settlement(
                                TENANT_A,
                                "PAY-005",
                                100_000,
                                "INR"
                        ),
                        settlement(
                                TENANT_A,
                                "PAY-005",
                                100_000,
                                "INR"
                        ),
                        orphan
                )
        );

        assertEquals(5L, report.totalPayments());
        assertEquals(4L, report.unresolvedPaymentCount());
        assertEquals(1L, report.orphanSettlementCount());

        for (MatchOutcome outcome : MatchOutcome.values()) {
            assertEquals(
                    1L,
                    report.outcomeCounts()
                            .get(outcome)
                            .longValue()
            );
        }

        assertEquals(
                List.of(orphan),
                report.orphanSettlementLines()
        );

        assertFalse(report.fullyReconciled());
    }

    @Test
    void reportsSettlementFromAnotherTenantAsOrphan() {
        SettlementLine otherTenantLine = settlement(
                TENANT_B,
                "PAY-001",
                100_000,
                "INR"
        );

        ReconciliationReport report = service.reconcile(
                List.of(payment("PAY-001", 100_000)),
                List.of(otherTenantLine)
        );

        assertEquals(
                MatchOutcome.MISSING_COUNTERPART,
                report.paymentResults().get(0).outcome()
        );

        assertEquals(
                List.of(otherTenantLine),
                report.orphanSettlementLines()
        );

        assertFalse(report.fullyReconciled());
    }

    @Test
    void reportsAllSettlementsAsOrphansWhenNoPaymentsExist() {
        SettlementLine line = settlement(
                TENANT_A,
                "PAY-001",
                100_000,
                "INR"
        );

        ReconciliationReport report = service.reconcile(
                List.of(),
                List.of(line)
        );

        assertEquals(0L, report.totalPayments());
        assertEquals(1L, report.orphanSettlementCount());
        assertFalse(report.fullyReconciled());
    }

    @Test
    void doesNotTreatEmptyInputAsSuccessfulReconciliation() {
        ReconciliationReport report = service.reconcile(
                List.of(),
                List.of()
        );

        assertEquals(0L, report.totalPayments());
        assertEquals(0L, report.orphanSettlementCount());

        for (MatchOutcome outcome : MatchOutcome.values()) {
            assertEquals(
                    0L,
                    report.outcomeCounts()
                            .get(outcome)
                            .longValue()
            );
        }

        assertFalse(report.fullyReconciled());
    }

    @Test
    void returnsImmutableReportCollections() {
        ReconciliationReport report = service.reconcile(
                List.of(payment("PAY-001", 100_000)),
                List.of(
                        settlement(
                                TENANT_A,
                                "PAY-999",
                                100_000,
                                "INR"
                        )
                )
        );

        assertThrows(
                UnsupportedOperationException.class,
                () -> report.paymentResults().clear()
        );

        assertThrows(
                UnsupportedOperationException.class,
                () -> report.orphanSettlementLines().clear()
        );

        assertThrows(
                UnsupportedOperationException.class,
                () -> report.outcomeCounts().put(
                        MatchOutcome.MATCHED,
                        999L
                )
        );
    }

    @Test
    void reportIsUnaffectedByChangesToCallerLists() {
        List<PaymentRecord> payments = new ArrayList<>();
        payments.add(payment("PAY-001", 100_000));

        List<SettlementLine> lines = new ArrayList<>();
        lines.add(
                settlement(
                        TENANT_A,
                        "PAY-999",
                        100_000,
                        "INR"
                )
        );

        ReconciliationReport report = service.reconcile(
                payments,
                lines
        );

        payments.clear();
        lines.clear();

        assertEquals(1L, report.totalPayments());
        assertEquals(1L, report.orphanSettlementCount());
        assertEquals(1L, report.unresolvedPaymentCount());
    }

    private PaymentRecord payment(
            String reference,
            long amountMinor
    ) {
        return new PaymentRecord(
                UUID.randomUUID(),
                new MatchKey(
                        TENANT_A,
                        "PROCESSOR_A",
                        reference
                ),
                Money.ofMinor(amountMinor, "INR")
        );
    }

    private SettlementLine settlement(
            UUID tenant,
            String reference,
            long grossMinor,
            String currency
    ) {
        Money gross = Money.ofMinor(grossMinor, currency);
        Money fee = Money.ofMinor(2_000, currency);

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