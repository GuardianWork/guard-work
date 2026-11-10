package com.guardwork.backend.notification.template;

import static org.assertj.core.api.Assertions.assertThat;

import com.guardwork.backend.notification.model.NotificationOutbox;
import com.guardwork.backend.notification.model.NotificationPayload;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Frozen copy contract for notification templates. */
class NotificationTemplateTest {

  @Test
  void statusChangedCopyReadsCompanyActionApplicationThenRole() {
    var event =
        outbox(
            NotificationPayload.application(
                7L, "Công ty A", 12L, "Backend Engineer", UUID.randomUUID(), null));

    assertThat(NotificationTemplate.APPLICATION_STATUS_CHANGED.body(event))
        .isEqualTo(
            "Công ty A vừa cập nhật trạng thái hồ sơ ứng tuyển của bạn cho vị trí Backend Engineer.");
  }

  @Test
  void statusChangedCopyFallsBackWhenLegacyPayloadHasNoJobTitle() {
    var event =
        outbox(
            new NotificationPayload(
                7L, "Công ty A", 12L, null, null, UUID.randomUUID(), null, null, null));

    assertThat(NotificationTemplate.APPLICATION_STATUS_CHANGED.body(event))
        .isEqualTo("Công ty A vừa cập nhật trạng thái hồ sơ ứng tuyển của bạn.");
  }

  private NotificationOutbox outbox(NotificationPayload payload) {
    return NotificationOutbox.builder().payload(payload.toJson()).build();
  }
}
