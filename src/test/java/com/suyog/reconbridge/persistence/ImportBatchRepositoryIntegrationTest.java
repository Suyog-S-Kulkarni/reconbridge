package com.suyog.reconbridge.persistence;

import com.suyog.reconbridge.domain.model.ImportStatus;
import com.suyog.reconbridge.domain.model.SourceType;
import com.suyog.reconbridge.persistence.entity.ImportBatchEntity;
import com.suyog.reconbridge.persistence.entity.TenantEntity;
import com.suyog.reconbridge.persistence.repository.ImportBatchRepository;
import com.suyog.reconbridge.persistence.repository.TenantRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ImportBatchRepositoryIntegrationTest {

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private ImportBatchRepository importRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void savesAndReloadsImportForOwningTenant() {
        UUID tenantId = createTenant("Tenant A");

        ImportBatchEntity saved = importRepository.saveAndFlush(
                newImport(tenantId, "a".repeat(64))
        );

        UUID importId = saved.getId();

        // Force the lookup to load persisted data.
        entityManager.clear();

        ImportBatchEntity loaded = importRepository
                .findByTenantIdAndId(tenantId, importId)
                .orElseThrow();

        assertEquals(importId, loaded.getId());
        assertEquals(tenantId, loaded.getTenantId());

        assertEquals(
                SourceType.PROCESSOR_SETTLEMENT,
                loaded.getSourceType()
        );

        assertEquals(
                ImportStatus.RECEIVED,
                loaded.getStatus()
        );

        assertEquals("PROCESSOR_A", loaded.getSourceSystem());
        assertEquals("a".repeat(64), loaded.getFileHash());

        assertEquals(
                LocalDate.of(2026, 10, 20),
                loaded.getBusinessDate()
        );

        assertEquals(0L, loaded.getRowCount());
        assertEquals(Long.valueOf(0), loaded.getVersion());
    }

    @Test
    void anotherTenantCannotRetrieveImportByItsId() {
        UUID tenantA = createTenant("Tenant A");
        UUID tenantB = createTenant("Tenant B");

        ImportBatchEntity saved = importRepository.saveAndFlush(
                newImport(tenantA, "b".repeat(64))
        );

        UUID importId = saved.getId();

        entityManager.clear();

        assertTrue(
                importRepository
                        .findByTenantIdAndId(tenantB, importId)
                        .isEmpty()
        );

        assertTrue(
                importRepository
                        .findByTenantIdAndId(tenantA, importId)
                        .isPresent()
        );
    }

    @Test
    void sameContentCanBelongToDifferentTenants() {
        UUID tenantA = createTenant("Tenant A");
        UUID tenantB = createTenant("Tenant B");

        String hash = "c".repeat(64);

        ImportBatchEntity first = importRepository.saveAndFlush(
                newImport(tenantA, hash)
        );

        ImportBatchEntity second = importRepository.saveAndFlush(
                newImport(tenantB, hash)
        );

        UUID firstId = first.getId();
        UUID secondId = second.getId();

        entityManager.clear();

        ImportBatchEntity foundForA = importRepository
                .findByTenantIdAndSourceTypeAndSourceSystemAndFileHash(
                        tenantA,
                        SourceType.PROCESSOR_SETTLEMENT,
                        "PROCESSOR_A",
                        hash
                )
                .orElseThrow();

        ImportBatchEntity foundForB = importRepository
                .findByTenantIdAndSourceTypeAndSourceSystemAndFileHash(
                        tenantB,
                        SourceType.PROCESSOR_SETTLEMENT,
                        "PROCESSOR_A",
                        hash
                )
                .orElseThrow();

        assertEquals(firstId, foundForA.getId());
        assertEquals(secondId, foundForB.getId());
    }

    @Test
    void rejectsDuplicateContentWithinSameTenantAndSource() {
        UUID tenantId = createTenant("Tenant A");
        String hash = "d".repeat(64);

        importRepository.saveAndFlush(
                newImport(tenantId, hash)
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> importRepository.saveAndFlush(
                        newImport(tenantId, hash)
                )
        );
    }

    private UUID createTenant(String name) {
        UUID tenantId = UUID.randomUUID();

        tenantRepository.saveAndFlush(
                new TenantEntity(tenantId, name)
        );

        return tenantId;
    }

    private ImportBatchEntity newImport(
            UUID tenantId,
            String fileHash
    ) {
        return new ImportBatchEntity(
                tenantId,
                SourceType.PROCESSOR_SETTLEMENT,
                "PROCESSOR_A",
                "settlements.csv",
                "test://imports/" + UUID.randomUUID(),
                fileHash,
                LocalDate.of(2026, 10, 20)
        );
    }
}