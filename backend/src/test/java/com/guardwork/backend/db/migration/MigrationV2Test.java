package com.guardwork.backend.db.migration;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.guardwork.backend.audit.model.AuditLog;
import com.guardwork.backend.audit.repository.JdbcAuditLogRepository;
import com.guardwork.backend.company.model.Company;
import com.guardwork.backend.company.model.VerificationStatus;
import com.guardwork.backend.company.repository.JdbcCompanyRepository;
import com.guardwork.backend.user.model.User;
import com.guardwork.backend.user.repository.JdbcUserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class MigrationV2Test {

    @Autowired
    private JdbcCompanyRepository companyRepository;

    @Autowired
    private JdbcAuditLogRepository auditLogRepository;

    @Autowired
    private JdbcUserRepository userRepository;

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    private Long testAdminId;

    @BeforeEach
    void setUp() {
        // Ensure a test admin user exists for foreign key references
        String adminEmail = "admin_v2_test@guardwork.vn";
        userRepository.findByEmail(adminEmail).ifPresentOrElse(
                user -> testAdminId = user.getId(),
                () -> {
                    User user = new User();
                    user.setFirstName("Admin");
                    user.setLastName("Reviewer");
                    user.setUsername("admin_reviewer_" + System.currentTimeMillis());
                    user.setEmail(adminEmail);
                    user.setPassword("secret123");
                    User saved = userRepository.save(user);
                    testAdminId = saved.getId();
                }
        );
    }

    @Test
    void testCompaniesTableOperationsAndOptimisticLocking() {
        String uniqueTax = "TAX_" + System.currentTimeMillis();

        Company company = new Company();
        company.setName("Công ty Cổ phần Alpha Test");
        company.setTaxCode(uniqueTax);
        company.setRegistrationCertificateUrl("https://storage.guardwork.vn/certs/alpha.pdf");
        company.setVerificationStatus(VerificationStatus.PENDING);
        company.setVersion(0L);

        Company saved = companyRepository.save(company);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getVersion()).isZero();
        assertThat(saved.getVerificationStatus()).isEqualTo(VerificationStatus.PENDING);

        // First decision with expectedVersion 0 succeeds and increments version to 1
        int updated = companyRepository.updateVerification(
                saved.getId(),
                VerificationStatus.VERIFIED,
                null,
                testAdminId,
                Instant.now(),
                0L
        );
        assertThat(updated).isEqualTo(1);

        Company reloaded = companyRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(reloaded.getVersion()).isEqualTo(1L);
        assertThat(reloaded.getVerifiedBy()).isEqualTo(testAdminId);

        // Concurrent update with stale expectedVersion 0 returns 0 rows updated
        int conflictUpdate = companyRepository.updateVerification(
                saved.getId(),
                VerificationStatus.REJECTED,
                "Conflict attempt",
                testAdminId,
                Instant.now(),
                0L
        );
        assertThat(conflictUpdate).isZero();
    }

    @Test
    void testDuplicateTaxCode_ThrowsDuplicateKeyException() {
        String duplicateTax = "DUP_TAX_" + System.currentTimeMillis();

        Company c1 = new Company();
        c1.setName("Company One");
        c1.setTaxCode(duplicateTax);
        c1.setRegistrationCertificateUrl("https://storage.guardwork.vn/certs/1.pdf");
        companyRepository.save(c1);

        Company c2 = new Company();
        c2.setName("Company Two");
        c2.setTaxCode(duplicateTax);
        c2.setRegistrationCertificateUrl("https://storage.guardwork.vn/certs/2.pdf");

        assertThatThrownBy(() -> companyRepository.save(c2))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void testAuditLogsImmutabilityTriggerRejectsUpdateAndDelete() {
        AuditLog log = new AuditLog();
        log.setAdminId(testAdminId);
        log.setAction("COMPANY_VERIFICATION");
        log.setTargetType("COMPANY");
        log.setTargetId("9999");
        log.setOldPayload("{\"status\":\"PENDING\"}");
        log.setNewPayload("{\"status\":\"VERIFIED\"}");
        log.setReason("Audit log trigger verification");
        log.setIpAddress("127.0.0.1");
        log.setUserAgent("JUnit");

        AuditLog saved = auditLogRepository.save(log);
        assertThat(saved.getId()).isNotNull();

        List<AuditLog> found = auditLogRepository.findByTarget("COMPANY", "9999");
        assertThat(found).isNotEmpty();

        // Direct UPDATE on audit_logs must be blocked by trigger
        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "UPDATE audit_logs SET reason = 'Tampered' WHERE id = :id",
                        new MapSqlParameterSource("id", saved.getId())
                )
        ).isInstanceOf(DataAccessException.class)
         .hasMessageContaining("audit_logs table is append-only");

        // Direct DELETE on audit_logs must be blocked by trigger
        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "DELETE FROM audit_logs WHERE id = :id",
                        new MapSqlParameterSource("id", saved.getId())
                )
        ).isInstanceOf(DataAccessException.class)
         .hasMessageContaining("audit_logs table is append-only");
    }
}
