package com.suyog.reconbridge.api;

import com.suyog.reconbridge.application.ImportLifecycleService;
import com.suyog.reconbridge.application.model.ImportBatchSnapshot;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/imports")
public class ImportController {

    private final ImportLifecycleService importLifecycleService;

    public ImportController(ImportLifecycleService importLifecycleService) {
        this.importLifecycleService = importLifecycleService;
    }

    @GetMapping("/{importId}")
    public ImportBatchSnapshot getImport(
            @PathVariable("importId") UUID importId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID tenantId = UUID.fromString(
                jwt.getClaimAsString("tenant_id")
        );

        return importLifecycleService.get(tenantId, importId);
    }
}