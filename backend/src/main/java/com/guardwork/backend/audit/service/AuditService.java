package com.guardwork.backend.audit.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.guardwork.backend.audit.model.AuditLog;
import com.guardwork.backend.audit.repository.AuditLogRepository;

@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    public AuditLog recordLog(Long adminId, String action, String targetType, String targetId,
                              String oldPayload, String newPayload, String reason,
                              String ipAddress, String userAgent) {
        AuditLog log = new AuditLog();
        log.setAdminId(adminId);
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setOldPayload(oldPayload);
        log.setNewPayload(newPayload);
        log.setReason(reason);
        log.setIpAddress(ipAddress != null && !ipAddress.isBlank() ? ipAddress : "127.0.0.1");
        log.setUserAgent(userAgent);

        return auditLogRepository.save(log);
    }

    public List<AuditLog> getLogsForTarget(String targetType, String targetId) {
        return auditLogRepository.findByTarget(targetType, targetId);
    }
}
