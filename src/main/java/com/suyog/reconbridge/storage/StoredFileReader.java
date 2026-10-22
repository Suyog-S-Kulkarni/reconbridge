package com.suyog.reconbridge.storage;

public interface StoredFileReader {

    byte[] readVerified(
            String storedUri,
            String expectedSha256
    ) throws StoredFileReadException;
}