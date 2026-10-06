package com.guardwork.backend.company.controller;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.company.dto.CompanyRegistrationRequest;
import com.guardwork.backend.company.dto.CompanyResubmissionRequest;
import com.guardwork.backend.company.model.Company;
import com.guardwork.backend.company.model.VerificationStatus;
import com.guardwork.backend.company.service.CompanyService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CompanyControllerTest {

    @Mock
    private CompanyService companyService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        CompanyController controller = new CompanyController(companyService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void requestVerification_ValidPayload_Returns201() throws Exception {
        CompanyRegistrationRequest request = new CompanyRegistrationRequest(
                "Công ty Gamma", "0303030303", "https://storage.guardwork.vn/gamma.pdf"
        );
        Company company = new Company();
        company.setId(1L);
        company.setName(request.name());
        company.setTaxCode(request.taxCode());
        company.setRegistrationCertificateUrl(request.registrationCertificateUrl());
        company.setVerificationStatus(VerificationStatus.PENDING);
        company.setVersion(0L);
        company.setCreatedAt(Instant.now());

        when(companyService.registerCompany(any(CompanyRegistrationRequest.class))).thenReturn(company);

        mockMvc.perform(post("/api/companies/verification-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.name").value("Công ty Gamma"))
                .andExpect(jsonPath("$.data.verificationStatus").value("PENDING"));
    }

    @Test
    void resubmitVerification_ValidPayload_Returns200() throws Exception {
        CompanyResubmissionRequest request = new CompanyResubmissionRequest(
                "Công ty Gamma Updated", "https://new-gamma.pdf", 1L
        );
        Company updated = new Company();
        updated.setId(1L);
        updated.setName(request.name());
        updated.setTaxCode("0303030303");
        updated.setRegistrationCertificateUrl(request.registrationCertificateUrl());
        updated.setVerificationStatus(VerificationStatus.PENDING);
        updated.setVersion(2L);
        updated.setCreatedAt(Instant.now());

        when(companyService.resubmitCompany(eq(1L), any(CompanyResubmissionRequest.class))).thenReturn(updated);

        mockMvc.perform(put("/api/companies/1/resubmit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.name").value("Công ty Gamma Updated"))
                .andExpect(jsonPath("$.data.version").value(2));
    }

    @Test
    void getCompany_ExistingId_Returns200() throws Exception {
        Company company = new Company();
        company.setId(1L);
        company.setName("Công ty Gamma");
        company.setTaxCode("0303030303");
        company.setRegistrationCertificateUrl("https://gamma.pdf");
        company.setVerificationStatus(VerificationStatus.PENDING);
        company.setVersion(0L);
        company.setCreatedAt(Instant.now());

        when(companyService.getCompany(1L)).thenReturn(company);

        mockMvc.perform(get("/api/companies/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.taxCode").value("0303030303"));
    }
}
