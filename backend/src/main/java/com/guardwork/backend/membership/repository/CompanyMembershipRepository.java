package com.guardwork.backend.membership.repository;

import com.guardwork.backend.membership.model.CompanyMembership;
import com.guardwork.backend.membership.model.MembershipStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CompanyMembershipRepository extends JpaRepository<CompanyMembership, Long> {
  Optional<CompanyMembership> findByUserIdAndStatus(Long userId, MembershipStatus status);

  @Query(
      "SELECT m.companyId FROM CompanyMembership m WHERE m.userId = :userId AND m.status ="
          + " 'ACTIVE'")
  Optional<Long> findActiveCompanyId(@Param("userId") Long userId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT m FROM CompanyMembership m WHERE m.userId = :userId AND m.status = 'ACTIVE'")
  Optional<CompanyMembership> findActiveForUpdate(@Param("userId") Long userId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT m FROM CompanyMembership m WHERE m.id = :id AND m.companyId = :company")
  Optional<CompanyMembership> findByIdAndCompanyForUpdate(
      @Param("id") Long id, @Param("company") Long company);

  Page<CompanyMembership> findByCompanyIdAndStatus(
      Long company, MembershipStatus status, Pageable page);
}
