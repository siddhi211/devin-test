package com.legacyins.insurance.product;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "insurance_product")
@Getter
@Setter
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "product_id")
    private Long id;
    private String productCode;
    private String productName;
    private String lineOfBusiness;
    private String description;
    private BigDecimal baseRate;
    private String status = "A";
    private LocalDateTime createdDt = LocalDateTime.now();
    private LocalDateTime updatedDt;
    private Integer minAge;
    private Integer maxAge;
    private BigDecimal minSumInsured;
    private BigDecimal maxSumInsured;
    private Integer minTermMonths;
    private Integer maxTermMonths;
    private Integer versionNo = 1;
}
