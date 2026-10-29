package com.suyog.reconbridge.persistence.repository;

import com.suyog.reconbridge.application.model.BankReconciliationPreview;
import com.suyog.reconbridge.application.model.ReconciliationReadModels.RunSummary;
import com.suyog.reconbridge.application.model.ReconciliationReadModels.UnresolvedOutcome;
import com.suyog.reconbridge.application.model.ReconciliationReadModels.UnresolvedResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class ReconciliationQueryRepository {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public ReconciliationQueryRepository(
            JdbcTemplate jdbc,
            JsonMapper json
    ) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public long countRuns(UUID tenantId) {
        Long count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM reconciliation_run
                WHERE tenant_id = ?
                """,
                Long.class,
                tenantId.toString()
        );

        return count == null ? 0L : count;
    }

    public List<RunSummary> findRuns(
            UUID tenantId,
            int size,
            long offset
    ) {
        return jdbc.query(
                """
                SELECT id,
                       settlement_import_id,
                       bank_import_id,
                       rule_version,
                       created_by,
                       created_at_utc,
                       matched_batch_count,
                       unresolved_batch_count,
                       orphan_bank_credit_count,
                       fully_reconciled
                FROM reconciliation_run
                WHERE tenant_id = ?
                ORDER BY created_at_utc DESC, id DESC
                LIMIT ? OFFSET ?
                """,
                (rs, rowNumber) -> new RunSummary(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(
                                rs.getString("settlement_import_id")
                        ),
                        UUID.fromString(
                                rs.getString("bank_import_id")
                        ),
                        rs.getString("rule_version"),
                        rs.getString("created_by"),
                        rs.getObject(
                                "created_at_utc",
                                LocalDateTime.class
                        ).toInstant(ZoneOffset.UTC),
                        rs.getLong("matched_batch_count"),
                        rs.getLong("unresolved_batch_count"),
                        rs.getLong("orphan_bank_credit_count"),
                        rs.getBoolean("fully_reconciled")
                ),
                tenantId.toString(),
                size,
                offset
        );
    }

    public boolean runExists(UUID tenantId, UUID runId) {
        Integer exists = jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM reconciliation_run
                    WHERE tenant_id = ? AND id = ?
                )
                """,
                Integer.class,
                tenantId.toString(),
                runId.toString()
        );

        return exists != null && exists == 1;
    }

    public long countUnresolved(
            UUID tenantId,
            UUID runId,
            UnresolvedOutcome outcome
    ) {
        List<Object> arguments = arguments(
                tenantId,
                runId,
                outcome
        );

        Long count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM reconciliation_run_item
                WHERE tenant_id = ?
                  AND run_id = ?
                """ + outcomeCondition(outcome),
                Long.class,
                arguments.toArray()
        );

        return count == null ? 0L : count;
    }

    public List<UnresolvedResult> findUnresolved(
            UUID tenantId,
            UUID runId,
            UnresolvedOutcome outcome,
            int size,
            long offset
    ) {
        List<Object> arguments = arguments(
                tenantId,
                runId,
                outcome
        );

        arguments.add(size);
        arguments.add(offset);

        String sql = """
                SELECT item_type, evidence_id, outcome, payload
                FROM reconciliation_run_item
                WHERE tenant_id = ?
                  AND run_id = ?
                """
                + outcomeCondition(outcome)
                + """
                 ORDER BY item_type ASC, evidence_id ASC
                 LIMIT ? OFFSET ?
                """;

        return jdbc.query(
                sql,
                (rs, rowNumber) -> {
                    String itemType = rs.getString("item_type");
                    UUID evidenceId = UUID.fromString(
                            rs.getString("evidence_id")
                    );
                    String storedOutcome = rs.getString("outcome");
                    String payload = rs.getString("payload");

                    return switch (itemType) {
                        case "BATCH" -> new UnresolvedResult(
                                itemType,
                                evidenceId,
                                storedOutcome,
                                json.readValue(
                                        payload,
                                        BankReconciliationPreview
                                                .BatchResult.class
                                ),
                                null
                        );

                        case "ORPHAN" -> new UnresolvedResult(
                                itemType,
                                evidenceId,
                                storedOutcome,
                                null,
                                json.readValue(
                                        payload,
                                        BankReconciliationPreview
                                                .OrphanCredit.class
                                )
                        );

                        default -> throw new IllegalStateException(
                                "Unsupported stored item type: " + itemType
                        );
                    };
                },
                arguments.toArray()
        );
    }

    private String outcomeCondition(UnresolvedOutcome outcome) {
        return outcome == null
                ? " AND outcome <> 'MATCHED' "
                : " AND outcome = ? ";
    }

    private List<Object> arguments(
            UUID tenantId,
            UUID runId,
            UnresolvedOutcome outcome
    ) {
        List<Object> arguments = new ArrayList<>();
        arguments.add(tenantId.toString());
        arguments.add(runId.toString());

        if (outcome != null) {
            arguments.add(outcome.name());
        }

        return arguments;
    }
}