package com.suyog.reconbridge.persistence;

import com.suyog.reconbridge.domain.model.ImportStatus;
import com.suyog.reconbridge.domain.model.SourceType;
import com.suyog.reconbridge.persistence.entity.ImportBatchEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImportBatchLifecycleTest {

    @Test
    void startsReceivedImport() {
        ImportBatchEntity batch = newImport();

        batch.startProcessing();

        assertEquals(
                ImportStatus.PROCESSING,
                batch.getStatus()
        );
    }

    @Test
    void completesProcessingWithValidatedRowCount() {
        ImportBatchEntity batch = newImport();
        batch.startProcessing();

        batch.markReady(25);

        assertEquals(ImportStatus.READY, batch.getStatus());
        assertEquals(25L, batch.getRowCount());
    }

    @Test
    void cannotBecomeReadyBeforeProcessing() {
        ImportBatchEntity batch = newImport();

        assertThrows(
                IllegalStateException.class,
                () -> batch.markReady(25)
        );

        assertEquals(ImportStatus.RECEIVED, batch.getStatus());
        assertEquals(0L, batch.getRowCount());
    }

    @Test
    void rejectsNegativeRowCountWithoutChangingState() {
        ImportBatchEntity batch = newImport();
        batch.startProcessing();

        assertThrows(
                IllegalArgumentException.class,
                () -> batch.markReady(-1)
        );

        assertEquals(
                ImportStatus.PROCESSING,
                batch.getStatus()
        );
        assertEquals(0L, batch.getRowCount());
    }

    @Test
    void processingCannotBeStartedTwice() {
        ImportBatchEntity batch = newImport();
        batch.startProcessing();

        assertThrows(
                IllegalStateException.class,
                batch::startProcessing
        );
    }

    @Test
    void failedImportCanBeRetriedUsingSameIdentity() {
        ImportBatchEntity batch = newImport();
        UUID originalId = batch.getId();

        batch.startProcessing();
        batch.fail();

        assertEquals(ImportStatus.FAILED, batch.getStatus());

        batch.retryProcessing();
        batch.markReady(12);

        assertEquals(originalId, batch.getId());
        assertEquals(ImportStatus.READY, batch.getStatus());
        assertEquals(12L, batch.getRowCount());
    }

    @Test
    void readyImportCannotBeChangedByLifecycleMethods() {
        ImportBatchEntity batch = newImport();
        batch.startProcessing();
        batch.markReady(25);

        assertThrows(
                IllegalStateException.class,
                batch::startProcessing
        );

        assertThrows(
                IllegalStateException.class,
                batch::retryProcessing
        );

        assertThrows(
                IllegalStateException.class,
                batch::fail
        );

        assertThrows(
                IllegalStateException.class,
                batch::reject
        );

        assertThrows(
                IllegalStateException.class,
                () -> batch.markReady(99)
        );

        assertEquals(ImportStatus.READY, batch.getStatus());
        assertEquals(25L, batch.getRowCount());
    }

    @Test
    void rejectedImportCannotBeRetriedOrCompleted() {
        ImportBatchEntity batch = newImport();
        batch.startProcessing();
        batch.reject();

        assertThrows(
                IllegalStateException.class,
                batch::retryProcessing
        );

        assertThrows(
                IllegalStateException.class,
                batch::startProcessing
        );

        assertThrows(
                IllegalStateException.class,
                batch::fail
        );

        assertThrows(
                IllegalStateException.class,
                () -> batch.markReady(25)
        );

        assertEquals(
                ImportStatus.REJECTED,
                batch.getStatus()
        );
    }

    private ImportBatchEntity newImport() {
        return new ImportBatchEntity(
                UUID.randomUUID(),
                SourceType.PROCESSOR_SETTLEMENT,
                "PROCESSOR_A",
                "settlements.csv",
                "test://imports/" + UUID.randomUUID(),
                "a".repeat(64),
                LocalDate.of(2026, 10, 20)
        );
    }
}