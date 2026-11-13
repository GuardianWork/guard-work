package com.guardwork.backend.notification.template;

import com.guardwork.backend.notification.model.NotificationCategory;
import com.guardwork.backend.notification.model.NotificationEventType;
import com.guardwork.backend.notification.model.NotificationOutbox;
import com.guardwork.backend.notification.model.NotificationPayload;

/**
 * Presentation catalog: one template per backend event key.
 *
 * <p>Copy lives here (not in services) so business logic stays decoupled from user-facing
 * text. Title/body are rendered once at dispatch time and frozen as a snapshot on the inbox
 * row; later template edits never rewrite history.
 */
public enum NotificationTemplate {
  RECRUITER_ACCEPTED(NotificationEventType.RECRUITER_ACCEPTED, NotificationCategory.COMPANY) {
    @Override public String title(NotificationOutbox e) { return "Bạn đã được chấp nhận vào " + companyName(e); }
    @Override public String body(NotificationOutbox e) {
      return "Bạn đã chính thức trở thành thành viên của " + companyName(e)
          + ". Hãy hoàn thiện hồ sơ để ứng tuyển và nhận các thông báo liên quan.";
    }
    @Override public String actionHref(NotificationOutbox e) { return "/companies/" + e.payloadView().companyId(); }
  },
  MEMBERSHIP_REMOVED(NotificationEventType.MEMBERSHIP_REMOVED, NotificationCategory.COMPANY) {
    @Override public String title(NotificationOutbox e) { return "Quyền truy cập đã bị thu hồi"; }
    @Override public String body(NotificationOutbox e) {
      return "Quyền truy cập của bạn vào " + companyName(e)
          + " đã bị thu hồi. Liên hệ quản trị viên của công ty nếu bạn tin rằng điều này xảy ra nhầm lẫn.";
    }
    @Override public String actionHref(NotificationOutbox e) { return null; }
  },
  JOB_ASSIGNED(NotificationEventType.JOB_ASSIGNED, NotificationCategory.JOB) {
    @Override public String title(NotificationOutbox e) { return "Bạn được giao một tin tuyển dụng"; }
    @Override public String body(NotificationOutbox e) {
      return "Bạn được giao phụ trách tin tuyển dụng #" + jobId(e) + " tại " + companyName(e) + ".";
    }
    @Override public String actionHref(NotificationOutbox e) { return withJobHref(e); }
  },
  JOB_UNASSIGNED(NotificationEventType.JOB_UNASSIGNED, NotificationCategory.JOB) {
    @Override public String title(NotificationOutbox e) { return "Bạn không còn phụ trách tin tuyển dụng"; }
    @Override public String body(NotificationOutbox e) {
      return "Bạn không còn phụ trách tin tuyển dụng #" + jobId(e) + " tại " + companyName(e) + ".";
    }
    @Override public String actionHref(NotificationOutbox e) { return null; }
  },
  JOB_NEEDS_REASSIGNMENT(NotificationEventType.JOB_NEEDS_REASSIGNMENT, NotificationCategory.JOB) {
    @Override public String title(NotificationOutbox e) { return "Tin tuyển dụng cần chuyển người phụ trách"; }
    @Override public String body(NotificationOutbox e) {
      return "Tin tuyển dụng #" + jobId(e) + " tại " + companyName(e) + " cần chuyển người phụ trách mới.";
    }
    @Override public String actionHref(NotificationOutbox e) { return withJobHref(e); }
  },
  JOB_REVISION_APPROVED(NotificationEventType.JOB_REVISION_APPROVED, NotificationCategory.JOB) {
    @Override public String title(NotificationOutbox e) { return "Tin tuyển dụng đã được duyệt"; }
    @Override public String body(NotificationOutbox e) {
      return "Tin tuyển dụng #" + jobId(e) + " tại " + companyName(e) + " đã được phê duyệt và đăng công khai.";
    }
    @Override public String actionHref(NotificationOutbox e) { return withJobHref(e); }
  },
  JOB_REVISION_REJECTED(NotificationEventType.JOB_REVISION_REJECTED, NotificationCategory.JOB) {
    @Override public String title(NotificationOutbox e) { return "Tin tuyển dụng cần chỉnh sửa"; }
    @Override public String body(NotificationOutbox e) {
      return "Ban quản trị chưa thể phê duyệt tin tuyển dụng #" + jobId(e) + " tại " + companyName(e)
          + ". Vui lòng xem xét và chỉnh sửa.";
    }
    @Override public String actionHref(NotificationOutbox e) { return withJobHref(e); }
  },
  APPLICATION_STATUS_CHANGED(NotificationEventType.APPLICATION_STATUS_CHANGED, NotificationCategory.APPLICATION) {
    @Override public String title(NotificationOutbox e) { return "Trạng thái hồ sơ đã thay đổi"; }
    @Override public String body(NotificationOutbox e) {
      // Company -> action -> application -> applied-for role.
      String base = companyName(e) + " vừa cập nhật trạng thái hồ sơ ứng tuyển của bạn";
      String jobTitle = e.payloadView().jobTitle();
      return jobTitle == null || jobTitle.isBlank() ? base + "." : base + " cho vị trí " + jobTitle + ".";
    }
    @Override public String actionHref(NotificationOutbox e) { return withApplicationHref(e); }
  },
  APPLICATION_REJECTED(NotificationEventType.APPLICATION_REJECTED, NotificationCategory.APPLICATION) {
    @Override public String title(NotificationOutbox e) { return "Hồ sơ ứng tuyển đã bị từ chối"; }
    @Override public String body(NotificationOutbox e) {
      String base = "Hồ sơ ứng tuyển của bạn tại " + companyName(e) + " đã bị từ chối.";
      String feedback = e.payloadView().candidateFeedback();
      return feedback == null || feedback.isBlank() ? base : base + " " + feedback.strip();
    }
    @Override public String actionHref(NotificationOutbox e) { return withApplicationHref(e); }
  },
  APPLICATION_WITHDRAWN(NotificationEventType.APPLICATION_WITHDRAWN, NotificationCategory.APPLICATION) {
    @Override public String title(NotificationOutbox e) { return "Hồ sơ ứng tuyển đã bị rút"; }
    @Override public String body(NotificationOutbox e) {
      return "Hồ sơ ứng tuyển tại " + companyName(e) + " đã được rút.";
    }
    @Override public String actionHref(NotificationOutbox e) { return withApplicationHref(e); }
  },
  APPLICATION_INTERVIEWING(NotificationEventType.APPLICATION_INTERVIEWING, NotificationCategory.APPLICATION) {
    @Override public String title(NotificationOutbox e) { return "Hồ sơ đã chuyển sang vòng phỏng vấn"; }
    @Override public String body(NotificationOutbox e) {
      return "Hồ sơ của bạn đã chuyển sang vòng phỏng vấn. Nhà tuyển dụng sẽ liên hệ để trao đổi chi tiết.";
    }
    @Override public String actionHref(NotificationOutbox e) { return withApplicationHref(e); }
  },
  APPLICATION_HIRED(NotificationEventType.APPLICATION_HIRED, NotificationCategory.APPLICATION) {
    @Override public String title(NotificationOutbox e) { return "Bạn đã được tuyển"; }
    @Override public String body(NotificationOutbox e) {
      return "Công ty đã xác nhận bạn đồng ý gia nhập. Nhà tuyển dụng sẽ liên hệ với bạn về các bước tiếp theo.";
    }
    @Override public String actionHref(NotificationOutbox e) { return withApplicationHref(e); }
  },
  /** Fallback for unknown keys; keeps the raw backend key as its title. */
  UNKNOWN(NotificationEventType.UNKNOWN, NotificationCategory.SYSTEM) {
    @Override public String title(NotificationOutbox e) {
      return e.getEventType() == null ? "" : e.getEventType().wireValue();
    }
    @Override public String body(NotificationOutbox e) { return ""; }
    @Override public String actionHref(NotificationOutbox e) { return null; }
  };

  private final NotificationEventType eventType;
  private final NotificationCategory category;

  NotificationTemplate(NotificationEventType eventType, NotificationCategory category) {
    this.eventType = eventType;
    this.category = category;
  }

  public NotificationEventType eventType() { return eventType; }
  public String eventTypeValue() { return eventType.wireValue(); }
  public NotificationCategory category() { return category; }
  public String categoryValue() { return category.wireValue(); }
  public abstract String title(NotificationOutbox event);
  public abstract String body(NotificationOutbox event);
  /** Relative frontend path, or null for informational-only notifications. */
  public abstract String actionHref(NotificationOutbox event);

  private static NotificationPayload payload(NotificationOutbox e) { return e.payloadView(); }

  private static String companyName(NotificationOutbox e) { return payload(e).companyName(); }

  private static Long jobId(NotificationOutbox e) { return payload(e).jobId(); }

  private static String withJobHref(NotificationOutbox e) {
    Long jobId = jobId(e);
    return jobId == null ? null : "/jobs/" + jobId;
  }

  private static String withApplicationHref(NotificationOutbox e) {
    var applicationId = payload(e).applicationId();
    return applicationId == null ? null : "/applications/" + applicationId;
  }

  /** Unknown keys fall back to a system notification that keeps the raw key as its title. */
  public static NotificationTemplate find(NotificationEventType eventType) {
    if (eventType != null) {
      for (NotificationTemplate t : values()) {
        if (t.eventType == eventType) return t;
      }
    }
    return UNKNOWN;
  }

  /** Legacy string entry point; converts then resolves by kind. */
  public static NotificationTemplate find(String eventType) {
    return find(NotificationEventType.fromWire(eventType));
  }

  /** Category only, for legacy rows that were written before templates populated the snapshot. */
  public static String categoryForEvent(NotificationEventType eventType) {
    return find(eventType).categoryValue();
  }

  /** Legacy string overload; kept for existing call sites. */
  public static String categoryForEvent(String eventType) {
    return find(eventType).categoryValue();
  }
}
