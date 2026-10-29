package com.legacyins.insurance.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "beneficiary")
@Getter
@Setter
public class Beneficiary {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "beneficiary_id")
    private Long id;
    private Long customerId;
    private String fullName;
    private String relationship;
    private BigDecimal sharePct;
    private LocalDate dateOfBirth;
    private LocalDateTime createdDt = LocalDateTime.now();
}
