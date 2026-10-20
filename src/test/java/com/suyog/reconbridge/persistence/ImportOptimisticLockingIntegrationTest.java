package com.suyog.reconbridge.persistence;

import com.suyog.reconbridge.domain.model.ImportStatus;
import com.suyog.reconbridge.domain.model.SourceType;
import com.suyog.reconbridge.persistence.entity.ImportBatchEntity;
import com.suyog.reconbridge.persistence.entity.TenantEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
class ImportOptimisticLockingIntegrationTest {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void onlyOneWorkerCanStartFromTheSameReceivedVersion() {
        verifyStaleUpdateRejected(
                false,
                ImportBatchEntity::startProcessing,
                ImportBatchEntity::startProcessing,
                ImportStatus.PROCESSING,
                0
        );
    }

    @Test
    void staleFailureCannotOverwriteCommittedReadyState() {
        verifyStaleUpdateRejected(
                true,
                batch -> batch.markReady(25),
                ImportBatchEntity::fail,
                ImportStatus.READY,
                25
        );
    }

    private void verifyStaleUpdateRejected(
            boolean initiallyProcessing,
            Consumer<ImportBatchEntity> firstChange,
            Consumer<ImportBatchEntity> staleChange,
            ImportStatus expectedStatus,
            long expectedRowCount
    ) {
        // Guard the committed setup and cleanup against a wrong schema.
        assertEquals(
                "reconbridge_test",
                jdbc.queryForObject(
                        "SELECT DATABASE()",
                        String.class
                )
        );

        UUID tenantId = UUID.randomUUID();

        ImportBatchEntity seed = new ImportBatchEntity(
                tenantId,
                SourceType.PROCESSOR_SETTLEMENT,
                "PROCESSOR_A",
                "settlements.csv",
                "test://imports/" + UUID.randomUUID(),
                "e".repeat(64),
                LocalDate.of(2026, 10, 20)
        );

        if (initiallyProcessing) {
            seed.startProcessing();
        }

        UUID importId = seed.getId();

        try {
            createCommittedFixture(tenantId, seed);

            long originalVersion;

            EntityManager first =
                    entityManagerFactory.createEntityManager();

            try {
                EntityManager second =
                        entityManagerFactory.createEntityManager();

                try {
                    first.getTransaction().begin();
                    second.getTransaction().begin();

                    ImportBatchEntity firstCopy = first.find(
                            ImportBatchEntity.class,
                            importId
                    );

                    ImportBatchEntity staleCopy = second.find(
                            ImportBatchEntity.class,
                            importId
                    );

                    assertNotNull(firstCopy);
                    assertNotNull(staleCopy);

                    assertEquals(
                            firstCopy.getVersion(),
                            staleCopy.getVersion()
                    );

                    originalVersion = firstCopy.getVersion();

                    // Both workers act on the same original version.
                    firstChange.accept(firstCopy);
                    staleChange.accept(staleCopy);

                    // Worker one commits its update.
                    first.getTransaction().commit();

                    // Worker two still holds the original version.
                    assertThrows(
                            OptimisticLockException.class,
                            second::flush
                    );

                } finally {
                    rollbackAndClose(second);
                }

            } finally {
                rollbackAndClose(first);
            }

            // A fresh persistence context verifies committed state.
            EntityManager verifier =
                    entityManagerFactory.createEntityManager();

            try {
                verifier.getTransaction().begin();

                ImportBatchEntity stored = verifier.find(
                        ImportBatchEntity.class,
                        importId
                );

                assertNotNull(stored);

                assertEquals(
                        expectedStatus,
                        stored.getStatus()
                );

                assertEquals(
                        expectedRowCount,
                        stored.getRowCount()
                );

                assertEquals(
                        Long.valueOf(originalVersion + 1),
                        stored.getVersion()
                );

                verifier.getTransaction().commit();

            } finally {
                rollbackAndClose(verifier);
            }

        } finally {
            // Remove only this test's committed records.
            jdbc.update(
                    """
                    DELETE FROM import_batch
                    WHERE tenant_id = ?
                      AND id = ?
                    """,
                    tenantId.toString(),
                    importId.toString()
            );

            jdbc.update(
                    "DELETE FROM tenant WHERE id = ?",
                    tenantId.toString()
            );
        }
    }

    private void createCommittedFixture(
            UUID tenantId,
            ImportBatchEntity batch
    ) {
        EntityManager entityManager =
                entityManagerFactory.createEntityManager();

        try {
            entityManager.getTransaction().begin();

            entityManager.persist(
                    new TenantEntity(
                            tenantId,
                            "Optimistic Lock Test Tenant"
                    )
            );

            // The import has a scalar tenantId mapping, so explicitly
            // insert the parent before inserting the child.
            entityManager.flush();

            entityManager.persist(batch);

            entityManager.getTransaction().commit();

        } finally {
            rollbackAndClose(entityManager);
        }
    }

    private void rollbackAndClose(EntityManager entityManager) {
        try {
            if (entityManager.getTransaction().isActive()) {
                entityManager.getTransaction().rollback();
            }
        } finally {
            entityManager.close();
        }
    }
}