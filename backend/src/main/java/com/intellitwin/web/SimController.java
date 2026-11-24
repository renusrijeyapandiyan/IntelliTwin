package com.intellitwin.web;

import com.intellitwin.service.AuditService;
import com.intellitwin.service.SimClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/** Demo-only proxy to the simulator control API. Advisory system: this never touches real machines. */
@RestController
@RequestMapping("/api/sim")
public class SimController {
    public record InjectFault(@NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{1,32}$") String machineId,
                              @NotBlank @Pattern(regexp = "bearing_wear|jam|leakage|imbalance|lube_loss") String fault,
                              @DecimalMin("1") @DecimalMax("7200") Double rampSeconds,
                              @DecimalMin("0") @DecimalMax("1") Double severity) {}

    private final SimClient sim;
    private final AuditService audit;

    public SimController(SimClient sim, AuditService audit) {
        this.sim = sim;
        this.audit = audit;
    }

    private <T> T guard(java.util.function.Supplier<T> s) {
        try {
            return s.get();
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "simulator unavailable or rejected the request");
        }
    }

    @GetMapping("/status")
    public Map<String, Object> status() { return guard(sim::status); }

    @PostMapping("/faults")
    public Map<String, Object> inject(@Valid @RequestBody InjectFault req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("machineId", req.machineId());
        body.put("fault", req.fault());
        if (req.rampSeconds() != null) body.put("rampSeconds", req.rampSeconds());
        if (req.severity() != null) body.put("severity", req.severity());
        Map<String, Object> out = guard(() -> sim.inject(body));
        audit.log("SIM_FAULT_INJECTED", req.machineId(), req.fault() + " " + body);
        return out;
    }

    @DeleteMapping("/faults/{machineId}")
    public Map<String, Object> clear(@PathVariable String machineId, @RequestParam(required = false) String fault) {
        Map<String, Object> out = guard(() -> sim.clear(machineId, fault));
        audit.log("SIM_FAULT_CLEARED", machineId, fault == null ? "all" : fault);
        return out;
    }

    @PostMapping("/machines/{machineId}/{action}")
    public Map<String, Object> power(@PathVariable String machineId, @PathVariable String action) {
        if (!action.equals("start") && !action.equals("stop")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action must be start or stop");
        Map<String, Object> out = guard(() -> sim.power(machineId, action));
        audit.log("SIM_MACHINE_" + action.toUpperCase(), machineId, "simulated machine");
        return out;
    }
}
