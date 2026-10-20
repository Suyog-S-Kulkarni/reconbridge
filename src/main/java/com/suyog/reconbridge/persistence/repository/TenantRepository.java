package com.suyog.reconbridge.persistence.repository;

import com.suyog.reconbridge.persistence.entity.TenantEntity;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

public interface TenantRepository
        extends Repository<TenantEntity, UUID> {

    <S extends TenantEntity> S saveAndFlush(S entity);

    Optional<TenantEntity> findById(UUID id);
}