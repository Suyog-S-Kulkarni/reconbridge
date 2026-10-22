package com.suyog.reconbridge.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static com.suyog.reconbridge.storage.StoredFileReadException.Reason;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalStoredFileReaderTest {

    @TempDir
    Path root;

    private LocalStoredFileReader reader;

    @BeforeEach
    void setUp() {
        reader = new LocalStoredFileReader(root.toString(), 1024);
    }

    @Test
    void returnsBytesMatchingTheRegisteredHash() throws Exception {
        byte[] content = bytes("reference,amount\nPAY-001,12550\n");
        String uri = store(content);

        byte[] result = reader.readVerified(uri, sha256(content));

        assertThat(result).isEqualTo(content);
    }

    @Test
    void rejectsChangedFileContent() throws Exception {
        byte[] original = bytes("original");
        String uri = store(original);

        Files.write(path(uri), bytes("changed"));

        assertReason(
                uri,
                sha256(original),
                Reason.HASH_MISMATCH
        );
    }

    @Test
    void returnedSnapshotDoesNotChangeWhenFileChanges() throws Exception {
        byte[] original = bytes("original");
        String uri = store(original);

        byte[] verified = reader.readVerified(uri, sha256(original));

        Files.write(path(uri), bytes("replacement"));

        assertThat(verified).isEqualTo(original);
    }

    @Test
    void rejectsMissingFile() throws Exception {
        String uri = "local:" + UUID.randomUUID() + ".csv";

        assertReason(
                uri,
                sha256(bytes("missing")),
                Reason.FILE_UNAVAILABLE
        );
    }

    @Test
    void rejectsEmptyStoredFile() throws Exception {
        byte[] empty = new byte[0];
        String uri = store(empty);

        assertReason(
                uri,
                sha256(empty),
                Reason.EMPTY_FILE
        );
    }

    @Test
    void rejectsOversizedStoredFile() throws Exception {
        byte[] content = new byte[1025];
        String uri = store(content);

        assertReason(
                uri,
                sha256(content),
                Reason.SIZE_LIMIT_EXCEEDED
        );
    }

    @Test
    void acceptsFileExactlyAtTheLimit() throws Exception {
        byte[] content = new byte[1024];
        String uri = store(content);

        assertThat(reader.readVerified(uri, sha256(content)))
                .isEqualTo(content);
    }

    @Test
    void rejectsPathTraversalReference() throws Exception {
        assertReason(
                "local:../outside.csv",
                sha256(bytes("content")),
                Reason.INVALID_REFERENCE
        );
    }

    @Test
    void rejectsExternalUri() throws Exception {
        assertReason(
                "https://example.invalid/settlement.csv",
                sha256(bytes("content")),
                Reason.INVALID_REFERENCE
        );
    }

    @Test
    void rejectsMalformedRegisteredHash() throws Exception {
        String uri = store(bytes("content"));

        assertReason(
                uri,
                "invalid-hash",
                Reason.INVALID_HASH
        );
    }

    @Test
    void rejectsDirectoryWithCsvName() throws Exception {
        String key = UUID.randomUUID() + ".csv";
        Files.createDirectory(root.resolve(key));

        assertReason(
                "local:" + key,
                sha256(bytes("content")),
                Reason.FILE_UNAVAILABLE
        );
    }

    private void assertReason(
            String uri,
            String hash,
            Reason reason
    ) {
        assertThatThrownBy(() -> reader.readVerified(uri, hash))
                .isInstanceOfSatisfying(
                        StoredFileReadException.class,
                        exception -> assertThat(exception.getReason())
                                .isEqualTo(reason)
                );
    }

    private String store(byte[] content) throws Exception {
        String key = UUID.randomUUID() + ".csv";
        Files.write(root.resolve(key), content);
        return "local:" + key;
    }

    private Path path(String uri) {
        return root.resolve(uri.substring("local:".length()));
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content)
        );
    }
}