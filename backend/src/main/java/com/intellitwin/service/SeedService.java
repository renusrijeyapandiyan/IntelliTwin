package com.intellitwin.service;

import com.intellitwin.config.AppProperties;
import com.intellitwin.model.Docs;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Seeds one user per role and 3 machines on first start. Passwords come only from environment variables. */
@Component
@Order(10)
public class SeedService implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SeedService.class);
    private final MongoTemplate mongo;
    private final PasswordEncoder encoder;
    private final AppProperties props;

    public SeedService(MongoTemplate mongo, PasswordEncoder encoder, AppProperties props) {
        this.mongo = mongo;
        this.encoder = encoder;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedUser("admin", props.getSeed().getAdminPassword(), "ADMIN");
        seedUser("engineer", props.getSeed().getEngineerPassword(), "ENGINEER");
        seedUser("operator", props.getSeed().getOperatorPassword(), "OPERATOR");
        seedMachine("M-001", "Drive Motor", "MOTOR", "Line 1", 62.0, 2.0, 3.0, 35.0, 1480.0, 0.004, 0.07, 0.008, 0.012, 0.002);
        seedMachine("M-002", "Coolant Pump", "PUMP", "Line 1", 55.0, 2.8, 6.5, 22.0, 2950.0, 0.004, 0.07, 0.008, 0.012, 0.002);
        seedMachine("M-003", "Air Compressor", "COMPRESSOR", "Utilities", 78.0, 3.2, 8.0, 48.0, 1780.0, 0.004, 0.07, 0.008, 0.012, 0.002);
    }

    private void seedUser(String username, String password, String role) {
        if (mongo.exists(new Query(Criteria.where("username").is(username)), Docs.USERS)) return;
        if (password == null || password.length() < 8) {
            log.warn("user '{}' NOT seeded: set SEED_{}_PASSWORD (min 8 chars)", username, username.toUpperCase());
            return;
        }
        Docs.User u = new Docs.User();
        u.username = username; u.passwordHash = encoder.encode(password); u.role = role; u.createdAt = Instant.now();
        mongo.insert(u, Docs.USERS);
        log.info("seeded user '{}' with role {}", username, role);
    }

    private void seedMachine(String id, String name, String type, String loc, double t, double v, double p, double c, double s,
                             double nt, double nv, double np, double nc, double ns) {
        if (mongo.exists(new Query(Criteria.where("machineId").is(id)), Docs.MACHINES)) return;
        Docs.Machine m = new Docs.Machine();
        m.id = id; m.machineId = id; m.name = name; m.type = type; m.location = loc; m.createdAt = Instant.now();
        m.baselines.put("temperature", bl(t, t * nt));
        m.baselines.put("vibration", bl(v, v * nv));
        m.baselines.put("pressure", bl(p, p * np));
        m.baselines.put("current", bl(c, c * nc));
        m.baselines.put("speed", bl(s, s * ns));
        mongo.save(m, Docs.MACHINES);
        log.info("seeded machine {}", id);
    }

    private static Map<String, Double> bl(double mean, double std) {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("mean", mean);
        m.put("std", Math.round(std * 10000.0) / 10000.0);
        return m;
    }
}
