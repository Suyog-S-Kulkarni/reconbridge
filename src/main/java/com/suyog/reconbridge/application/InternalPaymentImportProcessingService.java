package com.suyog.reconbridge.application;

import com.suyog.reconbridge.application.exception.StaleImportVersionException;
import com.suyog.reconbridge.application.model.ClaimedInternalPaymentImport;
import com.suyog.reconbridge.application.model.ImportProcessingResult;
import com.suyog.reconbridge.domain.model.PaymentRecord;
import com.suyog.reconbridge.ingestion.InternalPaymentCsvParser;
import com.suyog.reconbridge.ingestion.PaymentCsvValidationException;
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
public class InternalPaymentImportProcessingService {

    private static final Logger log = LoggerFactory.getLogger(
            InternalPaymentImportProcessingService.class
    );

    private final InternalPaymentImportClaimService claims;
    private final StoredFileReader reader;
    private final InternalPaymentPersistenceService persistence;
    private final ImportLifecycleService lifecycle;

    private final InternalPaymentCsvParser parser =
            new InternalPaymentCsvParser();

    public InternalPaymentImportProcessingService(
            InternalPaymentImportClaimService claims,
            StoredFileReader reader,
            InternalPaymentPersistenceService persistence,
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
         * Claim failures propagate directly.
         * A request that did not acquire the import must not
         * reject it or mark it FAILED.
         */
        ClaimedInternalPaymentImport claim = claims.claim(
                tenantId,
                importId,
                expectedVersion
        );

        List<PaymentRecord> payments;

        try {
            byte[] verifiedContents = reader.readVerified(
                    claim.storageUri(),
                    claim.sha256()
            );

            payments = parser.parse(
                    verifiedContents,
                    claim.tenantId(),
                    claim.provider()
            );

        } catch (PaymentCsvValidationException exception) {
            return new ImportProcessingResult(
                    lifecycle.reject(
                            claim.tenantId(),
                            claim.importId(),
                            claim.version()
                    ),
                    "CSV_REJECTED",
                    exception.getMessage()
            );

        } catch (StoredFileReadException exception) {
            log.warn(
                    "Stored file verification failed for payment import {}: {}",
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
                    "File read failed for payment import {}",
                    importId,
                    exception
            );

            return failed(
                    claim,
                    "FILE_READ_FAILED",
                    "The internal payment file could not be read."
            );

        } catch (RuntimeException exception) {
            log.error(
                    "Unexpected payment processing failure for import {}",
                    importId,
                    exception
            );

            return failed(
                    claim,
                    "PROCESSING_FAILED",
                    "Internal payment processing failed unexpectedly."
            );
        }

        try {
            return new ImportProcessingResult(
                    persistence.complete(
                            claim.tenantId(),
                            claim.importId(),
                            claim.version(),
                            payments
                    ),
                    "READY",
                    "Internal payments were persisted successfully."
            );

        } catch (StaleImportVersionException
                 | OptimisticLockingFailureException exception) {
            // The claimed version is no longer ours to advance.
            throw exception;

        } catch (RuntimeException exception) {
            log.error(
                    "Payment persistence failed for import {}",
                    importId,
                    exception
            );

            /*
             * The persistence service's transaction has exited
             * and rolled back before this catch executes.
             */
            return failed(
                    claim,
                    "PERSISTENCE_FAILED",
                    "Internal payments could not be persisted."
            );
        }
    }

    private ImportProcessingResult failed(
            ClaimedInternalPaymentImport claim,
            String code,
            String detail
    ) {
        /*
         * This transition still requires the claimed version.
         * A stale failure must not overwrite newer import state.
         */
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