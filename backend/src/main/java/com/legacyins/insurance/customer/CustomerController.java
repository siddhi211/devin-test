package com.legacyins.insurance.customer;

import com.legacyins.insurance.common.PageResponse;
import com.legacyins.insurance.common.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {
    private final CustomerService service;

    public record StatusRequest(String status, String reason) {
    }

    public record MergeRequest(Long survivorId, Long duplicateId, String reason) {
    }

    public record ReviewRequest(String status, String note) {
    }

    public record KycDecision(boolean approve, String note) {
    }

    @GetMapping
    public PageResponse<Customer> search(@RequestParam(required = false) String q,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return service.search(q, status, page, size);
    }

    @GetMapping("/{id}")
    public CustomerService.CustomerDetail get(@PathVariable Long id) {
        return service.detail(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','CSR','UNDERWRITER')")
    public Customer create(@RequestBody CustomerService.CustomerRequest r) {
        return service.create(r, SecurityUtils.currentUsername());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','CSR','UNDERWRITER')")
    public Customer update(@PathVariable Long id, @RequestBody CustomerService.CustomerRequest r) {
        return service.update(id, r, SecurityUtils.currentUsername());
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN','CSR','MANAGER')")
    public Customer status(@PathVariable Long id, @RequestBody StatusRequest r) {
        return service.changeStatus(id, r.status(), r.reason(), SecurityUtils.currentUsername());
    }

    @GetMapping("/{id}/duplicates")
    public List<Customer> duplicates(@PathVariable Long id) {
        return service.findDuplicates(id);
    }

    @PostMapping("/merge")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public Customer merge(@RequestBody MergeRequest r) {
        return service.merge(r.survivorId(), r.duplicateId(), r.reason(), SecurityUtils.currentUsername());
    }

    @PostMapping("/{id}/addresses")
    @PreAuthorize("hasAnyRole('ADMIN','CSR','UNDERWRITER')")
    public CustomerAddress addAddress(@PathVariable Long id, @RequestBody CustomerService.AddressRequest r) {
        return service.addAddress(id, r, SecurityUtils.currentUsername());
    }

    @DeleteMapping("/{id}/addresses/{addressId}")
    @PreAuthorize("hasAnyRole('ADMIN','CSR')")
    public Map<String, String> deleteAddress(@PathVariable Long id, @PathVariable Long addressId) {
        service.deleteAddress(id, addressId, SecurityUtils.currentUsername());
        return Map.of("status", "OK");
    }

    @PostMapping("/{id}/beneficiaries")
    @PreAuthorize("hasAnyRole('ADMIN','CSR','UNDERWRITER')")
    public Beneficiary addBeneficiary(@PathVariable Long id, @RequestBody CustomerService.BeneficiaryRequest r) {
        return service.addBeneficiary(id, r, SecurityUtils.currentUsername());
    }

    @DeleteMapping("/{id}/beneficiaries/{beneficiaryId}")
    @PreAuthorize("hasAnyRole('ADMIN','CSR')")
    public Map<String, String> deleteBeneficiary(@PathVariable Long id, @PathVariable Long beneficiaryId) {
        service.deleteBeneficiary(id, beneficiaryId, SecurityUtils.currentUsername());
        return Map.of("status", "OK");
    }

    @PostMapping("/{id}/documents")
    @PreAuthorize("hasAnyRole('ADMIN','CSR','UNDERWRITER')")
    public CustomerDocument addDocument(@PathVariable Long id, @RequestBody CustomerService.DocumentRequest r) {
        return service.addDocument(id, r, SecurityUtils.currentUsername());
    }

    @PostMapping("/{id}/documents/{documentId}/review")
    @PreAuthorize("hasAnyRole('ADMIN','UNDERWRITER','MANAGER')")
    public CustomerDocument review(@PathVariable Long id, @PathVariable Long documentId, @RequestBody ReviewRequest r) {
        return service.reviewDocument(id, documentId, r.status(), r.note(), SecurityUtils.currentUsername());
    }

    @PostMapping("/{id}/kyc/open")
    @PreAuthorize("hasAnyRole('ADMIN','CSR','UNDERWRITER')")
    public Map<String, Object> openKyc(@PathVariable Long id) {
        return service.openKycCase(id, SecurityUtils.currentUsername());
    }

    @PostMapping("/{id}/kyc/decision")
    @PreAuthorize("hasAnyRole('ADMIN','UNDERWRITER','MANAGER')")
    public Customer decideKyc(@PathVariable Long id, @RequestBody KycDecision r) {
        return service.decideKyc(id, r.approve(), r.note(), SecurityUtils.currentUsername());
    }
}
