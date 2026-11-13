package com.guardwork.backend.notification.dto;

import com.guardwork.backend.notification.model.NotificationInbox;
import com.guardwork.backend.notification.template.NotificationTemplate;
import lombok.Builder;

/**
 * Shared response envelope for every notification type; per-event variety lives in
 * {@code references} plus the template-rendered {@code title}/{@code body} snapshot.
 */
@Builder
public record NotificationResponse(
    String id,
    String type,
    String audience,
    String category,
    String title,
    String body,
    String createdAt,
    boolean read,
    String readAt,
    NotificationAction action,
    NotificationActor actor,
    NotificationReferences references) {
  public static NotificationResponse from(NotificationInbox n) {
    String type = n.getEventType() == null ? "" : n.getEventType().wireValue();
    String audience = n.getAudience() == null ? "" : n.getAudience().wireValue();
    String category =
        n.getCategory() == null || n.getCategory().isBlank()
            ? NotificationTemplate.categoryForEvent(n.getEventType())
            : n.getCategory();
    String title =
        n.getTitle() == null || n.getTitle().isBlank() ? type : n.getTitle();
    String body = n.getBody() == null ? "" : n.getBody();
    String createdAt = n.getCreatedAt() == null ? "" : n.getCreatedAt().toString();
    String readAt = n.getReadAt() == null ? null : n.getReadAt().toString();
    NotificationAction action =
        n.getActionHref() == null || n.getActionHref().isBlank()
            ? null
            : NotificationAction.builder().href(n.getActionHref()).build();
    NotificationActor actor =
        n.getActorUserId() == null || n.getActorName() == null || n.getActorName().isBlank()
            ? null
            : NotificationActor.builder()
                .id(String.valueOf(n.getActorUserId()))
                .name(n.getActorName())
                .build();
    NotificationReferences references =
        NotificationReferences.builder()
            .companyId(n.getCompanyId())
            .companyName(n.getCompanyName())
            .jobId(n.getJobId())
            .revisionId(n.getRevisionId())
            .applicationId(n.getApplicationId())
            .candidateFeedback(n.getCandidateFeedback())
            .build();
    return NotificationResponse.builder()
        .id(n.getId() == null ? "" : String.valueOf(n.getId()))
        .type(type)
        .audience(audience)
        .category(category)
        .title(title)
        .body(body)
        .createdAt(createdAt)
        .read(n.getReadAt() != null)
        .readAt(readAt)
        .action(action)
        .actor(actor)
        .references(references)
        .build();
  }
}
