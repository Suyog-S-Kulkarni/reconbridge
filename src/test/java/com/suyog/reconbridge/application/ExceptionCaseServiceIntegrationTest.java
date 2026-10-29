package com.suyog.reconbridge.application;

import com.suyog.reconbridge.application.exception.ExceptionCaseOperationException;
import com.suyog.reconbridge.application.model.BankReconciliationPreview;
import com.suyog.reconbridge.application.model.CaseNoteResult;
import com.suyog.reconbridge.application.model.StoredExceptionCase;
import com.suyog.reconbridge.application.model.StoredReconciliationRun;
import com.suyog.reconbridge.domain.model.CaseEvidenceReference;
import com.suyog.reconbridge.domain.model.ExceptionCase;
import com.suyog.reconbridge.domain.model.ExceptionCaseStatus;
import com.suyog.reconbridge.domain.model.InvestigationNote;
import com.suyog.reconbridge.domain.model.MatchOutcome;
import com.suyog.reconbridge.persistence.repository.ExceptionCaseRepository;
import com.suyog.reconbridge.persistence.repository.ReconciliationRunRepository;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@SpringBootTest
@ActiveProfiles("test")
class ExceptionCaseServiceIntegrationTest {

    @Autowired
    private ExceptionCaseService service;

    @Autowired
    private ReconciliationRunRepository runRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private JwtDecoder decoder;

    @MockitoSpyBean
    private ExceptionCaseRepository repository;

    private ExceptionCaseRepository repositorySpy;

    private UUID tenantA;
    private UUID tenantB;
    private UUID runA;
    private UUID runB;

    private UUID mismatchId;
    private UUID matchedId;
    private UUID orphanId;

    @BeforeEach
    void setUp() {
        repositorySpy = AopTestUtils.getUltimateTargetObject(repository);
        reset(repositorySpy);

        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();

        mismatchId = UUID.randomUUID();
        matchedId = UUID.randomUUID();
        orphanId = UUID.randomUUID();

        jdbc.update(
                "INSERT INTO tenant (id, name) VALUES (?, ?)",
                tenantA.toString(),
                "Case tenant A"
        );

        jdbc.update(
                "INSERT INTO tenant (id, name) VALUES (?, ?)",
                tenantB.toString(),
                "Case tenant B"
        );

        runA = seedRun(tenantA);
        runB = seedRun(tenantB);
    }

    @AfterEach
    void cleanUp() {
        reset(repositorySpy);

        for (String table : List.of(
                "investigation_note",
                "exception_case",
                "reconciliation_run_request",
                "reconciliation_run_item",
                "reconciliation_run",
                "import_batch",
                "tenant"
        )) {
            String column = table.equals("tenant")
                    ? "id"
                    : "tenant_id";

            jdbc.update(
                    "DELETE FROM " + table
                            + " WHERE " + column + " IN (?, ?)",
                    tenantA.toString(),
                    tenantB.toString()
            );
        }
    }

    @Test
    void opensCaseUsingStoredEvidenceReason() {
        StoredExceptionCase opened = openMismatch();

        assertEquals(0L, opened.version());
        assertEquals(ExceptionCaseStatus.OPEN, opened.details().status());
        assertEquals(
                CaseEvidenceReference.Reason.AMOUNT_MISMATCH,
                opened.details().evidence().reason()
        );
        assertEquals(runA, opened.details().evidence().runId());

        assertEquals(
                opened,
                service.get(tenantA, opened.details().id())
        );

        assertEquals(1L, count("exception_case"));
    }

    @Test
    void duplicateOpenReturnsOriginalCaseWithoutResettingIt() {
        StoredExceptionCase first = openMismatch();

        StoredExceptionCase claimed = service.claim(
                tenantA,
                first.details().id(),
                first.version(),
                "analyst-one"
        );

        StoredExceptionCase repeated = service.open(
                tenantA,
                "another-creator",
                runA,
                CaseEvidenceReference.ItemType.BATCH,
                mismatchId
        );

        assertEquals(first.details().id(), repeated.details().id());
        assertEquals("creator", repeated.details().createdBy());
        assertEquals("analyst-one", repeated.details().assignedTo());
        assertEquals(claimed.version(), repeated.version());
        assertEquals(ExceptionCaseStatus.IN_REVIEW, repeated.details().status());
        assertEquals(1L, count("exception_case"));
    }

    @Test
    void rejectsMatchedEvidenceButAllowsOrphanEvidence() {
        ExceptionCaseOperationException failure = assertThrows(
                ExceptionCaseOperationException.class,
                () -> service.open(
                        tenantA,
                        "creator",
                        runA,
                        CaseEvidenceReference.ItemType.BATCH,
                        matchedId
                )
        );

        assertEquals("CASE_NOT_REQUIRED", failure.getCode());
        assertEquals(0L, count("exception_case"));

        StoredExceptionCase orphan = service.open(
                tenantA,
                "creator",
                runA,
                CaseEvidenceReference.ItemType.ORPHAN,
                orphanId
        );

        assertEquals(
                CaseEvidenceReference.Reason.ORPHAN_BANK_CREDIT,
                orphan.details().evidence().reason()
        );
    }

    @Test
    void foreignTenantCannotOpenReadClaimOrAnnotateCase() {
        ExceptionCaseOperationException evidenceFailure = assertThrows(
                ExceptionCaseOperationException.class,
                () -> service.open(
                        tenantB,
                        "foreign-analyst",
                        runA,
                        CaseEvidenceReference.ItemType.BATCH,
                        mismatchId
                )
        );

        assertEquals(
                "CASE_EVIDENCE_NOT_FOUND",
                evidenceFailure.getCode()
        );

        StoredExceptionCase opened = openMismatch();
        UUID caseId = opened.details().id();

        assertEquals(
                "CASE_NOT_FOUND",
                assertThrows(
                        ExceptionCaseOperationException.class,
                        () -> service.get(tenantB, caseId)
                ).getCode()
        );

        assertEquals(
                "CASE_NOT_FOUND",
                assertThrows(
                        ExceptionCaseOperationException.class,
                        () -> service.claim(
                                tenantB, caseId, 0, "foreign-analyst"
                        )
                ).getCode()
        );

        assertEquals(
                "CASE_NOT_FOUND",
                assertThrows(
                        ExceptionCaseOperationException.class,
                        () -> service.addNote(
                                tenantB, caseId, 0,
                                "foreign-analyst", "Unauthorized note"
                        )
                ).getCode()
        );

        StoredExceptionCase ownCase = service.open(
                tenantB,
                "foreign-analyst",
                runB,
                CaseEvidenceReference.ItemType.BATCH,
                mismatchId
        );

        assertNotEquals(caseId, ownCase.details().id());
    }

    @Test
    void claimIncrementsVersionAndRejectsStaleClaim() {
        StoredExceptionCase opened = openMismatch();

        StoredExceptionCase claimed = service.claim(
                tenantA,
                opened.details().id(),
                opened.version(),
                "analyst-one"
        );

        assertEquals(1L, claimed.version());
        assertEquals("analyst-one", claimed.details().assignedTo());

        ExceptionCaseOperationException failure = assertThrows(
                ExceptionCaseOperationException.class,
                () -> service.claim(
                        tenantA,
                        opened.details().id(),
                        opened.version(),
                        "analyst-two"
                )
        );

        assertEquals("STALE_CASE_VERSION", failure.getCode());
        assertEquals(
                claimed,
                service.get(tenantA, opened.details().id())
        );
    }

    @Test
    void noteAndCaseVersionArePersistedTogether() {
        StoredExceptionCase claimed = claimCase();

        CaseNoteResult result = service.addNote(
                tenantA,
                claimed.details().id(),
                claimed.version(),
                "analyst-one",
                "  Requested settlement advice.  "
        );

        assertEquals(2L, result.exceptionCase().version());
        assertEquals(
                "Requested settlement advice.",
                result.note().text()
        );
        assertEquals(1L, count("investigation_note"));

        String author = jdbc.queryForObject(
                """
                SELECT author_subject
                FROM investigation_note
                WHERE tenant_id = ? AND id = ?
                """,
                String.class,
                tenantA.toString(),
                result.note().id().toString()
        );

        assertEquals("analyst-one", author);
        assertEquals(
                result.exceptionCase(),
                service.get(tenantA, claimed.details().id())
        );

        ExceptionCaseOperationException stale = assertThrows(
                ExceptionCaseOperationException.class,
                () -> service.addNote(
                        tenantA,
                        claimed.details().id(),
                        claimed.version(),
                        "analyst-one",
                        "Retry using a stale version."
                )
        );

        assertEquals("STALE_CASE_VERSION", stale.getCode());
        assertEquals(1L, count("investigation_note"));
    }

    @Test
    void rejectsUnassignedAndInvalidNotesWithoutUpdatingCase() {
        StoredExceptionCase claimed = claimCase();
        UUID caseId = claimed.details().id();

        assertEquals(
                "CASE_NOT_ASSIGNED_TO_ACTOR",
                assertThrows(
                        ExceptionCaseOperationException.class,
                        () -> service.addNote(
                                tenantA, caseId, claimed.version(),
                                "analyst-two", "Not my investigation."
                        )
                ).getCode()
        );

        assertEquals(
                "INVALID_CASE_NOTE",
                assertThrows(
                        ExceptionCaseOperationException.class,
                        () -> service.addNote(
                                tenantA, caseId, claimed.version(),
                                "analyst-one", "   "
                        )
                ).getCode()
        );

        assertEquals(0L, count("investigation_note"));
        assertEquals(
                claimed.version(),
                service.get(tenantA, caseId).version()
        );
    }

    @Test
    void noteInsertionFailureRollsBackNoteAndVersionUpdate() {
        StoredExceptionCase claimed = claimCase();

        doAnswer(invocation -> {
            invocation.callRealMethod();

            assertEquals(1L, count("investigation_note"));

            throw new DataIntegrityViolationException(
                    "Simulated failure after note insertion."
            );
        }).when(repositorySpy).insertNote(
                any(InvestigationNote.class)
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> service.addNote(
                        tenantA,
                        claimed.details().id(),
                        claimed.version(),
                        "analyst-one",
                        "This transaction must roll back."
                )
        );

        assertEquals(0L, count("investigation_note"));
        assertEquals(
                claimed,
                service.get(tenantA, claimed.details().id())
        );
    }

    @Test
    void concurrentClaimsAllowOnlyOneSuccessfulVersionUpdate()
            throws Exception {
        StoredExceptionCase opened = openMismatch();
        CyclicBarrier bothReadyToWrite = new CyclicBarrier(2);

        /*
         * Both transactions must complete their reads and domain checks
         * before either reaches the UPDATE.
         */
        doAnswer(invocation -> {
            bothReadyToWrite.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(repositorySpy).update(
                any(ExceptionCase.class),
                anyLong()
        );

        ExecutorService workers = Executors.newFixedThreadPool(2);

        try {
            Future<StoredExceptionCase> first = workers.submit(
                    () -> service.claim(
                            tenantA,
                            opened.details().id(),
                            opened.version(),
                            "analyst-one"
                    )
            );

            Future<StoredExceptionCase> second = workers.submit(
                    () -> service.claim(
                            tenantA,
                            opened.details().id(),
                            opened.version(),
                            "analyst-two"
                    )
            );

            int successes = 0;
            int conflicts = 0;
            String winner = null;

            for (Future<StoredExceptionCase> future : List.of(first, second)) {
                try {
                    StoredExceptionCase claimed =
                            future.get(20, TimeUnit.SECONDS);

                    successes++;
                    winner = claimed.details().assignedTo();
                } catch (ExecutionException exception) {
                    ExceptionCaseOperationException conflict =
                            assertInstanceOf(
                                    ExceptionCaseOperationException.class,
                                    exception.getCause()
                            );

                    assertEquals(
                            "STALE_CASE_VERSION",
                            conflict.getCode()
                    );
                    conflicts++;
                }
            }

            assertEquals(1, successes);
            assertEquals(1, conflicts);

            StoredExceptionCase stored = service.get(
                    tenantA,
                    opened.details().id()
            );

            assertEquals(1L, stored.version());
            assertEquals(winner, stored.details().assignedTo());
            assertEquals(
                    ExceptionCaseStatus.IN_REVIEW,
                    stored.details().status()
            );
        } finally {
            workers.shutdownNow();

            assertTrue(
                    workers.awaitTermination(10, TimeUnit.SECONDS)
            );
        }
    }

    private StoredExceptionCase openMismatch() {
        return service.open(
                tenantA,
                "creator",
                runA,
                CaseEvidenceReference.ItemType.BATCH,
                mismatchId
        );
    }

    private StoredExceptionCase claimCase() {
        StoredExceptionCase opened = openMismatch();

        return service.claim(
                tenantA,
                opened.details().id(),
                opened.version(),
                "analyst-one"
        );
    }

    private UUID seedRun(UUID tenantId) {
        UUID settlementImport = imported(
                tenantId,
                "PROCESSOR_SETTLEMENT"
        );
        UUID bankImport = imported(tenantId, "BANK_STATEMENT");

        BankReconciliationPreview report =
                new BankReconciliationPreview(
                        settlementImport,
                        bankImport,
                        2L,
                        3L,
                        1L,
                        1L,
                        1L,
                        false,
                        List.of(
                                new BankReconciliationPreview.BatchResult(
                                        mismatchId,
                                        "PROCESSOR_A",
                                        "BANK-001",
                                        "SET-MISMATCH",
                                        MatchOutcome.AMOUNT_MISMATCH,
                                        List.of(UUID.randomUUID()),
                                        100L,
                                        "INR"
                                ),
                                new BankReconciliationPreview.BatchResult(
                                        matchedId,
                                        "PROCESSOR_A",
                                        "BANK-001",
                                        "SET-MATCHED",
                                        MatchOutcome.MATCHED,
                                        List.of(UUID.randomUUID()),
                                        0L,
                                        "INR"
                                )
                        ),
                        List.of(
                                new BankReconciliationPreview.OrphanCredit(
                                        orphanId,
                                        "PROCESSOR_A",
                                        "BANK-001",
                                        "SET-ORPHAN",
                                        500L,
                                        "INR"
                                )
                        )
                );

        StoredReconciliationRun run = new StoredReconciliationRun(
                UUID.randomUUID(),
                "EXACT_SETTLEMENT_BANK_V1",
                1,
                "fixture-creator",
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                report
        );

        /*
         * These tests exercise case persistence from an already saved
         * report. Run creation and matching have their own test suites.
         */
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status -> runRepository.save(tenantId, run)
                );

        return run.id();
    }

    private UUID imported(UUID tenantId, String source) {
        UUID id = UUID.randomUUID();

        String hash = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");

        jdbc.update(
                """
                INSERT INTO import_batch (
                    id, tenant_id, source_type, source_system,
                    file_name, file_uri, file_hash, status,
                    business_date, row_count, version
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id.toString(),
                tenantId.toString(),
                source,
                "CASE_TEST_SOURCE",
                "case-fixture.csv",
                "test://" + id,
                hash,
                "READY",
                java.sql.Date.valueOf("2026-10-29"),
                source.equals("BANK_STATEMENT") ? 3L : 2L,
                2L
        );

        return id;
    }

    private long count(String table) {
        // Only fixed table names from this class are supplied.
        Long result = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE tenant_id = ?",
                Long.class,
                tenantA.toString()
        );

        return result == null ? 0L : result;
    }
}