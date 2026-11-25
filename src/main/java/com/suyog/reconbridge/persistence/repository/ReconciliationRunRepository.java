package com.suyog.reconbridge.persistence.repository;

import com.suyog.reconbridge.application.model.BankReconciliationPreview;
import com.suyog.reconbridge.application.model.ReconciliationReportSnapshot;
import com.suyog.reconbridge.application.model.StoredReconciliationRun;
import com.suyog.reconbridge.application.model.ThreeSourceReconciliationPreview;
import com.suyog.reconbridge.domain.model.MatchOutcome;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

@Repository
public class ReconciliationRunRepository {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public ReconciliationRunRepository(
            JdbcTemplate jdbc,
            JsonMapper json
    ) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void save(
            UUID tenantId,
            StoredReconciliationRun run
    ) {
        ThreeSourceReconciliationPreview three =
                run.report() instanceof ThreeSourceReconciliationPreview value
                        ? value
                        : null;

        if (three != null) {
            validateThreeSource(three);
        }

        BankReconciliationPreview bank =
                run.report() instanceof BankReconciliationPreview value
                        ? value
                        : bankSection(three);

        jdbc.update(
                """
                INSERT INTO reconciliation_run (
                    tenant_id,
                    id,
                    settlement_import_id,
                    bank_import_id,
                    payment_import_id,
                    rule_version,
                    report_version,
                    created_by,
                    created_at_utc,
                    settlement_batch_count,
                    bank_credit_count,
                    matched_batch_count,
                    unresolved_batch_count,
                    orphan_bank_credit_count,
                    fully_reconciled,
                    payment_count,
                    settlement_line_count,
                    matched_payment_count,
                    unresolved_payment_count,
                    orphan_settlement_line_count
                )
                VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                )
                """,
                tenantId.toString(),
                run.id().toString(),
                bank.settlementImportId().toString(),
                bank.bankImportId().toString(),
                three == null
                        ? null
                        : three.paymentImportId().toString(),
                run.ruleVersion(),
                run.reportVersion(),
                run.createdBy(),
                LocalDateTime.ofInstant(
                        run.createdAt(),
                        ZoneOffset.UTC
                ),
                bank.settlementBatchCount(),
                bank.bankCreditCount(),
                bank.matchedBatchCount(),
                bank.unresolvedBatchCount(),
                bank.orphanBankCreditCount(),
                three == null
                        ? bank.fullyReconciled()
                        : three.fullyReconciled(),
                three == null ? 0L : three.paymentCount(),
                three == null ? 0L : three.settlementLineCount(),
                three == null
                        ? 0L
                        : three.paymentOutcomeCounts()
                        .get(MatchOutcome.MATCHED),
                three == null ? 0L : three.unresolvedPaymentCount(),
                three == null ? 0L : three.orphanSettlementLineCount()
        );

        for (BankReconciliationPreview.BatchResult result
                : bank.results()) {
            insertItem(
                    tenantId,
                    run.id(),
                    "BATCH",
                    result.settlementBatchId(),
                    result.outcome().name(),
                    result
            );
        }

        for (BankReconciliationPreview.OrphanCredit orphan
                : bank.orphanBankCredits()) {
            insertItem(
                    tenantId,
                    run.id(),
                    "ORPHAN",
                    orphan.creditId(),
                    "ORPHAN_BANK_CREDIT",
                    orphan
            );
        }

        if (three != null) {
            for (ThreeSourceReconciliationPreview.PaymentResult result
                    : three.paymentResults()) {
                insertItem(
                        tenantId,
                        run.id(),
                        "PAYMENT",
                        result.paymentId(),
                        result.outcome().name(),
                        result
                );
            }

            for (ThreeSourceReconciliationPreview.OrphanSettlementLine orphan
                    : three.orphanSettlementLines()) {
                insertItem(
                        tenantId,
                        run.id(),
                        "ORPHAN_LINE",
                        orphan.lineId(),
                        "ORPHAN_SETTLEMENT_LINE",
                        orphan
                );
            }
        }
    }

    @Transactional(
            readOnly = true,
            isolation = Isolation.REPEATABLE_READ
    )
    public Optional<StoredReconciliationRun> find(
            UUID tenantId,
            UUID runId
    ) {
        List<Header> headers = jdbc.query(
                """
                SELECT id, payment_import_id,
                       settlement_import_id, bank_import_id,
                       rule_version, report_version,
                       created_by, created_at_utc,
                       payment_count, settlement_line_count,
                       settlement_batch_count, bank_credit_count,
                       matched_payment_count, unresolved_payment_count,
                       matched_batch_count, unresolved_batch_count,
                       orphan_settlement_line_count,
                       orphan_bank_credit_count, fully_reconciled
                FROM reconciliation_run
                WHERE tenant_id = ? AND id = ?
                """,
                (rs, rowNumber) -> readHeader(rs),
                tenantId.toString(),
                runId.toString()
        );

        if (headers.isEmpty()) {
            return Optional.empty();
        }

        Header h = headers.getFirst();

        if (h.version() != 1 && h.version() != 2) {
            throw new IllegalStateException(
                    "Unsupported stored report version: " + h.version()
            );
        }

        List<Item> items = jdbc.query(
                """
                SELECT item_type, evidence_id, outcome, payload
                FROM reconciliation_run_item
                WHERE tenant_id = ? AND run_id = ?
                ORDER BY item_type, evidence_id
                """,
                (rs, rowNumber) -> new Item(
                        rs.getString("item_type"),
                        UUID.fromString(rs.getString("evidence_id")),
                        rs.getString("outcome"),
                        rs.getString("payload")
                ),
                tenantId.toString(),
                runId.toString()
        );

        Set<String> allowed = h.version() == 1
                ? Set.of("BATCH", "ORPHAN")
                : Set.of("BATCH", "ORPHAN", "PAYMENT", "ORPHAN_LINE");

        if (items.stream().anyMatch(item -> !allowed.contains(item.type()))) {
            throw new IllegalStateException(
                    "Stored item type is incompatible with report version."
            );
        }

        List<BankReconciliationPreview.BatchResult> batches = decode(
                items,
                "BATCH",
                BankReconciliationPreview.BatchResult.class,
                BankReconciliationPreview.BatchResult::settlementBatchId,
                result -> result.outcome().name()
        );

        List<BankReconciliationPreview.OrphanCredit> credits = decode(
                items,
                "ORPHAN",
                BankReconciliationPreview.OrphanCredit.class,
                BankReconciliationPreview.OrphanCredit::creditId,
                ignored -> "ORPHAN_BANK_CREDIT"
        );

        Map<MatchOutcome, Long> bankCounts = counts(
                batches,
                BankReconciliationPreview.BatchResult::outcome
        );

        require(
                h.batches() == batches.size()
                        && h.matchedBatches()
                        == bankCounts.get(MatchOutcome.MATCHED)
                        && h.unresolvedBatches()
                        == h.batches() - h.matchedBatches()
                        && h.orphanCredits() == credits.size(),
                "Stored bank summary does not match stored items."
        );

        ReconciliationReportSnapshot report;

        if (h.version() == 1) {
            report = new BankReconciliationPreview(
                    h.settlementImport(),
                    h.bankImport(),
                    h.batches(),
                    h.credits(),
                    h.matchedBatches(),
                    h.unresolvedBatches(),
                    h.orphanCredits(),
                    h.fullyReconciled(),
                    batches,
                    credits
            );
        } else {
            List<ThreeSourceReconciliationPreview.PaymentResult> payments =
                    decode(
                            items,
                            "PAYMENT",
                            ThreeSourceReconciliationPreview.PaymentResult.class,
                            ThreeSourceReconciliationPreview.PaymentResult::paymentId,
                            result -> result.outcome().name()
                    );

            List<ThreeSourceReconciliationPreview.OrphanSettlementLine> lines =
                    decode(
                            items,
                            "ORPHAN_LINE",
                            ThreeSourceReconciliationPreview.OrphanSettlementLine.class,
                            ThreeSourceReconciliationPreview.OrphanSettlementLine::lineId,
                            ignored -> "ORPHAN_SETTLEMENT_LINE"
                    );

            Map<MatchOutcome, Long> paymentCounts = counts(
                    payments,
                    ThreeSourceReconciliationPreview.PaymentResult::outcome
            );

            require(
                    h.matchedPayments()
                            == paymentCounts.get(MatchOutcome.MATCHED),
                    "Stored matched-payment count is inconsistent."
            );

            ThreeSourceReconciliationPreview three =
                    new ThreeSourceReconciliationPreview(
                            h.paymentImport(),
                            h.settlementImport(),
                            h.bankImport(),
                            h.payments(),
                            h.lines(),
                            h.batches(),
                            h.credits(),
                            h.unresolvedPayments(),
                            h.unresolvedBatches(),
                            h.orphanLines(),
                            h.orphanCredits(),
                            h.fullyReconciled(),
                            paymentCounts,
                            bankCounts,
                            payments,
                            lines,
                            batches,
                            credits
                    );

            validateThreeSource(three);
            report = three;
        }

        return Optional.of(
                new StoredReconciliationRun(
                        h.id(),
                        h.rule(),
                        h.version(),
                        h.actor(),
                        h.createdAt(),
                        report
                )
        );
    }

    private BankReconciliationPreview bankSection(
            ThreeSourceReconciliationPreview report
    ) {
        long matched = report.bankOutcomeCounts()
                .get(MatchOutcome.MATCHED);

        return new BankReconciliationPreview(
                report.settlementImportId(),
                report.bankImportId(),
                report.settlementBatchCount(),
                report.bankCreditCount(),
                matched,
                report.unresolvedBatchCount(),
                report.orphanBankCreditCount(),
                report.settlementBatchCount() > 0
                        && report.unresolvedBatchCount() == 0
                        && report.orphanBankCreditCount() == 0,
                report.bankResults(),
                report.orphanBankCredits()
        );
    }

    private void validateThreeSource(
            ThreeSourceReconciliationPreview report
    ) {
        require(
                report.paymentImportId() != null
                        && report.settlementImportId() != null
                        && report.bankImportId() != null,
                "All three import IDs are required."
        );

        require(
                !report.paymentImportId()
                        .equals(report.settlementImportId())
                        && !report.paymentImportId()
                        .equals(report.bankImportId())
                        && !report.settlementImportId()
                        .equals(report.bankImportId()),
                "Three distinct imports are required."
        );

        Map<MatchOutcome, Long> paymentCounts = counts(
                report.paymentResults(),
                ThreeSourceReconciliationPreview.PaymentResult::outcome
        );

        Map<MatchOutcome, Long> bankCounts = counts(
                report.bankResults(),
                BankReconciliationPreview.BatchResult::outcome
        );

        require(
                report.paymentCount() == report.paymentResults().size()
                        && report.settlementBatchCount()
                        == report.bankResults().size()
                        && report.orphanSettlementLineCount()
                        == report.orphanSettlementLines().size()
                        && report.orphanBankCreditCount()
                        == report.orphanBankCredits().size()
                        && report.settlementLineCount()
                        >= report.orphanSettlementLineCount()
                        && report.bankCreditCount()
                        >= report.orphanBankCreditCount(),
                "Report counts do not match its results."
        );

        require(
                report.paymentOutcomeCounts().equals(paymentCounts)
                        && report.bankOutcomeCounts().equals(bankCounts),
                "Outcome counts do not match report results."
        );

        require(
                report.unresolvedPaymentCount()
                        == report.paymentCount()
                        - paymentCounts.get(MatchOutcome.MATCHED)
                        && report.unresolvedBatchCount()
                        == report.settlementBatchCount()
                        - bankCounts.get(MatchOutcome.MATCHED),
                "Unresolved counts do not match report results."
        );

        boolean fullyReconciled =
                report.paymentCount() > 0
                        && report.settlementBatchCount() > 0
                        && report.unresolvedPaymentCount() == 0
                        && report.unresolvedBatchCount() == 0
                        && report.orphanSettlementLineCount() == 0
                        && report.orphanBankCreditCount() == 0;

        require(
                report.fullyReconciled() == fullyReconciled,
                "fullyReconciled does not match report results."
        );
    }

    private <T> Map<MatchOutcome, Long> counts(
            List<T> results,
            Function<T, MatchOutcome> outcome
    ) {
        EnumMap<MatchOutcome, Long> values =
                new EnumMap<>(MatchOutcome.class);

        for (MatchOutcome value : MatchOutcome.values()) {
            values.put(value, 0L);
        }

        for (T result : results) {
            values.merge(outcome.apply(result), 1L, Long::sum);
        }

        return Map.copyOf(values);
    }

    private <T> List<T> decode(
            List<Item> items,
            String itemType,
            Class<T> target,
            Function<T, UUID> identity,
            Function<T, String> outcome
    ) {
        return items.stream()
                .filter(item -> itemType.equals(item.type()))
                .map(item -> {
                    T value = json.readValue(item.payload(), target);

                    require(
                            item.evidenceId().equals(identity.apply(value))
                                    && item.outcome().equals(outcome.apply(value)),
                            "Stored item metadata and JSON payload disagree."
                    );

                    return value;
                })
                .toList();
    }

    private void insertItem(
            UUID tenantId,
            UUID runId,
            String itemType,
            UUID evidenceId,
            String outcome,
            Object payload
    ) {
        jdbc.update(
                """
                INSERT INTO reconciliation_run_item (
                    tenant_id, run_id, item_type,
                    evidence_id, outcome, payload
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                tenantId.toString(),
                runId.toString(),
                itemType,
                evidenceId.toString(),
                outcome,
                json.writeValueAsString(payload)
        );
    }

    private Header readHeader(ResultSet rs) throws SQLException {
        String paymentImport = rs.getString("payment_import_id");

        return new Header(
                UUID.fromString(rs.getString("id")),
                paymentImport == null ? null : UUID.fromString(paymentImport),
                UUID.fromString(rs.getString("settlement_import_id")),
                UUID.fromString(rs.getString("bank_import_id")),
                rs.getString("rule_version"),
                rs.getInt("report_version"),
                rs.getString("created_by"),
                rs.getObject(
                        "created_at_utc",
                        LocalDateTime.class
                ).toInstant(ZoneOffset.UTC),
                rs.getLong("payment_count"),
                rs.getLong("settlement_line_count"),
                rs.getLong("settlement_batch_count"),
                rs.getLong("bank_credit_count"),
                rs.getLong("matched_payment_count"),
                rs.getLong("unresolved_payment_count"),
                rs.getLong("matched_batch_count"),
                rs.getLong("unresolved_batch_count"),
                rs.getLong("orphan_settlement_line_count"),
                rs.getLong("orphan_bank_credit_count"),
                rs.getBoolean("fully_reconciled")
        );
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record Item(
            String type,
            UUID evidenceId,
            String outcome,
            String payload
    ) {
    }

    private record Header(
            UUID id,
            UUID paymentImport,
            UUID settlementImport,
            UUID bankImport,
            String rule,
            int version,
            String actor,
            Instant createdAt,
            long payments,
            long lines,
            long batches,
            long credits,
            long matchedPayments,
            long unresolvedPayments,
            long matchedBatches,
            long unresolvedBatches,
            long orphanLines,
            long orphanCredits,
            boolean fullyReconciled
    ) {
    }
}