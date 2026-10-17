package com.guardwork.backend.membership.operations;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OwnershipReviewRepository extends JpaRepository<OwnershipReview, String> {
  java.util.List<OwnershipReview> findByCompanyId(Long companyId);
}
