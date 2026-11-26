package com.flashsale.order.deadletter;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Operator view of order-created.DLT. Under /admin, so it needs the Keycloak admin role. */
@RestController
@RequestMapping("/admin/dead-letters")
public class DeadLetterController {

    private final DeadLetterService deadLetters;

    public DeadLetterController(DeadLetterService deadLetters) {
        this.deadLetters = deadLetters;
    }

    /** Records waiting for replay, with the error that sent each one there. */
    @GetMapping
    public List<DeadLetterService.DeadLetter> pending(@RequestParam(defaultValue = "100") @Min(1) @Max(1000) int limit) {
        return deadLetters.pending(limit);
    }

    /** Re-publishes every pending record to order-created (safe: the consumer is idempotent). */
    @PostMapping("/replay")
    public Map<String, Integer> replay() {
        return Map.of("replayed", deadLetters.replayAll());
    }
}
