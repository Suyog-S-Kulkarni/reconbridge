package com.suyog.reconbridge.api;

import com.suyog.reconbridge.application.IdempotentReconciliationRunService;
import com.suyog.reconbridge.application.ReconciliationRunService;
import com.suyog.reconbridge.application.model.RunCreationResult;
import com.suyog.reconbridge.application.model.StoredReconciliationRun;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/reconciliation/runs")
public class ReconciliationRunController {

    private final ReconciliationRunService service;
    private final IdempotentReconciliationRunService creationService;

    public ReconciliationRunController(
            ReconciliationRunService service,
            IdempotentReconciliationRunService creationService
    ) {
        this.service = service;
        this.creationService = creationService;
    }

    @PreAuthorize("hasRole('ANALYST')")
    @PostMapping
    public ResponseEntity<StoredReconciliationRun> create(
            @Valid @RequestBody CreateReconciliationRunRequest request,
            @RequestHeader(
                    name = "Idempotency-Key",
                    required = false
            ) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt
    ) {
        /*
         * required=false lets the application return our consistent
         * INVALID_IDEMPOTENCY_KEY problem response for a missing header.
         * The application service still requires a valid key.
         */
        RunCreationResult result = creationService.create(
                tenantId(jwt),
                jwt.getSubject(),
                idempotencyKey,
                request.settlementImportId(),
                request.bankImportId()
        );

        URI location = URI.create(
                "/api/reconciliation/runs/" + result.run().id()
        );

        if (result.created()) {
            return ResponseEntity
                    .created(location)
                    .header("Idempotency-Replayed", "false")
                    .body(result.run());
        }

        return ResponseEntity
                .ok()
                .location(location)
                .header("Idempotency-Replayed", "true")
                .body(result.run());
    }

    @PreAuthorize(
            "hasAnyRole('IMPORTER', 'ANALYST', 'APPROVER', 'AUDITOR')"
    )
    @GetMapping("/{runId}")
    public StoredReconciliationRun get(
            @PathVariable("runId") UUID runId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return service.get(tenantId(jwt), runId);
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(
                jwt.getClaimAsString("tenant_id")
        );
    }
}