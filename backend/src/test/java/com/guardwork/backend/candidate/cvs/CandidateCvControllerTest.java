package com.guardwork.backend.candidate.cvs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.candidate.cvs.dto.CandidateCvResponse;
import com.guardwork.backend.candidate.cvs.dto.CreateCandidateCvRequest;
import com.guardwork.backend.candidate.cvs.dto.UploadUrlResponse;
import com.guardwork.backend.common.utils.SecurityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class CandidateCvControllerTest {

    private static final Long USER_ID = 42L;

    @Mock CandidateCvService cvService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new CandidateCvController(cvService)).build();
    }

    @Test
    void uploadUrlRejectsMissingConsent() throws Exception {
        mockMvc.perform(post("/api/v1/candidate/cvs/upload-url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"consentGiven\":false}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(cvService);
    }

    @Test
    void uploadUrlUsesAuthenticatedCandidate() throws Exception {
        when(cvService.generateUploadUrl(eq(USER_ID), any()))
                .thenReturn(new UploadUrlResponse("https://upload", "key", "123", "signature", "candidates/x/cvs/y"));

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
            mockMvc.perform(post("/api/v1/candidate/cvs/upload-url")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"consentGiven\":true,\"folder\":\"client\",\"publicId\":\"client\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.publicId").value("candidates/x/cvs/y"))
                    .andExpect(jsonPath("$.url").value("https://upload"));
        }
    }

    @Test
    void registerUsesAuthenticatedCandidate() throws Exception {
        when(cvService.registerCv(eq(USER_ID), any(CreateCandidateCvRequest.class)))
                .thenReturn(new CandidateCvResponse(
                        UUID.randomUUID(), UUID.randomUUID(), "cv.pdf", 100L,
                        "application/pdf", "candidates/x/cvs/cv", true,
                        Instant.now(), Instant.now()));

        CreateCandidateCvRequest request = new CreateCandidateCvRequest(
                null, "cv.pdf", 100L, "application/pdf", "candidates/x/cvs/cv");

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
            mockMvc.perform(post("/api/v1/candidate/cvs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.fileName").value("cv.pdf"))
                    .andExpect(jsonPath("$.isDefault").value(true));
        }
    }

    @Test
    void registerRejectsMissingPublicId() throws Exception {
        CreateCandidateCvRequest request = new CreateCandidateCvRequest(null, "cv.pdf", 100L, "application/pdf", " ");

        mockMvc.perform(post("/api/v1/candidate/cvs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(cvService);
    }

    @Test
    void listKeepsOptionalCandidateIdButUsesAuthenticatedUser() throws Exception {
        UUID candidateId = UUID.randomUUID();
        when(cvService.listCvs(USER_ID, candidateId)).thenReturn(List.of());

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
            mockMvc.perform(get("/api/v1/candidate/cvs").param("candidateId", candidateId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(content().json("[]"));
        }
    }

    @Test
    void downloadUsesAuthenticatedUser() throws Exception {
        UUID cvId = UUID.randomUUID();
        when(cvService.generateDownloadUrl(USER_ID, cvId))
                .thenReturn(new UploadUrlResponse("https://download", null, null, null, "public"));

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
            mockMvc.perform(get("/api/v1/candidate/cvs/{id}/download-url", cvId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.url").value("https://download"));
        }
    }
}
