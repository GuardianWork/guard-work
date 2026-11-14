package com.guardwork.backend.notification.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Guards the forward-compatible contract of the JSONB outbox payload. */
class NotificationPayloadTest {

  @Test
  void unknownFutureKeysAreIgnoredSoOlderReadersKeepWorking() {
    String futurePayload =
        "{\"companyId\":7,\"companyName\":\"Công ty A\",\"jobId\":12,"
            + "\"futureContext\":{\"source\":\"mobile\"},\"candidateFeedback\":null}";

    var payload = NotificationPayload.fromJson(futurePayload);

    assertThat(payload.companyId()).isEqualTo(7L);
    assertThat(payload.companyName()).isEqualTo("Công ty A");
    assertThat(payload.jobId()).isEqualTo(12L);
    assertThat(payload.jobTitle()).isNull();
  }

  @Test
  void keysAbsentFromOlderRowsDeserializeAsNull() {
    var payload = NotificationPayload.fromJson("{\"companyId\":7,\"companyName\":\"Công ty A\"}");

    assertThat(payload.jobId()).isNull();
    assertThat(payload.jobTitle()).isNull();
    assertThat(payload.applicationId()).isNull();
    assertThat(payload.candidateFeedback()).isNull();
  }

  @Test
  void payloadRoundTripsAndOmitsNullFields() {
    UUID application = UUID.randomUUID();
    var payload =
        NotificationPayload.application(7L, "Công ty A", 12L, "Backend Engineer", application, null);

    String json = payload.toJson();

    assertThat(json).doesNotContain("candidateFeedback").doesNotContain("revisionId");
    assertThat(NotificationPayload.fromJson(json)).isEqualTo(payload);
  }
}
