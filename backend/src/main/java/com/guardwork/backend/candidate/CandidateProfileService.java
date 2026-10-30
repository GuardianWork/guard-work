package com.guardwork.backend.candidate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.guardwork.backend.candidate.dto.UpdateCandidateProfileRequest;
import com.guardwork.backend.candidate.util.ContactMaskingUtils;
import com.guardwork.backend.candidate.util.ValidationConstants;
import com.guardwork.backend.common.exception.AppException;
import com.guardwork.backend.common.exception.ErrorCode;
import com.guardwork.backend.user.model.User;
import com.guardwork.backend.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;

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
        User user = requireUser(userId);
        CandidateProfile profile = profileRepository.findByUserId(userId)
                .orElseGet(() -> profileRepository.save(createSkeletonProfile(user)));
        profile.setEmail(user.getEmail());
        return profile;
    }

    /**
     * Updates a candidate profile based on the supplied request payload.
     */
    @Transactional
    public CandidateProfile updateProfile(Long userId, UpdateCandidateProfileRequest request) {
        User user = requireUser(userId);
        CandidateProfile profile = profileRepository.findByUserId(userId)
                .orElseGet(() -> createSkeletonProfile(user));

        // Account email is derived from User so the profile cannot drift from the login identity.
        profile.setEmail(user.getEmail());
        profile.setFullName(request.fullName());
        profile.setPhoneNumber(request.phoneNumber());
        profile.setAvatarUrl(request.avatarUrl());
        if (request.isPublic() != null) {
            profile.setPublic(request.isPublic());
        }
        if (request.skills() != null) {
            profile.setSkills(cleanSkills(request.skills()));
        }
        if (request.experience() != null) {
            profile.setExperience(request.experience());
        }
        if (request.education() != null) {
            profile.setEducation(request.education());
        }
        return profileRepository.save(profile);
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
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
        return profile;
    }

    private List<String> cleanSkills(List<String> incoming) {
        Set<String> normalized = new HashSet<>();
        List<String> cleaned = incoming.stream()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .peek(s -> {
                    if (s.length() > ValidationConstants.MAX_SKILL_LENGTH) {
                        throw new AppException(ErrorCode.INVALID_INPUT,
                                "Skill \"" + s + "\" exceeds maximum length of " + ValidationConstants.MAX_SKILL_LENGTH);
                    }
                })
                // Keep the first occurrence and deduplicate case-insensitively.
                .filter(s -> normalized.add(s.toLowerCase()))
                .collect(Collectors.toList());

        if (cleaned.size() > ValidationConstants.MAX_SKILLS) {
            throw new AppException(ErrorCode.INVALID_INPUT,
                    "Number of skills exceeds maximum of " + ValidationConstants.MAX_SKILLS);
        }
        return cleaned;
    }

    /**
     * Utility that can be used by future employer-facing services to mask contact info.
     */
    public static void applyMaskIfUnverified(CandidateProfile profile, boolean employerVerified) {
        if (!employerVerified) {
            // Mask email and phone. Email is derived from the User entity and masked elsewhere.
            profile.setPhoneNumber(ContactMaskingUtils.maskPhone(profile.getPhoneNumber()));
        }
    }
}
