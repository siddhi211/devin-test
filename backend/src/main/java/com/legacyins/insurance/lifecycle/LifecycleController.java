package com.legacyins.insurance.lifecycle;

import com.legacyins.insurance.common.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/lifecycle")
@RequiredArgsConstructor
public class LifecycleController {
    private final LifecycleService service;

    public record DecisionRequest(String decision) {
    }

    public record AutoRenewRequest(boolean autoRenew) {
    }

    public record ReinstateRequest(boolean payArrears) {
    }

    private String me() {
        return SecurityUtils.currentUsername();
    }

    @GetMapping("/renewals/candidates")
    public List<Map<String, Object>> candidates(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return service.candidates(asOf);
    }

    @GetMapping("/renewals")
    public List<Map<String, Object>> renewals(@RequestParam(required = false) String status, @RequestParam(required = false) Long policyId) {
        return service.renewals(status, policyId);
    }

    @GetMapping("/renewals/{id}")
    public Map<String, Object> renewal(@PathVariable Long id) {
        return service.renewal(id);
    }

    @PostMapping("/policies/{policyId}/renewal-offer")
    @PreAuthorize("hasAnyRole('ADMIN','CSR','UNDERWRITER')")
    public Map<String, Object> offer(@PathVariable Long policyId) {
        return service.offer(policyId, me());
    }

    @PostMapping("/renewals/{id}/decision")
    @PreAuthorize("hasAnyRole('ADMIN','CSR','UNDERWRITER')")
    public Map<String, Object> decide(@PathVariable Long id, @RequestBody DecisionRequest r) {
        return service.decide(id, r.decision(), me(), false);
    }

    @PostMapping("/policies/{policyId}/auto-renew")
    @PreAuthorize("hasAnyRole('ADMIN','CSR')")
    public Map<String, Object> autoRenew(@PathVariable Long policyId, @RequestBody AutoRenewRequest r) {
        return service.setAutoRenew(policyId, r.autoRenew(), me());
    }

    @GetMapping("/cancel-reasons")
    public List<Map<String, Object>> reasons() {
        return service.reasons();
    }

    @PostMapping("/cancellations/preview")
    public Map<String, Object> preview(@RequestBody LifecycleService.CancelRequest r) {
        return service.preview(r);
    }

    @PostMapping("/cancellations")
    @PreAuthorize("hasAnyRole('ADMIN','CSR','UNDERWRITER','FINANCE')")
    public Map<String, Object> request(@RequestBody LifecycleService.CancelRequest r) {
        return service.request(r, me());
    }

    @GetMapping("/cancellations")
    public List<Map<String, Object>> cancellations(@RequestParam(required = false) String status, @RequestParam(required = false) Long policyId) {
        return service.cancellations(status, policyId);
    }

    @GetMapping("/cancellations/{id}")
    public Map<String, Object> cancellation(@PathVariable Long id) {
        return service.cancellation(id);
    }

    @PostMapping("/cancellations/{id}/reinstate")
    @PreAuthorize("hasAnyRole('ADMIN','CSR','FINANCE')")
    public Map<String, Object> reinstate(@PathVariable Long id, @RequestBody(required = false) ReinstateRequest r) {
        return service.reinstate(id, r != null && r.payArrears(), me());
    }
}
