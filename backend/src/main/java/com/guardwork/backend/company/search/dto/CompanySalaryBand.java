package com.guardwork.backend.company.search.dto;

import java.math.BigDecimal;

/** Aggregated disclosed pay range for a currency and pay period. */
public record CompanySalaryBand(
    String currency,
    String payPeriod,
    BigDecimal minimum,
    BigDecimal maximum,
    int count) {}
