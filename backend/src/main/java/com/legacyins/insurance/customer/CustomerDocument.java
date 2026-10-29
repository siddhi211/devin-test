package com.legacyins.insurance.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "customer_document")
@Getter
@Setter
public class CustomerDocument {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "document_id")
    private Long id;
    private Long customerId;
    private String docType;
    private String docNumber;
    private String fileName;
    private LocalDate issueDt;
    private LocalDate expiryDt;
    private String status = "PENDING";
    private String reviewNote;
    private String reviewedBy;
    private LocalDateTime reviewedDt;
    private LocalDateTime uploadedDt = LocalDateTime.now();
}
