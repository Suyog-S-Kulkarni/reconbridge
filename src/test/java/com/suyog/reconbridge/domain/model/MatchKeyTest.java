package com.suyog.reconbridge.domain.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MatchKeyTest {

    private static final UUID TENANT_A =
            UUID.fromString(
                    "11111111-1111-4111-8111-111111111111"
            );

    private static final UUID TENANT_B =
            UUID.fromString(
                    "22222222-2222-4222-8222-222222222222"
            );

    @Test
    void equivalentKeysRetrieveTheSameHashMapEntry() {
        MatchKey storedKey = new MatchKey(
                TENANT_A,
                "PROCESSOR_A",
                "PAY-001"
        );

        Map<MatchKey, String> index = new HashMap<>();
        index.put(storedKey, "settlement-line-001");

        MatchKey lookupKey = new MatchKey(
                TENANT_A,
                "PROCESSOR_A",
                "PAY-001"
        );

        assertEquals(
                "settlement-line-001",
                index.get(lookupKey)
        );
    }

    @Test
    void separatesIdenticalReferencesAcrossTenants() {
        MatchKey first = new MatchKey(
                TENANT_A,
                "PROCESSOR_A",
                "PAY-001"
        );

        MatchKey second = new MatchKey(
                TENANT_B,
                "PROCESSOR_A",
                "PAY-001"
        );

        assertNotEquals(first, second);
    }

    @Test
    void separatesIdenticalReferencesAcrossProviders() {
        MatchKey first = new MatchKey(
                TENANT_A,
                "PROCESSOR_A",
                "PAY-001"
        );

        MatchKey second = new MatchKey(
                TENANT_A,
                "PROCESSOR_B",
                "PAY-001"
        );

        assertNotEquals(first, second);
    }

    @Test
    void trimsSurroundingWhitespace() {
        MatchKey key = new MatchKey(
                TENANT_A,
                " PROCESSOR_A ",
                " PAY-001 "
        );

        assertEquals("PROCESSOR_A", key.provider());
        assertEquals("PAY-001", key.paymentReference());
    }

    @Test
    void preservesReferenceCase() {
        MatchKey uppercase = new MatchKey(
                TENANT_A,
                "PROCESSOR_A",
                "PAY-001"
        );

        MatchKey lowercase = new MatchKey(
                TENANT_A,
                "PROCESSOR_A",
                "pay-001"
        );

        assertNotEquals(uppercase, lowercase);
    }

    @Test
    void rejectsBlankPaymentReference() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MatchKey(
                        TENANT_A,
                        "PROCESSOR_A",
                        " "
                )
        );
    }

    @Test
    void rejectsBlankProvider() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MatchKey(
                        TENANT_A,
                        " ",
                        "PAY-001"
                )
        );
    }

    @Test
    void rejectsMissingTenant() {
        assertThrows(
                NullPointerException.class,
                () -> new MatchKey(
                        null,
                        "PROCESSOR_A",
                        "PAY-001"
                )
        );
    }
}