package com.suyog.reconbridge.api;

import com.suyog.reconbridge.application.BankReconciliationPreviewService;
import com.suyog.reconbridge.application.model.BankReconciliationPreview;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/reconciliation")
public class BankReconciliationPreviewController {

    private final BankReconciliationPreviewService service;

    public BankReconciliationPreviewController(
            BankReconciliationPreviewService service
    ) {
        this.service = service;
    }

    @PreAuthorize(
            "hasAnyRole('IMPORTER', 'ANALYST', 'APPROVER', 'AUDITOR')"
    )
    @GetMapping("/bank-preview")
    public BankReconciliationPreview preview(
            @RequestParam("settlementImportId") UUID settlementImportId,
            @RequestParam("bankImportId") UUID bankImportId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID tenantId = UUID.fromString(
                jwt.getClaimAsString("tenant_id")
        );

        return service.preview(
                tenantId,
                settlementImportId,
                bankImportId
        );
    }
}