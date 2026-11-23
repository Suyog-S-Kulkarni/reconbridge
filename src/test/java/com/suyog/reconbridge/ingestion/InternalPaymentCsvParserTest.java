package com.suyog.reconbridge.ingestion;

import com.suyog.reconbridge.domain.model.PaymentRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalPaymentCsvParserTest {

    private static final UUID TENANT_A = UUID.fromString(
            "11111111-1111-4111-8111-111111111111"
    );

    private static final UUID TENANT_B = UUID.fromString(
            "22222222-2222-4222-8222-222222222222"
    );

    private static final String PAYMENT_ONE =
            "55555555-5555-4555-8555-555555555555";

    private static final String PAYMENT_TWO =
            "66666666-6666-4666-8666-666666666666";

    private static final String HEADER =
            "payment_id,payment_reference,amount_minor,currency\n";

    private final InternalPaymentCsvParser parser =
            new InternalPaymentCsvParser();

    @Test
    void parsesValidPaymentsWithTrustedContext() {
        List<PaymentRecord> payments = parse(
                HEADER
                        + row(PAYMENT_ONE, "PAY-001", "12550", "INR")
                        + row(PAYMENT_TWO, "PAY-002", "25000", "INR")
        );

        assertEquals(2, payments.size());

        PaymentRecord first = payments.getFirst();

        assertEquals(UUID.fromString(PAYMENT_ONE), first.id());
        assertEquals(TENANT_A, first.matchKey().tenantId());
        assertEquals("PROCESSOR_A", first.matchKey().provider());
        assertEquals("PAY-001", first.matchKey().paymentReference());
        assertEquals(12550L, first.amount().amountMinor());
        assertEquals("INR", first.amount().currency().getCurrencyCode());
    }

    @Test
    void acceptsBomAndQuotedCommaInReference() {
        List<PaymentRecord> payments = parse(
                "\uFEFF"
                        + HEADER
                        + row(PAYMENT_ONE, "\"PAY,001\"", "12550", "INR")
        );

        assertEquals(
                "PAY,001",
                payments.getFirst().matchKey().paymentReference()
        );
    }

    @Test
    void returnsImmutableList() {
        List<PaymentRecord> payments = parse(validFile());

        assertThrows(
                UnsupportedOperationException.class,
                payments::clear
        );
    }

    @Test
    void rejectsDuplicatePaymentIds() {
        PaymentCsvValidationException exception = assertThrows(
                PaymentCsvValidationException.class,
                () -> parse(
                        HEADER
                                + row(PAYMENT_ONE, "PAY-001", "100", "INR")
                                + row(PAYMENT_ONE, "PAY-002", "200", "INR")
                )
        );

        assertTrue(exception.getMessage().contains("Duplicate payment_id"));
    }

    @Test
    void rejectsDuplicateNormalizedPaymentReferences() {
        PaymentCsvValidationException exception = assertThrows(
                PaymentCsvValidationException.class,
                () -> parse(
                        HEADER
                                + row(PAYMENT_ONE, "PAY-001", "100", "INR")
                                + row(PAYMENT_TWO, " PAY-001 ", "200", "INR")
                )
        );

        assertTrue(
                exception.getMessage().contains(
                        "Duplicate payment_reference"
                )
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0",
            "-1",
            "+100",
            "12.50",
            "1e3",
            "9223372036854775808",
            ""
    })
    void rejectsInvalidAmounts(String amount) {
        assertThrows(
                PaymentCsvValidationException.class,
                () -> parse(
                        HEADER + row(PAYMENT_ONE, "PAY-001", amount, "INR")
                )
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"inr", "ZZZ", "IN", ""})
    void rejectsInvalidCurrencies(String currency) {
        assertThrows(
                PaymentCsvValidationException.class,
                () -> parse(
                        HEADER + row(PAYMENT_ONE, "PAY-001", "100", currency)
                )
        );
    }

    @Test
    void rejectsShortenedUuid() {
        assertThrows(
                PaymentCsvValidationException.class,
                () -> parse(
                        HEADER + row("1-1-1-1-1", "PAY-001", "100", "INR")
                )
        );
    }

    @Test
    void rejectsBlankReference() {
        assertThrows(
                PaymentCsvValidationException.class,
                () -> parse(
                        HEADER + row(PAYMENT_ONE, "   ", "100", "INR")
                )
        );
    }

    @Test
    void rejectsOverlongReference() {
        assertThrows(
                PaymentCsvValidationException.class,
                () -> parse(
                        HEADER
                                + row(
                                PAYMENT_ONE,
                                "P".repeat(256),
                                "100",
                                "INR"
                        )
                )
        );
    }

    @Test
    void rejectsControlCharacterInReference() {
        assertThrows(
                PaymentCsvValidationException.class,
                () -> parse(
                        HEADER
                                + row(
                                PAYMENT_ONE,
                                "\"PAY\n001\"",
                                "100",
                                "INR"
                        )
                )
        );
    }

    @Test
    void rejectsWrongHeaderOrder() {
        assertThrows(
                PaymentCsvValidationException.class,
                () -> parse(
                        "payment_reference,payment_id,amount_minor,currency\n"
                                + row(PAYMENT_ONE, "PAY-001", "100", "INR")
                )
        );
    }

    @Test
    void rejectsExtraTenantColumn() {
        assertThrows(
                PaymentCsvValidationException.class,
                () -> parse(
                        "payment_id,payment_reference,amount_minor,"
                                + "currency,tenant_id\n"
                                + PAYMENT_ONE
                                + ",PAY-001,100,INR,"
                                + TENANT_B
                                + "\n"
                )
        );
    }

    @Test
    void rejectsMissingField() {
        assertThrows(
                PaymentCsvValidationException.class,
                () -> parse(
                        HEADER + PAYMENT_ONE + ",PAY-001,100\n"
                )
        );
    }

    @Test
    void rejectsEmptyAndHeaderOnlyFiles() {
        assertThrows(
                PaymentCsvValidationException.class,
                () -> parse("")
        );

        assertThrows(
                PaymentCsvValidationException.class,
                () -> parse(HEADER)
        );
    }

    @Test
    void rejectsMalformedQuotedCsv() {
        assertThrows(
                PaymentCsvValidationException.class,
                () -> parse(
                        HEADER + PAYMENT_ONE + ",\"unfinished,100,INR"
                )
        );
    }

    @Test
    void rejectsInvalidUtf8() {
        byte[] invalid = {(byte) 0xC3, (byte) 0x28};

        assertThrows(
                PaymentCsvValidationException.class,
                () -> parser.parse(invalid, TENANT_A, "PROCESSOR_A")
        );
    }

    @Test
    void rejectsFileAboveByteLimit() {
        byte[] oversized = new byte[10 * 1024 * 1024 + 1];

        assertThrows(
                PaymentCsvValidationException.class,
                () -> parser.parse(oversized, TENANT_A, "PROCESSOR_A")
        );
    }

    @Test
    void rejectsMoreThanMaximumRows() {
        StringBuilder csv = new StringBuilder(HEADER);

        for (int index = 0; index < 100_001; index++) {
            csv.append(
                    row(
                            new UUID(0L, index + 1L).toString(),
                            "P-" + index,
                            "1",
                            "INR"
                    )
            );
        }

        PaymentCsvValidationException exception = assertThrows(
                PaymentCsvValidationException.class,
                () -> parse(csv.toString())
        );

        assertTrue(
                exception.getMessage().contains("100000 data records")
        );
    }

    @Test
    void bindsEachParseToItsSuppliedTenant() {
        byte[] contents = validFile().getBytes(StandardCharsets.UTF_8);

        PaymentRecord tenantA = parser.parse(
                contents,
                TENANT_A,
                "PROCESSOR_A"
        ).getFirst();

        PaymentRecord tenantB = parser.parse(
                contents,
                TENANT_B,
                "PROCESSOR_A"
        ).getFirst();

        assertEquals(TENANT_A, tenantA.matchKey().tenantId());
        assertEquals(TENANT_B, tenantB.matchKey().tenantId());
    }

    @Test
    void rejectsInvalidTrustedProviderAsCallerError() {
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(
                        validFile().getBytes(StandardCharsets.UTF_8),
                        TENANT_A,
                        " "
                )
        );
    }

    private List<PaymentRecord> parse(String csv) {
        return parser.parse(
                csv.getBytes(StandardCharsets.UTF_8),
                TENANT_A,
                "PROCESSOR_A"
        );
    }

    private String validFile() {
        return HEADER + row(PAYMENT_ONE, "PAY-001", "12550", "INR");
    }

    private String row(
            String id,
            String reference,
            String amount,
            String currency
    ) {
        return id
                + ","
                + reference
                + ","
                + amount
                + ","
                + currency
                + "\n";
    }
}