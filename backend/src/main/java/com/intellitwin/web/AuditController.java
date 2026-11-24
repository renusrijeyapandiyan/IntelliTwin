package com.intellitwin.web;

import com.intellitwin.model.Docs;
import com.intellitwin.service.AuditService;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/audit")
public class AuditController {
    private final AuditService audit;

    public AuditController(AuditService audit) { this.audit = audit; }

    @GetMapping
    public List<Docs.AuditEntry> recent(@RequestParam(defaultValue = "100") int limit) { return audit.recent(limit); }
}
