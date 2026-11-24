package com.intellitwin.config;

import com.intellitwin.model.Docs;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/** Creates indexes explicitly: uniqueness (dedup) and TTL retention. Runs before the seeder. */
@Component
@Order(0)
public class MongoIndexes implements ApplicationRunner {
    private final MongoTemplate mongo;
    private final AppProperties props;

    public MongoIndexes(MongoTemplate mongo, AppProperties props) {
        this.mongo = mongo;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        mongo.indexOps(Docs.USERS).ensureIndex(new Index().on("username", Sort.Direction.ASC).unique());
        mongo.indexOps(Docs.MACHINES).ensureIndex(new Index().on("machineId", Sort.Direction.ASC).unique());
        // duplicate (machineId, ts) readings are rejected by this unique index
        mongo.indexOps(Docs.READINGS).ensureIndex(new Index().on("machineId", Sort.Direction.ASC).on("ts", Sort.Direction.ASC).unique());
        mongo.indexOps(Docs.READINGS).ensureIndex(new Index().on("createdAt", Sort.Direction.ASC).expire(props.getRetention().getSensorDays() * 86400L).named("ttl_readings"));
        mongo.indexOps(Docs.PREDICTIONS).ensureIndex(new Index().on("machineId", Sort.Direction.ASC).on("ts", Sort.Direction.DESC));
        mongo.indexOps(Docs.PREDICTIONS).ensureIndex(new Index().on("createdAt", Sort.Direction.ASC).expire(props.getRetention().getPredictionDays() * 86400L).named("ttl_predictions"));
        mongo.indexOps(Docs.ALERTS).ensureIndex(new Index().on("machineId", Sort.Direction.ASC).on("status", Sort.Direction.ASC));
        mongo.indexOps(Docs.MAINTENANCE).ensureIndex(new Index().on("machineId", Sort.Direction.ASC).on("performedAt", Sort.Direction.DESC));
        mongo.indexOps(Docs.AUDIT).ensureIndex(new Index().on("ts", Sort.Direction.DESC));
    }
}
