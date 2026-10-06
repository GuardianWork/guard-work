package com.guardwork.backend.audit.model;

import java.time.Instant;

public class AuditLog {

    private Long id;
    private Long adminId;
    private String action;
    private String targetType;
    private String targetId;
    private String oldPayload;
    private String newPayload;
    private String reason;
    private String ipAddress;
    private String userAgent;
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAdminId() {
        return adminId;
    }

    public void setAdminId(Long adminId) {
        this.adminId = adminId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public String getOldPayload() {
        return oldPayload;
    }

    public void setOldPayload(String oldPayload) {
        this.oldPayload = oldPayload;
    }

    public String getNewPayload() {
        return newPayload;
    }

    public void setNewPayload(String newPayload) {
        this.newPayload = newPayload;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
