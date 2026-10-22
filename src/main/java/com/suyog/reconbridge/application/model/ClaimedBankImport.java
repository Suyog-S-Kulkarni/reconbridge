package com.suyog.reconbridge.application.model;

import java.util.UUID;

public record ClaimedBankImport(
        UUID tenantId,
        UUID importId,
        String storageUri,
        String sha256,
        long version
) {
}