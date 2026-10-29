package com.suyog.reconbridge.persistence.repository;

import com.suyog.reconbridge.application.model.ExceptionCaseReadModels.CaseSummary;
import com.suyog.reconbridge.application.model.ExceptionCaseReadModels.NoteSummary;
import com.suyog.reconbridge.domain.model.ExceptionCaseStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class ExceptionCaseQueryRepository {

    private final JdbcTemplate jdbcTemplate;

    public ExceptionCaseQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long countCases(
            UUID tenantId,
            ExceptionCaseStatus status,
            String assignedTo
    ) {
        CaseFilter filter = caseFilter(tenantId, status, assignedTo);

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM exception_case"
                        + filter.whereClause(),
                Long.class,
                filter.parameters().toArray()
        );

        return count == null ? 0 : count;
    }

    public List<CaseSummary> findCases(
            UUID tenantId,
            ExceptionCaseStatus status,
            String assignedTo,
            int size,
            long offset
    ) {
        CaseFilter filter = caseFilter(tenantId, status, assignedTo);

        List<Object> parameters =
                new ArrayList<>(filter.parameters());

        parameters.add(size);
        parameters.add(offset);

        String sql = """
                SELECT
                    id,
                    run_id,
                    item_type,
                    evidence_id,
                    reason,
                    status,
                    created_by,
                    assigned_to,
                    created_at_utc,
                    updated_at_utc,
                    version
                FROM exception_case
                """
                + filter.whereClause()
                + """
                
                ORDER BY created_at_utc DESC, id DESC
                LIMIT ? OFFSET ?
                """;

        return jdbcTemplate.query(
                sql,
                this::mapCase,
                parameters.toArray()
        );
    }

    public boolean caseExists(UUID tenantId, UUID caseId) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM exception_case
                WHERE tenant_id = ?
                  AND id = ?
                """,
                Long.class,
                tenantId.toString(),
                caseId.toString()
        );

        return count != null && count > 0;
    }

    public long countNotes(UUID tenantId, UUID caseId) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM investigation_note
                WHERE tenant_id = ?
                  AND case_id = ?
                """,
                Long.class,
                tenantId.toString(),
                caseId.toString()
        );

        return count == null ? 0 : count;
    }

    public List<NoteSummary> findNotes(
            UUID tenantId,
            UUID caseId,
            int size,
            long offset
    ) {
        return jdbcTemplate.query(
                """
                SELECT
                    id,
                    case_id,
                    author_subject,
                    note_text,
                    created_at_utc
                FROM investigation_note
                WHERE tenant_id = ?
                  AND case_id = ?
                ORDER BY created_at_utc ASC, id ASC
                LIMIT ? OFFSET ?
                """,
                this::mapNote,
                tenantId.toString(),
                caseId.toString(),
                size,
                offset
        );
    }

    private CaseFilter caseFilter(
            UUID tenantId,
            ExceptionCaseStatus status,
            String assignedTo
    ) {
        StringBuilder where =
                new StringBuilder(" WHERE tenant_id = ?");

        List<Object> parameters = new ArrayList<>();
        parameters.add(tenantId.toString());

        if (status != null) {
            where.append(" AND status = ?");
            parameters.add(status.name());
        }

        if (assignedTo != null) {
            where.append(" AND assigned_to = ?");
            parameters.add(assignedTo);
        }

        return new CaseFilter(
                where.toString(),
                List.copyOf(parameters)
        );
    }

    private CaseSummary mapCase(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new CaseSummary(
                UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("run_id")),
                resultSet.getString("item_type"),
                UUID.fromString(resultSet.getString("evidence_id")),
                resultSet.getString("reason"),
                ExceptionCaseStatus.valueOf(
                        resultSet.getString("status")
                ),
                resultSet.getString("created_by"),
                resultSet.getString("assigned_to"),
                resultSet.getObject(
                        "created_at_utc",
                        LocalDateTime.class
                ).toInstant(ZoneOffset.UTC),
                resultSet.getObject(
                        "updated_at_utc",
                        LocalDateTime.class
                ).toInstant(ZoneOffset.UTC),
                resultSet.getLong("version")
        );
    }

    private NoteSummary mapNote(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new NoteSummary(
                UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("case_id")),
                resultSet.getString("author_subject"),
                resultSet.getString("note_text"),
                resultSet.getObject(
                        "created_at_utc",
                        LocalDateTime.class
                ).toInstant(ZoneOffset.UTC)
        );
    }

    private record CaseFilter(
            String whereClause,
            List<Object> parameters
    ) {
    }
}