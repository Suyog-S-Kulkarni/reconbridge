package com.suyog.reconbridge.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ThreeSourceRunSchemaIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private UUID tenantId;
    private UUID paymentImportId;
    private UUID settlementImportId;
    private UUID bankImportId;

    @BeforeEach
    void setUp() {
        tenantId = createTenant();

        paymentImportId = createImport(
                tenantId,
                "INTERNAL_PAYMENT"
        );

        settlementImportId = createImport(
                tenantId,
                "PROCESSOR_SETTLEMENT"
        );

        bankImportId = createImport(
                tenantId,
                "BANK_STATEMENT"
        );
    }

    @Test
    void legacyInsertStillWorksWithDefaultPaymentColumns() {
        UUID runId = UUID.randomUUID();

        jdbc.update(
                """
                INSERT INTO reconciliation_run (
                    tenant_id,
                    id,
                    settlement_import_id,
                    bank_import_id,
                    rule_version,
                    report_version,
                    created_by,
                    created_at_utc,
                    settlement_batch_count,
                    bank_credit_count,
                    matched_batch_count,
                    unresolved_batch_count,
                    orphan_bank_credit_count,
                    fully_reconciled
                )
                VALUES (
                    ?, ?, ?, ?,
                    'EXACT_SETTLEMENT_BANK_V1',
                    1,
                    'schema-test',
                    UTC_TIMESTAMP(6),
                    0, 0, 0, 0, 0, TRUE
                )
                """,
                tenantId.toString(),
                runId.toString(),
                settlementImportId.toString(),
                bankImportId.toString()
        );

        String selectedPaymentImport = jdbc.queryForObject(
                """
                SELECT payment_import_id
                FROM reconciliation_run
                WHERE tenant_id = ? AND id = ?
                """,
                String.class,
                tenantId.toString(),
                runId.toString()
        );

        Long paymentCount = jdbc.queryForObject(
                """
                SELECT payment_count
                FROM reconciliation_run
                WHERE tenant_id = ? AND id = ?
                """,
                Long.class,
                tenantId.toString(),
                runId.toString()
        );

        assertNull(selectedPaymentImport);
        assertNotNull(paymentCount);
        assertEquals(0L, paymentCount.longValue());
    }

    @Test
    void versionTwoAcceptsOwnedPaymentImport() {
        UUID runId = insertRun(2, paymentImportId);

        String storedPaymentImport = jdbc.queryForObject(
                """
                SELECT payment_import_id
                FROM reconciliation_run
                WHERE tenant_id = ? AND id = ?
                """,
                String.class,
                tenantId.toString(),
                runId.toString()
        );

        assertEquals(
                paymentImportId.toString(),
                storedPaymentImport
        );
    }

    @Test
    void versionTwoRejectsMissingPaymentImport() {
        assertCheckViolation(
                "chk_reconciliation_run_source_shape",
                () -> insertRun(2, null)
        );
    }

    @Test
    void versionOneRejectsPaymentImport() {
        assertCheckViolation(
                "chk_reconciliation_run_source_shape",
                () -> insertRun(1, paymentImportId)
        );
    }

    @Test
    void rejectsPaymentImportFromAnotherTenant() {
        UUID otherTenant = createTenant();

        UUID foreignPaymentImport = createImport(
                otherTenant,
                "INTERNAL_PAYMENT"
        );

        assertMysqlConstraintViolation(
                1452,
                "23000",
                "fk_reconciliation_run_payment_import",
                () -> insertRun(2, foreignPaymentImport)
        );
    }

    @Test
    void rejectsInconsistentPaymentSummary() {
        UUID runId = insertRun(2, paymentImportId);

        assertCheckViolation(
                "chk_reconciliation_run_payment_counts",
                () -> jdbc.update(
                        """
                        UPDATE reconciliation_run
                        SET payment_count = 2,
                            matched_payment_count = 1,
                            unresolved_payment_count = 0
                        WHERE tenant_id = ? AND id = ?
                        """,
                        tenantId.toString(),
                        runId.toString()
                )
        );

        // The rejected statement must leave the existing row unchanged.
        Long paymentCount = jdbc.queryForObject(
                """
                SELECT payment_count
                FROM reconciliation_run
                WHERE tenant_id = ? AND id = ?
                """,
                Long.class,
                tenantId.toString(),
                runId.toString()
        );

        assertNotNull(paymentCount);
        assertEquals(0L, paymentCount.longValue());
    }

    @Test
    void acceptsPaymentAndOrphanLineResults() {
        UUID runId = insertRun(2, paymentImportId);

        insertItem(
                runId,
                "PAYMENT",
                "AMOUNT_MISMATCH"
        );

        insertItem(
                runId,
                "ORPHAN_LINE",
                "ORPHAN_SETTLEMENT_LINE"
        );

        assertEquals(2L, countItems(runId));
    }

    @Test
    void rejectsBankOrphanOutcomeForOrphanLine() {
        UUID runId = insertRun(2, paymentImportId);

        assertCheckViolation(
                "chk_reconciliation_run_item_type",
                () -> insertItem(
                        runId,
                        "ORPHAN_LINE",
                        "ORPHAN_BANK_CREDIT"
                )
        );

        assertEquals(0L, countItems(runId));
    }

    private void assertCheckViolation(
            String constraintName,
            Executable operation
    ) {
        assertMysqlConstraintViolation(
                3819,
                "HY000",
                constraintName,
                operation
        );
    }

    private void assertMysqlConstraintViolation(
            int expectedErrorCode,
            String expectedSqlState,
            String constraintName,
            Executable operation
    ) {
        DataAccessException failure = assertThrows(
                DataAccessException.class,
                operation
        );

        SQLException sqlException = findSqlException(failure);

        assertNotNull(
                sqlException,
                () -> "Expected an underlying SQLException, but received: "
                        + failure
        );

        assertEquals(
                expectedErrorCode,
                sqlException.getErrorCode(),
                () -> "Unexpected MySQL error: "
                        + sqlException.getMessage()
        );

        assertEquals(
                expectedSqlState,
                sqlException.getSQLState(),
                () -> "Unexpected SQLSTATE: "
                        + sqlException.getMessage()
        );

        assertTrue(
                sqlException.getMessage() != null
                        && sqlException.getMessage()
                        .contains(constraintName),
                () -> "Expected violation of "
                        + constraintName
                        + ", but received: "
                        + sqlException.getMessage()
        );
    }

    private SQLException findSqlException(Throwable failure) {
        Throwable current = failure;

        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }

            current = current.getCause();
        }

        return null;
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

        assertNotNull(count);
        return count.longValue();
    }

    private UUID createTenant() {
        UUID id = UUID.randomUUID();

        jdbc.update(
                "INSERT INTO tenant (id, name) VALUES (?, ?)",
                id.toString(),
                "Three-source schema test"
        );

        return id;
    }

    private UUID createImport(UUID owner, String sourceType) {
        UUID id = UUID.randomUUID();

        String hash =
                UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID()
                        .toString()
                        .replace("-", "");

        jdbc.update(
                """
                INSERT INTO import_batch (
                    id,
                    tenant_id,
                    source_type,
                    source_system,
                    file_name,
                    file_uri,
                    file_hash,
                    status,
                    business_date,
                    row_count,
                    version
                )
                VALUES (
                    ?, ?, ?, 'SCHEMA_TEST',
                    'schema-test.csv', ?, ?,
                    'READY', '2026-11-25', 0, 0
                )
                """,
                id.toString(),
                owner.toString(),
                sourceType,
                "test://" + id,
                hash
        );

        return id;
    }

    private UUID insertRun(
            int reportVersion,
            UUID selectedPaymentImport
    ) {
        UUID runId = UUID.randomUUID();

        jdbc.update(
                """
                INSERT INTO reconciliation_run (
                    tenant_id,
                    id,
                    settlement_import_id,
                    bank_import_id,
                    payment_import_id,
                    rule_version,
                    report_version,
                    created_by,
                    created_at_utc,
                    settlement_batch_count,
                    bank_credit_count,
                    matched_batch_count,
                    unresolved_batch_count,
                    orphan_bank_credit_count,
                    fully_reconciled
                )
                VALUES (
                    ?, ?, ?, ?, ?,
                    'SCHEMA_TEST',
                    ?,
                    'schema-test',
                    UTC_TIMESTAMP(6),
                    0, 0, 0, 0, 0, TRUE
                )
                """,
                tenantId.toString(),
                runId.toString(),
                settlementImportId.toString(),
                bankImportId.toString(),
                selectedPaymentImport == null
                        ? null
                        : selectedPaymentImport.toString(),
                reportVersion
        );

        return runId;
    }

    private void insertItem(
            UUID runId,
            String itemType,
            String outcome
    ) {
        jdbc.update(
                """
                INSERT INTO reconciliation_run_item (
                    tenant_id,
                    run_id,
                    item_type,
                    evidence_id,
                    outcome,
                    payload
                )
                VALUES (?, ?, ?, ?, ?, '{}')
                """,
                tenantId.toString(),
                runId.toString(),
                itemType,
                UUID.randomUUID().toString(),
                outcome
        );
    }
}