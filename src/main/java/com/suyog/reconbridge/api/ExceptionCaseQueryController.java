package com.suyog.reconbridge.api;

import com.suyog.reconbridge.application.ExceptionCaseQueryService;
import com.suyog.reconbridge.application.model.ExceptionCaseReadModels.CaseSummary;
import com.suyog.reconbridge.application.model.ExceptionCaseReadModels.NoteSummary;
import com.suyog.reconbridge.application.model.ExceptionCaseReadModels.Page;
import com.suyog.reconbridge.domain.model.ExceptionCaseStatus;
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
@RequestMapping("/api/exception-cases")
@PreAuthorize(
        "hasAnyRole('IMPORTER', 'ANALYST', 'APPROVER', 'AUDITOR')"
)
public class ExceptionCaseQueryController {

    private final ExceptionCaseQueryService service;

    public ExceptionCaseQueryController(
            ExceptionCaseQueryService service
    ) {
        this.service = service;
    }

    @GetMapping
    public Page<CaseSummary> listCases(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(
                    name = "status",
                    required = false
            ) ExceptionCaseStatus status,
            @RequestParam(
                    name = "assignedToMe",
                    defaultValue = "false"
            ) boolean assignedToMe,
            @RequestParam(
                    name = "page",
                    defaultValue = "0"
            ) int page,
            @RequestParam(
                    name = "size",
                    defaultValue = "20"
            ) int size
    ) {
        return service.listCases(
                tenantId(jwt),
                jwt.getSubject(),
                status,
                assignedToMe,
                page,
                size
        );
    }

    @GetMapping("/{caseId}/notes")
    public Page<NoteSummary> listNotes(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("caseId") UUID caseId,
            @RequestParam(
                    name = "page",
                    defaultValue = "0"
            ) int page,
            @RequestParam(
                    name = "size",
                    defaultValue = "20"
            ) int size
    ) {
        return service.listNotes(
                tenantId(jwt),
                caseId,
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