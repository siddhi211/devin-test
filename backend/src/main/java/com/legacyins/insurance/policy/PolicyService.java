package com.legacyins.insurance.policy;

import com.legacyins.insurance.auth.AuditService;
import com.legacyins.insurance.billing.BillingService;
import com.legacyins.insurance.common.ApiException;
import com.legacyins.insurance.common.ErrorCode;
import com.legacyins.insurance.common.Money;
import com.legacyins.insurance.common.PageResponse;
import com.legacyins.insurance.customer.Customer;
import com.legacyins.insurance.customer.CustomerService;
import com.legacyins.insurance.notification.NotificationService;
import com.legacyins.insurance.product.PremiumService;
import com.legacyins.insurance.product.Product;
import com.legacyins.insurance.product.ProductService;
import com.legacyins.insurance.workflow.WorkflowService;
import com.legacyins.insurance.workflow.WorkflowSubjectHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PolicyService implements WorkflowSubjectHandler {
    private final PolicyRepository policies;
    private final PolicyStateService state;
    private final CustomerService customers;
    private final ProductService products;
    private final PremiumService premiums;
    private final UnderwritingService underwriting;
    private final BillingService billing;
    private final WorkflowService workflow;
    private final NotificationService notifications;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    public record PolicyRequest(Long customerId, Long productId, BigDecimal sumInsured, Integer termMonths,
                                String paymentFrequency, String riskClass, Boolean smoker, String occupationCode,
                                String medicalCodes, LocalDate startDt, String branchCd) {
    }

    public record EndorsementRequest(String endorsementType, String description, BigDecimal newSumInsured) {
    }

    public record PolicySummary(Long id, String policyNo, String status, Long customerId, String customerName,
                                Long productId, String productCode, BigDecimal sumInsured, BigDecimal annualPremium,
                                String paymentFrequency, LocalDate startDt, LocalDate endDt) {
    }

    public Policy get(Long id) {
        return state.get(id);
    }

    public PageResponse<PolicySummary> search(String q, String status, Long customerId, Long productId, int page, int size) {
        String like = q == null || q.isBlank() ? null : "%" + q.toLowerCase().trim() + "%";
        return PageResponse.of(policies.search(like, blank(status), customerId, productId,
                PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, "id"))), this::summary);
    }

    private PolicySummary summary(Policy p) {
        Customer c = customers.get(p.getCustomerId());
        Product pr = products.get(p.getProductId());
        return new PolicySummary(p.getId(), p.getPolicyNo(), p.getStatus(), c.getId(), c.displayName(), pr.getId(),
                pr.getProductCode(), p.getSumInsured(), p.getAnnualPremium(), p.getPaymentFrequency(), p.getStartDt(), p.getEndDt());
    }

    public Map<String, Object> detail(Long id) {
        Policy p = get(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("policy", p);
        Customer c = customers.get(p.getCustomerId());
        m.put("customer", Map.of("id", c.getId(), "customerNo", c.getCustomerNo(), "name", c.displayName(),
                "kycStatus", c.getKycStatus(), "status", c.getStatus()));
        m.put("product", products.get(p.getProductId()));
        m.put("statusHistory", jdbc.queryForList("SELECT * FROM policy_status_history WHERE policy_id=? ORDER BY history_id DESC", id));
        m.put("versions", jdbc.queryForList("SELECT * FROM policy_version WHERE policy_id=? ORDER BY version_no DESC", id));
        m.put("endorsements", jdbc.queryForList("SELECT * FROM policy_endorsement WHERE policy_id=? ORDER BY endorsement_id DESC", id));
        m.put("uwDecisions", jdbc.queryForList("SELECT * FROM uw_decision WHERE policy_id=? ORDER BY decision_id DESC", id));
        m.put("claims", jdbc.queryForList("SELECT claim_id, claim_no, status, category_code, claimed_amount, approved_amount, settled_amount, incident_dt FROM claim WHERE policy_id=? ORDER BY claim_id DESC", id));
        m.put("lifecycle", jdbc.queryForList("SELECT * FROM policy_lifecycle WHERE policy_id=?", id).stream().findFirst().orElse(null));
        m.put("renewals", jdbc.queryForList("SELECT * FROM renewal_request WHERE policy_id=? ORDER BY renewal_id DESC", id));
        m.put("cancellations", jdbc.queryForList("SELECT * FROM cancellation_request WHERE policy_id=? ORDER BY cancellation_id DESC", id));
        m.put("workflows", workflow.instancesForSubject("UW_REFERRAL", id));
        m.put("outstanding", billing.outstanding(id, null));
        m.put("allowedTransitions", PolicyStateService.TRANSITIONS.getOrDefault(p.getStatus(), Set.of()));
        return m;
    }

    @Transactional
    public Policy create(PolicyRequest r, String actor) {
        Customer c = customers.get(r.customerId());
        Product pr = products.get(r.productId());
        checkEligibility(c, pr);
        Policy p = new Policy();
        p.setCustomerId(c.getId());
        p.setProductId(pr.getId());
        p.setCreatedBy(actor);
        apply(p, r, c);
        p.setPolicyNo("TMP-" + UUID.randomUUID().toString().substring(0, 12));
        policies.saveAndFlush(p);
        p.setPolicyNo(String.format("POL%08d", p.getId()));
        jdbc.update("INSERT INTO policy_status_history (policy_id, from_status, to_status, reason, actor) VALUES (?,?,?,?,?)",
                p.getId(), null, "DRAFT", "Policy created", actor);
        audit.log(actor, "POLICY_CREATE", "POLICY", p.getId(), p.getPolicyNo());
        return p;
    }

    @Transactional
    public Policy update(Long id, PolicyRequest r, String actor) {
        Policy p = get(id);
        if (!"DRAFT".equals(p.getStatus())) {
            throw ApiException.rule("Only DRAFT policies can be edited");
        }
        apply(p, r, customers.get(p.getCustomerId()));
        audit.log(actor, "POLICY_UPDATE", "POLICY", id, null);
        return p;
    }

    private void apply(Policy p, PolicyRequest r, Customer c) {
        if (r.sumInsured() == null || r.sumInsured().signum() <= 0) {
            throw ApiException.validation("sumInsured must be positive");
        }
        p.setSumInsured(r.sumInsured());
        p.setTermMonths(r.termMonths() == null ? products.get(p.getProductId()).getMinTermMonths() : r.termMonths());
        p.setPaymentFrequency(r.paymentFrequency() == null ? "ANNUAL" : r.paymentFrequency());
        p.setRiskClass(r.riskClass() == null ? (List.of("STD", "PREF", "SUB", "HIGH").contains(c.getRiskClass()) ? c.getRiskClass() : "STD") : r.riskClass());
        p.setSmokerFlag(Boolean.TRUE.equals(r.smoker()) ? "Y" : "N");
        p.setOccupationCode(r.occupationCode() == null ? c.getOccupationCode() : r.occupationCode());
        p.setMedicalCodes(blank(r.medicalCodes()));
        LocalDate start = r.startDt() == null ? LocalDate.now() : r.startDt();
        if (start.isBefore(LocalDate.now().minusDays(30))) {
            throw ApiException.rule("Start date cannot be more than 30 days in the past");
        }
        p.setStartDt(start);
        p.setEndDt(start.plusMonths(p.getTermMonths()).minusDays(1));
        p.setBranchCd(r.branchCd());
        requote(p, c);
    }

    private void requote(Policy p, Customer c) {
        if ("INDIVIDUAL".equals(c.getCustomerType()) && c.getDateOfBirth() == null) {
            throw ApiException.rule("Customer date of birth is required for rating");
        }
        LocalDate dob = c.getDateOfBirth() == null ? p.getStartDt().minusYears(30) : c.getDateOfBirth();
        PremiumService.QuoteResult q = premiums.quote(new PremiumService.QuoteRequest(p.getProductId(), c.getId(), dob,
                p.getSumInsured(), p.getTermMonths(), p.getPaymentFrequency(), p.getRiskClass(), "Y".equals(p.getSmokerFlag()),
                p.getOccupationCode(), p.getStartDt()), "SYSTEM", false);
        p.setAnnualPremium(q.annualPremium());
        p.setInstallmentPremium(q.installmentPremium());
    }

    private void checkEligibility(Customer c, Product pr) {
        List<String> errors = new ArrayList<>();
        if (!"A".equals(pr.getStatus())) {
            errors.add("Product " + pr.getProductCode() + " is not open for sale");
        }
        if (!"A".equals(c.getStatus())) {
            errors.add("Customer status " + c.getStatus() + " is not eligible for new business");
        }
        for (Map<String, Object> rule : jdbc.queryForList("SELECT * FROM product_eligibility_rule WHERE product_id=? AND active='Y'", pr.getId())) {
            String type = (String) rule.get("rule_type");
            String value = (String) rule.get("rule_value");
            boolean fail = switch (type) {
                case "KYC_REQUIRED" -> "Y".equals(value) && !"VERIFIED".equals(c.getKycStatus());
                case "MAX_OPEN_CLAIMS" -> openClaims(c.getId()) > Integer.parseInt(value);
                case "BLOCKED_STATUS" -> value.equals(c.getStatus());
                default -> false;
            };
            if (fail) {
                errors.add((String) rule.get("message"));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.BUSINESS_RULE, "Customer is not eligible for " + pr.getProductCode(), errors);
        }
    }

    private int openClaims(Long customerId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM claim WHERE customer_id=? AND status NOT IN ('SETTLED','CLOSED','REJECTED')",
                Integer.class, customerId);
        return n == null ? 0 : n;
    }

    @Transactional
    public Map<String, Object> submit(Long id, String actor) {
        Policy p = get(id);
        if (!"DRAFT".equals(p.getStatus())) {
            throw ApiException.rule("Only DRAFT policies can be submitted");
        }
        Customer c = customers.get(p.getCustomerId());
        checkEligibility(c, products.get(p.getProductId()));
        requote(p, c);
        state.transition(p, "SUBMITTED", "Submitted for underwriting", actor);
        UnderwritingService.Evaluation e = underwriting.evaluate(p, c.getDateOfBirth());
        underwriting.record(id, e, "SYSTEM", null);
        switch (e.outcome()) {
            case "DECLINE" -> state.transition(p, "DECLINED", "Automatic underwriting decline", "SYSTEM");
            case "REFER" -> {
                state.transition(p, "PENDING_UW", "Referred to underwriter (" + e.totalPoints() + " points)", "SYSTEM");
                workflow.start("UW_REFERRAL", id, p.getPolicyNo(), p.getSumInsured(), actor);
            }
            default -> state.transition(p, "UW_APPROVED", "Automatic underwriting approval", "SYSTEM");
        }
        audit.log(actor, "POLICY_SUBMIT", "POLICY", id, e.outcome());
        return Map.of("policy", p, "underwriting", e);
    }

    @Override
    public String subjectType() {
        return "UW_REFERRAL";
    }

    @Override
    public void onApproved(Long policyId, String actor, String note) {
        Policy p = get(policyId);
        if (!"PENDING_UW".equals(p.getStatus())) {
            return;
        }
        if (!"ADMIN".equals(actor) && !underwriting.hasAuthority(actor, p.getSumInsured())) {
            throw ApiException.rule("Underwriter " + actor + " lacks authority for sum insured " + p.getSumInsured());
        }
        underwriting.record(policyId, new UnderwritingService.Evaluation("APPROVED", 0, List.of("Manual approval")), actor, note);
        state.transition(p, "UW_APPROVED", "Underwriter approved" + (note == null ? "" : ": " + note), actor);
    }

    @Override
    public void onRejected(Long policyId, String actor, String note) {
        Policy p = get(policyId);
        if (!"PENDING_UW".equals(p.getStatus())) {
            return;
        }
        underwriting.record(policyId, new UnderwritingService.Evaluation("DECLINED", 0, List.of("Manual decline")), actor, note);
        state.transition(p, "DECLINED", "Underwriter declined: " + note, actor);
    }

    @Transactional
    public Policy issue(Long id, String actor) {
        Policy p = get(id);
        if (!"UW_APPROVED".equals(p.getStatus())) {
            throw ApiException.rule("Only UW_APPROVED policies can be issued");
        }
        if (p.getStartDt().isBefore(LocalDate.now().minusDays(30))) {
            throw ApiException.rule("Start date is too far in the past; amend and resubmit");
        }
        p.setIssueDt(LocalDate.now());
        p.setEndDt(p.getStartDt().plusMonths(p.getTermMonths()).minusDays(1));
        state.transition(p, "ACTIVE", "Policy issued", actor);
        state.addVersion(p, "NEW", "Policy issued", actor);
        billing.generateSchedule(p, p.getStartDt(), actor);
        jdbc.update("INSERT INTO policy_lifecycle (policy_id, updated_by) VALUES (?,?) ON CONFLICT (policy_id) DO NOTHING", id, actor);
        Map<String, Object> contact = state.customerContact(p.getCustomerId());
        notifications.enqueue("POLICY_ISSUED", (String) contact.get("email"),
                Map.of("name", contact.get("name"), "policyNo", p.getPolicyNo(), "startDt", p.getStartDt(), "endDt", p.getEndDt(),
                        "premium", p.getAnnualPremium()), "POLICY", id, "ISSUED-" + id);
        audit.log(actor, "POLICY_ISSUE", "POLICY", id, p.getPolicyNo());
        return p;
    }

    @Transactional
    public Policy withdraw(Long id, String reason, String actor) {
        Policy p = get(id);
        if (!List.of("DRAFT", "SUBMITTED", "PENDING_UW", "UW_APPROVED").contains(p.getStatus())) {
            throw ApiException.rule("Only policies that are not yet issued can be withdrawn");
        }
        workflow.cancel("UW_REFERRAL", id, actor, "Policy withdrawn");
        state.transition(p, "WITHDRAWN", reason == null ? "Withdrawn" : reason, actor);
        audit.log(actor, "POLICY_WITHDRAW", "POLICY", id, reason);
        return p;
    }

    @Transactional
    public Policy endorse(Long id, EndorsementRequest r, String actor) {
        Policy p = get(id);
        if (!"ACTIVE".equals(p.getStatus())) {
            throw ApiException.rule("Only ACTIVE policies can be endorsed");
        }
        String type = r.endorsementType() == null ? "OTHER" : r.endorsementType();
        if (!List.of("SUM_INSURED", "ADDRESS", "BENEFICIARY", "OTHER").contains(type)) {
            throw ApiException.validation("endorsementType must be SUM_INSURED, ADDRESS, BENEFICIARY or OTHER");
        }
        String oldValue = null, newValue = null;
        BigDecimal adjustment = BigDecimal.ZERO;
        if ("SUM_INSURED".equals(type)) {
            if (r.newSumInsured() == null || r.newSumInsured().signum() <= 0) {
                throw ApiException.validation("newSumInsured is required");
            }
            Product pr = products.get(p.getProductId());
            if (r.newSumInsured().compareTo(pr.getMinSumInsured()) < 0 || r.newSumInsured().compareTo(pr.getMaxSumInsured()) > 0) {
                throw ApiException.rule("Sum insured outside product range");
            }
            oldValue = p.getSumInsured().toPlainString();
            BigDecimal oldInstallment = p.getInstallmentPremium();
            BigDecimal oldAnnual = p.getAnnualPremium();
            p.setSumInsured(r.newSumInsured());
            requote(p, customers.get(p.getCustomerId()));
            newValue = p.getSumInsured().toPlainString();
            long remainingDays = Math.max(0, ChronoUnit.DAYS.between(LocalDate.now(), p.getEndDt()));
            long totalDays = Math.max(1, ChronoUnit.DAYS.between(p.getStartDt(), p.getEndDt()));
            adjustment = Money.of(p.getAnnualPremium().subtract(oldAnnual)
                    .multiply(BigDecimal.valueOf(remainingDays)).divide(BigDecimal.valueOf(totalDays), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(p.getTermMonths())).divide(BigDecimal.valueOf(12), 6, RoundingMode.HALF_UP));
            BigDecimal delta = p.getInstallmentPremium().subtract(oldInstallment);
            List<Map<String, Object>> future = jdbc.queryForList(
                    "SELECT schedule_id FROM premium_schedule WHERE policy_id=? AND status='DUE' AND due_dt>=?", id, LocalDate.now());
            for (Map<String, Object> s : future) {
                jdbc.update("UPDATE premium_schedule SET amount_due=? WHERE schedule_id=?", p.getInstallmentPremium(), s.get("schedule_id"));
            }
            BigDecimal billedDelta = delta.multiply(BigDecimal.valueOf(future.size()));
            if (billedDelta.signum() != 0) {
                List<com.legacyins.insurance.billing.LedgerService.Line> lines = billedDelta.signum() > 0
                        ? List.of(com.legacyins.insurance.billing.LedgerService.Line.dr("PREM_RECV", billedDelta),
                                  com.legacyins.insurance.billing.LedgerService.Line.cr("PREM_INCOME", billedDelta))
                        : List.of(com.legacyins.insurance.billing.LedgerService.Line.dr("PREM_INCOME", billedDelta.negate()),
                                  com.legacyins.insurance.billing.LedgerService.Line.cr("PREM_RECV", billedDelta.negate()));
                ledgerPost(p, lines, actor);
            }
            p.setVersionNo(p.getVersionNo() + 1);
            state.addVersion(p, "ENDORSEMENT", "Sum insured " + oldValue + " -> " + newValue, actor);
        }
        jdbc.update("""
                INSERT INTO policy_endorsement (policy_id, endorsement_type, description, old_value, new_value, adjustment, effective_dt, created_by)
                VALUES (?,?,?,?,?,?,?,?)""", id, type, r.description(), oldValue, newValue, adjustment, LocalDate.now(), actor);
        audit.log(actor, "POLICY_ENDORSE", "POLICY", id, type);
        return p;
    }

    private void ledgerPost(Policy p, List<com.legacyins.insurance.billing.LedgerService.Line> lines, String actor) {
        ledger.post("ENDORSEMENT", "POLICY", p.getId(), p.getId(), "Endorsement premium adjustment", actor, lines);
    }

    private final com.legacyins.insurance.billing.LedgerService ledger;

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
