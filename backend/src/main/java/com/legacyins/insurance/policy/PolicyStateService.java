package com.legacyins.insurance.policy;

import com.legacyins.insurance.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/** Single owner of policy status transitions: enforces the state machine and records history. */
@Service
@RequiredArgsConstructor
public class PolicyStateService {
    public static final Map<String, Set<String>> TRANSITIONS = Map.ofEntries(
            Map.entry("DRAFT", Set.of("SUBMITTED", "WITHDRAWN")),
            Map.entry("SUBMITTED", Set.of("PENDING_UW", "UW_APPROVED", "DECLINED", "DRAFT", "WITHDRAWN")),
            Map.entry("PENDING_UW", Set.of("UW_APPROVED", "DECLINED", "SUBMITTED", "WITHDRAWN")),
            Map.entry("UW_APPROVED", Set.of("ACTIVE", "DECLINED", "WITHDRAWN")),
            Map.entry("ACTIVE", Set.of("GRACE", "LAPSED", "CANCELLED", "EXPIRED")),
            Map.entry("GRACE", Set.of("ACTIVE", "LAPSED", "CANCELLED")),
            Map.entry("LAPSED", Set.of("ACTIVE", "CANCELLED")),
            Map.entry("CANCELLED", Set.of("ACTIVE")),
            Map.entry("DECLINED", Set.of()),
            Map.entry("WITHDRAWN", Set.of()),
            Map.entry("EXPIRED", Set.of()));

    private final PolicyRepository policies;
    private final JdbcTemplate jdbc;

    public Policy get(Long id) {
        return policies.findById(id).orElseThrow(() -> ApiException.notFound("Policy", id));
    }

    public Policy transition(Policy p, String to, String reason, String actor) {
        String from = p.getStatus();
        if (!TRANSITIONS.getOrDefault(from, Set.of()).contains(to)) {
            throw ApiException.rule("Policy " + p.getPolicyNo() + " cannot move from " + from + " to " + to);
        }
        p.setStatus(to);
        p.setUpdatedDt(LocalDateTime.now());
        policies.saveAndFlush(p);
        jdbc.update("INSERT INTO policy_status_history (policy_id, from_status, to_status, reason, actor) VALUES (?,?,?,?,?)",
                p.getId(), from, to, reason, actor);
        return p;
    }

    public void addVersion(Policy p, String changeType, String note, String actor) {
        jdbc.update("""
                INSERT INTO policy_version (policy_id, version_no, effective_dt, sum_insured, annual_premium, change_type, change_note, created_by)
                VALUES (?,?,?,?,?,?,?,?)""",
                p.getId(), p.getVersionNo(), java.time.LocalDate.now().isBefore(p.getStartDt()) ? p.getStartDt() : java.time.LocalDate.now(),
                p.getSumInsured(), p.getAnnualPremium(), changeType, note, actor);
    }

    public Map<String, Object> customerContact(Long customerId) {
        return jdbc.queryForMap("""
                SELECT customer_id, email, COALESCE(company_name, TRIM(COALESCE(first_name,'') || ' ' || COALESCE(last_name,''))) AS name
                FROM customer WHERE customer_id=?""", customerId);
    }
}
