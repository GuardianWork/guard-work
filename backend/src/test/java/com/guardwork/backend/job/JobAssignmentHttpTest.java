package com.guardwork.backend.job;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class JobAssignmentHttpTest extends JobDraftHttpTest {
 @Test void ownerRevokesCreatorAndRetainsHistoricalInbox() throws Exception {
  String owner=account(), recruiter=account(); long company=createdCompany(owner);
  long member=recruiter(company,recruiter); var job=create(recruiter,Map.of("title","Private"));
  String target=JOBS+"/"+job.path("id").asLong()+"/assignments/"+member;
  http.perform(delete(target+"?expectedJobVersion=0").header("Authorization",recruiter)).andExpect(status().isForbidden());
  http.perform(delete(target+"?expectedJobVersion=0").header("Authorization",owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.needsReassignment").value(true));
  http.perform(get(JOBS+"/"+job.path("id").asLong()).header("Authorization",recruiter)).andExpect(status().isNotFound());
  http.perform(delete(target+"?expectedJobVersion=1").header("Authorization",owner)).andExpect(status().isOk());
  http.perform(get("/api/me/notifications").header("Authorization",recruiter)).andExpect(jsonPath("$.data.totalElements").value(2));
  http.perform(get("/api/me/notifications").header("Authorization",owner)).andExpect(jsonPath("$.data.totalElements").value(1)).andExpect(jsonPath("$.data.content[0].eventType").value("JOB_NEEDS_REASSIGNMENT"));
 }

 @Test void assignmentAndOwnerResponsibilityAreIndependentAndVersioned() throws Exception {
  String owner=account(), recruiter=account();long company=createdCompany(owner);long member=recruiter(company,recruiter);
  var job=create(owner,Map.of());long id=job.path("id").asLong();String base=JOBS+"/"+id;
  http.perform(put(base+"/assignments/"+member).header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content("{\"expectedJobVersion\":0}"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.ownerResponsible").value(true)).andExpect(jsonPath("$.data.assignments.length()").value(1));
  http.perform(put(base+"/assignments/"+member).header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content("{\"expectedJobVersion\":1}"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
  http.perform(put(base+"/owner-responsibility").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content("{\"expectedJobVersion\":1,\"responsible\":false}"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.needsReassignment").value(false));
  http.perform(put(base+"/owner-responsibility").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content("{\"expectedJobVersion\":1,\"responsible\":true}"))
   .andExpect(status().isConflict());
  http.perform(delete(base+"/assignments/"+member+"?expectedJobVersion=2").header("Authorization",owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.needsReassignment").value(true));
  http.perform(put(base+"/owner-responsibility").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content("{\"expectedJobVersion\":3,\"responsible\":true}"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.needsReassignment").value(false));
 }
 @Test void membershipDepartureEndsAssignmentsPermanentlyAndNotifiesOwner() throws Exception {
  String owner=account(), recruiter=account();long company=createdCompany(owner);long member=recruiter(company,recruiter);
  var job=create(recruiter,Map.of());long id=job.path("id").asLong();
  http.perform(post("/api/companies/me/membership/leave").header("Authorization",recruiter)).andExpect(status().isOk());
  http.perform(get(JOBS+"/"+id).header("Authorization",owner)).andExpect(jsonPath("$.data.needsReassignment").value(true));
  database.update("UPDATE company_memberships SET status='ACTIVE',ended_at=null,ended_by_user_id=null WHERE id=?",member);
  http.perform(get(JOBS+"/"+id).header("Authorization",recruiter)).andExpect(status().isNotFound());
  http.perform(get("/api/me/notifications").header("Authorization",recruiter)).andExpect(jsonPath("$.data.totalElements").value(2));
  http.perform(get("/api/me/notifications").header("Authorization",owner)).andExpect(jsonPath("$.data.totalElements").value(1));
 }

 @Test void assignmentRejectsForeignInactiveAdminAndMissingVersion() throws Exception {
  String owner=account(), other=account(), recruiter=account(), foreign=account();long company=createdCompany(owner), otherCompany=createdCompany(other);
  long member=recruiter(company,recruiter), foreignMember=recruiter(otherCompany,foreign);long id=create(owner,Map.of()).path("id").asLong();
  String base=JOBS+"/"+id+"/assignments/";
  http.perform(put(base+foreignMember).header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content("{\"expectedJobVersion\":0}"))
   .andExpect(status().isNotFound());
  http.perform(put(base+member).header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content("{}"))
   .andExpect(status().isBadRequest());
  database.update("UPDATE company_memberships SET role='ADMIN' WHERE id=?",member);
  http.perform(put(base+member).header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content("{\"expectedJobVersion\":0}"))
   .andExpect(status().isNotFound());
  database.update("UPDATE company_memberships SET role='RECRUITER' WHERE id=?",member);
  database.update("UPDATE users SET status='SUSPENDED' WHERE id=?",userId(recruiter));
  http.perform(put(base+member).header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content("{\"expectedJobVersion\":0}"))
   .andExpect(status().isNotFound());
 }
 @Test void failedRemovalIntentRollsBackAccessAndMembership() throws Exception {
  String owner=account(), recruiter=account();long company=createdCompany(owner), member=recruiter(company,recruiter);
  long id=create(recruiter,Map.of()).path("id").asLong();long recipient=userId(recruiter);
  database.execute("CREATE FUNCTION gw78_assignment_fail() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'controlled failure' USING ERRCODE='23514'; END; $$ LANGUAGE plpgsql");
  database.execute("CREATE TRIGGER gw78_assignment_fail BEFORE INSERT ON notification_outbox FOR EACH ROW WHEN (NEW.recipient_user_id="+recipient+" AND NEW.event_type='JOB_UNASSIGNED') EXECUTE FUNCTION gw78_assignment_fail()");
  try {
   http.perform(delete(JOBS+"/"+id+"/assignments/"+member+"?expectedJobVersion=0").header("Authorization",owner)).andExpect(status().isInternalServerError());
   http.perform(get(JOBS+"/"+id).header("Authorization",recruiter)).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(0));
   http.perform(post("/api/companies/me/membership/leave").header("Authorization",recruiter)).andExpect(status().isInternalServerError());
   http.perform(get(JOBS+"/"+id).header("Authorization",recruiter)).andExpect(status().isOk());
  } finally {database.execute("DROP TRIGGER gw78_assignment_fail ON notification_outbox");database.execute("DROP FUNCTION gw78_assignment_fail()");}
 }

 @Test void ownerMembershipRemovalPreservesCommittedRevocationWhenInboxFailsAndRecoveryDeduplicates() throws Exception {
  String owner=account(), recruiter=account();long company=createdCompany(owner), member=recruiter(company,recruiter);
  long id=create(recruiter,Map.of()).path("id").asLong();long recipient=userId(recruiter);
  database.execute("CREATE FUNCTION gw78_removed_inbox_fail() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'controlled failure' USING ERRCODE='23514'; END; $$ LANGUAGE plpgsql");
  database.execute("CREATE TRIGGER gw78_removed_inbox_fail BEFORE INSERT ON user_notifications FOR EACH ROW WHEN (NEW.recipient_user_id="+recipient+") EXECUTE FUNCTION gw78_removed_inbox_fail()");
  try {
   http.perform(delete("/api/companies/me/members/"+member).header("Authorization",owner)).andExpect(status().isOk());
   http.perform(get(JOBS+"/"+id).header("Authorization",recruiter)).andExpect(status().isNotFound());
   http.perform(get(JOBS+"/"+id).header("Authorization",owner)).andExpect(jsonPath("$.data.needsReassignment").value(true));
  } finally {database.execute("DROP TRIGGER gw78_removed_inbox_fail ON user_notifications");database.execute("DROP FUNCTION gw78_removed_inbox_fail()");}
  var events=database.queryForList("SELECT event_id FROM notification_outbox WHERE recipient_user_id=?",java.util.UUID.class,recipient);
  for(var event:events){dispatcher.replay(event);dispatcher.replay(event);}
  http.perform(get("/api/me/notifications").header("Authorization",recruiter)).andExpect(jsonPath("$.data.totalElements").value(3));
 }
}
