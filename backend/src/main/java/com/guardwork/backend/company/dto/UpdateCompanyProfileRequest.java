package com.guardwork.backend.company.dto;

import java.util.List;

import com.guardwork.backend.company.model.CompanyContent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Recruiter request to replace the company's entire public profile payload. Omitted fields are
 * cleared; {@code {}} clears the profile. The server stamps {@code schemaVersion} and never
 * accepts it from the client.
 */
public record UpdateCompanyProfileRequest(
        @Size(max = 100, message = "industry must be at most 100 characters")
        String industry,

        @Size(max = 2000, message = "introduction must be at most 2000 characters")
        String introduction,

        @Size(max = 500, message = "workAddress must be at most 500 characters")
        String workAddress,

        @Pattern(regexp = CompanyContent.WORKING_HOURS_PATTERN,
                message = "workingHours must look like \"Thứ 2 - Thứ 6\" or \"Thứ 2 - Chủ nhật, 08:30 - 17:30\"")
        @Size(max = 60, message = "workingHours must be at most 60 characters")
        String workingHours,

        @Valid
        CompanyContent.CompanyScale companyScale,

        @Size(max = 10, message = "expertise must contain at most 10 items")
        List<@Size(max = 50, message = "each expertise item must be at most 50 characters") String> expertise) {
}
