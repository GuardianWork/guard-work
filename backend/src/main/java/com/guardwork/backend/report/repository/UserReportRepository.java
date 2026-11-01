package com.guardwork.backend.report.repository;

import com.guardwork.backend.report.model.UserReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface UserReportRepository extends JpaRepository<UserReport, Long>,
    JpaSpecificationExecutor<UserReport> {}
