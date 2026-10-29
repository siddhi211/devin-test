package com.legacyins.insurance.lifecycle;

import com.legacyins.insurance.auth.AuditService;
import com.legacyins.insurance.billing.BillingService;
import com.legacyins.insurance.billing.LedgerService;
import com.legacyins.insurance.common.ApiException;
import com.legacyins.insurance.common.Money;
import com.legacyins.insurance.customer.Customer;
import com.legacyins.insurance.customer.CustomerService;
import com.legacyins.insurance.notification.NotificationService;
import com.legacyins.insurance.policy.Policy;
import com.legacyins.insurance.policy.PolicyStateService;
import com.legacyins.insurance.product.PremiumService;
import com.legacyins.insurance.workflow.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Renewals (NCB, claims loading, re-underwriting) and cancellations (cooling-off, pro-rata/short-rate, reinstatement). */
@Service
@RequiredArgsConstructor
public class LifecycleService {
    public static final int RENEWAL_WINDOW_DAYS = 60;
    public static final int COOLING_OFF_DAYS = 14;
    public static final int REINSTATE_WINDOW_DAYS = 30;
    public static final BigDecimal INSURED_PRORATA_FEE_PCT = BigDecimal.TEN;
    public static final BigDecimal AUTO_APPROVE_REFUND = new BigDecimal("5000");
    private static final int[] SHORT_RATE_EARNED_PCT = {0, 20, 30, 40, 50, 60, 70, 75, 80, 85, 90, 95, 100};

    private final PolicyStateService policies;
    private final CustomerService customers;
    private final PremiumService premiums;
    private final BillingService billing;
    private final LedgerService ledger;
    private final WorkflowService workflow;
    private final NotificationService notifications;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    public record CancelRequest(Long policyId, String cancelType, String reasonCode, LocalDate effectiveDt,
                                String refundMethod, Boolean override, String notes) {
    }

    // ------------------------------------------------------------------ renewals

    public List<Map<String, Object>> candidates(LocalDate asOf) {
        LocalDate d = asOf == null ? LocalDate.now() : asOf;
        return jdbc.queryForList("""
                SELECT p.policy_id, p.policy_no, p.status, p.end_dt, p.annual_premium, p.renewal_count, pr.product_code,
                       COALESCE(l.auto_renew,'N') AS auto_renew, COALESCE(l.ncb_years,0) AS ncb_years,
                       (SELECT r.status FROM renewal_request r WHERE r.policy_id=p.policy_id AND r.renewal_start_dt=p.end_dt + 1
                        ORDER BY r.renewal_id DESC LIMIT 1) AS renewal_status
                FROM policy p JOIN insurance_product pr ON pr.product_id=p.product_id
                LEFT JOIN policy_lifecycle l ON l.policy_id=p.policy_id
                WHERE p.status IN ('ACTIVE','GRACE') AND p.end_dt BETWEEN ? AND ?
                ORDER BY p.end_dt""", d, d.plusDays(RENEWAL_WINDOW_DAYS));
    }

    public List<Map<String, Object>> renewals(String status, Long policyId) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT r.*, p.policy_no FROM renewal_request r JOIN policy p ON p.policy_id=r.policy_id WHERE 1=1""");
        if (status != null && !status.isBlank()) {
            sql.append(" AND r.status=?");
            args.add(status);
        }
        if (policyId != null) {
            sql.append(" AND r.policy_id=?");
            args.add(policyId);
        }
        sql.append(" ORDER BY r.renewal_id DESC LIMIT 500");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public Map<String, Object> renewal(Long id) {
        return jdbc.queryForList("SELECT r.*, p.policy_no FROM renewal_request r JOIN policy p ON p.policy_id=r.policy_id WHERE r.renewal_id=?", id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Renewal", id));
    }

    @Transactional
    public Map<String, Object> offer(Long policyId, String actor) {
        Policy p = policies.get(policyId);
        if (!List.of("ACTIVE", "GRACE").contains(p.getStatus())) {
            throw ApiException.rule("Only in-force policies can be offered renewal");
        }
        LocalDate renewalStart = p.getEndDt().plusDays(1);
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT * FROM renewal_request WHERE policy_id=? AND renewal_start_dt=? AND status NOT IN ('DECLINED','LAPSED')", policyId, renewalStart);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        Map<String, Object> lc = lifecycle(policyId, actor);
        int ncbYears = ((Number) lc.get("ncb_years")).intValue();
        Integer termClaims = jdbc.queryForObject(
                "SELECT count(*) FROM claim WHERE policy_id=? AND incident_dt BETWEEN ? AND ? AND status<>'REJECTED'",
                Integer.class, policyId, p.getEndDt().minusMonths(12), p.getEndDt());
        int claims = termClaims == null ? 0 : termClaims;
        int projectedNcb = claims == 0 ? Math.min(ncbYears + 1, 5) : 0;
        BigDecimal ncbPct = BigDecimal.valueOf(projectedNcb * 10L);
        BigDecimal loadPct = BigDecimal.valueOf(Math.min(claims * 25L, 100));

        Customer c = customers.get(p.getCustomerId());
        LocalDate dob = c.getDateOfBirth() == null ? renewalStart.minusYears(30) : c.getDateOfBirth();
        List<String> reuw = new ArrayList<>();
        Map<String, Object> product = jdbc.queryForMap("SELECT max_age, status FROM insurance_product WHERE product_id=?", p.getProductId());
        int age = Period.between(dob, renewalStart).getYears();
        if (c.getDateOfBirth() != null && age > ((Number) product.get("max_age")).intValue()) {
            reuw.add("Insured age " + age + " exceeds product maximum");
        }
        if (loadPct.compareTo(BigDecimal.valueOf(50)) >= 0) {
            reuw.add(claims + " claims in term (load " + loadPct + "%)");
        }
        if (!"A".equals(product.get("status"))) {
            reuw.add("Product is no longer open for sale");
        }
        BigDecimal base;
        String engine = "POLICY_MODULE";
        try {
            base = premiums.quote(new PremiumService.QuoteRequest(p.getProductId(), c.getId(), dob, p.getSumInsured(), 12,
                    p.getPaymentFrequency(), p.getRiskClass(), "Y".equals(p.getSmokerFlag()), p.getOccupationCode(), renewalStart),
                    actor, false).annualPremium();
        } catch (ApiException e) {
            engine = "LOCAL";
            base = p.getAnnualPremium().setScale(2, RoundingMode.DOWN);
            reuw.add("Rating failed: " + e.getMessage());
        }
        BigDecimal factor = BigDecimal.ONE.add(loadPct.movePointLeft(2)).subtract(ncbPct.movePointLeft(2));
        BigDecimal offered = Money.of(base.multiply(factor));
        String status = reuw.isEmpty() ? "OFFERED" : "REFERRED";
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        BigDecimal finalBase = base;
        String finalEngine = engine;
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO renewal_request (policy_id, status, renewal_start_dt, offer_dt, offer_expires_dt, current_premium, base_premium,
                      ncb_pct, claims_load_pct, offered_premium, reuw_flag, reuw_reason, premium_engine, created_by)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", new String[]{"renewal_id"});
            ps.setLong(1, policyId);
            ps.setString(2, status);
            ps.setDate(3, Date.valueOf(renewalStart));
            ps.setDate(4, Date.valueOf(LocalDate.now()));
            ps.setDate(5, Date.valueOf(p.getEndDt()));
            ps.setBigDecimal(6, p.getAnnualPremium());
            ps.setBigDecimal(7, finalBase);
            ps.setBigDecimal(8, ncbPct);
            ps.setBigDecimal(9, loadPct);
            ps.setBigDecimal(10, offered);
            ps.setString(11, reuw.isEmpty() ? "N" : "Y");
            ps.setString(12, reuw.isEmpty() ? null : String.join("; ", reuw));
            ps.setString(13, finalEngine);
            ps.setString(14, actor);
            return ps;
        }, kh);
        long renewalId = kh.getKey().longValue();
        if (!reuw.isEmpty()) {
            workflow.start("RENEWAL_EXCEPTION", renewalId, p.getPolicyNo(), offered, actor);
        } else {
            notifyOffer(p, offered);
        }
        audit.log(actor, "RENEWAL_OFFER", "POLICY", policyId, status + " " + offered);
        return renewal(renewalId);
    }

    private void notifyOffer(Policy p, BigDecimal offered) {
        Map<String, Object> contact = policies.customerContact(p.getCustomerId());
        notifications.enqueue("RENEWAL_OFFER", (String) contact.get("email"),
                Map.of("name", contact.get("name"), "policyNo", p.getPolicyNo(), "premium", offered, "endDt", p.getEndDt()),
                "POLICY", p.getId(), "RENOFFER-" + p.getId() + "-" + p.getEndDt());
    }

    private Map<String, Object> lifecycle(Long policyId, String actor) {
        jdbc.update("INSERT INTO policy_lifecycle (policy_id, updated_by) VALUES (?,?) ON CONFLICT (policy_id) DO NOTHING", policyId, actor);
        return jdbc.queryForMap("SELECT * FROM policy_lifecycle WHERE policy_id=?", policyId);
    }

    @Transactional
    public Map<String, Object> setAutoRenew(Long policyId, boolean on, String actor) {
        policies.get(policyId);
        lifecycle(policyId, actor);
        jdbc.update("UPDATE policy_lifecycle SET auto_renew=?, updated_dt=now(), updated_by=? WHERE policy_id=?", on ? "Y" : "N", actor, policyId);
        audit.log(actor, "AUTO_RENEW", "POLICY", policyId, on ? "Y" : "N");
        return lifecycle(policyId, actor);
    }

    public void releaseReferral(Long renewalId, boolean approved, String actor, String note) {
        Map<String, Object> r = renewal(renewalId);
        if (!"REFERRED".equals(r.get("status"))) {
            return;
        }
        jdbc.update("UPDATE renewal_request SET status=?, decided_by=?, decided_dt=now(), notes=? WHERE renewal_id=?",
                approved ? "OFFERED" : "DECLINED", actor, note, renewalId);
        if (approved) {
            Policy p = policies.get(((Number) r.get("policy_id")).longValue());
            notifyOffer(p, (BigDecimal) r.get("offered_premium"));
        }
        audit.log(actor, approved ? "RENEWAL_RELEASE" : "RENEWAL_DECLINE", "RENEWAL", renewalId, note);
    }

    @Transactional
    public Map<String, Object> decide(Long renewalId, String decision, String actor, boolean auto) {
        Map<String, Object> r = renewal(renewalId);
        if (!"OFFERED".equals(r.get("status"))) {
            throw ApiException.rule("Renewal is " + r.get("status") + "; only OFFERED renewals can be decided");
        }
        if ("DECLINE".equals(decision)) {
            jdbc.update("UPDATE renewal_request SET status='DECLINED', decided_by=?, decided_dt=now() WHERE renewal_id=?", actor, renewalId);
            audit.log(actor, "RENEWAL_DECLINED", "RENEWAL", renewalId, null);
            return renewal(renewalId);
        }
        if (!"ACCEPT".equals(decision)) {
            throw ApiException.validation("decision must be ACCEPT or DECLINE");
        }
        Policy p = policies.get(((Number) r.get("policy_id")).longValue());
        if (!List.of("ACTIVE", "GRACE").contains(p.getStatus())) {
            throw ApiException.rule("Policy is no longer in force");
        }
        LocalDate start = ((java.sql.Date) r.get("renewal_start_dt")).toLocalDate();
        if (!start.equals(p.getEndDt().plusDays(1))) {
            throw ApiException.rule("Renewal offer is stale for the current policy term");
        }
        jdbc.update("UPDATE renewal_request SET status='ACCEPTED', decided_by=?, decided_dt=now() WHERE renewal_id=?", actor, renewalId);
        BigDecimal premium = (BigDecimal) r.get("offered_premium");
        BigDecimal divisor = switch (p.getPaymentFrequency()) {
            case "MONTHLY" -> BigDecimal.valueOf(12);
            case "QUARTERLY" -> BigDecimal.valueOf(4);
            case "SEMIANNUAL" -> BigDecimal.valueOf(2);
            default -> BigDecimal.ONE;
        };
        Integer claimsInTerm = jdbc.queryForObject("SELECT count(*) FROM claim WHERE policy_id=? AND incident_dt BETWEEN ? AND ? AND status<>'REJECTED'",
                Integer.class, p.getId(), p.getStartDt(), p.getEndDt());
        p.setStartDt(start);
        p.setTermMonths(12);
        p.setEndDt(start.plusMonths(12).minusDays(1));
        p.setAnnualPremium(premium);
        p.setInstallmentPremium(Money.of(premium.divide(divisor, 6, RoundingMode.HALF_UP)));
        p.setRenewalCount(p.getRenewalCount() + 1);
        p.setVersionNo(p.getVersionNo() + 1);
        policies.addVersion(p, "RENEWAL", "Renewed for term starting " + start, actor);
        billing.generateSchedule(p, start, actor);
        jdbc.update("UPDATE policy_lifecycle SET ncb_years = CASE WHEN ?=0 THEN LEAST(ncb_years+1,5) ELSE 0 END, updated_dt=now(), updated_by=? WHERE policy_id=?",
                claimsInTerm == null ? 0 : claimsInTerm, actor, p.getId());
        jdbc.update("UPDATE renewal_request SET status='RENEWED', new_version_no=?, auto_issued=? WHERE renewal_id=?",
                p.getVersionNo(), auto ? "Y" : "N", renewalId);
        jdbc.update("INSERT INTO policy_status_history (policy_id, from_status, to_status, reason, actor) VALUES (?,?,?,?,?)",
                p.getId(), p.getStatus(), p.getStatus(), "Renewed to " + p.getEndDt(), actor);
        audit.log(actor, "RENEWAL_ACCEPTED", "POLICY", p.getId(), premium.toPlainString());
        return renewal(renewalId);
    }

    @Transactional
    public Map<String, Integer> generateOffers(LocalDate asOf) {
        int offered = 0, failed = 0;
        for (Map<String, Object> c : candidates(asOf)) {
            if (c.get("renewal_status") != null) {
                continue;
            }
            try {
                offer(((Number) c.get("policy_id")).longValue(), "BATCH");
                offered++;
            } catch (ApiException e) {
                failed++;
            }
        }
        return Map.of("offered", offered, "failed", failed);
    }

    @Transactional
    public Map<String, Integer> expiry(LocalDate asOf) {
        int lapsedOffers = 0, autoRenewed = 0, expired = 0, completed = 0;
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT r.renewal_id, r.policy_id, COALESCE(l.auto_renew,'N') AS auto_renew FROM renewal_request r
                LEFT JOIN policy_lifecycle l ON l.policy_id=r.policy_id
                WHERE r.status='OFFERED' AND r.offer_expires_dt < ?""", asOf)) {
            long id = ((Number) r.get("renewal_id")).longValue();
            if ("Y".equals(r.get("auto_renew"))) {
                try {
                    decide(id, "ACCEPT", "BATCH", true);
                    autoRenewed++;
                    continue;
                } catch (ApiException ignored) {
                    // falls through to lapse
                }
            }
            jdbc.update("UPDATE renewal_request SET status='LAPSED', decided_by='BATCH', decided_dt=now() WHERE renewal_id=?", id);
            lapsedOffers++;
        }
        for (Long id : jdbc.queryForList("SELECT policy_id FROM policy WHERE status='ACTIVE' AND end_dt < ?", Long.class, asOf)) {
            policies.transition(policies.get(id), "EXPIRED", "Term ended without renewal", "BATCH");
            expired++;
        }
        for (Long id : jdbc.queryForList("SELECT cancellation_id FROM cancellation_request WHERE status='APPROVED' AND effective_dt <= ?", Long.class, asOf)) {
            complete(id, "BATCH");
            completed++;
        }
        return Map.of("offersLapsed", lapsedOffers, "autoRenewed", autoRenewed, "policiesExpired", expired, "cancellationsCompleted", completed);
    }

    // ------------------------------------------------------------------ cancellations

    public List<Map<String, Object>> reasons() {
        return jdbc.queryForList("SELECT * FROM lc_cancel_reason WHERE active='Y' ORDER BY cancel_type, reason_code");
    }

    public Map<String, Object> preview(CancelRequest r) {
        Policy p = policies.get(r.policyId());
        return quote(p, r);
    }

    private Map<String, Object> quote(Policy p, CancelRequest r) {
        String type = r.cancelType() == null ? "INSURED" : r.cancelType();
        if (!List.of("INSURED", "INSURER", "NONPAY").contains(type)) {
            throw ApiException.validation("cancelType must be INSURED, INSURER or NONPAY");
        }
        Map<String, Object> reason = jdbc.queryForList("SELECT * FROM lc_cancel_reason WHERE reason_code=? AND active='Y'", r.reasonCode())
                .stream().findFirst().orElseThrow(() -> ApiException.validation("Unknown cancellation reason " + r.reasonCode()));
        if (!type.equals(reason.get("cancel_type"))) {
            throw ApiException.validation("Reason " + r.reasonCode() + " is for " + reason.get("cancel_type") + " cancellations");
        }
        if ("NONPAY".equals(type) && !List.of("GRACE", "LAPSED").contains(p.getStatus())) {
            throw ApiException.rule("Non-payment cancellation requires a GRACE or LAPSED policy");
        }
        if (!List.of("ACTIVE", "GRACE", "LAPSED").contains(p.getStatus())) {
            throw ApiException.rule("Policy in status " + p.getStatus() + " cannot be cancelled");
        }
        LocalDate eff = r.effectiveDt() == null ? LocalDate.now() : r.effectiveDt();
        if (eff.isBefore(p.getStartDt()) || eff.isAfter(p.getEndDt())) {
            throw ApiException.validation("Effective date must fall within the current term");
        }
        if (eff.isBefore(LocalDate.now().minusDays(30)) && !Boolean.TRUE.equals(r.override())) {
            throw ApiException.rule("Backdating more than 30 days requires override");
        }
        BigDecimal paid = jdbc.queryForObject("SELECT COALESCE(SUM(amount_paid),0) FROM premium_schedule WHERE policy_id=? AND due_dt BETWEEN ? AND ?",
                BigDecimal.class, p.getId(), p.getStartDt(), p.getEndDt());
        BigDecimal arrears = billing.outstanding(p.getId(), eff.plusDays(1));
        long termDays = ChronoUnit.DAYS.between(p.getStartDt(), p.getEndDt()) + 1;
        long usedDays = ChronoUnit.DAYS.between(p.getStartDt(), eff);
        BigDecimal termPremium = Money.of(p.getAnnualPremium().multiply(BigDecimal.valueOf(p.getTermMonths())).divide(BigDecimal.valueOf(12), 6, RoundingMode.HALF_UP));
        boolean coolingOff = "INSURED".equals(type) && p.getRenewalCount() == 0 && usedDays <= COOLING_OFF_DAYS;
        String method = r.refundMethod();
        if (method == null) {
            method = switch (type) {
                case "NONPAY" -> "NONE";
                case "INSURER" -> "PRORATA";
                default -> jdbc.queryForList("SELECT refund_scale FROM billing_product_config WHERE product_id=?", String.class, p.getProductId())
                        .stream().findFirst().orElse("PRORATA");
            };
        }
        if (!List.of("PRORATA", "SHORTRATE", "FULL", "NONE").contains(method)) {
            throw ApiException.validation("refundMethod must be PRORATA, SHORTRATE, FULL or NONE");
        }
        if ("N".equals(reason.get("refundable")) || "NONPAY".equals(type)) {
            method = "NONE";
        }
        if (coolingOff) {
            method = "FULL";
        }
        BigDecimal earned;
        BigDecimal fee = BigDecimal.ZERO;
        switch (method) {
            case "FULL" -> earned = BigDecimal.ZERO;
            case "NONE" -> earned = paid;
            case "SHORTRATE" -> {
                long months = Math.min(12, (long) Math.ceil(usedDays / 30.4375));
                earned = Money.pct(termPremium, BigDecimal.valueOf(SHORT_RATE_EARNED_PCT[(int) Math.max(1, months)]));
            }
            default -> earned = Money.of(termPremium.multiply(BigDecimal.valueOf(usedDays)).divide(BigDecimal.valueOf(termDays), 6, RoundingMode.HALF_UP));
        }
        BigDecimal unearned = Money.max(paid.subtract(earned), BigDecimal.ZERO);
        if ("PRORATA".equals(method) && "INSURED".equals(type)) {
            fee = Money.pct(unearned, INSURED_PRORATA_FEE_PCT);
        }
        BigDecimal refund = Money.max(unearned.subtract(fee), BigDecimal.ZERO);
        Integer openClaims = jdbc.queryForObject("SELECT count(*) FROM claim WHERE policy_id=? AND status NOT IN ('SETTLED','CLOSED','REJECTED')",
                Integer.class, p.getId());
        List<String> warnings = new ArrayList<>();
        if (openClaims != null && openClaims > 0) {
            warnings.add(openClaims + " open claim(s) on policy");
        }
        int level = 0;
        if (!"INSURED".equals(type) || refund.compareTo(AUTO_APPROVE_REFUND) > 0 || !warnings.isEmpty() || Boolean.TRUE.equals(r.override())) {
            level = 1;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("policyId", p.getId());
        m.put("policyNo", p.getPolicyNo());
        m.put("cancelType", type);
        m.put("reasonCode", r.reasonCode());
        m.put("effectiveDt", eff);
        m.put("coolingOff", coolingOff);
        m.put("refundMethod", method);
        m.put("termPremium", termPremium);
        m.put("premiumPaid", paid);
        m.put("earnedPremium", Money.of(earned));
        m.put("cancelFee", fee);
        m.put("refundAmount", refund);
        m.put("arrears", arrears);
        m.put("openClaims", openClaims);
        m.put("approvalLevel", level);
        m.put("warnings", warnings);
        return m;
    }

    @Transactional
    public Map<String, Object> request(CancelRequest r, String actor) {
        Policy p = policies.get(r.policyId());
        Integer pending = jdbc.queryForObject("SELECT count(*) FROM cancellation_request WHERE policy_id=? AND status IN ('PENDING_APPROVAL','APPROVED')",
                Integer.class, p.getId());
        if (pending != null && pending > 0) {
            throw ApiException.duplicate("A cancellation is already pending for this policy");
        }
        Map<String, Object> q = quote(p, r);
        int level = (int) q.get("approvalLevel");
        String status = level > 0 ? "PENDING_APPROVAL" : "APPROVED";
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO cancellation_request (policy_id, cancel_type, reason_code, requested_dt, effective_dt, cooling_off, refund_method,
                      premium_paid, earned_premium, cancel_fee, refund_amount, arrears, open_claims, status, approval_level, override_flag,
                      prior_status, notes, requested_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", new String[]{"cancellation_id"});
            int i = 1;
            ps.setLong(i++, p.getId());
            ps.setString(i++, (String) q.get("cancelType"));
            ps.setString(i++, r.reasonCode());
            ps.setDate(i++, Date.valueOf(LocalDate.now()));
            ps.setDate(i++, Date.valueOf((LocalDate) q.get("effectiveDt")));
            ps.setString(i++, (boolean) q.get("coolingOff") ? "Y" : "N");
            ps.setString(i++, (String) q.get("refundMethod"));
            ps.setBigDecimal(i++, (BigDecimal) q.get("premiumPaid"));
            ps.setBigDecimal(i++, (BigDecimal) q.get("earnedPremium"));
            ps.setBigDecimal(i++, (BigDecimal) q.get("cancelFee"));
            ps.setBigDecimal(i++, (BigDecimal) q.get("refundAmount"));
            ps.setBigDecimal(i++, (BigDecimal) q.get("arrears"));
            ps.setInt(i++, (Integer) q.get("openClaims"));
            ps.setString(i++, status);
            ps.setInt(i++, level);
            ps.setString(i++, Boolean.TRUE.equals(r.override()) ? "Y" : "N");
            ps.setString(i++, p.getStatus());
            ps.setString(i++, r.notes());
            ps.setString(i, actor);
            return ps;
        }, kh);
        long id = kh.getKey().longValue();
        audit.log(actor, "CANCEL_REQUEST", "POLICY", p.getId(), status + " refund " + q.get("refundAmount"));
        if (level > 0) {
            workflow.start("CANCELLATION", id, p.getPolicyNo(), (BigDecimal) q.get("refundAmount"), actor);
        } else if (!((LocalDate) q.get("effectiveDt")).isAfter(LocalDate.now())) {
            complete(id, actor);
        }
        return cancellation(id);
    }

    public Map<String, Object> cancellation(Long id) {
        return jdbc.queryForList("""
                SELECT c.*, p.policy_no, r.status AS refund_status FROM cancellation_request c JOIN policy p ON p.policy_id=c.policy_id
                LEFT JOIN billing_refund r ON r.refund_id=c.refund_id WHERE c.cancellation_id=?""", id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Cancellation", id));
    }

    public List<Map<String, Object>> cancellations(String status, Long policyId) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT c.*, p.policy_no FROM cancellation_request c JOIN policy p ON p.policy_id=c.policy_id WHERE 1=1""");
        if (status != null && !status.isBlank()) {
            sql.append(" AND c.status=?");
            args.add(status);
        }
        if (policyId != null) {
            sql.append(" AND c.policy_id=?");
            args.add(policyId);
        }
        sql.append(" ORDER BY c.cancellation_id DESC LIMIT 500");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public void decideCancellation(Long id, boolean approved, String actor, String note) {
        Map<String, Object> c = cancellation(id);
        if (!"PENDING_APPROVAL".equals(c.get("status"))) {
            return;
        }
        jdbc.update("UPDATE cancellation_request SET status=?, decided_by=?, decided_dt=now(), notes=COALESCE(?, notes) WHERE cancellation_id=?",
                approved ? "APPROVED" : "REJECTED", actor, note, id);
        audit.log(actor, approved ? "CANCEL_APPROVE" : "CANCEL_REJECT", "CANCELLATION", id, note);
        if (approved && !((java.sql.Date) c.get("effective_dt")).toLocalDate().isAfter(LocalDate.now())) {
            complete(id, actor);
        }
    }

    @Transactional
    public void complete(Long id, String actor) {
        Map<String, Object> c = cancellation(id);
        if (!"APPROVED".equals(c.get("status"))) {
            throw ApiException.rule("Only APPROVED cancellations can be completed");
        }
        Policy p = policies.get(((Number) c.get("policy_id")).longValue());
        LocalDate eff = ((java.sql.Date) c.get("effective_dt")).toLocalDate();
        jdbc.update("UPDATE cancellation_request SET prior_status=? WHERE cancellation_id=?", p.getStatus(), id);
        policies.transition(p, "CANCELLED", "Cancelled (" + c.get("reason_code") + ") effective " + eff, actor);
        billing.cancelFutureInstallments(p, eff, actor);
        BigDecimal refund = (BigDecimal) c.get("refund_amount");
        Long refundId = null;
        if (refund.signum() > 0) {
            refundId = billing.createRefund(p, "CXL-" + id, id, refund.add((BigDecimal) c.get("cancel_fee")),
                    (BigDecimal) c.get("cancel_fee"), (String) c.get("refund_method"), actor);
        }
        jdbc.update("UPDATE cancellation_request SET status='COMPLETED', refund_id=?, reinstate_until_dt=? WHERE cancellation_id=?",
                refundId, eff.plusDays(REINSTATE_WINDOW_DAYS), id);
        workflow.cancel("RENEWAL_EXCEPTION", p.getId(), actor, "Policy cancelled");
        jdbc.update("UPDATE renewal_request SET status='DECLINED', notes='Policy cancelled' WHERE policy_id=? AND status IN ('PENDING','OFFERED','REFERRED')", p.getId());
        Map<String, Object> contact = policies.customerContact(p.getCustomerId());
        notifications.enqueue("POLICY_CANCELLED", (String) contact.get("email"),
                Map.of("name", contact.get("name"), "policyNo", p.getPolicyNo(), "effectiveDt", eff, "refund", refund),
                "POLICY", p.getId(), "CXL-" + id);
        audit.log(actor, "CANCEL_COMPLETE", "POLICY", p.getId(), "refund " + refund);
    }

    @Transactional
    public Map<String, Object> reinstate(Long cancellationId, boolean payArrears, String actor) {
        Map<String, Object> c = cancellation(cancellationId);
        if (!List.of("COMPLETED", "APPROVED").contains(c.get("status"))) {
            throw ApiException.rule("Only COMPLETED or APPROVED cancellations can be reinstated");
        }
        if (c.get("reinstate_until_dt") != null && LocalDate.now().isAfter(((java.sql.Date) c.get("reinstate_until_dt")).toLocalDate())) {
            throw ApiException.rule("Reinstatement window has closed");
        }
        if ("PAID".equals(c.get("refund_status"))) {
            throw ApiException.rule("Refund has already been disbursed; policy cannot be reinstated");
        }
        BigDecimal arrears = (BigDecimal) c.get("arrears");
        if (arrears != null && arrears.signum() > 0 && !payArrears) {
            throw ApiException.rule("Outstanding arrears of " + arrears + " must be settled (payArrears=Y)");
        }
        Policy p = policies.get(((Number) c.get("policy_id")).longValue());
        if ("COMPLETED".equals(c.get("status"))) {
            LocalDate eff = ((java.sql.Date) c.get("effective_dt")).toLocalDate();
            String prior = (String) c.get("prior_status");
            policies.transition(p, "ACTIVE", "Reinstated after cancellation" + ("ACTIVE".equals(prior) ? "" : " (was " + prior + ")"), actor);
            if (c.get("refund_id") != null) {
                Map<String, Object> refund = jdbc.queryForMap("SELECT * FROM billing_refund WHERE refund_id=?", c.get("refund_id"));
                jdbc.update("UPDATE billing_refund SET status='CANCELLED' WHERE refund_id=?", c.get("refund_id"));
                BigDecimal net = (BigDecimal) refund.get("net_amount");
                ledger.post("REFUND_CANCELLED", "REFUND", ((Number) c.get("refund_id")).longValue(), p.getId(), "Refund cancelled on reinstatement", actor,
                        List.of(LedgerService.Line.dr("REFUND_PAY", net), LedgerService.Line.cr("PREM_INCOME", net)));
            }
            BigDecimal restored = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(amount_due),0) FROM premium_schedule WHERE policy_id=? AND due_dt>=? AND status='CANCELLED'",
                    BigDecimal.class, p.getId(), eff);
            jdbc.update("UPDATE premium_schedule SET status='DUE' WHERE policy_id=? AND due_dt>=? AND status='CANCELLED'", p.getId(), eff);
            ledger.post("PREMIUM_REINSTATED", "POLICY", p.getId(), p.getId(), "Installments reinstated", actor,
                    List.of(LedgerService.Line.dr("PREM_RECV", restored), LedgerService.Line.cr("PREM_INCOME", restored)));
        }
        jdbc.update("UPDATE cancellation_request SET status='REINSTATED', reinstated_dt=now(), reinstated_by=? WHERE cancellation_id=?",
                actor, cancellationId);
        audit.log(actor, "POLICY_REINSTATE", "POLICY", p.getId(), "cancellation " + cancellationId);
        return cancellation(cancellationId);
    }
}
