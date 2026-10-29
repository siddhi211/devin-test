package com.legacyins.insurance.auth;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_account")
@Getter
@Setter
public class UserAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;
    private String username;
    private String passwordHash;
    private String fullName;
    private String email;
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "role_id")
    private Role role;
    private String branchCd;
    private String status = "A";
    private Integer failedLogins = 0;
    private LocalDateTime lastLoginDt;
    private LocalDateTime pwdChangedDt;
    private LocalDateTime createdDt = LocalDateTime.now();
}
