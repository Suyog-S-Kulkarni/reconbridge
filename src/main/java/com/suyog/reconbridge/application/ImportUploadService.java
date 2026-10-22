package com.suyog.reconbridge.application;

import com.suyog.reconbridge.application.exception.DuplicateImportException;
import com.suyog.reconbridge.application.exception.FileStorageException;
import com.suyog.reconbridge.application.exception.InvalidUploadException;
import com.suyog.reconbridge.application.exception.TenantNotProvisionedException;
import com.suyog.reconbridge.application.model.ImportBatchSnapshot;
import com.suyog.reconbridge.domain.model.SourceType;
import com.suyog.reconbridge.persistence.repository.TenantRepository;
import com.suyog.reconbridge.storage.FileStorage;
import com.suyog.reconbridge.storage.StoredFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

@Service
public class ImportUploadService {
    private static final Logger log = LoggerFactory.getLogger(ImportUploadService.class);
    private final FileStorage storage;
    private final UploadedImportRegistrationService registration;
    private final TenantRepository tenants;

    public ImportUploadService(
            FileStorage storage,
            UploadedImportRegistrationService registration,
            TenantRepository tenants
    ) {
        this.storage = storage;
        this.registration = registration;
        this.tenants = tenants;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ImportBatchSnapshot upload(
            UUID tenantId, SourceType sourceType, String sourceSystem,
            LocalDate businessDate, MultipartFile file
    ) {
        tenants.findById(tenantId).orElseThrow(TenantNotProvisionedException::new);
        String name = validateFile(file);
        StoredFile stored = null;
        try (InputStream input = file.getInputStream()) {
            stored = storage.store(input);
        } catch (IOException exception) {
            if (stored != null) {
                cleanup(stored, exception);
            }
            throw new FileStorageException("Unable to store the uploaded file.", exception);
        }

        try {
            return registration.register(
                    tenantId, sourceType, sourceSystem, name, businessDate, stored);
        } catch (DuplicateImportException | TenantNotProvisionedException
                 | DataIntegrityViolationException | IllegalArgumentException exception) {
            cleanup(stored, exception);
            throw exception;
        } catch (RuntimeException exception) {
            log.error("Registration outcome uncertain; retained storage key {} for tenant {}",
                    stored.key(), tenantId, exception);
            throw exception;
        }
    }

    private String validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidUploadException("The uploaded file must not be empty.");
        }
        String name = file.getOriginalFilename();
        if (name == null) {
            throw new InvalidUploadException("A filename is required.");
        }
        name = name.trim();
        if (name.isBlank() || name.length() > 255 || name.contains("/")
                || name.contains("\\") || name.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidUploadException("Invalid filename.");
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new InvalidUploadException("The filename must have a .csv extension.");
        }
        return name;
    }

    private void cleanup(StoredFile file, Throwable original) {
        try {
            storage.delete(file);
        } catch (IOException | RuntimeException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
            log.error("Failed to remove unregistered storage key {}", file.key(), cleanupFailure);
        }
    }
}
