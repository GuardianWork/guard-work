package com.guardwork.backend.company.service;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.guardwork.backend.company.dto.CompanyRegistrationRequest;
import com.guardwork.backend.company.dto.CompanyResubmissionRequest;
import com.guardwork.backend.company.model.Company;
import com.guardwork.backend.company.model.VerificationStatus;
import com.guardwork.backend.company.repository.CompanyRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanyServiceTest {

    @Mock
    private CompanyRepository companyRepository;

    private CompanyService companyService;

    @BeforeEach
    void setUp() {
        companyService = new CompanyService(companyRepository);
    }

    @Test
    void registerCompany_ValidRequest_Success() {
        CompanyRegistrationRequest request = new CompanyRegistrationRequest(
                "Công ty Beta", "0202020202", "contact@beta.com.vn", "https://storage.guardwork.vn/cert.pdf"
        );
        when(companyRepository.findByTaxCode("0202020202")).thenReturn(Optional.empty());
        when(companyRepository.save(any(Company.class))).thenAnswer(invocation -> {
            Company c = invocation.getArgument(0);
            c.setId(100L);
            return c;
        });

        Company result = companyService.registerCompany(request);

        assertThat(result.getId()).isEqualTo(100L);
        assertThat(result.getName()).isEqualTo("Công ty Beta");
        assertThat(result.getTaxCode()).isEqualTo("0202020202");
        assertThat(result.getEmail()).isEqualTo("contact@beta.com.vn");
        assertThat(result.getVerificationStatus()).isEqualTo(VerificationStatus.PENDING);
        assertThat(result.getVersion()).isZero();
    }

    @Test
    void registerCompany_DuplicateTaxCode_ThrowsConflict() {
        CompanyRegistrationRequest request = new CompanyRegistrationRequest(
                "Công ty Beta", "0202020202", "contact@beta.com.vn", "https://storage.guardwork.vn/cert.pdf"
        );
        Company existing = new Company();
        existing.setId(50L);
        when(companyRepository.findByTaxCode("0202020202")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> companyService.registerCompany(request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void registerCompany_MissingTaxCode_ThrowsBadRequest() {
        CompanyRegistrationRequest request = new CompanyRegistrationRequest(
                "Công ty Beta", "", "contact@beta.com.vn", "https://storage.guardwork.vn/cert.pdf"
        );

        assertThatThrownBy(() -> companyService.registerCompany(request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void registerCompany_MissingEmail_ThrowsBadRequest() {
        CompanyRegistrationRequest request = new CompanyRegistrationRequest(
                "Công ty Beta", "0202020202", "", "https://storage.guardwork.vn/cert.pdf"
        );

        assertThatThrownBy(() -> companyService.registerCompany(request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void registerCompany_InvalidEmailFormat_ThrowsBadRequest() {
        CompanyRegistrationRequest request = new CompanyRegistrationRequest(
                "Công ty Beta", "0202020202", "invalid-email-format", "https://storage.guardwork.vn/cert.pdf"
        );

        assertThatThrownBy(() -> companyService.registerCompany(request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void resubmitCompany_RejectedStatus_Success() {
        Company rejected = new Company();
        rejected.setId(10L);
        rejected.setName("Old Name");
        rejected.setEmail("old@beta.com.vn");
        rejected.setVerificationStatus(VerificationStatus.REJECTED);
        rejected.setRejectionReason("Old reason");
        rejected.setVersion(1L);

        when(companyRepository.findById(10L)).thenReturn(Optional.of(rejected));
        when(companyRepository.save(any(Company.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CompanyResubmissionRequest request = new CompanyResubmissionRequest(
                "Updated Name", "new@beta.com.vn", "https://new-cert.pdf", 1L
        );

        Company resubmitted = companyService.resubmitCompany(10L, request);

        assertThat(resubmitted.getVerificationStatus()).isEqualTo(VerificationStatus.PENDING);
        assertThat(resubmitted.getRejectionReason()).isNull();
        assertThat(resubmitted.getVersion()).isEqualTo(2L);
        assertThat(resubmitted.getName()).isEqualTo("Updated Name");
        assertThat(resubmitted.getEmail()).isEqualTo("new@beta.com.vn");
    }

    @Test
    void resubmitCompany_InvalidEmailFormat_ThrowsBadRequest() {
        Company rejected = new Company();
        rejected.setId(10L);
        rejected.setVerificationStatus(VerificationStatus.REJECTED);
        rejected.setVersion(1L);

        when(companyRepository.findById(10L)).thenReturn(Optional.of(rejected));

        CompanyResubmissionRequest request = new CompanyResubmissionRequest(
                "Updated Name", "not-an-email", "https://new-cert.pdf", 1L
        );

        assertThatThrownBy(() -> companyService.resubmitCompany(10L, request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void resubmitCompany_NotRejectedStatus_ThrowsBadRequest() {
        Company pending = new Company();
        pending.setId(10L);
        pending.setVerificationStatus(VerificationStatus.PENDING);
        pending.setVersion(0L);

        when(companyRepository.findById(10L)).thenReturn(Optional.of(pending));

        CompanyResubmissionRequest request = new CompanyResubmissionRequest(
                "Updated Name", "https://new-cert.pdf", 0L
        );

        assertThatThrownBy(() -> companyService.resubmitCompany(10L, request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void resubmitCompany_VersionMismatch_ThrowsConflict() {
        Company rejected = new Company();
        rejected.setId(10L);
        rejected.setVerificationStatus(VerificationStatus.REJECTED);
        rejected.setVersion(2L);

        when(companyRepository.findById(10L)).thenReturn(Optional.of(rejected));

        CompanyResubmissionRequest request = new CompanyResubmissionRequest(
                "Updated Name", "https://new-cert.pdf", 1L // Outdated version
        );

        assertThatThrownBy(() -> companyService.resubmitCompany(10L, request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void registerCompany_WhenPreviouslyRejected_ThrowsConflictWithResubmissionRequired() {
        CompanyRegistrationRequest request = new CompanyRegistrationRequest(
                "Công ty Beta", "0202020202", "contact@beta.com.vn", "https://storage.guardwork.vn/cert.pdf"
        );
        Company existing = new Company();
        existing.setId(50L);
        existing.setName("Công ty Beta");
        existing.setTaxCode("0202020202");
        existing.setEmail("contact@beta.com.vn");
        existing.setVerificationStatus(VerificationStatus.REJECTED);
        existing.setVersion(1L);

        when(companyRepository.findByTaxCode("0202020202")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> companyService.registerCompany(request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RESUBMISSION_REQUIRED")
                .hasMessageContaining("/api/companies/50/resubmit")
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void registerCompany_WhenAlreadyPending_ThrowsConflict() {
        CompanyRegistrationRequest request = new CompanyRegistrationRequest(
                "Công ty Beta", "0202020202", "contact@beta.com.vn", "https://storage.guardwork.vn/cert.pdf"
        );
        Company existing = new Company();
        existing.setId(50L);
        existing.setName("Công ty Beta");
        existing.setTaxCode("0202020202");
        existing.setVerificationStatus(VerificationStatus.PENDING);

        when(companyRepository.findByTaxCode("0202020202")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> companyService.registerCompany(request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("COMPANY_ALREADY_PENDING")
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void registerCompany_WhenAlreadyVerified_ThrowsConflict() {
        CompanyRegistrationRequest request = new CompanyRegistrationRequest(
                "Công ty Beta", "0202020202", "contact@beta.com.vn", "https://storage.guardwork.vn/cert.pdf"
        );
        Company existing = new Company();
        existing.setId(50L);
        existing.setName("Công ty Beta");
        existing.setTaxCode("0202020202");
        existing.setVerificationStatus(VerificationStatus.VERIFIED);

        when(companyRepository.findByTaxCode("0202020202")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> companyService.registerCompany(request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("COMPANY_ALREADY_VERIFIED")
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void getCompanyByTaxCode_Existing_ReturnsCompany() {
        Company company = new Company();
        company.setId(10L);
        company.setTaxCode("0202020202");
        company.setName("Công ty Beta");

        when(companyRepository.findByTaxCode("0202020202")).thenReturn(Optional.of(company));

        Company result = companyService.getCompanyByTaxCode("0202020202");
        assertThat(result.getId()).isEqualTo(10L);
        assertThat(result.getName()).isEqualTo("Công ty Beta");
    }

    @Test
    void getCompanyByTaxCode_NotFound_ThrowsNotFound() {
        when(companyRepository.findByTaxCode("9999999999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> companyService.getCompanyByTaxCode("9999999999"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
