package com.suyog.reconbridge.persistence.repository;

import com.suyog.reconbridge.application.exception.ExceptionCaseOperationException;
import com.suyog.reconbridge.domain.model.ResolutionProposal;
import com.suyog.reconbridge.domain.model.ResolutionProposalStatus;
import com.suyog.reconbridge.domain.model.ResolutionType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ResolutionProposalRepository {

    private final JdbcTemplate jdbc;

    public ResolutionProposalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ResolutionProposal> find(
            UUID tenantId,
            UUID caseId,
            UUID proposalId
    ) {
        return jdbc.query(
                """
                SELECT
                    tenant_id, id, case_id, resolution_type,
                    rationale, proposed_by, proposed_at_utc,
                    status, decided_by, decision_comment,
                    decided_at_utc
                FROM resolution_proposal
                WHERE tenant_id = ?
                  AND case_id = ?
                  AND id = ?
                """,
                this::mapProposal,
                tenantId.toString(),
                caseId.toString(),
                proposalId.toString()
        ).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(ResolutionProposal proposal) {
        if (proposal.status() != ResolutionProposalStatus.PENDING) {
            throw new IllegalArgumentException(
                    "Only a new PENDING proposal can be inserted."
            );
        }

        jdbc.update(
                """
                INSERT INTO resolution_proposal (
                    tenant_id, id, case_id, resolution_type,
                    rationale, proposed_by, proposed_at_utc,
                    status
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                proposal.tenantId().toString(),
                proposal.id().toString(),
                proposal.caseId().toString(),
                proposal.resolutionType().name(),
                proposal.rationale(),
                proposal.proposedBy(),
                utc(proposal.proposedAt()),
                proposal.status().name()
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordDecision(ResolutionProposal proposal) {
        if (proposal.status() == ResolutionProposalStatus.PENDING) {
            throw new IllegalArgumentException(
                    "A terminal proposal decision is required."
            );
        }

        int changed = jdbc.update(
                """
                UPDATE resolution_proposal
                SET status = ?,
                    decided_by = ?,
                    decision_comment = ?,
                    decided_at_utc = ?
                WHERE tenant_id = ?
                  AND case_id = ?
                  AND id = ?
                  AND status = 'PENDING'
                """,
                proposal.status().name(),
                proposal.decidedBy(),
                proposal.decisionComment(),
                utc(proposal.decidedAt()),
                proposal.tenantId().toString(),
                proposal.caseId().toString(),
                proposal.id().toString()
        );

        if (changed != 1) {
            throw new ExceptionCaseOperationException(
                    409,
                    "PROPOSAL_NOT_PENDING",
                    "The proposal has already been decided."
            );
        }
    }

    private ResolutionProposal mapProposal(
            ResultSet rs,
            int rowNumber
    ) throws SQLException {
        LocalDateTime decidedAt = rs.getObject(
                "decided_at_utc",
                LocalDateTime.class
        );

        return new ResolutionProposal(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("tenant_id")),
                UUID.fromString(rs.getString("case_id")),
                ResolutionType.valueOf(rs.getString("resolution_type")),
                rs.getString("rationale"),
                rs.getString("proposed_by"),
                rs.getObject(
                        "proposed_at_utc",
                        LocalDateTime.class
                ).toInstant(ZoneOffset.UTC),
                ResolutionProposalStatus.valueOf(rs.getString("status")),
                rs.getString("decided_by"),
                rs.getString("decision_comment"),
                decidedAt == null
                        ? null
                        : decidedAt.toInstant(ZoneOffset.UTC)
        );
    }

    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}