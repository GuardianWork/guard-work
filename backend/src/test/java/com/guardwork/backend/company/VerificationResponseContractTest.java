package com.guardwork.backend.company;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardwork.backend.admin.dto.CompanyVerificationResponse;
import com.guardwork.backend.company.dto.OwnerVerificationResponse;
import com.guardwork.backend.company.model.Company;
import com.guardwork.backend.company.model.VerificationStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerificationResponseContractTest {

    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({
            "PENDING,PENDING,",
            "VERIFIED,CHECKED,VERIFIED",
            "REJECTED,CHECKED,REJECTED"
    })
    void adminAndOwnerResponsesExposeProcessingStateWithoutReplacingEligibilityState(
            VerificationStatus status, String processingStatus, String reviewOutcome) throws Exception {
        Company company = new Company();
        company.setId(1L);
        company.setVerificationStatus(status);

        for (Object response : new Object[]{
                CompanyVerificationResponse.from(company), OwnerVerificationResponse.from(company)}) {
            var body = json.readTree(json.writeValueAsString(response));
            assertEquals(status.name(), body.path("verificationStatus").asText());
            assertEquals(processingStatus, body.path("processingStatus").asText());
            assertTrue(body.has("reviewOutcome"), "Pending responses must explicitly expose no outcome");
            if (reviewOutcome == null) {
                assertTrue(body.get("reviewOutcome").isNull());
            } else {
                assertEquals(reviewOutcome, body.path("reviewOutcome").asText());
            }
        }
    }
}
