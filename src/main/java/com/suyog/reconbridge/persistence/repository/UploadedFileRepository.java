package com.suyog.reconbridge.persistence.repository;

import com.suyog.reconbridge.storage.StoredFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
public class UploadedFileRepository {
    private final JdbcTemplate jdbc;

    public UploadedFileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(UUID tenantId, UUID importId, StoredFile file) {
        jdbc.update("""
                INSERT INTO import_uploaded_file
                    (tenant_id, import_id, storage_uri, sha256, size_bytes)
                VALUES (?, ?, ?, ?, ?)
                """,
                tenantId.toString(), importId.toString(),
                file.uri(), file.sha256(), file.size());
    }

    public Optional<OwnedFile> find(UUID tenantId, UUID importId) {
        return jdbc.query("""
                SELECT storage_uri, sha256, size_bytes
                FROM import_uploaded_file
                WHERE tenant_id = ? AND import_id = ?
                """,
                (rs, row) -> new OwnedFile(
                        rs.getString("storage_uri"),
                        rs.getString("sha256"),
                        rs.getLong("size_bytes")),
                tenantId.toString(), importId.toString())
                .stream().findFirst();
    }

    public record OwnedFile(String uri, String sha256, long size) {
    }
}
