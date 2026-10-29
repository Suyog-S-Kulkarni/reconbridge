package com.suyog.reconbridge.api;

import com.suyog.reconbridge.application.ReconciliationQueryService;
import com.suyog.reconbridge.application.model.ReconciliationReadModels.Page;
import com.suyog.reconbridge.application.model.ReconciliationReadModels.RunSummary;
import com.suyog.reconbridge.application.model.ReconciliationReadModels.UnresolvedOutcome;
import com.suyog.reconbridge.application.model.ReconciliationReadModels.UnresolvedResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/reconciliation/runs")
@PreAuthorize(
        "hasAnyRole('IMPORTER', 'ANALYST', 'APPROVER', 'AUDITOR')"
)
public class ReconciliationQueryController {

    private final ReconciliationQueryService service;

    public ReconciliationQueryController(
            ReconciliationQueryService service
    ) {
        this.service = service;
    }

    @GetMapping
    public Page<RunSummary> history(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return service.history(
                tenantId(jwt),
                page,
                size
        );
    }

    @GetMapping("/{runId}/unresolved-results")
    public Page<UnresolvedResult> unresolved(
            @PathVariable("runId") UUID runId,
            @RequestParam(
                    name = "outcome",
                    required = false
            ) UnresolvedOutcome outcome,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return service.unresolved(
                tenantId(jwt),
                runId,
                outcome,
                page,
                size
        );
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenant_id"));
    }
}