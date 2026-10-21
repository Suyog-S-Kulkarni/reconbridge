package com.suyog.reconbridge.api;

import com.suyog.reconbridge.application.ImportLifecycleService;
import com.suyog.reconbridge.application.ImportRegistrationService;
import com.suyog.reconbridge.application.model.ImportBatchSnapshot;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/imports")
public class ImportController {

    private final ImportLifecycleService importLifecycleService;
    private final ImportRegistrationService importRegistrationService;

    public ImportController(
            ImportLifecycleService importLifecycleService,
            ImportRegistrationService importRegistrationService
    ) {
        this.importLifecycleService = importLifecycleService;
        this.importRegistrationService = importRegistrationService;
    }

    @GetMapping("/{importId}")
    public ImportBatchSnapshot getImport(
            @PathVariable("importId") UUID importId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return importLifecycleService.get(tenantId(jwt), importId);
    }

    @PostMapping
    public ResponseEntity<ImportBatchSnapshot> registerImport(
            @Valid @RequestBody RegisterImportRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        ImportBatchSnapshot created = importRegistrationService.register(
                tenantId(jwt),
                request.sourceType(),
                request.sourceSystem(),
                request.fileName(),
                request.fileUri(),
                request.fileHash(),
                request.businessDate()
        );

        URI location = URI.create("/api/imports/" + created.id());

        return ResponseEntity.created(location).body(created);
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenant_id"));
    }
}