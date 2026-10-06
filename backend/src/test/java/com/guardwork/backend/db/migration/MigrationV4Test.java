package com.guardwork.backend.db.migration;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.guardwork.backend.company.model.Company;
import com.guardwork.backend.company.model.VerificationStatus;
import com.guardwork.backend.company.repository.CompanyRepository;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MigrationV4Test {

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Test
    void testCompanyEmailColumnAndIndexExist() {
        // Verify 'email' column exists in companies table
        List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                "SELECT column_name, data_type, is_nullable " +
                        "FROM information_schema.columns " +
                        "WHERE table_name = 'companies' AND column_name = 'email'",
                new MapSqlParameterSource()
        );
        assertThat(columns).hasSize(1);
        assertThat(columns.getFirst().get("column_name")).isEqualTo("email");
        assertThat(columns.getFirst().get("is_nullable")).isEqualTo("YES");

        // Verify index 'idx_companies_email' exists
        List<Map<String, Object>> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'companies' AND indexname = 'idx_companies_email'",
                new MapSqlParameterSource()
        );
        assertThat(indexes).hasSize(1);
    }

    @Test
    void testCompanyEmailPersistence() {
        String uniqueTax = "TAX_V4_" + System.currentTimeMillis();
        String companyEmail = "contact_" + System.currentTimeMillis() + "@techcorp.vn";

        Company company = new Company();
        company.setName("Công ty Cổ phần TechCorp");
        company.setTaxCode(uniqueTax);
        company.setEmail(companyEmail);
        company.setRegistrationCertificateUrl("https://storage.guardwork.vn/certs/techcorp.pdf");
        company.setVerificationStatus(VerificationStatus.PENDING);
        company.setVersion(0L);

        Company saved = companyRepository.save(company);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getEmail()).isEqualTo(companyEmail);

        Company reloaded = companyRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getEmail()).isEqualTo(companyEmail);
    }

    @Test
    void testCompanyEmailNullableForBackwardCompatibility() {
        String uniqueTax = "TAX_NULL_EMAIL_" + System.currentTimeMillis();

        Company legacyCompany = new Company();
        legacyCompany.setName("Công ty Legacy Null Email");
        legacyCompany.setTaxCode(uniqueTax);
        legacyCompany.setEmail(null);
        legacyCompany.setRegistrationCertificateUrl("https://storage.guardwork.vn/certs/legacy.pdf");
        legacyCompany.setVerificationStatus(VerificationStatus.PENDING);
        legacyCompany.setVersion(0L);

        Company saved = companyRepository.save(legacyCompany);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getEmail()).isNull();

        Company reloaded = companyRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getEmail()).isNull();
    }
}
