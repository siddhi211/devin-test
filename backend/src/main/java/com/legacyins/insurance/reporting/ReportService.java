package com.legacyins.insurance.reporting;

import com.legacyins.insurance.common.ApiException;
import com.legacyins.insurance.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/** Report catalogue with parameterised queries, CSV export, saved filters, dashboard KPIs and summary-table refresh. */
@Service
@RequiredArgsConstructor
public class ReportService {
    private final JdbcTemplate jdbc;

    public record ReportDef(String code, String name, String description, List<String> params, Set<String> roles, String sql) {
    }

    private static final Set<String> ALL = Set.of("ADMIN", "CSR", "UNDERWRITER", "ADJUSTER", "FINANCE", "MANAGER");
    private static final Set<String> FIN = Set.of("ADMIN", "FINANCE", "MANAGER");

    private static final List<ReportDef> CATALOGUE = List.of(
            new ReportDef("POLICY_REGISTER", "Policy register", "Policies by status and product, optionally filtered by start date",
                    List.of("fromDt", "toDt", "status", "productCode"), ALL, """
                    SELECT p.policy_no, p.status, pr.product_code, c.customer_no,
                           COALESCE(c.company_name, c.first_name || ' ' || c.last_name) AS customer, p.sum_insured, p.annual_premium,
                           p.payment_frequency, p.start_dt, p.end_dt, p.branch_cd
                    FROM policy p JOIN insurance_product pr ON pr.product_id=p.product_id JOIN customer c ON c.customer_id=p.customer_id
                    WHERE p.start_dt BETWEEN :fromDt AND :toDt AND (:status = '' OR p.status = :status)
                      AND (:productCode = '' OR pr.product_code = :productCode)
                    ORDER BY p.policy_id"""),
            new ReportDef("PREMIUM_COLLECTION", "Premium collections", "Posted, failed and reversed payments in a date range",
                    List.of("fromDt", "toDt", "status"), FIN, """
                    SELECT pp.payment_id, p.policy_no, pp.amount, pp.method, pp.status, pp.payment_dt, pp.gateway_code, r.receipt_no
                    FROM premium_payment pp JOIN policy p ON p.policy_id=pp.policy_id LEFT JOIN billing_receipt r ON r.payment_id=pp.payment_id
                    WHERE CAST(pp.payment_dt AS DATE) BETWEEN :fromDt AND :toDt AND (:status = '' OR pp.status = :status)
                    ORDER BY pp.payment_id"""),
            new ReportDef("PREMIUM_AGEING", "Premium ageing", "Outstanding installments bucketed by days overdue as of a date",
                    List.of("asOfDt"), FIN, """
                    SELECT pr.product_code,
                           CASE WHEN :asOfDt - s.due_dt <= 0 THEN 'CURRENT' WHEN :asOfDt - s.due_dt <= 30 THEN '1-30'
                                WHEN :asOfDt - s.due_dt <= 60 THEN '31-60' WHEN :asOfDt - s.due_dt <= 90 THEN '61-90' ELSE '90+' END AS bucket,
                           count(*) AS items, SUM(s.amount_due - s.amount_paid + s.late_fee - s.late_fee_paid) AS outstanding
                    FROM premium_schedule s JOIN policy p ON p.policy_id=s.policy_id JOIN insurance_product pr ON pr.product_id=p.product_id
                    WHERE s.status IN ('DUE','PARTIAL','OVERDUE') AND s.due_dt <= :asOfDt
                    GROUP BY 1,2 ORDER BY 1,2"""),
            new ReportDef("CLAIMS_REGISTER", "Claims register", "Claims reported in a date range",
                    List.of("fromDt", "toDt", "status"), Set.of("ADMIN", "ADJUSTER", "MANAGER", "FINANCE", "CSR"), """
                    SELECT c.claim_no, p.policy_no, c.category_code, CASE WHEN c.status='PARTIAL' THEN 'PARTIALLY_APPROVED' ELSE c.status END AS status,
                           c.priority, c.incident_dt, c.reported_dt, c.claimed_amount, c.assessed_amount, c.approved_amount, c.settled_amount,
                           c.fraud_score, c.sla_breached, u.username AS adjuster
                    FROM claim c JOIN policy p ON p.policy_id=c.policy_id LEFT JOIN user_account u ON u.user_id=c.assigned_adjuster_id
                    WHERE c.reported_dt BETWEEN :fromDt AND :toDt
                      AND (:status = '' OR c.status = (CASE WHEN :status='PARTIALLY_APPROVED' THEN 'PARTIAL' ELSE :status END))
                    ORDER BY c.claim_id"""),
            new ReportDef("CLAIMS_PAID", "Claim payouts", "Payout and reversal events in a date range",
                    List.of("fromDt", "toDt"), FIN, """
                    SELECT e.event_id, c.claim_no, e.event_type, e.amount, e.payout_ref, e.actor, e.event_dt
                    FROM claim_payout_event e JOIN claim c ON c.claim_id=e.claim_id
                    WHERE CAST(e.event_dt AS DATE) BETWEEN :fromDt AND :toDt ORDER BY e.event_id"""),
            new ReportDef("UW_DECISIONS", "Underwriting decisions", "Automatic and manual underwriting outcomes",
                    List.of("fromDt", "toDt"), Set.of("ADMIN", "UNDERWRITER", "MANAGER"), """
                    SELECT d.decision_id, p.policy_no, d.outcome, d.total_points, d.decided_by, d.decided_dt, d.note
                    FROM uw_decision d JOIN policy p ON p.policy_id=d.policy_id
                    WHERE CAST(d.decided_dt AS DATE) BETWEEN :fromDt AND :toDt ORDER BY d.decision_id"""),
            new ReportDef("PRODUCT_PERFORMANCE", "Product performance", "Policies, premium, claims and loss ratio per product",
                    List.of(), FIN, """
                    SELECT pr.product_code, pr.product_name, count(DISTINCT p.policy_id) AS policies,
                           COALESCE(SUM(DISTINCT p.annual_premium),0) AS written_premium,
                           (SELECT COALESCE(SUM(pp.amount),0) FROM premium_payment pp JOIN policy x ON x.policy_id=pp.policy_id
                              WHERE x.product_id=pr.product_id AND pp.status='POSTED') AS collected,
                           (SELECT COALESCE(SUM(c.settled_amount),0) FROM claim c JOIN policy x ON x.policy_id=c.policy_id WHERE x.product_id=pr.product_id) AS claims_paid
                    FROM insurance_product pr LEFT JOIN policy p ON p.product_id=pr.product_id AND p.status NOT IN ('DRAFT','WITHDRAWN','DECLINED')
                    GROUP BY pr.product_id, pr.product_code, pr.product_name ORDER BY pr.product_code"""),
            new ReportDef("AUDIT_TRAIL", "Audit trail", "Audit log entries in a date range",
                    List.of("fromDt", "toDt", "actor"), Set.of("ADMIN"), """
                    SELECT audit_id, created_dt, actor, action, entity_type, entity_id, detail FROM audit_log
                    WHERE CAST(created_dt AS DATE) BETWEEN :fromDt AND :toDt AND (:actor = '' OR actor = :actor)
                    ORDER BY audit_id DESC LIMIT 5000"""),
            new ReportDef("NOTIFICATIONS", "Notification log", "Outbox messages by status",
                    List.of("status"), Set.of("ADMIN", "MANAGER"), """
                    SELECT outbox_id, template_code, recipient_addr, status, attempts, created_dt, sent_dt, last_error FROM notification_outbox
                    WHERE (:status = '' OR status = :status) ORDER BY outbox_id DESC LIMIT 5000"""));

    public List<Map<String, Object>> catalogue(Set<String> roles) {
        return CATALOGUE.stream().filter(d -> d.roles().stream().anyMatch(roles::contains))
                .map(d -> Map.<String, Object>of("code", d.code(), "name", d.name(), "description", d.description(), "params", d.params()))
                .toList();
    }

    private ReportDef def(String code, Set<String> roles) {
        ReportDef d = CATALOGUE.stream().filter(x -> x.code().equals(code)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Report", code));
        if (d.roles().stream().noneMatch(roles::contains)) {
            throw new ApiException(ErrorCode.NOT_AUTHORIZED, "Not authorised for report " + code);
        }
        return d;
    }

    @Transactional
    public Map<String, Object> run(String code, Map<String, String> params, String format, String actor, Set<String> roles) {
        ReportDef d = def(code, roles);
        long t0 = System.currentTimeMillis();
        String sql = d.sql();
        List<Object> args = new ArrayList<>();
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < sql.length()) {
            char ch = sql.charAt(i);
            if (ch == ':' && i + 1 < sql.length() && Character.isLetter(sql.charAt(i + 1)) && (i == 0 || sql.charAt(i - 1) != ':')) {
                int j = i + 1;
                while (j < sql.length() && Character.isLetterOrDigit(sql.charAt(j))) {
                    j++;
                }
                String name = sql.substring(i + 1, j);
                args.add(param(name, params.get(name)));
                out.append(name.endsWith("Dt") ? "CAST(? AS DATE)" : "CAST(? AS VARCHAR)");
                i = j;
            } else {
                out.append(ch);
                i++;
            }
        }
        List<Map<String, Object>> rows = jdbc.queryForList(out.toString(), args.toArray());
        long ms = System.currentTimeMillis() - t0;
        jdbc.update("INSERT INTO report_run (report_code, params, output_fmt, row_count, duration_ms, run_by) VALUES (?,?,?,?,?,?)",
                code, params.toString(), format == null ? "JSON" : format.toUpperCase(), rows.size(), ms, actor);
        List<String> columns = rows.isEmpty() ? List.of() : new ArrayList<>(rows.get(0).keySet());
        return Map.of("code", code, "name", d.name(), "columns", columns, "rows", rows, "rowCount", rows.size(), "durationMs", ms);
    }

    private static Object param(String name, String value) {
        if (name.endsWith("Dt")) {
            if (value == null || value.isBlank()) {
                return name.startsWith("from") ? LocalDate.now().minusYears(1).toString() : LocalDate.now().toString();
            }
            try {
                return LocalDate.parse(value).toString();
            } catch (Exception e) {
                throw ApiException.validation("Parameter " + name + " must be an ISO date");
            }
        }
        return value == null ? "" : value.trim();
    }

    public static String toCsv(Map<String, Object> result) {
        @SuppressWarnings("unchecked") List<String> cols = (List<String>) result.get("columns");
        @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");
        StringBuilder sb = new StringBuilder(String.join(",", cols)).append("\n");
        for (Map<String, Object> r : rows) {
            StringJoiner j = new StringJoiner(",");
            for (String c : cols) {
                Object v = r.get(c);
                String s = v == null ? "" : v.toString();
                if (s.startsWith("=") || s.startsWith("+") || s.startsWith("-") && !s.matches("-?[0-9.]+") || s.startsWith("@")) {
                    s = "'" + s;
                }
                j.add(s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s);
            }
            sb.append(j).append("\n");
        }
        return sb.toString();
    }

    public List<Map<String, Object>> history(int limit) {
        return jdbc.queryForList("SELECT * FROM report_run ORDER BY run_id DESC LIMIT ?", Math.min(limit, 500));
    }

    public List<Map<String, Object>> filters(String username) {
        return jdbc.queryForList("SELECT * FROM saved_filter WHERE username=? ORDER BY report_code, filter_name", username);
    }

    public Map<String, Object> saveFilter(String username, String reportCode, String name, String params) {
        if (name == null || name.isBlank()) {
            throw ApiException.validation("Filter name is required");
        }
        jdbc.update("""
                INSERT INTO saved_filter (username, report_code, filter_name, params) VALUES (?,?,?,?)
                ON CONFLICT (username, filter_name) DO UPDATE SET report_code=EXCLUDED.report_code, params=EXCLUDED.params""",
                username, reportCode, name, params);
        return jdbc.queryForMap("SELECT * FROM saved_filter WHERE username=? AND filter_name=?", username, name);
    }

    public void deleteFilter(String username, Long id) {
        if (jdbc.update("DELETE FROM saved_filter WHERE filter_id=? AND username=?", id, username) == 0) {
            throw ApiException.notFound("Saved filter", id);
        }
    }

    public Map<String, Object> dashboard() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("customers", jdbc.queryForObject("SELECT count(*) FROM customer WHERE status<>'M'", Long.class));
        m.put("policiesByStatus", jdbc.queryForList("SELECT status, count(*) AS count FROM policy GROUP BY status ORDER BY status"));
        m.put("activePremium", jdbc.queryForObject("SELECT COALESCE(SUM(annual_premium),0) FROM policy WHERE status IN ('ACTIVE','GRACE')", java.math.BigDecimal.class));
        m.put("collectedThisMonth", jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM premium_payment WHERE status='POSTED' AND date_trunc('month',payment_dt)=date_trunc('month',now())", java.math.BigDecimal.class));
        m.put("overdueInstallments", jdbc.queryForObject("SELECT count(*) FROM premium_schedule WHERE status='OVERDUE'", Long.class));
        m.put("openClaims", jdbc.queryForObject("SELECT count(*) FROM claim WHERE status NOT IN ('SETTLED','CLOSED','REJECTED')", Long.class));
        m.put("claimsPaidThisMonth", jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM claim_payout_event WHERE date_trunc('month',event_dt)=date_trunc('month',now())", java.math.BigDecimal.class));
        m.put("openTasks", jdbc.queryForObject("SELECT count(*) FROM workflow_task WHERE status IN ('OPEN','CLAIMED')", Long.class));
        m.put("openDiscrepancies", jdbc.queryForObject("SELECT count(*) FROM reconciliation_discrepancy WHERE status='OPEN'", Long.class));
        m.put("monthlyPremium", jdbc.queryForList("""
                SELECT to_char(date_trunc('month', payment_dt),'YYYY-MM') AS month, SUM(amount) AS amount FROM premium_payment
                WHERE status='POSTED' AND payment_dt > now() - interval '12 months' GROUP BY 1 ORDER BY 1"""));
        m.put("claimsByCategory", jdbc.queryForList("SELECT category_code, count(*) AS count, COALESCE(SUM(claimed_amount),0) AS claimed FROM claim GROUP BY 1 ORDER BY 1"));
        return m;
    }

    @Transactional
    public Map<String, Integer> refreshSummaries(String actor) {
        long t0 = System.currentTimeMillis();
        jdbc.update("DELETE FROM rpt_policy_monthly WHERE period_ym = to_char(now(),'YYYY-MM')");
        int a = jdbc.update("""
                INSERT INTO rpt_policy_monthly (period_ym, product_code, branch_cd, status, policy_count, sum_insured, annual_premium)
                SELECT to_char(now(),'YYYY-MM'), pr.product_code, COALESCE(p.branch_cd,'HO'), p.status, count(*), SUM(p.sum_insured), SUM(p.annual_premium)
                FROM policy p JOIN insurance_product pr ON pr.product_id=p.product_id GROUP BY 2,3,4""");
        jdbc.update("INSERT INTO rpt_refresh_log (table_name, row_count, duration_ms, refreshed_by) VALUES (?,?,?,?)",
                "RPT_POLICY_MONTHLY", a, System.currentTimeMillis() - t0, actor);
        t0 = System.currentTimeMillis();
        jdbc.update("DELETE FROM rpt_premium_ageing WHERE as_of_dt = CURRENT_DATE");
        int b = jdbc.update("""
                INSERT INTO rpt_premium_ageing (as_of_dt, product_code, bucket, item_count, outstanding)
                SELECT CURRENT_DATE, pr.product_code,
                       CASE WHEN CURRENT_DATE - s.due_dt <= 0 THEN 'CURRENT' WHEN CURRENT_DATE - s.due_dt <= 30 THEN '1-30'
                            WHEN CURRENT_DATE - s.due_dt <= 60 THEN '31-60' WHEN CURRENT_DATE - s.due_dt <= 90 THEN '61-90' ELSE '90+' END,
                       count(*), SUM(s.amount_due - s.amount_paid + s.late_fee - s.late_fee_paid)
                FROM premium_schedule s JOIN policy p ON p.policy_id=s.policy_id JOIN insurance_product pr ON pr.product_id=p.product_id
                WHERE s.status IN ('DUE','PARTIAL','OVERDUE') AND s.due_dt <= CURRENT_DATE GROUP BY 2,3""");
        jdbc.update("INSERT INTO rpt_refresh_log (table_name, row_count, duration_ms, refreshed_by) VALUES (?,?,?,?)",
                "RPT_PREMIUM_AGEING", b, System.currentTimeMillis() - t0, actor);
        return Map.of("rpt_policy_monthly", a, "rpt_premium_ageing", b);
    }
}
