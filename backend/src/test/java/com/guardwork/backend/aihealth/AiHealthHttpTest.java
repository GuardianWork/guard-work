package com.guardwork.backend.aihealth;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class AiHealthHttpTest {
  static final String PATH = "/api/ai/health";
  @Autowired WebApplicationContext context;
  @MockitoBean AiHealthService service;
  MockMvc http;

  @BeforeEach void setup() {
    http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test void anonymousCallerReceivesReadinessInTheExistingEnvelopeWithoutCaching() throws Exception {
    when(service.check()).thenReturn(view(AiHealthService.Status.READY));
    http.perform(get(PATH))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.status").value(200))
        .andExpect(jsonPath("$.data.status").value("READY"))
        .andExpect(jsonPath("$.data.mode").value("crawler"))
        .andExpect(jsonPath("$.data.profile").value("test-v1"))
        .andExpect(jsonPath("$.data.legalSnapshot").value("labor-test-v1"))
        .andExpect(jsonPath("$.data.sharedKey").doesNotExist())
        .andExpect(jsonPath("$.data.adapterUrl").doesNotExist());
    verify(service).check();
  }

  @Test void notReadyStatesReturn503WithSafeDiagnostics() throws Exception {
    for (var state : new AiHealthService.Status[] {AiHealthService.Status.DISABLED,
        AiHealthService.Status.UNAVAILABLE, AiHealthService.Status.CONFIGURATION_MISMATCH}) {
      when(service.check()).thenReturn(view(state));
      http.perform(get(PATH))
          .andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control", "no-store"))
          .andExpect(jsonPath("$.status").value(503))
          .andExpect(jsonPath("$.message").value("50322 AI_SERVICE_NOT_READY"))
          .andExpect(jsonPath("$.data.status").value(state.name()));
    }
  }

  @Test void signedInUsersAndStaffCanAlsoCheckReadiness() throws Exception {
    when(service.check()).thenReturn(view(AiHealthService.Status.READY));
    for (String role : new String[] {"USER", "STAFF"}) {
      http.perform(get(PATH).with(user("health-check").roles(role)))
          .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("READY"));
    }
    verify(service, times(2)).check();
  }

  @Test void onlyTheExactHealthGetIsPublicAndProtectedRoutesStayProtected() throws Exception {
    http.perform(post(PATH)).andExpect(status().isUnauthorized());
    http.perform(get(PATH + "/other")).andExpect(status().isUnauthorized());
    http.perform(get("/api/staff/ai/health")).andExpect(status().isUnauthorized());
    http.perform(get("/api/staff/job-revisions")).andExpect(status().isUnauthorized());
    http.perform(post("/api/candidates/me/legal-chat").contentType(MediaType.APPLICATION_JSON)
        .content("{\"question\":\"Test\"}")).andExpect(status().isUnauthorized());
    verifyNoInteractions(service);
  }

  @Test void swaggerDocumentsThePublicRouteWithoutBearerAuthorization() throws Exception {
    http.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/ai/health'].get.summary").value("Check AI service readiness"))
        .andExpect(jsonPath("$.paths['/api/ai/health'].get.responses['200']").exists())
        .andExpect(jsonPath("$.paths['/api/ai/health'].get.responses['503']").exists())
        .andExpect(jsonPath("$.paths['/api/ai/health'].get.security").doesNotExist())
        .andExpect(jsonPath("$.paths['/api/staff/ai/health']").doesNotExist())
        .andExpect(jsonPath("$.components.securitySchemes.staffAiBearer").doesNotExist());
    verifyNoInteractions(service);
  }

  AiHealthService.View view(AiHealthService.Status status) {
    return new AiHealthService.View(status, "crawler", "test-v1", "labor-test-v1");
  }

}
