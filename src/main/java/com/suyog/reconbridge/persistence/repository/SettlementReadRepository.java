package com.suyog.reconbridge.persistence.repository;

import com.suyog.reconbridge.application.model.SettlementEvidence;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class SettlementReadRepository {

    private final JdbcTemplate jdbc;

    public SettlementReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long countBatches(
            UUID tenantId,
            UUID importId
    ) {
        Long count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM settlement_batch
                WHERE tenant_id = ?
                  AND import_id = ?
                """,
                Long.class,
                tenantId.toString(),
                importId.toString()
        );

        return count == null ? 0 : count;
    }

    public List<SettlementEvidence.Batch> batches(
            UUID tenantId,
            UUID importId,
            int size,
            long offset
    ) {
        return jdbc.query(
                """
                SELECT batch_id,
                       provider,
                       settlement_reference,
                       bank_account_reference,
                       declared_net_minor,
                       currency
                FROM settlement_batch
                WHERE tenant_id = ?
                  AND import_id = ?
                ORDER BY batch_id ASC
                LIMIT ? OFFSET ?
                """,
                (rs, rowNumber) -> new SettlementEvidence.Batch(
                        UUID.fromString(rs.getString("batch_id")),
                        rs.getString("provider"),
                        rs.getString("settlement_reference"),
                        rs.getString("bank_account_reference"),
                        rs.getLong("declared_net_minor"),
                        rs.getString("currency")
                ),
                tenantId.toString(),
                importId.toString(),
                size,
                offset
        );
    }

    public boolean batchExists(
            UUID tenantId,
            UUID importId,
            UUID batchId
    ) {
        Long count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM settlement_batch
                WHERE tenant_id = ?
                  AND import_id = ?
                  AND batch_id = ?
                """,
                Long.class,
                tenantId.toString(),
                importId.toString(),
                batchId.toString()
        );

        return count != null && count > 0;
    }

    public long countLines(
            UUID tenantId,
            UUID importId,
            UUID batchId
    ) {
        Long count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM settlement_line
                WHERE tenant_id = ?
                  AND import_id = ?
                  AND batch_id = ?
                """,
                Long.class,
                tenantId.toString(),
                importId.toString(),
                batchId.toString()
        );

        return count == null ? 0 : count;
    }

    public List<SettlementEvidence.Line> lines(
            UUID tenantId,
            UUID importId,
            UUID batchId,
            int size,
            long offset
    ) {
        return jdbc.query(
                """
                SELECT l.line_id,
                       l.batch_id,
                       l.payment_reference,
                       l.gross_minor,
                       l.fee_minor,
                       l.net_minor,
                       b.currency
                FROM settlement_line l
                JOIN settlement_batch b
                  ON b.tenant_id = l.tenant_id
                 AND b.import_id = l.import_id
                 AND b.batch_id = l.batch_id
                WHERE l.tenant_id = ?
                  AND l.import_id = ?
                  AND l.batch_id = ?
                ORDER BY l.line_id ASC
                LIMIT ? OFFSET ?
                """,
                (rs, rowNumber) -> new SettlementEvidence.Line(
                        UUID.fromString(rs.getString("line_id")),
                        UUID.fromString(rs.getString("batch_id")),
                        rs.getString("payment_reference"),
                        rs.getLong("gross_minor"),
                        rs.getLong("fee_minor"),
                        rs.getLong("net_minor"),
                        rs.getString("currency")
                ),
                tenantId.toString(),
                importId.toString(),
                batchId.toString(),
                size,
                offset
        );
    }
}