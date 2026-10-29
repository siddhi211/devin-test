package com.legacyins.insurance.billing;

import com.legacyins.insurance.auth.AuditService;
import com.legacyins.insurance.common.ApiException;
import com.legacyins.insurance.common.Money;
import com.legacyins.insurance.notification.NotificationService;
import com.legacyins.insurance.policy.Policy;
import com.legacyins.insurance.policy.PolicyStateService;
import com.legacyins.insurance.product.PremiumService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static com.legacyins.insurance.billing.LedgerService.Line;

@Service
@RequiredArgsConstructor
public class BillingService {
    private final JdbcTemplate jdbc;
    private final LedgerService ledger;
    private final PaymentGateway gateway;
    private final PolicyStateService policyState;
    private final NotificationService notifications;
    private final AuditService audit;

    public record PaymentRequest(BigDecimal amount, String method, String instrumentRef, String idempotencyKey) {
    }

    public record PaymentResult(Long paymentId, String status, BigDecimal amount, String receiptNo, String gatewayCode,
                                String failureReason, BigDecimal allocated, BigDecimal credited, boolean replayed,
                                String policyStatus) {
    }

    // ---------------------------------------------------------------- schedules

    public void generateSchedule(Policy p, LocalDate from, String actor) {
        int perYear = PremiumService.installmentsPerYear(p.getPaymentFrequency());
        int months = 12 / perYear;
        int count = Math.max(1, (int) Math.ceil(p.getTermMonths() / (double) months));
        Integer last = jdbc.queryForObject("SELECT COALESCE(MAX(installment_no),0) FROM premium_schedule WHERE policy_id=?",
                Integer.class, p.getId());
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < count; i++) {
            jdbc.update("INSERT INTO premium_schedule (policy_id, installment_no, due_dt, amount_due) VALUES (?,?,?,?)",
                    p.getId(), last + i + 1, from.plusMonths((long) i * months), p.getInstallmentPremium());
            total = total.add(p.getInstallmentPremium());
        }
        ledger.post("PREMIUM_BILLED", "POLICY", p.getId(), p.getId(), count + " installments billed for " + p.getPolicyNo(), actor,
                List.of(Line.dr("PREM_RECV", total), Line.cr("PREM_INCOME", total)));
    }

    public List<Map<String, Object>> schedule(Long policyId) {
        return jdbc.queryForList("SELECT * FROM premium_schedule WHERE policy_id=? ORDER BY installment_no", policyId);
    }

    public List<Map<String, Object>> payments(Long policyId) {
        return jdbc.queryForList("""
                SELECT p.*, r.receipt_no FROM premium_payment p LEFT JOIN billing_receipt r ON r.payment_id=p.payment_id
                WHERE p.policy_id=? ORDER BY p.payment_id DESC""", policyId);
    }

    public List<Map<String, Object>> recentPayments(String status, int limit) {
        String sql = """
                SELECT p.*, r.receipt_no, pol.policy_no FROM premium_payment p
                JOIN policy pol ON pol.policy_id=p.policy_id
                LEFT JOIN billing_receipt r ON r.payment_id=p.payment_id %s ORDER BY p.payment_id DESC LIMIT ?""";
        return status == null || status.isBlank()
                ? jdbc.queryForList(sql.formatted(""), limit)
                : jdbc.queryForList(sql.formatted("WHERE p.status=?"), status, limit);
    }

    public Map<String, Object> account(Long policyId) {
        Policy p = policyState.get(policyId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("policyId", policyId);
        m.put("policyNo", p.getPolicyNo());
        m.put("policyStatus", p.getStatus());
        m.put("totalDue", jdbc.queryForObject("SELECT COALESCE(SUM(amount_due),0) FROM premium_schedule WHERE policy_id=? AND status<>'CANCELLED'", BigDecimal.class, policyId));
        m.put("totalPaid", jdbc.queryForObject("SELECT COALESCE(SUM(amount_paid),0) FROM premium_schedule WHERE policy_id=?", BigDecimal.class, policyId));
        m.put("outstanding", outstanding(policyId, null));
        m.put("overdue", outstanding(policyId, LocalDate.now()));
        m.put("lateFeesOutstanding", jdbc.queryForObject("SELECT COALESCE(SUM(late_fee-late_fee_paid),0) FROM premium_schedule WHERE policy_id=? AND status<>'CANCELLED'", BigDecimal.class, policyId));
        m.put("creditBalance", creditBalance(policyId));
        m.put("schedule", schedule(policyId));
        m.put("payments", payments(policyId));
        m.put("refunds", jdbc.queryForList("SELECT * FROM billing_refund WHERE policy_id=? ORDER BY refund_id DESC", policyId));
        m.put("retries", jdbc.queryForList("SELECT * FROM billing_retry_queue WHERE policy_id=? ORDER BY retry_id DESC", policyId));
        m.put("journals", ledger.journals(policyId, 100));
        return m;
    }

    /** Unpaid premium + late fees, optionally only on installments due before {@code dueBefore}. */
    public BigDecimal outstanding(Long policyId, LocalDate dueBefore) {
        String sql = "SELECT COALESCE(SUM(amount_due-amount_paid+late_fee-late_fee_paid),0) FROM premium_schedule WHERE policy_id=? AND status IN ('DUE','PARTIAL','OVERDUE')";
        return dueBefore == null
                ? jdbc.queryForObject(sql, BigDecimal.class, policyId)
                : jdbc.queryForObject(sql + " AND due_dt<?", BigDecimal.class, policyId, dueBefore);
    }

    public BigDecimal creditBalance(Long policyId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM policy_credit_txn WHERE policy_id=?", BigDecimal.class, policyId);
    }

    // ---------------------------------------------------------------- payments

    @Transactional
    public PaymentResult pay(Long policyId, PaymentRequest r, String actor) {
        if (r.amount() == null || r.amount().signum() <= 0) {
            throw ApiException.validation("Amount must be positive");
        }
        if (r.amount().scale() > 2) {
            throw ApiException.validation("Amount cannot have more than 2 decimals");
        }
        if (!List.of("CARD", "BANK", "CASH", "CHEQUE").contains(r.method())) {
            throw ApiException.validation("Method must be CARD, BANK, CASH or CHEQUE");
        }
        String key = r.idempotencyKey() == null || r.idempotencyKey().isBlank() ? null : r.idempotencyKey().trim();
        if (key != null) {
            List<Map<String, Object>> existing = jdbc.queryForList("SELECT * FROM premium_payment WHERE idempotency_key=?", key);
            if (!existing.isEmpty()) {
                Map<String, Object> e = existing.get(0);
                boolean same = ((Number) e.get("policy_id")).longValue() == policyId
                        && ((BigDecimal) e.get("amount")).compareTo(r.amount()) == 0 && r.method().equals(e.get("method"));
                if (!same) {
                    throw ApiException.duplicate("Idempotency key already used with different payment parameters");
                }
                return replay(e);
            }
        }
        Policy p = policyState.get(policyId);
        if (!List.of("ACTIVE", "GRACE", "LAPSED").contains(p.getStatus())) {
            throw ApiException.rule("Payments are not accepted for policies in status " + p.getStatus());
        }
        Money.of(r.amount());
        PaymentGateway.Result g = gateway.charge(r.method(), r.instrumentRef(), r.amount());
        long paymentId = insertPayment(policyId, r, key, g, actor);
        Map<String, Object> contact = policyState.customerContact(p.getCustomerId());
        if (!g.approved()) {
            if (List.of("CARD", "BANK").contains(r.method())) {
                Integer maxRetries = jdbc.queryForObject("SELECT COALESCE((SELECT max_retries FROM billing_product_config WHERE product_id=?),3)",
                        Integer.class, p.getProductId());
                jdbc.update("INSERT INTO billing_retry_queue (payment_id, policy_id, max_attempts, next_attempt_dt, last_error) VALUES (?,?,?,?,?)",
                        paymentId, policyId, maxRetries, LocalDateTime.now().plusDays(1), g.reason());
            }
            notifications.enqueue("PAYMENT_FAILED", (String) contact.get("email"),
                    Map.of("name", contact.get("name"), "policyNo", p.getPolicyNo(), "amount", r.amount(), "reason", g.reason()),
                    "PAYMENT", paymentId, "PAYFAIL-" + paymentId);
            audit.log(actor, "PAYMENT_FAILED", "POLICY", policyId, g.code() + " " + g.reason());
            return new PaymentResult(paymentId, "FAILED", r.amount(), null, g.code(), g.reason(), BigDecimal.ZERO, BigDecimal.ZERO, false, p.getStatus());
        }
        return postPayment(p, paymentId, r.amount(), actor, contact);
    }

    private long insertPayment(Long policyId, PaymentRequest r, String key, PaymentGateway.Result g, String actor) {
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO premium_payment (policy_id, amount, method, instrument_ref, status, created_by, idempotency_key,
                      gateway_code, gateway_txn_ref, failure_reason) VALUES (?,?,?,?,?,?,?,?,?,?)""", new String[]{"payment_id"});
            ps.setLong(1, policyId);
            ps.setBigDecimal(2, r.amount());
            ps.setString(3, r.method());
            ps.setString(4, mask(r.instrumentRef()));
            ps.setString(5, g.approved() ? "POSTED" : "FAILED");
            ps.setString(6, actor);
            ps.setString(7, key);
            ps.setString(8, g.code());
            ps.setString(9, g.txnRef());
            ps.setString(10, g.reason());
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    /** Keep only the last 4 characters of card/bank references. */
    static String mask(String ref) {
        if (ref == null) {
            return null;
        }
        String s = ref.replaceAll("\\s", "");
        return s.length() <= 4 ? s : "*".repeat(Math.min(s.length() - 4, 12)) + s.substring(s.length() - 4);
    }

    private PaymentResult postPayment(Policy p, long paymentId, BigDecimal amount, String actor, Map<String, Object> contact) {
        BigDecimal remaining = amount;
        BigDecimal premiumAlloc = BigDecimal.ZERO;
        BigDecimal feeAlloc = BigDecimal.ZERO;
        for (Map<String, Object> s : jdbc.queryForList("""
                SELECT * FROM premium_schedule WHERE policy_id=? AND status IN ('OVERDUE','DUE','PARTIAL')
                ORDER BY due_dt, installment_no FOR UPDATE""", p.getId())) {
            if (remaining.signum() <= 0) {
                break;
            }
            long sid = ((Number) s.get("schedule_id")).longValue();
            BigDecimal premOpen = ((BigDecimal) s.get("amount_due")).subtract((BigDecimal) s.get("amount_paid"));
            BigDecimal feeOpen = ((BigDecimal) s.get("late_fee")).subtract((BigDecimal) s.get("late_fee_paid"));
            BigDecimal prem = Money.min(premOpen, remaining);
            remaining = remaining.subtract(prem);
            BigDecimal fee = Money.min(feeOpen, remaining);
            remaining = remaining.subtract(fee);
            if (prem.signum() > 0) {
                jdbc.update("INSERT INTO payment_allocation (payment_id, schedule_id, alloc_type, amount) VALUES (?,?,'PREMIUM',?)", paymentId, sid, prem);
                premiumAlloc = premiumAlloc.add(prem);
            }
            if (fee.signum() > 0) {
                jdbc.update("INSERT INTO payment_allocation (payment_id, schedule_id, alloc_type, amount) VALUES (?,?,'LATE_FEE',?)", paymentId, sid, fee);
                feeAlloc = feeAlloc.add(fee);
            }
            jdbc.update("UPDATE premium_schedule SET amount_paid=amount_paid+?, late_fee_paid=late_fee_paid+? WHERE schedule_id=?", prem, fee, sid);
            refreshScheduleStatus(sid);
        }
        BigDecimal credited = remaining;
        if (credited.signum() > 0) {
            jdbc.update("INSERT INTO policy_credit_txn (policy_id, amount, reason, ref_type, ref_id) VALUES (?,?,?,?,?)",
                    p.getId(), credited, "Overpayment", "PAYMENT", paymentId);
        }
        ledger.post("PAYMENT", "PAYMENT", paymentId, p.getId(), "Payment " + paymentId + " for " + p.getPolicyNo(), actor,
                List.of(Line.dr("CASH", amount), Line.cr("PREM_RECV", premiumAlloc), Line.cr("FEE_RECV", feeAlloc),
                        Line.cr("SUSPENSE", credited)));
        Long seq = jdbc.queryForObject("SELECT nextval('receipt_no_seq')", Long.class);
        String receiptNo = String.format("RCP-%d-%07d", LocalDate.now().getYear(), seq);
        jdbc.update("INSERT INTO billing_receipt (payment_id, receipt_no, amount) VALUES (?,?,?)", paymentId, receiptNo, amount);
        if ("GRACE".equals(p.getStatus()) && outstanding(p.getId(), LocalDate.now()).signum() == 0) {
            policyState.transition(p, "ACTIVE", "Arrears cleared by payment " + receiptNo, actor);
        }
        notifications.enqueue("PAYMENT_RECEIVED", (String) contact.get("email"),
                Map.of("name", contact.get("name"), "policyNo", p.getPolicyNo(), "amount", amount, "receiptNo", receiptNo),
                "PAYMENT", paymentId, "PAYOK-" + paymentId);
        audit.log(actor, "PAYMENT_POSTED", "POLICY", p.getId(), receiptNo + " " + amount);
        return new PaymentResult(paymentId, "POSTED", amount, receiptNo, "00", null, premiumAlloc.add(feeAlloc), credited, false, p.getStatus());
    }

    private PaymentResult replay(Map<String, Object> e) {
        long id = ((Number) e.get("payment_id")).longValue();
        List<String> receipt = jdbc.queryForList("SELECT receipt_no FROM billing_receipt WHERE payment_id=?", String.class, id);
        BigDecimal allocated = jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM payment_allocation WHERE payment_id=?", BigDecimal.class, id);
        BigDecimal amount = (BigDecimal) e.get("amount");
        String status = (String) e.get("status");
        String policyStatus = policyState.get(((Number) e.get("policy_id")).longValue()).getStatus();
        return new PaymentResult(id, status, amount, receipt.isEmpty() ? null : receipt.get(0), (String) e.get("gateway_code"),
                (String) e.get("failure_reason"), allocated, "POSTED".equals(status) ? amount.subtract(allocated) : BigDecimal.ZERO, true, policyStatus);
    }

    private void refreshScheduleStatus(long scheduleId) {
        jdbc.update("""
                UPDATE premium_schedule SET
                  status = CASE
                    WHEN amount_paid >= amount_due AND late_fee_paid >= late_fee THEN 'PAID'
                    WHEN due_dt < CURRENT_DATE THEN 'OVERDUE'
                    WHEN amount_paid > 0 THEN 'PARTIAL'
                    ELSE 'DUE' END,
                  paid_dt = CASE WHEN amount_paid >= amount_due AND late_fee_paid >= late_fee THEN CURRENT_DATE ELSE NULL END
                WHERE schedule_id=? AND status NOT IN ('WAIVED','CANCELLED')""", scheduleId);
    }

    @Transactional
    public Map<String, Object> reverse(Long paymentId, String reason, String actor) {
        Map<String, Object> pay = jdbc.queryForList("SELECT * FROM premium_payment WHERE payment_id=? FOR UPDATE", paymentId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Payment", paymentId));
        if (!"POSTED".equals(pay.get("status"))) {
            throw ApiException.rule("Only POSTED payments can be reversed");
        }
        if (reason == null || reason.isBlank()) {
            throw ApiException.validation("Reason is required");
        }
        long policyId = ((Number) pay.get("policy_id")).longValue();
        BigDecimal amount = (BigDecimal) pay.get("amount");
        BigDecimal prem = BigDecimal.ZERO, fee = BigDecimal.ZERO;
        for (Map<String, Object> a : jdbc.queryForList("SELECT * FROM payment_allocation WHERE payment_id=?", paymentId)) {
            BigDecimal amt = (BigDecimal) a.get("amount");
            long sid = ((Number) a.get("schedule_id")).longValue();
            if ("PREMIUM".equals(a.get("alloc_type"))) {
                jdbc.update("UPDATE premium_schedule SET amount_paid=amount_paid-? WHERE schedule_id=?", amt, sid);
                prem = prem.add(amt);
            } else {
                jdbc.update("UPDATE premium_schedule SET late_fee_paid=late_fee_paid-? WHERE schedule_id=?", amt, sid);
                fee = fee.add(amt);
            }
            refreshScheduleStatus(sid);
        }
        BigDecimal suspense = amount.subtract(prem).subtract(fee);
        if (suspense.signum() > 0) {
            jdbc.update("INSERT INTO policy_credit_txn (policy_id, amount, reason, ref_type, ref_id) VALUES (?,?,?,?,?)",
                    policyId, suspense.negate(), "Payment reversal", "PAYMENT", paymentId);
        }
        jdbc.update("UPDATE premium_payment SET status='REVERSED', failure_reason=? WHERE payment_id=?", "Reversed: " + reason, paymentId);
        ledger.post("PAYMENT_REVERSAL", "PAYMENT", paymentId, policyId, "Reversal of payment " + paymentId, actor,
                List.of(Line.cr("CASH", amount), Line.dr("PREM_RECV", prem), Line.dr("FEE_RECV", fee), Line.dr("SUSPENSE", suspense)));
        audit.log(actor, "PAYMENT_REVERSED", "POLICY", policyId, paymentId + ": " + reason);
        return Map.of("paymentId", paymentId, "status", "REVERSED");
    }

    @Transactional
    public void waiveLateFee(Long scheduleId, String actor) {
        Map<String, Object> s = jdbc.queryForList("SELECT * FROM premium_schedule WHERE schedule_id=?", scheduleId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Schedule", scheduleId));
        BigDecimal open = ((BigDecimal) s.get("late_fee")).subtract((BigDecimal) s.get("late_fee_paid"));
        if (open.signum() <= 0) {
            throw ApiException.rule("No outstanding late fee on this installment");
        }
        long policyId = ((Number) s.get("policy_id")).longValue();
        jdbc.update("UPDATE premium_schedule SET late_fee=late_fee_paid WHERE schedule_id=?", scheduleId);
        refreshScheduleStatus(scheduleId);
        ledger.post("LATE_FEE_WAIVER", "SCHEDULE", scheduleId, policyId, "Late fee waived", actor,
                List.of(Line.dr("FEE_INCOME", open), Line.cr("FEE_RECV", open)));
        audit.log(actor, "LATE_FEE_WAIVED", "POLICY", policyId, "schedule " + scheduleId + " " + open);
    }

    /** Cancels unpaid future installments and reverses their billed receivable. */
    public BigDecimal cancelFutureInstallments(Policy p, LocalDate effective, String actor) {
        BigDecimal unpaid = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount_due-amount_paid),0) FROM premium_schedule
                WHERE policy_id=? AND due_dt>=? AND status IN ('DUE','PARTIAL')""", BigDecimal.class, p.getId(), effective);
        jdbc.update("UPDATE premium_schedule SET status='CANCELLED' WHERE policy_id=? AND due_dt>=? AND status IN ('DUE','PARTIAL') AND amount_paid=0",
                p.getId(), effective);
        jdbc.update("UPDATE premium_schedule SET status='PAID', amount_due=amount_paid WHERE policy_id=? AND due_dt>=? AND status='PARTIAL'",
                p.getId(), effective);
        ledger.post("PREMIUM_CANCELLED", "POLICY", p.getId(), p.getId(), "Future installments cancelled", actor,
                List.of(Line.dr("PREM_INCOME", unpaid), Line.cr("PREM_RECV", unpaid)));
        return unpaid;
    }

    // ---------------------------------------------------------------- refunds

    public long createRefund(Policy p, String requestKey, Long cancellationId, BigDecimal gross, BigDecimal fee, String method, String actor) {
        List<Long> existing = jdbc.queryForList("SELECT refund_id FROM billing_refund WHERE request_key=?", Long.class, requestKey);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        BigDecimal net = Money.max(gross.subtract(fee), BigDecimal.ZERO);
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO billing_refund (policy_id, request_key, cancellation_id, gross_amount, fee_amount, net_amount, method)
                    VALUES (?,?,?,?,?,?,?)""", new String[]{"refund_id"});
            ps.setLong(1, p.getId());
            ps.setString(2, requestKey);
            ps.setObject(3, cancellationId);
            ps.setBigDecimal(4, gross);
            ps.setBigDecimal(5, fee);
            ps.setBigDecimal(6, net);
            ps.setString(7, method);
            return ps;
        }, kh);
        long refundId = kh.getKey().longValue();
        ledger.post("REFUND", "REFUND", refundId, p.getId(), "Refund for " + p.getPolicyNo(), actor,
                List.of(Line.dr("PREM_INCOME", net), Line.cr("REFUND_PAY", net)));
        return refundId;
    }

    public List<Map<String, Object>> refunds(String status) {
        String sql = "SELECT r.*, p.policy_no FROM billing_refund r JOIN policy p ON p.policy_id=r.policy_id %s ORDER BY r.refund_id DESC LIMIT 500";
        return status == null || status.isBlank() ? jdbc.queryForList(sql.formatted(""))
                : jdbc.queryForList(sql.formatted("WHERE r.status=?"), status);
    }

    @Transactional
    public Map<String, Object> markRefundPaid(Long refundId, String actor) {
        Map<String, Object> r = jdbc.queryForList("SELECT * FROM billing_refund WHERE refund_id=? FOR UPDATE", refundId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Refund", refundId));
        if (!"POSTED".equals(r.get("status"))) {
            throw ApiException.rule("Refund is " + r.get("status"));
        }
        jdbc.update("UPDATE billing_refund SET status='PAID', paid_dt=now() WHERE refund_id=?", refundId);
        BigDecimal net = (BigDecimal) r.get("net_amount");
        long policyId = ((Number) r.get("policy_id")).longValue();
        ledger.post("REFUND_PAID", "REFUND", refundId, policyId, "Refund " + refundId + " paid", actor,
                List.of(Line.dr("REFUND_PAY", net), Line.cr("CASH", net)));
        audit.log(actor, "REFUND_PAID", "POLICY", policyId, refundId + " " + net);
        return Map.of("refundId", refundId, "status", "PAID");
    }

    // ---------------------------------------------------------------- batch steps

    @Transactional
    public int markOverdue(LocalDate asOf) {
        return jdbc.update("UPDATE premium_schedule SET status='OVERDUE' WHERE status IN ('DUE','PARTIAL') AND due_dt < ?", asOf);
    }

    @Transactional
    public int applyLateFees(LocalDate asOf) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT s.schedule_id, s.policy_id, s.amount_due - s.amount_paid AS open_amt, c.late_fee_pct, c.late_fee_flat
                FROM premium_schedule s JOIN policy p ON p.policy_id=s.policy_id
                LEFT JOIN billing_product_config c ON c.product_id=p.product_id
                WHERE s.status='OVERDUE' AND s.late_fee=0 AND s.due_dt < ? AND p.status IN ('ACTIVE','GRACE')""", asOf);
        int n = 0;
        for (Map<String, Object> r : rows) {
            BigDecimal pct = Money.nz((BigDecimal) r.get("late_fee_pct"));
            BigDecimal flat = Money.nz((BigDecimal) r.get("late_fee_flat"));
            BigDecimal fee = Money.pct((BigDecimal) r.get("open_amt"), pct).add(flat);
            if (fee.signum() <= 0) {
                continue;
            }
            long sid = ((Number) r.get("schedule_id")).longValue();
            long pid = ((Number) r.get("policy_id")).longValue();
            jdbc.update("UPDATE premium_schedule SET late_fee=? WHERE schedule_id=?", fee, sid);
            ledger.post("LATE_FEE", "SCHEDULE", sid, pid, "Late fee", "BATCH",
                    List.of(Line.dr("FEE_RECV", fee), Line.cr("FEE_INCOME", fee)));
            n++;
        }
        return n;
    }

    @Transactional
    public Map<String, Integer> graceAndLapse(LocalDate asOf) {
        int grace = 0, lapsed = 0;
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT p.policy_id, p.status, MIN(s.due_dt) AS oldest_due, COALESCE(MAX(c.grace_days),30) AS grace_days
                FROM policy p JOIN premium_schedule s ON s.policy_id=p.policy_id AND s.status='OVERDUE'
                LEFT JOIN billing_product_config c ON c.product_id=p.product_id
                WHERE p.status IN ('ACTIVE','GRACE') GROUP BY p.policy_id, p.status""")) {
            long pid = ((Number) r.get("policy_id")).longValue();
            LocalDate oldest = ((java.sql.Date) r.get("oldest_due")).toLocalDate();
            int graceDays = ((Number) r.get("grace_days")).intValue();
            Policy p = policyState.get(pid);
            Map<String, Object> contact = policyState.customerContact(p.getCustomerId());
            if (oldest.plusDays(graceDays).isBefore(asOf)) {
                String from = p.getStatus();
                if ("ACTIVE".equals(from)) {
                    policyState.transition(p, "GRACE", "Premium overdue since " + oldest, "BATCH");
                }
                policyState.transition(p, "LAPSED", "Grace period of " + graceDays + " days expired", "BATCH");
                p.setLapseDt(asOf);
                jdbc.update("INSERT INTO billing_lapse_log (policy_id, from_status, to_status, as_of_dt, detail) VALUES (?,?,?,?,?)",
                        pid, from, "LAPSED", asOf, "oldest overdue " + oldest);
                notifications.enqueue("POLICY_LAPSED", (String) contact.get("email"),
                        Map.of("name", contact.get("name"), "policyNo", p.getPolicyNo()), "POLICY", pid, "LAPSE-" + pid + "-" + asOf);
                lapsed++;
            } else if ("ACTIVE".equals(p.getStatus())) {
                policyState.transition(p, "GRACE", "Premium overdue since " + oldest, "BATCH");
                jdbc.update("INSERT INTO billing_lapse_log (policy_id, from_status, to_status, as_of_dt, detail) VALUES (?,?,?,?,?)",
                        pid, "ACTIVE", "GRACE", asOf, "oldest overdue " + oldest);
                notifications.enqueue("POLICY_GRACE", (String) contact.get("email"),
                        Map.of("name", contact.get("name"), "policyNo", p.getPolicyNo()), "POLICY", pid, "GRACE-" + pid + "-" + oldest);
                grace++;
            }
        }
        return Map.of("grace", grace, "lapsed", lapsed);
    }

    @Transactional
    public Map<String, Integer> processRetries() {
        int ok = 0, failed = 0, exhausted = 0;
        for (Map<String, Object> q : jdbc.queryForList("""
                SELECT q.*, p.amount, p.method, p.instrument_ref FROM billing_retry_queue q JOIN premium_payment p ON p.payment_id=q.payment_id
                WHERE q.status='PENDING' AND q.next_attempt_dt <= now() ORDER BY q.retry_id LIMIT 200""")) {
            long retryId = ((Number) q.get("retry_id")).longValue();
            long policyId = ((Number) q.get("policy_id")).longValue();
            int attempt = ((Number) q.get("attempt_no")).intValue() + 1;
            Policy p = policyState.get(policyId);
            if (!List.of("ACTIVE", "GRACE").contains(p.getStatus()) || outstanding(policyId, null).signum() == 0) {
                jdbc.update("UPDATE billing_retry_queue SET status='CANCELLED', last_error='Nothing to collect' WHERE retry_id=?", retryId);
                continue;
            }
            BigDecimal amount = Money.min((BigDecimal) q.get("amount"), outstanding(policyId, null));
            PaymentRequest req = new PaymentRequest(amount, (String) q.get("method"), (String) q.get("instrument_ref"),
                    "RETRY-" + retryId + "-" + attempt);
            PaymentResult res = pay(policyId, req, "BATCH");
            jdbc.update("UPDATE premium_payment SET retry_count=? WHERE payment_id=?", attempt, res.paymentId());
            jdbc.update("DELETE FROM billing_retry_queue WHERE payment_id=? AND retry_id<>?", res.paymentId(), retryId);
            if ("POSTED".equals(res.status())) {
                jdbc.update("UPDATE billing_retry_queue SET status='DONE', attempt_no=? WHERE retry_id=?", attempt, retryId);
                ok++;
            } else if (attempt >= ((Number) q.get("max_attempts")).intValue()) {
                jdbc.update("UPDATE billing_retry_queue SET status='EXHAUSTED', attempt_no=?, last_error=? WHERE retry_id=?",
                        attempt, res.failureReason(), retryId);
                exhausted++;
            } else {
                jdbc.update("UPDATE billing_retry_queue SET attempt_no=?, last_error=?, next_attempt_dt=? WHERE retry_id=?",
                        attempt, res.failureReason(), LocalDateTime.now().plusDays(attempt), retryId);
                failed++;
            }
        }
        return Map.of("succeeded", ok, "failed", failed, "exhausted", exhausted);
    }

    public Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("collectedToday", jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM premium_payment WHERE status='POSTED' AND payment_dt::date=CURRENT_DATE", BigDecimal.class));
        m.put("collectedMonth", jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM premium_payment WHERE status='POSTED' AND date_trunc('month',payment_dt)=date_trunc('month',now())", BigDecimal.class));
        m.put("overdueAmount", jdbc.queryForObject("SELECT COALESCE(SUM(amount_due-amount_paid+late_fee-late_fee_paid),0) FROM premium_schedule WHERE status='OVERDUE'", BigDecimal.class));
        m.put("overdueInstallments", jdbc.queryForObject("SELECT count(*) FROM premium_schedule WHERE status='OVERDUE'", Long.class));
        m.put("failedPayments30d", jdbc.queryForObject("SELECT count(*) FROM premium_payment WHERE status='FAILED' AND payment_dt > now() - interval '30 days'", Long.class));
        m.put("pendingRetries", jdbc.queryForObject("SELECT count(*) FROM billing_retry_queue WHERE status='PENDING'", Long.class));
        m.put("refundsPending", jdbc.queryForObject("SELECT COALESCE(SUM(net_amount),0) FROM billing_refund WHERE status='POSTED'", BigDecimal.class));
        return m;
    }
}
