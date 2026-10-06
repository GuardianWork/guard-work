package com.guardwork.backend.audit.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.guardwork.backend.audit.model.AuditLog;

@Repository
public class JdbcAuditLogRepository implements AuditLogRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcAuditLogRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<AuditLog> AUDIT_MAPPER = (rs, rowNum) -> {
        AuditLog log = new AuditLog();
        log.setId(rs.getLong("id"));
        log.setAdminId(rs.getLong("admin_id"));
        log.setAction(rs.getString("action"));
        log.setTargetType(rs.getString("target_type"));
        log.setTargetId(rs.getString("target_id"));
        log.setOldPayload(rs.getString("old_payload"));
        log.setNewPayload(rs.getString("new_payload"));
        log.setReason(rs.getString("reason"));
        log.setIpAddress(rs.getString("ip_address"));
        log.setUserAgent(rs.getString("user_agent"));
        log.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return log;
    };

    @Override
    public AuditLog save(AuditLog auditLog) {
        String sql = """
                INSERT INTO audit_logs (admin_id, action, target_type, target_id, old_payload, new_payload,
                                        reason, ip_address, user_agent, created_at)
                VALUES (:adminId, :action, :targetType, :targetId,
                        CAST(:oldPayload AS jsonb), CAST(:newPayload AS jsonb),
                        :reason, :ipAddress, :userAgent, :createdAt)
                """;

        Instant now = auditLog.getCreatedAt() != null ? auditLog.getCreatedAt() : Instant.now();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("adminId", auditLog.getAdminId())
                .addValue("action", auditLog.getAction())
                .addValue("targetType", auditLog.getTargetType())
                .addValue("targetId", auditLog.getTargetId())
                .addValue("oldPayload", auditLog.getOldPayload())
                .addValue("newPayload", auditLog.getNewPayload())
                .addValue("reason", auditLog.getReason())
                .addValue("ipAddress", auditLog.getIpAddress())
                .addValue("userAgent", auditLog.getUserAgent())
                .addValue("createdAt", Timestamp.from(now));

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(sql, params, keyHolder, new String[]{"id"});

        Number key = keyHolder.getKey();
        if (key != null) {
            auditLog.setId(key.longValue());
        }
        auditLog.setCreatedAt(now);
        return auditLog;
    }

    @Override
    public List<AuditLog> findByTarget(String targetType, String targetId) {
        String sql = """
                SELECT * FROM audit_logs
                WHERE target_type = :targetType AND target_id = :targetId
                ORDER BY created_at DESC
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("targetType", targetType)
                .addValue("targetId", targetId);
        return jdbcTemplate.query(sql, params, AUDIT_MAPPER);
    }

    @Override
    public List<AuditLog> findByAdminId(Long adminId) {
        String sql = """
                SELECT * FROM audit_logs
                WHERE admin_id = :adminId
                ORDER BY created_at DESC
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("adminId", adminId);
        return jdbcTemplate.query(sql, params, AUDIT_MAPPER);
    }
}
