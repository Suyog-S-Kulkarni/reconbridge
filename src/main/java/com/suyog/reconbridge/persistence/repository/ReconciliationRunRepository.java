package com.suyog.reconbridge.persistence.repository;

import com.suyog.reconbridge.application.model.BankReconciliationPreview;
import com.suyog.reconbridge.application.model.StoredReconciliationRun;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
        BankReconciliationPreview report = run.report();

        jdbc.update(
                """
                INSERT INTO reconciliation_run (
                    tenant_id,
                    id,
                    settlement_import_id,
                    bank_import_id,
                    rule_version,
                    report_version,
                    created_by,
                    created_at_utc,
                    settlement_batch_count,
                    bank_credit_count,
                    matched_batch_count,
                    unresolved_batch_count,
                    orphan_bank_credit_count,
                    fully_reconciled
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                tenantId.toString(),
                run.id().toString(),
                report.settlementImportId().toString(),
                report.bankImportId().toString(),
                run.ruleVersion(),
                run.reportVersion(),
                run.createdBy(),
                LocalDateTime.ofInstant(
                        run.createdAt(),
                        ZoneOffset.UTC
                ),
                report.settlementBatchCount(),
                report.bankCreditCount(),
                report.matchedBatchCount(),
                report.unresolvedBatchCount(),
                report.orphanBankCreditCount(),
                report.fullyReconciled()
        );

        for (BankReconciliationPreview.BatchResult result
                : report.results()) {
            insertItem(
                    tenantId,
                    run.id(),
                    "BATCH",
                    result.settlementBatchId(),
                    result.outcome().name(),
                    json.writeValueAsString(result)
            );
        }

        for (BankReconciliationPreview.OrphanCredit orphan
                : report.orphanBankCredits()) {
            insertItem(
                    tenantId,
                    run.id(),
                    "ORPHAN",
                    orphan.creditId(),
                    "ORPHAN_BANK_CREDIT",
                    json.writeValueAsString(orphan)
            );
        }
    }

    public Optional<StoredReconciliationRun> find(
            UUID tenantId,
            UUID runId
    ) {
        List<Header> headers = jdbc.query(
                """
                SELECT id,
                       settlement_import_id,
                       bank_import_id,
                       rule_version,
                       report_version,
                       created_by,
                       created_at_utc,
                       settlement_batch_count,
                       bank_credit_count,
                       matched_batch_count,
                       unresolved_batch_count,
                       orphan_bank_credit_count,
                       fully_reconciled
                FROM reconciliation_run
                WHERE tenant_id = ? AND id = ?
                """,
                (rs, rowNumber) -> new Header(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(
                                rs.getString("settlement_import_id")
                        ),
                        UUID.fromString(
                                rs.getString("bank_import_id")
                        ),
                        rs.getString("rule_version"),
                        rs.getInt("report_version"),
                        rs.getString("created_by"),
                        rs.getObject(
                                "created_at_utc",
                                LocalDateTime.class
                        ),
                        rs.getLong("settlement_batch_count"),
                        rs.getLong("bank_credit_count"),
                        rs.getLong("matched_batch_count"),
                        rs.getLong("unresolved_batch_count"),
                        rs.getLong("orphan_bank_credit_count"),
                        rs.getBoolean("fully_reconciled")
                ),
                tenantId.toString(),
                runId.toString()
        );

        if (headers.isEmpty()) {
            return Optional.empty();
        }

        Header header = headers.get(0);

        if (header.reportVersion() != 1) {
            throw new IllegalStateException(
                    "Unsupported stored report version: "
                            + header.reportVersion()
            );
        }

        List<BankReconciliationPreview.BatchResult> results =
                jdbc.query(
                        """
                        SELECT payload
                        FROM reconciliation_run_item
                        WHERE tenant_id = ?
                          AND run_id = ?
                          AND item_type = 'BATCH'
                        ORDER BY evidence_id
                        """,
                        (rs, rowNumber) -> json.readValue(
                                rs.getString("payload"),
                                BankReconciliationPreview.BatchResult.class
                        ),
                        tenantId.toString(),
                        runId.toString()
                );

        List<BankReconciliationPreview.OrphanCredit> orphans =
                jdbc.query(
                        """
                        SELECT payload
                        FROM reconciliation_run_item
                        WHERE tenant_id = ?
                          AND run_id = ?
                          AND item_type = 'ORPHAN'
                        ORDER BY evidence_id
                        """,
                        (rs, rowNumber) -> json.readValue(
                                rs.getString("payload"),
                                BankReconciliationPreview.OrphanCredit.class
                        ),
                        tenantId.toString(),
                        runId.toString()
                );

        BankReconciliationPreview report =
                new BankReconciliationPreview(
                        header.settlementImportId(),
                        header.bankImportId(),
                        header.batchCount(),
                        header.creditCount(),
                        header.matchedCount(),
                        header.unresolvedCount(),
                        header.orphanCount(),
                        header.fullyReconciled(),
                        results,
                        orphans
                );

        return Optional.of(
                new StoredReconciliationRun(
                        header.id(),
                        header.ruleVersion(),
                        header.reportVersion(),
                        header.createdBy(),
                        header.createdAtUtc().toInstant(ZoneOffset.UTC),
                        report
                )
        );
    }

    private void insertItem(
            UUID tenantId,
            UUID runId,
            String itemType,
            UUID evidenceId,
            String outcome,
            String payload
    ) {
        jdbc.update(
                """
                INSERT INTO reconciliation_run_item (
                    tenant_id,
                    run_id,
                    item_type,
                    evidence_id,
                    outcome,
                    payload
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                tenantId.toString(),
                runId.toString(),
                itemType,
                evidenceId.toString(),
                outcome,
                payload
        );
    }

    private record Header(
            UUID id,
            UUID settlementImportId,
            UUID bankImportId,
            String ruleVersion,
            int reportVersion,
            String createdBy,
            LocalDateTime createdAtUtc,
            long batchCount,
            long creditCount,
            long matchedCount,
            long unresolvedCount,
            long orphanCount,
            boolean fullyReconciled
    ) {
    }
}