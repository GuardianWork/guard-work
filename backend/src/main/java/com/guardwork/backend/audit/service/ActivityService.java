package com.guardwork.backend.audit.service;

import com.fasterxml.jackson.databind.*;
import com.guardwork.backend.audit.dto.ActivityItem;
import com.guardwork.backend.audit.dto.ActivityItem.StateChange;
import com.guardwork.backend.audit.repository.ActivityRepository;
import com.guardwork.backend.common.PageResponse;
import java.sql.Timestamp;
import java.time.*;

import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ActivityService {
  private final ActivityRepository repository;
  private final ObjectMapper json;
  private final Clock clock;
  private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
  private static final Map<String,String> CATEGORIES = Map.of(
      "BAN_ACCOUNT","ACCOUNT_MANAGEMENT", "UNBAN_ACCOUNT","ACCOUNT_MANAGEMENT",
      "APPROVE_JOB_REVISION","JOB_MODERATION", "REJECT_JOB_REVISION","JOB_MODERATION",
      "APPROVE_COMPANY","COMPANY_VERIFICATION", "REJECT_COMPANY","COMPANY_VERIFICATION", "REVIEW_COMPANY","COMPANY_VERIFICATION");
  public ActivityService(ActivityRepository repository, ObjectMapper json, Clock clock) {
    this.repository=repository; this.json=json; this.clock=clock;
  }

  @Transactional(readOnly=true)
  public PageResponse<ActivityItem> search(long actor, MultiValueMap<String,String> input) {
    Set<String> allowed = Set.of("fromDate","toDate","category","action","targetType","targetId","page","size");
    input.forEach((key,values) -> { if (!allowed.contains(key) || values.size()!=1) throw invalid(); });
    var params = new HashMap<String,Object>();
    try {
      String from=value(input,"fromDate"), to=value(input,"toDate");
      if ((from==null)!=(to==null)) throw invalid();
      LocalDate end=to==null ? LocalDate.now(clock.withZone(VIETNAM)) : date(to);
      LocalDate start=from==null ? end.minusDays(29) : date(from);
      if (start.isAfter(end)) throw invalid();
      params.put("start",Timestamp.from(start.atStartOfDay(VIETNAM).toInstant()));
      params.put("end",Timestamp.from(end.plusDays(1).atStartOfDay(VIETNAM).toInstant()));
      int page=value(input,"page")==null ? 0 : Integer.parseInt(value(input,"page"));
      if (page<0 || (value(input,"size")!=null && !"20".equals(value(input,"size")))) throw invalid();
      String category=value(input,"category"), action=value(input,"action"), type=value(input,"targetType"), id=value(input,"targetId");
      if (category!=null && !CATEGORIES.containsValue(category)) throw invalid();
      if (action!=null && !CATEGORIES.containsKey(action)) throw invalid();
      if (type!=null && !Set.of("USER","COMPANY","JOB_POSTING").contains(type)) throw invalid();
      if (action!=null && category!=null && !CATEGORIES.get(action).equals(category)) throw invalid();
      String selected=action==null ? category : CATEGORIES.get(action);
      if (type!=null && selected!=null && !(switch(selected) {
        case "ACCOUNT_MANAGEMENT" -> Set.of("USER","COMPANY").contains(type);
        case "JOB_MODERATION" -> "JOB_POSTING".equals(type);
        default -> "COMPANY".equals(type);
      })) throw invalid();
      if (id!=null) {
        if (category==null || type==null || !id.matches("[0-9]+") || Long.parseLong(id)<=0) throw invalid();
        params.put("targetId",Long.toString(Long.parseLong(id)));
      }
      if (category!=null) params.put("category",category);
      if (action!=null) params.put("action",action);
      if (type!=null) params.put("targetType",type);
      params.put("actor",actor);
      String target=(String)params.get("targetId");
      Timestamp startTime=(Timestamp)params.get("start"), endTime=(Timestamp)params.get("end");
      long total=repository.countActivities(actor,startTime,endTime,category,action,type,target);
      var rows=repository.activities(actor,startTime,endTime,category,action,type,target,(long)page*20);
      return PageResponse.of(rows.stream().map(this::project).toList(),page,20,total);
    } catch (DateTimeException | IllegalArgumentException failure) { throw invalid(); }
      catch (DataAccessException unavailable) {
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Activity history unavailable");
      }
  }

  private ActivityItem project(Map<String,Object> row) {
    String category=(String)row.get("category"), type=(String)row.get("activity_type"), action=(String)row.get("activity_action");
    JsonNode before=parse(row.get("old_payload")), after=parse(row.get("new_payload"));
    String field=switch(category) {
      case "ACCOUNT_MANAGEMENT" -> "COMPANY".equals(type) ? "isBanned" : "status";
      case "JOB_MODERATION" -> "reviewState";
      default -> "verificationStatus";
    };
    Object oldState=state(before,field), newState=state(after,field);
    if (newState==null && "reviewState".equals(field)) newState=enumValue(after.path("outcome"),Set.of("APPROVED","REJECTED"));
    String reason=(String)row.get("reason");
    if (action.startsWith("APPROVE_") || "REVIEW_COMPANY".equals(action)) reason=null;
    if ("REJECT_COMPANY".equals(action)) {
      JsonNode rejection=after.path("rejectionReason");
      if (rejection.isTextual()) reason=rejection.textValue();
    }
    return new ActivityItem(((Number)row.get("id")).longValue(),instant(row.get("created_at")),
        category,action,type,(String)row.get("activity_target"),"JOB_MODERATION".equals(category) ? (String)row.get("target_id") : null,
        List.of(new StateChange(field,oldState,newState)),reason);
  }
  private static Instant instant(Object value) {
    if (value instanceof Instant instant) return instant;
    if (value instanceof OffsetDateTime date) return date.toInstant();
    return ((Timestamp)value).toInstant();
  }
  private JsonNode parse(Object raw) {
    try { return raw==null ? json.createObjectNode() : json.readTree(raw.toString()); }
    catch (Exception ignored) { return json.createObjectNode(); }
  }
  private Object state(JsonNode node,String field) {
    JsonNode value=node.path(field);
    return switch(field) {
      case "isBanned" -> value.isBoolean() ? value.booleanValue() : null;
      case "status" -> enumValue(value,Set.of("ACTIVE","BANNED","SUSPENDED","DELETED"));
      case "verificationStatus" -> enumValue(value,Set.of("PENDING","VERIFIED","REJECTED"));
      default -> enumValue(value,Set.of("DRAFT","PENDING_REVIEW","APPROVED","REJECTED","WITHDRAWN"));
    };
  }
  private static String enumValue(JsonNode node,Set<String> allowed) { return node.isTextual() && allowed.contains(node.textValue()) ? node.textValue() : null; }
  private static String value(MultiValueMap<String,String> input,String key) { String value=input.getFirst(key); return value==null || value.isEmpty() ? null : value; }
  private static LocalDate date(String text) { if (!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid(); return LocalDate.parse(text); }
  private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid activity filters"); }
}
