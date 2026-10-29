package com.legacyins.insurance.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {
    private final NotificationService service;

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String status,
                                          @RequestParam(required = false) String refType,
                                          @RequestParam(required = false) Long refId,
                                          @RequestParam(defaultValue = "200") int limit) {
        return service.list(status, refType, refId, Math.min(limit, 1000));
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return service.summary();
    }

    @GetMapping("/templates")
    public List<Map<String, Object>> templates() {
        return service.templates();
    }

    @PostMapping("/flush")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Map<String, Integer> flush() {
        return service.flush(500);
    }

    @PostMapping("/{id}/requeue")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, String> requeue(@PathVariable Long id) {
        service.requeue(id);
        return Map.of("status", "OK");
    }
}
