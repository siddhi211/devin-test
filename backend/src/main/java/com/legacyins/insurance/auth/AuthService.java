package com.legacyins.insurance.auth;

import com.legacyins.insurance.common.ApiException;
import com.legacyins.insurance.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder encoder;
    private final JwtEncoder jwtEncoder;
    private final AuditService audit;

    @Value("${app.jwt.ttl-minutes}")
    private long ttlMinutes;

    @Value("${app.security.max-failed-logins}")
    private int maxFailedLogins;

    public record LoginResult(String token, Instant expiresAt, UserDto user) {
    }

    public record UserDto(Long id, String username, String fullName, String email, String role,
                          String branchCd, String status, LocalDateTime lastLoginDt) {
        static UserDto of(UserAccount u) {
            return new UserDto(u.getId(), u.getUsername(), u.getFullName(), u.getEmail(), u.getRole().getName(),
                    u.getBranchCd(), u.getStatus(), u.getLastLoginDt());
        }
    }

    @Transactional(noRollbackFor = ApiException.class)
    public LoginResult login(String username, String password, String ip) {
        UserAccount user = users.findByUsername(username).orElse(null);
        if (user == null) {
            audit.sessionLog(username, "UNKNOWN_USER", ip);
            throw new ApiException(ErrorCode.AUTH_FAILED, "Invalid username or password");
        }
        if (!"A".equals(user.getStatus())) {
            audit.sessionLog(username, "LOCKED", ip);
            throw new ApiException(ErrorCode.AUTH_FAILED, "Account is locked or disabled");
        }
        if (!encoder.matches(password, user.getPasswordHash())) {
            user.setFailedLogins(user.getFailedLogins() + 1);
            if (user.getFailedLogins() >= maxFailedLogins) {
                user.setStatus("L");
            }
            audit.sessionLog(username, "BAD_PASSWORD", ip);
            throw new ApiException(ErrorCode.AUTH_FAILED, "Invalid username or password");
        }
        user.setFailedLogins(0);
        user.setLastLoginDt(LocalDateTime.now());
        audit.sessionLog(username, "SUCCESS", ip);

        Instant now = Instant.now();
        Instant exp = now.plus(ttlMinutes, ChronoUnit.MINUTES);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("insurance-backend")
                .subject(user.getUsername())
                .issuedAt(now)
                .expiresAt(exp)
                .claim("roles", List.of(user.getRole().getName()))
                .claim("name", user.getFullName())
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new LoginResult(token, exp, UserDto.of(user));
    }

    public UserDto me(String username) {
        return users.findByUsername(username).map(UserDto::of)
                .orElseThrow(() -> ApiException.notFound("User", username));
    }

    public List<UserDto> listUsers() {
        return users.findAllByOrderByUsername().stream().map(UserDto::of).toList();
    }

    @Transactional
    public UserDto createUser(String username, String password, String fullName, String email, String roleName,
                              String branchCd, String actor) {
        if (users.findByUsername(username).isPresent()) {
            throw ApiException.duplicate("Username already exists");
        }
        validatePassword(password);
        UserAccount u = new UserAccount();
        u.setUsername(username);
        u.setPasswordHash(encoder.encode(password));
        u.setFullName(fullName);
        u.setEmail(email);
        u.setRole(roles.findByName(roleName).orElseThrow(() -> ApiException.validation("Unknown role " + roleName)));
        u.setBranchCd(branchCd);
        u.setPwdChangedDt(LocalDateTime.now());
        users.save(u);
        audit.log(actor, "USER_CREATE", "USER", u.getId(), username + " role=" + roleName);
        return UserDto.of(u);
    }

    @Transactional
    public UserDto setStatus(Long userId, String status, String actor) {
        if (!List.of("A", "L", "D").contains(status)) {
            throw ApiException.validation("Status must be A, L or D");
        }
        UserAccount u = users.findById(userId).orElseThrow(() -> ApiException.notFound("User", userId));
        u.setStatus(status);
        if ("A".equals(status)) {
            u.setFailedLogins(0);
        }
        audit.log(actor, "USER_STATUS", "USER", userId, "status=" + status);
        return UserDto.of(u);
    }

    @Transactional
    public void changePassword(String username, String oldPassword, String newPassword) {
        UserAccount u = users.findByUsername(username).orElseThrow(() -> ApiException.notFound("User", username));
        if (!encoder.matches(oldPassword, u.getPasswordHash())) {
            throw new ApiException(ErrorCode.AUTH_FAILED, "Current password is incorrect");
        }
        validatePassword(newPassword);
        u.setPasswordHash(encoder.encode(newPassword));
        u.setPwdChangedDt(LocalDateTime.now());
        audit.log(username, "PASSWORD_CHANGE", "USER", u.getId(), null);
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 8) {
            throw ApiException.validation("Password must be at least 8 characters");
        }
    }
}
