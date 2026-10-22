package com.suyog.reconbridge.application;

import com.suyog.reconbridge.domain.model.ImportStatus;
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
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
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
class BankImportProcessingIntegrationTest {

    private static final Path ROOT = temporaryRoot();

    private static final String HEADER =
            "bank_credit_id,provider,bank_account_reference,"
                    + "settlement_reference,amount_minor,currency\n";

    private static final String VALID = HEADER
            + "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa,"
            + "PROCESSOR_A,BANK-001,SET-001,12400,INR\n"
            + "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb,"
            + "PROCESSOR_A,BANK-001,SET-002,24700,INR\n";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TenantRepository tenants;

    @Autowired
    private ImportLifecycleService lifecycle;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private JwtDecoder decoder;

    @MockitoSpyBean
    private LocalStoredFileReader reader;

    private LocalStoredFileReader readerSpy;

    private UUID tenantA;
    private UUID tenantB;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(
                "reconbridge.storage.root",
                () -> ROOT.toString()
        );

        registry.add(
                "reconbridge.storage.max-bytes",
                () -> "10485760"
        );
    }

    @BeforeEach
    void setUp() {
        readerSpy = AopTestUtils.getUltimateTargetObject(reader);
        reset(readerSpy);

        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();

        tenants.saveAndFlush(
                new TenantEntity(tenantA, "Bank processing A")
        );

        tenants.saveAndFlush(
                new TenantEntity(tenantB, "Bank processing B")
        );

        token("a", tenantA, "IMPORTER");
        token("b", tenantB, "IMPORTER");
        token("auditor", tenantA, "AUDITOR");
    }

    @AfterEach
    void cleanUp() throws IOException {
        reset(readerSpy);

        for (String table : List.of(
                "bank_credit",
                "import_uploaded_file",
                "import_batch",
                "tenant"
        )) {
            String column = table.equals("tenant")
                    ? "id"
                    : "tenant_id";

            jdbc.update(
                    "DELETE FROM " + table
                            + " WHERE " + column + " IN (?, ?)",
                    tenantA.toString(),
                    tenantB.toString()
            );
        }

        try (var files = Files.list(ROOT)) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    @Test
    void uploadsAndProcessesBankStatement() throws Exception {
        UUID id = upload(VALID);

        process("a", id, 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported.status").value("READY"))
                .andExpect(jsonPath("$.imported.rowCount").value(2))
                .andExpect(jsonPath("$.code").value("READY"));

        assertEquals(2L, count(id));

        assertEquals(
                ImportStatus.READY,
                lifecycle.get(tenantA, id).status()
        );
    }

    @Test
    void invalidCsvIsRejectedWithoutPersistingCredits() throws Exception {
        UUID id = upload(VALID.replace("12400", "-1"));

        process("a", id, 0)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.imported.status").value("REJECTED"))
                .andExpect(jsonPath("$.code").value("CSV_REJECTED"));

        assertEquals(0L, count(id));

        assertEquals(
                ImportStatus.REJECTED,
                lifecycle.get(tenantA, id).status()
        );
    }

    @Test
    void missingStoredFileMarksImportFailed() throws Exception {
        UUID id = upload(VALID);

        Files.delete(filePath(id));

        process("a", id, 0)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.imported.status").value("FAILED"));

        assertEquals(0L, count(id));

        assertEquals(
                ImportStatus.FAILED,
                lifecycle.get(tenantA, id).status()
        );
    }

    @Test
    void changedStoredFileMarksImportFailed() throws Exception {
        UUID id = upload(VALID);

        Files.writeString(
                filePath(id),
                VALID.replace("12400", "12401"),
                StandardCharsets.UTF_8
        );

        process("a", id, 0)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.imported.status").value("FAILED"));

        assertEquals(0L, count(id));
    }

    @Test
    void replayWithOriginalVersionCannotDuplicateCredits() throws Exception {
        UUID id = upload(VALID);

        process("a", id, 0)
                .andExpect(status().isOk());

        process("a", id, 0)
                .andExpect(status().isConflict());

        assertEquals(2L, count(id));
    }

    @Test
    void foreignTenantCannotClaimImport() throws Exception {
        UUID id = upload(VALID);

        process("b", id, 0)
                .andExpect(status().isNotFound());

        assertEquals(
                ImportStatus.RECEIVED,
                lifecycle.get(tenantA, id).status()
        );

        assertEquals(0L, count(id));
    }

    @Test
    void auditorCannotProcessImport() throws Exception {
        UUID id = upload(VALID);

        process("auditor", id, 0)
                .andExpect(status().isForbidden());

        assertEquals(
                ImportStatus.RECEIVED,
                lifecycle.get(tenantA, id).status()
        );
    }

    @Test
    void missingTokenIsUnauthorized() throws Exception {
        UUID id = upload(VALID);

        mvc.perform(
                post("/api/imports/{id}/process", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}")
        ).andExpect(status().isUnauthorized());

        assertEquals(
                ImportStatus.RECEIVED,
                lifecycle.get(tenantA, id).status()
        );
    }

    @Test
    void secondWorkerCannotProcessAnAlreadyClaimedVersion() throws Exception {
        UUID id = upload(VALID);
        String storageUri = uri(id);

        CountDownLatch enteredReader = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);

        doAnswer(invocation -> {
            enteredReader.countDown();

            if (!releaseReader.await(15, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting for test release");
            }

            return invocation.callRealMethod();

        }).when(readerSpy).readVerified(
                eq(storageUri),
                anyString()
        );

        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<Integer> firstRequest = executor.submit(
                    () -> process("a", id, 0)
                            .andReturn()
                            .getResponse()
                            .getStatus()
            );

            assertTrue(
                    enteredReader.await(10, TimeUnit.SECONDS)
            );

            assertEquals(
                    ImportStatus.PROCESSING,
                    lifecycle.get(tenantA, id).status()
            );

            process("a", id, 0)
                    .andExpect(status().isConflict());

            releaseReader.countDown();

            assertEquals(
                    200,
                    firstRequest.get(20, TimeUnit.SECONDS).intValue()
            );

            verify(readerSpy, times(1)).readVerified(
                    eq(storageUri),
                    anyString()
            );

            assertEquals(2L, count(id));

        } finally {
            releaseReader.countDown();
            executor.shutdownNow();

            assertTrue(
                    executor.awaitTermination(20, TimeUnit.SECONDS)
            );
        }
    }

    private UUID upload(String csv) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "bank-statement.csv",
                "text/csv",
                csv.getBytes(StandardCharsets.UTF_8)
        );

        String location = mvc.perform(
                        multipart("/api/imports")
                                .file(file)
                                .param("sourceType", "BANK_STATEMENT")
                                .param("sourceSystem", "BANK_A")
                                .param("businessDate", "2026-10-22")
                                .header("Authorization", "Bearer a")
                )
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getHeader("Location");

        assertNotNull(location);

        return UUID.fromString(
                location.substring(location.lastIndexOf('/') + 1)
        );
    }

    private ResultActions process(
            String token,
            UUID id,
            long version
    ) throws Exception {
        return mvc.perform(
                post("/api/imports/{id}/process", id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"expectedVersion\":" + version + "}"
                        )
        );
    }

    private String uri(UUID id) {
        return jdbc.queryForObject(
                """
                SELECT storage_uri
                FROM import_uploaded_file
                WHERE tenant_id = ? AND import_id = ?
                """,
                String.class,
                tenantA.toString(),
                id.toString()
        );
    }

    private Path filePath(UUID id) {
        String storageUri = uri(id);

        assertNotNull(storageUri);
        assertTrue(storageUri.startsWith("local:"));

        Path path = ROOT.resolve(
                storageUri.substring("local:".length())
        ).normalize();

        assertTrue(path.startsWith(ROOT));

        return path;
    }

    private long count(UUID id) {
        Long result = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM bank_credit
                WHERE tenant_id = ? AND import_id = ?
                """,
                Long.class,
                tenantA.toString(),
                id.toString()
        );

        return result == null ? 0L : result;
    }

    private void token(
            String value,
            UUID tenantId,
            String role
    ) {
        Instant now = Instant.now();

        Jwt jwt = Jwt.withTokenValue(value)
                .header("alg", "RS256")
                .subject(UUID.randomUUID().toString())
                .issuer("http://localhost:8081/realms/reconbridge")
                .audience(List.of("reconbridge-api"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("tenant_id", tenantId.toString())
                .claim(
                        "realm_access",
                        Map.of("roles", List.of(role))
                )
                .build();

        when(decoder.decode(value)).thenReturn(jwt);
    }

    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory(
                    "reconbridge-bank-processing-"
            ).toAbsolutePath().normalize();

        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to create test storage directory",
                    exception
            );
        }
    }
}