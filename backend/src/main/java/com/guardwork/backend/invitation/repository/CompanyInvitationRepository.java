package com.guardwork.backend.invitation.repository;

import com.guardwork.backend.invitation.model.*;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface CompanyInvitationRepository extends JpaRepository<CompanyInvitation, Long> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select i from CompanyInvitation i where i.id = :id")
  Optional<CompanyInvitation> findByIdForUpdate(@Param("id") Long id);

  Optional<CompanyInvitation> findBySecretHash(String secretHash);

  interface Candidate {
    Long getId();

    Long getCompanyId();
  }

  @Query(
      "select i.id as id,i.companyId as companyId from CompanyInvitation i where"
          + " i.secretHash=:hash")
  Optional<Candidate> findCandidateBySecretHash(@Param("hash") String hash);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select i from CompanyInvitation i where i.companyId=:company and"
          + " i.recipientEmailNormalized=:email and"
          + " i.status=com.guardwork.backend.invitation.model.InvitationStatus.PENDING")
  Optional<CompanyInvitation> findPendingForUpdate(
      @Param("company") Long company, @Param("email") String email);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select i from CompanyInvitation i where i.companyId=:company and"
          + " i.status=com.guardwork.backend.invitation.model.InvitationStatus.PENDING order by"
          + " i.id")
  List<CompanyInvitation> findPendingForUpdate(@Param("company") Long company);

  Page<CompanyInvitation> findByCompanyId(Long companyId, Pageable page);

  Page<CompanyInvitation> findByCompanyIdAndStatus(
      Long companyId, InvitationStatus status, Pageable page);
}
