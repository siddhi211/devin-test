package com.legacyins.insurance.workflow;

import com.legacyins.insurance.common.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/workflow")
@RequiredArgsConstructor
public class WorkflowController {
    private final WorkflowService service;

    public record DecisionRequest(String decision, String note) {
    }

    public record DelegationRequest(String toUsername, LocalDate startDt, LocalDate endDt) {
    }

    private WorkflowService.Actor actor() {
        return new WorkflowService.Actor(SecurityUtils.currentUsername(), SecurityUtils.currentRoles());
    }

    @GetMapping("/inbox")
    public List<Map<String, Object>> inbox(@RequestParam(defaultValue = "false") boolean all) {
        return service.inbox(actor(), all);
    }

    @PostMapping("/tasks/{id}/claim")
    public Map<String, Object> claim(@PathVariable Long id) {
        return service.claim(id, actor());
    }

    @PostMapping("/tasks/{id}/release")
    public Map<String, Object> release(@PathVariable Long id) {
        return service.release(id, actor());
    }

    @PostMapping("/tasks/{id}/decide")
    public Map<String, Object> decide(@PathVariable Long id, @RequestBody DecisionRequest r) {
        return service.decide(id, actor(), r.decision(), r.note());
    }

    @GetMapping("/instances")
    public List<Map<String, Object>> instances(@RequestParam(required = false) String status,
                                               @RequestParam(defaultValue = "200") int limit) {
        return service.instances(status, Math.min(limit, 1000));
    }

    @GetMapping("/instances/{id}")
    public Map<String, Object> instance(@PathVariable Long id) {
        return service.instanceDetail(id);
    }

    @GetMapping("/subject/{type}/{id}")
    public List<Map<String, Object>> forSubject(@PathVariable String type, @PathVariable Long id) {
        return service.instancesForSubject(type, id);
    }

    @GetMapping("/definitions")
    public List<Map<String, Object>> definitions() {
        return service.definitions();
    }

    @GetMapping("/delegations")
    public List<Map<String, Object>> delegations() {
        return service.delegations(SecurityUtils.currentUsername());
    }

    @PostMapping("/delegations")
    public Map<String, Object> delegate(@RequestBody DelegationRequest r) {
        return service.delegate(SecurityUtils.currentUsername(), r.toUsername(), r.startDt(), r.endDt());
    }

    @DeleteMapping("/delegations/{id}")
    public Map<String, String> deleteDelegation(@PathVariable Long id) {
        service.deleteDelegation(id, SecurityUtils.currentUsername());
        return Map.of("status", "OK");
    }
}
