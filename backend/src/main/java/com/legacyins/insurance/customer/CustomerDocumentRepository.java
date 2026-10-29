package com.legacyins.insurance.customer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CustomerDocumentRepository extends JpaRepository<CustomerDocument, Long> {
    List<CustomerDocument> findByCustomerIdOrderByIdAsc(Long customerId);
}
