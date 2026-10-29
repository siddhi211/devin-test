package com.legacyins.insurance.customer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
    List<Customer> findByNormNidAndStatusNot(String normNid, String status);

    @Query("""
            SELECT c FROM Customer c
            WHERE (:status IS NULL OR c.status = :status)
              AND (:q IS NULL OR lower(c.customerNo) LIKE :q OR c.normName LIKE :q
                   OR lower(coalesce(c.email,'')) LIKE :q OR c.normNid LIKE :q OR coalesce(c.phone,'') LIKE :q)
            """)
    Page<Customer> search(@Param("q") String q, @Param("status") String status, Pageable pageable);

    long countByStatus(String status);
}
