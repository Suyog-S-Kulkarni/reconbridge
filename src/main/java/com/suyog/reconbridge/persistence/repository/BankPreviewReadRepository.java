package com.suyog.reconbridge.persistence.repository;

import com.suyog.reconbridge.application.exception.BankPreviewException;
import com.suyog.reconbridge.domain.model.BankCredit;
import com.suyog.reconbridge.domain.model.MatchKey;
import com.suyog.reconbridge.domain.model.Money;
import com.suyog.reconbridge.domain.model.SettlementBatch;
import com.suyog.reconbridge.domain.model.SettlementKey;
import com.suyog.reconbridge.domain.model.SettlementLine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class BankPreviewReadRepository {

    private static final long MAX_RECORDS_PER_TABLE = 10_000;

    private final JdbcTemplate jdbc;

    public BankPreviewReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SettlementBatch> loadBatches(
            UUID tenantId,
            UUID importId
    ) {
        requireWithinLimit(
                countBatches(tenantId, importId),
                "settlement batches"
        );

        requireWithinLimit(
                countLines(tenantId, importId),
                "settlement lines"
        );

        List<BatchHeader> headers = jdbc.query(
                """
                SELECT batch_id,
                       provider,
                       bank_account_reference,
                       settlement_reference,
                       declared_net_minor,
                       currency
                FROM settlement_batch
                WHERE tenant_id = ?
                  AND import_id = ?
                ORDER BY batch_id
                """,
                (rs, rowNumber) -> new BatchHeader(
                        UUID.fromString(
                                rs.getString("batch_id")
                        ),
                        new SettlementKey(
                                tenantId,
                                rs.getString("provider"),
                                rs.getString("bank_account_reference"),
                                rs.getString("settlement_reference")
                        ),
                        Money.ofMinor(
                                rs.getLong("declared_net_minor"),
                                rs.getString("currency")
                        )
                ),
                tenantId.toString(),
                importId.toString()
        );

        List<AllocatedLine> storedLines = jdbc.query(
                """
                SELECT l.batch_id,
                       l.line_id,
                       l.payment_reference,
                       l.gross_minor,
                       l.fee_minor,
                       l.net_minor,
                       b.provider,
                       b.currency
                FROM settlement_line l
                JOIN settlement_batch b
                  ON b.tenant_id = l.tenant_id
                 AND b.import_id = l.import_id
                 AND b.batch_id = l.batch_id
                WHERE l.tenant_id = ?
                  AND l.import_id = ?
                ORDER BY l.batch_id, l.line_id
                """,
                (rs, rowNumber) -> new AllocatedLine(
                        UUID.fromString(
                                rs.getString("batch_id")
                        ),
                        new SettlementLine(
                                UUID.fromString(
                                        rs.getString("line_id")
                                ),
                                new MatchKey(
                                        tenantId,
                                        rs.getString("provider"),
                                        rs.getString("payment_reference")
                                ),
                                Money.ofMinor(
                                        rs.getLong("gross_minor"),
                                        rs.getString("currency")
                                ),
                                Money.ofMinor(
                                        rs.getLong("fee_minor"),
                                        rs.getString("currency")
                                ),
                                Money.ofMinor(
                                        rs.getLong("net_minor"),
                                        rs.getString("currency")
                                )
                        )
                ),
                tenantId.toString(),
                importId.toString()
        );

        Map<UUID, List<SettlementLine>> linesByBatch = new HashMap<>();

        for (AllocatedLine allocated : storedLines) {
            linesByBatch.computeIfAbsent(
                    allocated.batchId(),
                    ignored -> new ArrayList<>()
            ).add(allocated.line());
        }

        List<SettlementBatch> batches = new ArrayList<>();

        for (BatchHeader header : headers) {
            List<SettlementLine> batchLines = linesByBatch.getOrDefault(
                    header.id(),
                    List.of()
            );

            batches.add(
                    new SettlementBatch(
                            header.id(),
                            header.key(),
                            batchLines,
                            header.netAmount()
                    )
            );
        }

        return List.copyOf(batches);
    }

    public List<BankCredit> loadCredits(
            UUID tenantId,
            UUID importId
    ) {
        Long count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM bank_credit
                WHERE tenant_id = ?
                  AND import_id = ?
                """,
                Long.class,
                tenantId.toString(),
                importId.toString()
        );

        requireWithinLimit(
                count == null ? 0L : count,
                "bank credits"
        );

        List<BankCredit> credits = jdbc.query(
                """
                SELECT credit_id,
                       provider,
                       bank_account_reference,
                       settlement_reference,
                       amount_minor,
                       currency
                FROM bank_credit
                WHERE tenant_id = ?
                  AND import_id = ?
                ORDER BY credit_id
                """,
                (rs, rowNumber) -> new BankCredit(
                        UUID.fromString(
                                rs.getString("credit_id")
                        ),
                        new SettlementKey(
                                tenantId,
                                rs.getString("provider"),
                                rs.getString("bank_account_reference"),
                                rs.getString("settlement_reference")
                        ),
                        Money.ofMinor(
                                rs.getLong("amount_minor"),
                                rs.getString("currency")
                        )
                ),
                tenantId.toString(),
                importId.toString()
        );

        return List.copyOf(credits);
    }

    private long countBatches(
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

        return count == null ? 0L : count;
    }

    private long countLines(
            UUID tenantId,
            UUID importId
    ) {
        Long count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM settlement_line
                WHERE tenant_id = ?
                  AND import_id = ?
                """,
                Long.class,
                tenantId.toString(),
                importId.toString()
        );

        return count == null ? 0L : count;
    }

    private void requireWithinLimit(
            long count,
            String recordType
    ) {
        if (count > MAX_RECORDS_PER_TABLE) {
            throw new BankPreviewException(
                    "PREVIEW_LIMIT_EXCEEDED",
                    "Synchronous preview supports at most "
                            + MAX_RECORDS_PER_TABLE
                            + " "
                            + recordType
                            + " per selected import."
            );
        }
    }

    private record BatchHeader(
            UUID id,
            SettlementKey key,
            Money netAmount
    ) {
    }

    private record AllocatedLine(
            UUID batchId,
            SettlementLine line
    ) {
    }
}