package com.suyog.reconbridge.api;

import com.suyog.reconbridge.application.ExceptionCaseService;
import com.suyog.reconbridge.application.model.BankReconciliationPreview;
import com.suyog.reconbridge.application.model.StoredExceptionCase;
import com.suyog.reconbridge.application.model.StoredReconciliationRun;
import com.suyog.reconbridge.domain.model.CaseEvidenceReference;
import com.suyog.reconbridge.domain.model.MatchOutcome;
import com.suyog.reconbridge.persistence.repository.ReconciliationRunRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExceptionCaseControllerIntegrationTest {

    private static final String ROOT = "/api/exception-cases";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ExceptionCaseService cases;

    @Autowired
    private ReconciliationRunRepository runs;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private JwtDecoder decoder;

    private UUID tenantA;
    private UUID tenantB;
    private UUID runId;
    private UUID mismatchId;
    private UUID matchedId;

    @BeforeEach
    void setUp() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        mismatchId = UUID.randomUUID();
        matchedId = UUID.randomUUID();

        jdbc.update(
                "INSERT INTO tenant (id, name) VALUES (?, ?)",
                tenantA.toString(),
                "Case API tenant A"
        );

        jdbc.update(
                "INSERT INTO tenant (id, name) VALUES (?, ?)",
                tenantB.toString(),
                "Case API tenant B"
        );

        runId = seedRun();

        token("analyst", tenantA, "ANALYST", "analyst-one");
        token("analyst-two", tenantA, "ANALYST", "analyst-two");
        token("auditor", tenantA, "AUDITOR", "auditor-one");
        token("importer", tenantA, "IMPORTER", "importer-one");
        token("approver", tenantA, "APPROVER", "approver-one");
        token("foreign", tenantB, "ANALYST", "foreign-analyst");
        token("no-role", tenantA, "UNRELATED", "unrelated-user");
    }

    @AfterEach
    void cleanUp() {
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
    void analystCanOpenRetrieveClaimAndAnnotateCase() throws Exception {
        String opened = send(
                ROOT,
                "analyst",
                openBody(mismatchId)
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.details.status").value("OPEN"))
                .andExpect(jsonPath("$.details.createdBy")
                        .value("analyst-one"))
                .andExpect(jsonPath("$.details.tenantId")
                        .value(tenantA.toString()))
                .andExpect(jsonPath("$.details.evidence.reason")
                        .value("AMOUNT_MISMATCH"))
                .andReturn().getResponse().getContentAsString();

        String caseId = json.readTree(opened)
                .get("details").get("id").asText();

        read(caseId, "auditor")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.details.id").value(caseId));

        send(
                ROOT + "/" + caseId + "/claim",
                "analyst",
                versionBody(0)
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.details.status")
                        .value("IN_REVIEW"))
                .andExpect(jsonPath("$.details.assignedTo")
                        .value("analyst-one"));

        send(
                ROOT + "/" + caseId + "/notes",
                "analyst",
                noteBody(1, "Requested processor settlement advice.")
        )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.exceptionCase.version").value(2))
                .andExpect(jsonPath("$.note.caseId").value(caseId))
                .andExpect(jsonPath("$.note.authorSubject")
                        .value("analyst-one"))
                .andExpect(jsonPath("$.note.text")
                        .value("Requested processor settlement advice."));

        read(caseId, "auditor")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        assertEquals(1L, count("exception_case"));
        assertEquals(1L, count("investigation_note"));
    }

    @Test
    void repeatedOpenReturnsExistingCaseAndOriginalCreator()
            throws Exception {
        StoredExceptionCase existing = openDirect();

        send(ROOT, "analyst-two", openBody(mismatchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.details.id")
                        .value(existing.details().id().toString()))
                .andExpect(jsonPath("$.details.createdBy")
                        .value("analyst-one"));

        assertEquals(1L, count("exception_case"));
    }

    @Test
    void allApplicationReadRolesCanRetrieveCase() throws Exception {
        UUID caseId = openDirect().details().id();

        for (String token : List.of(
                "analyst", "auditor", "importer", "approver"
        )) {
            read(caseId.toString(), token)
                    .andExpect(status().isOk());
        }
    }

    @Test
    void readOnlyRolesCannotPerformCaseWrites() throws Exception {
        UUID caseId = openDirect().details().id();

        for (String token : List.of(
                "auditor", "importer", "approver"
        )) {
            send(ROOT, token, openBody(mismatchId))
                    .andExpect(status().isForbidden());

            send(
                    ROOT + "/" + caseId + "/claim",
                    token,
                    versionBody(0)
            ).andExpect(status().isForbidden());

            send(
                    ROOT + "/" + caseId + "/notes",
                    token,
                    noteBody(0, "Not permitted.")
            ).andExpect(status().isForbidden());
        }

        assertEquals(1L, count("exception_case"));
        assertEquals(0L, count("investigation_note"));
    }

    @Test
    void requestsWithoutTokenReturnUnauthorized() throws Exception {
        UUID caseId = openDirect().details().id();

        mvc.perform(
                post(ROOT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(openBody(mismatchId))
        ).andExpect(status().isUnauthorized());

        mvc.perform(get(ROOT + "/" + caseId))
                .andExpect(status().isUnauthorized());

        mvc.perform(
                post(ROOT + "/" + caseId + "/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(versionBody(0))
        ).andExpect(status().isUnauthorized());

        mvc.perform(
                post(ROOT + "/" + caseId + "/notes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(noteBody(0, "No authentication."))
        ).andExpect(status().isUnauthorized());
    }

    @Test
    void foreignTenantCannotAccessEvidenceOrCaseOperations()
            throws Exception {
        UUID caseId = openDirect().details().id();

        send(ROOT, "foreign", openBody(mismatchId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("CASE_EVIDENCE_NOT_FOUND"));

        read(caseId.toString(), "foreign")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CASE_NOT_FOUND"));

        send(
                ROOT + "/" + caseId + "/claim",
                "foreign",
                versionBody(0)
        )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CASE_NOT_FOUND"));

        send(
                ROOT + "/" + caseId + "/notes",
                "foreign",
                noteBody(0, "Foreign tenant.")
        )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CASE_NOT_FOUND"));
    }

    @Test
    void matchedResultCannotOpenCase() throws Exception {
        send(ROOT, "analyst", openBody(matchedId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASE_NOT_REQUIRED"));

        assertEquals(0L, count("exception_case"));
    }

    @Test
    void staleClaimAndNoteRequestsReturnConflict() throws Exception {
        UUID caseId = openDirect().details().id();

        cases.claim(tenantA, caseId, 0, "analyst-one");

        send(
                ROOT + "/" + caseId + "/claim",
                "analyst-two",
                versionBody(0)
        )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("STALE_CASE_VERSION"));

        send(
                ROOT + "/" + caseId + "/notes",
                "analyst",
                noteBody(0, "Stale note request.")
        )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("STALE_CASE_VERSION"));

        assertEquals(0L, count("investigation_note"));
    }

    @Test
    void anotherAnalystCannotAnnotateAssignedCase() throws Exception {
        UUID caseId = openDirect().details().id();

        cases.claim(tenantA, caseId, 0, "analyst-one");

        send(
                ROOT + "/" + caseId + "/notes",
                "analyst-two",
                noteBody(1, "Not assigned to me.")
        )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code")
                        .value("CASE_NOT_ASSIGNED_TO_ACTOR"));

        assertEquals(0L, count("investigation_note"));
    }

    @Test
    void rejectsInvalidBodiesAndIdentifiers() throws Exception {
        UUID caseId = openDirect().details().id();

        send(ROOT, "analyst", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_CASE_REQUEST"));

        send(ROOT + "/" + caseId + "/claim", "analyst", "{}")
                .andExpect(status().isBadRequest());

        send(
                ROOT + "/" + caseId + "/claim",
                "analyst",
                versionBody(-1)
        ).andExpect(status().isBadRequest());

        send(
                ROOT + "/" + caseId + "/notes",
                "analyst",
                noteBody(0, "   ")
        ).andExpect(status().isBadRequest());

        read("not-a-uuid", "analyst")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_CASE_REQUEST"));

        read(UUID.randomUUID().toString(), "analyst")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CASE_NOT_FOUND"));
    }

    @Test
    void caseMustBeClaimedBeforeAddingNotes() throws Exception {
        UUID caseId = openDirect().details().id();

        send(
                ROOT + "/" + caseId + "/notes",
                "analyst",
                noteBody(0, "Not claimed yet.")
        )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("CASE_NOT_IN_REVIEW"));
    }

    @Test
    void unrelatedRoleCannotReadOrOpenCase() throws Exception {
        UUID caseId = openDirect().details().id();

        read(caseId.toString(), "no-role")
                .andExpect(status().isForbidden());

        send(ROOT, "no-role", openBody(mismatchId))
                .andExpect(status().isForbidden());
    }

    private StoredExceptionCase openDirect() {
        return cases.open(
                tenantA,
                "analyst-one",
                runId,
                CaseEvidenceReference.ItemType.BATCH,
                mismatchId
        );
    }

    private ResultActions send(
            String path,
            String token,
            String body
    ) throws Exception {
        return mvc.perform(
                post(path)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
        );
    }

    private ResultActions read(
            String caseId,
            String token
    ) throws Exception {
        return mvc.perform(
                get(ROOT + "/" + caseId)
                        .header("Authorization", "Bearer " + token)
        );
    }

    private String openBody(UUID evidenceId) {
        return json.writeValueAsString(
                Map.of(
                        "runId", runId.toString(),
                        "itemType", "BATCH",
                        "evidenceId", evidenceId.toString()
                )
        );
    }

    private String versionBody(long version) {
        return json.writeValueAsString(
                Map.of("expectedVersion", version)
        );
    }

    private String noteBody(long version, String text) {
        return json.writeValueAsString(
                Map.of(
                        "expectedVersion", version,
                        "text", text
                )
        );
    }

    private long count(String table) {
        // Only fixed table names from this test are supplied.
        Long result = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE tenant_id = ?",
                Long.class,
                tenantA.toString()
        );

        return result == null ? 0L : result;
    }

    private UUID seedRun() {
        UUID settlement = imported("PROCESSOR_SETTLEMENT");
        UUID bank = imported("BANK_STATEMENT");

        BankReconciliationPreview report =
                new BankReconciliationPreview(
                        settlement,
                        bank,
                        2L,
                        2L,
                        1L,
                        1L,
                        0L,
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
                        List.of()
                );

        StoredReconciliationRun run = new StoredReconciliationRun(
                UUID.randomUUID(),
                "EXACT_SETTLEMENT_BANK_V1",
                1,
                "fixture-creator",
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                report
        );

        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status -> runs.save(tenantA, run)
                );

        return run.id();
    }

    private UUID imported(String source) {
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
                tenantA.toString(),
                source,
                "CASE_API_TEST",
                "case-api-fixture.csv",
                "test://" + id,
                hash,
                "READY",
                java.sql.Date.valueOf("2026-10-29"),
                2L,
                2L
        );

        return id;
    }

    private void token(
            String value,
            UUID tenant,
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
                        .claim("tenant_id", tenant.toString())
                        .claim(
                                "realm_access",
                                Map.of("roles", List.of(role))
                        )
                        .build()
        );
    }
}