package com.guardwork.backend.onboarding.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ResendOnboardingDeliveryTest {
    final ObjectMapper json = new ObjectMapper();
    final HttpClient http = mock(HttpClient.class);

    @Test void requestedEmailUsesAccountProofFragmentAndProviderAcceptance() throws Exception {
        acceptedProvider();
        var delivery = adapter("https://app.example.test/verify", "https://app.example.test");
        assertThat(delivery.memberEmail("user@example.vn", "mailbox-proof")).isTrue();
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().uri().toString()).isEqualTo("https://api.resend.com/emails");
        var payload = json.readTree(body(request.getValue()));
        assertThat(payload.path("to").get(0).asText()).isEqualTo("user@example.vn");
        assertThat(payload.path("text").asText())
                .contains("https://app.example.test/verify#emailSecret=mailbox-proof")
                .doesNotContain("memberEmailSecret", "?emailSecret", "registration=");
    }

    @Test void exactLocalhostOriginRemainsAvailableForDevelopment() throws Exception {
        acceptedProvider();
        assertThat(adapter("http://localhost:5137/verify", "http://localhost:5137")
                .memberEmail("user@example.vn", "mailbox-proof")).isTrue();
    }

    @Test void unsafeOrMismatchedConfirmationOriginsNeverSend() {
        for (String url : List.of("http://127.0.0.1:5137/verify", "http://sub.localhost:5137/verify",
                "http://remote.example.test/verify", "https://app.example.test/verify?secret=x",
                "https://app.example.test/verify#old", "https://other.example.test/verify")) {
            assertThat(adapter(url, "https://app.example.test").memberEmail("user@example.vn", "mailbox-proof")).isFalse();
        }
        assertThat(adapter("http://localhost:5137/verify", "http://localhost:5138")
                .memberEmail("user@example.vn", "mailbox-proof")).isFalse();
        verifyNoInteractions(http);
    }

    ResendOnboardingDelivery adapter(String url, String origin) {
        return new ResendOnboardingDelivery(json, "GuardWork <mail@example.vn>", "test-credential", url, origin, http);
    }

    @SuppressWarnings("unchecked")
    void acceptedProvider() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"id\":\"provider-receipt\"}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }

    String body(HttpRequest request) throws Exception {
        var result = new CompletableFuture<String>();
        var output = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer bytes) {
                byte[] chunk = new byte[bytes.remaining()]; bytes.get(chunk); output.writeBytes(chunk);
            }
            @Override public void onError(Throwable error) { result.completeExceptionally(error); }
            @Override public void onComplete() { result.complete(output.toString(StandardCharsets.UTF_8)); }
        });
        return result.get(5, TimeUnit.SECONDS);
    }
}
