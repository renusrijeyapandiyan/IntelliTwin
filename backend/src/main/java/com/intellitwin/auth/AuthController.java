package com.intellitwin.auth;

import com.intellitwin.model.Docs;
import com.intellitwin.service.AuditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public record LoginRequest(@NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 128) String password) {}

    public record LoginResponse(String token, String username, String role, Instant expiresAt) {}

    private static final int MAX_FAILURES = 5;
    private static final long WINDOW_MS = 5 * 60_000L;

    private final MongoTemplate mongo;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final AuditService audit;
    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();
    // constant-time dummy check so unknown users cost the same as wrong passwords
    private final String dummyHash;

    public AuthController(MongoTemplate mongo, PasswordEncoder encoder, JwtService jwt, AuditService audit) {
        this.mongo = mongo;
        this.encoder = encoder;
        this.jwt = jwt;
        this.audit = audit;
        this.dummyHash = encoder.encode("dummy-password-for-timing");
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        String key = req.username().toLowerCase();
        if (locked(key)) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "too many failed attempts; try again later");
        Docs.User u = mongo.findOne(new Query(Criteria.where("username").is(req.username())), Docs.User.class, Docs.USERS);
        boolean ok = encoder.matches(req.password(), u == null ? dummyHash : u.passwordHash) && u != null;
        if (!ok) {
            recordFailure(key);
            audit.log(req.username(), "LOGIN_FAILED", "auth", "invalid credentials");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid username or password");
        }
        failures.remove(key);
        audit.log(u.username, "LOGIN", "auth", "role " + u.role);
        return new LoginResponse(jwt.issue(u.username, u.role), u.username, u.role, jwt.expiryFromNow());
    }

    private boolean locked(String key) {
        Deque<Long> d = failures.get(key);
        if (d == null) return false;
        synchronized (d) {
            long cutoff = System.currentTimeMillis() - WINDOW_MS;
            while (!d.isEmpty() && d.peekFirst() < cutoff) d.removeFirst();
            return d.size() >= MAX_FAILURES;
        }
    }

    private void recordFailure(String key) {
        Deque<Long> d = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (d) { d.addLast(System.currentTimeMillis()); }
    }
}
