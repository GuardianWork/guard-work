package com.guardwork.backend.candidate.cvs;

import com.guardwork.backend.candidate.CandidateProfile;
import com.guardwork.backend.candidate.CandidateProfileRepository;
import com.guardwork.backend.candidate.CandidateProfileService;
import com.guardwork.backend.candidate.cvs.dto.CandidateCvResponse;
import com.guardwork.backend.candidate.cvs.dto.CreateCandidateCvRequest;
import com.guardwork.backend.candidate.cvs.dto.UploadUrlRequest;
import com.guardwork.backend.candidate.cvs.dto.UploadUrlResponse;
import com.guardwork.backend.common.exception.AppException;
import com.guardwork.backend.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CandidateCvServiceTest {

    private static final Long USER_ID = 7L;
    private static final String PDF = "application/pdf";
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Mock CandidateCvRepository cvRepository;
    @Mock CandidateProfileRepository profileRepository;
    @Mock CandidateProfileService profileService;
    @Mock CandidateCvStorage storage;

    @InjectMocks CandidateCvService service;

    private CandidateProfile profile;
    private UUID candidateId;

    @BeforeEach
    void setUp() {
        candidateId = UUID.randomUUID();
        profile = new CandidateProfile();
        profile.setId(candidateId);
        profile.setUserId(USER_ID);
        profile.setFullName("Nguyễn Văn An");
        lenient().when(profileService.getOrCreateProfile(USER_ID)).thenReturn(profile);
    }

    private String folder() {
        return "candidates/" + candidateId + "/cvs/";
    }

    private CandidateCvStorage.StoredCvAsset asset(String publicId, String format, String contentType,
            long bytes, String resourceType) {
        return new CandidateCvStorage.StoredCvAsset(publicId, format, contentType, bytes, resourceType, "cv");
    }

    private CreateCandidateCvRequest request(String fileName, String publicId) {
        return new CreateCandidateCvRequest(null, fileName, 1_000L, PDF, publicId);
    }

    @Nested
    @DisplayName("generateUploadUrl")
    class GenerateUploadUrl {

        @Test
        void requiresPrivacyConsent() {
            AppException failure = assertThrows(AppException.class,
                    () -> service.generateUploadUrl(USER_ID, new UploadUrlRequest(false, null, null)));

            assertEquals(ErrorCode.CV_CONSENT_REQUIRED, failure.getErrorCode());
            verifyNoInteractions(storage);
        }

        @Test
        void enforcesQuotaBeforeIssuingSignature() {
            when(cvRepository.countByCandidateId(candidateId)).thenReturn(5L);

            AppException failure = assertThrows(AppException.class,
                    () -> service.generateUploadUrl(USER_ID, new UploadUrlRequest(true, null, null)));

            assertEquals(ErrorCode.CV_QUOTA_EXCEEDED, failure.getErrorCode());
            verifyNoInteractions(storage);
        }

        @Test
        void derivesFolderFromAuthenticatedCandidateAndIgnoresClientValues() {
            when(cvRepository.countByCandidateId(candidateId)).thenReturn(0L);
            when(storage.createUploadSignature(anyString()))
                    .thenAnswer(invocation -> new CandidateCvStorage.UploadSignature(
                            "https://api.cloudinary.com/v1_1/demo/auto/upload", "key", "123",
                            "signature", invocation.getArgument(0)));

            UploadUrlResponse response = service.generateUploadUrl(
                    USER_ID, new UploadUrlRequest(true, "another/candidate", "client-chosen"));

            ArgumentCaptor<String> publicId = ArgumentCaptor.forClass(String.class);
            verify(storage).createUploadSignature(publicId.capture());
            assertTrue(publicId.getValue().startsWith(folder()));
            assertFalse(publicId.getValue().contains("another/candidate"));
            assertFalse(publicId.getValue().contains("client-chosen"));
            assertEquals(publicId.getValue(), response.publicId());
            assertEquals("https://api.cloudinary.com/v1_1/demo/auto/upload", response.url());
        }
    }

    @Nested
    @DisplayName("registerCv")
    class RegisterCv {

        @Test
        void rejectsAnotherCandidateId() {
            CreateCandidateCvRequest request = new CreateCandidateCvRequest(
                    UUID.randomUUID(), "cv.pdf", 1_000L, PDF, folder() + UUID.randomUUID());

            AppException failure = assertThrows(AppException.class, () -> service.registerCv(USER_ID, request));

            assertEquals(ErrorCode.FORBIDDEN, failure.getErrorCode());
            verifyNoInteractions(storage, cvRepository);
        }

        @Test
        void rejectsPublicIdOutsideCandidateFolder() {
            CreateCandidateCvRequest request = request("cv.pdf", "candidates/other/cvs/file.pdf");

            AppException failure = assertThrows(AppException.class, () -> service.registerCv(USER_ID, request));

            assertEquals(ErrorCode.CV_ASSET_NOT_VERIFIED, failure.getErrorCode());
            verifyNoInteractions(storage, cvRepository);
        }

        @Test
        void rejectsMissingCloudinaryAsset() {
            String publicId = folder() + UUID.randomUUID();
            when(storage.find(publicId)).thenReturn(Optional.empty());

            AppException failure = assertThrows(AppException.class,
                    () -> service.registerCv(USER_ID, request("cv.pdf", publicId)));

            assertEquals(ErrorCode.CV_ASSET_NOT_VERIFIED, failure.getErrorCode());
        }

        @Test
        void rejectsInvalidFormatAndCleansUp() {
            String publicId = folder() + UUID.randomUUID();
            CandidateCvStorage.StoredCvAsset asset = asset(publicId, "exe", null, 1_000L, "raw");
            when(storage.find(publicId)).thenReturn(Optional.of(asset));

            AppException failure = assertThrows(AppException.class,
                    () -> service.registerCv(USER_ID, request("cv.pdf", publicId)));

            assertEquals(ErrorCode.INVALID_CV_FILE_FORMAT, failure.getErrorCode());
            verify(storage).delete(publicId, "raw");
            verifyNoInteractions(cvRepository);
        }

        @Test
        void rejectsOversizedAssetAndCleansUp() {
            String publicId = folder() + UUID.randomUUID();
            CandidateCvStorage.StoredCvAsset asset = asset(publicId, "pdf", PDF, 10 * 1024 * 1024 + 1, "image");
            when(storage.find(publicId)).thenReturn(Optional.of(asset));

            AppException failure = assertThrows(AppException.class,
                    () -> service.registerCv(USER_ID, request("cv.pdf", publicId)));

            assertEquals(ErrorCode.INVALID_CV_FILE_FORMAT, failure.getErrorCode());
            verify(storage).delete(publicId, "image");
        }

        @Test
        void rejectsFileNameThatDoesNotMatchActualFormat() {
            String publicId = folder() + UUID.randomUUID();
            CandidateCvStorage.StoredCvAsset asset = asset(publicId, "docx", DOCX, 1_000L, "raw");
            when(storage.find(publicId)).thenReturn(Optional.of(asset));

            AppException failure = assertThrows(AppException.class,
                    () -> service.registerCv(USER_ID, request("cv.exe", publicId)));

            assertEquals(ErrorCode.INVALID_CV_FILE_FORMAT, failure.getErrorCode());
            verify(storage).delete(publicId, "raw");
        }

        @Test
        void firstCvIsDefaultAndUsesVerifiedMetadata() {
            String publicId = folder() + UUID.randomUUID();
            CandidateCvStorage.StoredCvAsset asset = asset(publicId, "pdf", PDF, 2_048L, "image");
            when(storage.find(publicId)).thenReturn(Optional.of(asset));
            when(profileRepository.findByIdForUpdate(candidateId)).thenReturn(Optional.of(profile));
            when(cvRepository.existsByPublicId(publicId)).thenReturn(false);
            when(cvRepository.countByCandidateId(candidateId)).thenReturn(0L);
            when(cvRepository.save(any(CandidateCv.class))).thenAnswer(invocation -> {
                CandidateCv cv = invocation.getArgument(0);
                cv.setId(UUID.randomUUID());
                return cv;
            });

            CandidateCvResponse response = service.registerCv(USER_ID, request("My CV.pdf", publicId));

            assertEquals(candidateId, response.candidateId());
            assertEquals("My CV.pdf", response.fileName());
            assertEquals(2_048L, response.fileSize());
            assertEquals(PDF, response.contentType());
            assertEquals(publicId, response.publicId());
            assertTrue(response.isDefault());
            verify(storage, never()).delete(anyString(), anyString());
        }

        @Test
        void laterCvIsNotDefault() {
            String publicId = folder() + UUID.randomUUID();
            CandidateCvStorage.StoredCvAsset asset = asset(publicId, "docx", DOCX, 1_000L, "raw");
            when(storage.find(publicId)).thenReturn(Optional.of(asset));
            when(profileRepository.findByIdForUpdate(candidateId)).thenReturn(Optional.of(profile));
            when(cvRepository.existsByPublicId(publicId)).thenReturn(false);
            when(cvRepository.countByCandidateId(candidateId)).thenReturn(1L);
            when(cvRepository.save(any(CandidateCv.class))).thenAnswer(invocation -> invocation.getArgument(0));

            var response = service.registerCv(USER_ID, request("cv.docx", publicId));

            assertFalse(response.isDefault());
        }

        @Test
        void duplicateRegistrationDoesNotDeleteTheExistingAsset() {
            String publicId = folder() + UUID.randomUUID();
            CandidateCvStorage.StoredCvAsset asset = asset(publicId, "pdf", PDF, 1_000L, "image");
            when(storage.find(publicId)).thenReturn(Optional.of(asset));
            when(profileRepository.findByIdForUpdate(candidateId)).thenReturn(Optional.of(profile));
            when(cvRepository.existsByPublicId(publicId)).thenReturn(true);

            AppException failure = assertThrows(AppException.class,
                    () -> service.registerCv(USER_ID, request("cv.pdf", publicId)));

            assertEquals(ErrorCode.DUPLICATE_CV, failure.getErrorCode());
            verify(storage, never()).delete(anyString(), anyString());
            verify(cvRepository, never()).save(any());
        }

        @Test
        void quotaRaceIsRejectedAndUploadIsCleanedUp() {
            String publicId = folder() + UUID.randomUUID();
            CandidateCvStorage.StoredCvAsset asset = asset(publicId, "pdf", PDF, 1_000L, "image");
            when(storage.find(publicId)).thenReturn(Optional.of(asset));
            when(profileRepository.findByIdForUpdate(candidateId)).thenReturn(Optional.of(profile));
            when(cvRepository.existsByPublicId(publicId)).thenReturn(false);
            when(cvRepository.countByCandidateId(candidateId)).thenReturn(5L);

            AppException failure = assertThrows(AppException.class,
                    () -> service.registerCv(USER_ID, request("cv.pdf", publicId)));

            assertEquals(ErrorCode.CV_QUOTA_EXCEEDED, failure.getErrorCode());
            verify(storage).delete(publicId, "image");
            verify(cvRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("listCvs")
    class ListCvs {

        @Test
        void rejectsAnotherCandidatesList() {
            AppException failure = assertThrows(AppException.class,
                    () -> service.listCvs(USER_ID, UUID.randomUUID()));

            assertEquals(ErrorCode.FORBIDDEN, failure.getErrorCode());
            verifyNoInteractions(cvRepository);
        }

        @Test
        void listsOnlyAuthenticatedCandidatesCvs() {
            CandidateCv cv = new CandidateCv();
            cv.setId(UUID.randomUUID());
            cv.setCandidateId(candidateId);
            cv.setFileName("cv.pdf");
            cv.setFileSize(1L);
            cv.setContentType(PDF);
            cv.setPublicId(folder() + "cv");
            when(cvRepository.findByCandidateIdOrderByCreatedAtDesc(candidateId)).thenReturn(List.of(cv));

            var cvs = service.listCvs(USER_ID, candidateId);

            assertEquals(1, cvs.size());
            verify(cvRepository).findByCandidateIdOrderByCreatedAtDesc(candidateId);
        }
    }

    @Nested
    @DisplayName("generateDownloadUrl")
    class GenerateDownloadUrl {

        @Test
        void returnsUrlForOwnedCvOnly() {
            UUID cvId = UUID.randomUUID();
            CandidateCv cv = new CandidateCv();
            cv.setId(cvId);
            cv.setCandidateId(candidateId);
            cv.setContentType(DOCX);
            cv.setPublicId(folder() + "cv.docx");
            when(cvRepository.findByIdAndCandidateId(cvId, candidateId)).thenReturn(Optional.of(cv));
            when(storage.createDownloadUrl(eq(cv.getPublicId()), eq("raw"), eq("docx"), any(Duration.class)))
                    .thenReturn("https://download.example/signed");

            UploadUrlResponse response = service.generateDownloadUrl(USER_ID, cvId);

            assertEquals("https://download.example/signed", response.url());
            assertEquals(cv.getPublicId(), response.publicId());
        }

        @Test
        void hidesCvsOwnedByAnotherCandidate() {
            UUID cvId = UUID.randomUUID();
            when(cvRepository.findByIdAndCandidateId(cvId, candidateId)).thenReturn(Optional.empty());

            AppException failure = assertThrows(AppException.class,
                    () -> service.generateDownloadUrl(USER_ID, cvId));

            assertEquals(ErrorCode.CV_NOT_FOUND, failure.getErrorCode());
            verifyNoInteractions(storage);
        }
    }
}
