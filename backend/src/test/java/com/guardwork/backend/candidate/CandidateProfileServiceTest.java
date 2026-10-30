package com.guardwork.backend.candidate;

import com.guardwork.backend.candidate.dto.UpdateCandidateProfileRequest;
import com.guardwork.backend.candidate.dto.ExperienceEntry;
import com.guardwork.backend.candidate.dto.EducationEntry;
import com.guardwork.backend.candidate.util.ValidationConstants;
import com.guardwork.backend.common.exception.AppException;
import com.guardwork.backend.common.exception.ErrorCode;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
    private CandidateProfileService service;

    private User user;
    private CandidateProfile profile;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(1L);
        user.setFirstName("John");
        user.setLastName("Doe");
        user.setEmail("john@example.com");

        profile = new CandidateProfile();
        profile.setId(UUID.randomUUID());
        profile.setUserId(user.getId());
        profile.setFullName("John Doe");
        profile.setPublic(true);
        profile.setSkills(List.of("Java", "Spring"));
    }

    @Nested
    @DisplayName("getOrCreateProfile")
    class GetOrCreateProfile {

        @Test
        void returnsExistingWhenFound() {
            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
            when(profileRepository.findByUserId(user.getId())).thenReturn(Optional.of(profile));

            CandidateProfile result = service.getOrCreateProfile(user.getId());

            assertSame(profile, result);
            assertEquals(user.getEmail(), result.getEmail());
            verify(profileRepository, never()).save(any());
        }

        @Test
        void createsSkeletonWhenMissing() {
            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
            when(profileRepository.findByUserId(user.getId())).thenReturn(Optional.empty());
            when(profileRepository.save(any(CandidateProfile.class))).thenAnswer(i -> i.getArgument(0));

            CandidateProfile result = service.getOrCreateProfile(user.getId());

            assertNotNull(result);
            assertEquals("John Doe", result.getFullName());
            assertEquals(user.getEmail(), result.getEmail());
            assertTrue(result.isPublic());
            assertTrue(result.getSkills().isEmpty());
            verify(profileRepository).save(any(CandidateProfile.class));
        }

        @Test
        void throwsWhenUserMissing() {
            when(userRepository.findById(anyLong())).thenReturn(Optional.empty());

            AppException ex = assertThrows(AppException.class,
                    () -> service.getOrCreateProfile(999L));
            assertEquals(ErrorCode.USER_NOT_FOUND, ex.getErrorCode());
        }
    }

    @Nested
    @DisplayName("updateProfile")
    class UpdateProfile {

        @Test
        void updatesProfileAndDerivesEmail() {
            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
            when(profileRepository.findByUserId(user.getId())).thenReturn(Optional.of(profile));
            when(profileRepository.save(any(CandidateProfile.class))).thenAnswer(i -> i.getArgument(0));

            UpdateCandidateProfileRequest request = new UpdateCandidateProfileRequest(
                    "Jane Doe",
                    "0123",
                    "https://example.com/avatar.png",
                    List.of("Java", "Spring"),
                    List.of(new ExperienceEntry("Acme", "Dev", "2020-01", "2021-01", "Did stuff")),
                    List.of(new EducationEntry("Uni", "BSc", "2015", "2019", "CS")),
                    true
            );

            CandidateProfile updated = service.updateProfile(user.getId(), request);

            assertEquals("Jane Doe", updated.getFullName());
            assertEquals("0123", updated.getPhoneNumber());
            assertEquals("https://example.com/avatar.png", updated.getAvatarUrl());
            assertEquals(user.getEmail(), updated.getEmail());
            assertEquals(1, updated.getExperience().size());
            assertEquals("Acme", updated.getExperience().get(0).company());
            assertEquals(1, updated.getEducation().size());
            assertEquals("Uni", updated.getEducation().get(0).institution());
            assertTrue(updated.isPublic());
        }

        @Test
        void trimsAndDeduplicatesSkills() {
            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
            when(profileRepository.findByUserId(user.getId())).thenReturn(Optional.of(profile));
            when(profileRepository.save(any(CandidateProfile.class))).thenAnswer(i -> i.getArgument(0));

            UpdateCandidateProfileRequest request = new UpdateCandidateProfileRequest(
                    "John Doe",
                    null,
                    null,
                    List.of("Java", "  java  ", "", "Spring", "Java"),
                    null,
                    null,
                    null
            );

            CandidateProfile updated = service.updateProfile(user.getId(), request);
            assertEquals(List.of("Java", "Spring"), updated.getSkills());
        }

        @Test
        void createsSkeletonWhenProfileMissing() {
            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
            when(profileRepository.findByUserId(user.getId())).thenReturn(Optional.empty());
            when(profileRepository.save(any(CandidateProfile.class))).thenAnswer(i -> i.getArgument(0));

            UpdateCandidateProfileRequest request = new UpdateCandidateProfileRequest(
                    "John Doe",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );

            CandidateProfile updated = service.updateProfile(user.getId(), request);

            assertEquals(user.getId(), updated.getUserId());
            assertEquals(user.getEmail(), updated.getEmail());
            verify(profileRepository).save(any(CandidateProfile.class));
        }

        @Test
        void validatesSkillLength() {
            String longSkill = "a".repeat(ValidationConstants.MAX_SKILL_LENGTH + 1);
            UpdateCandidateProfileRequest request = new UpdateCandidateProfileRequest(
                    "John Doe",
                    null,
                    null,
                    List.of(longSkill),
                    null,
                    null,
                    null
            );

            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
            when(profileRepository.findByUserId(user.getId())).thenReturn(Optional.of(profile));

            AppException ex = assertThrows(AppException.class,
                    () -> service.updateProfile(user.getId(), request));
            assertEquals(ErrorCode.INVALID_INPUT, ex.getErrorCode());
        }

        @Test
        void validatesSkillCount() {
            List<String> many = java.util.stream.IntStream.range(0, ValidationConstants.MAX_SKILLS + 1)
                    .mapToObj(i -> "Skill" + i).toList();
            UpdateCandidateProfileRequest request = new UpdateCandidateProfileRequest(
                    "John Doe",
                    null,
                    null,
                    many,
                    null,
                    null,
                    null
            );

            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
            when(profileRepository.findByUserId(user.getId())).thenReturn(Optional.of(profile));

            AppException ex = assertThrows(AppException.class,
                    () -> service.updateProfile(user.getId(), request));
            assertEquals(ErrorCode.INVALID_INPUT, ex.getErrorCode());
        }
    }
}
