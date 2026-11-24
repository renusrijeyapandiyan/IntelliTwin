package com.intellitwin.web;

import com.intellitwin.model.Docs;
import com.intellitwin.service.AuditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/maintenance")
public class MaintenanceController {
    public record CreateRecord(@NotBlank String machineId,
                               @NotBlank @Size(max = 200) String action,
                               @Size(max = 2000) String notes,
                               @NotBlank @Pattern(regexp = "FAULT_CONFIRMED|NO_FAULT_FOUND|PREVENTIVE|OTHER") String outcome,
                               Instant performedAt) {}

    private final MongoTemplate mongo;
    private final AuditService audit;

    public MaintenanceController(MongoTemplate mongo, AuditService audit) {
        this.mongo = mongo;
        this.audit = audit;
    }

    @GetMapping
    public List<Docs.MaintenanceRecord> list(@RequestParam(required = false) String machineId, @RequestParam(defaultValue = "200") int limit) {
        Query q = machineId == null || machineId.isBlank() ? new Query() : new Query(Criteria.where("machineId").is(machineId));
        return mongo.find(q.with(Sort.by(Sort.Direction.DESC, "performedAt")).limit(Math.min(Math.max(limit, 1), 500)),
                Docs.MaintenanceRecord.class, Docs.MAINTENANCE);
    }

    /** Maintenance feedback also feeds the self-learning loop (FAULT_CONFIRMED / NO_FAULT_FOUND = labels). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Docs.MaintenanceRecord create(@Valid @RequestBody CreateRecord req) {
        if (mongo.findById(req.machineId(), Docs.Machine.class, Docs.MACHINES) == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown machineId");
        Instant now = Instant.now();
        if (req.performedAt() != null && req.performedAt().isAfter(now.plusSeconds(60)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "performedAt cannot be in the future");
        Docs.MaintenanceRecord r = new Docs.MaintenanceRecord();
        r.machineId = req.machineId(); r.action = req.action(); r.notes = req.notes(); r.outcome = req.outcome();
        r.performedBy = AuditService.currentUser(); r.performedAt = req.performedAt() == null ? now : req.performedAt(); r.createdAt = now;
        mongo.insert(r, Docs.MAINTENANCE);
        audit.log("MAINTENANCE_RECORDED", r.machineId, r.action + " -> " + r.outcome);
        return r;
    }
}
