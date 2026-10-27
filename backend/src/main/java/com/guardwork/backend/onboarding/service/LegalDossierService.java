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
    private final VerificationDocumentRepository documents;
    private final VerificationAttemptRepository attempts;
    private final DecisionDeliveryRepository deliveries;
    private final CompanyRepository companies;
    private final CompanyService companyService;
    private final CompanyAccessService access;
    private final com.guardwork.backend.user.repository.UserRepository users;

    public LegalDossierService(VerificationDocumentRepository documents,
            VerificationAttemptRepository attempts, DecisionDeliveryRepository deliveries, CompanyRepository companies,
            CompanyService companyService, CompanyAccessService access,
            com.guardwork.backend.user.repository.UserRepository users) {
        this.documents = documents; this.attempts = attempts; this.deliveries = deliveries;
        this.companies = companies; this.companyService = companyService; this.access = access;
        this.users = users;
    }

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
    public Company registerComplete(Long actor, CompanyDossierRequest request, List<MultipartFile> file) {
        var user = access.requireActiveUser(actor);
        com.guardwork.backend.auth.service.AccountEmailVerificationService.requireRecruitingEmailEligible(user);
        if (request == null) throw invalidDossier("Complete Company dossier is required");
        String name = requiredDossier(request.name(), 255);
        String tax = requiredDossier(request.taxCode(), 50);
        validateCompleteLegal(request.legal());
        VerificationDocument document = retainCompleteDocument(actor, file);
        Company company = companyService.registerCompanyFromCompleteDossier(actor,
                new CompanyRegistrationRequest(name, tax, user.getEmail(), documentPath(document.getId())));
        recordAttempt(actor, company, completeLegal(request.legal(), document.getId()));
        return company;
    }

    @Transactional
    public Company correctComplete(Long actor, Long companyId, CompanyDossierCorrectionRequest request, List<MultipartFile> file) {
        var user = access.requireActiveUser(actor);
        com.guardwork.backend.auth.service.AccountEmailVerificationService.requireRecruitingEmailEligible(user);
        Company current = companyId == null ? access.requireOwner(actor).company() : access.requireOwnerOf(actor, companyId);
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0)
            throw invalidDossier("A current nonnegative version is required");
        String name = requiredDossier(request.name(), 255);
        validateCompleteLegal(request.legal());
        VerificationDocument document = retainCompleteDocument(actor, file);
        Company company = companyService.resubmitCompany(actor, current.getId(), new CompanyResubmissionRequest(
                name, null, documentPath(document.getId()), request.expectedVersion()));
        recordAttempt(actor, company, completeLegal(request.legal(), document.getId()));
        return company;
    }

    private static void validateCompleteLegal(CompanyDossierRequest.Legal legal) {
        if (legal == null || !Boolean.TRUE.equals(legal.declarationAccepted()))
            throw invalidDossier("Legal details and explicit declaration are required");
        requiredDossier(legal.workAddress(), 1000);
        requiredDossier(legal.submitterPosition(), 200);
        if (!Set.of("LEGAL_REPRESENTATIVE", "AUTHORIZED_REPRESENTATIVE").contains(String.valueOf(legal.representation())))
            throw invalidDossier("Representative selection is required");
    }

    private static LegalDetails completeLegal(CompanyDossierRequest.Legal legal, UUID documentId) {
        return new LegalDetails(legal.workAddress().trim(), legal.submitterPosition().trim(),
                legal.representation(), true, documentId);
    }

    private VerificationDocument retainCompleteDocument(Long actor, List<MultipartFile> files) {
        if (files == null || files.size() != 1) throw invalidDossier("Exactly one registration document is required");
        MultipartFile file = files.getFirst();
        if (file == null || file.isEmpty()) throw invalidDossier("A registration document is required");
        if (file.getSize() > MAX_DOCUMENT_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                "41330 DOCUMENT_TOO_LARGE: Maximum document size is 10 MiB");
        byte[] bytes;
        try { bytes = file.getBytes(); }
        catch (java.io.IOException invalid) { throw invalidDossier("Document could not be read"); }
        String media = mediaType(bytes);
        if (media == null) throw invalidDossier("Only PDF, JPEG and PNG documents are allowed");
        VerificationDocument document = new VerificationDocument();
        document.setId(UUID.randomUUID()); document.setUploaderId(actor);
        String filename = file.getOriginalFilename() == null ? "document" : file.getOriginalFilename();
        filename = filename.replaceAll("[\\p{Cntrl}/\\\\]", "_");
        document.setFilename(requiredDossier(filename, 255));
        document.setMediaType(media); document.setContent(bytes); document.setContentHash(OnboardingSecrets.hash(bytes));
        document.setCreatedAt(Instant.now());
        return documents.saveAndFlush(document);
    }

    private static String requiredDossier(String value, int maximum) {
        if (value == null || value.isBlank() || value.trim().codePointCount(0, value.trim().length()) > maximum)
            throw invalidDossier("Required dossier field is invalid");
        return value.trim();
    }

    private static ResponseStatusException invalidDossier(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "40001 INVALID_INPUT: " + reason);
    }

    @Transactional
    public VerificationDocument document(Long actor, UUID id) {
        var user = access.requireActiveUser(actor);
        var d = documents.findById(id).orElseThrow(LegalDossierService::notFound);
        if ("STAFF".equals(user.getRole())) return d;
        if (profiles.findById(actor).isPresent()) personal.requirePersonal(actor);
        var linked = attempts.findByDocumentId(id);
        if (linked.isEmpty() && actor.equals(d.getUploaderId())) return d;
        var current = access.findCurrentCompanyAccess(actor);
        if (current != null && attempts.existsByCompanyId(current.company().getId())) personal.requirePersonal(actor);
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
        // Only un-enrolled legacy Owners retain the URL-only correction contract.
        if (!attempts.existsByCompanyId(current.getId()) && profiles.findById(actor).isEmpty())
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
        if ("STAFF".equals(user.getRole()) && companyId != null) {
            id = companies.findById(companyId).orElseThrow(LegalDossierService::notFound).getId();
        } else id = companyId == null ? access.requireOwner(actor).company().getId() : access.requireOwnerOf(actor, companyId).getId();
        if (!"STAFF".equals(user.getRole()) && (profiles.findById(actor).isPresent() || attempts.existsByCompanyId(id)))
            personal.requirePersonal(actor);
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
