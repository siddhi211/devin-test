package com.legacyins.insurance.claims;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "claim")
@Getter
@Setter
public class Claim {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "claim_id")
    private Long id;
    private String claimNo;
    private Long policyId;
    private Long customerId;
    private String categoryCode;
    private String subtypeCode;
    private LocalDate incidentDt;
    private LocalDate reportedDt;
    private String description;
    private BigDecimal claimedAmount;
    private BigDecimal assessedAmount;
    private BigDecimal approvedAmount;
    private BigDecimal settledAmount = BigDecimal.ZERO;
    private String status = "REGISTERED";
    private String priority = "NORMAL";
    private Long assignedAdjusterId;
    private Integer reopenCount = 0;
    private LocalDate slaDueDt;
    private String slaBreached = "N";
    private Integer fraudScore = 0;
    private String decisionReason;
    private String findings;
    private LocalDateTime decidedDt;
    private String createdBy;
    private LocalDateTime createdDt = LocalDateTime.now();
    private LocalDateTime updatedDt;
}
