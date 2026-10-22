package com.suyog.reconbridge.application.model;

import java.util.UUID;

public record ClaimedSettlementImport(
        UUID tenantId,
        UUID importId,
        String provider,
        String storageUri,
        String sha256,
        long version
) {
}
