package com.suyog.reconbridge.api;

import com.suyog.reconbridge.application.ResolutionProposalQueryService;
import com.suyog.reconbridge.application.model.ResolutionProposalReadModels.Page;
import com.suyog.reconbridge.application.model.ResolutionProposalReadModels.PendingApproval;
import com.suyog.reconbridge.application.model.ResolutionProposalReadModels.ProposalSummary;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class ResolutionProposalQueryController {

    private final ResolutionProposalQueryService service;

    public ResolutionProposalQueryController(
            ResolutionProposalQueryService service
    ) {
        this.service = service;
    }

    @GetMapping("/api/exception-cases/{caseId}/proposals")
    @PreAuthorize(
            "hasAnyRole('IMPORTER', 'ANALYST', 'APPROVER', 'AUDITOR')"
    )
    public Page<ProposalSummary> history(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("caseId") UUID caseId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        return service.history(
                tenantId(jwt),
                caseId,
                page,
                size
        );
    }

    @GetMapping("/api/resolution-proposals/pending")
    @PreAuthorize("hasRole('APPROVER')")
    public Page<PendingApproval> pending(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        return service.pending(
                tenantId(jwt),
                jwt.getSubject(),
                page,
                size
        );
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(
                jwt.getClaimAsString("tenant_id")
        );
    }
}