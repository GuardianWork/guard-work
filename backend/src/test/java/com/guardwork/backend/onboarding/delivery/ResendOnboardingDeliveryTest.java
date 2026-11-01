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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ResendOnboardingDeliveryTest {
    final ObjectMapper json = new ObjectMapper();
    final HttpClient http = mock(HttpClient.class);

    @Test void requestedEmailUsesCodeAndProviderAcceptance() throws Exception {
        acceptedProvider();
        var delivery = adapter();
        assertThat(delivery.memberEmail("user@example.vn", "012345")).isTrue();
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().uri().toString()).isEqualTo("https://api.resend.com/emails");
        var payload = json.readTree(body(request.getValue()));
        assertThat(payload.path("to").get(0).asText()).isEqualTo("user@example.vn");
        assertThat(payload.path("text").asText())
                .contains("012345", "10 phút")
                .doesNotContain("https://", "emailSecret", "registration=");
    }

    @Test void accountCodeDeliveryDoesNotRequireFrontendUrlConfiguration() throws Exception {
        acceptedProvider();
        assertThat(adapter().memberEmail("user@example.vn", "012345")).isTrue();
    }

    @Test void missingMailCredentialsNeverSend() {
        var delivery = new ResendOnboardingDelivery(json, "", "", http);
        assertThat(delivery.memberEmail("user@example.vn", "012345")).isFalse();
        verifyNoInteractions(http);
    }

    @Test void lifecycleMailNamesTheSpecificActionInVietnamese() throws Exception {
        acceptedProvider();
        var purposes = java.util.Map.of("DEACTIVATE", "tạm ngừng tài khoản", "DELETE", "gửi yêu cầu xóa tài khoản",
                "REACTIVATE", "kích hoạt lại tài khoản", "CANCEL_DELETION", "hủy yêu cầu xóa tài khoản");
        for (var purpose : purposes.entrySet()) {
            clearInvocations(http);
            assertThat(adapter().accountActionEmail("user@example.vn", "012345", purpose.getKey())).isTrue();
            var request = ArgumentCaptor.forClass(HttpRequest.class);
            verify(http).send(request.capture(), any(HttpResponse.BodyHandler.class));
            var payload = json.readTree(body(request.getValue()));
            assertThat(payload.path("subject").asText()).contains(purpose.getValue());
            assertThat(payload.path("text").asText()).contains(purpose.getValue(), "012345", "10 phút")
                    .doesNotContain("Mã xác thực email của bạn");
        }
    }

    ResendOnboardingDelivery adapter() {
        return new ResendOnboardingDelivery(json, "GuardWork <mail@example.vn>", "test-credential", http);
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
