package com.guardwork.backend.application.repository;

import com.guardwork.backend.application.model.ApplicationSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ApplicationSnapshotRepository extends JpaRepository<ApplicationSnapshot, UUID> {
}
