package com.suyog.reconbridge.api;

import com.suyog.reconbridge.application.ImportLifecycleService;
import com.suyog.reconbridge.application.ImportRegistrationService;
import com.suyog.reconbridge.application.ImportUploadService;
import com.suyog.reconbridge.application.model.ImportBatchSnapshot;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/imports")
public class ImportController {

    private final ImportLifecycleService lifecycleService;
    private final ImportRegistrationService registrationService;
    private final ImportUploadService uploadService;

    public ImportController(
            ImportLifecycleService lifecycleService,
            ImportRegistrationService registrationService,
            ImportUploadService uploadService
    ) {
        this.lifecycleService = lifecycleService;
        this.registrationService = registrationService;
        this.uploadService = uploadService;
    }

    @GetMapping("/{importId}")
    public ImportBatchSnapshot getImport(
            @PathVariable("importId") UUID importId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return lifecycleService.get(tenantId(jwt), importId);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ImportBatchSnapshot> registerImport(
            @Valid @RequestBody RegisterImportRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        ImportBatchSnapshot created = registrationService.register(
                tenantId(jwt),
                request.sourceType(),
                request.sourceSystem(),
                request.fileName(),
                request.fileUri(),
                request.fileHash(),
                request.businessDate()
        );

        return createdResponse(created);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportBatchSnapshot> uploadImport(
            @Valid @ModelAttribute UploadImportRequest request,
            @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal Jwt jwt
    ) {
        ImportBatchSnapshot created = uploadService.upload(
                tenantId(jwt),
                request.sourceType(),
                request.sourceSystem(),
                request.businessDate(),
                file
        );

        return createdResponse(created);
    }

    private ResponseEntity<ImportBatchSnapshot> createdResponse(
            ImportBatchSnapshot created
    ) {
        return ResponseEntity
                .created(URI.create("/api/imports/" + created.id()))
                .body(created);
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenant_id"));
    }
}