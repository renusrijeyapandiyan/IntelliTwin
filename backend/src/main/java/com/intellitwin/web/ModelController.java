package com.intellitwin.web;

import com.intellitwin.model.Docs;
import com.intellitwin.service.ModelService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/models")
public class ModelController {
    public record PromoteRequest(@Size(max = 500) String note) {}

    private final ModelService models;

    public ModelController(ModelService models) { this.models = models; }

    @GetMapping
    public List<Docs.ModelVersion> list() { return models.list(); }

    /** ADMIN approval gate: a candidate becomes ACTIVE only through this call. */
    @PostMapping("/{id}/promote")
    public Docs.ModelVersion promote(@PathVariable String id, @Valid @RequestBody(required = false) PromoteRequest body) {
        return models.promote(id, body == null ? null : body.note());
    }

    @PostMapping("/rollback")
    public List<Docs.ModelVersion> rollback() { return models.rollback(); }
}
