package com.legacyins.insurance.billing;

import com.legacyins.insurance.common.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/billing")
@RequiredArgsConstructor
public class BillingController {
    private final BillingService service;
    private final LedgerService ledger;

    public record ReasonRequest(String reason) {
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return service.summary();
    }

    @GetMapping("/policies/{policyId}")
    public Map<String, Object> account(@PathVariable Long policyId) {
        return service.account(policyId);
    }

    @PostMapping("/policies/{policyId}/payments")
    @PreAuthorize("hasAnyRole('ADMIN','CSR','FINANCE')")
    public BillingService.PaymentResult pay(@PathVariable Long policyId, @RequestBody BillingService.PaymentRequest r) {
        return service.pay(policyId, r, SecurityUtils.currentUsername());
    }

    @GetMapping("/payments")
    public List<Map<String, Object>> payments(@RequestParam(required = false) String status,
                                              @RequestParam(defaultValue = "200") int limit) {
        return service.recentPayments(status, Math.min(limit, 1000));
    }

    @PostMapping("/payments/{paymentId}/reverse")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Map<String, Object> reverse(@PathVariable Long paymentId, @RequestBody ReasonRequest r) {
        return service.reverse(paymentId, r.reason(), SecurityUtils.currentUsername());
    }

    @PostMapping("/schedules/{scheduleId}/waive-late-fee")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Map<String, String> waive(@PathVariable Long scheduleId) {
        service.waiveLateFee(scheduleId, SecurityUtils.currentUsername());
        return Map.of("status", "OK");
    }

    @GetMapping("/refunds")
    public List<Map<String, Object>> refunds(@RequestParam(required = false) String status) {
        return service.refunds(status);
    }

    @PostMapping("/refunds/{refundId}/pay")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Map<String, Object> payRefund(@PathVariable Long refundId) {
        return service.markRefundPaid(refundId, SecurityUtils.currentUsername());
    }

    @GetMapping("/ledger")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public List<Map<String, Object>> journals(@RequestParam(required = false) Long policyId,
                                              @RequestParam(defaultValue = "200") int limit) {
        return ledger.journals(policyId, Math.min(limit, 1000));
    }

    @GetMapping("/trial-balance")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public List<Map<String, Object>> trialBalance() {
        return ledger.trialBalance();
    }
}
