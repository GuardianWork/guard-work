package com.guardwork.backend.onboarding.controller;

import com.guardwork.backend.auth.security.UserPrincipal;
import com.guardwork.backend.common.ApiResponse;
import com.guardwork.backend.onboarding.service.LegalDossierService;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class VerificationDocumentController {
    private final LegalDossierService dossiers;
    public VerificationDocumentController(LegalDossierService dossiers) { this.dossiers = dossiers; }

    @PostMapping("/api/me/employer-onboarding/documents")
    public ApiResponse<LegalDossierService.DocumentInfo> upload(@AuthenticationPrincipal UserPrincipal actor,
            @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok("Document retained", dossiers.upload(actor.getId(), file));
    }

    @GetMapping("/api/company-verification-documents/{id}")
    public ResponseEntity<byte[]> document(@AuthenticationPrincipal UserPrincipal actor, @PathVariable UUID id) {
        var document = dossiers.document(actor.getId(), id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(document.getMediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(document.getFilename(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff").cacheControl(CacheControl.noStore()).body(document.getContent());
    }

    @GetMapping("/api/companies/me/verification/history")
    public ApiResponse<List<LegalDossierService.AttemptInfo>> history(@AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.ok("Verification history", dossiers.history(actor.getId(), null));
    }

    @GetMapping("/api/staff/companies/{id}/verification-history")
    public ApiResponse<List<LegalDossierService.AttemptInfo>> staffHistory(@AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id) {
        return ApiResponse.ok("Verification history", dossiers.history(actor.getId(), id));
    }
}
