package com.legacyins.insurance.product;

import com.legacyins.insurance.common.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ProductController {
    private final ProductService service;
    private final PremiumService premiumService;
    private final JdbcTemplate jdbc;

    @GetMapping("/products")
    public List<Product> list() {
        return service.list();
    }

    @GetMapping("/products/{id}")
    public ProductService.ProductDetail get(@PathVariable Long id) {
        return service.detail(id);
    }

    @PostMapping("/products")
    @PreAuthorize("hasRole('ADMIN')")
    public Product create(@RequestBody ProductService.ProductRequest r) {
        return service.create(r, SecurityUtils.currentUsername());
    }

    @PutMapping("/products/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Product update(@PathVariable Long id, @RequestBody ProductService.ProductRequest r) {
        return service.update(id, r, SecurityUtils.currentUsername());
    }

    @PostMapping("/products/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public Product status(@PathVariable Long id, @RequestParam String status) {
        return service.setStatus(id, status, SecurityUtils.currentUsername());
    }

    @PostMapping("/products/{id}/rating-rules")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> addRule(@PathVariable Long id, @RequestBody ProductService.RatingRuleRequest r) {
        return service.addRatingRule(id, r, SecurityUtils.currentUsername());
    }

    @PostMapping("/products/{id}/rating-rules/{ruleId}/active")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, String> toggleRule(@PathVariable Long id, @PathVariable Long ruleId, @RequestParam boolean active) {
        service.toggleRatingRule(id, ruleId, active, SecurityUtils.currentUsername());
        return Map.of("status", "OK");
    }

    @PostMapping("/quotes")
    public PremiumService.QuoteResult quote(@RequestBody PremiumService.QuoteRequest r) {
        return premiumService.quote(r, SecurityUtils.currentUsername(), true);
    }

    @GetMapping("/reference/underwriting")
    public Map<String, Object> uwReference() {
        return Map.of(
                "occupations", jdbc.queryForList("SELECT * FROM uw_occupation ORDER BY occ_code"),
                "medicalCodes", jdbc.queryForList("SELECT * FROM uw_medical_code ORDER BY med_code"),
                "rules", jdbc.queryForList("SELECT * FROM uw_rule ORDER BY rule_id"),
                "authorities", jdbc.queryForList("SELECT * FROM uw_authority ORDER BY level_no"));
    }

    @GetMapping("/reference/claim-categories")
    public List<Map<String, Object>> claimCategories() {
        return jdbc.queryForList("SELECT * FROM claim_category WHERE active='Y' ORDER BY line_of_business, category_code");
    }

    @GetMapping("/reference/cancel-reasons")
    public List<Map<String, Object>> cancelReasons() {
        return jdbc.queryForList("SELECT * FROM lc_cancel_reason ORDER BY reason_code");
    }
}
