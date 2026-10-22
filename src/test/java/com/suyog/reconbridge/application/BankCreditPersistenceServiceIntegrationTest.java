package com.suyog.reconbridge.application;

import com.suyog.reconbridge.application.exception.ImportNotFoundException;
import com.suyog.reconbridge.application.exception.StaleImportVersionException;
import com.suyog.reconbridge.application.model.ImportBatchSnapshot;
import com.suyog.reconbridge.domain.model.BankCredit;
import com.suyog.reconbridge.domain.model.ImportStatus;
import com.suyog.reconbridge.domain.model.Money;
import com.suyog.reconbridge.domain.model.SettlementKey;
import com.suyog.reconbridge.domain.model.SourceType;
import com.suyog.reconbridge.persistence.entity.ImportBatchEntity;
import com.suyog.reconbridge.persistence.entity.TenantEntity;
import com.suyog.reconbridge.persistence.repository.BankCreditWriteRepository;
import com.suyog.reconbridge.persistence.repository.ImportBatchRepository;
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
class BankCreditPersistenceServiceIntegrationTest {

    @Autowired
    private BankCreditPersistenceService persistence;

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
    private BankCreditWriteRepository writer;

    private BankCreditWriteRepository writerSpy;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void setUp() {
        /*
         * Configure Mockito against the underlying spy.
         * Calling a stub declaration through the transaction proxy
         * would trigger its MANDATORY transaction advice.
         */
        writerSpy = AopTestUtils.getUltimateTargetObject(writer);

        assertTrue(
                mockingDetails(writerSpy).isSpy(),
                "Expected the repository target to be a Mockito spy."
        );

        reset(writerSpy);

        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();

        tenants.saveAndFlush(
                new TenantEntity(tenantA, "Bank persistence A")
        );

        tenants.saveAndFlush(
                new TenantEntity(tenantB, "Bank persistence B")
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
                "DELETE FROM bank_credit WHERE tenant_id IN (?, ?)",
                tenantA.toString(),
                tenantB.toString()
        );

        jdbc.update(
                "DELETE FROM import_batch WHERE tenant_id IN (?, ?)",
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
    void persistsCreditsAndMarksImportReady() {
        ImportBatchSnapshot processing = processingImport(
                SourceType.BANK_STATEMENT
        );

        BankCredit first = credit(
                tenantA,
                UUID.randomUUID(),
                "SET-001",
                12400
        );

        BankCredit second = credit(
                tenantA,
                UUID.randomUUID(),
                "SET-002",
                24700
        );

        ImportBatchSnapshot completed = persistence.complete(
                tenantA,
                processing.id(),
                processing.version(),
                List.of(first, second)
        );

        assertEquals(ImportStatus.READY, completed.status());
        assertEquals(2L, completed.rowCount());

        assertEquals(
                processing.version() + 1,
                completed.version()
        );

        assertEquals(2L, countCredits(processing.id()));

        Long totalAmount = jdbc.queryForObject(
                """
                SELECT SUM(amount_minor)
                FROM bank_credit
                WHERE tenant_id = ?
                  AND import_id = ?
                """,
                Long.class,
                tenantA.toString(),
                processing.id().toString()
        );

        assertEquals(
                Long.valueOf(37100L),
                totalAmount
        );

        ImportBatchSnapshot stored = lifecycle.get(
                tenantA,
                processing.id()
        );

        assertEquals(ImportStatus.READY, stored.status());
        assertEquals(2L, stored.rowCount());
        assertEquals(completed.version(), stored.version());
    }

    @Test
    void rejectsStaleCompletionWithoutAddingMoreCredits() {
        ImportBatchSnapshot processing = processingImport(
                SourceType.BANK_STATEMENT
        );

        ImportBatchSnapshot completed = persistence.complete(
                tenantA,
                processing.id(),
                processing.version(),
                List.of(
                        credit(
                                tenantA,
                                UUID.randomUUID(),
                                "SET-001",
                                12400
                        )
                )
        );

        /*
         * Verify the first transaction committed successfully
         * before attempting the stale completion.
         */
        assertEquals(
                1L,
                countCredits(processing.id()),
                "The first completion must persist one credit."
        );

        assertEquals(
                ImportStatus.READY,
                lifecycle.get(tenantA, processing.id()).status()
        );

        assertThrows(
                StaleImportVersionException.class,
                () -> persistence.complete(
                        tenantA,
                        processing.id(),
                        processing.version(),
                        List.of(
                                credit(
                                        tenantA,
                                        UUID.randomUUID(),
                                        "SET-002",
                                        24700
                                )
                        )
                )
        );

        assertEquals(
                1L,
                countCredits(processing.id()),
                "Stale completion must preserve the existing credit."
        );

        ImportBatchSnapshot stored = lifecycle.get(
                tenantA,
                processing.id()
        );

        assertEquals(ImportStatus.READY, stored.status());
        assertEquals(completed.version(), stored.version());
        assertEquals(1L, stored.rowCount());
    }

    @Test
    void rejectsAnotherTenantsImport() {
        ImportBatchSnapshot processing = processingImport(
                SourceType.BANK_STATEMENT
        );

        assertThrows(
                ImportNotFoundException.class,
                () -> persistence.complete(
                        tenantB,
                        processing.id(),
                        processing.version(),
                        List.of(
                                credit(
                                        tenantB,
                                        UUID.randomUUID(),
                                        "SET-001",
                                        12400
                                )
                        )
                )
        );

        assertEquals(0L, countCredits(processing.id()));
        assertStillProcessing(processing);
    }

    @Test
    void rejectsCreditBelongingToAnotherTenant() {
        ImportBatchSnapshot processing = processingImport(
                SourceType.BANK_STATEMENT
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> persistence.complete(
                        tenantA,
                        processing.id(),
                        processing.version(),
                        List.of(
                                credit(
                                        tenantB,
                                        UUID.randomUUID(),
                                        "SET-001",
                                        12400
                                )
                        )
                )
        );

        assertEquals(0L, countCredits(processing.id()));
        assertStillProcessing(processing);
    }

    @Test
    void rejectsWrongImportSource() {
        ImportBatchSnapshot processing = processingImport(
                SourceType.PROCESSOR_SETTLEMENT
        );

        assertThrows(
                IllegalStateException.class,
                () -> persistence.complete(
                        tenantA,
                        processing.id(),
                        processing.version(),
                        List.of(
                                credit(
                                        tenantA,
                                        UUID.randomUUID(),
                                        "SET-001",
                                        12400
                                )
                        )
                )
        );

        assertEquals(0L, countCredits(processing.id()));
        assertStillProcessing(processing);
    }

    @Test
    void rejectsDuplicateCreditIdsBeforeWriting() {
        ImportBatchSnapshot processing = processingImport(
                SourceType.BANK_STATEMENT
        );

        UUID duplicateId = UUID.randomUUID();

        assertThrows(
                IllegalArgumentException.class,
                () -> persistence.complete(
                        tenantA,
                        processing.id(),
                        processing.version(),
                        List.of(
                                credit(
                                        tenantA,
                                        duplicateId,
                                        "SET-001",
                                        12400
                                ),
                                credit(
                                        tenantA,
                                        duplicateId,
                                        "SET-002",
                                        24700
                                )
                        )
                )
        );

        assertEquals(0L, countCredits(processing.id()));
        assertStillProcessing(processing);
    }

    @Test
    void preservesDistinctCreditsWithTheSameMatchingKey() {
        ImportBatchSnapshot processing = processingImport(
                SourceType.BANK_STATEMENT
        );

        persistence.complete(
                tenantA,
                processing.id(),
                processing.version(),
                List.of(
                        credit(
                                tenantA,
                                UUID.randomUUID(),
                                "SET-001",
                                12400
                        ),
                        credit(
                                tenantA,
                                UUID.randomUUID(),
                                "SET-001",
                                12400
                        )
                )
        );

        assertEquals(2L, countCredits(processing.id()));

        assertEquals(
                ImportStatus.READY,
                lifecycle.get(tenantA, processing.id()).status()
        );
    }

    @Test
    void rollsBackEarlierInsertAndReadyTransitionWhenLaterInsertFails() {
        ImportBatchSnapshot processing = processingImport(
                SourceType.BANK_STATEMENT
        );

        AtomicInteger attempts = new AtomicInteger();

        /*
         * Stub the underlying spy, not the Spring transaction proxy.
         * Application calls still reach it through the real proxy.
         */
        doAnswer(invocation -> {
            assertTrue(
                    TransactionSynchronizationManager
                            .isActualTransactionActive(),
                    "Credit insertion must execute inside a transaction."
            );

            int attempt = attempts.incrementAndGet();

            if (attempt == 2) {
                throw new DataIntegrityViolationException(
                        "Simulated failure during second insert"
                );
            }

            Object result = invocation.callRealMethod();

            /*
             * Verify that the first insert really happened inside
             * the transaction before the second insert fails.
             */
            assertEquals(
                    1L,
                    countCredits(processing.id()),
                    "First credit should exist before rollback."
            );

            return result;

        }).when(writerSpy).insert(
                any(UUID.class),
                any(UUID.class),
                any(BankCredit.class)
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> persistence.complete(
                        tenantA,
                        processing.id(),
                        processing.version(),
                        List.of(
                                credit(
                                        tenantA,
                                        UUID.randomUUID(),
                                        "SET-001",
                                        12400
                                ),
                                credit(
                                        tenantA,
                                        UUID.randomUUID(),
                                        "SET-002",
                                        24700
                                )
                        )
                )
        );

        assertEquals(2, attempts.get());

        assertEquals(
                0L,
                countCredits(processing.id()),
                "The first insert must be rolled back."
        );

        assertStillProcessing(processing);
    }

    @Test
    void rejectsEmptyCompletion() {
        ImportBatchSnapshot processing = processingImport(
                SourceType.BANK_STATEMENT
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

        assertEquals(0L, countCredits(processing.id()));
        assertStillProcessing(processing);
    }

    private ImportBatchSnapshot processingImport(
            SourceType sourceType
    ) {
        ImportBatchEntity imported = new ImportBatchEntity(
                tenantA,
                sourceType,
                "BANK_A",
                "bank-statement.csv",
                "test://bank-statement.csv",
                "a".repeat(64),
                LocalDate.of(2026, 10, 22)
        );

        ImportBatchEntity saved = imports.saveAndFlush(imported);

        return lifecycle.startProcessing(
                tenantA,
                saved.getId(),
                saved.getVersion()
        );
    }

    private BankCredit credit(
            UUID tenantId,
            UUID creditId,
            String settlementReference,
            long amountMinor
    ) {
        return new BankCredit(
                creditId,
                new SettlementKey(
                        tenantId,
                        "PROCESSOR_A",
                        "BANK-001",
                        settlementReference
                ),
                Money.ofMinor(amountMinor, "INR")
        );
    }

    private long countCredits(UUID importId) {
        Long count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM bank_credit
                WHERE tenant_id = ?
                  AND import_id = ?
                """,
                Long.class,
                tenantA.toString(),
                importId.toString()
        );

        return count == null ? 0L : count;
    }

    private void assertStillProcessing(
            ImportBatchSnapshot original
    ) {
        ImportBatchSnapshot current = lifecycle.get(
                tenantA,
                original.id()
        );

        assertEquals(ImportStatus.PROCESSING, current.status());
        assertEquals(original.version(), current.version());
        assertEquals(0L, current.rowCount());
    }
}