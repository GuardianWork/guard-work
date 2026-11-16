package com.guardwork.backend.company.search.dto;

/** A public facet value and how many visible roles disclose it. */
public record CompanyCount(String label, int count) {}
