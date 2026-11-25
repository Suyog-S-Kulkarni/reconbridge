package com.suyog.reconbridge.persistence;

import com.suyog.reconbridge.application.ReconciliationRunService;
import com.suyog.reconbridge.application.model.BankReconciliationPreview;
import com.suyog.reconbridge.application.model.StoredReconciliationRun;
import com.suyog.reconbridge.application.model.ThreeSourceReconciliationPreview;
import com.suyog.reconbridge.domain.model.MatchOutcome;
import com.suyog.reconbridge.persistence.repository.ReconciliationRunRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class VersionedReconciliationRunIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReconciliationRunRepository repository;

    @Autowired
    private ReconciliationRunService service;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JsonMapper json;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private UUID tenantId;
    private UUID paymentImportId;
    private UUID settlementImportId;
    private UUID bankImportId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();

        jdbc.update(
                "INSERT INTO tenant (id, name) VALUES (?, ?)",
                tenantId.toString(),
                "Versioned run test"
        );

        paymentImportId = createImport("INTERNAL_PAYMENT");
        settlementImportId = createImport("PROCESSOR_SETTLEMENT");
        bankImportId = createImport("BANK_STATEMENT");
    }

    @AfterEach
    void cleanUp() {
        for (String table : List.of(
                "reconciliation_run_item",
                "reconciliation_run",
                "import_batch"
        )) {
            jdbc.update(
                    "DELETE FROM " + table + " WHERE tenant_id = ?",
                    tenantId.toString()
            );
        }

        jdbc.update(
                "DELETE FROM tenant WHERE id = ?",
                tenantId.toString()
        );
    }

    @Test
    void preservesVersionOneRoundTripAndJsonShape() {
        BankReconciliationPreview report =
                new BankReconciliationPreview(
                        settlementImportId,
                        bankImportId,
                        1,
                        1,
                        1,
                        0,
                        0,
                        true,
                        List.of(batch()),
                        List.of()
                );

        StoredReconciliationRun original =
                new StoredReconciliationRun(
                        UUID.randomUUID(),
                        "EXACT_SETTLEMENT_BANK_V1",
                        1,
                        "analyst",
                        Instant.parse("2026-11-25T10:00:00Z"),
                        report
                );

        save(original);

        StoredReconciliationRun loaded = service.get(
                tenantId,
                original.id()
        );

        assertEquals(original, loaded);

        assertInstanceOf(
                BankReconciliationPreview.class,
                loaded.report()
        );

        var tree = json.readTree(json.writeValueAsString(loaded));

        assertEquals(1, tree.get("reportVersion").asInt());
        assertTrue(tree.get("report").has("results"));
        assertFalse(tree.get("report").has("paymentImportId"));
        assertFalse(tree.get("report").has("paymentResults"));
    }

    @Test
    void roundTripsVersionTwoWithEveryItemType() {
        StoredReconciliationRun original = versionTwo(false);

        save(original);

        StoredReconciliationRun loaded = service.get(
                tenantId,
                original.id()
        );

        assertEquals(original, loaded);

        assertInstanceOf(
                ThreeSourceReconciliationPreview.class,
                loaded.report()
        );

        assertEquals(4L, countItems(original.id()));

        var tree = json.readTree(json.writeValueAsString(loaded));

        assertEquals(2, tree.get("reportVersion").asInt());

        assertEquals(
                paymentImportId.toString(),
                tree.get("report").get("paymentImportId").asText()
        );

        assertTrue(tree.get("report").has("paymentResults"));
        assertTrue(tree.get("report").has("bankResults"));
        assertTrue(tree.get("report").has("orphanSettlementLines"));
        assertTrue(tree.get("report").has("orphanBankCredits"));
    }

    @Test
    void anotherTenantCannotReadTheRun() {
        StoredReconciliationRun run = versionTwo(false);

        save(run);

        assertTrue(
                repository.find(
                        UUID.randomUUID(),
                        run.id()
                ).isEmpty()
        );
    }

    @Test
    void laterDuplicateItemRollsBackHeaderAndEarlierItems() {
        StoredReconciliationRun run = versionTwo(true);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> save(run)
        );

        Long headers = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM reconciliation_run
                WHERE tenant_id = ? AND id = ?
                """,
                Long.class,
                tenantId.toString(),
                run.id().toString()
        );

        assertEquals(0L, headers.longValue());
        assertEquals(0L, countItems(run.id()));
    }

    @Test
    void rejectsVersionAndReportTypeMismatch() {
        StoredReconciliationRun run = versionTwo(false);

        // This constructor is called directly, outside a repository proxy.
        assertThrows(
                IllegalArgumentException.class,
                () -> new StoredReconciliationRun(
                        run.id(),
                        run.ruleVersion(),
                        1,
                        run.createdBy(),
                        run.createdAt(),
                        run.report()
                )
        );
    }

    @Test
    void detectsStoredSummaryInconsistency() {
        StoredReconciliationRun run = versionTwo(false);

        save(run);

        // Valid under the SQL summary CHECK, but inconsistent
        // with the actual number of persisted payment results.
        jdbc.update(
                """
                UPDATE reconciliation_run
                SET payment_count = 2,
                    unresolved_payment_count = 2
                WHERE tenant_id = ? AND id = ?
                """,
                tenantId.toString(),
                run.id().toString()
        );

        assertTranslatedConsistencyFailure(
                "Report counts do not match its results.",
                () -> service.get(tenantId, run.id())
        );
    }

    @Test
    void detectsOutcomeMismatchBetweenColumnsAndPayload() {
        StoredReconciliationRun run = versionTwo(false);

        save(run);

        // Change only the indexed outcome, leaving the JSON
        // payload's original MISSING_COUNTERPART outcome intact.
        jdbc.update(
                """
                UPDATE reconciliation_run_item
                SET outcome = 'AMOUNT_MISMATCH'
                WHERE tenant_id = ?
                  AND run_id = ?
                  AND item_type = 'PAYMENT'
                """,
                tenantId.toString(),
                run.id().toString()
        );

        assertTranslatedConsistencyFailure(
                "Stored item metadata and JSON payload disagree.",
                () -> service.get(tenantId, run.id())
        );
    }

    private void assertTranslatedConsistencyFailure(
            String expectedMessage,
            Executable operation
    ) {
        InvalidDataAccessApiUsageException failure = assertThrows(
                InvalidDataAccessApiUsageException.class,
                operation
        );

        IllegalStateException cause = assertInstanceOf(
                IllegalStateException.class,
                failure.getMostSpecificCause()
        );

        assertEquals(expectedMessage, cause.getMessage());
    }

    private void save(StoredReconciliationRun run) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status -> repository.save(tenantId, run)
                );
    }

    private StoredReconciliationRun versionTwo(
            boolean duplicatePayment
    ) {
        var payment =
                new ThreeSourceReconciliationPreview.PaymentResult(
                        UUID.randomUUID(),
                        "PROCESSOR_A",
                        "PAY-MISSING",
                        MatchOutcome.MISSING_COUNTERPART,
                        List.of()
                );

        List<ThreeSourceReconciliationPreview.PaymentResult> payments =
                duplicatePayment
                        ? List.of(payment, payment)
                        : List.of(payment);

        var line =
                new ThreeSourceReconciliationPreview.OrphanSettlementLine(
                        UUID.randomUUID(),
                        "PROCESSOR_A",
                        "UNEXPECTED-PAYMENT",
                        100,
                        10,
                        90,
                        "INR"
                );

        var orphanCredit =
                new BankReconciliationPreview.OrphanCredit(
                        UUID.randomUUID(),
                        "PROCESSOR_A",
                        "ACCOUNT-001",
                        "UNEXPECTED-SETTLEMENT",
                        50,
                        "INR"
                );

        ThreeSourceReconciliationPreview report =
                new ThreeSourceReconciliationPreview(
                        paymentImportId,
                        settlementImportId,
                        bankImportId,
                        payments.size(),
                        1,
                        1,
                        2,
                        payments.size(),
                        0,
                        1,
                        1,
                        false,
                        counts(
                                MatchOutcome.MISSING_COUNTERPART,
                                payments.size()
                        ),
                        counts(MatchOutcome.MATCHED, 1),
                        payments,
                        List.of(line),
                        List.of(batch()),
                        List.of(orphanCredit)
                );

        return new StoredReconciliationRun(
                UUID.randomUUID(),
                "EXACT_THREE_SOURCE_V1",
                2,
                "analyst",
                Instant.parse("2026-11-25T10:00:00Z"),
                report
        );
    }

    private BankReconciliationPreview.BatchResult batch() {
        return new BankReconciliationPreview.BatchResult(
                UUID.randomUUID(),
                "PROCESSOR_A",
                "ACCOUNT-001",
                "SETTLEMENT-001",
                MatchOutcome.MATCHED,
                List.of(UUID.randomUUID()),
                0L,
                "INR"
        );
    }

    private Map<MatchOutcome, Long> counts(
            MatchOutcome selected,
            long count
    ) {
        EnumMap<MatchOutcome, Long> values =
                new EnumMap<>(MatchOutcome.class);

        for (MatchOutcome outcome : MatchOutcome.values()) {
            values.put(outcome, 0L);
        }

        values.put(selected, count);

        return Map.copyOf(values);
    }

    private long countItems(UUID runId) {
        Long count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM reconciliation_run_item
                WHERE tenant_id = ? AND run_id = ?
                """,
                Long.class,
                tenantId.toString(),
                runId.toString()
        );

        return count.longValue();
    }

    private UUID createImport(String sourceType) {
        UUID id = UUID.randomUUID();

        String hash =
                UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID()
                        .toString()
                        .replace("-", "");

        jdbc.update(
                """
                INSERT INTO import_batch (
                    id, tenant_id, source_type, source_system,
                    file_name, file_uri, file_hash, status,
                    business_date, row_count, version
                )
                VALUES (
                    ?, ?, ?, 'PROCESSOR_A',
                    'versioned-run-test.csv', ?, ?, 'READY',
                    '2026-11-25', 1, 0
                )
                """,
                id.toString(),
                tenantId.toString(),
                sourceType,
                "test://" + id,
                hash
        );

        return id;
    }
}