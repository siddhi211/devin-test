package com.legacyins.insurance.policy;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PolicyRepository extends JpaRepository<Policy, Long> {
    Optional<Policy> findByPolicyNo(String policyNo);

    List<Policy> findByStatus(String status);

    List<Policy> findByCustomerIdOrderByIdDesc(Long customerId);

    @Query("""
            SELECT p FROM Policy p
            WHERE (:status IS NULL OR p.status = :status)
              AND (:customerId IS NULL OR p.customerId = :customerId)
              AND (:productId IS NULL OR p.productId = :productId)
              AND (:q IS NULL OR lower(p.policyNo) LIKE :q)
            """)
    Page<Policy> search(@Param("q") String q, @Param("status") String status, @Param("customerId") Long customerId,
                        @Param("productId") Long productId, Pageable pageable);
}
