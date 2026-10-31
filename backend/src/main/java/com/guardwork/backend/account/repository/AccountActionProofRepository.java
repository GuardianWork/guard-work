package com.guardwork.backend.account.repository;
import com.guardwork.backend.account.model.AccountActionProof;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AccountActionProofRepository extends JpaRepository<AccountActionProof,Long> {
    Optional<AccountActionProof> findByUserIdAndPurpose(Long userId,String purpose);
    java.util.List<AccountActionProof> findByUserId(Long userId);
}
