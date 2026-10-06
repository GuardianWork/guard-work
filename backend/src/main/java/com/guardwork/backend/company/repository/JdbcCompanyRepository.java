package com.guardwork.backend.company.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.guardwork.backend.company.model.Company;
import com.guardwork.backend.company.model.VerificationStatus;

@Repository
public class JdbcCompanyRepository implements CompanyRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcCompanyRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<Company> COMPANY_MAPPER = (rs, rowNum) -> {
        Company company = new Company();
        company.setId(rs.getLong("id"));
        company.setName(rs.getString("name"));
        company.setTaxCode(rs.getString("tax_code"));
        company.setRegistrationCertificateUrl(rs.getString("registration_certificate_url"));
        company.setVerificationStatus(VerificationStatus.valueOf(rs.getString("verification_status")));
        company.setRejectionReason(rs.getString("rejection_reason"));
        long verifiedBy = rs.getLong("verified_by");
        company.setVerifiedBy(rs.wasNull() ? null : verifiedBy);
        Timestamp verifiedAt = rs.getTimestamp("verified_at");
        company.setVerifiedAt(verifiedAt != null ? verifiedAt.toInstant() : null);
        company.setBanned(rs.getBoolean("is_banned"));
        company.setVersion(rs.getLong("version"));
        company.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        company.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        return company;
    };

    @Override
    public Company save(Company company) {
        Instant now = Instant.now();
        if (company.getId() == null) {
            String sql = """
                    INSERT INTO companies (name, tax_code, registration_certificate_url, verification_status,
                                           rejection_reason, verified_by, verified_at, is_banned, version, created_at, updated_at)
                    VALUES (:name, :taxCode, :registrationCertificateUrl, :verificationStatus,
                            :rejectionReason, :verifiedBy, :verifiedAt, :isBanned, :version, :createdAt, :updatedAt)
                    """;

            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("name", company.getName())
                    .addValue("taxCode", company.getTaxCode())
                    .addValue("registrationCertificateUrl", company.getRegistrationCertificateUrl())
                    .addValue("verificationStatus", company.getVerificationStatus().name())
                    .addValue("rejectionReason", company.getRejectionReason())
                    .addValue("verifiedBy", company.getVerifiedBy())
                    .addValue("verifiedAt", company.getVerifiedAt() != null ? Timestamp.from(company.getVerifiedAt()) : null)
                    .addValue("isBanned", company.isBanned())
                    .addValue("version", company.getVersion() != null ? company.getVersion() : 0L)
                    .addValue("createdAt", Timestamp.from(now))
                    .addValue("updatedAt", Timestamp.from(now));

            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(sql, params, keyHolder, new String[]{"id"});
            Number key = keyHolder.getKey();
            if (key != null) {
                company.setId(key.longValue());
            }
            company.setCreatedAt(now);
            company.setUpdatedAt(now);
            if (company.getVersion() == null) {
                company.setVersion(0L);
            }
            return company;
        } else {
            String sql = """
                    UPDATE companies
                    SET name = :name,
                        tax_code = :taxCode,
                        registration_certificate_url = :registrationCertificateUrl,
                        verification_status = :verificationStatus,
                        rejection_reason = :rejectionReason,
                        verified_by = :verifiedBy,
                        verified_at = :verifiedAt,
                        is_banned = :isBanned,
                        version = :version,
                        updated_at = :updatedAt
                    WHERE id = :id
                    """;

            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("id", company.getId())
                    .addValue("name", company.getName())
                    .addValue("taxCode", company.getTaxCode())
                    .addValue("registrationCertificateUrl", company.getRegistrationCertificateUrl())
                    .addValue("verificationStatus", company.getVerificationStatus().name())
                    .addValue("rejectionReason", company.getRejectionReason())
                    .addValue("verifiedBy", company.getVerifiedBy())
                    .addValue("verifiedAt", company.getVerifiedAt() != null ? Timestamp.from(company.getVerifiedAt()) : null)
                    .addValue("isBanned", company.isBanned())
                    .addValue("version", company.getVersion())
                    .addValue("updatedAt", Timestamp.from(now));

            jdbcTemplate.update(sql, params);
            company.setUpdatedAt(now);
            return company;
        }
    }

    @Override
    public Optional<Company> findById(Long id) {
        String sql = "SELECT * FROM companies WHERE id = :id";
        return jdbcTemplate.query(sql, new MapSqlParameterSource("id", id), COMPANY_MAPPER).stream().findFirst();
    }

    @Override
    public Optional<Company> findByTaxCode(String taxCode) {
        String sql = "SELECT * FROM companies WHERE tax_code = :taxCode";
        return jdbcTemplate.query(sql, new MapSqlParameterSource("taxCode", taxCode), COMPANY_MAPPER).stream().findFirst();
    }

    @Override
    public List<Company> findByStatus(VerificationStatus status, int offset, int limit) {
        String sql = """
                SELECT * FROM companies
                WHERE verification_status = :status
                ORDER BY created_at ASC, id ASC
                LIMIT :limit OFFSET :offset
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("status", status.name())
                .addValue("limit", limit)
                .addValue("offset", offset);
        return jdbcTemplate.query(sql, params, COMPANY_MAPPER);
    }

    @Override
    public long countByStatus(VerificationStatus status) {
        String sql = "SELECT COUNT(*) FROM companies WHERE verification_status = :status";
        Long count = jdbcTemplate.queryForObject(sql, new MapSqlParameterSource("status", status.name()), Long.class);
        return count != null ? count : 0L;
    }

    @Override
    public List<Company> findAll(int offset, int limit) {
        String sql = """
                SELECT * FROM companies
                ORDER BY created_at ASC, id ASC
                LIMIT :limit OFFSET :offset
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", limit)
                .addValue("offset", offset);
        return jdbcTemplate.query(sql, params, COMPANY_MAPPER);
    }

    @Override
    public long countAll() {
        String sql = "SELECT COUNT(*) FROM companies";
        Long count = jdbcTemplate.queryForObject(sql, new MapSqlParameterSource(), Long.class);
        return count != null ? count : 0L;
    }

    @Override
    public int updateVerification(Long id, VerificationStatus status, String rejectionReason,
                                  Long verifiedBy, Instant verifiedAt, Long expectedVersion) {
        String sql = """
                UPDATE companies
                SET verification_status = :status,
                    rejection_reason = :rejectionReason,
                    verified_by = :verifiedBy,
                    verified_at = :verifiedAt,
                    version = version + 1,
                    updated_at = :updatedAt
                WHERE id = :id AND version = :expectedVersion
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", status.name())
                .addValue("rejectionReason", rejectionReason)
                .addValue("verifiedBy", verifiedBy)
                .addValue("verifiedAt", verifiedAt != null ? Timestamp.from(verifiedAt) : null)
                .addValue("updatedAt", Timestamp.from(Instant.now()))
                .addValue("expectedVersion", expectedVersion);

        return jdbcTemplate.update(sql, params);
    }
}
