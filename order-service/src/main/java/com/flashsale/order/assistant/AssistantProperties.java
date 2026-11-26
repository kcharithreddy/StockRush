package com.flashsale.order.assistant;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * flashsale.assistant.* (see application.yml). The assistant is off unless an API key is configured.
 *
 * @param apiKey          OpenAI API key, from OPENAI_API_KEY in .env. Never logged or returned.
 * @param model           chat model to ask
 * @param baseUrl         OpenAI-compatible API root (tests point it at a fake server)
 * @param alertmanagerUrl where to read the currently firing alerts
 * @param minInterval     at most one AI call per interval, a guard on cost
 * @param timeout         connect and read timeout for both outbound calls
 */
@ConfigurationProperties("flashsale.assistant")
public record AssistantProperties(String apiKey, String model, URI baseUrl, URI alertmanagerUrl,
                                  Duration minInterval, Duration timeout) {

    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Records print every field; this one must never print the key. */
    @Override
    public String toString() {
        return "AssistantProperties[apiKey=" + (enabled() ? "(set)" : "(none)") + ", model=" + model
                + ", baseUrl=" + baseUrl + ", alertmanagerUrl=" + alertmanagerUrl
                + ", minInterval=" + minInterval + ", timeout=" + timeout + "]";
    }
}
