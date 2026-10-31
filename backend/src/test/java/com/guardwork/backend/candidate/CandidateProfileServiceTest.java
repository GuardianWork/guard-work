package com.guardwork.backend.candidate;

import com.guardwork.backend.candidate.dto.UpdateCandidateProfileRequest;
import com.guardwork.backend.candidate.dto.CandidateProfileResponse;
import com.guardwork.backend.candidate.util.ValidationConstants;
import com.guardwork.backend.user.model.User;
import com.guardwork.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CandidateProfileServiceTest {
    @Mock
    private CandidateProfileRepository profileRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CandidateProfileService profileService;

    private User sampleUser;
    private CandidateProfile existingProfile;

    @BeforeEach
    void setUp() {
        sampleUser = new User();
        sampleUser.setId(1L);
        sampleUser.setFirstName("John");
        sampleUser.setLastName("Doe");

        existingProfile = new CandidateProfile();
        existingProfile.setId(java.util.UUID.randomUUID());
        existingProfile.setUserId(sampleUser.getId());
        existingProfile.setFullName("John Doe");
        existingProfile.setPublic(true);
        existingProfile.setSkills(List.of("Java", "Spring"));
    }

    @Nested
    @DisplayName("getOrCreateProfile happy path")
    class GetOrCreateProfile {
        @Test
        void returnsExistingProfileWhenFound() {
            when(profileRepository.findByUserId(sampleUser.getId()))
                    .thenReturn(Optional.of(existingProfile));
            when(userRepository.findById(sampleUser.getId())).thenReturn(Optional.of(sampleUser));

            CandidateProfileResponse result = profileService.getOrCreateProfile(sampleUser.getId());
            assertEquals(existingProfile.getId(), result.id());
            verify(profileRepository, never()).save(any());
        }

        @Test
        void createsSkeletonWhenMissing() {
            when(profileRepository.findByUserId(sampleUser.getId()))
                    .thenReturn(Optional.empty());
            when(userRepository.findById(sampleUser.getId()))
                    .thenReturn(Optional.of(sampleUser));
            when(profileRepository.save(any(CandidateProfile.class)))
                    .thenAnswer(invocation -> {
                        CandidateProfile saved = invocation.getArgument(0);
                        assertNull(saved.getId(), "The persistence provider generates a new profile ID");
                        saved.setId(java.util.UUID.randomUUID());
                        return saved;
                    });

            CandidateProfileResponse result = profileService.getOrCreateProfile(sampleUser.getId());
            assertNotNull(result.id());
            assertEquals("John Doe", result.fullName());
            assertTrue(result.isPublic());
            verify(profileRepository).save(any(CandidateProfile.class));
        }
    }

    @Nested
    @DisplayName("updateProfile happy path")
    class UpdateProfile {
        private UpdateCandidateProfileRequest request;

        @BeforeEach
        void initRequest() {
            when(userRepository.findByIdForUpdate(sampleUser.getId())).thenReturn(Optional.of(sampleUser));
            request = new UpdateCandidateProfileRequest(
                    "Jane Doe",
                    "0901234567",
                    "https://example.com/avatar.png",
                    List.of("Kotlin", "Docker"),
                    "5 years",
                    "BS Computer Science",
                    true
            );
        }

        @Test
        void updatesAllFieldsWhenValid() {
            when(profileRepository.findByUserId(sampleUser.getId()))
                    .thenReturn(Optional.of(existingProfile));
            when(profileRepository.save(any(CandidateProfile.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            CandidateProfileResponse updated = profileService.updateProfile(sampleUser.getId(), request);
            assertEquals("Jane Doe", updated.fullName());
            assertEquals("+84901234567", updated.phoneNumber());
            assertEquals("+84901234567", sampleUser.getPhoneNumber());
            assertNull(existingProfile.getPhoneNumber());
            assertEquals("https://example.com/avatar.png", updated.avatarUrl());
            assertEquals(List.of("Kotlin", "Docker"), updated.skills());
            assertEquals("5 years", updated.experienceSummary());
            assertEquals("BS Computer Science", updated.educationSummary());
            assertTrue(updated.isPublic());
        }

        @Test
        void createsSkeletonWhenProfileMissing() {
            when(profileRepository.findByUserId(sampleUser.getId()))
                    .thenReturn(Optional.empty());
            when(profileRepository.save(any(CandidateProfile.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            CandidateProfileResponse updated = profileService.updateProfile(sampleUser.getId(), request);
            assertEquals("Jane Doe", updated.fullName());
            verify(profileRepository).save(any(CandidateProfile.class));
        }
    }

    @Nested
    @DisplayName("error handling (guard clauses)")
    class GuardClauses {
        @Test
        void throwsWhenUserNotFoundDuringSkeletonCreation() {
            when(userRepository.findById(anyLong()))
                    .thenReturn(Optional.empty());

            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                    profileService.getOrCreateProfile(999L));
            assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
            assertTrue(ex.getReason().contains("USER_NOT_FOUND"));
        }

        @Test
        void throwsWhenSkillExceedsMaxLength() {
            when(userRepository.findByIdForUpdate(sampleUser.getId())).thenReturn(Optional.of(sampleUser));
            String longSkill = "a".repeat(ValidationConstants.MAX_SKILL_LENGTH + 1);
            var request = new UpdateCandidateProfileRequest(
                    "John Doe",
                    "0901234567",
                    "https://example.com/avatar.png",
                    List.of(longSkill),
                    null, null, null);

            when(profileRepository.findByUserId(sampleUser.getId()))
                    .thenReturn(Optional.of(existingProfile));

            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                    profileService.updateProfile(sampleUser.getId(), request));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
            assertTrue(ex.getReason().contains("exceeds maximum length"));
        }

        @Test
        void throwsWhenTooManySkills() {
            when(userRepository.findByIdForUpdate(sampleUser.getId())).thenReturn(Optional.of(sampleUser));
            List<String> manySkills = java.util.stream.IntStream.range(0, ValidationConstants.MAX_SKILLS + 1)
                    .mapToObj(i -> "Skill" + i)
                    .toList();
            var request = new UpdateCandidateProfileRequest(
                    "John Doe",
                    "0901234567",
                    "https://example.com/avatar.png",
                    manySkills,
                    null, null, null);

            when(profileRepository.findByUserId(sampleUser.getId()))
                    .thenReturn(Optional.of(existingProfile));

            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                    profileService.updateProfile(sampleUser.getId(), request));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
            assertTrue(ex.getReason().contains("Number of skills exceeds"));
        }
    }
}
