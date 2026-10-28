package com.guardwork.backend.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "company.membership.notifications.worker-enabled=false")
class JobOnboardingBypassHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate database;

    @Test void verifiedMembersIgnoreHistoricalPersonalProfilesAndStillRespectCompanySuspension() throws Exception {
        var fixture = new JobDraftHttpTest();
        fixture.context = context;
        fixture.mapper = mapper;
        fixture.database = database;
        fixture.setup();
        String owner = fixture.account(), recruiter = fixture.account();
        long company = fixture.createdCompany(owner);
        long member = fixture.recruiter(company, recruiter);
        for (String actor : new String[] {owner, recruiter}) {
            database.update("INSERT INTO employer_profiles(user_id,applicant,full_name) VALUES (?,false,'Unverified member')",
                    fixture.userId(actor));
        }
        for (String actor : new String[] {owner, recruiter}) {
            fixture.http.perform(get("/api/auth/me").header("Authorization", actor))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.emailVerified").value(true))
                    .andExpect(jsonPath("$.data.recruitingEmailEligible").value(true));
        }
        var draft = fixture.create(owner, Map.of());
        fixture.http.perform(put(JobDraftHttpTest.JOBS + "/" + draft.path("id").asLong() + "/assignments/" + member)
                .header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("expectedJobVersion", draft.path("version").asLong()))))
                .andExpect(status().isOk());
        fixture.http.perform(get(JobDraftHttpTest.JOBS + "/" + draft.path("id").asLong())
                .header("Authorization", recruiter)).andExpect(status().isOk());
        fixture.http.perform(get("/api/companies/me/invitations").header("Authorization", owner))
                .andExpect(status().isOk());
        database.update("UPDATE companies SET is_banned=true WHERE id=?", company);
        fixture.http.perform(get(JobDraftHttpTest.JOBS).header("Authorization", recruiter))
                .andExpect(status().isNotFound());
    }
}
