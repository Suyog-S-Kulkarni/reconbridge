package com.suyog.reconbridge.application;

import com.suyog.reconbridge.application.exception.StaleImportVersionException;
import com.suyog.reconbridge.application.model.ClaimedBankImport;
import com.suyog.reconbridge.application.model.ImportProcessingResult;
import com.suyog.reconbridge.domain.model.BankCredit;
import com.suyog.reconbridge.ingestion.BankCsvValidationException;
import com.suyog.reconbridge.ingestion.BankStatementCsvParser;
import com.suyog.reconbridge.storage.StoredFileReadException;
import com.suyog.reconbridge.storage.StoredFileReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Service
public class BankImportProcessingService {

    private static final Logger log =
            LoggerFactory.getLogger(BankImportProcessingService.class);

    private final BankImportClaimService claims;
    private final StoredFileReader reader;
    private final BankCreditPersistenceService persistence;
    private final ImportLifecycleService lifecycle;

    private final BankStatementCsvParser parser =
            new BankStatementCsvParser();

    public BankImportProcessingService(
            BankImportClaimService claims,
            StoredFileReader reader,
            BankCreditPersistenceService persistence,
            ImportLifecycleService lifecycle
    ) {
        this.claims = claims;
        this.reader = reader;
        this.persistence = persistence;
        this.lifecycle = lifecycle;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ImportProcessingResult process(
            UUID tenantId,
            UUID importId,
            long expectedVersion
    ) {
        /*
         * Claim errors propagate directly.
         * A worker that never acquired the import must not change its state.
         */
        ClaimedBankImport claim = claims.claim(
                tenantId,
                importId,
                expectedVersion
        );

        List<BankCredit> credits;

        try {
            byte[] verifiedContents = reader.readVerified(
                    claim.storageUri(),
                    claim.sha256()
            );

            credits = parser.parse(
                    verifiedContents,
                    claim.tenantId()
            );

        } catch (BankCsvValidationException exception) {
            return new ImportProcessingResult(
                    lifecycle.reject(
                            tenantId,
                            importId,
                            claim.version()
                    ),
                    "CSV_REJECTED",
                    exception.getMessage()
            );

        } catch (StoredFileReadException exception) {
            log.warn(
                    "Stored file verification failed for bank import {}: {}",
                    importId,
                    exception.getReason()
            );

            return failed(
                    claim,
                    exception.getReason().name(),
                    "Stored file verification failed."
            );

        } catch (IOException exception) {
            log.error(
                    "File read failed for bank import {}",
                    importId,
                    exception
            );

            return failed(
                    claim,
                    "FILE_READ_FAILED",
                    "The bank statement file could not be read."
            );

        } catch (RuntimeException exception) {
            log.error(
                    "Unexpected bank processing failure for import {}",
                    importId,
                    exception
            );

            return failed(
                    claim,
                    "PROCESSING_FAILED",
                    "Bank statement processing failed unexpectedly."
            );
        }

        try {
            return new ImportProcessingResult(
                    persistence.complete(
                            tenantId,
                            importId,
                            claim.version(),
                            credits
                    ),
                    "READY",
                    "Bank credits were persisted successfully."
            );

        } catch (StaleImportVersionException
                 | OptimisticLockingFailureException exception) {
            /*
             * Ownership of the claimed version was lost.
             * Never overwrite the newer state.
             */
            throw exception;

        } catch (RuntimeException exception) {
            log.error(
                    "Bank credit persistence failed for import {}",
                    importId,
                    exception
            );

            /*
             * complete() has exited its transaction before this catch runs.
             * Its inserts and READY transition have rolled back on failure.
             */
            return failed(
                    claim,
                    "PERSISTENCE_FAILED",
                    "Bank credits could not be persisted."
            );
        }
    }

    private ImportProcessingResult failed(
            ClaimedBankImport claim,
            String code,
            String detail
    ) {
        return new ImportProcessingResult(
                lifecycle.fail(
                        claim.tenantId(),
                        claim.importId(),
                        claim.version()
                ),
                code,
                detail
        );
    }
}