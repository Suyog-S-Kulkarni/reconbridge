package com.suyog.reconbridge.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

import static com.suyog.reconbridge.storage.StoredFileReadException.Reason;

@Component
public class LocalStoredFileReader implements StoredFileReader {

    private static final String PREFIX = "local:";

    private static final Pattern KEY_PATTERN = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
                    + "[0-9a-f]{4}-[0-9a-f]{12}\\.csv"
    );

    private static final Pattern HASH_PATTERN =
            Pattern.compile("[0-9a-f]{64}");

    private final Path root;
    private final long maxBytes;

    public LocalStoredFileReader(
            @Value("${reconbridge.storage.root:./var/imports}") String root,
            @Value("${reconbridge.storage.max-bytes:10485760}") long maxBytes
    ) {
        if (maxBytes <= 0 || maxBytes > Integer.MAX_VALUE - 8L) {
            throw new IllegalArgumentException(
                    "Storage max-bytes must be positive "
                            + "and fit within a Java byte array."
            );
        }

        this.root = Path.of(root).toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
    }

    @Override
    public byte[] readVerified(
            String storedUri,
            String expectedSha256
    ) throws StoredFileReadException {
        Path file = resolve(storedUri);
        byte[] expectedDigest = validateHash(expectedSha256);

        MessageDigest digest = sha256Digest();

        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new StoredFileReadException(
                        Reason.FILE_UNAVAILABLE,
                        "The stored file is unavailable or is not a regular file."
                );
            }

            try (
                    SeekableByteChannel channel = Files.newByteChannel(
                            file,
                            StandardOpenOption.READ,
                            LinkOption.NOFOLLOW_LINKS
                    );
                    InputStream input = Channels.newInputStream(channel);
                    ByteArrayOutputStream output = new ByteArrayOutputStream()
            ) {
                if (channel.size() > maxBytes) {
                    throw sizeLimitExceeded();
                }

                byte[] buffer = new byte[8192];
                long total = 0;
                int read;

                while ((read = input.read(buffer)) != -1) {
                    if (read == 0) {
                        continue;
                    }

                    if (read > maxBytes - total) {
                        throw sizeLimitExceeded();
                    }

                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    total += read;
                }

                if (total == 0) {
                    throw new StoredFileReadException(
                            Reason.EMPTY_FILE,
                            "The stored file is empty."
                    );
                }

                if (!MessageDigest.isEqual(
                        expectedDigest,
                        digest.digest()
                )) {
                    throw new StoredFileReadException(
                            Reason.HASH_MISMATCH,
                            "The stored file does not match its registered hash."
                    );
                }

                return output.toByteArray();
            }
        } catch (StoredFileReadException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new StoredFileReadException(
                    Reason.FILE_UNAVAILABLE,
                    "The stored file could not be read.",
                    exception
            );
        }
    }

    private Path resolve(String storedUri) throws StoredFileReadException {
        if (storedUri == null || !storedUri.startsWith(PREFIX)) {
            throw invalidReference();
        }

        String key = storedUri.substring(PREFIX.length());

        if (!KEY_PATTERN.matcher(key).matches()) {
            throw invalidReference();
        }

        Path file = root.resolve(key).normalize();

        if (!root.equals(file.getParent())) {
            throw invalidReference();
        }

        return file;
    }

    private byte[] validateHash(String expectedSha256)
            throws StoredFileReadException {
        if (expectedSha256 == null
                || !HASH_PATTERN.matcher(expectedSha256).matches()) {
            throw new StoredFileReadException(
                    Reason.INVALID_HASH,
                    "The registered SHA-256 hash is invalid."
            );
        }

        return HexFormat.of().parseHex(expectedSha256);
    }

    private StoredFileReadException invalidReference() {
        return new StoredFileReadException(
                Reason.INVALID_REFERENCE,
                "A valid server-managed local storage reference is required."
        );
    }

    private StoredFileReadException sizeLimitExceeded() {
        return new StoredFileReadException(
                Reason.SIZE_LIMIT_EXCEEDED,
                "The stored file exceeds the configured size limit."
        );
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