package com.suyog.reconbridge.application;

import com.suyog.reconbridge.application.exception.StaleImportVersionException;
import com.suyog.reconbridge.application.model.ClaimedSettlementImport;
import com.suyog.reconbridge.application.model.ImportProcessingResult;
import com.suyog.reconbridge.domain.model.SettlementBatch;
import com.suyog.reconbridge.ingestion.CsvValidationException;
import com.suyog.reconbridge.ingestion.SettlementCsvParser;
import com.suyog.reconbridge.storage.StoredFileReadException;
import com.suyog.reconbridge.storage.StoredFileReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Service
public class SettlementImportProcessingService {
    private static final Logger log = LoggerFactory.getLogger(SettlementImportProcessingService.class);
    private final SettlementImportClaimService claims;
    private final StoredFileReader reader;
    private final SettlementPersistenceService persistence;
    private final ImportLifecycleService lifecycle;
    private final SettlementCsvParser parser = new SettlementCsvParser();

    public SettlementImportProcessingService(
            SettlementImportClaimService claims,
            StoredFileReader reader,
            SettlementPersistenceService persistence,
            ImportLifecycleService lifecycle
    ) {
        this.claims = claims;
        this.reader = reader;
        this.persistence = persistence;
        this.lifecycle = lifecycle;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ImportProcessingResult process(UUID tenantId, UUID importId, long expectedVersion) {
        // Commit the claim before reading any file. A losing claim must not mark FAILED.
        ClaimedSettlementImport claim = claims.claim(tenantId, importId, expectedVersion);
        List<SettlementBatch> batches;
        try {
            byte[] verified = reader.readVerified(claim.storageUri(), claim.sha256());
            batches = parser.parse(new ByteArrayInputStream(verified), tenantId, claim.provider());
        } catch (CsvValidationException exception) {
            return new ImportProcessingResult(
                    lifecycle.reject(tenantId, importId, claim.version()),
                    "CSV_REJECTED", exception.getMessage());
        } catch (StoredFileReadException exception) {
            log.warn("Stored file failure for import {}: {}", importId, exception.getReason());
            return failed(claim, exception.getReason().name(), "Stored file verification failed.");
        } catch (IOException exception) {
            log.error("Read failure for import {}", importId, exception);
            return failed(claim, "FILE_READ_FAILED", "The file could not be read.");
        } catch (RuntimeException exception) {
            log.error("Unexpected processing failure for import {}", importId, exception);
            return failed(claim, "PROCESSING_FAILED", "Processing failed unexpectedly.");
        }

        try {
            return new ImportProcessingResult(
                    persistence.complete(tenantId, importId, claim.version(), batches),
                    "READY", "Settlement records were persisted successfully.");
        } catch (StaleImportVersionException | OptimisticLockingFailureException exception) {
            // Never change state after losing ownership of this version.
            throw exception;
        } catch (RuntimeException exception) {
            log.error("Settlement persistence failed for import {}", importId, exception);
            // complete() has exited its transaction. A failure transition uses the
            // original claimed version: an uncertain commit cannot overwrite READY.
            return failed(claim, "PERSISTENCE_FAILED", "Settlement records could not be persisted.");
        }
    }

    private ImportProcessingResult failed(ClaimedSettlementImport claim, String code, String detail) {
        // If this transition itself fails (e.g. DB outage), propagate that failure.
        // Do not report FAILED unless it was actually committed.
        return new ImportProcessingResult(
                lifecycle.fail(claim.tenantId(), claim.importId(), claim.version()), code, detail);
    }
}
