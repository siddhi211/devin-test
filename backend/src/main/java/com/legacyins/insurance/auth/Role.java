package com.legacyins.insurance.auth;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "user_role")
@Getter
@Setter
public class Role {
    @Id
    @Column(name = "role_id")
    private Long id;
    @Column(name = "role_name")
    private String name;
    private String description;
    private Integer securityLevel;
}
