package com.suyog.reconbridge.application;

import com.suyog.reconbridge.application.exception.ImportNotFoundException;
import com.suyog.reconbridge.application.exception.StaleImportVersionException;
import com.suyog.reconbridge.application.model.ImportBatchSnapshot;
import com.suyog.reconbridge.domain.model.ImportStatus;
import com.suyog.reconbridge.domain.model.SourceType;
import com.suyog.reconbridge.persistence.entity.ImportBatchEntity;
import com.suyog.reconbridge.persistence.entity.TenantEntity;
import com.suyog.reconbridge.persistence.repository.ImportBatchRepository;
import com.suyog.reconbridge.persistence.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
class ImportLifecycleServiceIntegrationTest {

    @Autowired
    private ImportLifecycleService service;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private ImportBatchRepository importRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenantId;
    private UUID otherTenantId;
    private UUID importId;

    @BeforeEach
    void setUp() {
        assertEquals(
                "reconbridge_test",
                jdbc.queryForObject(
                        "SELECT DATABASE()",
                        String.class
                )
        );

        tenantId = UUID.randomUUID();
        otherTenantId = UUID.randomUUID();

        tenantRepository.saveAndFlush(
                new TenantEntity(tenantId, "Lifecycle Tenant")
        );

        tenantRepository.saveAndFlush(
                new TenantEntity(otherTenantId, "Other Tenant")
        );

        ImportBatchEntity saved = importRepository.saveAndFlush(
                new ImportBatchEntity(
                        tenantId,
                        SourceType.PROCESSOR_SETTLEMENT,
                        "PROCESSOR_A",
                        "settlements.csv",
                        "test://imports/" + UUID.randomUUID(),
                        "f".repeat(64),
                        LocalDate.of(2026, 10, 20)
                )
        );

        importId = saved.getId();
    }

    @AfterEach
    void cleanUp() {
        if (tenantId != null) {
            jdbc.update(
                    "DELETE FROM import_batch WHERE tenant_id = ?",
                    tenantId.toString()
            );

            jdbc.update(
                    "DELETE FROM tenant WHERE id = ?",
                    tenantId.toString()
            );
        }

        if (otherTenantId != null) {
            jdbc.update(
                    "DELETE FROM tenant WHERE id = ?",
                    otherTenantId.toString()
            );
        }
    }

    @Test
    void startsProcessingAndCommitsUpdatedVersion() {
        ImportBatchSnapshot before = service.get(
                tenantId,
                importId
        );

        ImportBatchSnapshot result = service.startProcessing(
                tenantId,
                importId,
                before.version()
        );

        assertEquals(ImportStatus.PROCESSING, result.status());
        assertEquals(before.version() + 1, result.version());

        // A separate service call reads committed state.
        assertEquals(
                result,
                service.get(tenantId, importId)
        );
    }

    @Test
    void completesImportAndPersistsValidatedRowCount() {
        ImportBatchSnapshot processing = startImport();

        ImportBatchSnapshot ready = service.markReady(
                tenantId,
                importId,
                processing.version(),
                25
        );

        assertEquals(ImportStatus.READY, ready.status());
        assertEquals(25L, ready.rowCount());
        assertEquals(processing.version() + 1, ready.version());

        assertEquals(
                ready,
                service.get(tenantId, importId)
        );
    }

    @Test
    void refusesStaleRequestWithoutChangingCommittedState() {
        ImportBatchSnapshot original = service.get(
                tenantId,
                importId
        );

        ImportBatchSnapshot processing = service.startProcessing(
                tenantId,
                importId,
                original.version()
        );

        assertThrows(
                StaleImportVersionException.class,
                () -> service.markReady(
                        tenantId,
                        importId,
                        original.version(),
                        25
                )
        );

        assertEquals(
                processing,
                service.get(tenantId, importId)
        );
    }

    @Test
    void anotherTenantCannotReadOrTransitionImport() {
        ImportBatchSnapshot original = service.get(
                tenantId,
                importId
        );

        assertThrows(
                ImportNotFoundException.class,
                () -> service.get(otherTenantId, importId)
        );

        assertThrows(
                ImportNotFoundException.class,
                () -> service.startProcessing(
                        otherTenantId,
                        importId,
                        original.version()
                )
        );

        assertEquals(
                original,
                service.get(tenantId, importId)
        );
    }

    @Test
    void invalidTransitionLeavesDatabaseUnchanged() {
        ImportBatchSnapshot original = service.get(
                tenantId,
                importId
        );

        assertThrows(
                IllegalStateException.class,
                () -> service.markReady(
                        tenantId,
                        importId,
                        original.version(),
                        25
                )
        );

        assertEquals(
                original,
                service.get(tenantId, importId)
        );
    }

    @Test
    void invalidRowCountLeavesProcessingStateUnchanged() {
        ImportBatchSnapshot processing = startImport();

        assertThrows(
                IllegalArgumentException.class,
                () -> service.markReady(
                        tenantId,
                        importId,
                        processing.version(),
                        -1
                )
        );

        assertEquals(
                processing,
                service.get(tenantId, importId)
        );
    }

    @Test
    void failedImportCanBeRetriedAndCompleted() {
        ImportBatchSnapshot processing = startImport();

        ImportBatchSnapshot failed = service.fail(
                tenantId,
                importId,
                processing.version()
        );

        assertEquals(ImportStatus.FAILED, failed.status());

        ImportBatchSnapshot retrying = service.retryProcessing(
                tenantId,
                importId,
                failed.version()
        );

        assertEquals(ImportStatus.PROCESSING, retrying.status());

        ImportBatchSnapshot ready = service.markReady(
                tenantId,
                importId,
                retrying.version(),
                12
        );

        assertEquals(importId, ready.id());
        assertEquals(ImportStatus.READY, ready.status());
        assertEquals(12L, ready.rowCount());

        assertEquals(
                ready,
                service.get(tenantId, importId)
        );
    }

    @Test
    void rejectedImportCannotBeRetried() {
        ImportBatchSnapshot processing = startImport();

        ImportBatchSnapshot rejected = service.reject(
                tenantId,
                importId,
                processing.version()
        );

        assertEquals(ImportStatus.REJECTED, rejected.status());

        assertThrows(
                IllegalStateException.class,
                () -> service.retryProcessing(
                        tenantId,
                        importId,
                        rejected.version()
                )
        );

        assertEquals(
                rejected,
                service.get(tenantId, importId)
        );
    }

    private ImportBatchSnapshot startImport() {
        ImportBatchSnapshot original = service.get(
                tenantId,
                importId
        );

        return service.startProcessing(
                tenantId,
                importId,
                original.version()
        );
    }
}