package com.guardwork.backend.candidate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Web‑slice tests for {@link CandidateProfileController}.
 * They verify the HTTP contract, request/response mapping and that the controller
 * correctly delegates to {@link CandidateProfileService}.
 */
@WebMvcTest(CandidateProfileController.class)
class CandidateProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CandidateProfileService profileService;

    @Test
    @DisplayName("GET /api/v1/candidates/profile – returns existing profile")
    void getProfile_success() throws Exception {
        CandidateProfile dummy = new CandidateProfile();
        dummy.setId(java.util.UUID.randomUUID());
        dummy.setUserId(1L);
        dummy.setFullName("John Doe");
        dummy.setPublic(true);
        dummy.setSkills(java.util.List.of());

        Mockito.when(profileService.getOrCreateProfile(1L)).thenReturn(dummy);

        mockMvc.perform(get("/api/v1/candidates/profile")
                .param("userId", "1"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.fullName").value("John Doe"));
    }

    @Test
    @DisplayName("PATCH /api/v1/candidates/profile – updates profile fields")
    void patchProfile_success() throws Exception {
        UpdateCandidateProfileRequest request = new UpdateCandidateProfileRequest(
                "Jane Doe",
                "0123456789",
                "https://example.com/avatar.png",
                java.util.List.of("Kotlin", "Docker"),
                "5 years",
                "BS Computer Science",
                true);

        CandidateProfile updated = new CandidateProfile();
        updated.setId(java.util.UUID.randomUUID());
        updated.setUserId(1L);
        updated.setFullName("Jane Doe");
        updated.setPhoneNumber("0123456789");
        updated.setAvatarUrl("https://example.com/avatar.png");
        updated.setSkills(java.util.List.of("Kotlin", "Docker"));
        updated.setExperienceSummary("5 years");
        updated.setEducationSummary("BS Computer Science");
        updated.setPublic(true);

        Mockito.when(profileService.updateProfile(eq(1L), any(UpdateCandidateProfileRequest.class)))
                .thenReturn(updated);

        mockMvc.perform(patch("/api/v1/candidates/profile")
                .param("userId", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Jane Doe"))
                .andExpect(jsonPath("$.phoneNumber").value("0123456789"))
                .andExpect(jsonPath("$.skills[0]").value("Kotlin"))
                .andExpect(jsonPath("$.skills[1]").value("Docker"));
    }
}
