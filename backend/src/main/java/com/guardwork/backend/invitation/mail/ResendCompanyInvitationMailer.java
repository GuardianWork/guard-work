package com.guardwork.backend.invitation.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.invitation.model.DeliveryStatus;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** One HTTPS provider attempt; acceptance acknowledges the provider, never mailbox delivery. */
@Component
public class ResendCompanyInvitationMailer implements CompanyInvitationMailer {
  private final ObjectMapper json;
  private final String sender;
  private final String credential;
  private final String baseUrl;
  private final String allowedOrigins;
  private final HttpClient client;
  private final URI endpoint;
  private final Duration requestTimeout;

  @org.springframework.beans.factory.annotation.Autowired
  public ResendCompanyInvitationMailer(
      ObjectMapper json,
      @Value("${COMPANY_INVITATION_FROM:}") String sender,
      @Value("${COMPANY_INVITATION_PROVIDER_CREDENTIAL:}") String credential,
      @Value("${COMPANY_INVITATION_BASE_URL:}") String baseUrl,
      @Value("${COMPANY_INVITATION_ALLOWED_ORIGINS:}") String allowedOrigins,
      @Value("${COMPANY_INVITATION_CONNECT_TIMEOUT_SECONDS:5}") int connectTimeout,
      @Value("${COMPANY_INVITATION_REQUEST_TIMEOUT_SECONDS:10}") int requestTimeout) {
    this(
        json,
        sender,
        credential,
        baseUrl,
        allowedOrigins,
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(Math.max(1, connectTimeout)))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
        URI.create("https://api.resend.com/emails"),
        Duration.ofSeconds(Math.max(1, requestTimeout)));
  }

  // Package seam for the isolated provider HTTP contract test; production endpoint is fixed above.
  ResendCompanyInvitationMailer(
      ObjectMapper json,
      String sender,
      String credential,
      String baseUrl,
      String allowedOrigins,
      HttpClient client,
      URI endpoint,
      Duration requestTimeout) {
    this.json = json;
    this.sender = sender;
    this.credential = credential;
    this.baseUrl = baseUrl;
    this.allowedOrigins = allowedOrigins;
    this.client = client;
    this.endpoint = endpoint;
    this.requestTimeout = requestTimeout;
  }

  @Override
  public DeliveryStatus send(
      String recipientEmail, String companyName, Instant expiresAt, String secret) {
    URI invitation;
    try {
      if (sender.isBlank()
          || credential.isBlank()
          || sender.contains("\n")
          || sender.contains("\r")) return DeliveryStatus.FAILED;
      invitation = allowedInvitationBase();
    } catch (IllegalArgumentException invalidConfiguration) {
      return DeliveryStatus.FAILED;
    }
    String expiry =
        DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm", Locale.forLanguageTag("vi-VN"))
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"))
            .format(expiresAt);
    String text =
        "Bạn được mời tham gia Công ty "
            + companyName
            + " với vai trò Người tuyển dụng (RECRUITER).\n"
            + "Lời mời hết hạn lúc "
            + expiry
            + " (giờ Việt Nam).\n"
            + "Đăng nhập bằng địa chỉ email nhận lời mời và xác nhận tham gia tại:\n"
            + invitation
            + "#secret="
            + secret;
    HttpRequest request;
    try {
      String body =
          json.writeValueAsString(
              Map.of(
                  "from",
                  sender,
                  "to",
                  List.of(recipientEmail),
                  "subject",
                  "Lời mời tham gia Công ty " + companyName,
                  "text",
                  text));
      request =
          HttpRequest.newBuilder(endpoint)
              .timeout(requestTimeout)
              .header("Authorization", "Bearer " + credential)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
    } catch (Exception invalidRequest) {
      return DeliveryStatus.FAILED;
    }
    try {
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) return DeliveryStatus.FAILED;
      var id = json.readTree(response.body()).path("id");
      return id.isTextual() && !id.asText().isBlank()
          ? DeliveryStatus.PROVIDER_ACCEPTED
          : DeliveryStatus.UNKNOWN;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return DeliveryStatus.UNKNOWN;
    } catch (Exception uncertain) {
      return DeliveryStatus.UNKNOWN;
    }
  }

  private URI allowedInvitationBase() {
    URI base = URI.create(baseUrl);
    if (!"https".equals(base.getScheme())
        || base.getHost() == null
        || base.getRawUserInfo() != null
        || base.getRawQuery() != null
        || base.getRawFragment() != null) throw new IllegalArgumentException();
    String origin = origin(base);
    boolean allowed =
        Arrays.stream(allowedOrigins.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .anyMatch(
                candidate -> {
                  try {
                    URI configured = URI.create(candidate);
                    return "https".equals(configured.getScheme())
                        && configured.getHost() != null
                        && configured.getRawUserInfo() == null
                        && configured.getRawQuery() == null
                        && configured.getRawFragment() == null
                        && (configured.getPath().isEmpty() || "/".equals(configured.getPath()))
                        && origin.equals(origin(configured));
                  } catch (IllegalArgumentException invalid) {
                    return false;
                  }
                });
    if (!allowed) throw new IllegalArgumentException();
    return base;
  }

  private static String origin(URI uri) {
    int port = uri.getPort() == -1 ? 443 : uri.getPort();
    return uri.getScheme() + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
  }
}
