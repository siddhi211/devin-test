package com.legacyins.insurance.policy;

import com.legacyins.insurance.product.PremiumService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Points-based underwriting: occupation + medical codes + rules produce AUTO_APPROVE, REFER or DECLINE. */
@Service
@RequiredArgsConstructor
public class UnderwritingService {
    public static final int REFER_THRESHOLD = 50;

    private final JdbcTemplate jdbc;

    public record Evaluation(String outcome, int totalPoints, List<String> findings) {
    }

    public Evaluation evaluate(Policy p, LocalDate dateOfBirth) {
        List<String> findings = new ArrayList<>();
        int points = 0;
        boolean refer = false, decline = false;
        int age = dateOfBirth == null ? 0 : Period.between(dateOfBirth, p.getStartDt()).getYears();
        Integer occClass = null;
        if (p.getOccupationCode() != null) {
            List<Map<String, Object>> occ = jdbc.queryForList("SELECT * FROM uw_occupation WHERE occ_code=?", p.getOccupationCode());
            if (occ.isEmpty()) {
                findings.add("Unknown occupation " + p.getOccupationCode() + " - referred");
                refer = true;
            } else {
                occClass = ((Number) occ.get(0).get("risk_class")).intValue();
                int pts = ((Number) occ.get(0).get("points")).intValue();
                points += pts;
                if (pts > 0) {
                    findings.add("Occupation " + p.getOccupationCode() + " (class " + occClass + "): +" + pts);
                }
            }
        }
        if (p.getMedicalCodes() != null && !p.getMedicalCodes().isBlank()) {
            for (String code : p.getMedicalCodes().split(",")) {
                String c = code.trim();
                if (c.isEmpty()) {
                    continue;
                }
                List<Map<String, Object>> med = jdbc.queryForList("SELECT * FROM uw_medical_code WHERE med_code=?", c);
                if (med.isEmpty()) {
                    findings.add("Unknown medical code " + c + " - referred");
                    refer = true;
                    continue;
                }
                if ("Y".equals(med.get(0).get("decline_flag"))) {
                    findings.add("Medical code " + c + " is a decline condition");
                    decline = true;
                }
                int pts = ((Number) med.get(0).get("points")).intValue();
                points += pts;
                if (pts > 0) {
                    findings.add("Medical " + c + ": +" + pts);
                }
            }
        }
        for (Map<String, Object> rule : jdbc.queryForList("SELECT * FROM uw_rule WHERE active='Y' ORDER BY rule_id")) {
            Object actual = switch ((String) rule.get("field")) {
                case "AGE" -> age;
                case "SMOKER" -> p.getSmokerFlag();
                case "SUM_INSURED" -> p.getSumInsured();
                case "OCC_CLASS" -> occClass;
                case "RISK_CLASS" -> p.getRiskClass();
                default -> null;
            };
            if (!matches(actual, (String) rule.get("op"), (String) rule.get("threshold"))) {
                continue;
            }
            String action = (String) rule.get("action");
            String msg = rule.get("rule_code") + ": " + rule.get("message");
            switch (action) {
                case "DECLINE" -> {
                    decline = true;
                    findings.add(msg + " (decline)");
                }
                case "REFER" -> {
                    refer = true;
                    findings.add(msg + " (refer)");
                }
                default -> {
                    int pts = ((Number) rule.get("points")).intValue();
                    points += pts;
                    findings.add(msg + ": +" + pts);
                }
            }
        }
        if (points >= REFER_THRESHOLD) {
            refer = true;
            findings.add("Total points " + points + " >= referral threshold " + REFER_THRESHOLD);
        }
        String outcome = decline ? "DECLINE" : refer ? "REFER" : "AUTO_APPROVE";
        return new Evaluation(outcome, points, findings);
    }

    private static boolean matches(Object actual, String op, String threshold) {
        if (actual == null) {
            return false;
        }
        return PremiumService.matches(actual, op, threshold);
    }

    public void record(Long policyId, Evaluation e, String decidedBy, String note) {
        jdbc.update("INSERT INTO uw_decision (policy_id, total_points, outcome, findings, decided_by, note) VALUES (?,?,?,?,?,?)",
                policyId, e.totalPoints(), e.outcome(), String.join("\n", e.findings()), decidedBy, note);
    }

    public boolean hasAuthority(String username, BigDecimal sumInsured) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM uw_reviewer r JOIN user_account u ON u.user_id=r.user_id
                JOIN uw_authority a ON a.level_no=r.level_no WHERE u.username=? AND a.max_sum_insured>=?""",
                Integer.class, username, sumInsured);
        return n != null && n > 0;
    }
}
