package com.guardwork.backend.onboarding.service;

import com.guardwork.backend.company.dto.*;
import com.guardwork.backend.company.model.Company;
import com.guardwork.backend.company.repository.CompanyRepository;
import com.guardwork.backend.company.service.CompanyService;
import com.guardwork.backend.membership.service.CompanyAccessService;
import com.guardwork.backend.onboarding.model.*;
import com.guardwork.backend.onboarding.repository.*;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LegalDossierService {
    private static final int MAX_DOCUMENT_BYTES = 10 * 1024 * 1024;
    private static final String DECLARATION = loadDeclaration();
    private final EmployerProfileRepository profiles;
    private final VerificationDocumentRepository documents;
    private final VerificationAttemptRepository attempts;
    private final DecisionDeliveryRepository deliveries;
    private final CompanyRepository companies;
    private final CompanyService companyService;
    private final CompanyAccessService access;
    private final PersonalVerificationService personal;
    private final com.guardwork.backend.user.repository.UserRepository users;

    public LegalDossierService(EmployerProfileRepository profiles, VerificationDocumentRepository documents,
            VerificationAttemptRepository attempts, DecisionDeliveryRepository deliveries, CompanyRepository companies,
            CompanyService companyService, CompanyAccessService access, PersonalVerificationService personal,
            com.guardwork.backend.user.repository.UserRepository users) {
        this.profiles = profiles; this.documents = documents; this.attempts = attempts; this.deliveries = deliveries;
        this.companies = companies; this.companyService = companyService; this.access = access; this.personal = personal;
        this.users = users;
    }

    public record DocumentInfo(UUID documentId, String filename, String mediaType, String sha256) {}
    public record AttemptInfo(UUID attemptId, Long companyId, String companyName, String taxCode, String workAddress,
                              String submitterPosition, String representation, String declarationText,
                              String declarationVersion, UUID documentId, Instant submittedAt, String outcome,
                              String rejectionReason, Long reviewerId, Instant reviewedAt) {
        static AttemptInfo from(VerificationAttempt a) {
            return new AttemptInfo(a.getId(), a.getCompanyId(), a.getCompanyName(), a.getTaxCode(), a.getWorkAddress(),
                    a.getSubmitterPosition(), a.getRepresentation(), a.getDeclarationText(), a.getDeclarationVersion(),
                    a.getDocumentId(), a.getSubmittedAt(), a.getOutcome(), a.getRejectionReason(), a.getReviewerId(), a.getReviewedAt());
        }
    }

    @Transactional
    public DocumentInfo upload(Long actor, MultipartFile file) {
        personal.requirePersonal(actor);
        if (file == null || file.isEmpty() || file.getSize() > MAX_DOCUMENT_BYTES)
            throw OnboardingSecrets.invalid("A document up to 10 MiB is required");
        byte[] bytes;
        try { bytes = file.getBytes(); }
        catch (java.io.IOException invalid) { throw OnboardingSecrets.invalid("Document could not be read"); }
        String media = mediaType(bytes);
        if (media == null) throw OnboardingSecrets.invalid("Only PDF, JPEG and PNG documents are allowed");
        VerificationDocument d = new VerificationDocument(); d.setId(UUID.randomUUID()); d.setUploaderId(actor);
        String filename = file.getOriginalFilename() == null ? "document" : file.getOriginalFilename();
        filename = filename.replaceAll("[\\p{Cntrl}/\\\\]", "_");
        d.setFilename(OnboardingSecrets.required(filename, 255)); d.setMediaType(media); d.setContent(bytes);
        d.setContentHash(OnboardingSecrets.hash(bytes)); d.setCreatedAt(Instant.now()); documents.saveAndFlush(d);
        return new DocumentInfo(d.getId(), d.getFilename(), media, d.getContentHash());
    }

    @Transactional
    public VerificationDocument document(Long actor, UUID id) {
        var user = access.requireActiveUser(actor);
        var d = documents.findById(id).orElseThrow(LegalDossierService::notFound);
        if ("ADMIN".equals(user.getRole())) return d;
        var linked = attempts.findByDocumentId(id);
        if (linked.isEmpty() && actor.equals(d.getUploaderId())) return d;
        var current = access.findCurrentCompanyAccess(actor);
        if (current == null || !"OWNER".equals(current.membership().getRole().name())
                || linked.stream().noneMatch(a -> a.getCompanyId().equals(current.company().getId()))) throw notFound();
        return d;
    }

    @Transactional
    public Company register(Long actor, CompanyRegistrationRequest request) {
        // Old clients remain on the approved legacy journey until a separate rollout/migration.
        if (profiles.findById(actor).isEmpty()) return companyService.registerCompany(actor, request);
        personal.requirePersonal(actor);
        if (request == null) throw OnboardingSecrets.invalid("Dossier is required");
        LegalDetails legal = validateLegal(actor, request.legal());
        String email = access.requireActiveUser(actor).getEmail();
        Company company = companyService.registerCompanyFromVerifiedApplicant(actor, new CompanyRegistrationRequest(
                request.name(), request.taxCode(), email, documentPath(legal.documentId())));
        recordAttempt(actor, company, legal);
        return company;
    }

    @Transactional
    public Company correct(Long actor, Long companyId, CompanyResubmissionRequest request) {
        Company current = companyId == null ? access.requireOwner(actor).company() : access.requireOwnerOf(actor, companyId);
        if (!attempts.existsByCompanyId(current.getId()) && (profiles.findById(actor).isEmpty() || request == null || request.legal() == null))
            return companyService.resubmitCompany(actor, current.getId(), request);
        personal.requirePersonal(actor);
        if (request == null) throw OnboardingSecrets.invalid("Dossier is required");
        LegalDetails legal = validateLegal(actor, request.legal());
        Company corrected = companyService.resubmitCompany(actor, current.getId(), new CompanyResubmissionRequest(
                OnboardingSecrets.required(request.name(), 255), null, documentPath(legal.documentId()), request.expectedVersion()));
        recordAttempt(actor, corrected, legal);
        return corrected;
    }

    @Transactional
    public List<AttemptInfo> history(Long actor, Long companyId) {
        var user = access.requireActiveUser(actor);
        Long id;
        if ("ADMIN".equals(user.getRole()) && companyId != null) {
            id = companies.findById(companyId).orElseThrow(LegalDossierService::notFound).getId();
        } else id = companyId == null ? access.requireOwner(actor).company().getId() : access.requireOwnerOf(actor, companyId).getId();
        return attempts.findByCompanyIdOrderBySubmittedAtAsc(id).stream().map(AttemptInfo::from).toList();
    }

    @Transactional
    public void reviewed(Company company, Long reviewer) {
        var pending = attempts.findByCompanyIdAndOutcomeIsNull(company.getId());
        if (pending.isEmpty()) return;
        var attempt = pending.get();
        attempt.setOutcome(company.getVerificationStatus().name()); attempt.setRejectionReason(company.getRejectionReason());
        attempt.setReviewerId(reviewer); attempt.setReviewedAt(Instant.now()); attempts.saveAndFlush(attempt);
        var recipient = users.findById(attempt.getSubmitterId()).orElseThrow();
        DecisionDelivery intent = new DecisionDelivery(); intent.setId(attempt.getId());
        intent.setRecipientEmail(recipient.getEmail()); intent.setCompanyName(attempt.getCompanyName());
        intent.setOutcome(attempt.getOutcome()); intent.setRejectionReason(attempt.getRejectionReason());
        intent.setNextAttemptAt(Instant.now()); deliveries.saveAndFlush(intent);
    }

    private LegalDetails validateLegal(Long actor, LegalDetails legal) {
        if (legal == null || !Boolean.TRUE.equals(legal.declarationAccepted()) || legal.documentId() == null)
            throw OnboardingSecrets.invalid("Legal document and explicit declaration are required");
        OnboardingSecrets.required(legal.workAddress(), 1000); OnboardingSecrets.required(legal.submitterPosition(), 200);
        if (!Set.of("LEGAL_REPRESENTATIVE", "AUTHORIZED_REPRESENTATIVE").contains(String.valueOf(legal.representation())))
            throw OnboardingSecrets.invalid("Representative selection is required");
        var document = documents.findById(legal.documentId()).orElseThrow(LegalDossierService::notFound);
        if (!actor.equals(document.getUploaderId())) throw notFound();
        return legal;
    }

    private void recordAttempt(Long actor, Company company, LegalDetails legal) {
        VerificationAttempt a = new VerificationAttempt(); a.setId(UUID.randomUUID()); a.setCompanyId(company.getId());
        a.setSubmitterId(actor); a.setCompanyName(company.getName()); a.setTaxCode(company.getTaxCode());
        a.setWorkAddress(legal.workAddress().trim()); a.setSubmitterPosition(legal.submitterPosition().trim());
        a.setRepresentation(legal.representation()); a.setDeclarationText(DECLARATION); a.setDeclarationVersion("2026-10-18-v1");
        a.setDocumentId(legal.documentId()); a.setSubmittedAt(Instant.now()); attempts.saveAndFlush(a);
    }

    private static String documentPath(UUID id) { return "/api/company-verification-documents/" + id; }
    private static String loadDeclaration() {
        try { return new org.springframework.core.io.ClassPathResource("onboarding/declaration-vi.txt")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8).trim(); }
        catch (java.io.IOException missing) { throw new IllegalStateException("Missing declaration resource", missing); }
    }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "40430 DOCUMENT_UNAVAILABLE: Resource not found"); }

    private static String mediaType(byte[] bytes) {
        if (bytes.length >= 5 && new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")) return "application/pdf";
        if (bytes.length >= 3 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8 && bytes[2] == (byte) 0xff) return "image/jpeg";
        if (bytes.length >= 8 && java.util.Arrays.equals(java.util.Arrays.copyOf(bytes, 8), new byte[]{(byte)137,80,78,71,13,10,26,10})) return "image/png";
        return null;
    }
}
