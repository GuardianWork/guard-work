package com.guardwork.backend.membership.operations;

import com.guardwork.backend.auth.service.JwtService;
import com.guardwork.backend.company.model.Company;
import com.guardwork.backend.company.repository.CompanyRepository;
import com.guardwork.backend.membership.model.*;
import com.guardwork.backend.membership.repository.CompanyMembershipRepository;
import com.guardwork.backend.membership.repository.MembershipAuditEventRepository;
import com.guardwork.backend.user.model.User;
import com.guardwork.backend.user.repository.UserRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Restricted operational boundary; never exposed by an HTTP controller. Tokens are not logged. */
@Service
public class LegacyOwnershipCommand {
  private final JwtService jwt;
  private final UserRepository users;
  private final CompanyRepository companies;
  private final CompanyMembershipRepository memberships;
  private final OwnershipReviewRepository reviews;
  private final MembershipAuditEventRepository audits;

  public LegacyOwnershipCommand(
      JwtService jwt,
      UserRepository users,
      CompanyRepository companies,
      CompanyMembershipRepository memberships,
      OwnershipReviewRepository reviews,
      MembershipAuditEventRepository audits) {
    this.jwt = jwt;
    this.users = users;
    this.companies = companies;
    this.memberships = memberships;
    this.reviews = reviews;
    this.audits = audits;
  }

  public record Review(
      String caseReference,
      Long companyId,
      Long applicantUserId,
      Long expectedVersion,
      String expectedVerificationStatus,
      boolean expectedBanned,
      String evidenceType,
      boolean currentRegistrationChecked,
      boolean applicantIdentityMatched,
      boolean currentLegalAuthorityChecked,
      Boolean conflictingClaims,
      String evidenceValidUntil) {}

  public record Assignment(
      String caseReference,
      Long companyId,
      Long applicantUserId,
      Long membershipId,
      Long reviewerUserId,
      Long operatorUserId,
      String evidenceType,
      String decision,
      String outcome) {}

  public record AuditReport(
      String caseReference,
      Long companyId,
      Long applicantUserId,
      Long membershipId,
      Long reviewerUserId,
      Long operatorUserId,
      String evidenceType,
      String decision,
      String reviewedAt,
      String assignedAt,
      String outcome) {}

  @Transactional
  public AuditReport inspect(String operatorToken, String caseReference) {
    Long actor = authenticated(operatorToken);
    User user=lockUsers(actor).get(actor);
    requireStaff(user);
    requireCredential(operatorToken,user);
    MembershipAuditEvent audit =
        audits
            .findByReviewCaseReferenceAndEventType(caseReference, "LEGACY_OWNER_ASSIGNED")
            .orElseThrow(() -> refused("No committed assignment for this case"));
    return new AuditReport(
        audit.getReviewCaseReference(),
        audit.getCompanyId(),
        audit.getSubjectUserId(),
        audit.getMembershipId(),
        audit.getReviewerUserId(),
        audit.getActorUserId(),
        audit.getEvidenceType(),
        audit.getReviewDecision(),
        audit.getReviewedAt().toString(),
        audit.getCreatedAt().toString(),
        audit.getAssignmentOutcome());
  }

  @Transactional
  public String review(String reviewerToken, Review input) {
    Long reviewerId = authenticated(reviewerToken);
    validate(input);
    Company company =
        companies
            .findByIdForUpdate(input.companyId())
            .orElseThrow(() -> refused("Company not found"));
    Map<Long, User> locked = lockUsers(reviewerId, input.applicantUserId());
    requireStaff(locked.get(reviewerId));
    requireCredential(reviewerToken,locked.get(reviewerId));
    requireActiveApplicant(locked.get(input.applicantUserId()));

    requireUnassigned(company, input.applicantUserId());
    if (!company.getVersion().equals(input.expectedVersion())
        || !company.getVerificationStatus().name().equals(input.expectedVerificationStatus())
        || company.isBanned() != input.expectedBanned())
      throw refused("Company state changed; independent review required");
    if (reviews.existsById(input.caseReference())
        || reviews.findByCompanyId(company.getId()).stream()
            .anyMatch(
                existing ->
                    !existing.getApplicantUserId().equals(input.applicantUserId())
                        || (sameSnapshot(company, existing)
                            && currentEvidence(existing.getEvidenceValidUntil()))))
      throw refused(
          "Review already recorded or conflicting Company case; escalate for governance review");
    OwnershipReview review = new OwnershipReview();
    review.setCaseReference(input.caseReference());
    review.setCompanyId(company.getId());
    review.setApplicantUserId(input.applicantUserId());
    review.setReviewerUserId(reviewerId);
    review.setEvidenceType(input.evidenceType());
    review.setDecision("APPROVED");
    review.setCompanyVersion(company.getVersion());
    review.setVerificationStatus(company.getVerificationStatus().name());
    review.setCompanyBanned(company.isBanned());
    review.setCompanyUpdatedAt(company.getUpdatedAt());
    review.setReviewedAt(Instant.now());
    review.setEvidenceValidUntil(validUntil(input.evidenceValidUntil()));
    reviews.saveAndFlush(review);
    return review.getCaseReference();
  }

  @Transactional
  public Assignment assign(String operatorToken, String caseReference) {
    Long operatorId = authenticated(operatorToken);
    OwnershipReview review =
        reviews.findById(caseReference).orElseThrow(() -> refused("Recorded approval required"));
    Company company =
        companies
            .findByIdForUpdate(review.getCompanyId())
            .orElseThrow(() -> refused("Company not found"));
    Map<Long, User> locked =
        lockUsers(operatorId, review.getReviewerUserId(), review.getApplicantUserId());
    requireStaff(locked.get(operatorId));
    requireCredential(operatorToken,locked.get(operatorId));
    requireStaff(locked.get(review.getReviewerUserId()));
    if (operatorId.equals(review.getReviewerUserId()))
      throw refused("Reviewer and operator must differ");
    requireActiveApplicant(locked.get(review.getApplicantUserId()));
    if (!"APPROVED".equals(review.getDecision())) throw refused("Recorded approval required");

    // Waiting for the Company guard can outlive otherwise valid authority evidence.
    if (!currentEvidence(review.getEvidenceValidUntil()))
      throw refused("Current evidence required; new review required");
    requireUnassigned(company, review.getApplicantUserId());
    if (!sameSnapshot(company, review))
      throw refused("Company state changed; independent review required");
    CompanyMembership member = new CompanyMembership();
    member.setCompanyId(company.getId());
    member.setUserId(review.getApplicantUserId());
    member.setRole(CompanyRole.OWNER);
    member.setStatus(MembershipStatus.ACTIVE);
    memberships.saveAndFlush(member);
    company.setOwnerUserId(member.getUserId());
    company.setVersion(company.getVersion() + 1);
    companies.saveAndFlush(company);
    MembershipAuditEvent audit = new MembershipAuditEvent();
    audit.setActorUserId(operatorId);
    audit.setSubjectUserId(member.getUserId());
    audit.setCompanyId(company.getId());
    audit.setEventType("LEGACY_OWNER_ASSIGNED");
    audit.setMembershipId(member.getId());
    audit.setReviewCaseReference(review.getCaseReference());
    audit.setReviewerUserId(review.getReviewerUserId());
    audit.setEvidenceType(review.getEvidenceType());
    audit.setReviewDecision(review.getDecision());
    audit.setReviewedAt(review.getReviewedAt());
    audit.setAssignmentOutcome("ASSIGNED");
    audits.saveAndFlush(audit);
    return new Assignment(
        review.getCaseReference(),
        company.getId(),
        member.getUserId(),
        member.getId(),
        review.getReviewerUserId(),
        operatorId,
        review.getEvidenceType(),
        review.getDecision(),
        "ASSIGNED");
  }

  private void validate(Review review) {
    if (review == null
        || review.caseReference() == null
        || !review.caseReference().matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,254}")
        || review.companyId() == null
        || review.applicantUserId() == null
        || review.expectedVersion() == null
        || review.expectedVerificationStatus() == null)
      throw refused("Complete safe case reference and Company snapshot required");
    if (!("LEGAL_REPRESENTATIVE_REGISTRATION".equals(review.evidenceType())
            || "LEGAL_REPRESENTATIVE_AUTHORIZATION".equals(review.evidenceType()))
        || !review.currentRegistrationChecked()
        || !review.applicantIdentityMatched()
        || !review.currentLegalAuthorityChecked()
        || !Boolean.FALSE.equals(review.conflictingClaims()))
      throw refused(
          "Current registration, matched identity and current legal authority without conflicting"
              + " claims required");
    if (!currentEvidence(validUntil(review.evidenceValidUntil())))
      throw refused("Current evidence required");
  }

  private boolean sameSnapshot(Company company, OwnershipReview review) {
    return company.getVersion().equals(review.getCompanyVersion())
        && company.getVerificationStatus().name().equals(review.getVerificationStatus())
        && company.isBanned() == review.isCompanyBanned()
        && company.getUpdatedAt().equals(review.getCompanyUpdatedAt());
  }

  private Instant validUntil(String value) {
    if (value == null) return null;
    try {
      return Instant.parse(value);
    } catch (RuntimeException e) {
      throw refused("Current evidence with valid expiry required");
    }
  }

  private boolean currentEvidence(Instant until) {
    return until == null || until.isAfter(Instant.now());
  }

  private Long authenticated(String token) {
    try {
      return jwt.extractUserId(token);
    } catch (RuntimeException e) {
      throw refused("Valid authenticated operator credential required");
    }
  }

  private void requireCredential(String token, User user) {
    try {
      var claims=jwt.parseAndValidateToken(token);
      Number version=claims.get("sessionVersion",Number.class);
      if (!"STAFF".equals(claims.get("role",String.class))
          || user.getSessionVersion()!=(version==null ? 0 : version.longValue()))
        throw refused("Current authenticated operator credential required");
    } catch (RuntimeException failure) {
      throw refused("Current authenticated operator credential required");
    }
  }

  private Map<Long, User> lockUsers(Long... ids) {
    Map<Long, User> locked = new HashMap<>();
    // Mutating callers hold the Company guard first; user guards have a stable order.
    TreeSet<Long> ordered = new TreeSet<>();
    for (Long id : ids) ordered.add(id);
    for (Long id : ordered)
      locked.put(id, users.findByIdForUpdate(id).orElseThrow(() -> refused("User unavailable")));
    return locked;
  }

  private void requireStaff(User user) {
    if (!"STAFF".equals(user.getRole()) || !"ACTIVE".equals(user.getStatus()))
      throw refused("Active Platform Staff authority required");
  }

  private void requireActiveApplicant(User user) {
    if (!user.allowsNormalAccess()) throw refused("Applicant account unavailable");
  }

  private void requireUnassigned(Company company, Long applicant) {
    if (company.getOwnerUserId() != null)
      throw refused("Company already owned; ownership transfer prohibited");
    if (memberships.findByUserIdAndStatus(applicant, MembershipStatus.ACTIVE).isPresent())
      throw refused("Applicant already has active Company Membership");
  }

  private IllegalArgumentException refused(String safeReason) {
    return new IllegalArgumentException(safeReason);
  }
}
