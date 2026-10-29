package com.legacyins.insurance.customer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BeneficiaryRepository extends JpaRepository<Beneficiary, Long> {
    List<Beneficiary> findByCustomerIdOrderByIdAsc(Long customerId);
}
