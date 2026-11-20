package com.suyog.reconbridge.api;

import com.suyog.reconbridge.application.ResolutionProposalService;
import com.suyog.reconbridge.application.model.ResolutionProposalResult;
import com.suyog.reconbridge.domain.model.ResolutionProposal;
import jakarta.validation.Valid;
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
@RequestMapping("/api/exception-cases/{caseId}/proposals")
public class ResolutionProposalController {

    private final ResolutionProposalService service;

    public ResolutionProposalController(
            ResolutionProposalService service
    ) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasRole('ANALYST')")
    public ResponseEntity<ResolutionProposalResult> submit(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("caseId") UUID caseId,
            @Valid @RequestBody ResolutionProposalRequests.Submit request
    ) {
        ResolutionProposalResult result = service.submit(
                tenantId(jwt),
                caseId,
                request.expectedVersion(),
                jwt.getSubject(),
                request.resolutionType(),
                request.rationale()
        );

        URI location = URI.create(
                "/api/exception-cases/"
                        + caseId
                        + "/proposals/"
                        + result.proposal().id()
        );

        return ResponseEntity.created(location).body(result);
    }

    @GetMapping("/{proposalId}")
    @PreAuthorize(
            "hasAnyRole('IMPORTER', 'ANALYST', 'APPROVER', 'AUDITOR')"
    )
    public ResolutionProposal get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("caseId") UUID caseId,
            @PathVariable("proposalId") UUID proposalId
    ) {
        return service.get(
                tenantId(jwt),
                caseId,
                proposalId
        );
    }

    @PostMapping("/{proposalId}/approve")
    @PreAuthorize("hasRole('APPROVER')")
    public ResolutionProposalResult approve(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("caseId") UUID caseId,
            @PathVariable("proposalId") UUID proposalId,
            @Valid @RequestBody ResolutionProposalRequests.Decision request
    ) {
        return service.approve(
                tenantId(jwt),
                caseId,
                proposalId,
                request.expectedVersion(),
                jwt.getSubject(),
                request.comment()
        );
    }

    @PostMapping("/{proposalId}/reject")
    @PreAuthorize("hasRole('APPROVER')")
    public ResolutionProposalResult reject(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("caseId") UUID caseId,
            @PathVariable("proposalId") UUID proposalId,
            @Valid @RequestBody ResolutionProposalRequests.Decision request
    ) {
        return service.reject(
                tenantId(jwt),
                caseId,
                proposalId,
                request.expectedVersion(),
                jwt.getSubject(),
                request.comment()
        );
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenant_id"));
    }
}