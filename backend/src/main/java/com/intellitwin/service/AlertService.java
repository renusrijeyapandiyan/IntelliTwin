package com.intellitwin.service;

import com.intellitwin.model.Docs;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AlertService {
    private final MongoTemplate mongo;
    private final AuditService audit;

    public AlertService(MongoTemplate mongo, AuditService audit) {
        this.mongo = mongo;
        this.audit = audit;
    }

    public Docs.Alert raiseRisk(String machineId, String recommendation, double risk, List<String> reasons, boolean lowConf) {
        Docs.Alert a = new Docs.Alert();
        a.machineId = machineId; a.type = "RISK"; a.recommendation = recommendation; a.riskScore = risk;
        a.reasons = reasons; a.lowConfidence = lowConf; a.createdAt = Instant.now();
        a.message = recommendation.replace('_', ' ').toLowerCase() + (lowConf ? " (low confidence: human review needed)" : "")
                + ": " + String.join("; ", reasons);
        return mongo.insert(a, Docs.ALERTS);
    }

    public Docs.Alert raiseStale(String machineId, int seconds) {
        Docs.Alert a = new Docs.Alert();
        a.machineId = machineId; a.type = "STALE"; a.createdAt = Instant.now();
        a.message = "digital twin is STALE: no sensor data for more than " + seconds + " s";
        return mongo.insert(a, Docs.ALERTS);
    }

    public List<Docs.Alert> list(String status, String machineId, int limit) {
        Criteria c = new Criteria();
        if (status != null && !status.isBlank()) c = c.and("status").is(status);
        if (machineId != null && !machineId.isBlank()) c = c.and("machineId").is(machineId);
        return mongo.find(new Query(c).with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(Math.min(Math.max(limit, 1), 500)),
                Docs.Alert.class, Docs.ALERTS);
    }

    public Docs.Alert acknowledge(String id) {
        Docs.Alert a = mongo.findById(id, Docs.Alert.class, Docs.ALERTS);
        if (a == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "alert not found");
        if (!"ACKNOWLEDGED".equals(a.status)) {
            a.status = "ACKNOWLEDGED"; a.ackBy = AuditService.currentUser(); a.ackAt = Instant.now();
            mongo.save(a, Docs.ALERTS);
            audit.log("ALERT_ACKNOWLEDGED", a.machineId, "alert " + id + " (" + a.type + ")");
        }
        return a;
    }
}
