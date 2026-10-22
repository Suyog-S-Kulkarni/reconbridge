package com.suyog.reconbridge.api;

import com.suyog.reconbridge.application.SettlementEvidenceService;
import com.suyog.reconbridge.application.model.SettlementEvidence;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/imports/{importId}/settlement-batches")
public class SettlementEvidenceController {

    private final SettlementEvidenceService service;

    public SettlementEvidenceController(
            SettlementEvidenceService service
    ) {
        this.service = service;
    }

    @GetMapping
    public SettlementEvidence.Page<SettlementEvidence.Batch> batches(
            @PathVariable("importId") UUID importId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return service.batches(
                tenantId(jwt),
                importId,
                page,
                size
        );
    }

    @GetMapping("/{batchId}/lines")
    public SettlementEvidence.Page<SettlementEvidence.Line> lines(
            @PathVariable("importId") UUID importId,
            @PathVariable("batchId") UUID batchId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return service.lines(
                tenantId(jwt),
                importId,
                batchId,
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