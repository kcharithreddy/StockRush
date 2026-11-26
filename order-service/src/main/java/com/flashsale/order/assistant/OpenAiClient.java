package com.flashsale.order.assistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

/** One call to an OpenAI-compatible Chat Completions endpoint. */
@Component
public class OpenAiClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiClient.class);
    private static final int MAX_OUTPUT_TOKENS = 600;

    private final AssistantProperties properties;
    private final RestClient http;
    private final ObjectMapper json;

    public OpenAiClient(AssistantProperties properties, ObjectMapper json) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.timeout()).build());
        factory.setReadTimeout(properties.timeout());
        this.http = RestClient.builder().baseUrl(properties.baseUrl().toString()).requestFactory(factory).build();
        this.properties = properties;
        this.json = json;
    }

    /** Returns the model's answer, or throws {@link AssistantException} with the provider's error code only. */
    public String complete(String instructions, String input) {
        String request = json.writeValueAsString(Map.of(
                "model", properties.model(),
                "max_completion_tokens", MAX_OUTPUT_TOKENS,
                "messages", List.of(
                        Map.of("role", "system", "content", instructions),
                        Map.of("role", "user", "content", input))));

        try {
            return send(request);
        } catch (RestClientException e) {
            log.warn("AI provider unreachable ({})", e.getClass().getSimpleName());
            throw new AssistantException.Upstream("unreachable");
        }
    }

    private String send(String request) {
        return http.post()
                .uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange((req, res) -> {
                    String body = new String(res.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    if (!res.getStatusCode().is2xxSuccessful()) {
                        // Error messages can echo part of the key ("Incorrect API key provided: sk-...");
                        // only the status and the machine-readable code are logged or returned.
                        String code = errorCode(body);
                        log.warn("AI provider answered {} ({})", res.getStatusCode().value(), code);
                        throw new AssistantException.Upstream(res.getStatusCode().value() + " " + code);
                    }
                    JsonNode content = parse(body).path("choices").path(0).path("message").path("content");
                    if (!content.isString() || content.asString().isBlank()) {
                        throw new AssistantException.Upstream("empty answer");
                    }
                    return content.asString().strip();
                });
    }

    private String errorCode(String body) {
        JsonNode error = parse(body).path("error");
        String code = error.path("code").asString("");
        return code.isBlank() ? error.path("type").asString("unknown") : code;
    }

    private JsonNode parse(String body) {
        try {
            return json.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (JacksonException e) {
            return json.createObjectNode();
        }
    }
}
