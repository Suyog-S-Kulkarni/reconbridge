package com.suyog.reconbridge.persistence.repository;

import com.suyog.reconbridge.application.exception.ExceptionCaseOperationException;
import com.suyog.reconbridge.application.model.StoredExceptionCase;
import com.suyog.reconbridge.domain.model.CaseEvidenceReference;
import com.suyog.reconbridge.domain.model.ExceptionCase;
import com.suyog.reconbridge.domain.model.ExceptionCaseStatus;
import com.suyog.reconbridge.domain.model.InvestigationNote;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ExceptionCaseRepository {

    private static final String CASE_COLUMNS = """
            tenant_id, id, run_id, item_type, evidence_id,
            reason, status, created_by, assigned_to,
            created_at_utc, updated_at_utc, version
            """;

    private final JdbcTemplate jdbc;

    public ExceptionCaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> findOutcome(
            UUID tenantId,
            UUID runId,
            CaseEvidenceReference.ItemType itemType,
            UUID evidenceId
    ) {
        List<String> outcomes = jdbc.query(
                """
                SELECT outcome
                FROM reconciliation_run_item
                WHERE tenant_id = ?
                  AND run_id = ?
                  AND item_type = ?
                  AND evidence_id = ?
                """,
                (rs, rowNumber) -> rs.getString("outcome"),
                tenantId.toString(),
                runId.toString(),
                itemType.name(),
                evidenceId.toString()
        );

        return outcomes.stream().findFirst();
    }

    public Optional<StoredExceptionCase> find(
            UUID tenantId,
            UUID caseId
    ) {
        List<StoredExceptionCase> rows = jdbc.query(
                "SELECT " + CASE_COLUMNS + """
                 FROM exception_case
                 WHERE tenant_id = ? AND id = ?
                """,
                this::mapCase,
                tenantId.toString(),
                caseId.toString()
        );

        return rows.stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public StoredExceptionCase insertOrFind(ExceptionCase candidate) {
        CaseEvidenceReference evidence = candidate.evidence();

        /*
         * Preserve an existing case on duplicate evidence.
         * Do not overwrite its creator, assignee, state or version.
         */
        jdbc.update(
                """
                INSERT INTO exception_case (
                    tenant_id, id, run_id, item_type, evidence_id,
                    reason, status, created_by, assigned_to,
                    created_at_utc, updated_at_utc, version
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                ON DUPLICATE KEY UPDATE
                    id = exception_case.id
                """,
                candidate.tenantId().toString(),
                candidate.id().toString(),
                evidence.runId().toString(),
                evidence.itemType().name(),
                evidence.evidenceId().toString(),
                evidence.reason().name(),
                candidate.status().name(),
                candidate.createdBy(),
                candidate.assignedTo(),
                utc(candidate.createdAt()),
                utc(candidate.updatedAt())
        );

        /*
         * Locking read returns the current row after a competing
         * insert finishes, including under REPEATABLE_READ.
         */
        StoredExceptionCase result = jdbc.queryForObject(
                "SELECT " + CASE_COLUMNS + """
                 FROM exception_case
                 WHERE tenant_id = ?
                   AND run_id = ?
                   AND item_type = ?
                   AND evidence_id = ?
                 FOR UPDATE
                """,
                this::mapCase,
                candidate.tenantId().toString(),
                evidence.runId().toString(),
                evidence.itemType().name(),
                evidence.evidenceId().toString()
        );

        return Objects.requireNonNull(
                result,
                "The persisted exception case must exist."
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public StoredExceptionCase update(
            ExceptionCase updated,
            long expectedVersion
    ) {
        long nextVersion = Math.addExact(expectedVersion, 1L);

        int changed = jdbc.update(
                """
                UPDATE exception_case
                SET status = ?,
                    assigned_to = ?,
                    updated_at_utc = ?,
                    version = version + 1
                WHERE tenant_id = ?
                  AND id = ?
                  AND version = ?
                """,
                updated.status().name(),
                updated.assignedTo(),
                utc(updated.updatedAt()),
                updated.tenantId().toString(),
                updated.id().toString(),
                expectedVersion
        );

        if (changed != 1) {
            throw new ExceptionCaseOperationException(
                    409,
                    "STALE_CASE_VERSION",
                    "The case changed. Reload it before retrying."
            );
        }

        return new StoredExceptionCase(updated, nextVersion);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insertNote(InvestigationNote note) {
        jdbc.update(
                """
                INSERT INTO investigation_note (
                    tenant_id, id, case_id,
                    author_subject, note_text, created_at_utc
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                note.tenantId().toString(),
                note.id().toString(),
                note.caseId().toString(),
                note.authorSubject(),
                note.text(),
                utc(note.createdAt())
        );
    }

    private StoredExceptionCase mapCase(
            ResultSet rs,
            int rowNumber
    ) throws SQLException {
        CaseEvidenceReference evidence = new CaseEvidenceReference(
                UUID.fromString(rs.getString("run_id")),
                CaseEvidenceReference.ItemType.valueOf(
                        rs.getString("item_type")
                ),
                UUID.fromString(rs.getString("evidence_id")),
                CaseEvidenceReference.Reason.valueOf(
                        rs.getString("reason")
                )
        );

        ExceptionCase details = new ExceptionCase(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("tenant_id")),
                evidence,
                ExceptionCaseStatus.valueOf(rs.getString("status")),
                rs.getString("created_by"),
                rs.getString("assigned_to"),
                rs.getObject(
                        "created_at_utc",
                        LocalDateTime.class
                ).toInstant(ZoneOffset.UTC),
                rs.getObject(
                        "updated_at_utc",
                        LocalDateTime.class
                ).toInstant(ZoneOffset.UTC)
        );

        return new StoredExceptionCase(
                details,
                rs.getLong("version")
        );
    }

    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}