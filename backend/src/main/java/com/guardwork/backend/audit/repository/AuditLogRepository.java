package com.guardwork.backend.audit.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.guardwork.backend.audit.model.AuditLog;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    @Query("SELECT a FROM AuditLog a WHERE a.targetType = :targetType AND a.targetId = :targetId ORDER BY a.createdAt DESC")
    List<AuditLog> findByTarget(@Param("targetType") String targetType, @Param("targetId") String targetId);

    @Query("SELECT a FROM AuditLog a WHERE a.adminId = :adminId ORDER BY a.createdAt DESC")
    List<AuditLog> findByAdminId(@Param("adminId") Long adminId);
}
