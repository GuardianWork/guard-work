package com.guardwork.backend.job.dto;

import java.math.BigDecimal;

public record JobSearchRequest(
    String keyword,
    String location,
    String industry,
    String employmentType,
    String workArrangement,
    String deadline,
    BigDecimal salaryMin,
    BigDecimal salaryMax
){}
