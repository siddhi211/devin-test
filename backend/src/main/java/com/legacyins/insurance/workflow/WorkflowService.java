package com.legacyins.insurance.workflow;

import com.legacyins.insurance.auth.AuditService;
import com.legacyins.insurance.common.ApiException;
import com.legacyins.insurance.common.ErrorCode;
import com.legacyins.insurance.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** Generic multi-step approval engine with four-eyes control, task claiming, delegation and SLA tracking. */
@Service
@RequiredArgsConstructor
public class WorkflowService {
    private final JdbcTemplate jdbc;
    private final NotificationService notifications;
    private final AuditService audit;
    private final ObjectProvider<WorkflowSubjectHandler> handlers;

    public record Actor(String username, Set<String> roles) {
    }

    public Optional<Map<String, Object>> openInstance(String subjectType, Long subjectId) {
        return jdbc.queryForList("SELECT * FROM workflow_instance WHERE subject_type=? AND subject_id=? AND active_flag='Y'",
                subjectType, subjectId).stream().findFirst();
    }

    @Transactional
    public long start(String defCode, Long subjectId, String subjectRef, BigDecimal amount, String requestedBy) {
        Map<String, Object> def = jdbc.queryForList("SELECT * FROM workflow_definition WHERE def_code=? AND status='A'", defCode)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Workflow definition", defCode));
        String subjectType = (String) def.get("subject_type");
        if (openInstance(subjectType, subjectId).isPresent()) {
            throw ApiException.duplicate("An approval is already in progress for " + subjectType + " " + subjectId);
        }
        BigDecimal amt = amount == null ? BigDecimal.ZERO : amount;
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO workflow_instance (def_code, subject_type, subject_id, subject_ref, amount, requested_by, current_step)
                    VALUES (?,?,?,?,?,?,0)""", new String[]{"instance_id"});
            ps.setString(1, defCode);
            ps.setString(2, subjectType);
            ps.setLong(3, subjectId);
            ps.setString(4, subjectRef);
            ps.setBigDecimal(5, amt);
            ps.setString(6, requestedBy);
            return ps;
        }, kh);
        long instanceId = kh.getKey().longValue();
        history(instanceId, "STARTED", requestedBy, "amount=" + amt);
        if (!advance(instanceId, defCode, 0, amt, ((Number) def.get("sla_hours")).intValue(), subjectRef)) {
            complete(instanceId, subjectType, subjectId, "APPROVED", "SYSTEM", "No approval steps applicable");
        }
        return instanceId;
    }

    private boolean advance(long instanceId, String defCode, int afterStep, BigDecimal amount, int slaHours, String subjectRef) {
        List<Map<String, Object>> steps = jdbc.queryForList(
                "SELECT * FROM workflow_step WHERE def_code=? AND step_no>? AND min_amount<=? ORDER BY step_no LIMIT 1",
                defCode, afterStep, amount);
        if (steps.isEmpty()) {
            return false;
        }
        Map<String, Object> step = steps.get(0);
        int stepNo = ((Number) step.get("step_no")).intValue();
        String role = (String) step.get("role_code");
        jdbc.update("""
                INSERT INTO workflow_task (instance_id, step_no, step_name, role_code, sla_due_dt) VALUES (?,?,?,?,?)""",
                instanceId, stepNo, step.get("step_name"), role, LocalDateTime.now().plusHours(slaHours));
        jdbc.update("UPDATE workflow_instance SET current_step=? WHERE instance_id=?", stepNo, instanceId);
        history(instanceId, "TASK_CREATED", "SYSTEM", "step " + stepNo + " -> " + role);
        notifications.enqueue("TASK_ASSIGNED", "role:" + role,
                Map.of("defCode", defCode, "role", role, "subjectRef", subjectRef == null ? "" : subjectRef),
                "WORKFLOW", instanceId, "WF-" + instanceId + "-" + stepNo);
        return true;
    }

    private void complete(long instanceId, String subjectType, Long subjectId, String status, String actor, String note) {
        jdbc.update("UPDATE workflow_instance SET status=?, active_flag='N', completed_dt=now() WHERE instance_id=?", status, instanceId);
        jdbc.update("UPDATE workflow_task SET status='CANCELLED' WHERE instance_id=? AND status IN ('OPEN','CLAIMED')", instanceId);
        history(instanceId, status, actor, note);
        handlers.orderedStream().filter(h -> h.subjectType().equals(subjectType)).forEach(h -> {
            if ("APPROVED".equals(status)) {
                h.onApproved(subjectId, actor, note);
            } else if ("REJECTED".equals(status)) {
                h.onRejected(subjectId, actor, note);
            }
        });
    }

    @Transactional
    public void cancel(String subjectType, Long subjectId, String actor, String reason) {
        openInstance(subjectType, subjectId).ifPresent(i -> {
            long id = ((Number) i.get("instance_id")).longValue();
            complete(id, subjectType, subjectId, "CANCELLED", actor, reason);
        });
    }

    /** Roles a user can act with: own roles plus roles of users who delegated to them for today. */
    public Map<String, String> effectiveRoles(Actor actor) {
        Map<String, String> roles = new LinkedHashMap<>();
        actor.roles().forEach(r -> roles.put(r, null));
        jdbc.queryForList("""
                SELECT d.from_username, r.role_name FROM workflow_delegation d
                JOIN user_account u ON u.username = d.from_username JOIN user_role r ON r.role_id = u.role_id
                WHERE d.to_username=? AND ? BETWEEN d.start_dt AND d.end_dt""", actor.username(), LocalDate.now())
                .forEach(r -> roles.putIfAbsent((String) r.get("role_name"), (String) r.get("from_username")));
        return roles;
    }

    public List<Map<String, Object>> inbox(Actor actor, boolean includeAll) {
        Map<String, String> roles = effectiveRoles(actor);
        String base = """
                SELECT t.*, i.def_code, i.subject_type, i.subject_id, i.subject_ref, i.amount, i.requested_by, i.created_dt AS requested_dt
                FROM workflow_task t JOIN workflow_instance i ON i.instance_id = t.instance_id
                WHERE t.status IN ('OPEN','CLAIMED')""";
        if (includeAll && roles.containsKey("ADMIN")) {
            return jdbc.queryForList(base + " ORDER BY t.sla_due_dt");
        }
        List<String> roleList = new ArrayList<>(roles.keySet());
        if (roleList.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(roleList.size(), "?"));
        List<Object> args = new ArrayList<>(roleList);
        args.add(actor.username());
        return jdbc.queryForList(base + " AND (t.role_code IN (" + placeholders + ") OR t.assigned_user=?) ORDER BY t.sla_due_dt",
                args.toArray());
    }

    private Map<String, Object> task(Long taskId) {
        return jdbc.queryForList("""
                SELECT t.*, i.def_code, i.subject_type, i.subject_id, i.requested_by, i.amount, i.subject_ref
                FROM workflow_task t JOIN workflow_instance i ON i.instance_id=t.instance_id WHERE t.task_id=?""", taskId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Task", taskId));
    }

    private String eligibleVia(Map<String, Object> task, Actor actor) {
        Map<String, String> roles = effectiveRoles(actor);
        String role = (String) task.get("role_code");
        if (roles.containsKey(role)) {
            return roles.get(role) == null ? "" : roles.get(role);
        }
        if (roles.containsKey("ADMIN")) {
            return "";
        }
        throw new ApiException(ErrorCode.NOT_AUTHORIZED, "Task requires role " + role);
    }

    @Transactional
    public Map<String, Object> claim(Long taskId, Actor actor) {
        Map<String, Object> t = task(taskId);
        eligibleVia(t, actor);
        if (!"OPEN".equals(t.get("status"))) {
            throw ApiException.rule("Task is not open (status " + t.get("status") + ")");
        }
        jdbc.update("UPDATE workflow_task SET status='CLAIMED', assigned_user=? WHERE task_id=? AND status='OPEN'", actor.username(), taskId);
        history(((Number) t.get("instance_id")).longValue(), "CLAIMED", actor.username(), "task " + taskId);
        return task(taskId);
    }

    @Transactional
    public Map<String, Object> release(Long taskId, Actor actor) {
        Map<String, Object> t = task(taskId);
        if (!"CLAIMED".equals(t.get("status"))) {
            throw ApiException.rule("Task is not claimed");
        }
        if (!actor.username().equals(t.get("assigned_user")) && !actor.roles().contains("ADMIN")) {
            throw new ApiException(ErrorCode.NOT_AUTHORIZED, "Task is claimed by " + t.get("assigned_user"));
        }
        jdbc.update("UPDATE workflow_task SET status='OPEN', assigned_user=NULL WHERE task_id=?", taskId);
        history(((Number) t.get("instance_id")).longValue(), "RELEASED", actor.username(), "task " + taskId);
        return task(taskId);
    }

    @Transactional
    public Map<String, Object> decide(Long taskId, Actor actor, String decision, String note) {
        if (!List.of("APPROVE", "REJECT").contains(decision)) {
            throw ApiException.validation("Decision must be APPROVE or REJECT");
        }
        Map<String, Object> t = task(taskId);
        String actingFor = eligibleVia(t, actor);
        String status = (String) t.get("status");
        if (!List.of("OPEN", "CLAIMED").contains(status)) {
            throw ApiException.rule("Task already decided or cancelled");
        }
        if ("CLAIMED".equals(status) && !actor.username().equals(t.get("assigned_user"))) {
            throw ApiException.rule("Task is claimed by " + t.get("assigned_user"));
        }
        if ("REJECT".equals(decision) && (note == null || note.isBlank())) {
            throw ApiException.validation("A note is required when rejecting");
        }
        long instanceId = ((Number) t.get("instance_id")).longValue();
        Map<String, Object> def = jdbc.queryForMap("SELECT * FROM workflow_definition WHERE def_code=?", t.get("def_code"));
        String fourEyes = (String) def.get("four_eyes");
        if (!"N".equals(fourEyes) && actor.username().equals(t.get("requested_by"))) {
            throw ApiException.rule("Four-eyes control: the requester cannot approve their own request");
        }
        if ("S".equals(fourEyes)) {
            Integer prior = jdbc.queryForObject(
                    "SELECT count(*) FROM workflow_task WHERE instance_id=? AND decided_by=? AND task_id<>?",
                    Integer.class, instanceId, actor.username(), taskId);
            if (prior != null && prior > 0) {
                throw ApiException.rule("Four-eyes control (strict): each step must be decided by a different user");
            }
        }
        jdbc.update("""
                UPDATE workflow_task SET status='DONE', decision=?, decision_note=?, decided_by=?, acting_for=?, decided_dt=now(),
                  assigned_user=COALESCE(assigned_user, ?) WHERE task_id=?""",
                decision, note, actor.username(), actingFor.isEmpty() ? null : actingFor, actor.username(), taskId);
        history(instanceId, decision, actor.username(), (actingFor.isEmpty() ? "" : "on behalf of " + actingFor + ": ") + (note == null ? "" : note));
        audit.log(actor.username(), "WF_" + decision, (String) t.get("subject_type"), ((Number) t.get("subject_id")).longValue(),
                "task " + taskId + " " + t.get("def_code"));
        String subjectType = (String) t.get("subject_type");
        Long subjectId = ((Number) t.get("subject_id")).longValue();
        if ("REJECT".equals(decision)) {
            complete(instanceId, subjectType, subjectId, "REJECTED", actor.username(), note);
        } else if (!advance(instanceId, (String) t.get("def_code"), ((Number) t.get("step_no")).intValue(),
                (BigDecimal) t.get("amount"), ((Number) def.get("sla_hours")).intValue(), (String) t.get("subject_ref"))) {
            complete(instanceId, subjectType, subjectId, "APPROVED", actor.username(), note);
        }
        return instanceDetail(instanceId);
    }

    public Map<String, Object> instanceDetail(long instanceId) {
        Map<String, Object> inst = jdbc.queryForList("SELECT * FROM workflow_instance WHERE instance_id=?", instanceId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Workflow instance", instanceId));
        Map<String, Object> out = new LinkedHashMap<>(inst);
        out.put("tasks", jdbc.queryForList("SELECT * FROM workflow_task WHERE instance_id=? ORDER BY task_id", instanceId));
        out.put("history", jdbc.queryForList("SELECT * FROM workflow_history WHERE instance_id=? ORDER BY history_id", instanceId));
        return out;
    }

    public List<Map<String, Object>> instancesForSubject(String subjectType, Long subjectId) {
        return jdbc.queryForList("SELECT * FROM workflow_instance WHERE subject_type=? AND subject_id=? ORDER BY instance_id DESC",
                subjectType, subjectId).stream().map(i -> instanceDetail(((Number) i.get("instance_id")).longValue())).toList();
    }

    public List<Map<String, Object>> instances(String status, int limit) {
        if (status == null || status.isBlank()) {
            return jdbc.queryForList("SELECT * FROM workflow_instance ORDER BY instance_id DESC LIMIT ?", limit);
        }
        return jdbc.queryForList("SELECT * FROM workflow_instance WHERE status=? ORDER BY instance_id DESC LIMIT ?", status, limit);
    }

    public List<Map<String, Object>> definitions() {
        List<Map<String, Object>> defs = jdbc.queryForList("SELECT * FROM workflow_definition ORDER BY def_code");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> d : defs) {
            Map<String, Object> m = new LinkedHashMap<>(d);
            m.put("steps", jdbc.queryForList("SELECT * FROM workflow_step WHERE def_code=? ORDER BY step_no", d.get("def_code")));
            out.add(m);
        }
        return out;
    }

    @Transactional
    public Map<String, Object> delegate(String from, String to, LocalDate start, LocalDate end) {
        if (from.equals(to)) {
            throw ApiException.validation("Cannot delegate to yourself");
        }
        if (start == null || end == null || end.isBefore(start)) {
            throw ApiException.validation("Valid start and end dates are required");
        }
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM user_account WHERE username=? AND status='A'", Integer.class, to);
        if (exists == null || exists == 0) {
            throw ApiException.notFound("Active user", to);
        }
        jdbc.update("INSERT INTO workflow_delegation (from_username, to_username, start_dt, end_dt) VALUES (?,?,?,?)", from, to, start, end);
        audit.log(from, "WF_DELEGATE", "USER", null, to + " " + start + ".." + end);
        return Map.of("status", "OK");
    }

    public List<Map<String, Object>> delegations(String username) {
        return jdbc.queryForList("SELECT * FROM workflow_delegation WHERE from_username=? OR to_username=? ORDER BY delegation_id DESC",
                username, username);
    }

    @Transactional
    public void deleteDelegation(Long id, String username) {
        int n = jdbc.update("DELETE FROM workflow_delegation WHERE delegation_id=? AND from_username=?", id, username);
        if (n == 0) {
            throw ApiException.notFound("Delegation", id);
        }
    }

    @Transactional
    public int slaSweep() {
        List<Map<String, Object>> breached = jdbc.queryForList("""
                SELECT task_id, instance_id FROM workflow_task
                WHERE status IN ('OPEN','CLAIMED') AND sla_breached='N' AND sla_due_dt < now()""");
        for (Map<String, Object> b : breached) {
            jdbc.update("UPDATE workflow_task SET sla_breached='Y' WHERE task_id=?", b.get("task_id"));
            history(((Number) b.get("instance_id")).longValue(), "SLA_BREACH", "SYSTEM", "task " + b.get("task_id"));
        }
        return breached.size();
    }

    private void history(long instanceId, String event, String actor, String detail) {
        jdbc.update("INSERT INTO workflow_history (instance_id, event_type, actor, detail) VALUES (?,?,?,?)",
                instanceId, event, actor, detail == null ? null : (detail.length() > 500 ? detail.substring(0, 500) : detail));
    }
}
