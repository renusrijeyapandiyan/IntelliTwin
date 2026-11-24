package com.intellitwin.web;

import com.intellitwin.core.TwinState;
import com.intellitwin.model.Docs;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/twins")
public class TwinController {
    private final MongoTemplate mongo;

    public TwinController(MongoTemplate mongo) { this.mongo = mongo; }

    /** All twins without bulky history (overview page). */
    @GetMapping
    public List<TwinState> all() {
        List<TwinState> twins = mongo.findAll(TwinState.class, Docs.TWINS);
        twins.forEach(t -> { t.history = List.of(); t.features = new java.util.LinkedHashMap<>(); });
        return twins;
    }

    @GetMapping("/{machineId}")
    public TwinState one(@PathVariable String machineId) {
        TwinState t = mongo.findById(machineId, TwinState.class, Docs.TWINS);
        if (t == null) {
            t = new TwinState();
            t.id = machineId; t.machineId = machineId; t.syncStatus = "NO_DATA"; t.aiStatus = "WARMING_UP";
        }
        return t;
    }
}
