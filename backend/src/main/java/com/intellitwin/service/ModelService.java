package com.intellitwin.service;

import com.intellitwin.model.Docs;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Model governance. The AI service's registry is the source of truth for artifacts; this service mirrors it into
 * `model_versions` and records the human approval (who promoted what, when).
 */
@Service
public class ModelService {
    private final AiClient ai;
    private final MongoTemplate mongo;
    private final AuditService audit;

    public ModelService(AiClient ai, MongoTemplate mongo, AuditService audit) {
        this.ai = ai;
        this.mongo = mongo;
        this.audit = audit;
    }

    @SuppressWarnings("unchecked")
    public List<Docs.ModelVersion> list() {
        Map<String, Object> reg;
        try {
            reg = ai.models();
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI service unavailable");
        }
        List<Docs.ModelVersion> out = new ArrayList<>();
        for (Map<String, Object> row : (List<Map<String, Object>>) reg.get("versions")) {
            String v = (String) row.get("version");
            Docs.ModelVersion d = mongo.findById(v, Docs.ModelVersion.class, Docs.MODELS);
            if (d == null) { d = new Docs.ModelVersion(); d.id = v; d.modelId = v; d.version = v; }
            d.status = (String) row.get("status");
            d.createdAt = (String) row.get("createdAt");
            d.parent = (String) row.get("parent");
            d.promotedAt = row.get("promotedAt") == null ? d.promotedAt : (String) row.get("promotedAt");
            Map<String, Object> metrics = (Map<String, Object>) row.get("metrics");
            if (metrics != null) d.metrics = metrics;
            Map<String, Object> meta = (Map<String, Object>) row.get("meta");
            if (meta != null) {
                d.trainingDataSummary = new java.util.LinkedHashMap<>();
                for (String k : List.of("kind", "train_summary", "seed", "label_definition", "decision_threshold", "trainedAt", "drift_trigger"))
                    if (meta.containsKey(k)) d.trainingDataSummary.put(k, meta.get(k));
            }
            d.shadowEvaluation = (Map<String, Object>) row.get("shadowEvaluation");
            d.driftReport = (Map<String, Object>) row.get("driftReport");
            mongo.save(d, Docs.MODELS);
            out.add(d);
        }
        out.sort(Comparator.comparing((Docs.ModelVersion m) -> m.version).reversed());
        return out;
    }

    public Docs.ModelVersion promote(String id, String note) {
        Docs.ModelVersion target = list().stream().filter(m -> m.version.equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown model " + id));
        if ("ACTIVE".equals(target.status)) throw new ResponseStatusException(HttpStatus.CONFLICT, "model is already ACTIVE");
        if ("ROLLED_BACK".equals(target.status)) throw new ResponseStatusException(HttpStatus.CONFLICT, "a rolled-back model cannot be promoted again");
        try {
            ai.activate(id);
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI service refused or could not activate " + id);
        }
        String admin = AuditService.currentUser();
        target.approvedBy = admin;
        target.promotedAt = Instant.now().toString();
        target.note = note;
        target.status = "ACTIVE";
        mongo.save(target, Docs.MODELS);
        audit.log("MODEL_PROMOTED", id, "approved by " + admin + (note == null ? "" : ": " + note));
        return target;
    }

    public List<Docs.ModelVersion> rollback() {
        try {
            ai.rollback();
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "rollback not possible (no previous version?)");
        }
        audit.log("MODEL_ROLLBACK", "registry", "rolled back to previous version");
        return list();
    }
}
