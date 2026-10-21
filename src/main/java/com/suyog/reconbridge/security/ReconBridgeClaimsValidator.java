package com.suyog.reconbridge.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

public final class ReconBridgeClaimsValidator
        implements OAuth2TokenValidator<Jwt> {

    private final String requiredAudience;

    public ReconBridgeClaimsValidator(String requiredAudience) {
        this.requiredAudience = requiredAudience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (!token.getAudience().contains(requiredAudience)) {
            return invalid("Required API audience is missing");
        }

        Object subject = token.getClaims().get("sub");

        if (!(subject instanceof String subjectText)
                || subjectText.isBlank()) {
            return invalid("Subject must be present");
        }

        Object tenant = token.getClaims().get("tenant_id");

        if (!(tenant instanceof String tenantText)) {
            return invalid("tenant_id must be a UUID string");
        }

        try {
            UUID parsed = UUID.fromString(tenantText);

            if (!parsed.toString().equals(tenantText)) {
                return invalid(
                        "tenant_id must use canonical lowercase UUID format"
                );
            }
        } catch (IllegalArgumentException exception) {
            return invalid("tenant_id must be a valid UUID");
        }

        return OAuth2TokenValidatorResult.success();
    }

    private OAuth2TokenValidatorResult invalid(String description) {
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error(
                        "invalid_token",
                        description,
                        null
                )
        );
    }
}