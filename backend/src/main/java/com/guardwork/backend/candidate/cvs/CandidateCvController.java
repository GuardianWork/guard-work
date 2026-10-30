package com.guardwork.backend.candidate.cvs;

import com.guardwork.backend.candidate.cvs.dto.CandidateCvResponse;
import com.guardwork.backend.candidate.cvs.dto.CreateCandidateCvRequest;
import com.guardwork.backend.candidate.cvs.dto.UploadUrlRequest;
import com.guardwork.backend.candidate.cvs.dto.UploadUrlResponse;
import com.guardwork.backend.common.utils.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/candidate/cvs")
@RequiredArgsConstructor
public class CandidateCvController {

    private final CandidateCvService cvService;

    @PostMapping("/upload-url")
    @ResponseStatus(HttpStatus.OK)
    public UploadUrlResponse getUploadUrl(@Valid @RequestBody UploadUrlRequest request) {
        return cvService.generateUploadUrl(SecurityUtils.getCurrentUserId(), request);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CandidateCvResponse registerCv(@Valid @RequestBody CreateCandidateCvRequest request) {
        return cvService.registerCv(SecurityUtils.getCurrentUserId(), request);
    }

    /**
     * The candidate is taken from the authenticated principal. The optional parameter is kept
     * for older clients and must match that candidate when supplied.
     */
    @GetMapping
    public List<CandidateCvResponse> listCvs(@RequestParam(required = false) UUID candidateId) {
        return cvService.listCvs(SecurityUtils.getCurrentUserId(), candidateId);
    }

    @GetMapping("/{id}/download-url")
    public UploadUrlResponse getDownloadUrl(@PathVariable("id") UUID cvId) {
        return cvService.generateDownloadUrl(SecurityUtils.getCurrentUserId(), cvId);
    }
}
