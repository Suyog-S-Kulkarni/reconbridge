package com.suyog.reconbridge.ingestion;

import com.suyog.reconbridge.domain.model.MatchKey;
import com.suyog.reconbridge.domain.model.Money;
import com.suyog.reconbridge.domain.model.SettlementBatch;
import com.suyog.reconbridge.domain.model.SettlementKey;
import com.suyog.reconbridge.domain.model.SettlementLine;
import org.apache.commons.csv.CSVException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class SettlementCsvParser {

    private static final List<String> HEADERS = List.of(
            "batchId",
            "settlementReference",
            "bankAccountReference",
            "declaredNetMinor",
            "lineId",
            "paymentReference",
            "grossMinor",
            "feeMinor",
            "netMinor",
            "currency"
    );

    private static final CSVFormat FORMAT =
            CSVFormat.RFC4180.builder()
                    .setIgnoreEmptyLines(false)
                    .get();

    private final int maxRows;

    public SettlementCsvParser() {
        this(100_000);
    }

    public SettlementCsvParser(int maxRows) {
        if (maxRows <= 0) {
            throw new IllegalArgumentException(
                    "maxRows must be greater than zero"
            );
        }

        this.maxRows = maxRows;
    }

    /*
     * Consumes and closes the supplied stream.
     *
     * Invalid CSV/content -> CsvValidationException.
     * Technical read failure -> IOException.
     */
    public List<SettlementBatch> parse(
            InputStream input,
            UUID tenantId,
            String provider
    ) throws IOException {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");

        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException(
                    "provider must not be blank"
            );
        }

        String normalizedProvider = provider.trim();

        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);

        try (PushbackReader reader = new PushbackReader(
                new InputStreamReader(input, decoder),
                1
        )) {
            removeOptionalBom(reader);

            try (CSVParser parser = FORMAT.parse(reader)) {
                return parseRecords(
                        parser.iterator(),
                        tenantId,
                        normalizedProvider
                );
            }
        } catch (UncheckedIOException exception) {
            throw classifyReadFailure(exception.getCause());
        } catch (IOException exception) {
            throw classifyReadFailure(exception);
        }
    }

    private List<SettlementBatch> parseRecords(
            Iterator<CSVRecord> records,
            UUID tenantId,
            String provider
    ) {
        if (!records.hasNext()) {
            throw new CsvValidationException(0, "The file is empty.");
        }

        validateHeader(records.next());

        Map<UUID, BatchAccumulator> batches = new LinkedHashMap<>();
        Map<SettlementKey, UUID> batchIdsByKey = new HashMap<>();
        Set<UUID> lineIds = new HashSet<>();

        int rowCount = 0;

        while (records.hasNext()) {
            CSVRecord record = records.next();
            rowCount++;

            if (rowCount > maxRows) {
                throw invalid(
                        record,
                        "The file exceeds the maximum of "
                                + maxRows + " data records."
                );
            }

            if (record.size() != HEADERS.size()) {
                throw invalid(
                        record,
                        "Expected " + HEADERS.size()
                                + " columns but found " + record.size() + "."
                );
            }

            try {
                UUID batchId = uuid(record, 0);
                String settlementReference = text(record, 1, 255);
                String bankAccountReference = text(record, 2, 255);
                long declaredNetMinor = amount(record, 3);
                UUID lineId = uuid(record, 4);
                String paymentReference = text(record, 5, 255);
                long grossMinor = amount(record, 6);
                long feeMinor = amount(record, 7);
                long netMinor = amount(record, 8);
                String currency = text(record, 9, 3);

                if (!currency.matches("[A-Z]{3}")) {
                    throw invalid(
                            record,
                            "currency must contain three uppercase letters."
                    );
                }

                if (declaredNetMinor <= 0) {
                    throw invalid(
                            record,
                            "declaredNetMinor must be greater than zero."
                    );
                }

                if (!lineIds.add(lineId)) {
                    throw invalid(
                            record,
                            "Duplicate settlement line ID."
                    );
                }

                SettlementKey settlementKey = new SettlementKey(
                        tenantId,
                        provider,
                        bankAccountReference,
                        settlementReference
                );

                UUID previousBatchId =
                        batchIdsByKey.putIfAbsent(settlementKey, batchId);

                if (previousBatchId != null
                        && !previousBatchId.equals(batchId)) {
                    throw invalid(
                            record,
                            "The same settlement key uses different batch IDs."
                    );
                }

                Money declaredNet =
                        Money.ofMinor(declaredNetMinor, currency);

                SettlementLine line = new SettlementLine(
                        lineId,
                        new MatchKey(
                                tenantId,
                                provider,
                                paymentReference
                        ),
                        Money.ofMinor(grossMinor, currency),
                        Money.ofMinor(feeMinor, currency),
                        Money.ofMinor(netMinor, currency)
                );

                BatchAccumulator batch = batches.get(batchId);

                if (batch == null) {
                    batch = new BatchAccumulator(
                            batchId,
                            settlementKey,
                            declaredNet,
                            record.getRecordNumber()
                    );

                    batches.put(batchId, batch);
                } else if (!batch.key.equals(settlementKey)
                        || !batch.declaredNet.equals(declaredNet)) {
                    throw invalid(
                            record,
                            "Batch metadata, declared total and currency "
                                    + "must remain consistent across its rows."
                    );
                }

                batch.lines.add(line);
            } catch (IllegalArgumentException | ArithmeticException exception) {
                throw new CsvValidationException(
                        record.getRecordNumber(),
                        exception.getMessage(),
                        exception
                );
            }
        }

        if (rowCount == 0) {
            throw new CsvValidationException(
                    0,
                    "At least one settlement data record is required."
            );
        }

        List<SettlementBatch> result = new ArrayList<>();

        for (BatchAccumulator batch : batches.values()) {
            try {
                result.add(new SettlementBatch(
                        batch.id,
                        batch.key,
                        batch.lines,
                        batch.declaredNet
                ));
            } catch (IllegalArgumentException | ArithmeticException exception) {
                throw new CsvValidationException(
                        batch.firstRecord,
                        "Invalid batch: " + exception.getMessage(),
                        exception
                );
            }
        }

        return List.copyOf(result);
    }

    private void validateHeader(CSVRecord record) {
        if (record.size() != HEADERS.size()) {
            throw invalid(record, "Unexpected CSV header.");
        }

        for (int index = 0; index < HEADERS.size(); index++) {
            if (!HEADERS.get(index).equals(record.get(index))) {
                throw invalid(
                        record,
                        "Headers must exactly match: "
                                + String.join(",", HEADERS)
                );
            }
        }
    }

    private String text(
            CSVRecord record,
            int index,
            int maximumLength
    ) {
        String value = record.get(index).trim();

        if (value.isBlank()
                || value.length() > maximumLength
                || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid(
                    record,
                    HEADERS.get(index)
                            + " must be nonblank, contain no control characters "
                            + "and have at most " + maximumLength + " characters."
            );
        }

        return value;
    }

    private UUID uuid(CSVRecord record, int index) {
        String value = text(record, index, 36);

        if (!value.matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                        + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
        )) {
            throw invalid(
                    record,
                    HEADERS.get(index) + " must be a canonical UUID."
            );
        }

        return UUID.fromString(value);
    }

    private long amount(CSVRecord record, int index) {
        String value = text(record, index, 19);

        if (!value.matches("[0-9]+")) {
            throw invalid(
                    record,
                    HEADERS.get(index)
                            + " must be a nonnegative integer in minor units."
            );
        }

        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw invalid(
                    record,
                    HEADERS.get(index) + " exceeds the supported integer range."
            );
        }
    }

    private void removeOptionalBom(PushbackReader reader) throws IOException {
        int first = reader.read();

        if (first != -1 && first != '\uFEFF') {
            reader.unread(first);
        }
    }

    private IOException classifyReadFailure(IOException exception) {
        if (exception instanceof CSVException
                || exception instanceof CharacterCodingException) {
            throw new CsvValidationException(
                    0,
                    "Malformed CSV or invalid UTF-8 encoding.",
                    exception
            );
        }

        return exception;
    }

    private CsvValidationException invalid(
            CSVRecord record,
            String message
    ) {
        return new CsvValidationException(
                record.getRecordNumber(),
                message
        );
    }

    private static final class BatchAccumulator {

        private final UUID id;
        private final SettlementKey key;
        private final Money declaredNet;
        private final long firstRecord;
        private final List<SettlementLine> lines = new ArrayList<>();

        private BatchAccumulator(
                UUID id,
                SettlementKey key,
                Money declaredNet,
                long firstRecord
        ) {
            this.id = id;
            this.key = key;
            this.declaredNet = declaredNet;
            this.firstRecord = firstRecord;
        }
    }
}