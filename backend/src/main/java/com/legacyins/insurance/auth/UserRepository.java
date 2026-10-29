package com.legacyins.insurance.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<UserAccount, Long> {
    Optional<UserAccount> findByUsername(String username);

    List<UserAccount> findByRoleNameAndStatusOrderByUsername(String roleName, String status);

    List<UserAccount> findAllByOrderByUsername();
}
