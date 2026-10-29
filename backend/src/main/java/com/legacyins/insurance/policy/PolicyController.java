package com.legacyins.insurance.policy;

import com.legacyins.insurance.common.PageResponse;
import com.legacyins.insurance.common.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/policies")
@RequiredArgsConstructor
public class PolicyController {
    private final PolicyService service;

    public record ReasonRequest(String reason) {
    }

    @GetMapping
    public PageResponse<PolicyService.PolicySummary> search(@RequestParam(required = false) String q,
                                                            @RequestParam(required = false) String status,
                                                            @RequestParam(required = false) Long customerId,
                                                            @RequestParam(required = false) Long productId,
                                                            @RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "20") int size) {
        return service.search(q, status, customerId, productId, page, size);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        return service.detail(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','CSR','UNDERWRITER')")
    public Policy create(@RequestBody PolicyService.PolicyRequest r) {
        return service.create(r, SecurityUtils.currentUsername());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','CSR','UNDERWRITER')")
    public Policy update(@PathVariable Long id, @RequestBody PolicyService.PolicyRequest r) {
        return service.update(id, r, SecurityUtils.currentUsername());
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyRole('ADMIN','CSR','UNDERWRITER')")
    public Map<String, Object> submit(@PathVariable Long id) {
        return service.submit(id, SecurityUtils.currentUsername());
    }

    @PostMapping("/{id}/issue")
    @PreAuthorize("hasAnyRole('ADMIN','CSR','UNDERWRITER')")
    public Policy issue(@PathVariable Long id) {
        return service.issue(id, SecurityUtils.currentUsername());
    }

    @PostMapping("/{id}/withdraw")
    @PreAuthorize("hasAnyRole('ADMIN','CSR','UNDERWRITER')")
    public Policy withdraw(@PathVariable Long id, @RequestBody(required = false) ReasonRequest r) {
        return service.withdraw(id, r == null ? null : r.reason(), SecurityUtils.currentUsername());
    }

    @PostMapping("/{id}/endorsements")
    @PreAuthorize("hasAnyRole('ADMIN','CSR','UNDERWRITER')")
    public Policy endorse(@PathVariable Long id, @RequestBody PolicyService.EndorsementRequest r) {
        return service.endorse(id, r, SecurityUtils.currentUsername());
    }
}
