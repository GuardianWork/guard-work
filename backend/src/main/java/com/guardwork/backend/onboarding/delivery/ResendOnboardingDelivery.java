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
    private final String baseUrl;
    private final Set<String> allowedOrigins;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public ResendOnboardingDelivery(ObjectMapper json,
            @Value("${ONBOARDING_EMAIL_FROM:}") String sender,
            @Value("${ONBOARDING_EMAIL_CREDENTIAL:}") String credential,
            @Value("${ONBOARDING_CONFIRMATION_URL:}") String baseUrl,
            @Value("${ONBOARDING_ALLOWED_ORIGINS:}") String origins) {
        this.json = json; this.sender = sender; this.credential = credential; this.baseUrl = baseUrl;
        this.allowedOrigins = new HashSet<>(Arrays.asList(origins.split(",")));
    }

    @Override public boolean registrationEmail(String recipient, UUID id, String secret) {
        String url = confirmationUrl();
        return url != null && send(recipient, "Xác thực email tài khoản GuardWork",
                "Mở liên kết để xác nhận đăng ký. Liên kết có hiệu lực trong 24 giờ.\n"
                + url + "#registration=" + id + "&emailSecret=" + secret, null);
    }

    @Override public boolean memberEmail(String recipient, String secret) {
        String url = confirmationUrl();
        return url != null && send(recipient, "Xác thực email tuyển dụng GuardWork",
                "Đăng nhập và xác nhận email tại:\n" + url + "#memberEmailSecret=" + secret, null);
    }

    @Override public boolean sms(String phone, String code) {
        // No provider has been selected. Never substitute console output or accept undelivered proof.
        return false;
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

    private String confirmationUrl() {
        try {
            URI uri = URI.create(baseUrl);
            if (!permittedFrontendUrl(uri)) return null;
            boolean allowed = allowedOrigins.stream().anyMatch(candidate -> {
                try {
                    URI configured = URI.create(candidate.trim());
                    return permittedFrontendUrl(configured)
                            && (configured.getPath().isEmpty() || "/".equals(configured.getPath()))
                            && origin(uri).equals(origin(configured));
                } catch (IllegalArgumentException invalid) { return false; }
            });
            if (!allowed) return null;
            return uri.toASCIIString();
        } catch (IllegalArgumentException invalid) { return null; }
    }

    private static boolean permittedFrontendUrl(URI uri) {
        if (uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null || uri.getRawQuery() != null) return false;
        // HTTP is a localhost-only development exception; exact origin allowlisting still applies.
        return "https".equals(uri.getScheme())
                || "http".equals(uri.getScheme()) && "localhost".equalsIgnoreCase(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() >= 1 && uri.getPort() <= 65535);
    }

    private static String origin(URI uri) {
        int port = uri.getPort() == -1 ? ("http".equals(uri.getScheme()) ? 80 : 443) : uri.getPort();
        return uri.getScheme() + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }
}
