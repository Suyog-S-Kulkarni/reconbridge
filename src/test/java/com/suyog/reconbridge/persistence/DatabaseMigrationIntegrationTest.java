package com.suyog.reconbridge.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DatabaseMigrationIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void appliesMigrationToTestDatabaseAndPersistsImport() {
        assertEquals(
                "reconbridge_test",
                jdbc.queryForObject(
                        "SELECT DATABASE()",
                        String.class
                )
        );

        Integer appliedMigrations = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '1'
                  AND success = 1
                """,
                Integer.class
        );

        assertEquals(1, appliedMigrations.intValue());

        String tenantId = createTenant();
        String importId = UUID.randomUUID().toString();

        insertImport(
                importId,
                tenantId,
                "a".repeat(64)
        );

        String status = jdbc.queryForObject(
                """
                SELECT status
                FROM import_batch
                WHERE tenant_id = ?
                  AND id = ?
                """,
                String.class,
                tenantId,
                importId
        );

        assertEquals("RECEIVED", status);
    }

    @Test
    void rejectsDuplicateContentWithinSameSourceAndTenant() {
        String tenantId = createTenant();
        String hash = "b".repeat(64);

        insertImport(
                UUID.randomUUID().toString(),
                tenantId,
                hash
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertImport(
                        UUID.randomUUID().toString(),
                        tenantId,
                        hash
                )
        );
    }

    @Test
    void rejectsImportForUnknownTenant() {
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertImport(
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(),
                        "c".repeat(64)
                )
        );
    }

    private String createTenant() {
        String tenantId = UUID.randomUUID().toString();

        jdbc.update(
                """
                INSERT INTO tenant (
                    id,
                    name
                )
                VALUES (?, ?)
                """,
                tenantId,
                "Integration Test Tenant"
        );

        return tenantId;
    }

    private void insertImport(
            String importId,
            String tenantId,
            String fileHash
    ) {
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
                    ?, ?, ?, ?, ?, ?, ?, ?,
                    CURRENT_DATE(), 0, 0
                )
                """,
                importId,
                tenantId,
                "PROCESSOR_SETTLEMENT",
                "PROCESSOR_A",
                "settlements.csv",
                "test://imports/" + importId,
                fileHash,
                "RECEIVED"
        );
    }
}