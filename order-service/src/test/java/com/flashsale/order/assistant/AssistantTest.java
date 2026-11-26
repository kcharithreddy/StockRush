package com.flashsale.order.assistant;

import com.flashsale.order.AclRedis;
import com.flashsale.order.IntegrationTest;
import com.flashsale.order.deadletter.DeadLetterService;
import com.flashsale.order.security.TestTokens;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The ops assistant against a fake OpenAI endpoint and a fake Alertmanager (no real AI calls, no cost). */
@AutoConfigureMockMvc
class AssistantTest extends IntegrationTest {

    private static final String KEY = "sk-test-0123456789abcdef";
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final FakeServer FAKE = new FakeServer();

    @DynamicPropertySource
    static void assistantProperties(DynamicPropertyRegistry registry) {
        registry.add("flashsale.assistant.api-key", () -> KEY);
        registry.add("flashsale.assistant.base-url", () -> FAKE.url() + "/v1");
        registry.add("flashsale.assistant.alertmanager-url", FAKE::url);
        registry.add("flashsale.assistant.min-interval", () -> "0s");
        registry.add("flashsale.assistant.timeout", () -> "5s");
    }

    @AfterAll
    static void stopFake() {
        FAKE.server.stop(0);
    }

    @MockitoBean
    DeadLetterService deadLetters;

    @Autowired
    OpsFactsCollector collector;

    @Autowired
    AssistantService assistant;

    @Autowired
    ObjectMapper json;

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        FAKE.reset();
        when(deadLetters.summary()).thenReturn(new DeadLetterService.Summary(
                3, Map.of("InvalidEventException", 2, "DataIntegrityViolationException", 1),
                Instant.parse("2026-01-01T10:00:00Z"), Instant.parse("2026-01-01T10:05:00Z")));
    }

    @Test
    void factsAreAggregatesOnly() {
        AclRedis.admin().opsForValue().set("sale:1:stock", "97");
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        insertOrder(1, alice, "PENDING_PAYMENT", 1);
        insertOrder(1, bob, "PAID", 1);
        insertOrder(1, UUID.randomUUID(), "PAID", 1);
        insertOrder(1, UUID.randomUUID(), "CANCELLED", 30);

        String facts = json.writeValueAsString(collector.collect());
        JsonNode tree = json.readTree(facts);

        JsonNode sale1 = tree.path("sales").path(0);
        assertThat(sale1.path("saleId").asLong()).isEqualTo(1);
        assertThat(sale1.path("state").asString()).isEqualTo("OPEN");
        assertThat(sale1.path("stockInRedis").asInt()).isEqualTo(97);
        assertThat(sale1.path("orders").path("PAID").asInt()).isEqualTo(2);
        // 100 - (97 + 1 pending + 2 paid) = 0: every unit accounted for, the cancelled one included.
        assertThat(sale1.path("unaccounted").asInt()).isZero();
        JsonNode sale2 = tree.path("sales").path(1);
        assertThat(sale2.path("stockInRedis").isNull()).isTrue();
        assertThat(sale2.path("unaccounted").isNull()).isTrue();

        assertThat(tree.path("deadLetters").path("pending").asInt()).isEqualTo(3);
        assertThat(tree.path("alerts").path(0).path("name").asString()).isEqualTo("OrderEventsDeadLettered");
        assertThat(tree.has("unavailable")).isFalse();

        // No user ids, order tokens or other UUIDs, and no alert labels beyond name and severity.
        assertThat(UUID_PATTERN.matcher(facts).find()).as(facts).isFalse();
        assertThat(facts).doesNotContain(alice.toString(), "order-service:9091", "instance", "description");
    }

    @Test
    void summarySendsTheSnapshotAndReturnsTheAnswer() {
        insertOrder(1, UUID.randomUUID(), "PAID", 1);

        AssistantService.Report report = assistant.summarize();

        assertThat(report.summary()).isEqualTo("Status: ATTENTION. 3 events are dead-lettered.");
        assertThat(report.model()).isEqualTo("gpt-4o-mini");
        assertThat(FAKE.lastAuthorization).isEqualTo("Bearer " + KEY);
        JsonNode sent = json.readTree(FAKE.lastChatRequest);
        assertThat(sent.path("model").asString()).isEqualTo("gpt-4o-mini");
        assertThat(sent.path("messages").path(0).path("content").asString()).isEqualTo(AssistantService.INSTRUCTIONS);
        String userMessage = sent.path("messages").path(1).path("content").asString();
        assertThat(json.readTree(userMessage).path("deadLetters").path("pending").asInt()).isEqualTo(3);
        assertThat(UUID_PATTERN.matcher(FAKE.lastChatRequest).find()).isFalse();
    }

    @Test
    void providerErrorsReturnOnlyTheErrorCode() throws Exception {
        FAKE.chatStatus = 401;
        FAKE.chatBody = """
                {"error":{"message":"Incorrect API key provided: sk-test-****cdef.","type":"invalid_request_error",
                "code":"invalid_api_key"}}""";

        String body = mvc.perform(post("/admin/assistant/summary").with(TestTokens.bearer(UUID.randomUUID(), "admin")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("AI_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail").value("401 invalid_api_key"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("sk-");
    }

    @Test
    void sourcesThatFailAreListedNotFatal() {
        FAKE.alertsStatus = 500;
        when(deadLetters.summary()).thenThrow(new IllegalStateException("kafka down"));

        OpsFacts facts = collector.collect();

        assertThat(facts.unavailable()).containsExactly("deadLetters", "alerts");
        assertThat(facts.sales()).isNotEmpty();
    }

    @Test
    void onlyAdminsMayUseIt() throws Exception {
        UUID user = UUID.randomUUID();
        mvc.perform(get("/admin/assistant/facts").with(TestTokens.bearer(user, "buyer"))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/assistant/summary").with(TestTokens.bearer(user, "buyer"))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/assistant/summary")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/assistant/facts").with(TestTokens.bearer(user, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sales[0].saleId").value(1));
        assertThat(FAKE.chatCalls).isZero();
    }

    @Test
    void offWithoutAKeyAndRateLimitedWithOne() {
        OpsFactsCollector facts = mock(OpsFactsCollector.class);
        OpenAiClient ai = mock(OpenAiClient.class);
        when(ai.complete(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn("ok");
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

        AssistantService off = new AssistantService(properties(""), facts, ai, json, clock);
        assertThatThrownBy(off::summarize).isInstanceOf(AssistantException.Disabled.class);
        assertThat(properties(KEY).toString()).doesNotContain(KEY);

        AssistantService on = new AssistantService(properties(KEY), facts, ai, json, clock);
        assertThat(on.summarize().summary()).isEqualTo("ok");
        assertThatThrownBy(on::summarize).isInstanceOfSatisfying(AssistantException.TooSoon.class,
                e -> assertThat(e.retryAfterSeconds()).isEqualTo(30));
    }

    private static AssistantProperties properties(String key) {
        return new AssistantProperties(key, "gpt-4o-mini", URI.create("http://localhost:1"), URI.create("http://localhost:1"),
                Duration.ofSeconds(30), Duration.ofSeconds(1));
    }

    /** Answers /v1/chat/completions like OpenAI and /api/v2/alerts like Alertmanager. */
    static final class FakeServer {
        final HttpServer server;
        volatile int chatStatus;
        volatile String chatBody;
        volatile int alertsStatus;
        volatile String lastChatRequest;
        volatile String lastAuthorization;
        volatile int chatCalls;

        FakeServer() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            server.createContext("/v1/chat/completions", exchange -> {
                chatCalls++;
                lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
                lastChatRequest = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                respond(exchange, chatStatus, chatBody);
            });
            server.createContext("/api/v2/alerts", exchange -> respond(exchange, alertsStatus, alertsStatus == 200 ? """
                    [{"labels":{"alertname":"OrderEventsDeadLettered","severity":"warning","instance":"order-service:9091",
                      "job":"order-service"},
                      "annotations":{"summary":"Order events are being dead-lettered","description":"secret detail"},
                      "startsAt":"2026-01-01T10:01:00.123+00:00","status":{"state":"active"}}]""" : "oops"));
            server.start();
            reset();
        }

        void reset() {
            chatStatus = 200;
            chatBody = """
                    {"choices":[{"message":{"role":"assistant","content":"  Status: ATTENTION. 3 events are dead-lettered.\\n"}}]}""";
            alertsStatus = 200;
            lastChatRequest = null;
            lastAuthorization = null;
            chatCalls = 0;
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
