package com.suyog.reconbridge.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/me")
public class MeController {

    @GetMapping
    public IdentityResponse me(@AuthenticationPrincipal Jwt jwt) {
        return new IdentityResponse(
                jwt.getSubject(),
                UUID.fromString(jwt.getClaimAsString("tenant_id"))
        );
    }

    public record IdentityResponse(
            String subject,
            UUID tenantId
    ) {
    }
}