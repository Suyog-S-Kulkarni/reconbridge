package com.suyog.reconbridge.api;

import com.suyog.reconbridge.application.BankCreditPersistenceService;
import com.suyog.reconbridge.application.ImportLifecycleService;
import com.suyog.reconbridge.application.InternalPaymentPersistenceService;
import com.suyog.reconbridge.application.SettlementPersistenceService;
import com.suyog.reconbridge.application.model.ImportBatchSnapshot;
import com.suyog.reconbridge.domain.model.BankCredit;
import com.suyog.reconbridge.domain.model.MatchKey;
import com.suyog.reconbridge.domain.model.Money;
import com.suyog.reconbridge.domain.model.PaymentRecord;
import com.suyog.reconbridge.domain.model.SettlementBatch;
import com.suyog.reconbridge.domain.model.SettlementKey;
import com.suyog.reconbridge.domain.model.SettlementLine;
import com.suyog.reconbridge.domain.model.SourceType;
import com.suyog.reconbridge.persistence.entity.ImportBatchEntity;
import com.suyog.reconbridge.persistence.entity.TenantEntity;
import com.suyog.reconbridge.persistence.repository.ImportBatchRepository;
import com.suyog.reconbridge.persistence.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ThreeSourcePreviewIntegrationTest {

    private static final String URL =
            "/api/reconciliation/three-source-preview";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TenantRepository tenants;

    @Autowired
    private ImportBatchRepository imports;

    @Autowired
    private ImportLifecycleService lifecycle;

    @Autowired
    private InternalPaymentPersistenceService paymentPersistence;

    @Autowired
    private SettlementPersistenceService settlementPersistence;

    @Autowired
    private BankCreditPersistenceService bankPersistence;

    @MockitoBean
    private JwtDecoder decoder;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void setUp() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();

        tenants.saveAndFlush(new TenantEntity(tenantA, "Three-source A"));
        tenants.saveAndFlush(new TenantEntity(tenantB, "Three-source B"));

        for (String role : List.of(
                "IMPORTER", "ANALYST", "APPROVER", "AUDITOR"
        )) {
            token(role, tenantA, List.of(role));
        }

        token("tenant-b", tenantB, List.of("AUDITOR"));
        token("no-role", tenantA, List.of());
    }

    @AfterEach
    void cleanUp() {
        if (tenantA == null || tenantB == null) {
            return;
        }

        for (String table : List.of(
                "internal_payment",
                "settlement_line",
                "settlement_batch",
                "bank_credit",
                "import_batch",
                "tenant"
        )) {
            String column = table.equals("tenant") ? "id" : "tenant_id";

            jdbc.update(
                    "DELETE FROM " + table
                            + " WHERE " + column + " IN (?, ?)",
                    tenantA.toString(),
                    tenantB.toString()
            );
        }
    }

    @Test
    void allReadRolesCanPreviewFullyMatchedEvidence() throws Exception {
        Selection selection = fixture(tenantA, "MATCHED");

        for (String role : List.of(
                "IMPORTER", "ANALYST", "APPROVER", "AUDITOR"
        )) {
            preview(role, selection)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paymentCount").value(1))
                    .andExpect(jsonPath("$.settlementLineCount").value(1))
                    .andExpect(jsonPath("$.settlementBatchCount").value(1))
                    .andExpect(jsonPath("$.bankCreditCount").value(1))
                    .andExpect(jsonPath("$.paymentOutcomeCounts.MATCHED").value(1))
                    .andExpect(jsonPath("$.bankOutcomeCounts.MATCHED").value(1))
                    .andExpect(jsonPath("$.fullyReconciled").value(true));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "AMOUNT_MISMATCH",
            "CURRENCY_MISMATCH",
            "MISSING_COUNTERPART",
            "AMBIGUOUS"
    })
    void preservesPaymentMatchingOutcomes(String outcome) throws Exception {
        Selection selection = fixture(tenantA, outcome);

        preview("ANALYST", selection)
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.paymentResults[0].outcome").value(outcome)
                )
                .andExpect(jsonPath("$.unresolvedPaymentCount").value(1))
                .andExpect(jsonPath("$.bankOutcomeCounts.MATCHED").value(1))
                .andExpect(jsonPath("$.fullyReconciled").value(false));
    }

    @Test
    void includesBothOrphanTypesAndBankMismatch() throws Exception {
        Selection selection = fixture(tenantA, "ORPHANS");

        preview("AUDITOR", selection)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentOutcomeCounts.MATCHED").value(1))
                .andExpect(jsonPath("$.orphanSettlementLineCount").value(1))
                .andExpect(jsonPath("$.orphanBankCreditCount").value(1))
                .andExpect(jsonPath("$.unresolvedBatchCount").value(1))
                .andExpect(
                        jsonPath("$.bankResults[0].outcome")
                                .value("AMOUNT_MISMATCH")
                )
                .andExpect(jsonPath("$.bankResults[0].differenceMinor").value(1))
                .andExpect(jsonPath("$.fullyReconciled").value(false));
    }

    @Test
    void rejectsForeignImportsInEverySelectionPosition() throws Exception {
        Selection a = fixture(tenantA, "MATCHED");
        Selection b = fixture(tenantB, "MATCHED");

        for (Selection mixed : List.of(
                new Selection(b.payment(), a.settlement(), a.bank()),
                new Selection(a.payment(), b.settlement(), a.bank()),
                new Selection(a.payment(), a.settlement(), b.bank())
        )) {
            preview("AUDITOR", mixed)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("IMPORT_NOT_FOUND"));
        }

        preview("tenant-b", a).andExpect(status().isNotFound());
        preview("tenant-b", b).andExpect(status().isOk());
    }

    @Test
    void rejectsSwappedSourceTypes() throws Exception {
        Selection a = fixture(tenantA, "MATCHED");

        preview(
                "AUDITOR",
                new Selection(a.settlement(), a.payment(), a.bank())
        )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_IMPORT_SOURCE"));
    }

    @Test
    void rejectsNonReadyImport() throws Exception {
        Selection a = fixture(tenantA, "MATCHED");
        ImportBatchSnapshot processing = processing(
                tenantA,
                SourceType.INTERNAL_PAYMENT
        );

        preview(
                "AUDITOR",
                new Selection(processing.id(), a.settlement(), a.bank())
        )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMPORT_NOT_READY"));
    }

    @Test
    void rejectsMissingEvidence() throws Exception {
        Selection a = fixture(tenantA, "MATCHED");

        jdbc.update(
                """
                DELETE FROM internal_payment
                WHERE tenant_id = ? AND import_id = ?
                """,
                tenantA.toString(),
                a.payment().toString()
        );

        preview("AUDITOR", a)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVIDENCE_MISSING"));
    }

    @Test
    void rejectsInconsistentRecordedCount() throws Exception {
        Selection a = fixture(tenantA, "MATCHED");

        jdbc.update(
                """
                UPDATE import_batch SET row_count = 2
                WHERE tenant_id = ? AND id = ?
                """,
                tenantA.toString(),
                a.payment().toString()
        );

        preview("AUDITOR", a)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVIDENCE_INCONSISTENT"));
    }

    @Test
    void validatesRequiredUuidParameters() throws Exception {
        mvc.perform(
                        get(URL).header("Authorization", "Bearer AUDITOR")
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PREVIEW_REQUEST"));

        mvc.perform(
                        get(URL)
                                .header("Authorization", "Bearer AUDITOR")
                                .param("paymentImportId", "invalid")
                                .param("settlementImportId", UUID.randomUUID().toString())
                                .param("bankImportId", UUID.randomUUID().toString())
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PREVIEW_REQUEST"));
    }

    @Test
    void rejectsMissingTokenAndMissingRole() throws Exception {
        Selection a = fixture(tenantA, "MATCHED");

        mvc.perform(
                get(URL)
                        .param("paymentImportId", a.payment().toString())
                        .param("settlementImportId", a.settlement().toString())
                        .param("bankImportId", a.bank().toString())
        ).andExpect(status().isUnauthorized());

        preview("no-role", a).andExpect(status().isForbidden());
    }

    @Test
    void previewDoesNotChangeImportsOrCreateRuns() throws Exception {
        Selection a = fixture(tenantA, "MATCHED");

        List<ImportBatchSnapshot> before = snapshots(a);
        long runsBefore = runCount();

        preview("ANALYST", a).andExpect(status().isOk());
        preview("ANALYST", a).andExpect(status().isOk());

        assertEquals(before, snapshots(a));
        assertEquals(runsBefore, runCount());
    }

    private Selection fixture(UUID tenantId, String mode) {
        ImportBatchSnapshot p = processing(
                tenantId, SourceType.INTERNAL_PAYMENT
        );
        ImportBatchSnapshot s = processing(
                tenantId, SourceType.PROCESSOR_SETTLEMENT
        );
        ImportBatchSnapshot b = processing(
                tenantId, SourceType.BANK_STATEMENT
        );

        String paymentReference = mode.equals("MISSING_COUNTERPART")
                ? "MISSING"
                : "PAY-001";

        long paymentAmount = mode.equals("AMOUNT_MISMATCH") ? 101 : 100;
        String paymentCurrency = mode.equals("CURRENCY_MISMATCH")
                ? "USD"
                : "INR";

        paymentPersistence.complete(
                tenantId,
                p.id(),
                p.version(),
                List.of(new PaymentRecord(
                        UUID.randomUUID(),
                        new MatchKey(tenantId, "PROCESSOR_A", paymentReference),
                        Money.ofMinor(paymentAmount, paymentCurrency)
                ))
        );

        List<SettlementLine> lines = new ArrayList<>();
        lines.add(line(tenantId, "PAY-001"));

        if (mode.equals("AMBIGUOUS")) {
            lines.add(line(tenantId, "PAY-001"));
        }

        if (mode.equals("ORPHANS")) {
            lines.add(line(tenantId, "ORPHAN-PAYMENT"));
        }

        long netTotal = 90L * lines.size();

        SettlementKey key = new SettlementKey(
                tenantId, "PROCESSOR_A", "ACCOUNT-001", "SET-001"
        );

        settlementPersistence.complete(
                tenantId,
                s.id(),
                s.version(),
                List.of(new SettlementBatch(
                        UUID.randomUUID(),
                        key,
                        lines,
                        Money.ofMinor(netTotal, "INR")
                ))
        );

        List<BankCredit> credits = new ArrayList<>();

        credits.add(new BankCredit(
                UUID.randomUUID(),
                key,
                Money.ofMinor(
                        mode.equals("ORPHANS") ? netTotal - 1 : netTotal,
                        "INR"
                )
        ));

        if (mode.equals("ORPHANS")) {
            credits.add(new BankCredit(
                    UUID.randomUUID(),
                    new SettlementKey(
                            tenantId,
                            "PROCESSOR_A",
                            "ACCOUNT-001",
                            "ORPHAN-CREDIT"
                    ),
                    Money.ofMinor(7, "INR")
            ));
        }

        bankPersistence.complete(
                tenantId, b.id(), b.version(), credits
        );

        return new Selection(p.id(), s.id(), b.id());
    }

    private SettlementLine line(UUID tenantId, String reference) {
        return new SettlementLine(
                UUID.randomUUID(),
                new MatchKey(tenantId, "PROCESSOR_A", reference),
                Money.ofMinor(100, "INR"),
                Money.ofMinor(10, "INR"),
                Money.ofMinor(90, "INR")
        );
    }

    private ImportBatchSnapshot processing(
            UUID tenantId,
            SourceType sourceType
    ) {
        ImportBatchEntity imported = imports.saveAndFlush(
                new ImportBatchEntity(
                        tenantId,
                        sourceType,
                        "PROCESSOR_A",
                        "fixture.csv",
                        "local:" + UUID.randomUUID() + ".csv",
                        UUID.randomUUID().toString().replace("-", "").repeat(2),
                        LocalDate.of(2026, 11, 25)
                )
        );

        return lifecycle.startProcessing(
                tenantId, imported.getId(), imported.getVersion()
        );
    }

    private ResultActions preview(String token, Selection selection)
            throws Exception {
        return mvc.perform(
                get(URL)
                        .header("Authorization", "Bearer " + token)
                        .param("paymentImportId", selection.payment().toString())
                        .param(
                                "settlementImportId",
                                selection.settlement().toString()
                        )
                        .param("bankImportId", selection.bank().toString())
        );
    }

    private List<ImportBatchSnapshot> snapshots(Selection selection) {
        return List.of(
                lifecycle.get(tenantA, selection.payment()),
                lifecycle.get(tenantA, selection.settlement()),
                lifecycle.get(tenantA, selection.bank())
        );
    }

    private long runCount() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_run WHERE tenant_id = ?",
                Long.class,
                tenantA.toString()
        );

        return count == null ? 0L : count;
    }

    private void token(String value, UUID tenantId, List<String> roles) {
        Instant now = Instant.now();

        Jwt jwt = Jwt.withTokenValue(value)
                .header("alg", "RS256")
                .subject(UUID.randomUUID().toString())
                .issuer("http://localhost:8081/realms/reconbridge")
                .audience(List.of("reconbridge-api"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("tenant_id", tenantId.toString())
                .claim("realm_access", Map.of("roles", roles))
                .build();

        when(decoder.decode(value)).thenReturn(jwt);
    }

    private record Selection(
            UUID payment,
            UUID settlement,
            UUID bank
    ) {
    }
}