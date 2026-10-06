package com.guardwork.backend.company.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.guardwork.backend.company.model.Company;
import com.guardwork.backend.company.model.VerificationStatus;

public interface CompanyRepository {

    Company save(Company company);

    Optional<Company> findById(Long id);

    Optional<Company> findByTaxCode(String taxCode);

    List<Company> findByStatus(VerificationStatus status, int offset, int limit);

    long countByStatus(VerificationStatus status);

    List<Company> findAll(int offset, int limit);

    long countAll();

    int updateVerification(Long id, VerificationStatus status, String rejectionReason,
                           Long verifiedBy, Instant verifiedAt, Long expectedVersion);
}
