package com.guardwork.backend.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.guardwork.backend.job.service.JobExpiryService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class JobLifecycleHttpTest extends JobRevisionHttpTest {
 @Test void ownerPausesResumesAndClosesWithoutChangingApprovedContent() throws Exception {
  String owner=account();long company=createdCompany(owner);database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
  var pending=submit(owner,create(owner,complete()));String staff=staff(owner);decide(staff,pending,"APPROVED");long job=pending.path("id").asLong();
  var published=inspect(owner,job);
  assertThat(published.path("availableActions").toString()).contains("CREATE_DRAFT","PAUSE","CLOSE");
  http.perform(post(JOBS+"/"+job+"/pause").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(version(published)))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PAUSED"));
  http.perform(get("/api/jobs/"+job)).andExpect(jsonPath("$.data.applicationAvailabilityReason").value("PAUSED"))
   .andExpect(jsonPath("$.data.content.description").value("Dòng một\nDòng hai"));
  var paused=inspect(owner,job);lifecycle(owner,paused,"resume").andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PUBLISHED"));
  lifecycle(owner,inspect(owner,job),"close").andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CLOSED"));
  var closed=inspect(owner,job);lifecycle(owner,closed,"resume").andExpect(status().isConflict());
  http.perform(get("/api/jobs/"+job)).andExpect(jsonPath("$.data.applicationAvailabilityReason").value("CLOSED"));
  assertThat(closed.path("publishedRevisionId")).isEqualTo(published.path("publishedRevisionId"));
 }

 @Test void effectiveExpiryBlocksDraftChangesAndFiltersPrivateListBeforeWorker() throws Exception {
  String owner=account();long company=createdCompany(owner);database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
  var fields=complete();var deadline=clock.now.plusSeconds(30);fields.put("deadline",deadline.toString());
  var pending=submit(owner,create(owner,fields));decide(staff(owner),pending,"APPROVED");long job=pending.path("id").asLong();
  var draft=derive(owner,inspect(owner,job),null);fields.put("deadline",deadline.plusSeconds(120).toString());draft=save(owner,draft,fields);
  clock.now=deadline;
  http.perform(get(JOBS+"?status=EXPIRED").header("Authorization",owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1)).andExpect(jsonPath("$.data.content[0].status").value("EXPIRED"));
  http.perform(get(JOBS+"?status=PUBLISHED").header("Authorization",owner)).andExpect(jsonPath("$.data.totalElements").value(0));
  http.perform(get(JOBS+"/"+job+"/draft/preview").header("Authorization",owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.submissionEligible").value(false)).andExpect(jsonPath("$.data.validationIssues[?(@.code == 'TERMINAL_STATE')]").isNotEmpty());
  http.perform(put(JOBS+"/"+job+"/draft").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(saveBody(draft,fields))).andExpect(status().isConflict());
  http.perform(post(JOBS+"/"+job+"/revisions/"+draft.path("workingRevision").path("id").asLong()+"/submit").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(confirm(draft,true))).andExpect(status().isConflict());
  assertThat(database.queryForObject("SELECT status FROM job_postings WHERE id=?",String.class,job)).isEqualTo("PUBLISHED");
  var expired=inspect(owner,job);assertThat(expired.path("availableActions").toString()).doesNotContain("SAVE_DRAFT","SUBMIT","CREATE_DRAFT","RESUME");
 }

 @Test void retainedReplacementApprovalsPreservePausedClosedAndExpiredStates() throws Exception {
  for(String state:List.of("PAUSED","CLOSED","EXPIRED")) {
   String owner=account();long company=createdCompany(owner);database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
   var fields=complete();var deadline=clock.now.plusSeconds(30);fields.put("deadline",deadline.toString());fields.put("title","Original");
   var original=submit(owner,create(owner,fields));String staff=staff(owner);decide(staff,original,"APPROVED");long job=original.path("id").asLong();
   var draft=derive(owner,inspect(owner,job),null);fields.put("title","Retained replacement");fields.put("deadline",deadline.plusSeconds(120).toString());
   var replacement=submit(owner,save(owner,draft,fields));
   if(!state.equals("EXPIRED"))lifecycle(owner,replacement,state.equals("PAUSED")?"pause":"close").andExpect(status().isOk());
   else clock.now=deadline;
   // Lifecycle updates the job token, but never changes the pending revision token or evidence.
   var current=state.equals("EXPIRED")?replacement:inspect(owner,job);
   http.perform(get("/api/jobs/"+job)).andExpect(jsonPath("$.data.content.title").value("Original"));
   // Reload after public request-time expiry reconciliation as a real review client would.
   current=inspect(owner,job);
   http.perform(put("/api/staff/job-revisions/"+replacement.path("workingRevision").path("id").asLong()+"/decision").header("Authorization",staff)
    .contentType(MediaType.APPLICATION_JSON).content(decision(current,"APPROVED",null))).andExpect(status().isOk()).andExpect(jsonPath("$.data.publicationStatus").value(state));
   http.perform(get("/api/jobs/"+job)).andExpect(jsonPath("$.data.content.title").value("Retained replacement"))
    .andExpect(jsonPath("$.data.applicationsAvailable").value(false)).andExpect(jsonPath("$.data.applicationAvailabilityReason").value(state));
   var finalState=inspect(owner,job);assertThat(finalState.path("status").asText()).isEqualTo(state);
   if(!state.equals("PAUSED")) {
    http.perform(post(JOBS+"/"+job+"/draft").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(version(finalState))).andExpect(status().isConflict());
    lifecycle(owner,finalState,"resume").andExpect(status().isConflict());
   }
  }
 }
 @Test void terminalPendingReviewsCanBeWithdrawnOrRejectedAndExistingDraftStaysReadable() throws Exception {
  for(String outcome:List.of("WITHDRAWN","REJECTED")) {
   String owner=account();long company=createdCompany(owner);database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
   var original=submit(owner,create(owner,complete()));String staff=staff(owner);decide(staff,original,"APPROVED");long job=original.path("id").asLong();
   var replacement=submit(owner,derive(owner,inspect(owner,job),null));lifecycle(owner,replacement,"close").andExpect(status().isOk());var current=inspect(owner,job);
   assertThat(current.path("availableActions").toString()).contains("WITHDRAW").doesNotContain("SUBMIT","CREATE_DRAFT");
   if(outcome.equals("WITHDRAWN"))withdraw(owner,current);else decide(staff,current,"REJECTED");
   assertThat(history(owner,job).get(1).path("state").asText()).isEqualTo(outcome);
   assertThat(inspect(owner,job).path("status").asText()).isEqualTo("CLOSED");
  }
  String owner=account();long company=createdCompany(owner);database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
  var p=submit(owner,create(owner,complete()));decide(staff(owner),p,"APPROVED");long job=p.path("id").asLong();var draft=derive(owner,inspect(owner,job),null);
  lifecycle(owner,draft,"close").andExpect(status().isOk());var closed=inspect(owner,job);
  http.perform(put(JOBS+"/"+job+"/draft").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(saveBody(closed,complete()))).andExpect(status().isConflict());
  http.perform(get(JOBS+"/"+job+"/draft/preview").header("Authorization",owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.submissionEligible").value(false));
 }

 @Test void pausedDeadlineAndClosedDeadlineHaveDifferentWorkerOutcomes() throws Exception {
  for(String action:List.of("pause","close")) {
   String owner=account();long company=createdCompany(owner);database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
   var fields=complete();var deadline=clock.now.plusSeconds(30);fields.put("deadline",deadline.toString());
   var p=submit(owner,create(owner,fields));decide(staff(owner),p,"APPROVED");long job=p.path("id").asLong();
   lifecycle(owner,inspect(owner,job),action).andExpect(status().isOk());var prior=inspect(owner,job);clock.now=deadline;
   if(action.equals("pause")) {
    lifecycle(owner,prior,"resume").andExpect(status().isConflict());
    // The failed action rolls expiry back; the worker commits the durable fact independently.
    assertThat(database.queryForObject("SELECT status FROM job_postings WHERE id=?",String.class,job)).isEqualTo("PAUSED");
   }
   expiry.expireDue();expiry.expireDue();
   String expected=action.equals("pause")?"EXPIRED":"CLOSED";
   assertThat(inspect(owner,job).path("status").asText()).isEqualTo(expected);
   assertThat(database.queryForObject("SELECT count(*) FROM job_audit_events WHERE job_id=? AND event_type='JOB_EXPIRED'",Long.class,job)).isEqualTo(action.equals("pause")?1L:0L);
  }
 }
 @Autowired JobExpiryService expiry;
 @Test void competingPauseAndReviewAtOldDeadlineCannotExtendRecruitment() throws Exception {
  String owner=account(),moderator=staff(account());long company=createdCompany(owner);database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
  var fields=complete();var deadline=clock.now.plusSeconds(30);fields.put("deadline",deadline.toString());var p=submit(owner,create(owner,fields));decide(moderator,p,"APPROVED");long job=p.path("id").asLong();
  var draft=derive(owner,inspect(owner,job),null);fields.put("deadline",deadline.plusSeconds(120).toString());var replacement=submit(owner,save(owner,draft,fields));
  String reviewBody=decision(replacement,"APPROVED",null),pauseBody=version(replacement);long revision=replacement.path("workingRevision").path("id").asLong();
  try(var connection=database.getDataSource().getConnection();var workers=Executors.newFixedThreadPool(2)) {
   connection.setAutoCommit(false);try(var lock=connection.prepareStatement("SELECT id FROM companies WHERE id=? FOR UPDATE")){lock.setLong(1,company);lock.executeQuery().close();}
   var pause=workers.submit(()->http.perform(post(JOBS+"/"+job+"/pause").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(pauseBody)).andReturn().getResponse().getStatus());
   var approval=workers.submit(()->http.perform(put("/api/staff/job-revisions/"+revision+"/decision").header("Authorization",moderator).contentType(MediaType.APPLICATION_JSON).content(reviewBody)).andReturn().getResponse().getStatus());
   try{awaitLocks(2);clock.now=deadline;connection.commit();}finally{connection.rollback();}
   assertThat(pause.get(10,TimeUnit.SECONDS)).isEqualTo(409);
   assertThat(approval.get(10,TimeUnit.SECONDS)).isEqualTo(200);
  }
  http.perform(get("/api/jobs/"+job)).andExpect(jsonPath("$.data.publicationStatus").value("EXPIRED")).andExpect(jsonPath("$.data.applicationsAvailable").value(false));
  assertThat(database.queryForObject("SELECT count(*) FROM job_audit_events WHERE job_id=? AND event_type='JOB_EXPIRED'",Long.class,job)).isEqualTo(1);
 }
 void awaitLocks(int count)throws Exception {
  long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
  while(database.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",Integer.class)<count){if(System.nanoTime()>until)throw new AssertionError("Requests did not reach Company lock");Thread.sleep(10);}
 }
 @Test void lifecycleAuditFailureRollsBackPublicationStateWithoutTouchingPendingHistory() throws Exception {
  String owner=account();long company=createdCompany(owner);database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
  var p=submit(owner,create(owner,complete()));decide(staff(owner),p,"APPROVED");long job=p.path("id").asLong();var replacement=submit(owner,derive(owner,inspect(owner,job),null));var before=history(owner,job);
  database.execute("CREATE FUNCTION gw78_lifecycle_fail() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'controlled failure'; END; $$ LANGUAGE plpgsql");
  database.execute("CREATE TRIGGER gw78_lifecycle_fail BEFORE INSERT ON job_audit_events FOR EACH ROW WHEN (NEW.job_id="+job+" AND NEW.event_type='JOB_PAUSED') EXECUTE FUNCTION gw78_lifecycle_fail()");
  try{lifecycle(owner,replacement,"pause").andExpect(status().isInternalServerError());}
  finally{database.execute("DROP TRIGGER gw78_lifecycle_fail ON job_audit_events");database.execute("DROP FUNCTION gw78_lifecycle_fail()");}
  assertThat(inspect(owner,job).path("status").asText()).isEqualTo("PUBLISHED");assertThat(history(owner,job)).isEqualTo(before);
 }

 @Test void lifecycleRechecksMembershipVersionsAndResumeCompanyEligibility() throws Exception {
  String owner=account(),recruiter=account(),outsider=account(),otherOwner=account();long company=createdCompany(owner);createdCompany(otherOwner);long member=recruiter(company,recruiter);
  database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);var p=submit(recruiter,create(recruiter,complete()));decide(staff(owner),p,"APPROVED");long job=p.path("id").asLong();var published=inspect(owner,job);
  lifecycle(outsider,published,"pause").andExpect(status().isNotFound());lifecycle(otherOwner,published,"pause").andExpect(status().isNotFound());
  http.perform(post(JOBS+"/"+job+"/pause").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content("{}"))
   .andExpect(status().isBadRequest());
  lifecycle(recruiter,published,"pause").andExpect(status().isOk());lifecycle(owner,published,"close").andExpect(status().isConflict());var paused=inspect(owner,job);
  lifecycle(owner,paused,"pause").andExpect(status().isConflict());
  database.update("UPDATE companies SET verification_status='PENDING' WHERE id=?",company);
  lifecycle(owner,paused,"resume").andExpect(status().isConflict());assertThat(inspect(owner,job).path("availableActions").toString()).doesNotContain("RESUME");
  database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
  http.perform(delete(JOBS+"/"+job+"/assignments/"+member+"?expectedJobVersion="+paused.path("version").asLong()).header("Authorization",owner)).andExpect(status().isOk());
  lifecycle(recruiter,inspect(owner,job),"close").andExpect(status().isNotFound());
  database.update("UPDATE company_memberships SET role='ADMIN' WHERE id=?",member);
  lifecycle(recruiter,inspect(owner,job),"close").andExpect(status().isForbidden());
  var draft=create(owner,complete());lifecycle(owner,draft,"close").andExpect(status().isConflict());
  assertThat(draft.path("availableActions").toString()).contains("PREVIEW","SAVE_DRAFT","SUBMIT");
 }
 @Test void finalResponsibleLossPreservesPublicationAndBlocksReplacementUntilResponsibilityReturns() throws Exception {
  String owner=account(),recruiter=account();long company=createdCompany(owner),member=recruiter(company,recruiter);database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
  var first=submit(recruiter,create(recruiter,complete()));String staff=staff(owner);decide(staff,first,"APPROVED");long job=first.path("id").asLong();
  var replacement=submit(owner,derive(owner,inspect(owner,job),null));
  http.perform(delete(JOBS+"/"+job+"/assignments/"+member+"?expectedJobVersion="+replacement.path("version").asLong()).header("Authorization",owner)).andExpect(status().isOk());
  var current=inspect(owner,job);assertThat(current.path("needsReassignment").asBoolean()).isTrue();
  http.perform(get("/api/jobs/"+job)).andExpect(jsonPath("$.data.applicationsAvailable").value(true));
  http.perform(put("/api/staff/job-revisions/"+replacement.path("workingRevision").path("id").asLong()+"/decision").header("Authorization",staff).contentType(MediaType.APPLICATION_JSON).content(decision(current,"APPROVED",null)))
   .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(Matchers.containsString("RESPONSIBILITY_REQUIRED")));
  http.perform(put(JOBS+"/"+job+"/owner-responsibility").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
   .content(mapper.writeValueAsString(Map.of("expectedJobVersion",current.path("version").asLong(),"responsible",true)))).andExpect(status().isOk());
  decide(staff,inspect(owner,job),"APPROVED");
 }

 @Test void withdrawingRetainedReviewAtDeadlineCommitsExpiryAndKeepsHistory() throws Exception {
  String owner=account();long company=createdCompany(owner);database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
  var fields=complete();var deadline=clock.now.plusSeconds(30);fields.put("deadline",deadline.toString());var first=submit(owner,create(owner,fields));decide(staff(owner),first,"APPROVED");long job=first.path("id").asLong();
  var draft=derive(owner,inspect(owner,job),null);fields.put("deadline",deadline.plusSeconds(120).toString());var replacement=submit(owner,save(owner,draft,fields));
  lifecycle(owner,replacement,"pause").andExpect(status().isOk());var paused=inspect(owner,job);clock.now=deadline;withdraw(owner,paused);
  assertThat(database.queryForObject("SELECT status FROM job_postings WHERE id=?",String.class,job)).isEqualTo("EXPIRED");
  assertThat(history(owner,job).get(1).path("state").asText()).isEqualTo("WITHDRAWN");
 }
 String version(JsonNode job)throws Exception {return mapper.writeValueAsString(Map.of("expectedJobVersion",job.path("version").asLong()));}
 ResultActions lifecycle(String actor,JsonNode job,String action)throws Exception {
  return http.perform(post(JOBS+"/"+job.path("id").asLong()+"/"+action).header("Authorization",actor).contentType(MediaType.APPLICATION_JSON).content(version(job)));
 }
}
