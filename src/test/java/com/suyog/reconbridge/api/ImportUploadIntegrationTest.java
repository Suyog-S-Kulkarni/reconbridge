package com.suyog.reconbridge.api;

import com.suyog.reconbridge.persistence.entity.TenantEntity;
import com.suyog.reconbridge.persistence.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ImportUploadIntegrationTest {

    private static final Path STORAGE_ROOT = createStorageRoot();

    private static final byte[] CONTENT = (
            "reference,amountMinor,currency\n"
                    + "PAY-001,12550,INR\n"
    ).getBytes(StandardCharsets.UTF_8);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private UUID tenantId;

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "reconbridge.storage.root",
                () -> STORAGE_ROOT.toString()
        );

        registry.add("reconbridge.storage.max-bytes", () -> "128");
    }

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();

        tenantRepository.saveAndFlush(
                new TenantEntity(tenantId, "Upload Test Tenant")
        );
    }

    @AfterEach
    void cleanUp() throws IOException {
        jdbcTemplate.update(
                "DELETE FROM import_batch WHERE tenant_id = ?",
                tenantId.toString()
        );

        jdbcTemplate.update(
                "DELETE FROM tenant WHERE id = ?",
                tenantId.toString()
        );

        try (var files = Files.list(STORAGE_ROOT)) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    @Test
    void storesActualBytesAndCalculatedHash() throws Exception {
        mockToken("importer", List.of("IMPORTER"));

        upload("importer", "settlement.csv", CONTENT)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.rowCount").value(0));

        String expectedHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(CONTENT)
        );

        String actualHash = jdbcTemplate.queryForObject(
                "SELECT file_hash FROM import_batch WHERE tenant_id = ?",
                String.class,
                tenantId.toString()
        );

        String uri = jdbcTemplate.queryForObject(
                "SELECT file_uri FROM import_batch WHERE tenant_id = ?",
                String.class,
                tenantId.toString()
        );

        assertThat(actualHash).isEqualTo(expectedHash);
        assertThat(uri).startsWith("local:");

        Path stored = STORAGE_ROOT.resolve(
                uri.substring("local:".length())
        );

        assertThat(Files.readAllBytes(stored)).isEqualTo(CONTENT);
        assertThat(fileCount()).isEqualTo(1);
    }

    @Test
    void duplicateRemovesOnlyTheNewFile() throws Exception {
        mockToken("importer", List.of("IMPORTER"));

        upload("importer", "first.csv", CONTENT)
                .andExpect(status().isCreated());

        String originalUri = jdbcTemplate.queryForObject(
                "SELECT file_uri FROM import_batch WHERE tenant_id = ?",
                String.class,
                tenantId.toString()
        );

        upload("importer", "renamed.csv", CONTENT)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_IMPORT"));

        assertThat(fileCount()).isEqualTo(1);
        assertThat(importCount()).isEqualTo(1);

        Path originalFile = STORAGE_ROOT.resolve(
                originalUri.substring("local:".length())
        );

        assertThat(Files.readAllBytes(originalFile)).isEqualTo(CONTENT);
    }

    @Test
    void rejectsEmptyFile() throws Exception {
        mockToken("importer", List.of("IMPORTER"));

        upload("importer", "empty.csv", new byte[0])
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_UPLOAD"));

        assertNothingStored();
    }

    @Test
    void rejectsFilenameContainingPath() throws Exception {
        mockToken("importer", List.of("IMPORTER"));

        upload("importer", "../settlement.csv", CONTENT)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_UPLOAD"));

        assertNothingStored();
    }

    @Test
    void rejectsNonCsvExtension() throws Exception {
        mockToken("importer", List.of("IMPORTER"));

        upload("importer", "settlement.exe", CONTENT)
                .andExpect(status().isBadRequest());

        assertNothingStored();
    }

    @Test
    void oversizedStreamLeavesNoPartialFile() throws Exception {
        mockToken("importer", List.of("IMPORTER"));

        upload("importer", "large.csv", new byte[129])
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("UPLOAD_TOO_LARGE"));

        assertNothingStored();
    }

    @Test
    void auditorCannotUpload() throws Exception {
        mockToken("auditor", List.of("AUDITOR"));

        upload("auditor", "settlement.csv", CONTENT)
                .andExpect(status().isForbidden());

        assertNothingStored();
    }

    @Test
    void anonymousUserCannotUpload() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "settlement.csv",
                "text/csv",
                CONTENT
        );

        mockMvc.perform(multipart("/api/imports")
                        .file(file)
                        .param("sourceType", "PROCESSOR_SETTLEMENT")
                        .param("sourceSystem", "UPLOAD_TEST")
                        .param("businessDate", "2026-10-21"))
                .andExpect(status().isUnauthorized());

        assertNothingStored();
    }

    private ResultActions upload(
            String token,
            String filename,
            byte[] content
    ) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                filename,
                "text/csv",
                content
        );

        return mockMvc.perform(multipart("/api/imports")
                .file(file)
                .param("sourceType", "PROCESSOR_SETTLEMENT")
                .param("sourceSystem", "UPLOAD_TEST")
                .param("businessDate", "2026-10-21")
                .header("Authorization", "Bearer " + token));
    }

    private void assertNothingStored() throws IOException {
        assertThat(fileCount()).isZero();
        assertThat(importCount()).isZero();
    }

    private long fileCount() throws IOException {
        try (var files = Files.list(STORAGE_ROOT)) {
            return files.count();
        }
    }

    private long importCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM import_batch WHERE tenant_id = ?",
                Long.class,
                tenantId.toString()
        );

        return count == null ? 0 : count;
    }

    private void mockToken(String token, List<String> roles) {
        Instant now = Instant.now();

        Jwt jwt = Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .subject(UUID.randomUUID().toString())
                .issuer("http://localhost:8081/realms/reconbridge")
                .audience(List.of("reconbridge-api"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("tenant_id", tenantId.toString())
                .claim("realm_access", Map.of("roles", roles))
                .build();

        when(jwtDecoder.decode(token)).thenReturn(jwt);
    }

    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("reconbridge-upload-test-");
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}