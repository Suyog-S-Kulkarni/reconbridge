package com.suyog.reconbridge.application;

import com.suyog.reconbridge.application.model.ImportBatchSnapshot;
import com.suyog.reconbridge.domain.model.ImportStatus;
import com.suyog.reconbridge.domain.model.SourceType;
import com.suyog.reconbridge.persistence.entity.TenantEntity;
import com.suyog.reconbridge.persistence.repository.TenantRepository;
import com.suyog.reconbridge.storage.LocalStoredFileReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SettlementImportProcessingIntegrationTest {
    private static final Path ROOT = temporaryRoot();
    private static final String BATCH_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final String HEADER = "batchId,settlementReference,bankAccountReference,"
            + "declaredNetMinor,lineId,paymentReference,grossMinor,feeMinor,netMinor,currency\n";
    private static final String VALID = HEADER
            + BATCH_ID + ",SET-001,BANK-001,37100,11111111-1111-4111-8111-111111111111,PAY-001,12550,150,12400,INR\n"
            + BATCH_ID + ",SET-001,BANK-001,37100,22222222-2222-4222-8222-222222222222,PAY-002,25000,300,24700,INR\n";

    @Autowired private MockMvc mvc;
    @Autowired private TenantRepository tenants;
    @Autowired private ImportRegistrationService registration;
    @Autowired private ImportLifecycleService lifecycle;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private JwtDecoder decoder;
    @MockitoSpyBean private LocalStoredFileReader reader;
    private UUID tenantA;
    private UUID tenantB;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("reconbridge.storage.root", () -> ROOT.toString());
        registry.add("reconbridge.storage.max-bytes", () -> "10485760");
    }

    @BeforeEach
    void setUp() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        tenants.saveAndFlush(new TenantEntity(tenantA, "Processing A"));
        tenants.saveAndFlush(new TenantEntity(tenantB, "Processing B"));
        token("a", tenantA, List.of("IMPORTER"));
        token("b", tenantB, List.of("IMPORTER"));
        token("auditor", tenantA, List.of("AUDITOR"));
    }

    @AfterEach
    void cleanUp() throws IOException {
        for (String table : List.of("settlement_line", "settlement_batch",
                "import_uploaded_file", "import_batch", "tenant")) {
            // Table names are a fixed test-owned whitelist, never request input.
            String column = table.equals("tenant") ? "id" : "tenant_id";
            jdbc.update("DELETE FROM " + table + " WHERE " + column + " IN (?, ?)",
                    tenantA.toString(), tenantB.toString());
        }
        try (var files = Files.list(ROOT)) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    @Test
    void uploadAndProcessPersistsEvidenceAndMarksReady() throws Exception {
        UUID id = upload(VALID, "PROCESSOR_SETTLEMENT");
        process("a", id, 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported.status").value("READY"))
                .andExpect(jsonPath("$.imported.rowCount").value(2))
                .andExpect(jsonPath("$.imported.version").value(2))
                .andExpect(jsonPath("$.code").value("READY"));
        assertThat(count("settlement_batch", id)).isEqualTo(1);
        assertThat(count("settlement_line", id)).isEqualTo(2);
        assertThat(count("import_uploaded_file", id)).isEqualTo(1);
    }

    @Test
    void invalidCsvIsRejectedWithoutPersistingEvidence() throws Exception {
        UUID id = upload(VALID.replace("12550,150,12400", "12550,150,12401"), "PROCESSOR_SETTLEMENT");
        process("a", id, 0)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.imported.status").value("REJECTED"))
                .andExpect(jsonPath("$.code").value("CSV_REJECTED"));
        assertNoEvidence(id);
        assertThat(lifecycle.get(tenantA, id).rowCount()).isZero();
    }

    @Test
    void missingFileIsFailed() throws Exception {
        UUID id = upload(VALID, "PROCESSOR_SETTLEMENT");
        Files.delete(filePath(id));
        process("a", id, 0)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.imported.status").value("FAILED"))
                .andExpect(jsonPath("$.code").value("FILE_UNAVAILABLE"));
        assertNoEvidence(id);
    }

    @Test
    void changedFileIsFailedBeforeParsing() throws Exception {
        UUID id = upload(VALID, "PROCESSOR_SETTLEMENT");
        Files.writeString(filePath(id), "changed", StandardCharsets.UTF_8);
        process("a", id, 0)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.imported.status").value("FAILED"))
                .andExpect(jsonPath("$.code").value("HASH_MISMATCH"));
        assertNoEvidence(id);
    }

    @Test
    void metadataOnlyImportCannotBeProcessed() throws Exception {
        ImportBatchSnapshot imported = registration.register(tenantA,
                SourceType.PROCESSOR_SETTLEMENT, "PROCESSOR_A", "test.csv",
                "demo://test.csv", "a".repeat(64), LocalDate.of(2026, 10, 22));
        process("a", imported.id(), 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UPLOAD_REQUIRED"));
        assertThat(lifecycle.get(tenantA, imported.id()).status()).isEqualTo(ImportStatus.RECEIVED);
        verify(reader, never()).readVerified(anyString(), anyString());
    }

    @Test
    void copyingAnotherTenantsFileReferenceDoesNotEstablishOwnership() throws Exception {
        UUID owned = upload(VALID, "PROCESSOR_SETTLEMENT");
        String uri = uri(owned);
        String hash = jdbc.queryForObject(
                "SELECT file_hash FROM import_batch WHERE tenant_id = ? AND id = ?",
                String.class, tenantA.toString(), owned.toString());
        ImportBatchSnapshot forged = registration.register(tenantB,
                SourceType.PROCESSOR_SETTLEMENT, "PROCESSOR_A", "copy.csv",
                uri, hash, LocalDate.of(2026, 10, 22));
        process("b", forged.id(), 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UPLOAD_REQUIRED"));
        verify(reader, never()).readVerified(anyString(), anyString());
        assertThat(lifecycle.get(tenantA, owned).status()).isEqualTo(ImportStatus.RECEIVED);
    }

    @Test
    void foreignTenantGetsNotFound() throws Exception {
        UUID id = upload(VALID, "PROCESSOR_SETTLEMENT");
        process("b", id, 0).andExpect(status().isNotFound());
        assertThat(lifecycle.get(tenantA, id).status()).isEqualTo(ImportStatus.RECEIVED);
        verify(reader, never()).readVerified(anyString(), anyString());
    }

    @Test
    void auditorCannotProcess() throws Exception {
        UUID id = upload(VALID, "PROCESSOR_SETTLEMENT");
        process("auditor", id, 0).andExpect(status().isForbidden());
        assertThat(lifecycle.get(tenantA, id).status()).isEqualTo(ImportStatus.RECEIVED);
    }

    @Test
    void anonymousRequestIsUnauthorized() throws Exception {
        UUID id = upload(VALID, "PROCESSOR_SETTLEMENT");
        mvc.perform(post("/api/imports/{id}/process", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void staleRequestCannotClaimOrReadFile() throws Exception {
        UUID id = upload(VALID, "PROCESSOR_SETTLEMENT");
        process("a", id, 99).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_IMPORT_VERSION"));
        assertThat(lifecycle.get(tenantA, id).version()).isZero();
        verify(reader, never()).readVerified(anyString(), anyString());
    }

    @Test
    void wrongSourceCannotBeClaimed() throws Exception {
        UUID id = upload(VALID, "BANK_STATEMENT");
        process("a", id, 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_SOURCE"));
        assertThat(lifecycle.get(tenantA, id).status()).isEqualTo(ImportStatus.RECEIVED);
    }

    @Test
    void missingExpectedVersionReturnsBadRequest() throws Exception {
        UUID id = upload(VALID, "PROCESSOR_SETTLEMENT");
        mvc.perform(post("/api/imports/{id}/process", id)
                        .header("Authorization", "Bearer a")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(lifecycle.get(tenantA, id).status()).isEqualTo(ImportStatus.RECEIVED);
    }

    @Test
    void completedImportCannotBeProcessedAgain() throws Exception {
        UUID id = upload(VALID, "PROCESSOR_SETTLEMENT");
        process("a", id, 0).andExpect(status().isOk());
        process("a", id, 2).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMPORT_NOT_RECEIVED"));
        assertThat(count("settlement_line", id)).isEqualTo(2);
    }

    @Test
    void concurrentRequestLosesBeforeReadingWhileClaimIsCommitted() throws Exception {
        UUID id = upload(VALID, "PROCESSOR_SETTLEMENT");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        String storageUri = uri(id);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            entered.countDown();
            if (!release.await(15, TimeUnit.SECONDS)) {
                throw new IOException("Test release timed out");
            }
            return invocation.callRealMethod();
        }).when(reader).readVerified(eq(storageUri), anyString());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> first = executor.submit(() -> process("a", id, 0)
                    .andReturn().getResponse().getStatus());
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(lifecycle.get(tenantA, id).status()).isEqualTo(ImportStatus.PROCESSING);
            process("a", id, 0).andExpect(status().isConflict());
            release.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(200);
            verify(reader, times(1)).readVerified(eq(storageUri), anyString());
            assertThat(count("settlement_line", id)).isEqualTo(2);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void oldWorkerCannotOverwriteANewerProcessingVersion() throws Exception {
        UUID id = upload(VALID, "PROCESSOR_SETTLEMENT");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!release.await(15, TimeUnit.SECONDS)) {
                throw new IOException("Test release timed out");
            }
            return invocation.callRealMethod();
        }).when(reader).readVerified(eq(uri(id)), anyString());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> old = executor.submit(() -> process("a", id, 0)
                    .andReturn().getResponse().getStatus());
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            ImportBatchSnapshot failed = lifecycle.fail(tenantA, id, 1);
            ImportBatchSnapshot newer = lifecycle.retryProcessing(tenantA, id, failed.version());
            release.countDown();
            assertThat(old.get(20, TimeUnit.SECONDS)).isEqualTo(409);
            ImportBatchSnapshot current = lifecycle.get(tenantA, id);
            assertThat(current.status()).isEqualTo(ImportStatus.PROCESSING);
            assertThat(current.version()).isEqualTo(newer.version());
            assertNoEvidence(id);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void persistenceFailureRollsBackNewEvidenceThenMarksFailed() throws Exception {
        UUID id = upload(VALID, "PROCESSOR_SETTLEMENT");
        // Corrupt pre-existing evidence deliberately causes a real insert failure.
        jdbc.update("""
                INSERT INTO settlement_batch (tenant_id, import_id, batch_id, provider,
                    settlement_reference, bank_account_reference, declared_net_minor, currency)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantA.toString(), id.toString(), BATCH_ID,
                "PROCESSOR_A", "SET-001", "BANK-001", 37100, "INR");
        process("a", id, 0).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.imported.status").value("FAILED"))
                .andExpect(jsonPath("$.code").value("PERSISTENCE_FAILED"));
        assertThat(count("settlement_batch", id)).isEqualTo(1);
        assertThat(count("settlement_line", id)).isZero();
        assertThat(lifecycle.get(tenantA, id).rowCount()).isZero();
    }

    private UUID upload(String csv, String source) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "settlement.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
        String location = mvc.perform(multipart("/api/imports").file(file)
                        .param("sourceType", source).param("sourceSystem", "PROCESSOR_A")
                        .param("businessDate", "2026-10-22")
                        .header("Authorization", "Bearer a"))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }

    private ResultActions process(String token, UUID id, long version) throws Exception {
        return mvc.perform(post("/api/imports/{id}/process", id)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":" + version + "}"));
    }

    private String uri(UUID id) {
        return jdbc.queryForObject(
                "SELECT storage_uri FROM import_uploaded_file WHERE tenant_id = ? AND import_id = ?",
                String.class, tenantA.toString(), id.toString());
    }

    private Path filePath(UUID id) {
        return ROOT.resolve(uri(id).substring("local:".length()));
    }

    private long count(String table, UUID id) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table
                        + " WHERE tenant_id = ? AND import_id = ?",
                Long.class, tenantA.toString(), id.toString());
        return count == null ? 0 : count;
    }

    private void assertNoEvidence(UUID id) {
        assertThat(count("settlement_batch", id)).isZero();
        assertThat(count("settlement_line", id)).isZero();
    }

    private void token(String value, UUID tenant, List<String> roles) {
        Instant now = Instant.now();
        when(decoder.decode(value)).thenReturn(Jwt.withTokenValue(value)
                .header("alg", "RS256").subject(UUID.randomUUID().toString())
                .issuer("http://localhost:8081/realms/reconbridge")
                .audience(List.of("reconbridge-api"))
                .issuedAt(now).expiresAt(now.plusSeconds(300))
                .claim("tenant_id", tenant.toString())
                .claim("realm_access", Map.of("roles", roles)).build());
    }

    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory("reconbridge-processing-test-");
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
