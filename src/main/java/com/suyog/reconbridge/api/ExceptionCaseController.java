package com.suyog.reconbridge.api;

import com.suyog.reconbridge.application.ExceptionCaseService;
import com.suyog.reconbridge.application.model.CaseNoteResult;
import com.suyog.reconbridge.application.model.StoredExceptionCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/exception-cases")
public class ExceptionCaseController {

    private final ExceptionCaseService service;

    public ExceptionCaseController(ExceptionCaseService service) {
        this.service = service;
    }

    @PreAuthorize("hasRole('ANALYST')")
    @PostMapping
    public ResponseEntity<StoredExceptionCase> open(
            @Valid @RequestBody ExceptionCaseRequests.Open request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        StoredExceptionCase result = service.open(
                tenantId(jwt),
                jwt.getSubject(),
                request.runId(),
                request.itemType(),
                request.evidenceId()
        );

        return ResponseEntity
                .ok()
                .location(
                        URI.create(
                                "/api/exception-cases/"
                                        + result.details().id()
                        )
                )
                .body(result);
    }

    @PreAuthorize(
            "hasAnyRole('IMPORTER', 'ANALYST', 'APPROVER', 'AUDITOR')"
    )
    @GetMapping("/{caseId}")
    public StoredExceptionCase get(
            @PathVariable("caseId") UUID caseId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return service.get(
                tenantId(jwt),
                caseId
        );
    }

    @PreAuthorize("hasRole('ANALYST')")
    @PostMapping("/{caseId}/claim")
    public StoredExceptionCase claim(
            @PathVariable("caseId") UUID caseId,
            @Valid @RequestBody ExceptionCaseRequests.Claim request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return service.claim(
                tenantId(jwt),
                caseId,
                request.expectedVersion(),
                jwt.getSubject()
        );
    }

    @PreAuthorize("hasRole('ANALYST')")
    @PostMapping("/{caseId}/notes")
    public ResponseEntity<CaseNoteResult> addNote(
            @PathVariable("caseId") UUID caseId,
            @Valid @RequestBody ExceptionCaseRequests.AddNote request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        CaseNoteResult result = service.addNote(
                tenantId(jwt),
                caseId,
                request.expectedVersion(),
                jwt.getSubject(),
                request.text()
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(result);
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(
                jwt.getClaimAsString("tenant_id")
        );
    }
}