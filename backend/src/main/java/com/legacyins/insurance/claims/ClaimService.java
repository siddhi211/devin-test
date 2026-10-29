package com.legacyins.insurance.claims;

import com.legacyins.insurance.auth.AuditService;
import com.legacyins.insurance.billing.LedgerService;
import com.legacyins.insurance.common.ApiException;
import com.legacyins.insurance.common.ErrorCode;
import com.legacyins.insurance.common.Money;
import com.legacyins.insurance.common.PageResponse;
import com.legacyins.insurance.notification.NotificationService;
import com.legacyins.insurance.policy.Policy;
import com.legacyins.insurance.policy.PolicyStateService;
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
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Claim lifecycle: registration with eligibility and fraud screening, assessment, approval, settlement, reopen. */
@Service
@RequiredArgsConstructor
public class ClaimService implements WorkflowSubjectHandler {
    public static final Map<String, Set<String>> TRANSITIONS = Map.ofEntries(
            Map.entry("REGISTERED", Set.of("ASSIGNED", "REJECTED", "CLOSED")),
            Map.entry("ASSIGNED", Set.of("ASSIGNED", "UNDER_INVESTIGATION", "PENDING_INFO", "ASSESSED", "REJECTED")),
            Map.entry("UNDER_INVESTIGATION", Set.of("PENDING_INFO", "ASSESSED", "REJECTED")),
            Map.entry("PENDING_INFO", Set.of("UNDER_INVESTIGATION", "ASSESSED", "REJECTED", "CLOSED")),
            Map.entry("ASSESSED", Set.of("ASSESSED", "PENDING_APPROVAL", "APPROVED", "PARTIAL", "REJECTED", "UNDER_INVESTIGATION")),
            Map.entry("PENDING_APPROVAL", Set.of("APPROVED", "PARTIAL", "REJECTED", "ASSESSED")),
            Map.entry("APPROVED", Set.of("SETTLED", "CLOSED")),
            Map.entry("PARTIAL", Set.of("SETTLED", "CLOSED")),
            Map.entry("REJECTED", Set.of("REOPENED", "CLOSED")),
            Map.entry("SETTLED", Set.of("CLOSED", "REOPENED", "APPROVED", "PARTIAL")),
            Map.entry("CLOSED", Set.of("REOPENED")),
            Map.entry("REOPENED", Set.of("ASSIGNED", "UNDER_INVESTIGATION", "ASSESSED")));
    private static final Set<String> TERMINAL = Set.of("SETTLED", "CLOSED", "REJECTED");
    public static final BigDecimal AUTO_APPROVAL_CEILING = new BigDecimal("10000");
    public static final int FRAUD_REFERRAL_SCORE = 50;
    private static final int MAX_REOPENS = 3;
    private static final int REOPEN_WINDOW_DAYS = 60;

    private final ClaimRepository claims;
    private final PolicyStateService policies;
    private final LedgerService ledger;
    private final WorkflowService workflow;
    private final NotificationService notifications;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    public record RegisterRequest(Long policyId, String categoryCode, String subtypeCode, LocalDate incidentDt,
                                  LocalDate reportedDt, String description, BigDecimal claimedAmount) {
    }

    public record AssessRequest(BigDecimal excludedAmount, BigDecimal depreciationPct, BigDecimal salvageAmount, String note) {
    }

    public record DecideRequest(String decision, BigDecimal approvedAmount, String reason) {
    }

    public record SettleRequest(String method, String payeeName, BigDecimal amount) {
    }

    public Claim get(Long id) {
        return claims.findById(id).orElseThrow(() -> ApiException.notFound("Claim", id));
    }

    public PageResponse<Map<String, Object>> search(String q, String status, Long policyId, Long adjusterId, int page, int size) {
        String like = q == null || q.isBlank() ? null : "%" + q.toLowerCase().trim() + "%";
        String st = status == null || status.isBlank() ? null : ("PARTIALLY_APPROVED".equals(status) ? "PARTIAL" : status);
        return PageResponse.of(claims.search(like, st, policyId, adjusterId,
                PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, "id"))), this::row);
    }

    private Map<String, Object> row(Claim c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("claimNo", c.getClaimNo());
        m.put("status", c.getStatus());
        m.put("displayStatus", displayStatus(c.getStatus()));
        m.put("categoryCode", c.getCategoryCode());
        m.put("policyId", c.getPolicyId());
        m.put("policyNo", jdbc.queryForObject("SELECT policy_no FROM policy WHERE policy_id=?", String.class, c.getPolicyId()));
        m.put("claimedAmount", c.getClaimedAmount());
        m.put("approvedAmount", c.getApprovedAmount());
        m.put("settledAmount", c.getSettledAmount());
        m.put("priority", c.getPriority());
        m.put("fraudScore", c.getFraudScore());
        m.put("incidentDt", c.getIncidentDt());
        m.put("slaDueDt", c.getSlaDueDt());
        m.put("slaBreached", c.getSlaBreached());
        m.put("assignedAdjusterId", c.getAssignedAdjusterId());
        return m;
    }

    public static String displayStatus(String status) {
        return "PARTIAL".equals(status) ? "PARTIALLY_APPROVED" : status;
    }

    public Map<String, Object> detail(Long id) {
        Claim c = get(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("claim", c);
        m.put("displayStatus", displayStatus(c.getStatus()));
        m.put("policy", jdbc.queryForMap("""
                SELECT p.policy_id, p.policy_no, p.status, p.sum_insured, p.start_dt, p.end_dt, pr.product_code, pr.line_of_business
                FROM policy p JOIN insurance_product pr ON pr.product_id=p.product_id WHERE p.policy_id=?""", c.getPolicyId()));
        m.put("customer", policies.customerContact(c.getCustomerId()));
        m.put("category", jdbc.queryForMap("SELECT * FROM claim_category WHERE category_code=?", c.getCategoryCode()));
        m.put("adjuster", c.getAssignedAdjusterId() == null ? null
                : jdbc.queryForMap("SELECT user_id, username, full_name FROM user_account WHERE user_id=?", c.getAssignedAdjusterId()));
        m.put("history", jdbc.queryForList("SELECT * FROM claim_status_history WHERE claim_id=? ORDER BY history_id DESC", id));
        m.put("notes", jdbc.queryForList("SELECT * FROM claim_note WHERE claim_id=? ORDER BY note_id DESC", id));
        m.put("documents", jdbc.queryForList("SELECT * FROM claim_document WHERE claim_id=? ORDER BY document_id DESC", id));
        m.put("assessments", jdbc.queryForList("SELECT * FROM claim_assessment WHERE claim_id=? ORDER BY assessment_id DESC", id));
        m.put("riskFlags", jdbc.queryForList("SELECT * FROM claim_risk_flag WHERE claim_id=? ORDER BY flag_id", id));
        m.put("settlements", jdbc.queryForList("SELECT * FROM claim_settlement WHERE claim_id=? ORDER BY settlement_id", id));
        m.put("payoutEvents", jdbc.queryForList("SELECT * FROM claim_payout_event WHERE claim_id=? ORDER BY event_id", id));
        m.put("workflows", workflow.instancesForSubject("CLAIM", id));
        m.put("allowedTransitions", TRANSITIONS.getOrDefault(c.getStatus(), Set.of()));
        return m;
    }

    @Transactional
    public Map<String, Object> register(RegisterRequest r, String actor) {
        List<String> errors = new ArrayList<>();
        if (r.policyId() == null) errors.add("policyId is required");
        if (r.categoryCode() == null) errors.add("categoryCode is required");
        if (r.incidentDt() == null) errors.add("incidentDt is required");
        if (r.description() == null || r.description().isBlank()) errors.add("description is required");
        if (r.claimedAmount() == null || r.claimedAmount().signum() <= 0) errors.add("claimedAmount must be positive");
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION, "Claim validation failed", errors);
        }
        LocalDate reported = r.reportedDt() == null ? LocalDate.now() : r.reportedDt();
        if (r.incidentDt().isAfter(LocalDate.now()) || r.incidentDt().isAfter(reported)) {
            throw ApiException.validation("Incident date cannot be in the future or after the reported date");
        }
        Policy p = policies.get(r.policyId());
        Map<String, Object> cat = jdbc.queryForList("SELECT * FROM claim_category WHERE category_code=? AND active='Y'", r.categoryCode())
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Claim category", r.categoryCode()));
        String lob = jdbc.queryForObject("SELECT line_of_business FROM insurance_product WHERE product_id=?", String.class, p.getProductId());

        List<String> blocking = new ArrayList<>();
        List<String> findings = new ArrayList<>();
        if (!List.of("ACTIVE", "GRACE").contains(p.getStatus())) {
            blocking.add("POLICY_NOT_IN_FORCE: policy status is " + p.getStatus());
        }
        if (!lob.equals(cat.get("line_of_business"))) {
            blocking.add("CATEGORY_MISMATCH: " + r.categoryCode() + " does not apply to " + lob + " policies");
        }
        if (p.getStartDt() != null && (r.incidentDt().isBefore(p.getStartDt()) || (p.getEndDt() != null && r.incidentDt().isAfter(p.getEndDt())))) {
            blocking.add("OUTSIDE_COVER: incident date outside cover period " + p.getStartDt() + " to " + p.getEndDt());
        }
        int waiting = ((Number) cat.get("waiting_days")).intValue();
        if (p.getStartDt() != null && waiting > 0 && r.incidentDt().isBefore(p.getStartDt().plusDays(waiting)) && p.getRenewalCount() == 0) {
            blocking.add("WAITING_PERIOD: incident within " + waiting + "-day waiting period");
        }
        String excluded = (String) cat.get("excluded_causes");
        if (excluded != null && r.subtypeCode() != null && Arrays.asList(excluded.split(",")).contains(r.subtypeCode())) {
            blocking.add("EXCLUDED_CAUSE: " + r.subtypeCode() + " is excluded");
        }
        BigDecimal paidOnPolicy = jdbc.queryForObject(
                "SELECT COALESCE(SUM(GREATEST(settled_amount, COALESCE(approved_amount,0))),0) FROM claim WHERE policy_id=? AND status NOT IN ('REJECTED')",
                BigDecimal.class, p.getId());
        BigDecimal remainingSi = p.getSumInsured().subtract(paidOnPolicy);
        if (r.claimedAmount().compareTo(remainingSi) > 0) {
            blocking.add("EXCEEDS_SUM_INSURED: claimed " + r.claimedAmount() + " exceeds remaining sum insured " + remainingSi);
        }
        long reportLag = ChronoUnit.DAYS.between(r.incidentDt(), reported);
        int reportingDays = ((Number) cat.get("reporting_days")).intValue();
        if (reportLag > reportingDays) {
            findings.add("LATE_REPORTING: reported " + reportLag + " days after incident (limit " + reportingDays + ")");
        }
        if (!blocking.isEmpty()) {
            throw new ApiException(ErrorCode.BUSINESS_RULE, "Claim is not eligible", blocking);
        }

        Claim c = new Claim();
        c.setPolicyId(p.getId());
        c.setCustomerId(p.getCustomerId());
        c.setCategoryCode(r.categoryCode());
        c.setSubtypeCode(r.subtypeCode());
        c.setIncidentDt(r.incidentDt());
        c.setReportedDt(reported);
        c.setDescription(r.description());
        c.setClaimedAmount(Money.of(r.claimedAmount()));
        c.setCreatedBy(actor);
        c.setSlaDueDt(LocalDate.now().plusDays(((Number) cat.get("sla_days")).intValue()));
        c.setFindings(findings.isEmpty() ? null : String.join("; ", findings));
        c.setClaimNo(nextClaimNo());
        claims.saveAndFlush(c);
        history(c, null, "REGISTERED", "Claim registered", actor);

        int score = 0;
        List<String[]> flags = new ArrayList<>();
        if (p.getStartDt() != null && ChronoUnit.DAYS.between(p.getStartDt(), r.incidentDt()) < 30) {
            flags.add(new String[]{"EARLY_CLAIM", "30", "Incident within 30 days of inception"});
        }
        if (r.claimedAmount().compareTo(p.getSumInsured().multiply(new BigDecimal("0.8"))) >= 0) {
            flags.add(new String[]{"HIGH_VALUE", "20", "Claimed amount >= 80% of sum insured"});
        }
        Integer recent = jdbc.queryForObject("SELECT count(*) FROM claim WHERE customer_id=? AND claim_id<>? AND created_dt > now() - interval '90 days'",
                Integer.class, p.getCustomerId(), c.getId());
        if (recent != null && recent >= 2) {
            flags.add(new String[]{"FREQUENT_CLAIMANT", "25", recent + " other claims in last 90 days"});
        }
        if ("GRACE".equals(p.getStatus())) {
            flags.add(new String[]{"GRACE_PERIOD", "15", "Policy in grace period with unpaid premium"});
        }
        if (reportLag > reportingDays) {
            flags.add(new String[]{"LATE_REPORTING", "10", "Reported after the reporting window"});
        }
        for (String[] f : flags) {
            jdbc.update("INSERT INTO claim_risk_flag (claim_id, flag_code, score, detail, raised_by) VALUES (?,?,?,?,?)",
                    c.getId(), f[0], Integer.parseInt(f[1]), f[2], "SYSTEM");
            score += Integer.parseInt(f[1]);
        }
        c.setFraudScore(score);
        if (score >= FRAUD_REFERRAL_SCORE || c.getClaimedAmount().compareTo(new BigDecimal("50000")) >= 0) {
            c.setPriority("HIGH");
        }
        Map<String, Object> contact = policies.customerContact(c.getCustomerId());
        notifications.enqueue("CLAIM_REGISTERED", (String) contact.get("email"),
                Map.of("name", contact.get("name"), "claimNo", c.getClaimNo()), "CLAIM", c.getId(), "CLMREG-" + c.getId());
        audit.log(actor, "CLAIM_REGISTER", "CLAIM", c.getId(), c.getClaimNo() + " " + c.getClaimedAmount());
        return Map.of("claim", c, "findings", findings, "riskFlags", flags.stream().map(f -> f[0]).toList());
    }

    private String nextClaimNo() {
        int year = LocalDate.now().getYear();
        Integer n = jdbc.queryForObject("""
                INSERT INTO claim_sequence (seq_year, next_val) VALUES (?, 1)
                ON CONFLICT (seq_year) DO UPDATE SET next_val = claim_sequence.next_val + 1, updated_dt = now()
                RETURNING next_val""", Integer.class, year);
        return String.format("CLM-%d-%06d", year, n);
    }

    private void transition(Claim c, String to, String reason, String actor) {
        String from = c.getStatus();
        if (!TRANSITIONS.getOrDefault(from, Set.of()).contains(to)) {
            throw ApiException.rule("Claim " + c.getClaimNo() + " cannot move from " + displayStatus(from) + " to " + displayStatus(to));
        }
        c.setStatus(to);
        c.setUpdatedDt(LocalDateTime.now());
        claims.saveAndFlush(c);
        history(c, from, to, reason, actor);
    }

    private void history(Claim c, String from, String to, String reason, String actor) {
        jdbc.update("INSERT INTO claim_status_history (claim_id, from_status, to_status, reason, actor) VALUES (?,?,?,?,?)",
                c.getId(), from, to, reason, actor);
    }

    @Transactional
    public Claim assign(Long id, Long adjusterId, String actor) {
        Claim c = get(id);
        Map<String, Object> u = jdbc.queryForList("""
                SELECT u.user_id, u.username, r.role_name FROM user_account u JOIN user_role r ON r.role_id=u.role_id
                WHERE u.user_id=? AND u.status='A'""", adjusterId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Active user", adjusterId));
        if (!List.of("ADJUSTER", "MANAGER").contains(u.get("role_name"))) {
            throw ApiException.rule("Claims can only be assigned to adjusters or managers");
        }
        c.setAssignedAdjusterId(adjusterId);
        transition(c, "ASSIGNED", "Assigned to " + u.get("username"), actor);
        audit.log(actor, "CLAIM_ASSIGN", "CLAIM", id, String.valueOf(u.get("username")));
        return c;
    }

    @Transactional
    public Claim autoAssign(Long id, String actor) {
        Long adjuster = jdbc.queryForList("""
                SELECT u.user_id FROM user_account u JOIN user_role r ON r.role_id=u.role_id
                LEFT JOIN claim c ON c.assigned_adjuster_id=u.user_id AND c.status NOT IN ('SETTLED','CLOSED','REJECTED')
                WHERE r.role_name='ADJUSTER' AND u.status='A' GROUP BY u.user_id ORDER BY count(c.claim_id), u.user_id LIMIT 1""", Long.class)
                .stream().findFirst().orElseThrow(() -> ApiException.rule("No active adjusters available"));
        return assign(id, adjuster, actor);
    }

    @Transactional
    public Claim move(Long id, String to, String reason, String actor) {
        if (!List.of("UNDER_INVESTIGATION", "PENDING_INFO", "CLOSED").contains(to)) {
            throw ApiException.validation("Use the dedicated endpoints for this transition");
        }
        Claim c = get(id);
        transition(c, to, reason, actor);
        if (reason != null && !reason.isBlank()) {
            addNote(id, "PENDING_INFO".equals(to) ? "INFO_REQUEST" : "STATUS", reason, actor);
        }
        audit.log(actor, "CLAIM_" + to, "CLAIM", id, reason);
        return c;
    }

    @Transactional
    public Map<String, Object> addNote(Long id, String type, String text, String actor) {
        get(id);
        if (text == null || text.isBlank()) {
            throw ApiException.validation("Note text is required");
        }
        jdbc.update("INSERT INTO claim_note (claim_id, note_type, note_text, author) VALUES (?,?,?,?)",
                id, type == null ? "GENERAL" : type, text.length() > 2000 ? text.substring(0, 2000) : text, actor);
        return Map.of("status", "OK");
    }

    @Transactional
    public Map<String, Object> addDocument(Long id, String docType, String fileName, String actor) {
        get(id);
        if (docType == null || fileName == null) {
            throw ApiException.validation("docType and fileName are required");
        }
        jdbc.update("INSERT INTO claim_document (claim_id, doc_type, file_name, uploaded_by) VALUES (?,?,?,?)", id, docType, fileName, actor);
        return Map.of("status", "OK");
    }

    @Transactional
    public Map<String, Object> clearFlag(Long id, Long flagId, String actor) {
        int n = jdbc.update("UPDATE claim_risk_flag SET cleared_dt=now(), cleared_by=? WHERE flag_id=? AND claim_id=? AND cleared_dt IS NULL",
                actor, flagId, id);
        if (n == 0) {
            throw ApiException.notFound("Open risk flag", flagId);
        }
        Claim c = get(id);
        Integer score = jdbc.queryForObject("SELECT COALESCE(SUM(score),0) FROM claim_risk_flag WHERE claim_id=? AND cleared_dt IS NULL", Integer.class, id);
        c.setFraudScore(score);
        audit.log(actor, "CLAIM_FLAG_CLEAR", "CLAIM", id, String.valueOf(flagId));
        return Map.of("fraudScore", score);
    }

    @Transactional
    public Map<String, Object> assess(Long id, AssessRequest r, String actor) {
        Claim c = get(id);
        if (!List.of("ASSIGNED", "UNDER_INVESTIGATION", "PENDING_INFO", "ASSESSED", "REOPENED").contains(c.getStatus())) {
            throw ApiException.rule("Claim cannot be assessed in status " + displayStatus(c.getStatus()));
        }
        Map<String, Object> cat = jdbc.queryForMap("SELECT * FROM claim_category WHERE category_code=?", c.getCategoryCode());
        BigDecimal excluded = Money.nz(r.excludedAmount());
        BigDecimal depPct = Money.nz(r.depreciationPct());
        BigDecimal salvage = Money.nz(r.salvageAmount());
        if (excluded.signum() < 0 || salvage.signum() < 0 || depPct.signum() < 0 || depPct.compareTo(Money.HUNDRED) > 0) {
            throw ApiException.validation("Assessment inputs must be non-negative and depreciation <= 100%");
        }
        BigDecimal deductible = (BigDecimal) cat.get("deductible");
        BigDecimal coverage = (BigDecimal) cat.get("coverage_pct");
        BigDecimal coins = Money.HUNDRED.subtract(coverage);
        List<String> trace = new ArrayList<>();
        BigDecimal base = c.getClaimedAmount().subtract(excluded);
        trace.add("Claimed " + c.getClaimedAmount() + " - excluded " + excluded + " = " + base);
        BigDecimal dep = Money.pct(base, depPct);
        BigDecimal net = base.subtract(dep).subtract(salvage).subtract(deductible);
        trace.add("- depreciation " + depPct + "% (" + dep + ") - salvage " + salvage + " - deductible " + deductible + " = " + net);
        net = Money.max(net, BigDecimal.ZERO);
        BigDecimal covered = Money.of(net.multiply(coverage).divide(Money.HUNDRED, 6, RoundingMode.HALF_UP));
        trace.add("x coverage " + coverage + "% (co-insurance " + coins + "%) = " + covered);
        Policy p = policies.get(c.getPolicyId());
        BigDecimal otherPaid = jdbc.queryForObject(
                "SELECT COALESCE(SUM(GREATEST(settled_amount, COALESCE(approved_amount,0))),0) FROM claim WHERE policy_id=? AND claim_id<>? AND status<>'REJECTED'",
                BigDecimal.class, p.getId(), id);
        BigDecimal remaining = Money.max(p.getSumInsured().subtract(otherPaid), BigDecimal.ZERO);
        if (covered.compareTo(remaining) > 0) {
            trace.add("Capped at remaining sum insured " + remaining);
            covered = remaining;
        }
        jdbc.update("""
                INSERT INTO claim_assessment (claim_id, claimed_amount, excluded_amount, depreciation_pct, depreciation_amt, salvage_amount,
                  deductible, coinsurance_pct, net_amount, fraud_score, calc_trace, assessed_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
                id, c.getClaimedAmount(), excluded, depPct, dep, salvage, deductible, coins, covered, c.getFraudScore(),
                String.join("\n", trace), actor);
        c.setAssessedAmount(covered);
        transition(c, "ASSESSED", "Assessed at " + covered, actor);
        if (r.note() != null && !r.note().isBlank()) {
            addNote(id, "ASSESSMENT", r.note(), actor);
        }
        audit.log(actor, "CLAIM_ASSESS", "CLAIM", id, covered.toPlainString());
        return Map.of("claim", c, "netAmount", covered, "trace", trace);
    }

    private BigDecimal claimLimit(String actor) {
        List<BigDecimal> limits = jdbc.queryForList("""
                SELECT l.max_amount FROM role_approval_limit l JOIN user_account u ON u.role_id=l.role_id
                WHERE u.username=? AND l.limit_type='CLAIM'""", BigDecimal.class, actor);
        return limits.isEmpty() ? BigDecimal.ZERO : limits.get(0);
    }

    @Transactional
    public Map<String, Object> decide(Long id, DecideRequest r, String actor) {
        Claim c = get(id);
        if (!"ASSESSED".equals(c.getStatus())) {
            throw ApiException.rule("Only ASSESSED claims can be decided");
        }
        if ("REJECT".equals(r.decision())) {
            if (r.reason() == null || r.reason().isBlank()) {
                throw ApiException.validation("A reason is required to reject a claim");
            }
            reject(c, r.reason(), actor);
            return Map.of("claim", c, "routedToApproval", false);
        }
        if (!"APPROVE".equals(r.decision())) {
            throw ApiException.validation("decision must be APPROVE or REJECT");
        }
        BigDecimal amount = r.approvedAmount() == null ? c.getAssessedAmount() : Money.of(r.approvedAmount());
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.rule("Approved amount must be positive; reject the claim instead");
        }
        if (amount.compareTo(c.getAssessedAmount()) > 0) {
            throw ApiException.rule("Approved amount cannot exceed assessed amount " + c.getAssessedAmount());
        }
        c.setApprovedAmount(amount);
        c.setDecisionReason(r.reason());
        boolean needsApproval = amount.compareTo(claimLimit(actor).min(AUTO_APPROVAL_CEILING)) > 0
                || c.getFraudScore() >= FRAUD_REFERRAL_SCORE;
        if (needsApproval) {
            transition(c, "PENDING_APPROVAL", "Approval of " + amount + " requires authorisation", actor);
            workflow.start("CLAIM_STD", id, c.getClaimNo(), amount, actor);
            audit.log(actor, "CLAIM_REFER", "CLAIM", id, amount.toPlainString());
            return Map.of("claim", get(id), "routedToApproval", true);
        }
        approve(c, actor, r.reason());
        return Map.of("claim", c, "routedToApproval", false);
    }

    private void approve(Claim c, String actor, String note) {
        String to = c.getApprovedAmount().compareTo(c.getAssessedAmount()) < 0 ? "PARTIAL" : "APPROVED";
        c.setDecidedDt(LocalDateTime.now());
        transition(c, to, "Approved " + c.getApprovedAmount() + (note == null ? "" : ": " + note), actor);
        notifyDecision(c);
        audit.log(actor, "CLAIM_APPROVE", "CLAIM", c.getId(), c.getApprovedAmount().toPlainString());
    }

    private void reject(Claim c, String reason, String actor) {
        c.setDecisionReason(reason);
        c.setDecidedDt(LocalDateTime.now());
        transition(c, "REJECTED", reason, actor);
        notifyDecision(c);
        audit.log(actor, "CLAIM_REJECT", "CLAIM", c.getId(), reason);
    }

    private void notifyDecision(Claim c) {
        Map<String, Object> contact = policies.customerContact(c.getCustomerId());
        notifications.enqueue("CLAIM_DECIDED", (String) contact.get("email"),
                Map.of("name", contact.get("name"), "claimNo", c.getClaimNo(), "status", displayStatus(c.getStatus())),
                "CLAIM", c.getId(), "CLMDEC-" + c.getId() + "-" + c.getStatus() + "-" + c.getReopenCount());
    }

    @Override
    public String subjectType() {
        return "CLAIM";
    }

    @Override
    public void onApproved(Long claimId, String actor, String note) {
        Claim c = get(claimId);
        if ("PENDING_APPROVAL".equals(c.getStatus())) {
            approve(c, actor, note);
        }
    }

    @Override
    public void onRejected(Long claimId, String actor, String note) {
        Claim c = get(claimId);
        if ("PENDING_APPROVAL".equals(c.getStatus())) {
            reject(c, "Approval declined: " + note, actor);
        }
    }

    @Transactional
    public Map<String, Object> settle(Long id, SettleRequest r, String actor) {
        Claim c = get(id);
        if (!List.of("APPROVED", "PARTIAL").contains(c.getStatus())) {
            throw ApiException.rule("Only approved claims can be settled");
        }
        String method = r.method() == null ? "TRANSFER" : r.method();
        if (!List.of("TRANSFER", "CHECK", "CASH").contains(method)) {
            throw ApiException.validation("method must be TRANSFER, CHECK or CASH");
        }
        BigDecimal open = c.getApprovedAmount().subtract(c.getSettledAmount());
        BigDecimal amount = r.amount() == null ? open : Money.of(r.amount());
        if (amount.signum() <= 0 || amount.compareTo(open) > 0) {
            throw ApiException.rule("Settlement amount must be between 0 and the unpaid approved amount " + open);
        }
        Integer seq = jdbc.queryForObject("SELECT COALESCE(MAX(payout_seq),0)+1 FROM claim_settlement WHERE claim_id=?", Integer.class, id);
        String payoutRef = c.getClaimNo() + "-P" + seq;
        String payee = r.payeeName() == null ? (String) policies.customerContact(c.getCustomerId()).get("name") : r.payeeName();
        jdbc.update("""
                INSERT INTO claim_settlement (claim_id, payout_seq, method, payee_name, gross_amount, net_amount, status, payout_ref, paid_by, paid_dt)
                VALUES (?,?,?,?,?,?,'PAID',?,?,now())""", id, seq, method, payee, amount, amount, payoutRef, actor);
        long journalId = ledger.post("CLAIM_PAYOUT", "CLAIM", id, c.getPolicyId(), "Claim payout " + payoutRef, actor,
                List.of(LedgerService.Line.dr("CLAIM_PAY", amount), LedgerService.Line.cr("CASH", amount)));
        jdbc.update("""
                INSERT INTO claim_payout_event (claim_id, event_type, amount, payout_ref, actor, detail, ledger_ref_type, ledger_ref_id)
                VALUES (?,'PAID',?,?,?,?,'JOURNAL',?)""", id, amount, payoutRef, actor, method + " to " + payee, journalId);
        c.setSettledAmount(c.getSettledAmount().add(amount));
        if (c.getSettledAmount().compareTo(c.getApprovedAmount()) >= 0) {
            transition(c, "SETTLED", "Settled " + c.getSettledAmount(), actor);
        } else {
            claims.saveAndFlush(c);
        }
        jdbc.update("""
                INSERT INTO policy_lifecycle (policy_id, last_claim_dt, updated_by) VALUES (?,?,?)
                ON CONFLICT (policy_id) DO UPDATE SET last_claim_dt=EXCLUDED.last_claim_dt, ncb_years=0, updated_dt=now(), updated_by=EXCLUDED.updated_by""",
                c.getPolicyId(), c.getIncidentDt(), actor);
        Map<String, Object> contact = policies.customerContact(c.getCustomerId());
        notifications.enqueue("CLAIM_SETTLED", (String) contact.get("email"),
                Map.of("name", contact.get("name"), "claimNo", c.getClaimNo(), "amount", amount), "CLAIM", id, "CLMSET-" + payoutRef);
        audit.log(actor, "CLAIM_SETTLE", "CLAIM", id, payoutRef + " " + amount);
        return Map.of("claim", c, "payoutRef", payoutRef);
    }

    @Transactional
    public Claim reverseSettlement(Long id, Long settlementId, String reason, String actor) {
        Claim c = get(id);
        Map<String, Object> s = jdbc.queryForList("SELECT * FROM claim_settlement WHERE settlement_id=? AND claim_id=?", settlementId, id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Settlement", settlementId));
        if (!"PAID".equals(s.get("status"))) {
            throw ApiException.rule("Settlement already reversed");
        }
        if (reason == null || reason.isBlank()) {
            throw ApiException.validation("Reason is required");
        }
        BigDecimal amount = (BigDecimal) s.get("net_amount");
        jdbc.update("UPDATE claim_settlement SET status='REVERSED', reversed_dt=now(), reversed_by=?, reversal_reason=? WHERE settlement_id=?",
                actor, reason, settlementId);
        long journalId = ledger.post("CLAIM_REVERSAL", "CLAIM", id, c.getPolicyId(), "Reversal of " + s.get("payout_ref"), actor,
                List.of(LedgerService.Line.dr("CASH", amount), LedgerService.Line.cr("CLAIM_PAY", amount)));
        jdbc.update("""
                INSERT INTO claim_payout_event (claim_id, event_type, amount, payout_ref, actor, detail, ledger_ref_type, ledger_ref_id)
                VALUES (?,'REVERSED',?,?,?,?,'JOURNAL',?)""", id, amount.negate(), s.get("payout_ref"), actor, reason, journalId);
        c.setSettledAmount(c.getSettledAmount().subtract(amount));
        if ("SETTLED".equals(c.getStatus())) {
            transition(c, c.getApprovedAmount().compareTo(c.getAssessedAmount()) < 0 ? "PARTIAL" : "APPROVED", "Payout reversed: " + reason, actor);
        }
        audit.log(actor, "CLAIM_PAYOUT_REVERSE", "CLAIM", id, s.get("payout_ref") + ": " + reason);
        return c;
    }

    @Transactional
    public Claim reopen(Long id, String reason, String actor) {
        Claim c = get(id);
        if (c.getReopenCount() >= MAX_REOPENS) {
            throw ApiException.rule("Claim has reached the maximum of " + MAX_REOPENS + " reopens");
        }
        if ("REJECTED".equals(c.getStatus()) && c.getDecidedDt() != null
                && c.getDecidedDt().isBefore(LocalDateTime.now().minusDays(REOPEN_WINDOW_DAYS))) {
            throw ApiException.rule("Rejected claims can only be reopened within " + REOPEN_WINDOW_DAYS + " days");
        }
        if (reason == null || reason.isBlank()) {
            throw ApiException.validation("Reason is required");
        }
        c.setReopenCount(c.getReopenCount() + 1);
        c.setSlaBreached("N");
        c.setSlaDueDt(LocalDate.now().plusDays(14));
        transition(c, "REOPENED", reason, actor);
        audit.log(actor, "CLAIM_REOPEN", "CLAIM", id, reason);
        return c;
    }

    @Transactional
    public int slaSweep(LocalDate asOf) {
        List<Claim> overdue = jdbc.queryForList("""
                SELECT claim_id FROM claim WHERE sla_breached='N' AND sla_due_dt < ? AND status NOT IN ('SETTLED','CLOSED','REJECTED')""",
                Long.class, asOf).stream().map(this::get).toList();
        for (Claim c : overdue) {
            c.setSlaBreached("Y");
            c.setPriority("HIGH");
            jdbc.update("INSERT INTO claim_note (claim_id, note_type, note_text, author) VALUES (?,?,?,?)",
                    c.getId(), "SLA", "SLA due " + c.getSlaDueDt() + " breached; escalated to HIGH priority", "BATCH");
        }
        return overdue.size();
    }

    public Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> byStatus = new LinkedHashMap<>();
        jdbc.queryForList("SELECT status, count(*) AS cnt FROM claim GROUP BY status ORDER BY status")
                .forEach(r -> byStatus.put(displayStatus((String) r.get("status")), r.get("cnt")));
        m.put("byStatus", byStatus);
        m.put("open", jdbc.queryForObject("SELECT count(*) FROM claim WHERE status NOT IN ('SETTLED','CLOSED','REJECTED')", Long.class));
        m.put("slaBreached", jdbc.queryForObject("SELECT count(*) FROM claim WHERE sla_breached='Y' AND status NOT IN ('SETTLED','CLOSED','REJECTED')", Long.class));
        m.put("highRisk", jdbc.queryForObject("SELECT count(*) FROM claim WHERE fraud_score>=? AND status NOT IN ('SETTLED','CLOSED','REJECTED')", Long.class, FRAUD_REFERRAL_SCORE));
        m.put("paidThisMonth", jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM claim_payout_event WHERE date_trunc('month',event_dt)=date_trunc('month',now())", BigDecimal.class));
        return m;
    }

    static boolean isTerminal(String status) {
        return TERMINAL.contains(status);
    }
}
