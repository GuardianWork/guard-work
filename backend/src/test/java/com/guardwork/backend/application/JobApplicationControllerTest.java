package com.guardwork.backend.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.application.controller.JobApplicationController;
import com.guardwork.backend.application.dto.ApplicationCvOption;
import com.guardwork.backend.application.dto.ApplicationFormResponse;
import com.guardwork.backend.application.dto.JobApplicationResponse;
import com.guardwork.backend.application.dto.JobSummary;
import com.guardwork.backend.application.dto.ProfilePrefill;
import com.guardwork.backend.application.dto.SubmitApplicationRequest;
import com.guardwork.backend.application.service.JobApplicationService;
import com.guardwork.backend.common.exception.AppException;
import com.guardwork.backend.common.exception.ErrorCode;
import com.guardwork.backend.common.exception.GlobalExceptionalHandler;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class JobApplicationControllerTest {

    private static final Long USER_ID = 42L;
    private static final Long JOB_ID = 10L;
    private static final UUID CV_ID = UUID.randomUUID();

    @Mock JobApplicationService applicationService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new JobApplicationController(applicationService))
                .setControllerAdvice(new GlobalExceptionalHandler())
                .build();
    }

    private SubmitApplicationRequest validRequest() {
        return new SubmitApplicationRequest(
                JOB_ID, "Nguyễn Văn An", "an@example.com", "0901234567", "Em quan tâm", CV_ID);
    }

    @Test
    void formUsesAuthenticatedCandidate() throws Exception {
        when(applicationService.form(USER_ID, JOB_ID)).thenReturn(new ApplicationFormResponse(
                new JobSummary(JOB_ID, "Backend Engineer", 20L, "Acme",
                        Instant.parse("2026-11-30T17:00:00Z")),
                new ProfilePrefill("Nguyễn Văn An", "an@example.com", "0901234567"),
                List.of(new ApplicationCvOption(CV_ID, "CV.pdf", "application/pdf", 2048L, true)),
                CV_ID,
                List.of(),
                false
        ));

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
            mockMvc.perform(get("/api/v1/candidate/applications/form").param("jobId", JOB_ID.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.job.title").value("Backend Engineer"))
                    .andExpect(jsonPath("$.data.defaultCvId").value(CV_ID.toString()))
                    .andExpect(jsonPath("$.data.alreadyApplied").value(false));
        }
    }

    @Test
    void submitReturnsCreated() throws Exception {
        when(applicationService.submit(eq(USER_ID), any(SubmitApplicationRequest.class)))
                .thenReturn(new JobApplicationResponse(
                        UUID.randomUUID(), JOB_ID, "Backend Engineer", 20L, "Acme",
                        "SUBMITTED", Instant.parse("2026-10-30T09:00:00Z"), "CV.pdf"));

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
            mockMvc.perform(post("/api/v1/candidate/applications")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value(201))
                    .andExpect(jsonPath("$.data.status").value("SUBMITTED"));
        }
    }

    @Test
    void submitRejectsInvalidPhone() throws Exception {
        SubmitApplicationRequest request = new SubmitApplicationRequest(
                JOB_ID, "Nguyễn Văn An", "an@example.com", "12345", "Em quan tâm", CV_ID);

        mockMvc.perform(post("/api/v1/candidate/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(applicationService);
    }

    @Test
    void submitRejectsMissingCv() throws Exception {
        SubmitApplicationRequest request = new SubmitApplicationRequest(
                JOB_ID, "Nguyễn Văn An", "an@example.com", "0901234567", null, null);

        mockMvc.perform(post("/api/v1/candidate/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(applicationService);
    }

    @Test
    void duplicateApplicationReturnsConflict() throws Exception {
        when(applicationService.submit(eq(USER_ID), any(SubmitApplicationRequest.class)))
                .thenThrow(new AppException(ErrorCode.APPLICATION_ALREADY_SUBMITTED));

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
            mockMvc.perform(post("/api/v1/candidate/applications")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(40903));
        }
    }
}
