package com.suyog.reconbridge.application;

import com.suyog.reconbridge.application.exception.ImportNotFoundException;
import com.suyog.reconbridge.application.exception.StaleImportVersionException;
import com.suyog.reconbridge.application.model.ImportBatchSnapshot;
import com.suyog.reconbridge.domain.model.ImportStatus;
import com.suyog.reconbridge.domain.model.MatchKey;
import com.suyog.reconbridge.domain.model.Money;
import com.suyog.reconbridge.domain.model.PaymentRecord;
import com.suyog.reconbridge.domain.model.SourceType;
import com.suyog.reconbridge.persistence.entity.ImportBatchEntity;
import com.suyog.reconbridge.persistence.entity.TenantEntity;
import com.suyog.reconbridge.persistence.repository.ImportBatchRepository;
import com.suyog.reconbridge.persistence.repository.InternalPaymentWriteRepository;
import com.suyog.reconbridge.persistence.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.reset;

@SpringBootTest
@ActiveProfiles("test")
class InternalPaymentPersistenceServiceIntegrationTest {

    @Autowired
    private InternalPaymentPersistenceService persistence;

    @Autowired
    private ImportLifecycleService lifecycle;

    @Autowired
    private ImportBatchRepository imports;

    @Autowired
    private TenantRepository tenants;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private JwtDecoder decoder;

    @MockitoSpyBean
    private InternalPaymentWriteRepository writer;

    private InternalPaymentWriteRepository writerSpy;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void setUp() {
        writerSpy = AopTestUtils.getUltimateTargetObject(writer);

        assertTrue(
                mockingDetails(writerSpy).isSpy(),
                "Expected a Mockito spy behind the repository proxy."
        );

        reset(writerSpy);

        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();

        tenants.saveAndFlush(
                new TenantEntity(tenantA, "Payment persistence A")
        );

        tenants.saveAndFlush(
                new TenantEntity(tenantB, "Payment persistence B")
        );
    }

    @AfterEach
    void cleanUp() {
        if (writerSpy != null) {
            reset(writerSpy);
        }

        if (tenantA == null || tenantB == null) {
            return;
        }

        jdbc.update(
                """
                DELETE FROM internal_payment
                WHERE tenant_id IN (?, ?)
                """,
                tenantA.toString(),
                tenantB.toString()
        );

        jdbc.update(
                """
                DELETE FROM import_batch
                WHERE tenant_id IN (?, ?)
                """,
                tenantA.toString(),
                tenantB.toString()
        );

        jdbc.update(
                "DELETE FROM tenant WHERE id IN (?, ?)",
                tenantA.toString(),
                tenantB.toString()
        );
    }

    @Test
    void persistsPaymentsAndMarksImportReady() {
        ImportBatchSnapshot processing = processingImport(
                SourceType.INTERNAL_PAYMENT
        );

        PaymentRecord first = payment("PAY-001", 12550);
        PaymentRecord second = payment("PAY-002", 25000);

        ImportBatchSnapshot result = persistence.complete(
                tenantA,
                processing.id(),
                processing.version(),
                List.of(first, second)
        );

        assertEquals(ImportStatus.READY, result.status());
        assertEquals(2L, result.rowCount());
        assertEquals(processing.version() + 1, result.version());
        assertEquals(2L, countPayments(processing.id()));

        Long storedAmount = jdbc.queryForObject(
                """
                SELECT amount_minor
                FROM internal_payment
                WHERE tenant_id = ?
                  AND import_id = ?
                  AND payment_id = ?
                """,
                Long.class,
                tenantA.toString(),
                processing.id().toString(),
                first.id().toString()
        );

        assertEquals(Long.valueOf(12550L), storedAmount);

        ImportBatchSnapshot reloaded = lifecycle.get(
                tenantA,
                processing.id()
        );

        assertEquals(result, reloaded);
    }

    @Test
    void rejectsStaleCompletionWithoutAddingPayments() {
        ImportBatchSnapshot processing = processingImport(
                SourceType.INTERNAL_PAYMENT
        );

        ImportBatchSnapshot completed = persistence.complete(
                tenantA,
                processing.id(),
                processing.version(),
                List.of(payment("PAY-001", 12550))
        );

        assertThrows(
                StaleImportVersionException.class,
                () -> persistence.complete(
                        tenantA,
                        processing.id(),
                        processing.version(),
                        List.of(payment("PAY-002", 25000))
                )
        );

        assertEquals(1L, countPayments(processing.id()));
        assertEquals(
                completed,
                lifecycle.get(tenantA, processing.id())
        );
    }

    @Test
    void rejectsReadyImportEvenWithItsCurrentVersion() {
        ImportBatchSnapshot processing = processingImport(
                SourceType.INTERNAL_PAYMENT
        );

        ImportBatchSnapshot completed = persistence.complete(
                tenantA,
                processing.id(),
                processing.version(),
                List.of(payment("PAY-001", 12550))
        );

        assertThrows(
                IllegalStateException.class,
                () -> persistence.complete(
                        tenantA,
                        completed.id(),
                        completed.version(),
                        List.of(payment("PAY-002", 25000))
                )
        );

        assertEquals(1L, countPayments(processing.id()));
        assertEquals(
                completed,
                lifecycle.get(tenantA, processing.id())
        );
    }

    @Test
    void rejectsReceivedImport() {
        ImportBatchEntity received = receivedImport(
                SourceType.INTERNAL_PAYMENT
        );

        assertThrows(
                IllegalStateException.class,
                () -> persistence.complete(
                        tenantA,
                        received.getId(),
                        received.getVersion(),
                        List.of(payment("PAY-001", 12550))
                )
        );

        assertEquals(0L, countPayments(received.getId()));

        assertEquals(
                ImportStatus.RECEIVED,
                lifecycle.get(tenantA, received.getId()).status()
        );
    }

    @Test
    void rejectsWrongSourceType() {
        ImportBatchSnapshot processing = processingImport(
                SourceType.BANK_STATEMENT
        );

        assertThrows(
                IllegalStateException.class,
                () -> persistence.complete(
                        tenantA,
                        processing.id(),
                        processing.version(),
                        List.of(payment("PAY-001", 12550))
                )
        );

        assertUnchanged(processing);
    }

    @Test
    void hidesForeignTenantImport() {
        ImportBatchSnapshot processing = processingImport(
                SourceType.INTERNAL_PAYMENT
        );

        PaymentRecord foreignPayment = new PaymentRecord(
                UUID.randomUUID(),
                new MatchKey(tenantB, "PROCESSOR_A", "PAY-001"),
                Money.ofMinor(12550, "INR")
        );

        assertThrows(
                ImportNotFoundException.class,
                () -> persistence.complete(
                        tenantB,
                        processing.id(),
                        processing.version(),
                        List.of(foreignPayment)
                )
        );

        assertUnchanged(processing);
    }

    @Test
    void rejectsPaymentWithDifferentTenant() {
        ImportBatchSnapshot processing = processingImport(
                SourceType.INTERNAL_PAYMENT
        );

        PaymentRecord foreignPayment = new PaymentRecord(
                UUID.randomUUID(),
                new MatchKey(tenantB, "PROCESSOR_A", "PAY-001"),
                Money.ofMinor(12550, "INR")
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> persistence.complete(
                        tenantA,
                        processing.id(),
                        processing.version(),
                        List.of(foreignPayment)
                )
        );

        assertUnchanged(processing);
    }

    @Test
    void rejectsPaymentWithDifferentProvider() {
        ImportBatchSnapshot processing = processingImport(
                SourceType.INTERNAL_PAYMENT
        );

        PaymentRecord wrongProvider = new PaymentRecord(
                UUID.randomUUID(),
                new MatchKey(tenantA, "PROCESSOR_B", "PAY-001"),
                Money.ofMinor(12550, "INR")
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> persistence.complete(
                        tenantA,
                        processing.id(),
                        processing.version(),
                        List.of(wrongProvider)
                )
        );

        assertUnchanged(processing);
    }

    @Test
    void rejectsDuplicatePaymentIds() {
        ImportBatchSnapshot processing = processingImport(
                SourceType.INTERNAL_PAYMENT
        );

        PaymentRecord first = payment("PAY-001", 12550);

        PaymentRecord duplicateId = new PaymentRecord(
                first.id(),
                new MatchKey(tenantA, "PROCESSOR_A", "PAY-002"),
                Money.ofMinor(25000, "INR")
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> persistence.complete(
                        tenantA,
                        processing.id(),
                        processing.version(),
                        List.of(first, duplicateId)
                )
        );

        assertUnchanged(processing);
    }

    @Test
    void rejectsDuplicateMatchingKeys() {
        ImportBatchSnapshot processing = processingImport(
                SourceType.INTERNAL_PAYMENT
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> persistence.complete(
                        tenantA,
                        processing.id(),
                        processing.version(),
                        List.of(
                                payment("PAY-001", 12550),
                                payment("PAY-001", 25000)
                        )
                )
        );

        assertUnchanged(processing);
    }

    @Test
    void rejectsEmptyCompletion() {
        ImportBatchSnapshot processing = processingImport(
                SourceType.INTERNAL_PAYMENT
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> persistence.complete(
                        tenantA,
                        processing.id(),
                        processing.version(),
                        List.of()
                )
        );

        assertUnchanged(processing);
    }

    @Test
    void allowsSamePaymentInSeparateImportSnapshots() {
        ImportBatchSnapshot firstImport = processingImport(
                SourceType.INTERNAL_PAYMENT
        );

        ImportBatchSnapshot secondImport = processingImport(
                SourceType.INTERNAL_PAYMENT
        );

        PaymentRecord samePayment = payment("PAY-001", 12550);

        persistence.complete(
                tenantA,
                firstImport.id(),
                firstImport.version(),
                List.of(samePayment)
        );

        persistence.complete(
                tenantA,
                secondImport.id(),
                secondImport.version(),
                List.of(samePayment)
        );

        assertEquals(1L, countPayments(firstImport.id()));
        assertEquals(1L, countPayments(secondImport.id()));
    }

    @Test
    void repositoryRequiresAnExistingTransaction() {
        ImportBatchSnapshot processing = processingImport(
                SourceType.INTERNAL_PAYMENT
        );

        assertThrows(
                IllegalTransactionStateException.class,
                () -> writer.insert(
                        tenantA,
                        processing.id(),
                        payment("PAY-001", 12550)
                )
        );

        assertUnchanged(processing);
    }

    @Test
    void rollsBackEarlierInsertAndReadyTransitionWhenLaterInsertFails() {
        ImportBatchSnapshot processing = processingImport(
                SourceType.INTERNAL_PAYMENT
        );

        AtomicInteger attempts = new AtomicInteger();

        /*
         * Configure the underlying Mockito spy.
         * Stubbing the transaction proxy itself would trigger
         * MANDATORY advice before the test transaction exists.
         */
        doAnswer(invocation -> {
            assertTrue(
                    TransactionSynchronizationManager
                            .isActualTransactionActive()
            );

            int attempt = attempts.incrementAndGet();

            if (attempt == 2) {
                throw new DataIntegrityViolationException(
                        "Simulated second payment insert failure"
                );
            }

            Object result = invocation.callRealMethod();

            assertEquals(
                    1L,
                    countPayments(processing.id()),
                    "First payment must exist before the later failure."
            );

            String statusInsideTransaction = jdbc.queryForObject(
                    """
                    SELECT status
                    FROM import_batch
                    WHERE tenant_id = ? AND id = ?
                    """,
                    String.class,
                    tenantA.toString(),
                    processing.id().toString()
            );

            assertEquals(
                    "READY",
                    statusInsideTransaction,
                    "The flushed state change must also be rolled back."
            );

            return result;
        }).when(writerSpy).insert(
                any(UUID.class),
                any(UUID.class),
                any(PaymentRecord.class)
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> persistence.complete(
                        tenantA,
                        processing.id(),
                        processing.version(),
                        List.of(
                                payment("PAY-001", 12550),
                                payment("PAY-002", 25000)
                        )
                )
        );

        assertEquals(2, attempts.get());
        assertUnchanged(processing);
    }

    private ImportBatchEntity receivedImport(SourceType sourceType) {
        String hash = UUID.randomUUID()
                .toString()
                .replace("-", "")
                .repeat(2);

        return imports.saveAndFlush(
                new ImportBatchEntity(
                        tenantA,
                        sourceType,
                        "PROCESSOR_A",
                        "internal-payments.csv",
                        "local:" + UUID.randomUUID() + ".csv",
                        hash,
                        LocalDate.of(2026, 11, 23)
                )
        );
    }

    private ImportBatchSnapshot processingImport(SourceType sourceType) {
        ImportBatchEntity received = receivedImport(sourceType);

        return lifecycle.startProcessing(
                tenantA,
                received.getId(),
                received.getVersion()
        );
    }

    private PaymentRecord payment(
            String reference,
            long amountMinor
    ) {
        return new PaymentRecord(
                UUID.randomUUID(),
                new MatchKey(
                        tenantA,
                        "PROCESSOR_A",
                        reference
                ),
                Money.ofMinor(amountMinor, "INR")
        );
    }

    private long countPayments(UUID importId) {
        Long count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM internal_payment
                WHERE tenant_id = ? AND import_id = ?
                """,
                Long.class,
                tenantA.toString(),
                importId.toString()
        );

        return count == null ? 0L : count;
    }

    private void assertUnchanged(ImportBatchSnapshot before) {
        assertEquals(0L, countPayments(before.id()));

        assertEquals(
                before,
                lifecycle.get(tenantA, before.id())
        );
    }
}