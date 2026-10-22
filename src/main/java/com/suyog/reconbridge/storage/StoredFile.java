package com.suyog.reconbridge.storage;

public record StoredFile(
        String key,
        String uri,
        String sha256,
        long size
) {
}