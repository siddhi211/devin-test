package com.legacyins.insurance.reporting;

import com.legacyins.insurance.common.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ReportController {
    private final ReportService reports;
    private final ReconciliationService recon;

    public record FilterRequest(String reportCode, String filterName, String params) {
    }

    public record ResolveRequest(String note) {
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return reports.dashboard();
    }

    @GetMapping("/reports")
    public List<Map<String, Object>> catalogue() {
        return reports.catalogue(SecurityUtils.currentRoles());
    }

    @GetMapping("/reports/{code}")
    public ResponseEntity<?> run(@PathVariable String code, @RequestParam Map<String, String> params,
                                 @RequestParam(defaultValue = "json") String format) {
        Map<String, String> p = new HashMap<>(params);
        p.remove("format");
        Map<String, Object> result = reports.run(code, p, format, SecurityUtils.currentUsername(), SecurityUtils.currentRoles());
        if ("csv".equalsIgnoreCase(format)) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + code.toLowerCase() + ".csv\"")
                    .contentType(MediaType.parseMediaType("text/csv"))
                    .body(ReportService.toCsv(result));
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/reports-history")
    public List<Map<String, Object>> history(@RequestParam(defaultValue = "100") int limit) {
        return reports.history(limit);
    }

    @GetMapping("/report-filters")
    public List<Map<String, Object>> filters() {
        return reports.filters(SecurityUtils.currentUsername());
    }

    @PostMapping("/report-filters")
    public Map<String, Object> saveFilter(@RequestBody FilterRequest r) {
        return reports.saveFilter(SecurityUtils.currentUsername(), r.reportCode(), r.filterName(), r.params());
    }

    @DeleteMapping("/report-filters/{id}")
    public void deleteFilter(@PathVariable Long id) {
        reports.deleteFilter(SecurityUtils.currentUsername(), id);
    }

    @PostMapping("/reports-refresh")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Map<String, Integer> refresh() {
        return reports.refreshSummaries(SecurityUtils.currentUsername());
    }

    @GetMapping("/reconciliation")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public List<Map<String, Object>> discrepancies(@RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String checkCode) {
        return recon.list(status, checkCode);
    }

    @GetMapping("/reconciliation/checks")
    public Set<String> checks() {
        return recon.checks();
    }

    @PostMapping("/reconciliation/run")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Map<String, Integer> runRecon() {
        return recon.run(SecurityUtils.currentUsername());
    }

    @PostMapping("/reconciliation/{id}/resolve")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Map<String, Object> resolve(@PathVariable Long id, @RequestBody ResolveRequest r) {
        return recon.resolve(id, r.note(), SecurityUtils.currentUsername());
    }
}
