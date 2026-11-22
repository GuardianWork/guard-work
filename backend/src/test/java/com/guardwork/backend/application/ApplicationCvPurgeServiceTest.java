package com.guardwork.backend.application;

import com.guardwork.backend.application.model.ApplicationSnapshot;
import com.guardwork.backend.application.repository.ApplicationSnapshotRepository;
import com.guardwork.backend.application.service.ApplicationCvPurgeService;
import com.guardwork.backend.candidate.cvs.CandidateCvStorage;
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
class ApplicationCvPurgeServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");
    private static final UUID APPLICATION_ID = UUID.randomUUID();
    private static final String PDF = "application/pdf";

    @Mock ApplicationSnapshotRepository snapshots;
    @Mock CandidateCvStorage storage;

    ApplicationCvPurgeService service;

    @BeforeEach
    void setUp() {
        service = new ApplicationCvPurgeService(snapshots, storage,
                Clock.fixed(NOW, ZoneOffset.UTC), 100);
    }

    @Test
    void deletesTheLegacyCopyAndKeepsTheSnapshotMarker() {
        ApplicationSnapshot snapshot = snapshot("applications/" + APPLICATION_ID + "/cv/copy.pdf");
        when(snapshots.findRejectedLegacyCvPurgeIds(100)).thenReturn(List.of(APPLICATION_ID));
        when(snapshots.findById(APPLICATION_ID)).thenReturn(Optional.of(snapshot));

        assertEquals(1, service.purgeDue());

        verify(storage).delete("applications/" + APPLICATION_ID + "/cv/copy.pdf", "image");
        verify(snapshots).markCvPurged(APPLICATION_ID, NOW);
    }

    @Test
    void neverDeletesAKeyOutsideTheApplicationFolder() {
        ApplicationSnapshot snapshot = snapshot("candidates/other/cvs/source");
        when(snapshots.findRejectedLegacyCvPurgeIds(100)).thenReturn(List.of(APPLICATION_ID));
        when(snapshots.findById(APPLICATION_ID)).thenReturn(Optional.of(snapshot));

        assertEquals(0, service.purgeDue());

        verify(storage, never()).delete(anyString(), anyString());
        verify(snapshots, never()).markCvPurged(any(), any());
    }

    @Test
    void keepsTheMarkerUnsetWhenDeletionFailsSoTheBatchRetries() {
        ApplicationSnapshot snapshot = snapshot("applications/" + APPLICATION_ID + "/cv/copy.pdf");
        when(snapshots.findRejectedLegacyCvPurgeIds(100)).thenReturn(List.of(APPLICATION_ID));
        when(snapshots.findById(APPLICATION_ID)).thenReturn(Optional.of(snapshot));
        doThrow(new AppException(ErrorCode.CV_STORAGE_UNAVAILABLE))
                .when(storage).delete(anyString(), anyString());

        assertEquals(0, service.purgeDue());

        verify(snapshots, never()).markCvPurged(any(), any());
    }

    @Test
    void skipsASnapshotThatWasAlreadyPurged() {
        ApplicationSnapshot snapshot = snapshot("applications/" + APPLICATION_ID + "/cv/copy.pdf");
        snapshot.setCvPurgedAt(NOW);
        when(snapshots.findRejectedLegacyCvPurgeIds(100)).thenReturn(List.of(APPLICATION_ID));
        when(snapshots.findById(APPLICATION_ID)).thenReturn(Optional.of(snapshot));

        assertEquals(0, service.purgeDue());

        verifyNoInteractions(storage);
        verify(snapshots, never()).markCvPurged(any(), any());
    }

    private ApplicationSnapshot snapshot(String key) {
        ApplicationSnapshot snapshot = new ApplicationSnapshot();
        snapshot.setApplicationId(APPLICATION_ID);
        snapshot.setCvStorageMode("LEGACY_COPY");
        snapshot.setCvStorageKey(key);
        snapshot.setCvContentType(PDF);
        return snapshot;
    }
}
