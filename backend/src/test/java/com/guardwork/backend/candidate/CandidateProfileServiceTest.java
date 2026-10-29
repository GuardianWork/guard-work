package com.guardwork.backend.candidate;

import com.guardwork.backend.candidate.dto.UpdateCandidateProfileRequest;
import com.guardwork.backend.candidate.dto.ExperienceEntry;
import com.guardwork.backend.candidate.dto.EducationEntry;
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
            when(profileRepository.findByUserId(user.getId())).thenReturn(Optional.of(profile));

            CandidateProfile result = service.getOrCreateProfile(user.getId());
            assertSame(profile, result);
            verify(profileRepository, never()).save(any());
        }

        @Test
        void createsSkeletonWhenMissing() {
            when(profileRepository.findByUserId(user.getId())).thenReturn(Optional.empty());
            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
            when(profileRepository.save(any(CandidateProfile.class))).thenAnswer(i -> i.getArgument(0));

            CandidateProfile result = service.getOrCreateProfile(user.getId());
            assertNotNull(result);
            assertEquals("John Doe", result.getFullName());
            assertTrue(result.isPublic());
            verify(profileRepository).save(any(CandidateProfile.class));
        }

        @Test
        void throwsWhenUserMissing() {
            when(profileRepository.findByUserId(anyLong())).thenReturn(Optional.empty());
            when(userRepository.findById(anyLong())).thenReturn(Optional.empty());

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.getOrCreateProfile(999L));
            assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        }
    }

    @Nested
    @DisplayName("updateProfile")
    class UpdateProfile {

        @Test
        void updatesEmailWhenUnique() {
            when(profileRepository.findByUserId(user.getId())).thenReturn(Optional.of(profile));
            when(profileRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
            when(profileRepository.save(any(CandidateProfile.class))).thenAnswer(i -> i.getArgument(0));

            UpdateCandidateProfileRequest request = new UpdateCandidateProfileRequest(
                    "new@example.com",
                    "John Doe",
                    "0123",
                    "https://example.com/avatar.png",
                    List.of("Java", "Spring"),
                    List.of(new ExperienceEntry("Acme", "Dev", "2020-01", "2021-01", "Did stuff")),
                    List.of(new EducationEntry("Uni", "BSc", "2015", "2019", "CS")),
                    true
            );

            CandidateProfile updated = service.updateProfile(user.getId(), request);
            assertEquals("new@example.com", updated.getEmail());
        }

        @Test
        void throwsWhenEmailTakenByOtherUser() {
            CandidateProfile other = new CandidateProfile();
            other.setUserId(2L);
            other.setEmail("taken@example.com");

            when(profileRepository.findByUserId(user.getId())).thenReturn(Optional.of(profile));
            when(profileRepository.findByEmail("taken@example.com")).thenReturn(Optional.of(other));

            UpdateCandidateProfileRequest request = new UpdateCandidateProfileRequest(
                    "taken@example.com",
                    "John Doe",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.updateProfile(user.getId(), request));
            assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        }

        @Test
        void trimsAndDeduplicatesSkills() {
            when(profileRepository.findByUserId(user.getId())).thenReturn(Optional.of(profile));
            when(profileRepository.save(any(CandidateProfile.class))).thenAnswer(i -> i.getArgument(0));

            UpdateCandidateProfileRequest request = new UpdateCandidateProfileRequest(
                    "john@example.com",
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
        void mapsExperienceAndEducationLists() {
            when(profileRepository.findByUserId(user.getId())).thenReturn(Optional.of(profile));
            when(profileRepository.save(any(CandidateProfile.class))).thenAnswer(i -> i.getArgument(0));

            ExperienceEntry exp = new ExperienceEntry("Acme", "Dev", "2020-01", null, "Desc");
            EducationEntry edu = new EducationEntry("Uni", "BSc", "2015", null, "CS");

            UpdateCandidateProfileRequest request = new UpdateCandidateProfileRequest(
                    "john@example.com",
                    "John Doe",
                    null,
                    null,
                    null,
                    List.of(exp),
                    List.of(edu),
                    null
            );

            CandidateProfile updated = service.updateProfile(user.getId(), request);
            assertEquals(1, updated.getExperience().size());
            assertEquals("Acme", updated.getExperience().get(0).company());
            assertEquals(1, updated.getEducation().size());
            assertEquals("Uni", updated.getEducation().get(0).institution());
        }

        @Test
        void validatesSkillLength() {
            String longSkill = "a".repeat(ValidationConstants.MAX_SKILL_LENGTH + 1);
            UpdateCandidateProfileRequest request = new UpdateCandidateProfileRequest(
                    "john@example.com",
                    "John Doe",
                    null,
                    null,
                    List.of(longSkill),
                    null,
                    null,
                    null
            );

            when(profileRepository.findByUserId(user.getId())).thenReturn(Optional.of(profile));

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.updateProfile(user.getId(), request));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        }

        @Test
        void validatesSkillCount() {
            List<String> many = java.util.stream.IntStream.range(0, ValidationConstants.MAX_SKILLS + 1)
                    .mapToObj(i -> "Skill" + i).toList();
            UpdateCandidateProfileRequest request = new UpdateCandidateProfileRequest(
                    "john@example.com",
                    "John Doe",
                    null,
                    null,
                    many,
                    null,
                    null,
                    null
            );

            when(profileRepository.findByUserId(user.getId())).thenReturn(Optional.of(profile));

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.updateProfile(user.getId(), request));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        }
    }
}
