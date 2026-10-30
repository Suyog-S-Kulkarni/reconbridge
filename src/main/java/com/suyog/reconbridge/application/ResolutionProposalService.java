package com.suyog.reconbridge.application;

import com.suyog.reconbridge.application.exception.ExceptionCaseOperationException;
import com.suyog.reconbridge.application.model.ResolutionProposalResult;
import com.suyog.reconbridge.application.model.StoredExceptionCase;
import com.suyog.reconbridge.domain.model.ExceptionCase;
import com.suyog.reconbridge.domain.model.ExceptionCaseStatus;
import com.suyog.reconbridge.domain.model.ResolutionProposal;
import com.suyog.reconbridge.domain.model.ResolutionProposalStatus;
import com.suyog.reconbridge.domain.model.ResolutionType;
import com.suyog.reconbridge.persistence.repository.ExceptionCaseRepository;
import com.suyog.reconbridge.persistence.repository.ResolutionProposalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class ResolutionProposalService {

    private final ExceptionCaseRepository cases;
    private final ResolutionProposalRepository proposals;

    public ResolutionProposalService(
            ExceptionCaseRepository cases,
            ResolutionProposalRepository proposals
    ) {
        this.cases = cases;
        this.proposals = proposals;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public ResolutionProposalResult submit(
            UUID tenantId,
            UUID caseId,
            long expectedVersion,
            String actor,
            ResolutionType resolutionType,
            String rationale
    ) {
        requireSubject(actor);

        StoredExceptionCase stored = requireCase(tenantId, caseId);
        requireVersion(stored, expectedVersion);

        ExceptionCase current = stored.details();

        if (current.status() != ExceptionCaseStatus.IN_REVIEW) {
            throw problem(
                    409,
                    "CASE_NOT_IN_REVIEW",
                    "Only an IN_REVIEW case can receive a proposal."
            );
        }

        if (!actor.equals(current.assignedTo())) {
            throw problem(
                    403,
                    "CASE_NOT_ASSIGNED_TO_ACTOR",
                    "Only the assigned analyst can submit a proposal."
            );
        }

        if (resolutionType == null) {
            throw problem(
                    400,
                    "INVALID_RESOLUTION_TYPE",
                    "A resolution type is required."
            );
        }

        requireText(rationale, "INVALID_PROPOSAL_RATIONALE");

        Instant timestamp = operationTime(current.updatedAt());

        ResolutionProposal proposal = ResolutionProposal.propose(
                tenantId,
                caseId,
                resolutionType,
                rationale,
                actor,
                timestamp
        );

        StoredExceptionCase updated = cases.update(
                current.awaitApproval(actor, timestamp),
                expectedVersion
        );

        proposals.insert(proposal);

        return new ResolutionProposalResult(updated, proposal);
    }

    @Transactional(
            readOnly = true,
            isolation = Isolation.REPEATABLE_READ
    )
    public ResolutionProposal get(
            UUID tenantId,
            UUID caseId,
            UUID proposalId
    ) {
        requireCase(tenantId, caseId);
        return requireProposal(tenantId, caseId, proposalId);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public ResolutionProposalResult approve(
            UUID tenantId,
            UUID caseId,
            UUID proposalId,
            long expectedVersion,
            String actor,
            String comment
    ) {
        return decide(
                tenantId,
                caseId,
                proposalId,
                expectedVersion,
                actor,
                comment,
                true
        );
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public ResolutionProposalResult reject(
            UUID tenantId,
            UUID caseId,
            UUID proposalId,
            long expectedVersion,
            String actor,
            String comment
    ) {
        return decide(
                tenantId,
                caseId,
                proposalId,
                expectedVersion,
                actor,
                comment,
                false
        );
    }

    private ResolutionProposalResult decide(
            UUID tenantId,
            UUID caseId,
            UUID proposalId,
            long expectedVersion,
            String actor,
            String comment,
            boolean approve
    ) {
        requireSubject(actor);

        StoredExceptionCase stored = requireCase(tenantId, caseId);
        ResolutionProposal proposal = requireProposal(
                tenantId,
                caseId,
                proposalId
        );

        requireVersion(stored, expectedVersion);

        if (actor.equals(proposal.proposedBy())) {
            throw problem(
                    403,
                    "SELF_APPROVAL_FORBIDDEN",
                    "The proposer cannot approve or reject their own proposal."
            );
        }

        if (proposal.status() != ResolutionProposalStatus.PENDING) {
            throw problem(
                    409,
                    "PROPOSAL_NOT_PENDING",
                    "The proposal has already been decided."
            );
        }

        ExceptionCase current = stored.details();

        if (current.status() != ExceptionCaseStatus.AWAITING_APPROVAL) {
            throw problem(
                    409,
                    "CASE_NOT_AWAITING_APPROVAL",
                    "The case is not awaiting a decision."
            );
        }

        requireText(comment, "INVALID_DECISION_COMMENT");

        Instant earliest = current.updatedAt().isAfter(proposal.proposedAt())
                ? current.updatedAt()
                : proposal.proposedAt();

        Instant timestamp = operationTime(earliest);

        ResolutionProposal decided = approve
                ? proposal.approve(actor, comment, timestamp)
                : proposal.reject(actor, comment, timestamp);

        ExceptionCase changedCase = approve
                ? current.resolve(timestamp)
                : current.resumeInvestigation(timestamp);

        /*
         * The version-checked UPDATE acquires the case row's write lock.
         * A competing operation using the same version cannot also succeed.
         */
        StoredExceptionCase updated = cases.update(
                changedCase,
                expectedVersion
        );

        /*
         * A failure here rolls back the case update as well.
         */
        proposals.recordDecision(decided);

        return new ResolutionProposalResult(updated, decided);
    }

    private StoredExceptionCase requireCase(
            UUID tenantId,
            UUID caseId
    ) {
        if (tenantId == null || caseId == null) {
            throw problem(
                    400,
                    "INVALID_CASE_REQUEST",
                    "Tenant and case ID are required."
            );
        }

        return cases.find(tenantId, caseId)
                .orElseThrow(() -> problem(
                        404,
                        "CASE_NOT_FOUND",
                        "Exception case not found."
                ));
    }

    private ResolutionProposal requireProposal(
            UUID tenantId,
            UUID caseId,
            UUID proposalId
    ) {
        if (proposalId == null) {
            throw problem(
                    400,
                    "INVALID_PROPOSAL_REQUEST",
                    "Proposal ID is required."
            );
        }

        return proposals.find(tenantId, caseId, proposalId)
                .orElseThrow(() -> problem(
                        404,
                        "PROPOSAL_NOT_FOUND",
                        "Resolution proposal not found."
                ));
    }

    private void requireVersion(
            StoredExceptionCase stored,
            long expectedVersion
    ) {
        if (expectedVersion < 0) {
            throw problem(
                    400,
                    "INVALID_CASE_VERSION",
                    "Expected version must not be negative."
            );
        }

        if (stored.version() != expectedVersion) {
            throw problem(
                    409,
                    "STALE_CASE_VERSION",
                    "The case changed. Reload it before retrying."
            );
        }
    }

    private void requireSubject(String actor) {
        if (actor == null || actor.isBlank() || actor.length() > 255) {
            throw problem(
                    400,
                    "INVALID_CASE_ACTOR",
                    "A valid actor subject is required."
            );
        }
    }

    private void requireText(String text, String code) {
        if (text == null
                || text.isBlank()
                || text.strip().length() > ResolutionProposal.MAX_TEXT_LENGTH) {
            throw problem(
                    400,
                    code,
                    "Provide nonblank text of at most 4000 characters."
            );
        }
    }

    private Instant operationTime(Instant earliest) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        // Prevent a backwards clock adjustment from reversing case timestamps.
        return now.isBefore(earliest) ? earliest : now;
    }

    private ExceptionCaseOperationException problem(
            int status,
            String code,
            String message
    ) {
        return new ExceptionCaseOperationException(status, code, message);
    }
}