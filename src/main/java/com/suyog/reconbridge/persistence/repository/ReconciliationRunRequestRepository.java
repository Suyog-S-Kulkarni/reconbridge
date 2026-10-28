package com.suyog.reconbridge.persistence.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Repository
public class ReconciliationRunRequestRepository {

    private final JdbcTemplate jdbc;

    public ReconciliationRunRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public RequestClaim acquireAndLock(
            UUID tenantId,
            String idempotencyKey,
            String requestHash
    ) {
        /*
         * Insert a new claim or lock the existing claim.
         *
         * On a duplicate key, deliberately retain the original hash.
         * Replacing it would hide conflicting requests.
         *
         * We do not use the affected-row count to detect ownership:
         * its value can depend on JDBC/MySQL connection settings.
         */
        jdbc.update(
                """
                INSERT INTO reconciliation_run_request (
                    tenant_id,
                    idempotency_key,
                    request_hash,
                    run_id
                )
                VALUES (?, ?, ?, NULL)
                ON DUPLICATE KEY UPDATE
                    request_hash =
                        reconciliation_run_request.request_hash
                """,
                tenantId.toString(),
                idempotencyKey,
                requestHash
        );

        /*
         * A locking read sees the current committed claim after any
         * competing transaction finishes, rather than an older snapshot.
         */
        RequestClaim claim = jdbc.queryForObject(
                """
                SELECT request_hash, run_id
                FROM reconciliation_run_request
                WHERE tenant_id = ?
                  AND idempotency_key = ?
                FOR UPDATE
                """,
                (rs, rowNumber) -> {
                    String runId = rs.getString("run_id");

                    return new RequestClaim(
                            rs.getString("request_hash"),
                            runId == null
                                    ? null
                                    : UUID.fromString(runId)
                    );
                },
                tenantId.toString(),
                idempotencyKey
        );

        return Objects.requireNonNull(
                claim,
                "The idempotency claim must exist after acquisition."
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(
            UUID tenantId,
            String idempotencyKey,
            String requestHash,
            UUID runId
    ) {
        int updated = jdbc.update(
                """
                UPDATE reconciliation_run_request
                SET run_id = ?
                WHERE tenant_id = ?
                  AND idempotency_key = ?
                  AND request_hash = ?
                  AND run_id IS NULL
                """,
                runId.toString(),
                tenantId.toString(),
                idempotencyKey,
                requestHash
        );

        if (updated != 1) {
            throw new IllegalStateException(
                    "The idempotency claim could not be completed."
            );
        }
    }

    public record RequestClaim(
            String requestHash,
            UUID runId
    ) {
    }
}