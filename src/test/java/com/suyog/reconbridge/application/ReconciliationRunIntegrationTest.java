package com.suyog.reconbridge.application;

import com.suyog.reconbridge.application.exception.RunIdempotencyException;
import com.suyog.reconbridge.application.model.RunCreationResult;
import com.suyog.reconbridge.application.model.StoredReconciliationRun;
import com.suyog.reconbridge.persistence.repository.ReconciliationRunRepository;
import com.suyog.reconbridge.persistence.repository.ReconciliationRunRequestRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReconciliationRunIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private IdempotentReconciliationRunService creationService;

    @MockitoBean
    private JwtDecoder decoder;

    @MockitoSpyBean
    private ReconciliationRunRepository repository;

    @MockitoSpyBean
    private ReconciliationRunRequestRepository requestRepository;

    private ReconciliationRunRepository repositorySpy;
    private ReconciliationRunRequestRepository requestSpy;

    private UUID tenantA;
    private UUID tenantB;
    private UUID settlementId;
    private UUID bankId;
    private UUID foreignSettlementId;
    private UUID foreignBankId;

    @BeforeEach
    void setUp() {
        repositorySpy = AopTestUtils.getUltimateTargetObject(repository);
        requestSpy = AopTestUtils.getUltimateTargetObject(requestRepository);

        reset(repositorySpy, requestSpy);

        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();

        jdbc.update(
                "INSERT INTO tenant (id, name) VALUES (?, ?)",
                tenantA.toString(),
                "Run tenant A"
        );

        jdbc.update(
                "INSERT INTO tenant (id, name) VALUES (?, ?)",
                tenantB.toString(),
                "Run tenant B"
        );

        settlementId = imported(tenantA, "PROCESSOR_SETTLEMENT");
        bankId = imported(tenantA, "BANK_STATEMENT");

        foreignSettlementId = imported(
                tenantB,
                "PROCESSOR_SETTLEMENT"
        );
        foreignBankId = imported(tenantB, "BANK_STATEMENT");

        evidence(tenantA, settlementId, bankId);
        evidence(tenantB, foreignSettlementId, foreignBankId);

        token("analyst", tenantA, "ANALYST", "analyst-subject");
        token("analyst-two", tenantA, "ANALYST", "second-analyst");
        token("importer", tenantA, "IMPORTER", "importer-subject");
        token("auditor", tenantA, "AUDITOR", "auditor-subject");
        token("foreign", tenantB, "ANALYST", "foreign-subject");
    }

    @AfterEach
    void cleanUp() {
        reset(repositorySpy, requestSpy);

        for (String table : List.of(
                "reconciliation_run_request",
                "reconciliation_run_item",
                "reconciliation_run",
                "bank_credit",
                "settlement_line",
                "settlement_batch",
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
    void createsAndRetrievesStoredSummaryAndIndividualResults()
            throws Exception {
        String response = create(
                "analyst",
                "create-001",
                settlementId,
                bankId
        )
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Idempotency-Replayed",
                        "false"
                ))
                .andExpect(jsonPath("$.createdBy")
                        .value("analyst-subject"))
                .andExpect(jsonPath("$.ruleVersion")
                        .value("EXACT_SETTLEMENT_BANK_V1"))
                .andExpect(jsonPath("$.reportVersion").value(1))
                .andExpect(jsonPath("$.report.matchedBatchCount")
                        .value(1))
                .andExpect(jsonPath("$.report.orphanBankCreditCount")
                        .value(1))
                .andReturn().getResponse().getContentAsString();

        String runId = json.readTree(response).get("id").asText();

        mvc.perform(
                        get("/api/reconciliation/runs/{id}", runId)
                                .header(
                                        "Authorization",
                                        "Bearer auditor"
                                )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(runId))
                .andExpect(jsonPath("$.createdBy")
                        .value("analyst-subject"))
                .andExpect(jsonPath("$.report.results[0].outcome")
                        .value("MATCHED"))
                .andExpect(jsonPath(
                        "$.report.orphanBankCredits[0].amountMinor"
                ).value(500));

        assertEquals(1L, count("reconciliation_run"));
        assertEquals(2L, count("reconciliation_run_item"));
        assertEquals(1L, count("reconciliation_run_request"));
    }

    @Test
    void importerCannotCreateRun() throws Exception {
        create("importer", "forbidden-001", settlementId, bankId)
                .andExpect(status().isForbidden());

        assertEquals(0L, count("reconciliation_run"));
        assertEquals(0L, count("reconciliation_run_request"));
    }

    @Test
    void foreignTenantCannotReadStoredRun() throws Exception {
        RunCreationResult result = createDirect(
                "foreign-read-001",
                settlementId,
                bankId
        );

        mvc.perform(
                        get(
                                "/api/reconciliation/runs/{id}",
                                result.run().id()
                        ).header("Authorization", "Bearer foreign")
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
    }

    @Test
    void mixedTenantImportsCannotCreateRun() throws Exception {
        create(
                "analyst",
                "mixed-001",
                settlementId,
                foreignBankId
        )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("IMPORT_NOT_FOUND"));

        assertEquals(0L, count("reconciliation_run"));
        assertEquals(0L, count("reconciliation_run_item"));
        assertEquals(0L, count("reconciliation_run_request"));
    }

    @Test
    void missingTokenCannotCreateRun() throws Exception {
        mvc.perform(
                        post("/api/reconciliation/runs")
                                .header(
                                        "Idempotency-Key",
                                        "unauthenticated-001"
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(settlementId, bankId))
                )
                .andExpect(status().isUnauthorized());

        assertEquals(0L, count("reconciliation_run_request"));
    }

    @Test
    void missingRequiredImportIdReturnsBadRequest() throws Exception {
        mvc.perform(
                        post("/api/reconciliation/runs")
                                .header(
                                        "Authorization",
                                        "Bearer analyst"
                                )
                                .header("Idempotency-Key", "invalid-body")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_RUN_REQUEST"));
    }

    @Test
    void failureRollsBackClaimRunAndItemsAndAllowsRetry() {
        doAnswer(invocation -> {
            invocation.callRealMethod();

            assertEquals(1L, count("reconciliation_run"));
            assertEquals(2L, count("reconciliation_run_item"));
            assertEquals(1L, count("reconciliation_run_request"));

            throw new DataIntegrityViolationException(
                    "Simulated failure after writing run results"
            );
        }).when(repositorySpy).save(
                any(UUID.class),
                any(StoredReconciliationRun.class)
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> createDirect(
                        "rollback-001",
                        settlementId,
                        bankId
                )
        );

        assertEquals(0L, count("reconciliation_run"));
        assertEquals(0L, count("reconciliation_run_item"));
        assertEquals(0L, count("reconciliation_run_request"));

        reset(repositorySpy);

        RunCreationResult retry = createDirect(
                "rollback-001",
                settlementId,
                bankId
        );

        assertTrue(retry.created());
        assertEquals(1L, count("reconciliation_run"));
        assertEquals(2L, count("reconciliation_run_item"));
        assertEquals(1L, count("reconciliation_run_request"));
    }

    @Test
    void identicalRetryReturnsOriginalRunAndCreator() throws Exception {
        String first = create(
                "analyst",
                "replay-001",
                settlementId,
                bankId
        )
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String originalId = json.readTree(first).get("id").asText();
        String originalTime = json.readTree(first)
                .get("createdAt").asText();

        create("analyst-two", "replay-001", settlementId, bankId)
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Idempotency-Replayed",
                        "true"
                ))
                .andExpect(jsonPath("$.id").value(originalId))
                .andExpect(jsonPath("$.createdAt").value(originalTime))
                .andExpect(jsonPath("$.createdBy")
                        .value("analyst-subject"));

        assertEquals(1L, count("reconciliation_run"));
        assertEquals(2L, count("reconciliation_run_item"));
        assertEquals(1L, count("reconciliation_run_request"));
    }

    @Test
    void sameKeyWithDifferentInputsReturnsConflict() throws Exception {
        create("analyst", "conflict-001", settlementId, bankId)
                .andExpect(status().isCreated());

        create(
                "analyst",
                "conflict-001",
                settlementId,
                UUID.randomUUID()
        )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("IDEMPOTENCY_KEY_CONFLICT"));

        assertEquals(1L, count("reconciliation_run"));
        assertEquals(1L, count("reconciliation_run_request"));
    }

    @Test
    void missingKeyReturnsBadRequest() throws Exception {
        mvc.perform(
                        post("/api/reconciliation/runs")
                                .header(
                                        "Authorization",
                                        "Bearer analyst"
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(settlementId, bankId))
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_IDEMPOTENCY_KEY"));

        assertEquals(0L, count("reconciliation_run"));
    }

    @Test
    void malformedKeysReturnBadRequest() throws Exception {
        for (String key : List.of(
                "",
                "contains space",
                " key",
                "key ",
                "bad/key",
                "x".repeat(129)
        )) {
            create("analyst", key, settlementId, bankId)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code")
                            .value("INVALID_IDEMPOTENCY_KEY"));
        }

        assertEquals(0L, count("reconciliation_run"));
        assertEquals(0L, count("reconciliation_run_request"));
    }

    @Test
    void differentKeysAllowIntentionalSeparateRuns() {
        RunCreationResult first = createDirect(
                "separate-001",
                settlementId,
                bankId
        );
        RunCreationResult second = createDirect(
                "separate-002",
                settlementId,
                bankId
        );

        assertTrue(first.created());
        assertTrue(second.created());
        assertNotEquals(first.run().id(), second.run().id());
        assertEquals(2L, count("reconciliation_run"));
        assertEquals(2L, count("reconciliation_run_request"));
    }

    @Test
    void sameKeyIsIndependentAcrossTenants() {
        RunCreationResult first = createDirect(
                "tenant-shared-key",
                settlementId,
                bankId
        );

        RunCreationResult second = creationService.create(
                tenantB,
                "foreign-subject",
                "tenant-shared-key",
                foreignSettlementId,
                foreignBankId
        );

        assertTrue(first.created());
        assertTrue(second.created());
        assertNotEquals(first.run().id(), second.run().id());

        assertEquals(1L, count("reconciliation_run", tenantA));
        assertEquals(1L, count("reconciliation_run", tenantB));
        assertEquals(1L, count("reconciliation_run_request", tenantA));
        assertEquals(1L, count("reconciliation_run_request", tenantB));
    }

    @Test
    void concurrentIdenticalRequestsCreateOnlyOneRun() throws Exception {
        exerciseConcurrentRequests(false);
    }

    @Test
    void concurrentConflictingRequestCannotCreateAnotherRun()
            throws Exception {
        exerciseConcurrentRequests(true);
    }

    private void exerciseConcurrentRequests(boolean conflicting)
            throws Exception {
        CountDownLatch firstHasWritten = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEnteredClaim = new CountDownLatch(1);
        AtomicInteger claimCalls = new AtomicInteger();

        doAnswer(invocation -> {
            if (claimCalls.incrementAndGet() == 2) {
                secondEnteredClaim.countDown();
            }

            return invocation.callRealMethod();
        }).when(requestSpy).acquireAndLock(
                any(UUID.class),
                anyString(),
                anyString()
        );

        doAnswer(invocation -> {
            invocation.callRealMethod();
            firstHasWritten.countDown();

            if (!releaseFirst.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Timed out waiting to release the first transaction."
                );
            }

            return null;
        }).when(repositorySpy).save(
                any(UUID.class),
                any(StoredReconciliationRun.class)
        );

        ExecutorService workers = Executors.newFixedThreadPool(2);

        try {
            Future<RunCreationResult> first = workers.submit(
                    () -> createDirect(
                            "concurrent-001",
                            settlementId,
                            bankId
                    )
            );

            assertTrue(
                    firstHasWritten.await(10, TimeUnit.SECONDS),
                    "The first transaction did not reach its held write."
            );

            UUID secondBank = conflicting
                    ? UUID.randomUUID()
                    : bankId;

            Future<RunCreationResult> second = workers.submit(
                    () -> createDirect(
                            "concurrent-001",
                            settlementId,
                            secondBank
                    )
            );

            assertTrue(
                    secondEnteredClaim.await(10, TimeUnit.SECONDS),
                    "The second request did not attempt its claim."
            );

            /*
             * The first transaction still holds the claim.
             * The second request must not finish independently.
             */
            assertThrows(
                    TimeoutException.class,
                    () -> second.get(250, TimeUnit.MILLISECONDS)
            );

            releaseFirst.countDown();

            RunCreationResult firstResult =
                    first.get(10, TimeUnit.SECONDS);

            assertTrue(firstResult.created());

            if (conflicting) {
                ExecutionException failure = assertThrows(
                        ExecutionException.class,
                        () -> second.get(10, TimeUnit.SECONDS)
                );

                RunIdempotencyException cause = assertInstanceOf(
                        RunIdempotencyException.class,
                        failure.getCause()
                );

                assertEquals(
                        "IDEMPOTENCY_KEY_CONFLICT",
                        cause.getCode()
                );
            } else {
                RunCreationResult replay =
                        second.get(10, TimeUnit.SECONDS);

                assertFalse(replay.created());
                assertEquals(
                        firstResult.run().id(),
                        replay.run().id()
                );
            }

            assertEquals(1L, count("reconciliation_run"));
            assertEquals(2L, count("reconciliation_run_item"));
            assertEquals(1L, count("reconciliation_run_request"));
        } finally {
            releaseFirst.countDown();
            workers.shutdownNow();

            assertTrue(
                    workers.awaitTermination(10, TimeUnit.SECONDS),
                    "Worker transactions did not terminate."
            );
        }
    }

    private RunCreationResult createDirect(
            String key,
            UUID settlement,
            UUID bank
    ) {
        return creationService.create(
                tenantA,
                "analyst-subject",
                key,
                settlement,
                bank
        );
    }

    private ResultActions create(
            String token,
            String key,
            UUID settlement,
            UUID bank
    ) throws Exception {
        return mvc.perform(
                post("/api/reconciliation/runs")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(settlement, bank))
        );
    }

    private String body(UUID settlement, UUID bank) {
        return """
                {
                  "settlementImportId": "%s",
                  "bankImportId": "%s"
                }
                """.formatted(settlement, bank);
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
                "TEST_SOURCE",
                "run-fixture.csv",
                "test://" + id,
                hash,
                "READY",
                java.sql.Date.valueOf("2026-10-28"),
                source.equals("BANK_STATEMENT") ? 2L : 1L,
                2L
        );

        return id;
    }

    private void evidence(
            UUID tenant,
            UUID settlement,
            UUID bank
    ) {
        UUID batchId = UUID.randomUUID();

        jdbc.update(
                """
                INSERT INTO settlement_batch (
                    tenant_id, import_id, batch_id, provider,
                    settlement_reference, bank_account_reference,
                    declared_net_minor, currency
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                tenant.toString(),
                settlement.toString(),
                batchId.toString(),
                "PROCESSOR_A",
                "SET-001",
                "BANK-001",
                990L,
                "INR"
        );

        jdbc.update(
                """
                INSERT INTO settlement_line (
                    tenant_id, import_id, line_id, batch_id,
                    payment_reference, gross_minor, fee_minor, net_minor
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                tenant.toString(),
                settlement.toString(),
                UUID.randomUUID().toString(),
                batchId.toString(),
                "PAY-001",
                1000L,
                10L,
                990L
        );

        addCredit(tenant, bank, "SET-001", 990L);
        addCredit(tenant, bank, "ORPHAN-001", 500L);
    }

    private void addCredit(
            UUID tenant,
            UUID bank,
            String reference,
            long amount
    ) {
        jdbc.update(
                """
                INSERT INTO bank_credit (
                    tenant_id, import_id, credit_id, provider,
                    bank_account_reference, settlement_reference,
                    amount_minor, currency
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                tenant.toString(),
                bank.toString(),
                UUID.randomUUID().toString(),
                "PROCESSOR_A",
                "BANK-001",
                reference,
                amount,
                "INR"
        );
    }

    private long count(String table) {
        return count(table, tenantA);
    }

    private long count(String table, UUID tenant) {
        // Table names come only from fixed strings in this test class.
        Long value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE tenant_id = ?",
                Long.class,
                tenant.toString()
        );

        return value == null ? 0L : value;
    }

    private void token(
            String value,
            UUID tenantId,
            String role,
            String subject
    ) {
        Instant now = Instant.now();

        when(decoder.decode(value)).thenReturn(
                Jwt.withTokenValue(value)
                        .header("alg", "RS256")
                        .subject(subject)
                        .issuer(
                                "http://localhost:8081/realms/reconbridge"
                        )
                        .audience(List.of("reconbridge-api"))
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(300))
                        .claim("tenant_id", tenantId.toString())
                        .claim(
                                "realm_access",
                                Map.of("roles", List.of(role))
                        )
                        .build()
        );
    }
}