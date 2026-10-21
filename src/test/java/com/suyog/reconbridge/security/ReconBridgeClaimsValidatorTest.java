package com.suyog.reconbridge.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconBridgeClaimsValidatorTest {

    private final ReconBridgeClaimsValidator validator =
            new ReconBridgeClaimsValidator("reconbridge-api");

    @Test
    void acceptsRequiredAudienceAndCanonicalTenant() {
        Jwt jwt = token()
                .claim(
                        "tenant_id",
                        "11111111-1111-4111-8111-111111111111"
                )
                .build();

        assertFalse(validator.validate(jwt).hasErrors());
    }

    @Test
    void rejectsMissingTenant() {
        assertTrue(
                validator.validate(token().build()).hasErrors()
        );
    }

    @Test
    void rejectsMalformedTenant() {
        Jwt jwt = token()
                .claim("tenant_id", "not-a-uuid")
                .build();

        assertTrue(validator.validate(jwt).hasErrors());
    }

    @Test
    void rejectsMultivaluedTenant() {
        Jwt jwt = token()
                .claim(
                        "tenant_id",
                        List.of(
                                "11111111-1111-4111-8111-111111111111"
                        )
                )
                .build();

        assertTrue(validator.validate(jwt).hasErrors());
    }

    @Test
    void rejectsWrongAudience() {
        Jwt jwt = token()
                .audience(List.of("another-api"))
                .claim(
                        "tenant_id",
                        "11111111-1111-4111-8111-111111111111"
                )
                .build();

        assertTrue(validator.validate(jwt).hasErrors());
    }

    private Jwt.Builder token() {
        return Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject("test-user")
                .audience(List.of("reconbridge-api"));
    }
}