package com.suyog.reconbridge.persistence.repository;

import com.suyog.reconbridge.domain.model.SourceType;
import com.suyog.reconbridge.persistence.entity.ImportBatchEntity;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

public interface ImportBatchRepository
        extends Repository<ImportBatchEntity, UUID> {

    <S extends ImportBatchEntity> S saveAndFlush(S entity);

    Optional<ImportBatchEntity> findByTenantIdAndId(
            UUID tenantId,
            UUID id
    );

    Optional<ImportBatchEntity>
    findByTenantIdAndSourceTypeAndSourceSystemAndFileHash(
            UUID tenantId,
            SourceType sourceType,
            String sourceSystem,
            String fileHash
    );
}