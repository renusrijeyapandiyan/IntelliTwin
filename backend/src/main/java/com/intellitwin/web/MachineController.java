package com.intellitwin.web;

import com.intellitwin.core.Sensors;
import com.intellitwin.model.Docs;
import com.intellitwin.service.AuditService;
import com.intellitwin.service.IngestService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/machines")
public class MachineController {
    public record CreateMachine(@NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{1,32}$") String machineId,
                                @NotBlank @Size(max = 100) String name,
                                @NotBlank @Size(max = 40) String type,
                                @Size(max = 100) String location,
                                @NotNull Map<String, Double> baselineMeans,
                                Map<String, Double> baselineStd) {}

    private final MongoTemplate mongo;
    private final AuditService audit;
    private final IngestService ingest;

    public MachineController(MongoTemplate mongo, AuditService audit, IngestService ingest) {
        this.mongo = mongo;
        this.audit = audit;
        this.ingest = ingest;
    }

    @GetMapping
    public List<Docs.Machine> list() {
        return mongo.find(new Query().with(Sort.by("machineId")), Docs.Machine.class, Docs.MACHINES);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Docs.Machine create(@Valid @RequestBody CreateMachine req) {
        if (mongo.findById(req.machineId(), Docs.Machine.class, Docs.MACHINES) != null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "machine already exists");
        Docs.Machine m = new Docs.Machine();
        m.id = req.machineId(); m.machineId = req.machineId(); m.name = req.name(); m.type = req.type();
        m.location = req.location(); m.createdAt = Instant.now();
        for (String s : Sensors.NAMES) {
            Double mean = req.baselineMeans().get(s);
            if (mean == null || !(mean > 0) || Double.isInfinite(mean))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "baselineMeans." + s + " must be a positive number");
            Map<String, Double> b = new LinkedHashMap<>();
            b.put("mean", mean);
            Double sd = req.baselineStd() == null ? null : req.baselineStd().get(s);
            b.put("std", sd == null ? mean * 0.01 : sd);
            m.baselines.put(s, b);
        }
        mongo.insert(m, Docs.MACHINES);
        ingest.invalidateMachine(m.machineId);
        audit.log("MACHINE_CREATED", m.machineId, m.name);
        return m;
    }
}
