package com.guardwork.backend.candidate;

import com.guardwork.backend.candidate.dto.UpdateCandidateProfileRequest;
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

            CandidateProfile result = profileService.getOrCreateProfile(sampleUser.getId());
            assertSame(existingProfile, result);
            verify(profileRepository, never()).save(any());
        }

        @Test
        void createsSkeletonWhenMissing() {
            when(profileRepository.findByUserId(sampleUser.getId()))
                    .thenReturn(Optional.empty());
            when(userRepository.findById(sampleUser.getId()))
                    .thenReturn(Optional.of(sampleUser));
            when(profileRepository.save(any(CandidateProfile.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            CandidateProfile result = profileService.getOrCreateProfile(sampleUser.getId());
            assertNotNull(result.getId());
            assertEquals("John Doe", result.getFullName());
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
            request = new UpdateCandidateProfileRequest(
                    "Jane Doe",
                    "0123456789",
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

            CandidateProfile updated = profileService.updateProfile(sampleUser.getId(), request);
            assertEquals("Jane Doe", updated.getFullName());
            assertEquals("0123456789", updated.getPhoneNumber());
            assertEquals("https://example.com/avatar.png", updated.getAvatarUrl());
            assertEquals(List.of("Kotlin", "Docker"), updated.getSkills());
            assertEquals("5 years", updated.getExperienceSummary());
            assertEquals("BS Computer Science", updated.getEducationSummary());
            assertTrue(updated.isPublic());
        }

        @Test
        void createsSkeletonWhenProfileMissing() {
            when(profileRepository.findByUserId(sampleUser.getId()))
                    .thenReturn(Optional.empty());
            when(userRepository.findById(sampleUser.getId()))
                    .thenReturn(Optional.of(sampleUser));
            when(profileRepository.save(any(CandidateProfile.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            CandidateProfile updated = profileService.updateProfile(sampleUser.getId(), request);
            assertEquals("Jane Doe", updated.getFullName());
            verify(profileRepository).save(any(CandidateProfile.class));
        }
    }

    @Nested
    @DisplayName("error handling (guard clauses)")
    class GuardClauses {
        @Test
        void throwsWhenUserNotFoundDuringSkeletonCreation() {
            when(profileRepository.findByUserId(anyLong()))
                    .thenReturn(Optional.empty());
            when(userRepository.findById(anyLong()))
                    .thenReturn(Optional.empty());

            ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                    profileService.getOrCreateProfile(999L));
            assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
            assertTrue(ex.getReason().contains("USER_NOT_FOUND"));
        }

        @Test
        void throwsWhenSkillExceedsMaxLength() {
            String longSkill = "a".repeat(ValidationConstants.MAX_SKILL_LENGTH + 1);
            var request = new UpdateCandidateProfileRequest(
                    "John Doe",
                    "0123456789",
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
            List<String> manySkills = java.util.stream.IntStream.range(0, ValidationConstants.MAX_SKILLS + 1)
                    .mapToObj(i -> "Skill" + i)
                    .toList();
            var request = new UpdateCandidateProfileRequest(
                    "John Doe",
                    "0123456789",
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
