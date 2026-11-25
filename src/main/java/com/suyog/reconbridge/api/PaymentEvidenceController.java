package com.suyog.reconbridge.api;

import com.suyog.reconbridge.application.PaymentEvidenceService;
import com.suyog.reconbridge.application.model.PaymentEvidence;
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
@RequestMapping("/api/imports/{importId}/payments")
public class PaymentEvidenceController {

    private final PaymentEvidenceService service;

    public PaymentEvidenceController(PaymentEvidenceService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(
            "hasAnyRole('IMPORTER', 'ANALYST', 'APPROVER', 'AUDITOR')"
    )
    public PaymentEvidence.Page payments(
            @PathVariable("importId") UUID importId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID tenantId = UUID.fromString(
                jwt.getClaimAsString("tenant_id")
        );

        return service.payments(
                tenantId,
                importId,
                page,
                size
        );
    }
}