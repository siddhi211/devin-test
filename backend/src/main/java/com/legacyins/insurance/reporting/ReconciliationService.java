package com.legacyins.insurance.reporting;

import com.legacyins.insurance.auth.AuditService;
import com.legacyins.insurance.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Cross-checks payments, allocations, ledger, schedules and claim payouts; records OPEN discrepancies. */
@Service
@RequiredArgsConstructor
public class ReconciliationService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    private static final Map<String, String> CHECKS = new LinkedHashMap<>();

    static {
        CHECKS.put("UNBALANCED_JOURNAL", """
                SELECT 'JOURNAL' AS entity_type, j.journal_id AS entity_id,
                       'Debits ' || SUM(CASE WHEN l.dr_cr='DR' THEN l.amount ELSE 0 END) || ' <> credits ' || SUM(CASE WHEN l.dr_cr='CR' THEN l.amount ELSE 0 END) AS detail,
                       ABS(SUM(CASE WHEN l.dr_cr='DR' THEN l.amount ELSE -l.amount END)) AS amount
                FROM billing_journal j JOIN billing_journal_line l ON l.journal_id=j.journal_id GROUP BY j.journal_id
                HAVING SUM(CASE WHEN l.dr_cr='DR' THEN l.amount ELSE -l.amount END) <> 0""");
        CHECKS.put("PAYMENT_ALLOCATION", """
                SELECT 'PAYMENT' AS entity_type, p.payment_id AS entity_id,
                       'Payment ' || p.amount || ' vs allocated ' || COALESCE(a.total,0) || ' + credit ' || COALESCE(c.total,0) AS detail,
                       p.amount - COALESCE(a.total,0) - COALESCE(c.total,0) AS amount
                FROM premium_payment p
                LEFT JOIN (SELECT payment_id, SUM(amount) total FROM payment_allocation GROUP BY payment_id) a ON a.payment_id=p.payment_id
                LEFT JOIN (SELECT ref_id, SUM(amount) total FROM policy_credit_txn WHERE ref_type='PAYMENT' AND amount>0 GROUP BY ref_id) c ON c.ref_id=p.payment_id
                WHERE p.status='POSTED' AND p.amount <> COALESCE(a.total,0) + COALESCE(c.total,0)""");
        CHECKS.put("SCHEDULE_OVERPAID", """
                SELECT 'SCHEDULE' AS entity_type, schedule_id AS entity_id,
                       'Installment ' || installment_no || ' paid ' || amount_paid || ' exceeds due ' || amount_due AS detail,
                       amount_paid - amount_due AS amount
                FROM premium_schedule WHERE amount_paid > amount_due""");
        CHECKS.put("PAYOUT_MISMATCH", """
                SELECT 'CLAIM' AS entity_type, c.claim_id AS entity_id,
                       'Claim ' || c.claim_no || ' settled ' || c.settled_amount || ' vs paid settlements ' || COALESCE(s.total,0) AS detail,
                       c.settled_amount - COALESCE(s.total,0) AS amount
                FROM claim c LEFT JOIN (SELECT claim_id, SUM(net_amount) total FROM claim_settlement WHERE status='PAID' GROUP BY claim_id) s
                  ON s.claim_id=c.claim_id
                WHERE c.settled_amount <> COALESCE(s.total,0)""");
        CHECKS.put("ACTIVE_WITHOUT_SCHEDULE", """
                SELECT 'POLICY' AS entity_type, p.policy_id AS entity_id, 'Policy ' || p.policy_no || ' is ACTIVE with no premium schedule' AS detail,
                       p.annual_premium AS amount
                FROM policy p WHERE p.status IN ('ACTIVE','GRACE')
                  AND NOT EXISTS (SELECT 1 FROM premium_schedule s WHERE s.policy_id=p.policy_id)""");
        CHECKS.put("CLAIM_OVER_SUM_INSURED", """
                SELECT 'CLAIM' AS entity_type, c.claim_id AS entity_id, 'Approved ' || c.approved_amount || ' exceeds sum insured ' || p.sum_insured AS detail,
                       c.approved_amount - p.sum_insured AS amount
                FROM claim c JOIN policy p ON p.policy_id=c.policy_id WHERE c.approved_amount > p.sum_insured""");
    }

    public Set<String> checks() {
        return CHECKS.keySet();
    }

    @Transactional
    public Map<String, Integer> run(String actor) {
        Map<String, Integer> found = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : CHECKS.entrySet()) {
            int n = 0;
            for (Map<String, Object> row : jdbc.queryForList(e.getValue())) {
                n += jdbc.update("""
                        INSERT INTO reconciliation_discrepancy (check_code, entity_type, entity_id, detail, amount, status)
                        VALUES (?,?,?,?,?,'OPEN')
                        ON CONFLICT (check_code, entity_type, entity_id) WHERE status='OPEN' DO NOTHING""",
                        e.getKey(), row.get("entity_type"), row.get("entity_id"), row.get("detail"), row.get("amount"));
            }
            found.put(e.getKey(), n);
        }
        audit.log(actor, "RECONCILIATION_RUN", "RECON", null, found.toString());
        return found;
    }

    public List<Map<String, Object>> list(String status, String checkCode) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM reconciliation_discrepancy WHERE 1=1");
        if (status != null && !status.isBlank()) {
            sql.append(" AND status=?");
            args.add(status);
        }
        if (checkCode != null && !checkCode.isBlank()) {
            sql.append(" AND check_code=?");
            args.add(checkCode);
        }
        sql.append(" ORDER BY discrepancy_id DESC LIMIT 500");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    @Transactional
    public Map<String, Object> resolve(Long id, String note, String actor) {
        if (note == null || note.isBlank()) {
            throw ApiException.validation("Resolution note is required");
        }
        int n = jdbc.update("UPDATE reconciliation_discrepancy SET status='RESOLVED', resolved_dt=now(), resolved_by=?, resolution_note=? WHERE discrepancy_id=? AND status='OPEN'",
                actor, note, id);
        if (n == 0) {
            throw ApiException.notFound("Open discrepancy", id);
        }
        audit.log(actor, "RECONCILIATION_RESOLVE", "RECON", id, note);
        return jdbc.queryForMap("SELECT * FROM reconciliation_discrepancy WHERE discrepancy_id=?", id);
    }
}
