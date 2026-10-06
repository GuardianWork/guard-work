package com.guardwork.backend.audit.repository;

import java.util.List;

import com.guardwork.backend.audit.model.AuditLog;

public interface AuditLogRepository {

    AuditLog save(AuditLog auditLog);

    List<AuditLog> findByTarget(String targetType, String targetId);

    List<AuditLog> findByAdminId(Long adminId);
}
