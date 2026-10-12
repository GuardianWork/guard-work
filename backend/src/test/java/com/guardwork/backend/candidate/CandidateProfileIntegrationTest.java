package com.guardwork.backend.candidate;

import com.guardwork.backend.candidate.dto.UpdateCandidateProfileRequest;
import com.guardwork.backend.user.model.User;
import com.guardwork.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.guardwork.backend.candidate.util.ValidationConstants;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@AutoConfigureTestDatabase(replace = Replace.ANY)
@Transactional
@ActiveProfiles("test")
class CandidateProfileIntegrationTest {

    @Autowired
    private CandidateProfileService profileService;

    @Autowired
    private CandidateProfileRepository profileRepository;

    @Autowired
    private UserRepository userRepository;

    private User sampleUser;

    @BeforeEach
    void setUp() {
        // Ensure a clean user entry for each test
        sampleUser = new User();
        sampleUser.setId(1L);
        sampleUser.setFirstName("John");
        sampleUser.setLastName("Doe");
        userRepository.save(sampleUser);
    }

    @Test
    void getOrCreateProfile_returnsExistingWhenPresent() {
        CandidateProfile existing = new CandidateProfile();
        existing.setId(java.util.UUID.randomUUID());
        existing.setUserId(sampleUser.getId());
        existing.setFullName("John Doe");
        existing.setPublic(true);
        existing.setSkills(List.of("Java"));
        profileRepository.save(existing);

        CandidateProfile result = profileService.getOrCreateProfile(sampleUser.getId());
        assertEquals(existing.getId(), result.getId());
        assertSame(existing, result);
    }

    @Test
    void getOrCreateProfile_createsSkeletonWhenMissing() {
        CandidateProfile result = profileService.getOrCreateProfile(sampleUser.getId());
        assertNotNull(result.getId(), "Skeleton profile should have a generated UUID");
        assertEquals("John Doe", result.getFullName());
        assertTrue(result.isPublic());
        assertEquals(sampleUser.getId(), result.getUserId());
        // Verify it is persisted
        Optional<CandidateProfile> fromDb = profileRepository.findByUserId(sampleUser.getId());
        assertTrue(fromDb.isPresent());
        assertEquals(result.getId(), fromDb.get().getId());
    }

    @Test
    void updateProfile_updatesFieldsAndPersists() {
        // Start with a skeleton profile
        CandidateProfile skeleton = profileService.getOrCreateProfile(sampleUser.getId());
        UpdateCandidateProfileRequest request = new UpdateCandidateProfileRequest(
                "Jane Doe",
                "0123456789",
                "https://example.com/avatar.png",
                List.of("Kotlin", "Docker"),
                "5 years",
                "BS Computer Science",
                true
        );
        CandidateProfile updated = profileService.updateProfile(sampleUser.getId(), request);
        assertEquals("Jane Doe", updated.getFullName());
        assertEquals("0123456789", updated.getPhoneNumber());
        assertEquals("https://example.com/avatar.png", updated.getAvatarUrl());
        assertEquals(List.of("Kotlin", "Docker"), updated.getSkills());
        assertEquals("5 years", updated.getExperienceSummary());
        assertEquals("BS Computer Science", updated.getEducationSummary());
        assertTrue(updated.isPublic());
        // Verify persistence
        CandidateProfile fromDb = profileRepository.findByUserId(sampleUser.getId()).orElseThrow();
        assertEquals(updated.getId(), fromDb.getId());
        assertEquals("Jane Doe", fromDb.getFullName());
    }
}
