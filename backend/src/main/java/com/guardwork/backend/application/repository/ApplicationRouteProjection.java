package com.guardwork.backend.application.repository;

import java.util.UUID;

public interface ApplicationRouteProjection {
    Long getJobId();
    Long getCompanyId();
    UUID getCandidateId();
}
