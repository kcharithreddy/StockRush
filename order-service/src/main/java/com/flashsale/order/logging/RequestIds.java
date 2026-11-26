package com.flashsale.order.logging;

import java.util.UUID;
import java.util.regex.Pattern;

/** One id per request, carried as X-Request-Id over HTTP, request_id over Kafka, and request_id in every log line. */
public final class RequestIds {

    public static final String HEADER = "X-Request-Id";
    public static final String KAFKA_HEADER = "request_id";
    public static final String MDC_KEY = "request_id";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    private RequestIds() {
    }

    /** Keeps a well-formed incoming id (normally Nginx's $request_id); anything else is replaced, never logged as-is. */
    public static String acceptOrGenerate(String incoming) {
        return incoming != null && SAFE.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
    }
}
