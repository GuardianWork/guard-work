package com.guardwork.backend.company.search.dto;

import java.math.BigDecimal;

/** Disclosed compensation for one visible role. */
public record CompanySalaryRange(
    BigDecimal minimum, BigDecimal maximum, String currency, String payPeriod) {}
