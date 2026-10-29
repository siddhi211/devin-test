package com.legacyins.insurance.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "customer")
@Getter
@Setter
public class Customer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "customer_id")
    private Long id;
    private String customerNo;
    private String customerType = "INDIVIDUAL";
    private String firstName;
    private String lastName;
    private String companyName;
    private String nationalId;
    private LocalDate dateOfBirth;
    private String gender;
    private String email;
    private String phone;
    private String occupationCode;
    private String status = "A";
    private LocalDateTime createdDt = LocalDateTime.now();
    private String createdBy;
    private LocalDateTime updatedDt;
    private String riskClass = "STD";
    private String segment = "RETAIL";
    private String normName;
    private String normNid;
    private Long mergedIntoId;
    private String kycStatus = "PENDING";

    public String displayName() {
        if ("CORPORATE".equals(customerType) && companyName != null) {
            return companyName;
        }
        return ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).trim();
    }
}
