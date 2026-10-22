package com.suyog.reconbridge.ingestion;

import com.suyog.reconbridge.domain.model.BankCredit;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankStatementCsvParserTest {

    private static final UUID TENANT_ID = UUID.fromString(
            "11111111-1111-4111-8111-111111111111"
    );

    private static final String CREDIT_A =
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

    private static final String CREDIT_B =
            "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

    private static final String HEADER =
            "bank_credit_id,provider,bank_account_reference,"
                    + "settlement_reference,amount_minor,currency\n";

    private final BankStatementCsvParser parser =
            new BankStatementCsvParser();

    @Test
    void parsesValidCreditsUsingTrustedTenantIdentity() {
        String csv = HEADER
                + row(CREDIT_A, "SETTLEMENT-001", "12400", "INR")
                + row(CREDIT_B, "SETTLEMENT-002", "24700", "INR");

        List<BankCredit> credits = parse(csv);

        assertEquals(2, credits.size());

        BankCredit first = credits.get(0);

        assertEquals(UUID.fromString(CREDIT_A), first.id());
        assertEquals(TENANT_ID, first.settlementKey().tenantId());
        assertEquals("PROCESSOR_A", first.settlementKey().provider());

        assertEquals(
                "BANK-001",
                first.settlementKey().bankAccountReference()
        );

        assertEquals(
                "SETTLEMENT-001",
                first.settlementKey().settlementReference()
        );

        assertEquals(12400L, first.amount().amountMinor());
        assertEquals("INR", first.amount().currency().getCurrencyCode());
    }

    @Test
    void preservesDifferentCreditsWithTheSameSettlementKey() {
        String csv = HEADER
                + row(CREDIT_A, "SETTLEMENT-001", "12400", "INR")
                + row(CREDIT_B, "SETTLEMENT-001", "12400", "INR");

        List<BankCredit> credits = parse(csv);

        assertEquals(2, credits.size());

        assertEquals(
                credits.get(0).settlementKey(),
                credits.get(1).settlementKey()
        );
    }

    @Test
    void rejectsDuplicateCreditIds() {
        String csv = HEADER
                + row(CREDIT_A, "SETTLEMENT-001", "12400", "INR")
                + row(CREDIT_A, "SETTLEMENT-002", "24700", "INR");

        BankCsvValidationException exception = assertThrows(
                BankCsvValidationException.class,
                () -> parse(csv)
        );

        assertTrue(exception.getMessage().contains("Duplicate"));
    }

    @Test
    void rejectsInvalidAmounts() {
        List<String> invalidAmounts = List.of(
                "0",
                "-100",
                "12.50",
                "abc",
                "9223372036854775808"
        );

        for (String amount : invalidAmounts) {
            String csv = HEADER
                    + row(CREDIT_A, "SETTLEMENT-001", amount, "INR");

            assertThrows(
                    BankCsvValidationException.class,
                    () -> parse(csv),
                    "Expected rejection for amount: " + amount
            );
        }
    }

    @Test
    void rejectsInvalidCurrency() {
        for (String currency : List.of("inr", "ZZZ", "IN")) {
            String csv = HEADER
                    + row(CREDIT_A, "SETTLEMENT-001", "12400", currency);

            assertThrows(
                    BankCsvValidationException.class,
                    () -> parse(csv)
            );
        }
    }

    @Test
    void rejectsInvalidAndShortenedUuids() {
        for (String id : List.of("not-a-uuid", "1-1-1-1-1")) {
            String csv = HEADER
                    + row(id, "SETTLEMENT-001", "12400", "INR");

            assertThrows(
                    BankCsvValidationException.class,
                    () -> parse(csv)
            );
        }
    }

    @Test
    void rejectsIncorrectHeader() {
        String csv = HEADER.replace(
                "bank_credit_id",
                "credit_id"
        ) + row(CREDIT_A, "SETTLEMENT-001", "12400", "INR");

        assertThrows(
                BankCsvValidationException.class,
                () -> parse(csv)
        );
    }

    @Test
    void rejectsMissingOrExtraFields() {
        String missingField = HEADER
                + CREDIT_A
                + ",PROCESSOR_A,BANK-001,SETTLEMENT-001,12400\n";

        String extraField = HEADER
                + CREDIT_A
                + ",PROCESSOR_A,BANK-001,SETTLEMENT-001,12400,INR,EXTRA\n";

        assertThrows(
                BankCsvValidationException.class,
                () -> parse(missingField)
        );

        assertThrows(
                BankCsvValidationException.class,
                () -> parse(extraField)
        );
    }

    @Test
    void rejectsEmptyAndHeaderOnlyFiles() {
        assertThrows(
                BankCsvValidationException.class,
                () -> parse("")
        );

        assertThrows(
                BankCsvValidationException.class,
                () -> parse(HEADER)
        );
    }

    @Test
    void rejectsBlankRequiredReference() {
        String csv = HEADER
                + row(CREDIT_A, "   ", "12400", "INR");

        assertThrows(
                BankCsvValidationException.class,
                () -> parse(csv)
        );
    }

    @Test
    void acceptsUtf8Bom() {
        String csv = "\uFEFF"
                + HEADER
                + row(CREDIT_A, "SETTLEMENT-001", "12400", "INR");

        assertEquals(1, parse(csv).size());
    }

    @Test
    void rejectsInvalidUtf8() {
        byte[] invalidUtf8 = {
                (byte) 0xC3,
                (byte) 0x28
        };

        assertThrows(
                BankCsvValidationException.class,
                () -> parser.parse(invalidUtf8, TENANT_ID)
        );
    }

    @Test
    void supportsQuotedReferencesContainingCommas() {
        String csv = HEADER
                + CREDIT_A
                + ",PROCESSOR_A,BANK-001,\"SETTLEMENT,001\",12400,INR\n";

        List<BankCredit> credits = parse(csv);

        assertEquals(
                "SETTLEMENT,001",
                credits.get(0).settlementKey().settlementReference()
        );
    }

    @Test
    void rejectsMalformedCsvQuoting() {
        String csv = HEADER
                + CREDIT_A
                + ",PROCESSOR_A,BANK-001,\"unterminated,12400,INR";

        assertThrows(
                BankCsvValidationException.class,
                () -> parse(csv)
        );
    }

    @Test
    void returnsAnImmutableList() {
        List<BankCredit> credits = parse(
                HEADER + row(
                        CREDIT_A,
                        "SETTLEMENT-001",
                        "12400",
                        "INR"
                )
        );

        assertThrows(
                UnsupportedOperationException.class,
                () -> credits.add(credits.get(0))
        );
    }

    @Test
    void rejectsFilesAboveTheSizeLimit() {
        byte[] oversizedFile = new byte[10 * 1024 * 1024 + 1];

        assertThrows(
                BankCsvValidationException.class,
                () -> parser.parse(oversizedFile, TENANT_ID)
        );
    }

    private List<BankCredit> parse(String csv) {
        return parser.parse(
                csv.getBytes(StandardCharsets.UTF_8),
                TENANT_ID
        );
    }

    private String row(
            String id,
            String settlementReference,
            String amountMinor,
            String currency
    ) {
        return id
                + ",PROCESSOR_A,BANK-001,"
                + settlementReference
                + ","
                + amountMinor
                + ","
                + currency
                + "\n";
    }
}