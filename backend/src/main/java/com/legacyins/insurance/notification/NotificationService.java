package com.legacyins.insurance.notification;

import com.legacyins.insurance.common.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Transactional outbox: notifications are written with the business change and delivered by OUTBOX_FLUSH. */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {
    private static final int[] BACKOFF_MINUTES = {15, 60, 240};

    private final JdbcTemplate jdbc;

    public void enqueue(String templateCode, String recipient, Map<String, ?> vars, String refType, Long refId, String dedupKey) {
        List<Map<String, Object>> tpl = jdbc.queryForList("SELECT * FROM notification_template WHERE template_code=?", templateCode);
        if (tpl.isEmpty()) {
            log.warn("Unknown notification template {}", templateCode);
            return;
        }
        Map<String, Object> t = tpl.get(0);
        String subject = render((String) t.get("subject_tpl"), vars);
        String body = render((String) t.get("body_tpl"), vars);
        jdbc.update("""
                INSERT INTO notification_outbox (template_code, channel, recipient_addr, subject, body, dedup_key, ref_type, ref_id, next_attempt_dt)
                VALUES (?,?,?,?,?,?,?,?,now()) ON CONFLICT (dedup_key) DO NOTHING""",
                templateCode, t.get("channel"), recipient == null ? "unknown@invalid" : recipient,
                trunc(subject, 200), trunc(body, 2000), dedupKey, refType, refId);
    }

    static String render(String tpl, Map<String, ?> vars) {
        if (tpl == null) {
            return "";
        }
        String out = tpl;
        for (Map.Entry<String, ?> e : vars.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue().toString());
        }
        return out;
    }

    private static String trunc(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    @Transactional
    public Map<String, Integer> flush(int limit) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT outbox_id, recipient_addr, attempts, max_attempts FROM notification_outbox
                WHERE status IN ('PENDING','FAILED') AND (next_attempt_dt IS NULL OR next_attempt_dt <= now())
                ORDER BY outbox_id LIMIT ? FOR UPDATE SKIP LOCKED""", limit);
        int sent = 0, failed = 0, dead = 0;
        for (Map<String, Object> r : rows) {
            long id = ((Number) r.get("outbox_id")).longValue();
            String recipient = (String) r.get("recipient_addr");
            int attempts = ((Number) r.get("attempts")).intValue() + 1;
            int max = ((Number) r.get("max_attempts")).intValue();
            boolean ok = recipient != null && !recipient.contains("fail") && !recipient.endsWith("@invalid");
            if (ok) {
                jdbc.update("UPDATE notification_outbox SET status='SENT', attempts=?, sent_dt=now(), last_error=NULL WHERE outbox_id=?", attempts, id);
                sent++;
            } else if (attempts >= max) {
                jdbc.update("UPDATE notification_outbox SET status='DEAD', attempts=?, last_error=? WHERE outbox_id=?",
                        attempts, "Delivery failed permanently", id);
                dead++;
            } else {
                int backoff = BACKOFF_MINUTES[Math.min(attempts - 1, BACKOFF_MINUTES.length - 1)];
                jdbc.update("UPDATE notification_outbox SET status='FAILED', attempts=?, last_error=?, next_attempt_dt=? WHERE outbox_id=?",
                        attempts, "Simulated gateway rejection", LocalDateTime.now().plusMinutes(backoff), id);
                failed++;
            }
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("processed", rows.size());
        result.put("sent", sent);
        result.put("failed", failed);
        result.put("dead", dead);
        return result;
    }

    public List<Map<String, Object>> list(String status, String refType, Long refId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM notification_outbox WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status=?");
            args.add(status);
        }
        if (refType != null && refId != null) {
            sql.append(" AND ref_type=? AND ref_id=?");
            args.add(refType);
            args.add(refId);
        }
        sql.append(" ORDER BY outbox_id DESC LIMIT ?");
        args.add(limit);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        jdbc.queryForList("SELECT status, count(*) AS cnt FROM notification_outbox GROUP BY status")
                .forEach(r -> m.put((String) r.get("status"), r.get("cnt")));
        return m;
    }

    @Transactional
    public void requeue(Long id) {
        int n = jdbc.update("UPDATE notification_outbox SET status='PENDING', attempts=0, next_attempt_dt=now() WHERE outbox_id=? AND status IN ('FAILED','DEAD')", id);
        if (n == 0) {
            throw ApiException.rule("Only FAILED or DEAD notifications can be requeued");
        }
    }

    public List<Map<String, Object>> templates() {
        return jdbc.queryForList("SELECT * FROM notification_template ORDER BY template_code");
    }
}
