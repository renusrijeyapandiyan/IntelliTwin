package com.intellitwin.service;

import com.intellitwin.config.AppProperties;
import com.intellitwin.core.DecisionEngine;
import com.intellitwin.core.FeatureExtractor;
import com.intellitwin.core.Quality;
import com.intellitwin.core.RawReading;
import com.intellitwin.core.Reading;
import com.intellitwin.core.Recommendation;
import com.intellitwin.core.SensorValidator;
import com.intellitwin.core.Sensors;
import com.intellitwin.core.TopFeature;
import com.intellitwin.core.TwinState;
import com.intellitwin.core.TwinUpdater;
import com.intellitwin.model.Docs;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Ingestion pipeline: validate -> dedup -> order check -> window/features -> AI -> decision -> twin update
 * -> alerts -> audit of predictions. Work for one machine is serialised by a per-machine lock.
 */
@Service
public class IngestService {
    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    /** In-memory working state per machine (rebuilt from MongoDB after a restart). */
    static final class Runtime {
        final Deque<Reading> window = new ArrayDeque<>();
        final Deque<Boolean> suspectRecent = new ArrayDeque<>();
        final List<Double> recentP = new ArrayList<>();
        Reading last;
        int runningStreak;
        Double prevRisk;
        Recommendation prevRec;
    }

    public record Result(String status, String quality, List<String> flags, String aiStatus, String recommendation) {}

    private final MongoTemplate mongo;
    private final AppProperties props;
    private final AiClient ai;
    private final AlertService alerts;
    private final AuditService audit;
    private final DecisionEngine engine;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, Runtime> runtimes = new ConcurrentHashMap<>();
    private final Map<String, Docs.Machine> machineCache = new ConcurrentHashMap<>();

    public IngestService(MongoTemplate mongo, AppProperties props, AiClient ai, AlertService alerts, AuditService audit) {
        this.mongo = mongo;
        this.props = props;
        this.ai = ai;
        this.alerts = alerts;
        this.audit = audit;
        this.engine = new DecisionEngine(props.getDecision());
    }

    public void invalidateMachine(String machineId) { machineCache.remove(machineId); }

    private Docs.Machine machine(String id) {
        return machineCache.computeIfAbsent(id, k -> {
            Docs.Machine m = mongo.findById(k, Docs.Machine.class, Docs.MACHINES);
            if (m == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown machineId " + k);
            return m;
        });
    }

    private Map<String, SensorValidator.Range> ranges(Docs.Machine m) {
        Map<String, SensorValidator.Range> r = new LinkedHashMap<>(SensorValidator.DEFAULT_RANGES);
        m.thresholds.forEach((s, mm) -> {
            if (mm != null && mm.get("min") != null && mm.get("max") != null) r.put(s, new SensorValidator.Range(mm.get("min"), mm.get("max")));
        });
        return r;
    }

    public Result ingest(RawReading raw) {
        Instant received = Instant.now();
        if (raw == null || raw.machineId() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "machineId required");
        Docs.Machine machine = machine(raw.machineId());
        synchronized (locks.computeIfAbsent(machine.machineId, k -> new Object())) {
            Runtime rt = runtimes.computeIfAbsent(machine.machineId, this::loadRuntime);
            var v = SensorValidator.validate(raw, ranges(machine), received, rt.last, Duration.ofSeconds(props.getTwin().getMaxFutureSeconds()));
            if (v.rejected()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join("; ", v.errors()));
            Reading r = v.cleaned();
            Quality q = v.quality();

            TwinState prev = mongo.findById(machine.machineId, TwinState.class, Docs.TWINS);
            boolean outOfOrder = prev != null && prev.lastSensorTs != null && !r.ts().isAfter(prev.lastSensorTs);
            List<String> flags = new ArrayList<>(v.flags());
            if (outOfOrder) flags.add("OUT_OF_ORDER");

            Docs.SensorReading doc = toDoc(raw, r, q, flags, received);
            try {
                mongo.insert(doc, Docs.READINGS);
            } catch (DuplicateKeyException dup) {
                return new Result("DUPLICATE", q.name(), flags, null, null);
            }
            if (outOfOrder) return new Result("OUT_OF_ORDER_IGNORED", q.name(), flags, null, null);

            // ---- rolling window bookkeeping ----
            rt.last = r;
            if (!r.running()) {
                rt.window.clear();
                rt.runningStreak = 0;
            } else if (q != Quality.BAD) {
                rt.window.addLast(r);
                while (rt.window.size() > Sensors.WINDOW) rt.window.removeFirst();
                rt.runningStreak++;
            }
            rt.suspectRecent.addLast(q != Quality.OK);
            while (rt.suspectRecent.size() > 10) rt.suspectRecent.removeFirst();

            // ---- analysis ----
            String aiStatus;
            AiClient.PredictResponse resp = null;
            DecisionEngine.Result dec = null;
            Map<String, Double> features = null;
            double aiMs = 0;
            if (q == Quality.BAD) aiStatus = "BAD_DATA";
            else if (!r.running()) aiStatus = "NOT_RUNNING";
            else if (rt.window.size() < Sensors.MIN_SAMPLES || rt.runningStreak < props.getTwin().getWarmupSamples()) aiStatus = "WARMING_UP";
            else {
                features = FeatureExtractor.extract(new ArrayList<>(rt.window));
                Quality wq = rt.suspectRecent.contains(Boolean.TRUE) ? Quality.SUSPECT : Quality.OK;
                long t0 = System.nanoTime();
                try {
                    resp = ai.predict(new AiClient.PredictRequest(machine.machineId, r.ts().toString(), features,
                            machine.baselineMeans(), wq.name()));
                    aiMs = (System.nanoTime() - t0) / 1e6;
                    aiStatus = "OK";
                    dec = engine.decide(new DecisionEngine.Input(resp.failureProbability(), resp.anomalyScore(), resp.confidence(),
                            resp.topFeatures(), wq), rt.recentP, rt.prevRisk, rt.prevRec);
                    rt.recentP.add(resp.failureProbability());
                    while (rt.recentP.size() > 100) rt.recentP.remove(0);
                    rt.prevRisk = dec.risk();
                    rt.prevRec = dec.recommendation();
                } catch (Exception e) {
                    log.warn("AI service unavailable for {}: {}", machine.machineId, e.toString());
                    aiStatus = "AI_UNAVAILABLE";
                    resp = null;
                    dec = null;
                }
            }

            TwinUpdater.Prediction pred = resp == null ? null
                    : new TwinUpdater.Prediction(resp.modelVersion(), resp.anomalyScore(), resp.failureProbability(), resp.confidence(), resp.inferenceMs());
            Instant now = Instant.now();
            TwinUpdater.Outcome out = TwinUpdater.apply(prev, r, q, pred, dec, features, machine.baselineMeans(), aiStatus, now,
                    props.getTwin().getHistorySize());
            TwinState twin = out.state();

            if (dec != null) {                                  // alert on escalation only (no spam)
                int rank = dec.recommendation().rank();
                if (rank > twin.alertRank && rank > 0) alerts.raiseRisk(machine.machineId, dec.recommendation().name(), dec.risk(), dec.reasons(), dec.lowConfidence());
                twin.alertRank = rank;
            }
            mongo.save(twin, Docs.TWINS);

            if (resp != null) savePrediction(machine.machineId, r, q, resp, dec, features, aiMs, now);
            return new Result("ACCEPTED", q.name(), flags, aiStatus, twin.recommendation);
        }
    }

    private Docs.SensorReading toDoc(RawReading raw, Reading r, Quality q, List<String> flags, Instant received) {
        Docs.SensorReading d = new Docs.SensorReading();
        d.machineId = r.machineId(); d.ts = r.ts(); d.receivedAt = received; d.seq = r.seq();
        d.temperature = r.temperature(); d.vibration = r.vibration(); d.pressure = r.pressure(); d.current = r.current(); d.speed = r.speed();
        d.state = r.state(); d.quality = q.name(); d.qualityFlags = flags; d.createdAt = received;
        return d;
    }

    private void savePrediction(String machineId, Reading r, Quality q, AiClient.PredictResponse resp, DecisionEngine.Result dec,
                                Map<String, Double> features, double aiMs, Instant now) {
        Docs.PredictionRecord p = new Docs.PredictionRecord();
        p.machineId = machineId; p.ts = r.ts(); p.createdAt = now; p.modelVersion = resp.modelVersion();
        p.anomalyScore = resp.anomalyScore(); p.failureProbability = resp.failureProbability(); p.confidence = resp.confidence();
        p.risk = dec.risk(); p.trend = dec.trend(); p.recommendation = dec.recommendation().name(); p.lowConfidence = dec.lowConfidence();
        p.reasons = dec.reasons(); p.features = features; p.quality = q.name();
        p.inferenceMs = resp.inferenceMs(); p.aiCallMs = aiMs;
        p.endToEndMs = (double) Math.max(0, Duration.between(r.ts(), now).toMillis());
        for (TopFeature f : resp.topFeatures()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", f.name()); m.put("value", f.value()); m.put("zScore", f.zScore()); m.put("contribution", f.contribution()); m.put("direction", f.direction());
            p.topFeatures.add(m);
        }
        if (resp.shadow() != null) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("modelVersion", resp.shadow().modelVersion());
            s.put("failureProbability", resp.shadow().failureProbability());
            s.put("anomalyScore", resp.shadow().anomalyScore());
            p.shadow = s;
        }
        mongo.insert(p, Docs.PREDICTIONS);
    }

    /** Rebuild window/streak/previous decision after a restart. */
    private Runtime loadRuntime(String machineId) {
        Runtime rt = new Runtime();
        List<Docs.SensorReading> rows = mongo.find(new Query(Criteria.where("machineId").is(machineId))
                .with(Sort.by(Sort.Direction.DESC, "ts")).limit(props.getTwin().getWarmupSamples() + Sensors.WINDOW), Docs.SensorReading.class, Docs.READINGS);
        java.util.Collections.reverse(rows);
        for (Docs.SensorReading d : rows) {
            Reading r = new Reading(d.machineId, d.ts, d.seq, d.temperature, d.vibration, d.pressure, d.current, d.speed, d.state);
            rt.last = r;
            if (!"RUNNING".equals(d.state)) { rt.window.clear(); rt.runningStreak = 0; }
            else if (!"BAD".equals(d.quality)) {
                rt.window.addLast(r);
                while (rt.window.size() > Sensors.WINDOW) rt.window.removeFirst();
                rt.runningStreak++;
            }
        }
        TwinState t = mongo.findById(machineId, TwinState.class, Docs.TWINS);
        if (t != null && t.riskScore != null) {
            rt.prevRisk = t.riskScore;
            rt.prevRec = Recommendation.valueOf(t.recommendation);
        }
        List<Docs.PredictionRecord> ps = mongo.find(new Query(Criteria.where("machineId").is(machineId))
                .with(Sort.by(Sort.Direction.DESC, "ts")).limit(props.getDecision().getTrendWindow()), Docs.PredictionRecord.class, Docs.PREDICTIONS);
        java.util.Collections.reverse(ps);
        for (Docs.PredictionRecord p : ps) rt.recentP.add(p.failureProbability);
        return rt;
    }

    /** Marks twins STALE when no data arrives; recovers automatically on the next reading. */
    @Scheduled(fixedDelay = 2000)
    public void checkStale() {
        Duration after = Duration.ofSeconds(props.getTwin().getStaleAfterSeconds());
        try {
            for (TwinState t : mongo.findAll(TwinState.class, Docs.TWINS)) {
                synchronized (locks.computeIfAbsent(t.machineId, k -> new Object())) {
                    TwinState fresh = mongo.findById(t.machineId, TwinState.class, Docs.TWINS);
                    if (fresh != null && TwinUpdater.markStale(fresh, Instant.now(), after)) {
                        mongo.save(fresh, Docs.TWINS);
                        alerts.raiseStale(fresh.machineId, props.getTwin().getStaleAfterSeconds());
                        audit.log("system", "TWIN_STALE", fresh.machineId, "no data for > " + after.toSeconds() + " s");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("stale check failed: {}", e.toString());
        }
    }
}
