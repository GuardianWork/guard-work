package com.guardwork.backend.company.repository;

import java.util.List;

import com.guardwork.backend.company.model.Company;
import com.guardwork.backend.company.model.VerificationStatus;

public interface CompanyRepositoryCustom {

    List<Company> findByStatus(VerificationStatus status, int offset, int limit);

    List<Company> findAll(int offset, int limit);
}
