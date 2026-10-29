package com.legacyins.insurance.policy;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "policy")
@Getter
@Setter
public class Policy {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "policy_id")
    private Long id;
    private String policyNo;
    private Long customerId;
    private Long productId;
    private String status = "DRAFT";
    private BigDecimal sumInsured;
    private Integer termMonths = 12;
    private String paymentFrequency = "ANNUAL";
    private String riskClass = "STD";
    private String smokerFlag = "N";
    private String occupationCode;
    private String medicalCodes;
    private BigDecimal annualPremium;
    private BigDecimal installmentPremium;
    private LocalDate startDt;
    private LocalDate endDt;
    private LocalDate issueDt;
    private String branchCd;
    private Integer versionNo = 1;
    private String createdBy;
    private LocalDateTime createdDt = LocalDateTime.now();
    private LocalDateTime updatedDt;
    private LocalDate lapseDt;
    private Integer renewalCount = 0;
}
