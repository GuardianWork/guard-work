package com.guardwork.backend.job;

import static com.guardwork.backend.CompanyDossierFixtures.completeDossier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.Assertions;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "company.membership.notifications.worker-enabled=false")
@ActiveProfiles("test")
@Import(JobSubmissionHttpTest.TimeConfig.class)
class JobSubmissionHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate database;
    @Autowired MutableClock clock;
    MockMvc http;
    static final String JOBS = "/api/companies/me/jobs";
    @BeforeEach void setup() {
        clock.now=Instant.now();
        http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @Test void previewReportsIncompleteContentWithoutChangingDraft() throws Exception {
        String owner = account(); createdCompany(owner); var draft = create(owner, Map.of("title", "Bảo vệ"));
        http.perform(get(JOBS + "/" + draft.path("id").asLong() + "/draft/preview").header("Authorization", owner))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.content.title").value("Bảo vệ"))
            .andExpect(jsonPath("$.data.submissionEligible").value(false))
            .andExpect(jsonPath("$.data.validationIssues[0].field").value("content.description"))
            .andExpect(jsonPath("$.data.jobVersion").value(draft.path("version").asLong()));
        http.perform(get(JOBS + "/" + draft.path("id").asLong()).header("Authorization", owner))
            .andExpect(jsonPath("$.data.workingRevision.state").value("DRAFT"))
            .andExpect(jsonPath("$.data.version").value(draft.path("version").asLong()));
    }

    @Test void removedNarrativeSectionsAreRejectedFromJobContent() throws Exception {
        String owner = account();
        createdCompany(owner);
        for (String field : List.of("responsibilities", "requiredQualifications", "preferredQualifications",
                "experienceExpectations", "benefits", "applicationInstructions")) {
            var content = new LinkedHashMap<String, Object>();
            content.put("title", "Security Officer");
            content.put("description", "Responsibilities and qualifications are included here.");
            content.put("employmentType", "FULL_TIME");
            content.put("workArrangement", "ONSITE");
            content.put("location", "Hồ Chí Minh");
            content.put("deadline", "2099-12-01T12:00:00+07:00");
            content.put(field, "Legacy narrative field");
            http.perform(post(JOBS).header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of("content", content))))
                .andExpect(status().isBadRequest());
        }
    }

    @Test void eachRequiredPostingFieldIsReportedByPreviewAndRejectedAtSubmission() throws Exception {
        String owner = account();
        long company = createdCompany(owner);
        database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?", company);
        for (String field : List.of("title", "description", "employmentType", "workArrangement", "location", "deadline")) {
            var content = complete();
            content.remove(field);
            var draft = create(owner, content);
            long job = draft.path("id").asLong();
            long revision = draft.path("workingRevision").path("id").asLong();
            http.perform(get(JOBS + "/" + job + "/draft/preview").header("Authorization", owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.submissionEligible").value(false))
                .andExpect(jsonPath("$.data.validationIssues[?(@.field == 'content." + field + "')]").isNotEmpty());
            http.perform(post(JOBS + "/" + job + "/revisions/" + revision + "/submit")
                    .header("Authorization", owner).contentType(MediaType.APPLICATION_JSON).content(confirm(draft, true)))
                .andExpect(status().isBadRequest());
        }
        var content = complete();
        content.put("description", " \n ");
        var draft = create(owner, content);
        http.perform(get(JOBS + "/" + draft.path("id").asLong() + "/draft/preview").header("Authorization", owner))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.validationIssues[?(@.field == 'content.description')]").isNotEmpty());
        http.perform(post(JOBS + "/" + draft.path("id").asLong() + "/revisions/"
                + draft.path("workingRevision").path("id").asLong() + "/submit")
                .header("Authorization", owner).contentType(MediaType.APPLICATION_JSON).content(confirm(draft, true)))
            .andExpect(status().isBadRequest());
    }

    @Test void exactConfirmedRevisionIsSubmittedOnceAndWithdrawalPreservesHistory() throws Exception {
        String owner=account(); long company=createdCompany(owner);
        database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
        var draft=create(owner,complete()); long job=draft.path("id").asLong(), revision=draft.path("workingRevision").path("id").asLong();
        String route=JOBS+"/"+job+"/revisions/"+revision;
        http.perform(get(JOBS+"/"+job+"/draft/preview").header("Authorization",owner))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.submissionEligible").value(true))
            .andExpect(jsonPath("$.data.content.description").value("Dòng một\nDòng hai"));
        http.perform(post(route+"/submit").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
            .content(confirm(draft,false))).andExpect(status().isBadRequest());
        var pending=mapper.readTree(http.perform(post(route+"/submit").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
            .content(confirm(draft,true))).andExpect(status().isOk()).andExpect(jsonPath("$.data.workingRevision.state").value("PENDING_REVIEW"))
            .andExpect(jsonPath("$.data.publishedRevisionId").isEmpty()).andReturn().getResponse().getContentAsString()).path("data");
        http.perform(post(route+"/submit").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
            .content(confirm(draft,true))).andExpect(status().isConflict());
        http.perform(put(JOBS+"/"+job+"/draft").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
            .content(saveBody(pending,Map.of("title","Changed")))).andExpect(status().isConflict());
        Assertions.assertThatThrownBy(() -> database.update("UPDATE job_posting_revisions SET content='{}' WHERE id=?",revision))
            .isInstanceOf(DataAccessException.class);
        String staff=account(); long staffId=userId(staff);
        database.update("UPDATE users SET role='STAFF' WHERE id=?",staffId);
        // Tokens must reflect the newly granted platform role; the live service check is additional protection.
        http.perform(get("/api/staff/job-revisions").header("Authorization",owner)).andExpect(status().isForbidden());
        String login=http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("usernameOrEmail",database.queryForObject("SELECT email FROM users WHERE id=?",String.class,staffId),"password","Password123"))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        staff="Bearer "+mapper.readTree(login).path("data").path("accessToken").asText();
        http.perform(get("/api/staff/job-revisions/"+revision).header("Authorization",staff)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content.description").value("Dòng một\nDòng hai"))
            .andExpect(jsonPath("$.data.approvalEligible").value(true));
        var editable=create(owner,Map.of());
        http.perform(get("/api/staff/job-revisions/"+editable.path("workingRevision").path("id").asLong()).header("Authorization",staff))
            .andExpect(status().isNotFound());
        http.perform(get("/api/staff/jobs/"+job+"/revisions").header("Authorization",staff))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        http.perform(get("/api/staff/job-revisions?status=DRAFT").header("Authorization",staff)).andExpect(status().isBadRequest());
        http.perform(get("/api/staff/job-revisions?status=ALL&size=1").header("Authorization",staff)).andExpect(status().isOk());
        http.perform(post(route+"/withdraw").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
            .content(confirm(pending,true))).andExpect(status().isOk()).andExpect(jsonPath("$.data.workingRevision").isEmpty());
        http.perform(get(JOBS+"/"+job+"/draft/preview").header("Authorization",owner)).andExpect(status().isConflict());
        http.perform(get("/api/staff/job-revisions/"+revision).header("Authorization",staff)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.state").value("WITHDRAWN")).andExpect(jsonPath("$.data.withdrawnAt").isNotEmpty());
        http.perform(get("/api/staff/job-revisions?status=WITHDRAWN").header("Authorization",staff)).andExpect(status().isOk());
        assertThat(database.queryForObject("SELECT state FROM job_posting_revisions WHERE id=?",String.class,revision)).isEqualTo("WITHDRAWN");
        assertThat(database.queryForObject("SELECT count(*) FROM job_posting_revisions WHERE job_id=?",Long.class,job)).isEqualTo(1);
        assertThat(database.queryForObject("SELECT count(*) FROM job_audit_events WHERE job_id=? AND event_type IN ('JOB_REVISION_SUBMITTED','JOB_REVISION_WITHDRAWN')",Long.class,job)).isEqualTo(2);
    }

    @Test void submissionRejectsIncompleteCompensationQuestionsAndCurrentEligibility() throws Exception {
        String owner=account(); long company=createdCompany(owner); var draft=create(owner,complete());
        String route=JOBS+"/"+draft.path("id").asLong()+"/revisions/"+draft.path("workingRevision").path("id").asLong()+"/submit";
        http.perform(post(route).header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(confirm(draft,true)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(Matchers.containsString("COMPANY_VERIFICATION_REQUIRED")));
        database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
        database.update("UPDATE job_postings SET owner_responsible=false WHERE id=?",draft.path("id").asLong());
        http.perform(post(route).header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(confirm(draft,true)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(Matchers.containsString("JOB_RESPONSIBILITY_REQUIRED")));
        for (var fields:List.of(Map.<String,Object>of("compensation",Map.of("disclosed",false,"minimum",1)),
            Map.<String,Object>of("compensation",Map.of("disclosed",true,"minimum",5,"maximum",1,"currency","USD","payPeriod","MONTH")),
            Map.<String,Object>of("compensation",Map.of("disclosed",true,"minimum",1.5,"maximum",2,"currency","VND","payPeriod","MONTH")),
            Map.<String,Object>of("screeningQuestions",List.of(Map.of("key",UUID.randomUUID().toString(),"type","YES_NO","prompt","Question?","required",true,"options",List.of("a","b")))),
            Map.<String,Object>of("screeningQuestions",List.of(Map.of("type","SINGLE_CHOICE","prompt","Question?"))))) {
          var invalid=complete(); invalid.putAll(fields); var d=create(owner,invalid);
          http.perform(post(JOBS+"/"+d.path("id").asLong()+"/revisions/"+d.path("workingRevision").path("id").asLong()+"/submit")
            .header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(confirm(d,true))).andExpect(status().isBadRequest());
        }
    }

    @Test void stalePreviewCannotSubmitEditedContentAndForeignRevisionsRemainConcealed() throws Exception {
        String owner=account(),other=account(); long company=createdCompany(owner);createdCompany(other);
        database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
        var d=create(owner,complete());var foreign=create(other,complete());long job=d.path("id").asLong(),revision=d.path("workingRevision").path("id").asLong();
        var edited=complete();edited.put("title","Changed after preview");
        http.perform(put(JOBS+"/"+job+"/draft").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(saveBody(d,edited))).andExpect(status().isOk());
        http.perform(post(JOBS+"/"+job+"/revisions/"+revision+"/submit").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(confirm(d,true)))
            .andExpect(status().isConflict());
        http.perform(post(JOBS+"/"+job+"/revisions/"+foreign.path("workingRevision").path("id").asLong()+"/submit")
            .header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isNotFound());
        http.perform(get(JOBS+"/"+job+"/draft/preview").header("Authorization",other)).andExpect(status().isNotFound());
        http.perform(get(JOBS+"/"+job).header("Authorization",owner)).andExpect(jsonPath("$.data.workingRevision.state").value("DRAFT"))
            .andExpect(jsonPath("$.data.workingRevision.content.title").value("Changed after preview"));
    }

    Map<String,Object> complete() {
        var c=new LinkedHashMap<String,Object>();
        c.put("title", "Bảo vệ");
        c.put("description", "Dòng một\nDòng hai");
        c.put("location", "Hồ Chí Minh");
        c.put("employmentType", "FULL_TIME");c.put("workArrangement", "ONSITE");c.put("headcount",2);
        var questions=new ArrayList<Map<String,Object>>();
        for(String type:List.of("SHORT_TEXT","SINGLE_CHOICE","YES_NO","NUMERIC")) {
          var q=new LinkedHashMap<String,Object>();q.put("key",UUID.randomUUID().toString());q.put("type",type);
          q.put("prompt","Câu hỏi "+type);q.put("required",true);
          if(type.equals("SINGLE_CHOICE"))q.put("options",List.of("Một","Hai"));questions.add(q);
        }
        c.put("screeningQuestions",questions);
        c.put("deadline","2099-12-01T12:00:00+07:00");c.put("compensation",Map.of("disclosed",false));return c;
    }
    String confirm(JsonNode d,boolean confirmed)throws Exception {
        return mapper.writeValueAsString(Map.of("expectedJobVersion",d.path("version").asLong(),"expectedRevisionVersion",d.path("workingRevision").path("version").asLong(),"confirmed",confirmed));
    }

    @Test void deadlineAtExactInstantFailsAndCompetingConfirmedSubmissionsHaveOneWinner() throws Exception {
        String owner=account(),recruiter=account(); long company=createdCompany(owner); recruiter(company,recruiter);
        database.update("UPDATE companies SET verification_status='VERIFIED' WHERE id=?",company);
        var c=complete(); c.put("deadline",clock.now.toString()); var expired=create(owner,c);
        http.perform(post(JOBS+"/"+expired.path("id").asLong()+"/revisions/"+expired.path("workingRevision").path("id").asLong()+"/submit")
            .header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(confirm(expired,true)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(Matchers.containsString("JOB_DEADLINE_PASSED")));
        var d=create(recruiter,complete()); long job=d.path("id").asLong();
        String route=JOBS+"/"+job+"/revisions/"+d.path("workingRevision").path("id").asLong()+"/submit", body=confirm(d,true);
        try(var connection=database.getDataSource().getConnection();var workers=Executors.newFixedThreadPool(2)) {
            connection.setAutoCommit(false);
            try(var lock=connection.prepareStatement("SELECT id FROM companies WHERE id=? FOR UPDATE")){lock.setLong(1,company);lock.executeQuery().close();}
            var first=workers.submit(()->http.perform(post(route).header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus());
            var second=workers.submit(()->http.perform(post(route).header("Authorization",recruiter).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus());
            try {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(database.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",Integer.class)<2) {
                if(System.nanoTime()>until) throw new AssertionError("Expected competing requests to wait for Company lock");
                Thread.sleep(10);
            }
            connection.commit();
            } finally { connection.rollback(); }
            assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS)))
              .containsExactlyInAnyOrder(200,409);
        }
        assertThat(database.queryForObject("SELECT count(*) FROM job_audit_events WHERE job_id=? AND event_type='JOB_REVISION_SUBMITTED'",Long.class,job)).isEqualTo(1);
    }
    @Test void staffQueueSearchesRevisionTitlesAndCompanyNamesBeforePagination() throws Exception {
        String owner = account();
        long actor = userId(owner);
        long company = createdCompany(owner);
        String marker = UUID.randomUUID().toString();
        String companyName = "Công ty Ánh Dương " + marker;
        database.update("UPDATE companies SET name=? WHERE id=?", companyName, company);
        String otherOwner = account();
        long otherActor = userId(otherOwner);
        long otherCompany = createdCompany(otherOwner);
        Instant submitted = Instant.parse("2026-11-01T00:00:00Z");
        var first = queueFixture(company, actor, "Kế toán " + marker + " first", "PENDING_REVIEW", submitted);
        var second = queueFixture(otherCompany, otherActor, "KẾ TOÁN " + marker + " second", "PENDING_REVIEW", submitted);
        var approved = queueFixture(company, actor, "Kế toán " + marker, "APPROVED", submitted.minusSeconds(1));
        var rejected = queueFixture(company, actor, "Kế toán " + marker, "REJECTED", submitted.plusSeconds(2));
        var withdrawn = queueFixture(company, actor, "Kế toán " + marker, "WITHDRAWN", submitted.plusSeconds(3));
        queueFixture(company, actor, "Kế toán " + marker, "DRAFT", submitted);
        String staff = queueStaff(owner);
        String route = "/api/staff/job-revisions";
        http.perform(get(route).header("Authorization", staff).param("q", "  ke TOAN " + marker + "  ").param("size", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2))
            .andExpect(jsonPath("$.data.totalPages").value(2))
            .andExpect(jsonPath("$.data.content[0].id").value(first.revisionId()))
            .andExpect(jsonPath("$.data.content[0].companyId").value(company))
            .andExpect(jsonPath("$.data.content[0].companyName").value(companyName));
        http.perform(get(route).header("Authorization", staff).param("q", "ke toan " + marker).param("size", "1").param("page", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2))
            .andExpect(jsonPath("$.data.content[0].id").value(second.revisionId()));
        // The second match is outside page zero, yet a narrower search must find it on page zero.
        http.perform(get(route).header("Authorization", staff).param("q", marker + " second").param("size", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.totalPages").value(1))
            .andExpect(jsonPath("$.data.content[0].id").value(second.revisionId()));
        http.perform(get(route).header("Authorization", staff).param("q", "anh DUONG " + marker).param("status", "ALL"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(4))
            .andExpect(jsonPath("$.data.content[*].id").value(Matchers.contains(
                (int) approved.revisionId(), (int) first.revisionId(), (int) rejected.revisionId(), (int) withdrawn.revisionId())))
            .andExpect(jsonPath("$.data.content[?(@.state == 'DRAFT')]").isEmpty());
        http.perform(get(route).header("Authorization", staff).param("q", "anh duong " + marker).param("status", "APPROVED"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].id").value(approved.revisionId()));
        http.perform(get(route).header("Authorization", staff).param("q", "ke toan " + marker).param("page", "10").param("size", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2))
            .andExpect(jsonPath("$.data.content").isEmpty());
        http.perform(get(route + "/" + first.revisionId()).header("Authorization", staff))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.companyName").value(companyName));
        http.perform(get("/api/staff/jobs/" + first.jobId() + "/revisions").header("Authorization", staff))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].companyName").value(companyName));
    }

    @Test void staffQueueSearchUsesEachRevisionsOwnTitle() throws Exception {
        String owner = account();
        long actor = userId(owner);
        long company = createdCompany(owner);
        String marker = UUID.randomUUID().toString();
        var previous = queueFixture(company, actor, "Old title " + marker, "APPROVED", clock.instant());
        long pending = database.queryForObject("""
            INSERT INTO job_posting_revisions(job_id,revision_number,content,state,creator_user_id,created_at,
                submitter_user_id,submitted_at)
            VALUES (?,2,?::jsonb,'PENDING_REVIEW',?,now(),?,now()) RETURNING id
            """, Long.class, previous.jobId(), mapper.writeValueAsString(Map.of("title", "New title " + marker)), actor, actor);
        String staff = queueStaff(owner);
        http.perform(get("/api/staff/job-revisions").header("Authorization", staff).param("q", "Old title " + marker))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
        http.perform(get("/api/staff/job-revisions").header("Authorization", staff).param("q", "New title " + marker))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].id").value(pending));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"%", "_", "\\", "'"})
    void staffQueueSearchTreatsSqlPatternCharactersLiterally(String literal) throws Exception {
        String owner = account();
        long actor = userId(owner);
        long company = createdCompany(owner);
        String marker = UUID.randomUUID().toString();
        var exact = queueFixture(company, actor, marker + literal, "PENDING_REVIEW", clock.instant());
        queueFixture(company, actor, marker + "x", "PENDING_REVIEW", clock.instant());
        http.perform(get("/api/staff/job-revisions").header("Authorization", queueStaff(owner)).param("q", marker + literal))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].id").value(exact.revisionId()));
    }

    @Test void staffQueueValidatesTrimmedUnicodeSearchWithoutChangingBlankQueriesOrAuthority() throws Exception {
        String owner = account();
        http.perform(get("/api/staff/job-revisions").param("q", "ke toan")).andExpect(status().isUnauthorized());
        http.perform(get("/api/staff/job-revisions").header("Authorization", owner).param("q", "ke toan"))
            .andExpect(status().isForbidden());
        String staff = queueStaff(owner);
        String route = "/api/staff/job-revisions";
        String before = http.perform(get(route).header("Authorization", staff)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String blank = http.perform(get(route).header("Authorization", staff).param("q", " \t\n\u2003 "))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(blank).path("data")).isEqualTo(mapper.readTree(before).path("data"));
        for (String q : List.of("x".repeat(200), "  " + "x".repeat(200) + "  ", "🙂".repeat(200))) {
            http.perform(get(route).header("Authorization", staff).param("q", q)).andExpect(status().isOk());
        }
        for (String q : List.of("x".repeat(201), "🙂".repeat(201))) {
            http.perform(get(route).header("Authorization", staff).param("q", q)).andExpect(status().isBadRequest());
        }
        database.update("UPDATE users SET status='SUSPENDED' WHERE id=?", userId(staff));
        http.perform(get(route).header("Authorization", staff).param("q", "ke toan")).andExpect(status().isUnauthorized());
    }

    record QueueFixture(long jobId, long revisionId) {}

    QueueFixture queueFixture(long company, long actor, String title, String state, Instant submitted) throws Exception {
        long job = database.queryForObject("""
            INSERT INTO job_postings(company_id,creator_user_id,status,owner_responsible,created_at,updated_at)
            VALUES (?,?,'DRAFT',true,now(),now()) RETURNING id
            """, Long.class, company, actor);
        boolean draft = "DRAFT".equals(state);
        boolean decided = "APPROVED".equals(state) || "REJECTED".equals(state);
        boolean withdrawn = "WITHDRAWN".equals(state);
        long revision = database.queryForObject("""
            INSERT INTO job_posting_revisions(job_id,revision_number,content,state,creator_user_id,created_at,
                submitter_user_id,submitted_at,reviewer_user_id,decided_at,rejection_reason,withdrawer_user_id,withdrawn_at)
            VALUES (?,1,?::jsonb,?,?,now(),?,?::timestamptz,?,?::timestamptz,?,?,?::timestamptz) RETURNING id
            """, Long.class, job, mapper.writeValueAsString(Map.of("title", title)), state, actor,
            draft ? null : actor, draft ? null : submitted.toString(), decided ? actor : null,
            decided ? submitted.toString() : null, "REJECTED".equals(state) ? "Fixture rejection reason" : null,
            withdrawn ? actor : null, withdrawn ? submitted.toString() : null);
        return new QueueFixture(job, revision);
    }

    String queueStaff(String account) throws Exception {
        long actor = userId(account);
        database.update("UPDATE users SET role='STAFF' WHERE id=?", actor);
        String email = database.queryForObject("SELECT email FROM users WHERE id=?", String.class, actor);
        String response = http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("usernameOrEmail", email, "password", "Password123"))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + mapper.readTree(response).path("data").path("accessToken").asText();
    }

    @TestConfiguration static class TimeConfig {
        @Bean @Primary MutableClock jobTestClock(){return new MutableClock();}
    }
    static class MutableClock extends Clock {
        volatile Instant now=Instant.now();
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    }

    long recruiter(long company, String token) throws Exception {
        return database.queryForObject("INSERT INTO company_memberships(company_id,user_id,role,status,joined_at) VALUES (?,?,'RECRUITER','ACTIVE',now()) RETURNING id",
                Long.class, company, userId(token));
    }

    JsonNode create(String actor, Map<String, Object> content) throws Exception {
        return mapper.readTree(http.perform(post(JOBS).header("Authorization", actor)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("content", content))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data");
    }
    String saveBody(JsonNode detail, Map<String, Object> content) throws Exception {
        return mapper.writeValueAsString(Map.of("expectedJobVersion", detail.path("version").asLong(),
                "expectedRevisionVersion", detail.path("workingRevision").path("version").asLong(), "content", content));
    }

    long userId(String token) throws Exception {
        String body = http.perform(get("/api/auth/me").header("Authorization", token)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("data").path("id").asLong();
    }

    long createdCompany(String token) throws Exception {
        String body = http.perform(completeDossier("POST", "/api/companies/verification-request", company(UUID.randomUUID().toString())).header("Authorization", token))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("data").path("id").asLong();
    }

    String account() throws Exception {
        String name = "user_" + UUID.randomUUID().toString().replace("-", "");
        String email = name + "@example.vn";
        http.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("firstName", "Ánh", "lastName", "Nguyễn", "username", name,
                                "email", email, "password", "Password123"))))
                .andExpect(status().isCreated());
        database.update("UPDATE users SET email_verified=TRUE WHERE email=?", email);
        String response = http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("usernameOrEmail", email, "password", "Password123"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + mapper.readTree(response).path("data").path("accessToken").asText();
    }

    String company(String taxCode) throws Exception {
        return mapper.writeValueAsString(Map.of("name", "Công ty Giải Pháp Ánh Dương", "taxCode", taxCode,
                "email", taxCode + "@company.vn", "registrationCertificateUrl", "https://storage.guardwork.vn/giấy-phép.pdf"));
    }
}
