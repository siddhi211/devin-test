package com.legacyins.insurance.claims;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClaimRepository extends JpaRepository<Claim, Long> {
    @Query("""
            SELECT c FROM Claim c
            WHERE (:status IS NULL OR c.status = :status)
              AND (:policyId IS NULL OR c.policyId = :policyId)
              AND (:adjusterId IS NULL OR c.assignedAdjusterId = :adjusterId)
              AND (:q IS NULL OR lower(c.claimNo) LIKE :q OR lower(c.description) LIKE :q)
            """)
    Page<Claim> search(@Param("q") String q, @Param("status") String status, @Param("policyId") Long policyId,
                       @Param("adjusterId") Long adjusterId, Pageable pageable);
}
