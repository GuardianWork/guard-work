package com.guardwork.backend.auth.repository;

import com.guardwork.backend.auth.model.UserEmailVerification;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserEmailVerificationRepository extends JpaRepository<UserEmailVerification, Long> {}
