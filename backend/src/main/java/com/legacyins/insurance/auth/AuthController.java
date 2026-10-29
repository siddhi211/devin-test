package com.legacyins.insurance.auth;

import com.legacyins.insurance.common.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;
    private final AuditService auditService;

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record CreateUserRequest(@NotBlank String username, @NotBlank String password, @NotBlank String fullName,
                                    String email, @NotBlank String role, String branchCd) {
    }

    public record ChangePasswordRequest(@NotBlank String oldPassword, @NotBlank String newPassword) {
    }

    @PostMapping("/auth/login")
    public AuthService.LoginResult login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        return authService.login(req.username(), req.password(), http.getRemoteAddr());
    }

    @GetMapping("/auth/me")
    public AuthService.UserDto me() {
        return authService.me(SecurityUtils.currentUsername());
    }

    @PostMapping("/auth/change-password")
    public Map<String, String> changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        authService.changePassword(SecurityUtils.currentUsername(), req.oldPassword(), req.newPassword());
        return Map.of("status", "OK");
    }

    @GetMapping("/admin/users")
    @PreAuthorize("hasRole('ADMIN')")
    public List<AuthService.UserDto> users() {
        return authService.listUsers();
    }

    @PostMapping("/admin/users")
    @PreAuthorize("hasRole('ADMIN')")
    public AuthService.UserDto createUser(@Valid @RequestBody CreateUserRequest r) {
        return authService.createUser(r.username(), r.password(), r.fullName(), r.email(), r.role(), r.branchCd(),
                SecurityUtils.currentUsername());
    }

    @PatchMapping("/admin/users/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public AuthService.UserDto setStatus(@PathVariable Long id, @RequestParam String status) {
        return authService.setStatus(id, status, SecurityUtils.currentUsername());
    }

    @GetMapping("/admin/audit")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public List<Map<String, Object>> audit(@RequestParam(required = false) String entityType,
                                           @RequestParam(required = false) Long entityId,
                                           @RequestParam(defaultValue = "200") int limit) {
        return auditService.recent(entityType, entityId, Math.min(limit, 1000));
    }

    @GetMapping("/users/by-role/{role}")
    public List<AuthService.UserDto> usersByRole(@PathVariable String role) {
        return authService.listUsers().stream().filter(u -> u.role().equals(role) && "A".equals(u.status())).toList();
    }
}
