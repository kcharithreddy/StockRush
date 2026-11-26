package com.flashsale.order.assistant;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Under /admin, so it needs the Keycloak admin role. */
@RestController
@RequestMapping("/admin/assistant")
@EnableConfigurationProperties(AssistantProperties.class)
public class AssistantController {

    private final AssistantService assistant;
    private final OpsFactsCollector facts;

    public AssistantController(AssistantService assistant, OpsFactsCollector facts) {
        this.assistant = assistant;
        this.facts = facts;
    }

    /** Exactly what a summary would send to the AI provider. Free: no AI call. */
    @GetMapping("/facts")
    public OpsFacts facts() {
        return facts.collect();
    }

    /** Asks the AI model for a status report on the current snapshot. */
    @PostMapping("/summary")
    public AssistantService.Report summary() {
        return assistant.summarize();
    }

    @ExceptionHandler(AssistantException.Disabled.class)
    ResponseEntity<Map<String, String>> disabled(AssistantException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage(),
                "hint", "Set OPENAI_API_KEY in .env and restart order-service"));
    }

    @ExceptionHandler(AssistantException.TooSoon.class)
    ResponseEntity<Map<String, Object>> tooSoon(AssistantException.TooSoon e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()))
                .body(Map.of("error", e.getMessage(), "retryAfterSeconds", e.retryAfterSeconds()));
    }

    @ExceptionHandler(AssistantException.Upstream.class)
    ResponseEntity<Map<String, String>> upstream(AssistantException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "AI_UNAVAILABLE", "detail", e.getMessage()));
    }
}
