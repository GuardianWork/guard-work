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
                "Công ty Beta", "0202020202", "https://storage.guardwork.vn/cert.pdf"
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
        assertThat(result.getVerificationStatus()).isEqualTo(VerificationStatus.PENDING);
        assertThat(result.getVersion()).isZero();
    }

    @Test
    void registerCompany_DuplicateTaxCode_ThrowsConflict() {
        CompanyRegistrationRequest request = new CompanyRegistrationRequest(
                "Công ty Beta", "0202020202", "https://storage.guardwork.vn/cert.pdf"
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
                "Công ty Beta", "", "https://storage.guardwork.vn/cert.pdf"
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
        rejected.setVerificationStatus(VerificationStatus.REJECTED);
        rejected.setRejectionReason("Old reason");
        rejected.setVersion(1L);

        when(companyRepository.findById(10L)).thenReturn(Optional.of(rejected));
        when(companyRepository.save(any(Company.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CompanyResubmissionRequest request = new CompanyResubmissionRequest(
                "Updated Name", "https://new-cert.pdf", 1L
        );

        Company resubmitted = companyService.resubmitCompany(10L, request);

        assertThat(resubmitted.getVerificationStatus()).isEqualTo(VerificationStatus.PENDING);
        assertThat(resubmitted.getRejectionReason()).isNull();
        assertThat(resubmitted.getVersion()).isEqualTo(2L);
        assertThat(resubmitted.getName()).isEqualTo("Updated Name");
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
}
