package com.intellitwin.web;

import com.intellitwin.model.Docs;
import com.intellitwin.service.AlertService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {
    public record PatchAlert(@Pattern(regexp = "ACKNOWLEDGED") String status) {}

    private final AlertService alerts;

    public AlertController(AlertService alerts) { this.alerts = alerts; }

    @GetMapping
    public List<Docs.Alert> list(@RequestParam(required = false) String status, @RequestParam(required = false) String machineId,
                                 @RequestParam(defaultValue = "100") int limit) {
        return alerts.list(status, machineId, limit);
    }

    /** Operators, engineers and admins may acknowledge. */
    @PatchMapping("/{id}")
    public Docs.Alert patch(@PathVariable String id, @Valid @RequestBody PatchAlert body) {
        if (body.status() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status required");
        return alerts.acknowledge(id);
    }
}
