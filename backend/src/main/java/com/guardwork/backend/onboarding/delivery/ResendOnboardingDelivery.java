package com.guardwork.backend.onboarding.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ResendOnboardingDelivery implements OnboardingDelivery {
    private final ObjectMapper json;
    private final String sender;
    private final String credential;
    private final HttpClient http;

    @org.springframework.beans.factory.annotation.Autowired
    public ResendOnboardingDelivery(ObjectMapper json,
            @Value("${ONBOARDING_EMAIL_FROM:}") String sender,
            @Value("${ONBOARDING_EMAIL_CREDENTIAL:}") String credential) {
        this(json, sender, credential, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    @Override public boolean memberEmail(String recipient, String secret) {
        String url = confirmationUrl();
        return url != null && send(recipient, "Xác thực email tuyển dụng GuardWork",
                "Đăng nhập và xác nhận email tại:\n" + url + "#memberEmailSecret=" + secret, null);
    }

    @Override public boolean decisionEmail(String recipient, String company, String outcome, String reason, UUID id) {
        return send(recipient, "Kết quả xác minh công ty GuardWork",
                "Công ty: " + company + "\nKết quả: " + ("VERIFIED".equals(outcome) ? "Đã xác minh" : "Bị từ chối")
                + (reason == null ? "" : "\nLý do: " + reason) + "\nXem chi tiết trong Cài đặt tài khoản.", id);
    }

    private boolean send(String recipient, String subject, String text, UUID id) {
        if (sender.isBlank() || credential.isBlank() || sender.contains("\n") || sender.contains("\r")) return false;
        try {
            var builder = HttpRequest.newBuilder(URI.create("https://api.resend.com/emails"))
                    .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + credential)
                    .header("Content-Type", "application/json");
            if (id != null) builder.header("Idempotency-Key", "company-decision-" + id);
            var response = http.send(builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                    Map.of("from", sender, "to", List.of(recipient), "subject", subject, "text", text)))).build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300
                    && json.readTree(response.body()).path("id").isTextual();
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        catch (Exception failure) { return false; }
    }

}
