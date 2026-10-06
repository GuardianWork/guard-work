package com.guardwork.backend.company.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.guardwork.backend.company.model.Company;
import com.guardwork.backend.company.model.VerificationStatus;

public interface CompanyRepository extends JpaRepository<Company, Long>, CompanyRepositoryCustom {

    Optional<Company> findByTaxCode(String taxCode);

    Optional<Company> findByEmail(String email);

    @Query("SELECT COUNT(c) FROM Company c WHERE c.verificationStatus = :status")
    long countByStatus(@Param("status") VerificationStatus status);

    @Query("SELECT COUNT(c) FROM Company c")
    long countAll();

    @Modifying
    @Transactional
    @Query("UPDATE Company c SET c.verificationStatus = :status, c.rejectionReason = :rejectionReason, " +
           "c.verifiedBy = :verifiedBy, c.verifiedAt = :verifiedAt, c.version = c.version + 1, " +
           "c.updatedAt = CURRENT_TIMESTAMP WHERE c.id = :id AND c.version = :expectedVersion")
    int updateVerification(@Param("id") Long id,
                           @Param("status") VerificationStatus status,
                           @Param("rejectionReason") String rejectionReason,
                           @Param("verifiedBy") Long verifiedBy,
                           @Param("verifiedAt") Instant verifiedAt,
                           @Param("expectedVersion") Long expectedVersion);
}
