package com.guardwork.backend.company.search.dto;

/** A company in the candidate-facing directory. */
public record CompanyDirectoryEntry(Long id, String name, CompanySummary summary) {}
