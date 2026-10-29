package com.legacyins.insurance.batch;

import com.legacyins.insurance.common.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/batch")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
public class BatchController {
    private final BatchService batch;

    public record EnableRequest(boolean enabled) {
    }

    @GetMapping("/jobs")
    public List<Map<String, Object>> jobs() {
        return batch.definitions();
    }

    @GetMapping("/runs")
    public List<Map<String, Object>> runs(@RequestParam(required = false) String job, @RequestParam(defaultValue = "100") int limit) {
        return batch.runs(job, limit);
    }

    @GetMapping("/runs/{runId}/errors")
    public List<Map<String, Object>> errors(@PathVariable Long runId) {
        return batch.errors(runId);
    }

    @PostMapping("/jobs/{job}/run")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> run(@PathVariable String job,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return batch.run(job, asOf, SecurityUtils.currentUsername());
    }

    @PostMapping("/run-daily")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> runDaily(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return batch.runDaily(asOf, SecurityUtils.currentUsername());
    }

    @PatchMapping("/jobs/{job}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> enable(@PathVariable String job, @RequestBody EnableRequest r) {
        return batch.setEnabled(job, r.enabled(), SecurityUtils.currentUsername());
    }
}
