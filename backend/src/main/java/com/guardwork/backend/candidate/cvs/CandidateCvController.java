package com.guardwork.backend.candidate.cvs;

import com.guardwork.backend.candidate.cvs.dto.CandidateCvResponse;
import com.guardwork.backend.candidate.cvs.dto.CreateCandidateCvRequest;
import com.guardwork.backend.candidate.cvs.dto.UploadUrlRequest;
import com.guardwork.backend.candidate.cvs.dto.UploadUrlResponse;
import com.guardwork.backend.common.ApiResponse;
import com.guardwork.backend.common.utils.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/candidate/cvs")
@RequiredArgsConstructor
public class CandidateCvController {

    private final CandidateCvService cvService;

    @PostMapping("/upload-url")
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<UploadUrlResponse> getUploadUrl(@Valid @RequestBody UploadUrlRequest request) {
        return ApiResponse.ok(cvService.generateUploadUrl(SecurityUtils.getCurrentUserId(), request));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CandidateCvResponse> registerCv(@Valid @RequestBody CreateCandidateCvRequest request) {
        return ApiResponse.created(
                "CV uploaded successfully",
                cvService.registerCv(SecurityUtils.getCurrentUserId(), request));
    }

    /**
     * The candidate is taken from the authenticated principal. The optional parameter is kept
     * for older clients and must match that candidate when supplied.
     */
    @GetMapping
    public ApiResponse<List<CandidateCvResponse>> listCvs(@RequestParam(required = false) UUID candidateId) {
        return ApiResponse.ok(cvService.listCvs(SecurityUtils.getCurrentUserId(), candidateId));
    }

    @GetMapping("/{id}/download-url")
    public ApiResponse<UploadUrlResponse> getDownloadUrl(@PathVariable("id") UUID cvId) {
        return ApiResponse.ok(cvService.generateDownloadUrl(SecurityUtils.getCurrentUserId(), cvId));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteCv(@PathVariable("id") UUID cvId) {
        cvService.deleteCv(SecurityUtils.getCurrentUserId(), cvId);
        return ApiResponse.ok(null);
    }

    @PatchMapping("/{id}/default")
    public ApiResponse<CandidateCvResponse> setDefaultCv(@PathVariable("id") UUID cvId) {
        return ApiResponse.ok(cvService.setDefaultCv(SecurityUtils.getCurrentUserId(), cvId));
    }
}
