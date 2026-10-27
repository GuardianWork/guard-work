package com.guardwork.backend.candidate;

import com.guardwork.backend.candidate.dto.CandidateProfileResponse;
import com.guardwork.backend.common.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.AccessLevel;
import com.guardwork.backend.candidate.dto.UpdateCandidateProfileRequest;
import com.guardwork.backend.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/candidates")
@RequiredArgsConstructor
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
public class CandidateProfileController {

    CandidateProfileService profileService;

    @GetMapping("/profile")
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<CandidateProfileResponse> getProfile() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.ok(profileService.getOrCreateProfile(userId));
    }

    @PutMapping("/profile")
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<CandidateProfileResponse> updateProfile(
            @Valid @RequestBody UpdateCandidateProfileRequest request) {

        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.ok(profileService.updateProfile(userId, request));
    }

}
