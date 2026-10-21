package com.suyog.reconbridge.ingestion;

import com.suyog.reconbridge.domain.model.SettlementBatch;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementCsvParserTest {

    private static final UUID TENANT =
            UUID.fromString("11111111-1111-4111-8111-111111111111");

    private static final String PROVIDER = "PROCESSOR_A";

    private static final String HEADER =
            "batchId,settlementReference,bankAccountReference,"
                    + "declaredNetMinor,lineId,paymentReference,"
                    + "grossMinor,feeMinor,netMinor,currency";

    private static final String BATCH =
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

    private static final String LINE_ONE =
            "11111111-1111-4111-8111-111111111111";

    private static final String LINE_TWO =
            "22222222-2222-4222-8222-222222222222";

    private final SettlementCsvParser parser = new SettlementCsvParser();

    @Test
    void parsesBatchAndUsesTrustedTenantAndProvider() throws Exception {
        List<SettlementBatch> batches = parse(validCsv());

        assertThat(batches).hasSize(1);

        SettlementBatch batch = batches.get(0);

        assertThat(batch.id()).isEqualTo(UUID.fromString(BATCH));
        assertThat(batch.settlementKey().tenantId()).isEqualTo(TENANT);
        assertThat(batch.settlementKey().provider()).isEqualTo(PROVIDER);
        assertThat(batch.lines()).hasSize(2);
        assertThat(batch.declaredNetAmount().amountMinor()).isEqualTo(37100);

        assertThat(batch.lines().get(0).netAmount().amountMinor())
                .isEqualTo(12400);

        assertThat(batch.lines().get(1).netAmount().amountMinor())
                .isEqualTo(24700);
    }

    @Test
    void acceptsUtf8Bom() throws Exception {
        assertThat(parse("\uFEFF" + validCsv())).hasSize(1);
    }

    @Test
    void supportsQuotedCommaInPaymentReference() throws Exception {
        String csv = validCsv().replace("PAY-001", "\"PAY,001\"");

        SettlementBatch batch = parse(csv).get(0);

        assertThat(batch.lines().get(0).matchKey().paymentReference())
                .isEqualTo("PAY,001");
    }

    @Test
    void preservesRepeatedPaymentReferencesForAmbiguityDetection()
            throws Exception {
        String csv = validCsv().replace("PAY-002", "PAY-001");

        SettlementBatch batch = parse(csv).get(0);

        assertThat(batch.lines()).hasSize(2);
        assertThat(batch.lines().get(0).matchKey())
                .isEqualTo(batch.lines().get(1).matchKey());
    }

    @Test
    void rejectsDuplicateLineIds() {
        String csv = validCsv().replace(LINE_TWO, LINE_ONE);

        assertInvalid(csv, "Duplicate settlement line ID");
    }

    @Test
    void rejectsInconsistentBatchMetadata() {
        String second = secondRow().replace("SETTLEMENT-001", "SETTLEMENT-002");

        assertInvalid(
                HEADER + "\n" + firstRow() + "\n" + second + "\n",
                "Batch metadata"
        );
    }

    @Test
    void rejectsIncorrectBatchTotal() {
        assertInvalid(
                validCsv().replace(",37100,", ",37101,"),
                "Declared net amount"
        );
    }

    @Test
    void rejectsIncorrectLineNetAmount() {
        assertInvalid(
                validCsv().replace(",12550,150,12400,", ",12550,150,12401,"),
                "Net amount must equal"
        );
    }

    @Test
    void rejectsDecimalAmounts() {
        assertInvalid(
                validCsv().replace(",12550,", ",125.50,"),
                "nonnegative integer"
        );
    }

    @Test
    void rejectsAmountOverflow() {
        assertInvalid(
                validCsv().replace(",12550,", ",9223372036854775808,"),
                "supported integer range"
        );
    }

    @Test
    void rejectsInvalidCurrency() {
        assertThatThrownBy(
                () -> parse(validCsv().replace(",INR", ",ZZZ"))
        ).isInstanceOf(CsvValidationException.class);
    }

    @Test
    void rejectsNonCanonicalUuid() {
        assertInvalid(
                validCsv().replace(LINE_ONE, "1-1-1-1-1"),
                "canonical UUID"
        );
    }

    @Test
    void rejectsUnexpectedHeader() {
        assertInvalid(
                validCsv().replace("paymentReference", "paymentRef"),
                "Headers must exactly match"
        );
    }

    @Test
    void rejectsHeaderOnlyFile() {
        assertInvalid(
                HEADER + "\n",
                "At least one settlement data record"
        );
    }

    @Test
    void rejectsExtraColumn() {
        String csv = HEADER + "\n" + firstRow() + ",unexpected\n";

        assertInvalid(csv, "Expected 10 columns");
    }

    @Test
    void rejectsMalformedQuotedField() {
        String csv = HEADER + "\n\"unterminated";

        assertInvalid(csv, "Malformed CSV");
    }

    @Test
    void rejectsInvalidUtf8() {
        byte[] invalidUtf8 = {(byte) 0xC3, (byte) 0x28};

        assertThatThrownBy(() -> parser.parse(
                new ByteArrayInputStream(invalidUtf8),
                TENANT,
                PROVIDER
        )).isInstanceOf(CsvValidationException.class);
    }

    @Test
    void enforcesRowLimit() {
        SettlementCsvParser limitedParser = new SettlementCsvParser(1);

        assertThatThrownBy(() -> limitedParser.parse(
                stream(validCsv()),
                TENANT,
                PROVIDER
        ))
                .isInstanceOf(CsvValidationException.class)
                .hasMessageContaining("maximum of 1");
    }

    @Test
    void preservesTechnicalReadFailures() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("Storage unavailable");
            }
        };

        assertThatThrownBy(() -> parser.parse(
                broken,
                TENANT,
                PROVIDER
        ))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Storage unavailable");
    }

    private List<SettlementBatch> parse(String csv) throws IOException {
        return parser.parse(stream(csv), TENANT, PROVIDER);
    }

    private ByteArrayInputStream stream(String csv) {
        return new ByteArrayInputStream(
                csv.getBytes(StandardCharsets.UTF_8)
        );
    }

    private void assertInvalid(String csv, String message) {
        assertThatThrownBy(() -> parse(csv))
                .isInstanceOf(CsvValidationException.class)
                .hasMessageContaining(message);
    }

    private String validCsv() {
        return HEADER + "\n" + firstRow() + "\n" + secondRow() + "\n";
    }

    private String firstRow() {
        return BATCH
                + ",SETTLEMENT-001,BANK-ACCOUNT-001,37100,"
                + LINE_ONE
                + ",PAY-001,12550,150,12400,INR";
    }

    private String secondRow() {
        return BATCH
                + ",SETTLEMENT-001,BANK-ACCOUNT-001,37100,"
                + LINE_TWO
                + ",PAY-002,25000,300,24700,INR";
    }
}