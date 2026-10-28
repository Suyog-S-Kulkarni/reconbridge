package com.suyog.reconbridge.application;

import com.suyog.reconbridge.application.exception.RunIdempotencyException;
import com.suyog.reconbridge.application.model.RunCreationResult;
import com.suyog.reconbridge.application.model.StoredReconciliationRun;
import com.suyog.reconbridge.persistence.repository.ReconciliationRunRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class IdempotentReconciliationRunService {

    private static final Pattern VALID_KEY =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    private final ReconciliationRunService runs;
    private final ReconciliationRunRequestRepository requests;

    public IdempotentReconciliationRunService(
            ReconciliationRunService runs,
            ReconciliationRunRequestRepository requests
    ) {
        this.runs = runs;
        this.requests = requests;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public RunCreationResult create(
            UUID tenantId,
            String createdBy,
            String idempotencyKey,
            UUID settlementImportId,
            UUID bankImportId
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(
                settlementImportId,
                "settlementImportId must not be null"
        );
        Objects.requireNonNull(
                bankImportId,
                "bankImportId must not be null"
        );

        validateKey(idempotencyKey);

        if (createdBy == null
                || createdBy.isBlank()
                || createdBy.length() > 255) {
            throw new IllegalArgumentException(
                    "A valid creator subject is required."
            );
        }

        String requestHash = fingerprint(
                settlementImportId,
                bankImportId
        );

        ReconciliationRunRequestRepository.RequestClaim claim =
                requests.acquireAndLock(
                        tenantId,
                        idempotencyKey,
                        requestHash
                );

        if (!claim.requestHash().equals(requestHash)) {
            throw new RunIdempotencyException(
                    409,
                    "IDEMPOTENCY_KEY_CONFLICT",
                    "This Idempotency-Key was already used "
                            + "with different import identifiers."
            );
        }

        if (claim.runId() != null) {
            StoredReconciliationRun existing = runs.get(
                    tenantId,
                    claim.runId()
            );

            return new RunCreationResult(existing, false);
        }

        /*
         * ReconciliationRunService is a separate Spring bean.
         * Its REQUIRED transaction joins this transaction.
         */
        StoredReconciliationRun created = runs.create(
                tenantId,
                createdBy,
                settlementImportId,
                bankImportId
        );

        requests.complete(
                tenantId,
                idempotencyKey,
                requestHash,
                created.id()
        );

        return new RunCreationResult(created, true);
    }

    private void validateKey(String key) {
        if (key == null || !VALID_KEY.matcher(key).matches()) {
            throw new RunIdempotencyException(
                    400,
                    "INVALID_IDEMPOTENCY_KEY",
                    "Provide an Idempotency-Key of 1 to 128 characters. "
                            + "Start with a letter or digit and use only "
                            + "letters, digits, periods, underscores, "
                            + "colons or hyphens."
            );
        }
    }

    private String fingerprint(
            UUID settlementImportId,
            UUID bankImportId
    ) {
        /*
         * Canonical UUID values make JSON whitespace and property order
         * irrelevant. The prefix identifies this request contract.
         *
         * Do not include the current matching-rule version here:
         * a retry after a deployment must still retrieve the original run.
         * That run already contains the rule version used to create it.
         */
        String canonicalRequest =
                "CREATE_RECONCILIATION_RUN_V1\n"
                        + settlementImportId
                        + "\n"
                        + bankImportId;

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(
                            canonicalRequest.getBytes(
                                    StandardCharsets.UTF_8
                            )
                    );

            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable.",
                    exception
            );
        }
    }
}