package com.guardwork.backend.company.search.dto;

import java.util.List;

/** Public hiring facts derived only from a company's visible postings. */

public record CompanySummary(
    int roleCount,
    List<CompanyCount> locations,
    List<CompanyCount> industries,
    List<CompanyCount> employmentTypes,
    List<CompanyCount> workArrangements,
    List<CompanySalaryBand> salaryBands,
    int disclosedSalaryCount,
    int headcountTotal,
    List<String> deadlines) {}
