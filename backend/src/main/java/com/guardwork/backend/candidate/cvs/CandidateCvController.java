package com.guardwork.backend.candidate.cvs;

import com.guardwork.backend.candidate.cvs.dto.CandidateCvResponse;
import com.guardwork.backend.candidate.cvs.dto.CreateCandidateCvRequest;
import com.guardwork.backend.candidate.cvs.dto.UploadUrlRequest;
import com.guardwork.backend.candidate.cvs.dto.UploadUrlResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;
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
        return cvService.generateUploadUrl(request);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CandidateCvResponse registerCv(@Valid @RequestBody CreateCandidateCvRequest request) {
        return cvService.registerCv(request);
    }

    @GetMapping
    public List<CandidateCvResponse> listCvs(@RequestParam UUID candidateId) {
        return cvService.listCvs(candidateId);
    }

    @GetMapping("/{id}/download-url")
    public UploadUrlResponse getDownloadUrl(@PathVariable("id") UUID cvId) {
        return cvService.generateDownloadUrl(cvId);
    }
}
