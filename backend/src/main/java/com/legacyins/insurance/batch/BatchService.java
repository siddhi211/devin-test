package com.legacyins.insurance.batch;

import com.legacyins.insurance.auth.AuditService;
import com.legacyins.insurance.billing.BillingService;
import com.legacyins.insurance.claims.ClaimService;
import com.legacyins.insurance.common.ApiException;
import com.legacyins.insurance.lifecycle.LifecycleService;
import com.legacyins.insurance.notification.NotificationService;
import com.legacyins.insurance.reporting.ReconciliationService;
import com.legacyins.insurance.reporting.ReportService;
import com.legacyins.insurance.workflow.WorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;

/** Batch job runner with DB lease locks, run history and item-level error capture. */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchService {
    private final JdbcTemplate jdbc;
    private final BillingService billing;
    private final LifecycleService lifecycle;
    private final ClaimService claims;
    private final WorkflowService workflow;
    private final NotificationService notifications;
    private final ReconciliationService recon;
    private final ReportService reports;
    private final AuditService audit;
    private final String ownerId = UUID.randomUUID().toString().substring(0, 8);

    private Map<String, Function<LocalDate, Map<String, ?>>> jobs() {
        Map<String, Function<LocalDate, Map<String, ?>>> m = new LinkedHashMap<>();
        m.put("EXPIRY", lifecycle::expiry);
        m.put("OVERDUE", d -> Map.of("markedOverdue", billing.markOverdue(d)));
        m.put("BILLING_LATE_FEE", d -> Map.of("lateFeesApplied", billing.applyLateFees(d)));
        m.put("BILLING_LAPSE", billing::graceAndLapse);
        m.put("BILLING_RETRY", d -> billing.processRetries());
        m.put("RENEWAL_OFFERS", lifecycle::generateOffers);
        m.put("CLAIM_SLA_SWEEP", d -> Map.of("escalated", claims.slaSweep(d)));
        m.put("WORKFLOW_SLA", d -> Map.of("breached", workflow.slaSweep()));
        m.put("RECONCILIATION", d -> recon.run("BATCH"));
        m.put("REPORT_REFRESH", d -> reports.refreshSummaries("BATCH"));
        m.put("OUTBOX_FLUSH", d -> notifications.flush(500));
        m.put("DATA_PURGE", d -> Map.of(
                "sessionLogs", jdbc.update("DELETE FROM user_session_log WHERE login_dt < now() - interval '180 days'"),
                "notifications", jdbc.update("DELETE FROM notification_outbox WHERE status='SENT' AND sent_dt < now() - interval '90 days'")));
        return m;
    }

    public List<Map<String, Object>> definitions() {
        return jdbc.queryForList("""
                SELECT d.*, l.owner_id AS lock_owner, l.expires_dt AS lock_expires,
                  (SELECT row_to_json(r) FROM (SELECT run_id, status, started_dt, ended_dt, items_processed, items_failed, message
                     FROM batch_job_run WHERE job_name=d.job_name ORDER BY run_id DESC LIMIT 1) r)::text AS last_run
                FROM batch_job_def d LEFT JOIN batch_job_lock l ON l.job_name=d.job_name ORDER BY d.run_order""");
    }

    public List<Map<String, Object>> runs(String jobName, int limit) {
        if (jobName == null || jobName.isBlank()) {
            return jdbc.queryForList("SELECT * FROM batch_job_run ORDER BY run_id DESC LIMIT ?", Math.min(limit, 500));
        }
        return jdbc.queryForList("SELECT * FROM batch_job_run WHERE job_name=? ORDER BY run_id DESC LIMIT ?", jobName, Math.min(limit, 500));
    }

    public List<Map<String, Object>> errors(Long runId) {
        return jdbc.queryForList("SELECT * FROM batch_item_error WHERE run_id=? ORDER BY error_id", runId);
    }

    public Map<String, Object> setEnabled(String jobName, boolean enabled, String actor) {
        if (jdbc.update("UPDATE batch_job_def SET enabled=?, updated_dt=now(), updated_by=? WHERE job_name=?", enabled ? "Y" : "N", actor, jobName) == 0) {
            throw ApiException.notFound("Batch job", jobName);
        }
        audit.log(actor, "BATCH_" + (enabled ? "ENABLE" : "DISABLE"), "BATCH_JOB", null, jobName);
        return jdbc.queryForMap("SELECT * FROM batch_job_def WHERE job_name=?", jobName);
    }

    private boolean acquire(String job, int maxMinutes) {
        int n = jdbc.update("""
                INSERT INTO batch_job_lock (job_name, owner_id, acquired_dt, expires_dt) VALUES (?,?,now(), now() + make_interval(mins => ?))
                ON CONFLICT (job_name) DO UPDATE SET owner_id=EXCLUDED.owner_id, acquired_dt=now(), expires_dt=EXCLUDED.expires_dt
                WHERE batch_job_lock.expires_dt < now()""", job, ownerId, maxMinutes);
        return n > 0;
    }

    private void release(String job) {
        jdbc.update("DELETE FROM batch_job_lock WHERE job_name=? AND owner_id=?", job, ownerId);
    }

    public Map<String, Object> run(String jobName, LocalDate asOf, String triggeredBy) {
        Map<String, Object> def = jdbc.queryForList("SELECT * FROM batch_job_def WHERE job_name=?", jobName).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Batch job", jobName));
        Function<LocalDate, Map<String, ?>> fn = jobs().get(jobName);
        if (fn == null) {
            throw ApiException.rule("No implementation registered for " + jobName);
        }
        LocalDate d = asOf == null ? LocalDate.now() : asOf;
        if (!acquire(jobName, ((Number) def.get("max_runtime_min")).intValue())) {
            throw ApiException.rule("Job " + jobName + " is already running");
        }
        Long runId = jdbc.queryForObject("""
                INSERT INTO batch_job_run (job_name, status, triggered_by, as_of_dt, started_dt) VALUES (?,'RUNNING',?,?,now()) RETURNING run_id""",
                Long.class, jobName, triggeredBy, d);
        String status;
        String message;
        int processed = 0, failed = 0;
        try {
            Map<String, ?> result = fn.apply(d);
            for (Map.Entry<String, ?> e : result.entrySet()) {
                if (e.getValue() instanceof Number n) {
                    if (e.getKey().toLowerCase().contains("fail") || e.getKey().toLowerCase().contains("dead")) {
                        failed += n.intValue();
                    } else {
                        processed += n.intValue();
                    }
                }
            }
            status = failed > 0 ? "PARTIAL" : "SUCCESS";
            message = result.toString();
        } catch (RuntimeException e) {
            log.error("Batch job {} failed", jobName, e);
            status = "FAILED";
            message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            jdbc.update("INSERT INTO batch_item_error (run_id, item_key, error_text) VALUES (?,?,?)", runId, jobName,
                    message.length() > 1000 ? message.substring(0, 1000) : message);
        } finally {
            release(jobName);
        }
        jdbc.update("UPDATE batch_job_run SET status=?, ended_dt=now(), items_processed=?, items_failed=?, message=? WHERE run_id=?",
                status, processed, failed, message.length() > 1000 ? message.substring(0, 1000) : message, runId);
        if (!"SCHEDULER".equals(triggeredBy)) {
            audit.log(triggeredBy, "BATCH_RUN", "BATCH_JOB", runId, jobName + " " + status);
        }
        return jdbc.queryForMap("SELECT * FROM batch_job_run WHERE run_id=?", runId);
    }

    public List<Map<String, Object>> runDaily(LocalDate asOf, String triggeredBy) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (String job : jdbc.queryForList("SELECT job_name FROM batch_job_def WHERE enabled='Y' AND schedule_type='DAILY' ORDER BY run_order", String.class)) {
            try {
                results.add(run(job, asOf, triggeredBy));
            } catch (ApiException e) {
                results.add(Map.of("job_name", job, "status", "SKIPPED", "message", e.getMessage()));
            }
        }
        return results;
    }
}
