package com.intellitwin.service;

import com.intellitwin.model.Docs;
import java.time.Instant;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;

/** Append-only audit trail of user and system actions. */
@Service
public class AuditService {
    private final MongoTemplate mongo;

    public AuditService(MongoTemplate mongo) { this.mongo = mongo; }

    public static String currentUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? "system" : String.valueOf(a.getPrincipal());
    }

    public void log(String action, String target, String details) { log(currentUser(), action, target, details); }

    public void log(String user, String action, String target, String details) {
        Docs.AuditEntry e = new Docs.AuditEntry();
        e.user = user; e.action = action; e.target = target; e.details = details; e.ts = Instant.now();
        mongo.insert(e, Docs.AUDIT);
    }

    public List<Docs.AuditEntry> recent(int limit) {
        return mongo.find(new Query().with(Sort.by(Sort.Direction.DESC, "ts")).limit(Math.min(Math.max(limit, 1), 500)),
                Docs.AuditEntry.class, Docs.AUDIT);
    }
}
