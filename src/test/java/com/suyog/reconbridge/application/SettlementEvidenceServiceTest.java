package com.suyog.reconbridge.application;

import com.suyog.reconbridge.application.exception.ImportNotFoundException;
import com.suyog.reconbridge.application.exception.SettlementEvidenceException;
import com.suyog.reconbridge.application.exception.SettlementEvidenceException.Reason;
import com.suyog.reconbridge.application.model.SettlementEvidence;
import com.suyog.reconbridge.domain.model.SourceType;
import com.suyog.reconbridge.persistence.entity.ImportBatchEntity;
import com.suyog.reconbridge.persistence.repository.ImportBatchRepository;
import com.suyog.reconbridge.persistence.repository.SettlementReadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementEvidenceServiceTest {

    @Mock
    private ImportBatchRepository imports;

    @Mock
    private SettlementReadRepository settlements;

    private SettlementEvidenceService service;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SettlementEvidenceService(
                imports,
                settlements
        );
    }

    @Test
    void returnsPaginatedBatches() {
        ImportBatchEntity imported = readyImport();
        UUID importId = imported.getId();

        SettlementEvidence.Batch batch = new SettlementEvidence.Batch(
                UUID.randomUUID(),
                "PROCESSOR_A",
                "SETTLEMENT-001",
                "BANK-001",
                990,
                "INR"
        );

        when(imports.findByTenantIdAndId(tenantId, importId))
                .thenReturn(Optional.of(imported));

        when(settlements.countBatches(tenantId, importId))
                .thenReturn(3L);

        when(settlements.batches(tenantId, importId, 2, 2L))
                .thenReturn(List.of(batch));

        var result = service.batches(
                tenantId,
                importId,
                1,
                2
        );

        assertEquals(List.of(batch), result.items());
        assertEquals(1, result.page());
        assertEquals(2, result.size());
        assertEquals(3L, result.totalElements());
        assertEquals(2L, result.totalPages());
    }

    @Test
    void missingOrForeignImportStopsBeforeSettlementQueries() {
        UUID importId = UUID.randomUUID();

        when(imports.findByTenantIdAndId(tenantId, importId))
                .thenReturn(Optional.empty());

        assertThrows(
                ImportNotFoundException.class,
                () -> service.batches(tenantId, importId, 0, 20)
        );

        verifyNoInteractions(settlements);
    }

    @Test
    void receivedImportCannotExposeEvidence() {
        ImportBatchEntity imported = newImport(
                SourceType.PROCESSOR_SETTLEMENT
        );

        when(imports.findByTenantIdAndId(tenantId, imported.getId()))
                .thenReturn(Optional.of(imported));

        SettlementEvidenceException exception = assertThrows(
                SettlementEvidenceException.class,
                () -> service.batches(
                        tenantId,
                        imported.getId(),
                        0,
                        20
                )
        );

        assertEquals(Reason.IMPORT_NOT_READY, exception.getReason());
        verifyNoInteractions(settlements);
    }

    @Test
    void rejectsUnsupportedSource() {
        ImportBatchEntity imported = newImport(
                SourceType.BANK_STATEMENT
        );

        when(imports.findByTenantIdAndId(tenantId, imported.getId()))
                .thenReturn(Optional.of(imported));

        SettlementEvidenceException exception = assertThrows(
                SettlementEvidenceException.class,
                () -> service.batches(
                        tenantId,
                        imported.getId(),
                        0,
                        20
                )
        );

        assertEquals(Reason.UNSUPPORTED_SOURCE, exception.getReason());
        verifyNoInteractions(settlements);
    }

    @Test
    void rejectsInvalidPaginationBeforeDatabaseAccess() {
        int[][] invalidValues = {
                {-1, 20},
                {0, 0},
                {0, 101}
        };

        for (int[] values : invalidValues) {
            SettlementEvidenceException exception = assertThrows(
                    SettlementEvidenceException.class,
                    () -> service.batches(
                            tenantId,
                            UUID.randomUUID(),
                            values[0],
                            values[1]
                    )
            );

            assertEquals(
                    Reason.INVALID_PAGINATION,
                    exception.getReason()
            );
        }

        verifyNoInteractions(imports, settlements);
    }

    @Test
    void rejectsBatchOutsideRequestedImport() {
        ImportBatchEntity imported = readyImport();
        UUID batchId = UUID.randomUUID();

        when(imports.findByTenantIdAndId(tenantId, imported.getId()))
                .thenReturn(Optional.of(imported));

        when(settlements.batchExists(
                tenantId,
                imported.getId(),
                batchId
        )).thenReturn(false);

        SettlementEvidenceException exception = assertThrows(
                SettlementEvidenceException.class,
                () -> service.lines(
                        tenantId,
                        imported.getId(),
                        batchId,
                        0,
                        20
                )
        );

        assertEquals(Reason.BATCH_NOT_FOUND, exception.getReason());
    }

    @Test
    void returnsLinesUsingTenantImportAndBatchScope() {
        ImportBatchEntity imported = readyImport();
        UUID importId = imported.getId();
        UUID batchId = UUID.randomUUID();

        SettlementEvidence.Line line = new SettlementEvidence.Line(
                UUID.randomUUID(),
                batchId,
                "PAYMENT-001",
                1000,
                10,
                990,
                "INR"
        );

        when(imports.findByTenantIdAndId(tenantId, importId))
                .thenReturn(Optional.of(imported));

        when(settlements.batchExists(tenantId, importId, batchId))
                .thenReturn(true);

        when(settlements.countLines(tenantId, importId, batchId))
                .thenReturn(1L);

        when(settlements.lines(
                tenantId,
                importId,
                batchId,
                20,
                0L
        )).thenReturn(List.of(line));

        var result = service.lines(
                tenantId,
                importId,
                batchId,
                0,
                20
        );

        assertEquals(List.of(line), result.items());
        assertEquals(1L, result.totalElements());
        assertEquals(1L, result.totalPages());

        verify(settlements).lines(
                tenantId,
                importId,
                batchId,
                20,
                0L
        );
    }

    @Test
    void returnsEmptyItemsForPageBeyondAvailableResults() {
        ImportBatchEntity imported = readyImport();
        UUID importId = imported.getId();

        when(imports.findByTenantIdAndId(tenantId, importId))
                .thenReturn(Optional.of(imported));

        when(settlements.countBatches(tenantId, importId))
                .thenReturn(1L);

        when(settlements.batches(tenantId, importId, 20, 200L))
                .thenReturn(List.of());

        var result = service.batches(
                tenantId,
                importId,
                10,
                20
        );

        assertEquals(List.of(), result.items());
        assertEquals(1L, result.totalElements());
        assertEquals(1L, result.totalPages());
    }

    private ImportBatchEntity readyImport() {
        ImportBatchEntity imported = newImport(
                SourceType.PROCESSOR_SETTLEMENT
        );

        imported.startProcessing();
        imported.markReady(1);

        return imported;
    }

    private ImportBatchEntity newImport(SourceType sourceType) {
        return new ImportBatchEntity(
                tenantId,
                sourceType,
                "PROCESSOR_A",
                "settlements.csv",
                "test://settlements.csv",
                "a".repeat(64),
                LocalDate.of(2026, 10, 22)
        );
    }
}