package com.suyog.reconbridge.storage;

import com.suyog.reconbridge.application.exception.InvalidUploadException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class LocalFileStorage implements FileStorage {

    private final Path root;
    private final long maxBytes;

    public LocalFileStorage(
            @Value("${reconbridge.storage.root:./var/imports}") String root,
            @Value("${reconbridge.storage.max-bytes:10485760}") long maxBytes
    ) throws IOException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException(
                    "Storage max-bytes must be greater than zero."
            );
        }

        this.root = Path.of(root).toAbsolutePath().normalize();
        this.maxBytes = maxBytes;

        Files.createDirectories(this.root);
    }

    @Override
    public StoredFile store(InputStream input) throws IOException {
        String key = UUID.randomUUID() + ".csv";
        Path destination = resolve(key);
        MessageDigest digest = sha256Digest();

        long total = 0;
        boolean created = false;

        try {
            OutputStream output = Files.newOutputStream(
                    destination,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            );

            created = true;

            try (output) {
                byte[] buffer = new byte[8192];
                int read;

                while ((read = input.read(buffer)) != -1) {
                    if (read == 0) {
                        continue;
                    }

                    if (read > maxBytes - total) {
                        throw new MaxUploadSizeExceededException(maxBytes);
                    }

                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    total += read;
                }
            }

            if (total == 0) {
                throw new InvalidUploadException(
                        "The uploaded file must not be empty."
                );
            }

            return new StoredFile(
                    key,
                    "local:" + key,
                    HexFormat.of().formatHex(digest.digest()),
                    total
            );
        } catch (IOException | RuntimeException exception) {
            if (created) {
                try {
                    Files.deleteIfExists(destination);
                } catch (IOException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }

            throw exception;
        }
    }

    @Override
    public void delete(StoredFile file) throws IOException {
        Files.deleteIfExists(resolve(file.key()));
    }

    private Path resolve(String key) {
        if (!key.matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
                        + "[0-9a-f]{4}-[0-9a-f]{12}\\.csv"
        )) {
            throw new IllegalArgumentException("Invalid storage key.");
        }

        Path result = root.resolve(key).normalize();

        if (!result.getParent().equals(root)) {
            throw new IllegalArgumentException("Invalid storage path.");
        }

        return result;
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable.",
                    exception
            );
        }
    }
}