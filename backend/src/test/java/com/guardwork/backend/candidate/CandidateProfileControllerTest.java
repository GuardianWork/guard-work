package com.guardwork.backend.candidate;

import com.guardwork.backend.candidate.dto.UpdateCandidateProfileRequest;
import com.guardwork.backend.candidate.dto.ExperienceEntry;
import com.guardwork.backend.candidate.dto.EducationEntry;
import com.guardwork.backend.auth.security.UserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import org.mockito.MockedStatic;
import com.guardwork.backend.common.utils.SecurityUtils;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests the full request/response flow of {@link CandidateProfileController}.
 * The service layer is mocked so that we can assert the controller correctly
 * maps the domain object to the DTO and returns the expected JSON payload.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CandidateProfileController flow tests")
class CandidateProfileControllerTest {

    private MockMvc mockMvc;

    @Mock
    private CandidateProfileService profileService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UserPrincipal testPrincipal = new UserPrincipal(1L, "testuser", "test@example.com", "USER");

    private CandidateProfile sampleProfile() {
        CandidateProfile profile = new CandidateProfile();
        profile.setId(UUID.randomUUID());
        profile.setUserId(testPrincipal.getId());
        profile.setEmail(testPrincipal.getEmail());
        profile.setFullName("John Doe");
        profile.setAvatarUrl("https://example.com/avatar.png");
        profile.setPhoneNumber("0123456789");
        profile.setSkills(List.of("Kotlin", "Docker"));
        profile.setExperience(List.of(new ExperienceEntry("Acme", "Dev", "2019-01", "2024-01", "5 years experience")));
        profile.setEducation(List.of(new EducationEntry("State Uni", "BSc", "2014", "2018", "Computer Science")));
        profile.setPublic(true);
        profile.setCreatedAt(java.time.Instant.now());
        profile.setUpdatedAt(java.time.Instant.now());
        return profile;
    }


    @BeforeEach
    void setUp() {
        CandidateProfileController controller = new CandidateProfileController(profileService);
        this.mockMvc = MockMvcBuilders.standaloneSetup(controller).build();


    }

    @Test
    void getProfile_returnsMappedResponse() throws Exception {
        CandidateProfile profile = sampleProfile();
        when(profileService.getOrCreateProfile(anyLong())).thenReturn(profile);

        try (MockedStatic<SecurityUtils> mockedSecurity = org.mockito.Mockito.mockStatic(SecurityUtils.class)) {
            mockedSecurity.when(SecurityUtils::getCurrentUserId).thenReturn(testPrincipal.getId());
            mockMvc.perform(get("/api/candidates/profile"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value("200"))
                    .andExpect(jsonPath("$.data.email").value(testPrincipal.getEmail()))
                    .andExpect(jsonPath("$.data.fullName").value(profile.getFullName()))
                    .andExpect(jsonPath("$.data.avatarUrl").value(profile.getAvatarUrl()))
                    .andExpect(jsonPath("$.data.skills[0]").value("Kotlin"))
                    .andExpect(jsonPath("$.data.isPublic").value(true));
        }
    }

    @Test
    void updateProfile_returnsUpdatedResponse() throws Exception {
        CandidateProfile updated = sampleProfile();
        updated.setFullName("Jane Doe");
        updated.setPhoneNumber("0987654321");
        updated.setPublic(false);

        UpdateCandidateProfileRequest request = new UpdateCandidateProfileRequest(
                "Jane Doe",
                "0987654321",
                "https://example.com/new-avatar.png",
                List.of("Java", "Spring"),
                List.of(new ExperienceEntry("Corp", "Engineer", "2020-01", null, "6 years")),
                List.of(new EducationEntry("Uni", "MSc", "2015", "2018", "Computer Science")),
                false
        );

        when(profileService.updateProfile(anyLong(), any(UpdateCandidateProfileRequest.class)))
                .thenReturn(updated);

        try (MockedStatic<SecurityUtils> mockedSecurity = org.mockito.Mockito.mockStatic(SecurityUtils.class)) {
            mockedSecurity.when(SecurityUtils::getCurrentUserId).thenReturn(testPrincipal.getId());
            mockMvc.perform(put("/api/candidates/profile")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value("200"))
                    .andExpect(jsonPath("$.data.fullName").value("Jane Doe"))
                    .andExpect(jsonPath("$.data.phoneNumber").value("0987654321"))
                    .andExpect(jsonPath("$.data.isPublic").value(false));
        }
    }

    @Test
    void updateProfile_rejectsInvalidNestedExperience() throws Exception {
        UpdateCandidateProfileRequest request = new UpdateCandidateProfileRequest(
                "Jane Doe",
                null,
                null,
                null,
                List.of(new ExperienceEntry("", "Engineer", "2020-01", null, null)),
                null,
                null
        );

        try (MockedStatic<SecurityUtils> mockedSecurity = org.mockito.Mockito.mockStatic(SecurityUtils.class)) {
            mockedSecurity.when(SecurityUtils::getCurrentUserId).thenReturn(testPrincipal.getId());
            mockMvc.perform(put("/api/candidates/profile")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }
    }
}
