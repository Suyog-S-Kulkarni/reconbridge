package com.suyog.reconbridge.api;

import com.suyog.reconbridge.application.SettlementImportProcessingService;
import com.suyog.reconbridge.application.model.ImportProcessingResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/imports")
public class ImportProcessingController {
    private final SettlementImportProcessingService processing;

    public ImportProcessingController(SettlementImportProcessingService processing) {
        this.processing = processing;
    }

    @PreAuthorize("hasRole('IMPORTER')")
    @PostMapping("/{importId}/process")
    public ResponseEntity<ImportProcessingResult> process(
            @PathVariable("importId") UUID importId,
            @Valid @RequestBody ProcessImportRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID tenantId = UUID.fromString(jwt.getClaimAsString("tenant_id"));
        ImportProcessingResult result = processing.process(tenantId, importId, request.expectedVersion());
        int status = switch (result.imported().status()) {
            case READY -> 200;
            case REJECTED -> 422;
            case FAILED -> 500;
            default -> throw new IllegalStateException("Unexpected processing result status");
        };
        return ResponseEntity.status(status).body(result);
    }
}
