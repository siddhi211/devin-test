package com.legacyins.insurance.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuditService {
    private final JdbcTemplate jdbc;

    public void log(String actor, String action, String entityType, Long entityId, String detail) {
        String d = detail == null ? null : (detail.length() > 1000 ? detail.substring(0, 1000) : detail);
        jdbc.update("INSERT INTO audit_log (actor, action, entity_type, entity_id, detail) VALUES (?,?,?,?,?)",
                actor, action, entityType, entityId, d);
    }

    public List<Map<String, Object>> recent(String entityType, Long entityId, int limit) {
        if (entityType != null && entityId != null) {
            return jdbc.queryForList("SELECT * FROM audit_log WHERE entity_type=? AND entity_id=? ORDER BY audit_id DESC LIMIT ?",
                    entityType, entityId, limit);
        }
        return jdbc.queryForList("SELECT * FROM audit_log ORDER BY audit_id DESC LIMIT ?", limit);
    }

    public void sessionLog(String username, String outcome, String ip) {
        jdbc.update("INSERT INTO user_session_log (username, outcome, ip_addr) VALUES (?,?,?)", username, outcome, ip);
    }
}
