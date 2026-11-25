package com.suyog.reconbridge.api;

import com.suyog.reconbridge.application.ThreeSourceReconciliationPreviewService;
import com.suyog.reconbridge.application.model.ThreeSourceReconciliationPreview;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class ThreeSourceReconciliationPreviewController {

    private final ThreeSourceReconciliationPreviewService service;

    public ThreeSourceReconciliationPreviewController(
            ThreeSourceReconciliationPreviewService service
    ) {
        this.service = service;
    }

    @GetMapping("/api/reconciliation/three-source-preview")
    @PreAuthorize(
            "hasAnyRole('IMPORTER', 'ANALYST', 'APPROVER', 'AUDITOR')"
    )
    public ThreeSourceReconciliationPreview preview(
            @RequestParam("paymentImportId") UUID paymentImportId,
            @RequestParam("settlementImportId") UUID settlementImportId,
            @RequestParam("bankImportId") UUID bankImportId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID tenantId = UUID.fromString(
                jwt.getClaimAsString("tenant_id")
        );

        return service.preview(
                tenantId,
                paymentImportId,
                settlementImportId,
                bankImportId
        );
    }
}