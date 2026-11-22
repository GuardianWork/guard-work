package com.guardwork.backend.candidate.cvs;

import com.guardwork.backend.common.exception.AppException;
import com.guardwork.backend.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CandidateCvRetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final String PDF = "application/pdf";

    @Mock CandidateCvRepository cvs;
    @Mock CandidateCvStorage storage;

    CandidateCvRetentionService service;

    @BeforeEach
    void setUp() {
        service = new CandidateCvRetentionService(cvs, storage, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void deletesAndMarksEveryDueObject() {
        CandidateCv first = removedCv("candidates/a/cvs/one");
        CandidateCv second = removedCv("candidates/b/cvs/two");
        when(cvs.findPurgeableCvIds(20)).thenReturn(List.of(first.getId(), second.getId()));
        when(cvs.findById(first.getId())).thenReturn(Optional.of(first));
        when(cvs.findById(second.getId())).thenReturn(Optional.of(second));

        assertEquals(2, service.purgeDue(20));

        verify(storage).delete("candidates/a/cvs/one", "image");
        verify(storage).delete("candidates/b/cvs/two", "image");
        verify(cvs).markStorageDeleted(first.getId(), NOW);
        verify(cvs).markStorageDeleted(second.getId(), NOW);
    }

    @Test
    void keepsTheTombstoneWhenProviderDeletionFailsAndContinuesTheBatch() {
        CandidateCv failing = removedCv("candidates/a/cvs/one");
        CandidateCv succeeding = removedCv("candidates/b/cvs/two");
        when(cvs.findPurgeableCvIds(20)).thenReturn(List.of(failing.getId(), succeeding.getId()));
        when(cvs.findById(failing.getId())).thenReturn(Optional.of(failing));
        when(cvs.findById(succeeding.getId())).thenReturn(Optional.of(succeeding));
        doThrow(new AppException(ErrorCode.CV_STORAGE_UNAVAILABLE))
                .when(storage).delete("candidates/a/cvs/one", "image");

        assertEquals(1, service.purgeDue(20));

        verify(cvs, never()).markStorageDeleted(failing.getId(), NOW);
        verify(cvs).markStorageDeleted(succeeding.getId(), NOW);
    }

    @Test
    void skipsRowsThatDisappearedBetweenClaimAndLoad() {
        UUID id = UUID.randomUUID();
        when(cvs.findPurgeableCvIds(20)).thenReturn(List.of(id));
        when(cvs.findById(id)).thenReturn(Optional.empty());

        assertEquals(0, service.purgeDue(20));
        verifyNoInteractions(storage);
    }

    private CandidateCv removedCv(String publicId) {
        CandidateCv cv = new CandidateCv();
        cv.setId(UUID.randomUUID());
        cv.setPublicId(publicId);
        cv.setContentType(PDF);
        cv.setDeletedAt(NOW);
        return cv;
    }
}
