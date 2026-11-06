package com.guardwork.backend.application.repository;

public interface CompanyApplicationProjection extends RecruiterApplicationProjection {
    Long getJobId();

    String getJobTitle();
}
