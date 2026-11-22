package com.guardwork.backend.company.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Recruiter-authored public company profile stored in {@code companies.content} (JSONB,
 * schemaVersion 1). Every field is optional; unset fields are omitted from serialization so
 * the candidate UI keeps its "not disclosed" fallbacks. This payload is self-declared by the
 * company: it is never a substitute for GuardianWork-verified facts nor for the
 * posting-derived summary, and the backend never invents values for it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CompanyContent(
        Integer schemaVersion,

        @Size(max = 100, message = "industry must be at most 100 characters")
        String industry,

        @Size(max = 2000, message = "introduction must be at most 2000 characters")
        String introduction,

        @Size(max = 500, message = "workAddress must be at most 500 characters")
        String workAddress,

        @Pattern(regexp = WORKING_HOURS_PATTERN,
                message = "workingHours must look like \"Thứ 2 - Thứ 6\" or \"Thứ 2 - Chủ nhật, 08:30 - 17:30\"")
        @Size(max = 60, message = "workingHours must be at most 60 characters")
        String workingHours,

        @Valid
        CompanyScale companyScale,

        @Size(max = 10, message = "expertise must contain at most 10 items")
        List<@Size(max = 50, message = "each expertise item must be at most 50 characters") String> expertise) {

    public static final int SCHEMA_VERSION = 1;

    /** Weekday-range convention used by Vietnamese employers, e.g. {@code Thứ 2 - Thứ 6, 08:30 - 17:30}. */
    public static final String WORKING_HOURS_PATTERN =
            "^(Thứ [2-7]|Chủ nhật) - (Thứ [2-7]|Chủ nhật)(, ([01][0-9]|2[0-3]):[0-5][0-9] - ([01][0-9]|2[0-3]):[0-5][0-9])?$";

    public record CompanyScale(@NotNull(message = "companyScale.employeeRange is required when companyScale is set")
                               EmployeeRange employeeRange) {
    }

    public enum EmployeeRange {
        ONE_TO_TEN("1-10"),
        ELEVEN_TO_FIFTY("11-50"),
        FIFTY_ONE_TO_TWO_HUNDRED("51-200"),
        TWO_HUNDRED_ONE_TO_FIVE_HUNDRED("201-500"),
        FIVE_HUNDRED_ONE_TO_ONE_THOUSAND("501-1000"),
        ONE_THOUSAND_PLUS("1000+");

        private final String label;

        EmployeeRange(String label) {
            this.label = label;
        }

        @JsonValue
        public String label() {
            return label;
        }

        @JsonCreator
        public static EmployeeRange fromLabel(String value) {
            for (EmployeeRange range : values()) {
                if (range.label.equals(value)) {
                    return range;
                }
            }
            throw new IllegalArgumentException("Unknown employeeRange: " + value);
        }
    }

    public static CompanyContent empty() {
        return new CompanyContent(null, null, null, null, null, null, null);
    }

    /** True when no meaningful (non-schema) field is set; such payloads must not be published. */
    public boolean payloadEmpty() {
        return (industry == null || industry.isBlank())
                && (introduction == null || introduction.isBlank())
                && (workAddress == null || workAddress.isBlank())
                && (workingHours == null || workingHours.isBlank())
                && (companyScale == null || companyScale.employeeRange() == null)
                && (expertise == null || expertise.isEmpty());
    }

    /** Returns the same payload stamped with the current schema version for persistence or publication. */
    public CompanyContent stamped() {
        return new CompanyContent(SCHEMA_VERSION, industry, introduction, workAddress, workingHours,
                companyScale, expertise == null ? null : List.copyOf(expertise));
    }
}
