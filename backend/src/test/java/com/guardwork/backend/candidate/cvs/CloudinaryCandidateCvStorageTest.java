package com.guardwork.backend.candidate.cvs;

import com.cloudinary.Cloudinary;
import com.guardwork.backend.common.exception.AppException;
import com.guardwork.backend.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CloudinaryCandidateCvStorageTest {

    private static final String PUBLIC_ID = "applications/test/cv/snapshot";
    private static final byte[] PDF = "%PDF-1.4".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    @Mock ObjectProvider<Cloudinary> provider;
    @Mock HttpURLConnection connection;
    CloudinaryCandidateCvStorage storage;

    @BeforeEach
    void setUp() {
        when(provider.getIfAvailable()).thenReturn(new Cloudinary(Map.of(
                "cloud_name", "cv-test-cloud", "api_key", "test-key", "api_secret", "test-secret")));
        storage = spy(new CloudinaryCandidateCvStorage(provider));
    }

    @Test
    void readsTheActualSdkPrivateDownloadUrlWithoutRejectingItsApiHost() throws Exception {
        doReturn(connection).when(storage).openConnection(any(URI.class));
        when(connection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
        when(connection.getInputStream()).thenReturn(new ByteArrayInputStream(PDF));

        assertArrayEquals(PDF, storage.read(PUBLIC_ID, "image", "pdf"));

        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(storage).openConnection(uri.capture());
        assertEquals("https", uri.getValue().getScheme());
        assertEquals("api.cloudinary.com", uri.getValue().getHost());
        assertEquals("/v1_1/cv-test-cloud/image/download", uri.getValue().getPath());
        verify(connection).setInstanceFollowRedirects(false);
        verify(connection).setConnectTimeout(5_000);
        verify(connection).setReadTimeout(15_000);
        verify(connection).setRequestMethod("GET");
        verify(connection).disconnect();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://api.cloudinary.com/v1_1/cv-test-cloud/image/download",
            "https://example.test/v1_1/cv-test-cloud/image/download",
            "https://api.cloudinary.com.example.test/v1_1/cv-test-cloud/image/download",
            "https://api.cloudinary.com@evil.test/v1_1/cv-test-cloud/image/download",
            "https://user@api.cloudinary.com/v1_1/cv-test-cloud/image/download",
            "https://api.cloudinary.com:8443/v1_1/cv-test-cloud/image/download",
            "https://api.cloudinary.com/v1_1/other-cloud/image/download",
            "https://api.cloudinary.com/v1_1/cv-test-cloud/raw/download",
            "https://api.cloudinary.com/v1_1/cv-test-cloud/image/upload",
            "https://api.cloudinary.com/v1_1/cv-test-cloud/image/download/extra",
            "https://res.cloudinary.com/cv-test-cloud/image/upload/test.pdf"
    })
    void rejectsUnexpectedDownloadDestinationsBeforeOpeningAConnection(String url) throws Exception {
        doReturn(url).when(storage).createDownloadUrl(PUBLIC_ID, "image", "pdf", Duration.ofMinutes(1));

        AppException failure = assertThrows(AppException.class,
                () -> storage.read(PUBLIC_ID, "image", "pdf"));

        assertEquals(ErrorCode.CV_STORAGE_UNAVAILABLE, failure.getErrorCode());
        verify(storage, never()).openConnection(any(URI.class));
        verifyNoInteractions(connection);
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 404, 503})
    void rejectsNonOkProviderResponsesWithoutFollowingRedirects(int status) throws Exception {
        doReturn(connection).when(storage).openConnection(any(URI.class));
        when(connection.getResponseCode()).thenReturn(status);

        AppException failure = assertThrows(AppException.class,
                () -> storage.read(PUBLIC_ID, "image", "pdf"));

        assertEquals(ErrorCode.CV_STORAGE_UNAVAILABLE, failure.getErrorCode());
        verify(connection).setInstanceFollowRedirects(false);
        verify(connection, never()).getInputStream();
        verify(connection).disconnect();
    }

    @Test
    void retainsTheTenMiBResponseLimit() throws Exception {
        doReturn(connection).when(storage).openConnection(any(URI.class));
        when(connection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
        when(connection.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[10 * 1024 * 1024 + 1]));

        AppException failure = assertThrows(AppException.class,
                () -> storage.read(PUBLIC_ID, "image", "pdf"));

        assertEquals(ErrorCode.CV_STORAGE_UNAVAILABLE, failure.getErrorCode());
        verify(connection).disconnect();
    }

    @Test
    void mapsProviderReadFailuresAndDisconnects() throws Exception {
        doReturn(connection).when(storage).openConnection(any(URI.class));
        when(connection.getResponseCode()).thenThrow(new IOException("Provider unavailable"));

        AppException failure = assertThrows(AppException.class,
                () -> storage.read(PUBLIC_ID, "image", "pdf"));

        assertEquals(ErrorCode.CV_STORAGE_UNAVAILABLE, failure.getErrorCode());
        verify(connection).disconnect();
    }
}
