package com.guardwork.backend.candidate;

import java.util.HashSet;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.guardwork.backend.candidate.dto.UpdateCandidateProfileRequest;
import com.guardwork.backend.candidate.util.ValidationConstants;
import com.guardwork.backend.user.model.User;
import com.guardwork.backend.user.repository.UserRepository;
import com.guardwork.backend.candidate.util.ContactMaskingUtils;
import jakarta.transaction.Transactional;
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
    public CandidateProfile getOrCreateProfile(Long userId) {
        return profileRepository.findByUserId(userId)
                .orElseGet(() -> {
                    CandidateProfile skeleton = createSkeletonProfile(userId);
                    return profileRepository.save(skeleton);
                });
    }

    private CandidateProfile createSkeletonProfile(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
        String defaultFullName = (user.getFirstName() != null ? user.getFirstName() : "")
                + " "
                + (user.getLastName() != null ? user.getLastName() : "");
        CandidateProfile profile = new CandidateProfile();
        profile.setId(java.util.UUID.randomUUID());
        profile.setUserId(userId);
        profile.setFullName(defaultFullName.trim());
        profile.setSkills(List.of());
        profile.setPublic(true);
        return profile; // no save here
    }

    /**
     * Updates a candidate profile based on the supplied request payload.
     */
    @Transactional
    public CandidateProfile updateProfile(Long userId, UpdateCandidateProfileRequest request) {
        CandidateProfile profile = profileRepository.findByUserId(userId)
                .orElseGet(() -> createSkeletonProfile(userId));

        // ----- Simple scalar fields -----
        profile.setFullName(request.fullName());
        profile.setPhoneNumber(request.phoneNumber());
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

        return profileRepository.save(profile);
    }

    /**
     * Utility that can be used by future employer‑facing services to mask contact info.
     */
    public static void applyMaskIfUnverified(CandidateProfile profile, boolean employerVerified) {
        if (!employerVerified) {
            // Mask email and phone. Email is stored in the User entity – not directly here,
            // but we expose a hook for future use.
            profile.setPhoneNumber(ContactMaskingUtils.maskPhone(profile.getPhoneNumber()));
            // Note: email masking would be performed on the User object elsewhere.
        }
    }
}
