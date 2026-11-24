package com.intellitwin.web;

import com.intellitwin.core.RawReading;
import com.intellitwin.model.Docs;
import com.intellitwin.service.IngestService;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sensors")
public class SensorController {
    private final IngestService ingest;
    private final MongoTemplate mongo;

    public SensorController(IngestService ingest, MongoTemplate mongo) {
        this.ingest = ingest;
        this.mongo = mongo;
    }

    /** Machine-to-machine endpoint (X-API-Key). Validation errors -> 400, unknown machine -> 404. */
    @PostMapping("/ingest")
    public IngestService.Result ingest(@RequestBody RawReading reading) {
        return ingest.ingest(reading);
    }

    /** Recent readings, oldest first. */
    @GetMapping("/{machineId}")
    public List<Docs.SensorReading> recent(@PathVariable String machineId,
                                           @RequestParam(defaultValue = "300") int limit,
                                           @RequestParam(required = false) Instant since) {
        Criteria c = Criteria.where("machineId").is(machineId);
        if (since != null) c = c.and("ts").gt(since);
        List<Docs.SensorReading> rows = mongo.find(new Query(c).with(Sort.by(Sort.Direction.DESC, "ts"))
                .limit(Math.min(Math.max(limit, 1), 2000)), Docs.SensorReading.class, Docs.READINGS);
        Collections.reverse(rows);
        return rows;
    }
}
