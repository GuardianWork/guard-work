package com.guardwork.backend.company.repository;

import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import com.guardwork.backend.company.model.Company;
import com.guardwork.backend.company.model.VerificationStatus;

@Repository
public class CompanyRepositoryImpl implements CompanyRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<Company> findByStatus(VerificationStatus status, int offset, int limit) {
        return entityManager.createQuery(
                "SELECT c FROM Company c WHERE c.verificationStatus = :status ORDER BY c.createdAt ASC, c.id ASC",
                Company.class)
                .setParameter("status", status)
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultList();
    }

    @Override
    public List<Company> findAll(int offset, int limit) {
        return entityManager.createQuery(
                "SELECT c FROM Company c ORDER BY c.createdAt ASC, c.id ASC",
                Company.class)
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultList();
    }
}
