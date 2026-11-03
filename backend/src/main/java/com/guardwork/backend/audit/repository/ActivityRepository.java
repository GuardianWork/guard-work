package com.guardwork.backend.audit.repository;

import com.guardwork.backend.audit.model.AuditLog;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActivityRepository extends JpaRepository<AuditLog,Long> {
  String SOURCE = """
      WITH activities AS (
        SELECT a.*, CASE
          WHEN a.action IN ('ACCOUNT_BANNED','ACCOUNT_UNBANNED') AND a.target_type IN ('USER','COMPANY') THEN 'ACCOUNT_MANAGEMENT'
          WHEN a.action IN ('JOB_REVISION_APPROVED','JOB_REVISION_REJECTED') AND a.target_type='JOB_REVISION' THEN 'JOB_MODERATION'
          WHEN a.action='COMPANY_VERIFICATION' AND a.target_type='COMPANY' THEN 'COMPANY_VERIFICATION'
        END AS category,
        CASE a.action
          WHEN 'ACCOUNT_BANNED' THEN 'BAN_ACCOUNT'
          WHEN 'ACCOUNT_UNBANNED' THEN 'UNBAN_ACCOUNT'
          WHEN 'JOB_REVISION_APPROVED' THEN 'APPROVE_JOB_REVISION'
          WHEN 'JOB_REVISION_REJECTED' THEN 'REJECT_JOB_REVISION'
          WHEN 'COMPANY_VERIFICATION' THEN CASE a.new_payload->>'verificationStatus'
            WHEN 'VERIFIED' THEN 'APPROVE_COMPANY' WHEN 'REJECTED' THEN 'REJECT_COMPANY' ELSE 'REVIEW_COMPANY' END
        END AS activity_action,
        CASE WHEN a.target_type='JOB_REVISION' THEN 'JOB_POSTING' ELSE a.target_type END AS activity_type,
        CASE WHEN a.target_type='JOB_REVISION' THEN r.job_id::text ELSE a.target_id END AS activity_target
        FROM audit_logs a LEFT JOIN job_posting_revisions r
          ON a.target_type='JOB_REVISION' AND r.id::text=a.target_id
        WHERE a.admin_id=:actor AND a.created_at>=:start AND a.created_at<:end
      ) 
      """;


  String FILTER = " FROM activities WHERE category IS NOT NULL"
      + " AND (CAST(:category AS text) IS NULL OR category=:category)"
      + " AND (CAST(:action AS text) IS NULL OR activity_action=:action)"
      + " AND (CAST(:type AS text) IS NULL OR activity_type=:type)"
      + " AND (CAST(:target AS text) IS NULL OR activity_target=:target)";
  @Query(value=SOURCE+" SELECT *"+FILTER+" ORDER BY created_at DESC,id DESC LIMIT 20 OFFSET :offset",nativeQuery=true)
  List<Map<String,Object>> activities(@Param("actor") long actor,@Param("start") Timestamp start,@Param("end") Timestamp end,
      @Param("category") String category,@Param("action") String action,@Param("type") String type,@Param("target") String target,@Param("offset") long offset);
  @Query(value=SOURCE+" SELECT count(*)"+FILTER,nativeQuery=true)
  long countActivities(@Param("actor") long actor,@Param("start") Timestamp start,@Param("end") Timestamp end,
      @Param("category") String category,@Param("action") String action,@Param("type") String type,@Param("target") String target);
}
