package com.suyog.reconbridge.ingestion;

import com.suyog.reconbridge.domain.model.BankCredit;
import com.suyog.reconbridge.domain.model.Money;
import com.suyog.reconbridge.domain.model.SettlementKey;
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

public final class BankStatementCsvParser {

    private static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_ROWS = 100_000;

    private static final List<String> HEADERS = List.of(
            "bank_credit_id",
            "provider",
            "bank_account_reference",
            "settlement_reference",
            "amount_minor",
            "currency"
    );

    private static final CSVFormat FORMAT = CSVFormat.RFC4180
            .builder()
            .setIgnoreEmptyLines(false)
            .get();

    public List<BankCredit> parse(
            byte[] verifiedContents,
            UUID tenantId
    ) {
        Objects.requireNonNull(
                verifiedContents,
                "verifiedContents must not be null"
        );

        Objects.requireNonNull(
                tenantId,
                "tenantId must not be null"
        );

        if (verifiedContents.length == 0) {
            throw new BankCsvValidationException(
                    "Bank statement file must not be empty."
            );
        }

        if (verifiedContents.length > MAX_FILE_BYTES) {
            throw new BankCsvValidationException(
                    "Bank statement file must not exceed 10 MiB."
            );
        }

        String contents = decodeUtf8(verifiedContents);

        // Accept a UTF-8 byte order mark at the start of the file.
        if (contents.startsWith("\uFEFF")) {
            contents = contents.substring(1);
        }

        try (
                StringReader reader = new StringReader(contents);
                CSVParser parser = FORMAT.parse(reader)
        ) {
            Iterator<CSVRecord> records = parser.iterator();

            if (!records.hasNext()) {
                throw new BankCsvValidationException(
                        "Bank statement header is missing."
                );
            }

            validateHeader(records.next());

            List<BankCredit> credits = new ArrayList<>();
            Set<UUID> creditIds = new HashSet<>();

            while (records.hasNext()) {
                CSVRecord record = records.next();

                if (credits.size() >= MAX_ROWS) {
                    throw new BankCsvValidationException(
                            "Bank statement must not exceed "
                                    + MAX_ROWS
                                    + " data records."
                    );
                }

                BankCredit credit = parseRecord(
                        record,
                        tenantId
                );

                if (!creditIds.add(credit.id())) {
                    throw invalidRecord(
                            record,
                            "Duplicate bank_credit_id."
                    );
                }

                credits.add(credit);
            }

            if (credits.isEmpty()) {
                throw new BankCsvValidationException(
                        "Bank statement must contain at least one credit."
                );
            }

            return List.copyOf(credits);

        } catch (UncheckedIOException exception) {
            throw new BankCsvValidationException(
                    "Malformed bank statement CSV.",
                    exception.getCause()
            );
        } catch (IOException exception) {
            // Parsing uses an in-memory StringReader.
            // External storage reads happen before this parser is called.
            throw new BankCsvValidationException(
                    "Malformed bank statement CSV.",
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
            throw new BankCsvValidationException(
                    "Bank statement must use valid UTF-8 encoding.",
                    exception
            );
        }
    }

    private void validateHeader(CSVRecord header) {
        if (header.size() != HEADERS.size()) {
            throw new BankCsvValidationException(
                    "Bank statement must contain exactly these columns: "
                            + String.join(",", HEADERS)
            );
        }

        for (int index = 0; index < HEADERS.size(); index++) {
            if (!HEADERS.get(index).equals(header.get(index))) {
                throw new BankCsvValidationException(
                        "Bank statement header names and order must be: "
                                + String.join(",", HEADERS)
                );
            }
        }
    }

    private BankCredit parseRecord(
            CSVRecord record,
            UUID tenantId
    ) {
        if (record.size() != HEADERS.size()) {
            throw invalidRecord(
                    record,
                    "Expected exactly six fields."
            );
        }

        try {
            String idText = requireText(
                    record.get(0),
                    "bank_credit_id",
                    36
            );

            UUID creditId = UUID.fromString(idText);

            // Reject shortened UUID forms accepted by UUID.fromString().
            if (!creditId.toString().equalsIgnoreCase(idText)) {
                throw new IllegalArgumentException(
                        "bank_credit_id must use the full UUID format."
                );
            }

            String provider = requireText(
                    record.get(1),
                    "provider",
                    100
            );

            String bankAccountReference = requireText(
                    record.get(2),
                    "bank_account_reference",
                    255
            );

            String settlementReference = requireText(
                    record.get(3),
                    "settlement_reference",
                    255
            );

            String amountText = requireText(
                    record.get(4),
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
                    record.get(5),
                    "currency",
                    3
            );

            if (!currency.matches("[A-Z]{3}")) {
                throw new IllegalArgumentException(
                        "currency must contain three uppercase letters."
                );
            }

            SettlementKey key = new SettlementKey(
                    tenantId,
                    provider,
                    bankAccountReference,
                    settlementReference
            );

            return new BankCredit(
                    creditId,
                    key,
                    Money.ofMinor(amountMinor, currency)
            );

        } catch (IllegalArgumentException exception) {
            throw new BankCsvValidationException(
                    "CSV record "
                            + record.getRecordNumber()
                            + ": invalid bank credit. "
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
        String normalized = value.trim();

        if (normalized.isEmpty()) {
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

        if (normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    fieldName + " must not contain control characters."
            );
        }

        return normalized;
    }

    private BankCsvValidationException invalidRecord(
            CSVRecord record,
            String detail
    ) {
        return new BankCsvValidationException(
                "CSV record "
                        + record.getRecordNumber()
                        + ": "
                        + detail
        );
    }
}