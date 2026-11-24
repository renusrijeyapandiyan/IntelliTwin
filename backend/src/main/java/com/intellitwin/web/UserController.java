package com.intellitwin.web;

import com.intellitwin.model.Docs;
import com.intellitwin.service.AuditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** ADMIN-only account administration. Password hashes are never returned. */
@RestController
@RequestMapping("/api/users")
public class UserController {
    public record UserView(String username, String role, Instant createdAt) {}

    public record CreateUser(@NotBlank @Pattern(regexp = "^[A-Za-z0-9_.-]{3,32}$") String username,
                             @NotBlank @Size(min = 8, max = 128) String password,
                             @NotBlank @Pattern(regexp = "ADMIN|ENGINEER|OPERATOR") String role) {}

    private final MongoTemplate mongo;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public UserController(MongoTemplate mongo, PasswordEncoder encoder, AuditService audit) {
        this.mongo = mongo;
        this.encoder = encoder;
        this.audit = audit;
    }

    @GetMapping
    public List<UserView> list() {
        return mongo.find(new Query().with(Sort.by("username")), Docs.User.class, Docs.USERS).stream()
                .map(u -> new UserView(u.username, u.role, u.createdAt)).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserView create(@Valid @RequestBody CreateUser req) {
        if (mongo.exists(new Query(Criteria.where("username").is(req.username())), Docs.USERS))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "username already exists");
        Docs.User u = new Docs.User();
        u.username = req.username();
        u.passwordHash = encoder.encode(req.password());
        u.role = req.role();
        u.createdAt = Instant.now();
        mongo.insert(u, Docs.USERS);
        audit.log("USER_CREATED", u.username, "role " + u.role);
        return new UserView(u.username, u.role, u.createdAt);
    }

    @DeleteMapping("/{username}")
    public UserView delete(@PathVariable String username) {
        if (username.equals(AuditService.currentUser()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "you cannot delete your own account");
        Docs.User u = mongo.findOne(new Query(Criteria.where("username").is(username)), Docs.User.class, Docs.USERS);
        if (u == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found");
        mongo.remove(u, Docs.USERS);
        audit.log("USER_DELETED", username, "role " + u.role);
        return new UserView(u.username, u.role, u.createdAt);
    }
}
