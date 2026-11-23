package com.suyog.reconbridge.ingestion;

import com.suyog.reconbridge.domain.model.MatchKey;
import com.suyog.reconbridge.domain.model.Money;
import com.suyog.reconbridge.domain.model.PaymentRecord;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class InternalPaymentCsvParser {

    private static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_ROWS = 100_000;

    private static final List<String> HEADERS = List.of(
            "payment_id",
            "payment_reference",
            "amount_minor",
            "currency"
    );

    private static final CSVFormat FORMAT = CSVFormat.RFC4180
            .builder()
            .setIgnoreEmptyLines(false)
            .get();

    public List<PaymentRecord> parse(
            byte[] verifiedContents,
            UUID tenantId,
            String provider
    ) {
        Objects.requireNonNull(
                verifiedContents,
                "verifiedContents must not be null"
        );
        Objects.requireNonNull(
                tenantId,
                "tenantId must not be null"
        );

        // Invalid trusted context is a caller/configuration error,
        // rather than invalid CSV content.
        String trustedProvider = requireText(
                provider,
                "provider",
                100
        );

        if (verifiedContents.length == 0) {
            throw new PaymentCsvValidationException(
                    "Internal payment file must not be empty."
            );
        }

        if (verifiedContents.length > MAX_FILE_BYTES) {
            throw new PaymentCsvValidationException(
                    "Internal payment file must not exceed 10 MiB."
            );
        }

        String contents = decodeUtf8(verifiedContents);

        if (contents.startsWith("\uFEFF")) {
            contents = contents.substring(1);
        }

        try (
                StringReader reader = new StringReader(contents);
                CSVParser parser = FORMAT.parse(reader)
        ) {
            Iterator<CSVRecord> records = parser.iterator();

            if (!records.hasNext()) {
                throw new PaymentCsvValidationException(
                        "Internal payment header is missing."
                );
            }

            validateHeader(records.next());

            List<PaymentRecord> payments = new ArrayList<>();
            Set<UUID> paymentIds = new HashSet<>();
            Set<MatchKey> paymentKeys = new HashSet<>();

            while (records.hasNext()) {
                CSVRecord record = records.next();

                if (payments.size() >= MAX_ROWS) {
                    throw new PaymentCsvValidationException(
                            "Internal payment file must not exceed "
                                    + MAX_ROWS
                                    + " data records."
                    );
                }

                PaymentRecord payment = parseRecord(
                        record,
                        tenantId,
                        trustedProvider
                );

                if (!paymentIds.add(payment.id())) {
                    throw invalidRecord(
                            record,
                            "Duplicate payment_id."
                    );
                }

                if (!paymentKeys.add(payment.matchKey())) {
                    throw invalidRecord(
                            record,
                            "Duplicate payment_reference for this provider."
                    );
                }

                payments.add(payment);
            }

            if (payments.isEmpty()) {
                throw new PaymentCsvValidationException(
                        "Internal payment file must contain "
                                + "at least one payment."
                );
            }

            return List.copyOf(payments);

        } catch (UncheckedIOException exception) {
            throw new PaymentCsvValidationException(
                    "Malformed internal payment CSV.",
                    exception.getCause()
            );
        } catch (IOException exception) {
            // The parser reads in-memory contents. Storage I/O
            // happens before this parser is invoked.
            throw new PaymentCsvValidationException(
                    "Malformed internal payment CSV.",
                    exception
            );
        }
    }

    private String decodeUtf8(byte[] contents) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(contents))
                    .toString();

        } catch (CharacterCodingException exception) {
            throw new PaymentCsvValidationException(
                    "Internal payment file must use valid UTF-8 encoding.",
                    exception
            );
        }
    }

    private void validateHeader(CSVRecord header) {
        if (header.size() != HEADERS.size()) {
            throw new PaymentCsvValidationException(
                    "Internal payment columns must be: "
                            + String.join(",", HEADERS)
            );
        }

        for (int index = 0; index < HEADERS.size(); index++) {
            if (!HEADERS.get(index).equals(header.get(index))) {
                throw new PaymentCsvValidationException(
                        "Internal payment header names and order must be: "
                                + String.join(",", HEADERS)
                );
            }
        }
    }

    private PaymentRecord parseRecord(
            CSVRecord record,
            UUID tenantId,
            String provider
    ) {
        if (record.size() != HEADERS.size()) {
            throw invalidRecord(
                    record,
                    "Expected exactly four fields."
            );
        }

        try {
            String idText = requireText(
                    record.get(0),
                    "payment_id",
                    36
            );

            UUID paymentId = UUID.fromString(idText);

            if (!paymentId.toString().equalsIgnoreCase(idText)) {
                throw new IllegalArgumentException(
                        "payment_id must use the full UUID format."
                );
            }

            String paymentReference = requireText(
                    record.get(1),
                    "payment_reference",
                    255
            );

            String amountText = requireText(
                    record.get(2),
                    "amount_minor",
                    19
            );

            if (!amountText.matches("[0-9]+")) {
                throw new IllegalArgumentException(
                        "amount_minor must contain only decimal digits."
                );
            }

            long amountMinor = Long.parseLong(amountText);

            if (amountMinor <= 0) {
                throw new IllegalArgumentException(
                        "amount_minor must be greater than zero."
                );
            }

            String currency = requireText(
                    record.get(3),
                    "currency",
                    3
            );

            if (!currency.matches("[A-Z]{3}")) {
                throw new IllegalArgumentException(
                        "currency must contain three uppercase letters."
                );
            }

            return new PaymentRecord(
                    paymentId,
                    new MatchKey(
                            tenantId,
                            provider,
                            paymentReference
                    ),
                    Money.ofMinor(amountMinor, currency)
            );

        } catch (IllegalArgumentException exception) {
            throw new PaymentCsvValidationException(
                    "CSV record "
                            + record.getRecordNumber()
                            + ": invalid internal payment. "
                            + exception.getMessage(),
                    exception
            );
        }
    }

    private String requireText(
            String value,
            String fieldName,
            int maximumLength
    ) {
        if (value == null) {
            throw new IllegalArgumentException(
                    fieldName + " must not be null."
            );
        }

        // Check before trimming so controls cannot be hidden
        // at the beginning or end of a field.
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    fieldName + " must not contain control characters."
            );
        }

        String normalized = value.trim();

        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " must not be blank."
            );
        }

        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(
                    fieldName
                            + " must not exceed "
                            + maximumLength
                            + " characters."
            );
        }

        return normalized;
    }

    private PaymentCsvValidationException invalidRecord(
            CSVRecord record,
            String detail
    ) {
        return new PaymentCsvValidationException(
                "CSV record "
                        + record.getRecordNumber()
                        + ": "
                        + detail
        );
    }
}