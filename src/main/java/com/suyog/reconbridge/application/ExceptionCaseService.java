package com.suyog.reconbridge.application;

import com.suyog.reconbridge.application.exception.ExceptionCaseOperationException;
import com.suyog.reconbridge.application.model.CaseNoteResult;
import com.suyog.reconbridge.application.model.StoredExceptionCase;
import com.suyog.reconbridge.domain.model.CaseEvidenceReference;
import com.suyog.reconbridge.domain.model.ExceptionCase;
import com.suyog.reconbridge.domain.model.ExceptionCaseStatus;
import com.suyog.reconbridge.domain.model.InvestigationNote;
import com.suyog.reconbridge.domain.model.MatchOutcome;
import com.suyog.reconbridge.persistence.repository.ExceptionCaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class ExceptionCaseService {

    private final ExceptionCaseRepository repository;

    public ExceptionCaseService(ExceptionCaseRepository repository) {
        this.repository = repository;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public StoredExceptionCase open(
            UUID tenantId,
            String actor,
            UUID runId,
            CaseEvidenceReference.ItemType itemType,
            UUID evidenceId
    ) {
        requireSubject(actor);

        if (tenantId == null
                || runId == null
                || itemType == null
                || evidenceId == null) {
            throw problem(
                    400,
                    "INVALID_CASE_REQUEST",
                    "Tenant, run, item type and evidence ID are required."
            );
        }

        String outcome = repository.findOutcome(
                tenantId,
                runId,
                itemType,
                evidenceId
        ).orElseThrow(
                () -> problem(
                        404,
                        "CASE_EVIDENCE_NOT_FOUND",
                        "The selected reconciliation result was not found."
                )
        );

        CaseEvidenceReference evidence;

        if (itemType == CaseEvidenceReference.ItemType.BATCH) {
            MatchOutcome matchOutcome = MatchOutcome.valueOf(outcome);

            if (matchOutcome == MatchOutcome.MATCHED) {
                throw problem(
                        409,
                        "CASE_NOT_REQUIRED",
                        "A matched result cannot open an exception case."
                );
            }

            evidence = CaseEvidenceReference.forBatch(
                    runId,
                    evidenceId,
                    matchOutcome
            );
        } else {
            if (!"ORPHAN_BANK_CREDIT".equals(outcome)) {
                throw new IllegalStateException(
                        "Stored orphan evidence has an invalid outcome."
                );
            }

            evidence = CaseEvidenceReference.forOrphan(
                    runId,
                    evidenceId
            );
        }

        ExceptionCase candidate = ExceptionCase.open(
                tenantId,
                evidence,
                actor,
                now()
        );

        return repository.insertOrFind(candidate);
    }

    @Transactional(
            readOnly = true,
            isolation = Isolation.REPEATABLE_READ
    )
    public StoredExceptionCase get(
            UUID tenantId,
            UUID caseId
    ) {
        return requireCase(tenantId, caseId);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public StoredExceptionCase claim(
            UUID tenantId,
            UUID caseId,
            long expectedVersion,
            String actor
    ) {
        requireSubject(actor);

        StoredExceptionCase stored = requireCase(tenantId, caseId);
        requireVersion(stored, expectedVersion);

        if (stored.details().status() != ExceptionCaseStatus.OPEN) {
            throw problem(
                    409,
                    "CASE_NOT_OPEN",
                    "Only an OPEN case can be claimed."
            );
        }

        ExceptionCase claimed = stored.details().claim(
                actor,
                now()
        );

        return repository.update(claimed, expectedVersion);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public CaseNoteResult addNote(
            UUID tenantId,
            UUID caseId,
            long expectedVersion,
            String actor,
            String text
    ) {
        requireSubject(actor);

        StoredExceptionCase stored = requireCase(tenantId, caseId);
        requireVersion(stored, expectedVersion);

        ExceptionCase current = stored.details();

        if (current.status() != ExceptionCaseStatus.IN_REVIEW) {
            throw problem(
                    409,
                    "CASE_NOT_IN_REVIEW",
                    "Claim the case before adding investigation notes."
            );
        }

        if (!actor.equals(current.assignedTo())) {
            throw problem(
                    403,
                    "CASE_NOT_ASSIGNED_TO_ACTOR",
                    "Only the assigned analyst can add investigation notes."
            );
        }

        if (text == null
                || text.isBlank()
                || text.strip().length()
                > InvestigationNote.MAX_TEXT_LENGTH) {
            throw problem(
                    400,
                    "INVALID_CASE_NOTE",
                    "Provide a nonblank note of at most 4000 characters."
            );
        }

        Instant timestamp = now();

        InvestigationNote note = current.addNote(
                actor,
                text,
                timestamp
        );

        /*
         * The domain object remains immutable.
         * Persist a new case value recording the activity timestamp.
         */
        ExceptionCase touched = new ExceptionCase(
                current.id(),
                current.tenantId(),
                current.evidence(),
                current.status(),
                current.createdBy(),
                current.assignedTo(),
                current.createdAt(),
                timestamp
        );

        /*
         * Guard the case version before inserting the note.
         * Both operations belong to this same transaction.
         */
        StoredExceptionCase updated = repository.update(
                touched,
                expectedVersion
        );

        repository.insertNote(note);

        return new CaseNoteResult(updated, note);
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

        return repository.find(tenantId, caseId)
                .orElseThrow(
                        () -> problem(
                                404,
                                "CASE_NOT_FOUND",
                                "Exception case not found."
                        )
                );
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
        if (actor == null
                || actor.isBlank()
                || actor.length() > 255) {
            throw problem(
                    400,
                    "INVALID_CASE_ACTOR",
                    "A valid actor subject is required."
            );
        }
    }

    private Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private ExceptionCaseOperationException problem(
            int status,
            String code,
            String message
    ) {
        return new ExceptionCaseOperationException(
                status,
                code,
                message
        );
    }
}