package com.guardwork.backend.account.repository;
import com.guardwork.backend.account.model.AccountDeletionRequest;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AccountDeletionRequestRepository extends JpaRepository<AccountDeletionRequest,Long> {
    Optional<AccountDeletionRequest> findByUserIdAndCancelledAtIsNull(Long userId);
}
