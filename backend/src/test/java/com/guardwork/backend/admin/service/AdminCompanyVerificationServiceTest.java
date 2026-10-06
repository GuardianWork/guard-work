package com.guardwork.backend.admin.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.admin.dto.CompanyVerificationResponse;
import com.guardwork.backend.admin.dto.VerificationDecisionRequest;
import com.guardwork.backend.audit.service.AuditService;
import com.guardwork.backend.common.PageResponse;
import com.guardwork.backend.company.model.Company;
import com.guardwork.backend.company.model.VerificationStatus;
import com.guardwork.backend.company.repository.CompanyRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminCompanyVerificationServiceTest {

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private AuditService auditService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AdminCompanyVerificationService service;

    @BeforeEach
    void setUp() {
        service = new AdminCompanyVerificationService(companyRepository, auditService, objectMapper);
    }

    private Company createSampleCompany(Long id, VerificationStatus status, Long version) {
        Company company = new Company();
        company.setId(id);
        company.setName("Công ty TNHH Giải Pháp Alpha");
        company.setTaxCode("0101234567");
        company.setRegistrationCertificateUrl("https://storage.guardwork.vn/licenses/alpha.pdf");
        company.setVerificationStatus(status);
        company.setVersion(version);
        company.setCreatedAt(Instant.now());
        company.setUpdatedAt(Instant.now());
        return company;
    }

    @Test
    void getVerificationQueue_DefaultPending_ReturnsPaginatedList() {
        Company c1 = createSampleCompany(1L, VerificationStatus.PENDING, 0L);
        when(companyRepository.findByStatus(VerificationStatus.PENDING, 0, 20)).thenReturn(List.of(c1));
        when(companyRepository.countByStatus(VerificationStatus.PENDING)).thenReturn(1L);

        PageResponse<CompanyVerificationResponse> response = service.getVerificationQueue("PENDING", 0, 20);

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().getFirst().name()).isEqualTo("Công ty TNHH Giải Pháp Alpha");
        assertThat(response.totalElements()).isEqualTo(1L);
        assertThat(response.pageNumber()).isZero();
    }

    @Test
    void getVerificationQueue_InvalidStatus_ThrowsBadRequest() {
        assertThatThrownBy(() -> service.getVerificationQueue("UNKNOWN_STATUS", 0, 20))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void verifyCompany_ApprovePendingCompany_Success() {
        Company pending = createSampleCompany(10L, VerificationStatus.PENDING, 0L);
        Company verified = createSampleCompany(10L, VerificationStatus.VERIFIED, 1L);
        verified.setVerifiedBy(99L);
        verified.setVerifiedAt(Instant.now());

        when(companyRepository.findById(10L))
                .thenReturn(Optional.of(pending))
                .thenReturn(Optional.of(verified));
        when(companyRepository.updateVerification(eq(10L), eq(VerificationStatus.VERIFIED), any(), eq(99L), any(), eq(0L)))
                .thenReturn(1);

        VerificationDecisionRequest request = new VerificationDecisionRequest(VerificationStatus.VERIFIED, null, 0L);
        CompanyVerificationResponse result = service.verifyCompany(10L, request, 99L, "127.0.0.1", "JUnit");

        assertThat(result.verificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(result.version()).isEqualTo(1L);

        verify(auditService).recordLog(
                eq(99L),
                eq("COMPANY_VERIFICATION"),
                eq("COMPANY"),
                eq("10"),
                anyString(),
                anyString(),
                eq("Approved by admin 99"),
                eq("127.0.0.1"),
                eq("JUnit")
        );
    }

    @Test
    void verifyCompany_RejectPendingCompany_WithValidReason_Success() {
        Company pending = createSampleCompany(10L, VerificationStatus.PENDING, 0L);
        Company rejected = createSampleCompany(10L, VerificationStatus.REJECTED, 1L);
        String reason = "Giấy phép kinh doanh scan bị mờ và thiếu con dấu đỏ.";
        rejected.setRejectionReason(reason);
        rejected.setVerifiedBy(99L);

        when(companyRepository.findById(10L))
                .thenReturn(Optional.of(pending))
                .thenReturn(Optional.of(rejected));
        when(companyRepository.updateVerification(eq(10L), eq(VerificationStatus.REJECTED), eq(reason), eq(99L), any(), eq(0L)))
                .thenReturn(1);

        VerificationDecisionRequest request = new VerificationDecisionRequest(VerificationStatus.REJECTED, reason, 0L);
        CompanyVerificationResponse result = service.verifyCompany(10L, request, 99L, "127.0.0.1", "JUnit");

        assertThat(result.verificationStatus()).isEqualTo(VerificationStatus.REJECTED);
        assertThat(result.rejectionReason()).isEqualTo(reason);
        assertThat(result.version()).isEqualTo(1L);

        verify(auditService).recordLog(
                eq(99L),
                eq("COMPANY_VERIFICATION"),
                eq("COMPANY"),
                eq("10"),
                anyString(),
                anyString(),
                eq(reason),
                eq("127.0.0.1"),
                eq("JUnit")
        );
    }

    @Test
    void verifyCompany_RejectPendingCompany_TooShortReason_ThrowsBadRequest() {
        Company pending = createSampleCompany(10L, VerificationStatus.PENDING, 0L);
        when(companyRepository.findById(10L)).thenReturn(Optional.of(pending));

        VerificationDecisionRequest request = new VerificationDecisionRequest(VerificationStatus.REJECTED, "Too short", 0L);

        assertThatThrownBy(() -> service.verifyCompany(10L, request, 99L, "127.0.0.1", "JUnit"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void verifyCompany_RejectPendingCompany_NullReason_ThrowsBadRequest() {
        Company pending = createSampleCompany(10L, VerificationStatus.PENDING, 0L);
        when(companyRepository.findById(10L)).thenReturn(Optional.of(pending));

        VerificationDecisionRequest request = new VerificationDecisionRequest(VerificationStatus.REJECTED, null, 0L);

        assertThatThrownBy(() -> service.verifyCompany(10L, request, 99L, "127.0.0.1", "JUnit"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void verifyCompany_CompanyNotPending_ThrowsBadRequest() {
        Company verifiedCompany = createSampleCompany(10L, VerificationStatus.VERIFIED, 1L);
        when(companyRepository.findById(10L)).thenReturn(Optional.of(verifiedCompany));

        VerificationDecisionRequest request = new VerificationDecisionRequest(VerificationStatus.REJECTED, "Lý do hợp lệ dài hơn mười ký tự", 1L);

        assertThatThrownBy(() -> service.verifyCompany(10L, request, 99L, "127.0.0.1", "JUnit"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void verifyCompany_OptimisticLockMismatch_ThrowsConflict() {
        Company pending = createSampleCompany(10L, VerificationStatus.PENDING, 1L);
        when(companyRepository.findById(10L)).thenReturn(Optional.of(pending));

        // Admin passes expectedVersion = 0, but database is at version 1
        VerificationDecisionRequest request = new VerificationDecisionRequest(VerificationStatus.VERIFIED, null, 0L);

        assertThatThrownBy(() -> service.verifyCompany(10L, request, 99L, "127.0.0.1", "JUnit"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void verifyCompany_CompanyNotFound_ThrowsNotFound() {
        when(companyRepository.findById(999L)).thenReturn(Optional.empty());

        VerificationDecisionRequest request = new VerificationDecisionRequest(VerificationStatus.VERIFIED, null, 0L);

        assertThatThrownBy(() -> service.verifyCompany(999L, request, 99L, "127.0.0.1", "JUnit"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
