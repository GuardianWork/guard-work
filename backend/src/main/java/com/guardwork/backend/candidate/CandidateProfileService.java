package com.guardwork.backend.candidate;

import java.util.HashSet;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.guardwork.backend.candidate.dto.UpdateCandidateProfileRequest;
import com.guardwork.backend.candidate.dto.CandidateProfileResponse;
import com.guardwork.backend.user.AccountPhone;
import com.guardwork.backend.candidate.util.ValidationConstants;
import com.guardwork.backend.user.model.User;
import com.guardwork.backend.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@FieldDefaults(makeFinal = true, level = lombok.AccessLevel.PRIVATE)
public class CandidateProfileService {

    CandidateProfileRepository profileRepository;
    UserRepository userRepository;

    /**
     * Returns the existing profile for the given user, or creates a skeleton one
     * using the user's first/last name combined as the default full name.
     */
    @Transactional
    public CandidateProfileResponse getOrCreateProfile(Long userId) {
        User user = requireUser(userId);
        CandidateProfile profile = profileRepository.findByUserId(userId)
                .orElseGet(() -> {
                    CandidateProfile skeleton = createSkeletonProfile(user);
                    return profileRepository.save(skeleton);
                });
        return response(profile, user);
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
    }

    private CandidateProfile createSkeletonProfile(User user) {
        String defaultFullName = (user.getFirstName() != null ? user.getFirstName() : "")
                + " "
                + (user.getLastName() != null ? user.getLastName() : "");
        CandidateProfile profile = new CandidateProfile();
        profile.setUserId(user.getId());
        profile.setFullName(defaultFullName.trim());
        profile.setSkills(List.of());
        profile.setPublic(true);
        return profile; // no save here
    }

    /**
     * Updates a candidate profile based on the supplied request payload.
     */
    @Transactional
    public CandidateProfileResponse updateProfile(Long userId, UpdateCandidateProfileRequest request) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
        String phone;
        try {
            phone = AccountPhone.normalize(request.phoneNumber());
        } catch (IllegalArgumentException invalidPhone) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PHONE_NUMBER");
        }
        CandidateProfile profile = profileRepository.findByUserId(userId)
                .orElseGet(() -> createSkeletonProfile(user));

        // ----- Simple scalar fields -----
        profile.setFullName(request.fullName());
        profile.setAvatarUrl(request.avatarUrl());
        profile.setExperienceSummary(request.experienceSummary());
        profile.setEducationSummary(request.educationSummary());
        if (request.isPublic() != null) {
            profile.setPublic(request.isPublic());
        }

        // ----- Skills handling -----
        if (request.skills() != null) {
            List<String> incoming = request.skills();
            // Trim, filter blanks, enforce length, deduplicate case‑insensitively
            Set<String> normalized = new HashSet<>();
            List<String> cleaned = incoming.stream()
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .peek(s -> {
                        if (s.length() > ValidationConstants.MAX_SKILL_LENGTH) {
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                    "Skill \"" + s + "\" exceeds maximum length of " + ValidationConstants.MAX_SKILL_LENGTH);
                        }
                    })
                    .filter(s -> normalized.add(s.toLowerCase())) // keep first occurrence, deduplicate case‑insensitively
                    .collect(Collectors.toList());

            if (cleaned.size() > ValidationConstants.MAX_SKILLS) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Number of skills exceeds maximum of " + ValidationConstants.MAX_SKILLS);
            }
            profile.setSkills(cleaned);
        }

        user.setPhoneNumber(phone);
        try {
            // Flush here so a racing claim rolls back this entire profile transaction.
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException conflict) {
            if (String.valueOf(conflict.getMostSpecificCause().getMessage()).contains("uq_users_phone_number")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "40930 IDENTIFIER_UNAVAILABLE: Contact information is unavailable");
            }
            throw conflict;
        }
        return response(profileRepository.save(profile), user);
    }

    private CandidateProfileResponse response(CandidateProfile profile, User user) {
        return new CandidateProfileResponse(profile.getId(), profile.getUserId(), profile.getFullName(),
                profile.getAvatarUrl(), user.getPhoneNumber(), profile.getSkills(), profile.getExperienceSummary(),
                profile.getEducationSummary(), profile.isPublic(), profile.getCreatedAt(), profile.getUpdatedAt());
    }
}
