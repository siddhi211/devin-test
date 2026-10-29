package com.legacyins.insurance.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Entity
@Table(name = "customer_address")
@Getter
@Setter
public class CustomerAddress {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "address_id")
    private Long id;
    private Long customerId;
    private String addressType = "HOME";
    private String line1;
    private String line2;
    private String city;
    private String state;
    private String postalCode;
    private String country;
    private String primaryFlag = "N";
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
}
