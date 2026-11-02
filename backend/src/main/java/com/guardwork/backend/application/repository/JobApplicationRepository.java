package com.guardwork.backend.application.repository;

import com.guardwork.backend.application.model.JobApplication;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobApplicationRepository extends JpaRepository<JobApplication, UUID> {

    boolean existsByCandidateIdAndJobId(UUID candidateId, Long jobId);

    Optional<JobApplication> findByCandidateIdAndJobId(UUID candidateId, Long jobId);

    List<JobApplication> findByCandidateIdOrderByAppliedAtDesc(UUID candidateId);
}
