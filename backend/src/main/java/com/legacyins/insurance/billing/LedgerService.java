package com.legacyins.insurance.billing;

import com.legacyins.insurance.common.ApiException;
import com.legacyins.insurance.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

/** Double-entry journal: every financial event posts balanced DR/CR lines. */
@Service
@RequiredArgsConstructor
public class LedgerService {
    private final JdbcTemplate jdbc;

    public record Line(String account, String drCr, BigDecimal amount) {
        public static Line dr(String account, BigDecimal amount) {
            return new Line(account, "DR", amount);
        }

        public static Line cr(String account, BigDecimal amount) {
            return new Line(account, "CR", amount);
        }
    }

    public long post(String eventType, String refType, Long refId, Long policyId, String description, String actor, List<Line> lines) {
        List<Line> nonZero = lines.stream().filter(l -> l.amount() != null && l.amount().signum() != 0).toList();
        if (nonZero.isEmpty()) {
            return 0;
        }
        BigDecimal dr = nonZero.stream().filter(l -> "DR".equals(l.drCr())).map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cr = nonZero.stream().filter(l -> "CR".equals(l.drCr())).map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (dr.compareTo(cr) != 0) {
            throw new ApiException(ErrorCode.SYSTEM, "Unbalanced journal for " + eventType + ": DR " + dr + " CR " + cr);
        }
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO billing_journal (event_type, ref_type, ref_id, policy_id, description, posted_by) VALUES (?,?,?,?,?,?)""",
                    new String[]{"journal_id"});
            ps.setString(1, eventType);
            ps.setString(2, refType);
            ps.setObject(3, refId);
            ps.setObject(4, policyId);
            ps.setString(5, description);
            ps.setString(6, actor);
            return ps;
        }, kh);
        long journalId = kh.getKey().longValue();
        for (Line l : nonZero) {
            jdbc.update("INSERT INTO billing_journal_line (journal_id, account_code, dr_cr, amount) VALUES (?,?,?,?)",
                    journalId, l.account(), l.drCr(), l.amount());
        }
        return journalId;
    }

    public List<Map<String, Object>> journals(Long policyId, int limit) {
        List<Map<String, Object>> journals = policyId == null
                ? jdbc.queryForList("SELECT * FROM billing_journal ORDER BY journal_id DESC LIMIT ?", limit)
                : jdbc.queryForList("SELECT * FROM billing_journal WHERE policy_id=? ORDER BY journal_id DESC LIMIT ?", policyId, limit);
        return journals.stream().map(j -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>(j);
            m.put("lines", jdbc.queryForList("SELECT account_code, dr_cr, amount FROM billing_journal_line WHERE journal_id=? ORDER BY line_id",
                    j.get("journal_id")));
            return m;
        }).toList();
    }

    public List<Map<String, Object>> trialBalance() {
        return jdbc.queryForList("""
                SELECT account_code,
                       SUM(CASE WHEN dr_cr='DR' THEN amount ELSE 0 END) AS debit,
                       SUM(CASE WHEN dr_cr='CR' THEN amount ELSE 0 END) AS credit,
                       SUM(CASE WHEN dr_cr='DR' THEN amount ELSE -amount END) AS balance
                FROM billing_journal_line GROUP BY account_code ORDER BY account_code""");
    }
}
