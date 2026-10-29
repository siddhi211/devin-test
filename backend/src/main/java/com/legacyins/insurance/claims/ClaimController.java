package com.legacyins.insurance.claims;

import com.legacyins.insurance.common.PageResponse;
import com.legacyins.insurance.common.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/claims")
@RequiredArgsConstructor
public class ClaimController {
    private final ClaimService service;

    public record AssignRequest(Long adjusterId) {
    }

    public record MoveRequest(String status, String reason) {
    }

    public record NoteRequest(String noteType, String text) {
    }

    public record DocRequest(String docType, String fileName) {
    }

    public record ReasonRequest(String reason) {
    }

    private String me() {
        return SecurityUtils.currentUsername();
    }

    @GetMapping
    public PageResponse<Map<String, Object>> search(@RequestParam(required = false) String q,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(required = false) Long policyId,
                                                    @RequestParam(required = false) Long adjusterId,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return service.search(q, status, policyId, adjusterId, page, size);
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return service.summary();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        return service.detail(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','CSR','ADJUSTER','MANAGER')")
    public Map<String, Object> register(@RequestBody ClaimService.RegisterRequest r) {
        return service.register(r, me());
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','ADJUSTER')")
    public Claim assign(@PathVariable Long id, @RequestBody(required = false) AssignRequest r) {
        return r == null || r.adjusterId() == null ? service.autoAssign(id, me()) : service.assign(id, r.adjusterId(), me());
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','ADJUSTER')")
    public Claim move(@PathVariable Long id, @RequestBody MoveRequest r) {
        return service.move(id, r.status(), r.reason(), me());
    }

    @PostMapping("/{id}/notes")
    public Map<String, Object> note(@PathVariable Long id, @RequestBody NoteRequest r) {
        return service.addNote(id, r.noteType(), r.text(), me());
    }

    @PostMapping("/{id}/documents")
    public Map<String, Object> doc(@PathVariable Long id, @RequestBody DocRequest r) {
        return service.addDocument(id, r.docType(), r.fileName(), me());
    }

    @PostMapping("/{id}/risk-flags/{flagId}/clear")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public Map<String, Object> clearFlag(@PathVariable Long id, @PathVariable Long flagId) {
        return service.clearFlag(id, flagId, me());
    }

    @PostMapping("/{id}/assess")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','ADJUSTER')")
    public Map<String, Object> assess(@PathVariable Long id, @RequestBody ClaimService.AssessRequest r) {
        return service.assess(id, r, me());
    }

    @PostMapping("/{id}/decide")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','ADJUSTER')")
    public Map<String, Object> decide(@PathVariable Long id, @RequestBody ClaimService.DecideRequest r) {
        return service.decide(id, r, me());
    }

    @PostMapping("/{id}/settle")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public Map<String, Object> settle(@PathVariable Long id, @RequestBody ClaimService.SettleRequest r) {
        return service.settle(id, r, me());
    }

    @PostMapping("/{id}/settlements/{settlementId}/reverse")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Claim reverse(@PathVariable Long id, @PathVariable Long settlementId, @RequestBody ReasonRequest r) {
        return service.reverseSettlement(id, settlementId, r.reason(), me());
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public Claim reopen(@PathVariable Long id, @RequestBody ReasonRequest r) {
        return service.reopen(id, r.reason(), me());
    }
}
