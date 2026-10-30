package com.guardwork.backend;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/** Complete dossier fixtures for pre-existing Company/membership/job test scenarios. */
public final class CompanyDossierFixtures {
    private static final ObjectMapper JSON = new ObjectMapper();
    private CompanyDossierFixtures() {}

    public static MockMultipartHttpServletRequestBuilder completeDossier(String method, String path, String body) throws Exception {
        var form = JSON.readValue(body, new TypeReference<LinkedHashMap<String,Object>>() {});
        // Older fixtures supplied contact/URL fields. The replacement API derives contact and stores actual bytes.
        form.remove("email");
        Object previousDocument = form.remove("registrationCertificateUrl");
        if ("PUT".equals(method)) {
            form.remove("taxCode");
            form.putIfAbsent("name", "Công ty Giải Pháp Ánh Dương");
        }
        form.putIfAbsent("legal", Map.of("workAddress", "Địa chỉ công ty", "submitterPosition", "Owner",
                "representation", "LEGAL_REPRESENTATIVE", "declarationAccepted", true));
        byte[] bytes = previousDocument instanceof String value && value.isBlank()
                ? new byte[0] : "%PDF-test-fixture".getBytes(StandardCharsets.UTF_8);
        return multipart(HttpMethod.valueOf(method), path)
                .file(new MockMultipartFile("dossier", "dossier.json", "application/json", JSON.writeValueAsBytes(form)))
                .file(new MockMultipartFile("file", "company-license.pdf", "application/pdf", bytes));
    }
    /** Fixture-only counterpart for tests that directly stage a rejected Company before exercising correction. */
    public static void completeFixtureReview(org.springframework.jdbc.core.JdbcTemplate database, long companyId) {
        database.update("""
                UPDATE company_verification_attempts a SET outcome='REJECTED', rejection_reason=c.rejection_reason,
                    reviewer_id=c.owner_user_id, reviewed_at=now(), version=a.version+1
                FROM companies c WHERE a.company_id=c.id AND c.id=? AND a.outcome IS NULL
                """, companyId);
    }
}
