package com.legacyins.insurance.product;

import com.legacyins.insurance.auth.AuditService;
import com.legacyins.insurance.common.ApiException;
import com.legacyins.insurance.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ProductService {
    private final ProductRepository products;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public record ProductRequest(String productCode, String productName, String lineOfBusiness, String description,
                                 BigDecimal baseRate, Integer minAge, Integer maxAge, BigDecimal minSumInsured,
                                 BigDecimal maxSumInsured, Integer minTermMonths, Integer maxTermMonths) {
    }

    public record RatingRuleRequest(Integer ruleOrder, String ruleName, String ruleKind, String conditionField,
                                    String conditionOp, String conditionValue, String amountType, BigDecimal amount) {
    }

    public record ProductDetail(Product product, List<Map<String, Object>> rateBase, List<Map<String, Object>> ratingRules,
                                Map<String, Object> rounding, List<Map<String, Object>> eligibilityRules,
                                List<Map<String, Object>> versions, Map<String, Object> billingConfig,
                                List<Map<String, Object>> claimCategories) {
    }

    public List<Product> list() {
        return products.findAllByOrderByProductCode();
    }

    public Product get(Long id) {
        return products.findById(id).orElseThrow(() -> ApiException.notFound("Product", id));
    }

    public ProductDetail detail(Long id) {
        Product p = get(id);
        List<Map<String, Object>> rounding = jdbc.queryForList("SELECT * FROM rounding_rule WHERE product_id=?", id);
        List<Map<String, Object>> billing = jdbc.queryForList("SELECT * FROM billing_product_config WHERE product_id=?", id);
        return new ProductDetail(p,
                jdbc.queryForList("SELECT * FROM rate_base WHERE product_id=? ORDER BY risk_class, age_from", id),
                jdbc.queryForList("SELECT * FROM rating_rule WHERE product_id=? ORDER BY rule_order", id),
                rounding.isEmpty() ? null : rounding.get(0),
                jdbc.queryForList("SELECT * FROM product_eligibility_rule WHERE product_id=? ORDER BY rule_id", id),
                jdbc.queryForList("SELECT product_version_id, version_no, created_by, created_dt FROM product_version WHERE product_id=? ORDER BY version_no DESC", id),
                billing.isEmpty() ? null : billing.get(0),
                jdbc.queryForList("SELECT * FROM claim_category WHERE line_of_business=? ORDER BY category_code", p.getLineOfBusiness()));
    }

    @Transactional
    public Product create(ProductRequest r, String actor) {
        if (products.findByProductCode(r.productCode()).isPresent()) {
            throw ApiException.duplicate("Product code already exists");
        }
        Product p = new Product();
        p.setProductCode(r.productCode());
        apply(p, r);
        products.saveAndFlush(p);
        jdbc.update("INSERT INTO rounding_rule (product_id, scale_digits, rounding_mode, min_premium) VALUES (?,2,'HALF_UP',0)", p.getId());
        jdbc.update("INSERT INTO billing_product_config (product_id) VALUES (?)", p.getId());
        snapshot(p, actor);
        audit.log(actor, "PRODUCT_CREATE", "PRODUCT", p.getId(), p.getProductCode());
        return p;
    }

    @Transactional
    public Product update(Long id, ProductRequest r, String actor) {
        Product p = get(id);
        apply(p, r);
        p.setVersionNo(p.getVersionNo() + 1);
        p.setUpdatedDt(LocalDateTime.now());
        snapshot(p, actor);
        audit.log(actor, "PRODUCT_UPDATE", "PRODUCT", id, "v" + p.getVersionNo());
        return p;
    }

    @Transactional
    public Product setStatus(Long id, String status, String actor) {
        if (!List.of("A", "D", "S", "R").contains(status)) {
            throw ApiException.validation("Status must be A (active), D (draft), S (suspended) or R (retired)");
        }
        Product p = get(id);
        if ("R".equals(p.getStatus())) {
            throw ApiException.rule("Retired products cannot be reactivated");
        }
        p.setStatus(status);
        p.setUpdatedDt(LocalDateTime.now());
        audit.log(actor, "PRODUCT_STATUS", "PRODUCT", id, status);
        return p;
    }

    @Transactional
    public Map<String, Object> addRatingRule(Long productId, RatingRuleRequest r, String actor) {
        get(productId);
        if (!List.of("LOADING", "DISCOUNT", "SURCHARGE", "FEE", "TAX").contains(r.ruleKind())) {
            throw ApiException.validation("Invalid rule kind");
        }
        if (!List.of("PCT", "FLAT").contains(r.amountType())) {
            throw ApiException.validation("amountType must be PCT or FLAT");
        }
        jdbc.update("""
                INSERT INTO rating_rule (product_id, rule_order, rule_name, rule_kind, condition_field, condition_op,
                  condition_value, amount_type, amount) VALUES (?,?,?,?,?,?,?,?,?)""",
                productId, r.ruleOrder() == null ? 50 : r.ruleOrder(), r.ruleName(), r.ruleKind(),
                r.conditionField() == null ? "ANY" : r.conditionField(), r.conditionOp() == null ? "ANY" : r.conditionOp(),
                r.conditionValue(), r.amountType(), r.amount());
        audit.log(actor, "RATING_RULE_ADD", "PRODUCT", productId, r.ruleName());
        return Map.of("status", "OK");
    }

    @Transactional
    public void toggleRatingRule(Long productId, Long ruleId, boolean active, String actor) {
        int n = jdbc.update("UPDATE rating_rule SET active=? WHERE rule_id=? AND product_id=?", active ? "Y" : "N", ruleId, productId);
        if (n == 0) {
            throw ApiException.notFound("Rating rule", ruleId);
        }
        audit.log(actor, "RATING_RULE_TOGGLE", "PRODUCT", productId, ruleId + "=" + active);
    }

    private void snapshot(Product p, String actor) {
        String snap = String.format(
                "{\"code\":\"%s\",\"name\":\"%s\",\"baseRate\":%s,\"age\":[%d,%d],\"si\":[%s,%s],\"term\":[%d,%d]}",
                p.getProductCode(), p.getProductName(), p.getBaseRate(), p.getMinAge(), p.getMaxAge(),
                p.getMinSumInsured(), p.getMaxSumInsured(), p.getMinTermMonths(), p.getMaxTermMonths());
        jdbc.update("INSERT INTO product_version (product_id, version_no, snapshot, created_by) VALUES (?,?,?,?)",
                p.getId(), p.getVersionNo(), snap, actor);
    }

    private void apply(Product p, ProductRequest r) {
        List<String> errors = new ArrayList<>();
        if (r.productName() == null || r.productName().isBlank()) {
            errors.add("productName is required");
        }
        if (!List.of("LIFE", "HEALTH", "MOTOR", "HOME").contains(r.lineOfBusiness())) {
            errors.add("lineOfBusiness must be LIFE, HEALTH, MOTOR or HOME");
        }
        if (r.baseRate() == null || r.baseRate().signum() <= 0) {
            errors.add("baseRate must be positive");
        }
        if (r.minAge() != null && r.maxAge() != null && r.minAge() > r.maxAge()) {
            errors.add("minAge must be <= maxAge");
        }
        if (r.minSumInsured() != null && r.maxSumInsured() != null && r.minSumInsured().compareTo(r.maxSumInsured()) > 0) {
            errors.add("minSumInsured must be <= maxSumInsured");
        }
        if (r.minTermMonths() != null && r.maxTermMonths() != null && r.minTermMonths() > r.maxTermMonths()) {
            errors.add("minTermMonths must be <= maxTermMonths");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION, "Product validation failed", errors);
        }
        p.setProductName(r.productName());
        p.setLineOfBusiness(r.lineOfBusiness());
        p.setDescription(r.description());
        p.setBaseRate(r.baseRate());
        p.setMinAge(r.minAge() == null ? 18 : r.minAge());
        p.setMaxAge(r.maxAge() == null ? 99 : r.maxAge());
        p.setMinSumInsured(r.minSumInsured() == null ? BigDecimal.ZERO : r.minSumInsured());
        p.setMaxSumInsured(r.maxSumInsured() == null ? new BigDecimal("99999999") : r.maxSumInsured());
        p.setMinTermMonths(r.minTermMonths() == null ? 12 : r.minTermMonths());
        p.setMaxTermMonths(r.maxTermMonths() == null ? 12 : r.maxTermMonths());
    }
}
