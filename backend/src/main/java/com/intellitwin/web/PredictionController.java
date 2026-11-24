package com.intellitwin.web;

import com.intellitwin.model.Docs;
import java.util.Collections;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/predictions")
public class PredictionController {
    private final MongoTemplate mongo;

    public PredictionController(MongoTemplate mongo) { this.mongo = mongo; }

    /** Prediction audit log (model version + inference time per prediction), oldest first. */
    @GetMapping("/{machineId}")
    public List<Docs.PredictionRecord> list(@PathVariable String machineId, @RequestParam(defaultValue = "200") int limit) {
        Query q = new Query(Criteria.where("machineId").is(machineId)).with(Sort.by(Sort.Direction.DESC, "ts"))
                .limit(Math.min(Math.max(limit, 1), 2000));
        q.fields().exclude("features");
        List<Docs.PredictionRecord> rows = mongo.find(q, Docs.PredictionRecord.class, Docs.PREDICTIONS);
        Collections.reverse(rows);
        return rows;
    }
}
