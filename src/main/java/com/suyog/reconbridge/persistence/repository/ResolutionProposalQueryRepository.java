package com.suyog.reconbridge.persistence.repository;

import com.suyog.reconbridge.application.model.ResolutionProposalReadModels.PendingApproval;
import com.suyog.reconbridge.application.model.ResolutionProposalReadModels.ProposalSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
public class ResolutionProposalQueryRepository {

    /*
     * Reused by count and page queries so both apply identical filters.
     * Subject comparison is exact, matching the service's identity rule.
     */
    private static final String PENDING_FROM_AND_WHERE = """
            FROM resolution_proposal p
            JOIN exception_case c
              ON c.tenant_id = p.tenant_id
             AND c.id = p.case_id
            WHERE p.tenant_id = ?
              AND p.status = 'PENDING'
              AND c.status = 'AWAITING_APPROVAL'
              AND CAST(p.proposed_by AS BINARY) <> CAST(? AS BINARY)
            """;

    private final JdbcTemplate jdbc;

    public ResolutionProposalQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean caseExists(UUID tenantId, UUID caseId) {
        Long count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM exception_case
                WHERE tenant_id = ?
                  AND id = ?
                """,
                Long.class,
                tenantId.toString(),
                caseId.toString()
        );

        return count != null && count > 0;
    }

    public long countHistory(UUID tenantId, UUID caseId) {
        Long count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM resolution_proposal
                WHERE tenant_id = ?
                  AND case_id = ?
                """,
                Long.class,
                tenantId.toString(),
                caseId.toString()
        );

        return count == null ? 0 : count;
    }

    public List<ProposalSummary> findHistory(
            UUID tenantId,
            UUID caseId,
            int size,
            long offset
    ) {
        return jdbc.query(
                """
                SELECT
                    id,
                    case_id,
                    resolution_type,
                    rationale,
                    proposed_by,
                    proposed_at_utc,
                    status,
                    decided_by,
                    decision_comment,
                    decided_at_utc
                FROM resolution_proposal
                WHERE tenant_id = ?
                  AND case_id = ?
                ORDER BY proposed_at_utc DESC, id DESC
                LIMIT ? OFFSET ?
                """,
                this::mapProposal,
                tenantId.toString(),
                caseId.toString(),
                size,
                offset
        );
    }

    public long countPending(
            UUID tenantId,
            String actorSubject
    ) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) " + PENDING_FROM_AND_WHERE,
                Long.class,
                tenantId.toString(),
                actorSubject
        );

        return count == null ? 0 : count;
    }

    public List<PendingApproval> findPending(
            UUID tenantId,
            String actorSubject,
            int size,
            long offset
    ) {
        String sql = """
                SELECT
                    p.id AS proposal_id,
                    p.case_id,
                    c.run_id,
                    c.item_type,
                    c.evidence_id,
                    c.reason,
                    p.resolution_type,
                    p.rationale,
                    p.proposed_by,
                    p.proposed_at_utc,
                    c.assigned_to,
                    c.version AS case_version
                """
                + PENDING_FROM_AND_WHERE
                + """
                
                ORDER BY p.proposed_at_utc DESC, p.id DESC
                LIMIT ? OFFSET ?
                """;

        return jdbc.query(
                sql,
                this::mapPending,
                tenantId.toString(),
                actorSubject,
                size,
                offset
        );
    }

    private ProposalSummary mapProposal(
            ResultSet rs,
            int rowNumber
    ) throws SQLException {
        return new ProposalSummary(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("case_id")),
                rs.getString("resolution_type"),
                rs.getString("rationale"),
                rs.getString("proposed_by"),
                utc(rs, "proposed_at_utc"),
                rs.getString("status"),
                rs.getString("decided_by"),
                rs.getString("decision_comment"),
                utc(rs, "decided_at_utc")
        );
    }

    private PendingApproval mapPending(
            ResultSet rs,
            int rowNumber
    ) throws SQLException {
        return new PendingApproval(
                UUID.fromString(rs.getString("proposal_id")),
                UUID.fromString(rs.getString("case_id")),
                UUID.fromString(rs.getString("run_id")),
                rs.getString("item_type"),
                UUID.fromString(rs.getString("evidence_id")),
                rs.getString("reason"),
                rs.getString("resolution_type"),
                rs.getString("rationale"),
                rs.getString("proposed_by"),
                utc(rs, "proposed_at_utc"),
                rs.getString("assigned_to"),
                rs.getLong("case_version")
        );
    }

    private Instant utc(
            ResultSet rs,
            String column
    ) throws SQLException {
        LocalDateTime value = rs.getObject(
                column,
                LocalDateTime.class
        );

        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}