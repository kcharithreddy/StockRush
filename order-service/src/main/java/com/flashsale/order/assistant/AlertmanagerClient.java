package com.flashsale.order.assistant;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Reads the alerts that are firing now (not silenced or inhibited) from Alertmanager's v2 API. */
@Component
public class AlertmanagerClient {

    private static final int MAX_ALERTS = 20;

    private final RestClient http;
    private final ObjectMapper json;

    public AlertmanagerClient(AssistantProperties properties, ObjectMapper json) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.timeout()).build());
        factory.setReadTimeout(properties.timeout());
        this.http = RestClient.builder().baseUrl(properties.alertmanagerUrl().toString()).requestFactory(factory).build();
        this.json = json;
    }

    /** Name, severity, summary and start time only: other labels (instance, job, ...) are not passed on. */
    public List<OpsFacts.Alert> firing() {
        String body = http.get()
                .uri("/api/v2/alerts?active=true&silenced=false&inhibited=false")
                .retrieve()
                .body(String.class);
        List<OpsFacts.Alert> alerts = new ArrayList<>();
        for (JsonNode alert : json.readTree(body == null ? "[]" : body)) {
            if (alerts.size() == MAX_ALERTS) {
                break;
            }
            String since = alert.path("startsAt").asString(null);
            alerts.add(new OpsFacts.Alert(
                    alert.path("labels").path("alertname").asString("unknown"),
                    alert.path("labels").path("severity").asString(null),
                    alert.path("annotations").path("summary").asString(null),
                    since == null ? null : OffsetDateTime.parse(since).toInstant()));
        }
        return alerts;
    }
}
