package com.guardwork.backend.candidate;

import com.guardwork.backend.candidate.dto.UpdateCandidateProfileRequest;
import com.guardwork.backend.candidate.dto.CandidateProfileResponse;
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

    private CandidateProfileResponse sampleProfile() {
        CandidateProfile profile = new CandidateProfile();
        profile.setId(UUID.randomUUID());
        profile.setUserId(testPrincipal.getId());
        profile.setFullName("John Doe");
        profile.setAvatarUrl("https://example.com/avatar.png");
        profile.setPhoneNumber("+84901234567");
        profile.setSkills(List.of("Kotlin", "Docker"));
        profile.setExperienceSummary("5 years");
        profile.setEducationSummary("BS Computer Science");
        profile.setPublic(true);
        profile.setCreatedAt(java.time.Instant.now());
        profile.setUpdatedAt(java.time.Instant.now());
        return new CandidateProfileResponse(profile.getId(), profile.getUserId(), profile.getFullName(),
                profile.getAvatarUrl(), profile.getPhoneNumber(), profile.getSkills(), profile.getExperienceSummary(),
                profile.getEducationSummary(), profile.isPublic(), profile.getCreatedAt(), profile.getUpdatedAt());
    }


    @BeforeEach
    void setUp() {
        CandidateProfileController controller = new CandidateProfileController(profileService);
        this.mockMvc = MockMvcBuilders.standaloneSetup(controller).build();


    }

    @Test
    void getProfile_returnsMappedResponse() throws Exception {
        CandidateProfileResponse profile = sampleProfile();
        when(profileService.getOrCreateProfile(anyLong())).thenReturn(profile);

        try (MockedStatic<SecurityUtils> mockedSecurity = org.mockito.Mockito.mockStatic(SecurityUtils.class)) {
            mockedSecurity.when(SecurityUtils::getCurrentUserId).thenReturn(testPrincipal.getId());
            mockMvc.perform(get("/api/v1/candidates/profile"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value("200"))
                    .andExpect(jsonPath("$.data.fullName").value(profile.fullName()))
                    .andExpect(jsonPath("$.data.avatarUrl").value(profile.avatarUrl()))
                    .andExpect(jsonPath("$.data.skills[0]").value("Kotlin"))
                    .andExpect(jsonPath("$.data.isPublic").value(true));
        }
    }

    @Test
    void updateProfile_returnsUpdatedResponse() throws Exception {
        CandidateProfileResponse sample = sampleProfile();
        CandidateProfileResponse updated = new CandidateProfileResponse(sample.id(), sample.userId(), "Jane Doe",
                sample.avatarUrl(), "+84987654321", sample.skills(), sample.experienceSummary(),
                sample.educationSummary(), false, sample.createdAt(), sample.updatedAt());

        UpdateCandidateProfileRequest request = new UpdateCandidateProfileRequest(
                "Jane Doe",
                "0987654321",
                "https://example.com/new-avatar.png",
                List.of("Java", "Spring"),
                "6 years",
                "MSc Computer Science",
                false
        );

        when(profileService.updateProfile(anyLong(), any(UpdateCandidateProfileRequest.class)))
                .thenReturn(updated);

        try (MockedStatic<SecurityUtils> mockedSecurity = org.mockito.Mockito.mockStatic(SecurityUtils.class)) {
            mockedSecurity.when(SecurityUtils::getCurrentUserId).thenReturn(testPrincipal.getId());
            mockMvc.perform(put("/api/v1/candidates/profile")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value("200"))
                    .andExpect(jsonPath("$.data.fullName").value("Jane Doe"))
                    .andExpect(jsonPath("$.data.phoneNumber").value("+84987654321"))
                    .andExpect(jsonPath("$.data.isPublic").value(false));
        }
    }
}
