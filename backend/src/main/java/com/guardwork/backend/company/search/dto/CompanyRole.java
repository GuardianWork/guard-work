package com.guardwork.backend.company.search.dto;

/**
 * One visible role in a company dossier.
 *
 * <p>Enum values and user-facing copy remain unlocalized here; the candidate client owns Vietnamese
 * labels and formatting.
 *
 * @param applicationCount total applications received by this posting, or {@code null} when the
 *     projection does not disclose it (currently the public roles endpoint always sends it,
 *     including {@code 0} for postings without applicants)
 */
public record CompanyRole(
    Long id,
    String title,
    String location,
    String industry,
    String employmentType,
    String workArrangement,
    CompanySalaryRange salary,
    Integer headcount,
    String deadline,
    String description,
    boolean applicationsAvailable,
    Long applicationCount) {}
