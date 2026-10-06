package com.guardwork.backend.admin.controller;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.admin.dto.CompanyVerificationResponse;
import com.guardwork.backend.admin.dto.VerificationDecisionRequest;
import com.guardwork.backend.admin.service.AdminCompanyVerificationService;
import com.guardwork.backend.common.PageResponse;
import com.guardwork.backend.company.model.VerificationStatus;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminCompanyVerificationControllerTest {

    @Mock
    private AdminCompanyVerificationService verificationService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        AdminCompanyVerificationController controller = new AdminCompanyVerificationController(verificationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void getVerificationQueue_MissingAdminRoleHeader_Returns401() throws Exception {
        mockMvc.perform(get("/api/admin/companies/verifications"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getVerificationQueue_NonAdminRole_Returns403() throws Exception {
        mockMvc.perform(get("/api/admin/companies/verifications")
                        .header("X-Admin-Role", "RECRUITER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getVerificationQueue_ValidAdminRole_Returns200WithQueue() throws Exception {
        CompanyVerificationResponse item = new CompanyVerificationResponse(
                1L, "Alpha Corp", "0101234567", "https://cert.pdf",
                VerificationStatus.PENDING, null, null, null, 0L, Instant.now()
        );
        PageResponse<CompanyVerificationResponse> page = PageResponse.of(List.of(item), 0, 20, 1L);

        when(verificationService.getVerificationQueue("PENDING", 0, 20)).thenReturn(page);

        mockMvc.perform(get("/api/admin/companies/verifications")
                        .header("X-Admin-Role", "ADMIN")
                        .param("status", "PENDING")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("Verification queue retrieved successfully"))
                .andExpect(jsonPath("$.data.content[0].id").value(1))
                .andExpect(jsonPath("$.data.content[0].name").value("Alpha Corp"));
    }

    @Test
    void verifyCompany_MissingAdminId_Returns401() throws Exception {
        VerificationDecisionRequest request = new VerificationDecisionRequest(VerificationStatus.VERIFIED, null, 0L);

        mockMvc.perform(put("/api/admin/companies/1/verify")
                        .header("X-Admin-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void verifyCompany_NonAdminRole_Returns403() throws Exception {
        VerificationDecisionRequest request = new VerificationDecisionRequest(VerificationStatus.VERIFIED, null, 0L);

        mockMvc.perform(put("/api/admin/companies/1/verify")
                        .header("X-Admin-Id", "100")
                        .header("X-Admin-Role", "CANDIDATE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void verifyCompany_ValidAdmin_Returns200WithUpdatedCompany() throws Exception {
        VerificationDecisionRequest request = new VerificationDecisionRequest(VerificationStatus.VERIFIED, null, 0L);
        CompanyVerificationResponse response = new CompanyVerificationResponse(
                1L, "Alpha Corp", "0101234567", "https://cert.pdf",
                VerificationStatus.VERIFIED, null, 100L, Instant.now(), 1L, Instant.now()
        );

        when(verificationService.verifyCompany(eq(1L), any(VerificationDecisionRequest.class), eq(100L), anyString(), any()))
                .thenReturn(response);

        mockMvc.perform(put("/api/admin/companies/1/verify")
                        .header("X-Admin-Id", "100")
                        .header("X-Admin-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("Company verification status updated successfully"))
                .andExpect(jsonPath("$.data.verificationStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.data.version").value(1));
    }

    @Test
    void rejectCompany_MissingAdminId_Returns401() throws Exception {
        VerificationDecisionRequest request = new VerificationDecisionRequest("Invalid registration documents provided.", 0L);

        mockMvc.perform(put("/api/admin/companies/1/reject")
                        .header("X-Admin-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectCompany_NonAdminRole_Returns403() throws Exception {
        VerificationDecisionRequest request = new VerificationDecisionRequest("Invalid registration documents provided.", 0L);

        mockMvc.perform(put("/api/admin/companies/1/reject")
                        .header("X-Admin-Id", "100")
                        .header("X-Admin-Role", "RECRUITER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectCompany_ValidAdmin_Returns200WithRejectedCompany() throws Exception {
        String reason = "Giấy phép kinh doanh đã hết hạn hoặc không hợp lệ.";
        VerificationDecisionRequest request = new VerificationDecisionRequest(reason, 0L);
        CompanyVerificationResponse response = new CompanyVerificationResponse(
                1L, "Alpha Corp", "0101234567", "https://cert.pdf",
                VerificationStatus.REJECTED, reason, 100L, Instant.now(), 1L, Instant.now()
        );

        when(verificationService.rejectCompany(eq(1L), any(VerificationDecisionRequest.class), eq(100L), anyString(), any()))
                .thenReturn(response);

        mockMvc.perform(put("/api/admin/companies/1/reject")
                        .header("X-Admin-Id", "100")
                        .header("X-Admin-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("Company verification rejected successfully"))
                .andExpect(jsonPath("$.data.verificationStatus").value("REJECTED"))
                .andExpect(jsonPath("$.data.rejectionReason").value(reason))
                .andExpect(jsonPath("$.data.version").value(1));
    }

    @Test
    void rejectCompany_ViaPostMethod_Returns405MethodNotAllowed() throws Exception {
        String reason = "Giấy phép kinh doanh đã hết hạn hoặc không hợp lệ.";
        VerificationDecisionRequest request = new VerificationDecisionRequest(reason, 0L);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/admin/companies/1/reject")
                        .header("X-Admin-Id", "100")
                        .header("X-Admin-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isMethodNotAllowed());
    }
}
