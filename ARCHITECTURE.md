# Flash Sale System — Architecture & Build Spec

This file is the source of truth for this repo. Read it fully before writing code. Build in the phases listed at the bottom, one phase at a time. After each phase, run its acceptance checks, report the results, and STOP for my review before starting the next phase.

## 1. What this system does

A flash-sale backend: thousands of users try to buy a small, fixed stock (e.g. 100 units) at the same moment. Requirements:

1. **Never oversell.** Exactly N orders for N units, under any load.
2. **One purchase per user per sale.**
3. **Survive the spike.** The database must never see the raw traffic.
4. **Release unpaid stock.** Orders not paid within a timeout are cancelled and their unit returns to stock.
5. **Observable and provable.** Metrics in Grafana and a k6 load test that proves requirement 1.

## 2. Tech stack (do not substitute without asking)

| Concern | Choice |
| :---- | :---- |
| Language / framework | Java 17, Spring Boot 4.x (Spring Framework 7, Jackson 3), Maven (use the Maven wrapper `./mvnw`; the wrapper needs `unzip`, since its download is pinned to the zip's checksum). Images are built from source in Docker (`maven:3.9.11-eclipse-temurin-17` build stage, JRE-only runtime stage), so running the stack needs no local JDK |
| Hot state / atomic stock | Redis 7 with AOF (`--appendonly yes`), Lua scripts |
| Async order pipeline | Apache Kafka 3.8 (KRaft mode, no ZooKeeper), image `apache/kafka:3.8.0` |
| Database | PostgreSQL 16 |
| Load balancer | Nginx 1.27 |
| Containers | Docker Compose (single `docker-compose.yml` at repo root) |
| Load testing | k6 |
| Monitoring | Micrometer + Prometheus + Grafana |
| Identity | Keycloak 26 (OIDC), image `keycloak/keycloak:26.4.0`; services are Spring Security OAuth2 resource servers |

No Lombok. Use Java records for DTOs/events. Use `JdbcTemplate` for the hot paths in order-service (insert, pay, expire); JPA is fine for admin/read code.

## 3. Components

```
Users / k6
   │
   ▼
Nginx :8080  ── per-IP rate limit on buy, round-robin; routes only /sales/* and /orders/*
   │
   ├──► sale-1 :8080 (container)  ┐  sale-service (stateless, 2 instances)
   └──► sale-2 :8080 (container)  ┘  - per-user rate limit (Redis)
                │                    - local in-memory "sold out" flag
                │                    - (1) atomic buy via Redis Lua
                │                    - (2) publish OrderCreated to Kafka
                ▼
        Redis :6379  ◄──────────────────────────────┐
        Kafka :9092 (host) / kafka:29092 (docker)   │ (restore stock on timeout,
                │                                    │  warm-up, reconciliation)
                ▼ (3) consume                        │
        order-service :8090 ─────────────────────────┘
          - idempotent Kafka consumer
          - order status + mock payment API
          - payment timeout worker
          - reconciliation job
          - admin: warm-up / reset sale
                │ (4) write
                ▼
        PostgreSQL :5432  (source of truth for orders)

Keycloak :8180 issues the users' access tokens (realm stockrush); both services verify them
Prometheus :9090 scrapes sale-1, sale-2 (:9080) and order-service (:9091) at /actuator/prometheus
(management ports, never published outside the Docker network)
Grafana :3000 reads Prometheus
```

### 3.1 sale-service

- Stateless. Owns the buy path only. Has NO database connection.
- Talks to Redis (Lua buy script, rate limiter) and Kafka (producer).

### 3.2 order-service

- Owns PostgreSQL. Consumes Kafka. Writes to Redis only for warm-up, reset, and stock restore.
- Runs as a single instance today. The timeout worker already uses `FOR UPDATE SKIP LOCKED`, so more instances could share it; the consumer scales with the topic's 3 partitions.

## 4. Repository layout

```
flash-sale-system/
├── ARCHITECTURE.md
├── README.md
├── docker-compose.yml
├── SECURITY.md
├── .env.example             # copy to .env (git-ignored); all passwords live there
├── .github/                 # CI, CodeQL, Dependabot
├── infra/
│   ├── db/migration/          # Flyway migrations V1__…, V2__… (the schema's single source of truth)
│   ├── db-init/02-app-user.sh   # roles: flashsale_migrator (schema owner) and DB_USER (services)
│   ├── nginx.conf
│   ├── nginx-ratelimit.conf     # per-IP limit, included by nginx.conf
│   ├── prometheus.yml
│   └── grafana/                 # provisioned datasource + dashboard
├── sale-service/            # Spring Boot app, package com.flashsale.sale
│   ├── Dockerfile
│   └── src/main/resources/scripts/buy.lua
├── order-service/           # Spring Boot app, package com.flashsale.order
│   └── Dockerfile
├── web/                     # the shop page: index.html, app.js, app.css (static, no build step; served by Nginx)
├── scripts/                 # dev-cert.sh, token.sh, keycloak-web-client.sh
├── docker-compose.loadtest.yml  # override: per-IP limit off for k6
├── loadtest/
│   ├── flash-sale.js
│   └── nginx-no-ratelimit.conf
└── docs/                    # load test results, Grafana screenshots
```

## 5. Data model (infra/db/migration — applied by Flyway)

The schema lives in versioned Flyway migrations: `V1__create_shop_schema.sql` (below), `V2__seed_demo_sale.sql` (the demo sale), `V3__index_orders_by_sale_and_status.sql`. They are never edited once merged; every change is a new `V<n>__description.sql`. The schema as of V1:

```sql
CREATE TABLE products (
  id          BIGSERIAL PRIMARY KEY,
  name        TEXT   NOT NULL,
  price_paise BIGINT NOT NULL
);

CREATE TABLE flash_sales (
  id               BIGSERIAL PRIMARY KEY,
  product_id       BIGINT NOT NULL REFERENCES products(id),
  total_stock      INT    NOT NULL,
  sale_price_paise BIGINT NOT NULL,
  starts_at        TIMESTAMPTZ NOT NULL,
  ends_at          TIMESTAMPTZ NOT NULL
);

CREATE TABLE orders (
  id          BIGSERIAL PRIMARY KEY,
  order_token UUID   NOT NULL UNIQUE,
  sale_id     BIGINT NOT NULL REFERENCES flash_sales(id),
  user_id     UUID   NOT NULL,   -- Keycloak subject (token "sub")
  status      TEXT   NOT NULL,   -- PENDING_PAYMENT | PAID | CANCELLED
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (sale_id, user_id)
);
CREATE INDEX idx_orders_status_created ON orders(status, created_at);

INSERT INTO products (name, price_paise) VALUES ('Phone X 128GB', 7999900);
INSERT INTO flash_sales (product_id, total_stock, sale_price_paise, starts_at, ends_at)
VALUES (1, 100, 4999900, now(), now() + interval '30 days');
```

Money is always integer paise. Never floats.

**Who can do what.** `postgres` (superuser) only creates the two roles and hands the `public` schema to `flashsale_migrator` (`infra/db-init/02-app-user.sh`). `flashsale_migrator` owns every table and is used only by the one-shot `db-migrate` container. The services connect as `DB_USER`, which gets exactly the grants each migration gives it (`${app_user}` placeholder): `SELECT` on `products` and `flash_sales`, `SELECT/INSERT/UPDATE/DELETE` on `orders`. It cannot create, alter or drop anything.

**How migrations run.** On `docker compose up`, `db-migrate` (image `flyway/flyway:12.11.0`) applies any pending migrations as `flashsale_migrator` and exits; order-service waits for it to succeed (`service_completed_successfully`). order-service does not contain Flyway at all (it is a test-only dependency), and still validates the result at startup (`ddl-auto: validate`), so a schema that does not match the entities stops it from starting. Tests apply the same files through Flyway against PostgreSQL 16 in Testcontainers.

**Rules for new migrations.**

- Never edit a merged migration; add the next version. Flyway checksums catch edits.
- Grant the app role what a new table needs, in the same migration (`GRANT … TO "${app_user}"`).
- Indexes on live tables use `CREATE INDEX CONCURRENTLY` with `-- flyway:executeInTransaction=false` at the top, and without `IF NOT EXISTS` (an interrupted build leaves an INVALID index that `IF NOT EXISTS` would silently accept).
- Flyway runs with `postgresql.transactional.lock=false`. With the default transactional lock, `CREATE INDEX CONCURRENTLY` waits forever on Flyway's own lock transaction — this happened while adding V3, and also left the history claiming success with an invalid index, which had to be cleaned up by hand.

## 6. Redis keys

| Key | Type | Meaning | Written by |
| :---- | :---- | :---- | :---- |
| `sale:{saleId}:stock` | string (int) | units left to win | order-service (warm-up, restore), Lua (decrement) |
| `sale:{saleId}:buyers` | set of user ids (UUID strings) | users who already won | Lua |
| `sale:{saleId}:window` | string `"startMillis,endMillis"` | sale active window | order-service warm-up |
| `rl:{userId}:{epochSecond}` (userId = token subject) | string (int), TTL 2 s | per-user rate-limit counter | sale-service |

A user whose order expires stays in `buyers` — one attempt per user per sale (documented design choice).

## 7. The buy script (sale-service/src/main/resources/scripts/buy.lua)

```
-- KEYS[1] = sale:{id}:stock   KEYS[2] = sale:{id}:buyers   KEYS[3] = sale:{id}:window
-- ARGV[1] = userId
-- Returns: 1 success | 0 sold out | -1 already bought | -2 sale not active
-- "now" comes from Redis's own clock, so every sale-service instance judges the window identically.
local window = redis.call('GET', KEYS[3])
if not window then return -2 end
local sep = string.find(window, ',')
local startsAt = tonumber(string.sub(window, 1, sep - 1))
local endsAt = tonumber(string.sub(window, sep + 1))
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
if now < startsAt or now > endsAt then return -2 end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return -1 end
local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
if stock <= 0 then return 0 end
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return 1
```

This script is the ONLY place stock is decremented. Never implement "read stock in Java, then write".

## 8. Kafka

- Topic `order-created`: 3 partitions, replication 1, created via a `NewTopic` bean in sale-service (and the same definition in order-service).
- Topic `order-created.DLT`: 3 partitions, replication 1, created by order-service. A dead-lettered record keeps its partition and carries the error in Spring Kafka's `kafka_dlt-*` headers.
- Message key: `userId` as string. Value: JSON string.
- Event: `record OrderCreatedEvent(String orderToken, long saleId, String userId)` — `userId` is the buyer's Keycloak subject (UUID).
- Producer: `acks=all`, String serializers. The send is synchronous with a 2 s timeout (`.get(2, SECONDS)`).
- Consumer group: `order-service`, `auto-offset-reset=earliest`. Delivery is at-least-once; the consumer MUST be idempotent.

## 9. API contracts

Every protected call carries `Authorization: Bearer <access token>`. The user id is the token's `sub` (a UUID); the roles are Keycloak realm roles in `realm_access.roles`:

| Role | Who | Can |
| :---- | :---- | :---- |
| `buyer` | every new user (realm default role) | buy, see and pay **own** orders |
| `admin` | operators | `/admin/**` |

A token is accepted only if all of these hold: valid RS256 signature from the realm's JWKS, `iss` = `OIDC_ISSUER`, `aud` contains `stockrush-api`, not expired, and `sub` is a UUID. Missing or invalid token → 401; valid token without the role → 403. See §17 for the load-test issuer.

### sale-service (behind Nginx at https://localhost:8443)

`POST /sales/{saleId}/buy` (role `buyer`)

| Lua result | HTTP | Body |
| :---- | :---- | :---- |
| 1 and Kafka send OK | 202 | `{"status":"PROCESSING","orderToken":"<uuid>"}` |
| 0 | 410 | `{"status":"SOLD_OUT"}` |
| -1 | 409 | `{"status":"ALREADY_BOUGHT"}` |
| -2 | 403 | `{"status":"SALE_NOT_ACTIVE"}` |
| 1 but Kafka send failed or timed out | 503 | `{"status":"TRY_AGAIN"}` |
| Redis unavailable | 503 + `Retry-After: 1` | `{"status":"TRY_AGAIN"}` |
| no or invalid token | 401 | — |
| token without the `buyer` role | 403 | — |
| `saleId` ≤ 0 | 400 | — |
| > 5 req/s for this user | 429 + `Retry-After: 1` | — |

`GET /sales/{saleId}/stock` → `{"stock": <int>}` (reads Redis; public, no token)

### order-service ([http://localhost:8090](http://localhost:8090))

| Method & path | Behaviour |
| :---- | :---- |
| `GET /orders/{token}` | `{"status": "PENDING_PAYMENT" | "PAID" | "CANCELLED" | "PROCESSING"}` — PROCESSING if the caller has no such order (yet) |
| `POST /orders/{token}/pay` | `UPDATE ... SET status='PAID' WHERE order_token=? AND user_id=? AND status='PENDING_PAYMENT'`; 200 `{"status":"PAID"}` if 1 row, else 409 `{"status":"NOT_PAYABLE"}` |
| `POST /admin/sales/{id}/warmup` | stock in Redis = total_stock − count(orders PENDING_PAYMENT or PAID); sets `window`; returns `{"saleId","stock"}` |
| `GET /admin/assistant/facts` | the aggregate snapshot the ops assistant would send (§20); no AI call |
| `POST /admin/assistant/summary` | AI-written status report: 200 `{"summary","model","sentToModel"}`; 503 `ASSISTANT_DISABLED` without a key; 429 `TOO_SOON` (+`Retry-After`) within 30 s of the last call; 502 `AI_UNAVAILABLE` with only the provider's status and error code |
| `POST /admin/sales/{id}/reset` | TEST ONLY: delete the sale's orders, delete `stock` and `buyers` keys, then warm-up. Exists only when `RESET_ENABLED=true`; otherwise 404 |

Both `/orders` endpoints need the `buyer` role and only ever see the caller's own orders (`WHERE … AND user_id = <token sub>`): another user's token behaves exactly like an unknown one. A token that is not a UUID is 400. Nginx exposes `/orders/*` and `/sales/*` on :8080 and nothing else.

`/admin/**` needs the `admin` role. order-service is additionally published on `127.0.0.1` only.

## 10. Request flows

### Buy (sale-service `BuyService`)

1. If the local sold-out flag is set for this sale → 410 immediately (no Redis call).
2. Run `buy.lua` with keys/args from §7.
3. On 0, set the local sold-out flag. Clear ALL flags every 5 s with `@Scheduled` (stock can come back).
4. On 1: generate a UUID `orderToken`, send `OrderCreatedEvent` synchronously.
5. If the send fails, compensate — `INCR stock`, `SREM buyers userId`, clear the sold-out flag for the sale — and return 503:
   - Kafka rejects the record (`send()` throws, e.g. no broker within `max.block.ms` = 2 s) or reports failure → compensate immediately.
   - No answer within 2 s → return 503 but keep the unit reserved; compensate only if Kafka later reports a final failure (bounded by `delivery.timeout.ms` = 10 s). A late success becomes a normal order, so the unit is never sold twice.
   - The producer is idempotent (`enable.idempotence=true`).

### Order creation (order-service `OrderConsumer`)

```sql
INSERT INTO orders (order_token, sale_id, user_id, status)
VALUES (?, ?, ?, 'PENDING_PAYMENT')
ON CONFLICT DO NOTHING
```

The listener runs 3 consumers (one per partition). Errors are handled by `KafkaConfig.kafkaErrorHandler`:

- Transient failures (database down) are retried with exponential backoff — 0.5 s doubling to 30 s, for up to 5 minutes.
- Records that can never succeed (malformed JSON, bad ids or token, unknown sale) are not retried.
- Either way, a record that is given up on is published to **`order-created.DLT`** with the error, logged at ERROR and counted in `flashsale_orders_dead_lettered_total{reason}`; the partition moves on, so one bad record never blocks the others.

**What a dead letter means.** The buyer got `202` and holds a unit in Redis, but has no order row, so reconciliation reports `redis_low` until it is dealt with.

**Operating it** (both need the `admin` role):

- `GET /admin/dead-letters?limit=100` — records not yet replayed: partition, offset, key, payload, error, original topic and offset.
- `POST /admin/dead-letters/replay` → `{"replayed": n}` — fix the cause first (deploy the fix, create the missing sale, bring the database back), then replay. It re-publishes each pending record's payload and key to `order-created` and remembers its position (consumer group `order-service-dlt-replay`), so each record is replayed once. The consumer is idempotent, so replaying something that was in fact processed changes nothing; a record that still fails simply returns to the DLT.
- A record that can never be fixed (garbage payload) comes back on every replay. Discard it by moving the replay group past it: `kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group order-service-dlt-replay --topic order-created.DLT --reset-offsets --to-latest --execute`, then release the buyer's unit by hand if needed.

### Payment timeout (order-service `PaymentTimeoutWorker`)

Every 10 s, in batches of 500, one atomic statement per batch:

```sql
WITH due AS (
  SELECT id FROM orders
  WHERE status='PENDING_PAYMENT' AND created_at < now() - make_interval(mins => ?)
  ORDER BY created_at LIMIT ?
  FOR UPDATE SKIP LOCKED
)
UPDATE orders o SET status='CANCELLED', updated_at=now()
FROM due WHERE o.id = due.id AND o.status='PENDING_PAYMENT'
RETURNING o.sale_id
```

Only after that statement has committed: `INCR sale:{saleId}:stock` for each returned row. If the process dies in between, Redis ends up one unit low, which can lose a sale but never oversell; reconciliation reports it as `redis_low`. Incrementing before the commit would risk the opposite.

The timeout is `orders.payment-timeout-minutes` (`PAYMENT_TIMEOUT_MINUTES` in `.env`; default 10, use 1 when testing). A pay and a cancel racing for the same order are both conditional on `status='PENDING_PAYMENT'`, so exactly one wins.

### Reconciliation (order-service `ReconciliationJob`)

Every 60 s, for each sale currently within its window: `expected = total_stock − count(PENDING_PAYMENT, PAID)`, `actual = Redis stock`.

- `actual > expected` → log ERROR (oversell risk) and increment metric `flashsale_reconcile_mismatch_total{direction="redis_high"}`.
- `actual < expected` → log WARN (events in flight or lost), metric with `direction="redis_low"`. Do not auto-correct; only report.

## 11. Rate limiting

- **Per user (sale-service):** `HandlerInterceptor` on `/sales/*/buy`. Fixed window: `INCR rl:{userId}:{epochSecond}`, `EXPIRE 2` on first hit (both in `scripts/rate_limit.lua`, so they are atomic), reject with 429 above 5.
- **Per IP (Nginx):** `limit_req_zone $binary_remote_addr zone=perip:10m rate=50r/s;` with `burst=100 nodelay`, `limit_req_status 429`. The limit applies to the buy route only and lives in `infra/nginx-ratelimit.conf`, which `nginx.conf` includes. For k6 runs (all k6 traffic is one IP) either comment out its `limit_req` line, or start Nginx with `docker-compose.loadtest.yml`, which mounts the empty `loadtest/nginx-no-ratelimit.conf` in its place.
- **TLS (Nginx):** HTTPS on :8443 with TLS 1.2/1.3 only, ECDHE AEAD ciphers, no session tickets, HTTP/2. Every response carries HSTS (except for `localhost` and `127.0.0.1`: HSTS applies to a host name on every port, so on a developer machine it would force Grafana on :3000 and Keycloak on :8180 onto HTTPS and break them), `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer` and `Content-Security-Policy: default-src 'none'`. Port 8080 only answers `308` to the HTTPS URL (308 keeps the method, so a POST is not turned into a GET). The certificate is in `infra/tls/` (git-ignored): `scripts/dev-cert.sh` creates a local CA and a `localhost` certificate; production puts a real one there. Inside the Docker network traffic stays plain HTTP (a private network with no published ports).
- **Nginx routing (`infra/nginx.conf`):** `POST /sales/{id}/buy` and `GET /sales/{id}/stock` go round-robin to `sale-1`/`sale-2`; `/orders/{uuid}` and `/orders/{uuid}/pay` go to order-service; every other path (including `/admin/**` and `/actuator/**`) is 404. Upstreams use Docker DNS with `resolve`, so Nginx keeps running while an instance is down. Upstream connections are reused (`keepalive 512`, `keepalive_requests 100000`); an instance is skipped for 5 s after 3 failed connects (2 s connect timeout), so a burst cannot take both out of rotation. A failed connect is retried on the other instance, but a request that reached an instance is never resent, so a buy is never sent twice.

## 12. Configuration

Each service reads env vars with local defaults so it runs from the IDE AND in Docker:

| Var | sale-service default | order-service default | Docker value |
| :---- | :---- | :---- | :---- |
| `PORT` | 8081 | — (fixed 8090) | 8080 for sale containers |
| `REDIS_HOST` | localhost | localhost | redis |
| `KAFKA` | localhost:9092 | localhost:9092 | kafka:29092 |
| `DB_HOST` | — | localhost | postgres |
| `MANAGEMENT_PORT` | 9081 | 9091 | 9080 for sale containers |
| `REDIS_SALE_PASSWORD` / `REDIS_ORDER_PASSWORD` | sale-service's Redis user (`sale-service`) | order-service's Redis user (`order-service`) | from `.env` |
| `DB_USER` / `DB_PASSWORD` | — | from `.env` | from `.env` |
| `RESET_ENABLED` | — | false | from `.env` |
| `OIDC_ISSUER` | http://localhost:8180/realms/stockrush | same | same (Keycloak's public URL, `KC_HOSTNAME`) |
| `OIDC_JWK_SET_URI` | http://localhost:8180/realms/stockrush/protocol/openid-connect/certs | same | http://keycloak:8080/realms/stockrush/protocol/openid-connect/certs |
| `LOADTEST_JWT_SECRET` | — (off) | — (off) | only via `docker-compose.loadtest.yml` |

Both services: Tomcat `accept-count: 1000`, `max-keep-alive-requests: 100000`, `keep-alive-timeout: 60s`, so a burst queues instead of being dropped and Nginx's upstream connections stay open.

Secrets have no defaults: a service refuses to start without them. Locally each service imports the repo's `.env` (`spring.config.import: optional:file:.env[.properties]` and `../.env`); in Docker compose passes the same values as environment variables. Compose binds every published port to `127.0.0.1`. Redis and Lettuce use a 1 s command and connect timeout.

Kafka listeners in compose: `INTERNAL://kafka:29092` (containers), `EXTERNAL://localhost:9092` (host), `CONTROLLER://:9093`; `KAFKA_INTER_BROKER_LISTENER_NAME=INTERNAL`; all replication factors 1.

Both services: Actuator with `health,prometheus` exposed on the separate `MANAGEMENT_PORT` only (the public port returns 404 for `/actuator/**`), liveness/readiness probes on, graceful shutdown, `micrometer-registry-prometheus`. Error responses never include messages or stack traces. sale-service: `management.metrics.distribution.percentiles-histogram.http.server.requests=true`. Both main classes: `@EnableScheduling`.

## 13. Metrics

- `flashsale_buy_total{result="SUCCESS|SOLD_OUT|ALREADY_BOUGHT|NOT_ACTIVE|TRY_AGAIN"}` (sale-service)
- `flashsale_orders_placed_total`, `flashsale_orders_expired_total`, `flashsale_orders_paid_total` (order-service). The spec originally said `flashsale_orders_created_total`, but the Prometheus client strips a trailing `_created` (an OpenMetrics reserved suffix), so that name cannot be exported.
- `flashsale_reconcile_mismatch_total{direction}` (order-service)
- `flashsale_orders_dead_lettered_total{reason}` (order-service): records parked on `order-created.DLT`, by reason — one of `invalid_event`, `integrity_violation`, `undeserializable`, `retries_exhausted`, all registered at 0 on startup so the first event is visible to `increase()`. The exact exception is in the log and the record headers

Prometheus (`infra/prometheus.yml`) scrapes `sale-1:9080`, `sale-2:9080` and `order-service:9091` every 5 s. Grafana provisions the datasource and the **StockRush — Flash Sale** dashboard from `infra/grafana/`. Its stat panels count exactly within the selected time range (`max_over_time − min_over_time`), not with `increase()`, whose extrapolation would show e.g. 313 for 300 orders.

### Logs and request ids

- Nginx gives every request an id (`$request_id`), always its own (a client cannot choose the id it is logged under), passes it upstream as `X-Request-Id` and returns it to the client. Its access log is one JSON line per request with that id, status, timing and the upstream instance.
- Both services keep that id (or generate one when called directly), put it in the log context as `request_id` before Spring Security runs (so rejected requests are traceable too) and return it.
- sale-service copies it onto the Kafka record as a `request_id` header (`RequestIdProducerInterceptor`); order-service restores it while processing the record (`RequestIdRecordInterceptor`), and a dead-lettered record keeps it. One id therefore links the Nginx line, sale-service's `Unit won` line and order-service's `Order created` line.
- In Docker the services log one JSON object per line in Elastic Common Schema (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`), ready for Loki, Elasticsearch or CloudWatch; local runs keep plain text with the id in brackets. Only winners are logged at INFO, never the flood of sold-out answers.

### Alerts

Rules in `infra/alerting/alerts.yml`, loaded by Prometheus and sent to Alertmanager (http://localhost:9093). `infra/alerting/alerts.test.yml` unit-tests them with `promtool`, and CI runs those tests on every pull request.

| Alert | Fires when | Severity |
| :---- | :---- | :---- |
| `OversellRisk` | any `redis_high` reconciliation mismatch in 5 min | critical |
| `ServiceDown` | a sale-service or order-service instance cannot be scraped for 1 min | critical |
| `StockHeldWithoutOrders` | ≥ 5 `redis_low` mismatches in 10 min (more than in-flight events explain) | warning |
| `OrderEventsDeadLettered` | any event parked on `order-created.DLT` in 5 min, per reason | warning |
| `HighServerErrorRate` | > 2 % of a service's requests are 5xx for 2 min (the normal flood of 410s is not an error) | warning |
| `OrderEventsNotPublished` | winners keep getting `503 TRY_AGAIN` (Kafka unreachable) for 2 min | warning |
| `BuyLatencyHigh` | buy p95 > 1 s for 5 min | warning |
| `OrderConsumerLagging` | > 500 events behind on `order-created` for 2 min | warning |

Every counter an alert relies on is registered at 0 when its service starts (`redis_high`/`redis_low`, and the four bounded dead-letter reasons). A counter that first appears already at 1 is invisible to `increase()`: a live drill showed the first dead-lettered event, and therefore the first oversell signal, would otherwise never alert. Alertmanager has no receiver configured (nothing to send to on a developer machine); `alertmanager.yml` explains how to add Slack, e-mail or PagerDuty. While a service is down its warnings are inhibited. Prometheus reads rules at startup: after editing them, `docker compose restart prometheus`.

Grafana panels (document the PromQL in README):

- `sum by (result) (rate(flashsale_buy_total[30s]))`
- `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{uri="/sales/{saleId}/buy"}[1m])))`
- `sum by (instance) (rate(http_server_requests_seconds_count[30s]))`

## 14. Non-negotiable rules

1. Stock is decremented only inside `buy.lua`.
2. sale-service never touches PostgreSQL.
3. Every Kafka consumer write is idempotent.
4. State changes on orders are conditional updates (`WHERE status = ...`), never read-then-write.
5. No secrets in the repository. Every password comes from the git-ignored `.env` (template: `.env.example`).
6. Every phase ends with its acceptance checks passing and a git commit.
7. Nothing is published beyond `127.0.0.1` except Nginx; services connect with least-privilege credentials (§17).

## 15. Build phases

### Phase 1 — Infrastructure and skeletons

- `docker-compose.yml` with postgres, redis, kafka; the schema (originally `infra/schema.sql`, now Flyway migrations in `infra/db/migration`).
- Generate both Spring Boot apps (Web, Data Redis, Kafka, Actuator, Validation; order-service also JPA + PostgreSQL driver).
- application.yml for both per §12.
- order-service: `FlashSale` and `PurchaseOrder` entities + repositories, admin warm-up and reset endpoints.
- sale-service: `GET /sales/{id}/stock`.

**Accept:** `docker compose up -d` healthy; `POST :8090/admin/sales/1/warmup` → stock 100; `GET :8081/sales/1/stock` → 100.

### Phase 2 — Atomic buy path

- `buy.lua`, `RedisConfig` (script bean + `NewTopic`), `BuyService`, `SaleController`, `UserRateLimiter` + `WebMvcConfigurer`.
- Unit test for `BuyService` outcome → HTTP mapping. Integration test with Testcontainers (Redis) that fires 200 concurrent buys at stock 10 and asserts exactly 10 successes.

**Accept:** with stock set to 10 and services run from the host:

```sh
curl -X POST -H "Authorization: Bearer $ADMIN" localhost:8090/admin/sales/1/reset
curl --cacert infra/tls/ca.crt -X POST -H "Authorization: Bearer $TOKEN" https://localhost:8443/sales/1/buy
docker compose exec redis redis-cli SET sale:1:stock 10
seq 1 200 | xargs -P 50 -I{} curl -s -o /dev/null -w "%{http_code}\n" \
  -X POST -H "X-User-Id: {}" localhost:8081/sales/1/buy | sort | uniq -c
```

(As built in Phase 2, before Step B. Since Step B the same race runs as `k6 run -e LOADTEST_JWT_SECRET -e STOCK=10 -e VUS=50 -e ITERATIONS=200 loadtest/flash-sale.js` against the load-test stack, and the reset needs an admin token — see §16.)

prints exactly `10 202` and `190 410`; `SCARD sale:1:buyers` = 10; same user twice → 409; 6+ calls/second from one user → 429.

### Phase 3 — Order pipeline, payment, timeout, reconciliation, scale-out

- `OrderConsumer`, `OrderController` (status + pay), `PaymentTimeoutWorker`, `ReconciliationJob`.
- Dockerfiles (`FROM eclipse-temurin:17-jre`, copy jar), compose services `sale-1`, `sale-2`, `order-service`, `nginx`; `infra/nginx.conf`.

**Accept:** race test through Nginx (`localhost:8080` at the time; HTTPS on `localhost:8443` since Step C) (Nginx limit commented out) still gives exactly 10 winners; 10 `PENDING_PAYMENT` rows; pay 3 (`POST /orders/{token}/pay` as the owner); with timeout = 1 min the other 7 become `CANCELLED` and Redis stock returns to 7; `docker compose stop sale-1` and buying still works.

### Phase 4 — Load test, monitoring, docs

- `loadtest/flash-sale.js`: k6 `shared-iterations`, 1000 VUs, 20000 iterations, a distinct user per iteration (`__VU * 100000 + __ITER`, sent as that user's bearer token since Step B), custom counters `orders_won` (202) and `sold_out` (410), check `status < 500`.
- Metrics per §13, `infra/prometheus.yml`, prometheus + grafana in compose.
- README: summary + headline result, architecture diagram, how to run, request flow, design decisions & trade-offs (Lua vs `SELECT ... FOR UPDATE` vs optimistic locking; why Kafka; idempotency; sold-out flag; failure scenarios), results table, Grafana screenshot, limitations & next steps.

**Accept:** after `reset` with stock 100, `k6 run loadtest/flash-sale.js` reports `orders_won` = 100 and `SELECT status, count(*) FROM orders WHERE sale_id=1 GROUP BY status` totals 100; Redis stock = 0; repeatable 3 runs in a row. Save the k6 summary to `docs/loadtest-results.md`.

## 16. Commands cheat-sheet

```sh
cp .env.example .env                      # once; then change the passwords
scripts/dev-cert.sh                       # once: local CA + certificate for https://localhost:8443
TOKEN=$(scripts/token.sh buyer1)          # demo users: buyer1, buyer2, shopadmin (password DEMO_USER_PASSWORD)
ADMIN=$(scripts/token.sh shopadmin)
docker compose up -d                      # start everything
docker compose up -d --build              # rebuild services after code changes
curl -X POST localhost:8090/admin/sales/1/reset
docker compose -f docker-compose.yml -f docker-compose.loadtest.yml up -d   # per-IP limit off, load-test issuer on
k6 run -e LOADTEST_JWT_SECRET loadtest/flash-sale.js   # after `set -a; . ./.env; set +a`; or: docker run --rm -i --network host -e LOADTEST_JWT_SECRET -v "$PWD/loadtest:/scripts:ro" grafana/k6:1.2.0 run /scripts/flash-sale.js
docker compose up -d                      # back to normal: per-IP limit on, load-test issuer off
docker compose exec postgres psql -U postgres -d flashsale
docker compose exec redis redis-cli     # authenticates via REDISCLI_AUTH
docker compose down -v                    # wipe data (only needed after changing DB passwords)
docker compose up db-migrate              # apply new migrations to the running database
docker compose run --rm db-migrate info   # show migration history
```

## 17. Security

### In place (Step A)

| Threat | Control |
| :---- | :---- |
| Infrastructure reachable from the network | Every compose port bound to `127.0.0.1` |
| Eavesdropping / tampering on the public API | HTTPS only (TLS 1.2/1.3), HSTS, HTTP → 308 redirect |
| Anyone editing stock or flushing Redis | Redis ACL users (`infra/redis/users.acl.template`): `sale-service` may only touch `sale:*`/`rl:*` with the commands its Lua scripts use; `order-service` only `sale:*` with GET/SET/INCR/DEL; neither can FLUSHALL, KEYS, CONFIG or run anything else. The operator account (`default`, `REDIS_PASSWORD`) is for humans only. Passwords come from `.env` and are rendered into the ACL at container start |
| App able to drop tables / read everything | Least-privilege `DB_USER`; schema owned by `flashsale_migrator`, used only by the one-shot `db-migrate` container; superuser only creates roles |
| Secrets in git | `.env` git-ignored; no defaults for secrets; `.env.example` only |
| Metrics and health leaking internals | Actuator on an internal management port |
| Accidental data wipe | `reset` endpoint absent unless `RESET_ENABLED=true` |
| Malformed or hostile input | Positive `saleId` (400); token claims validated (401) |
| Hung threads / cascading failure on Redis outage | 1 s Redis timeouts, clean 503 + `Retry-After` |
| Rate-limit counter without TTL | Atomic `rate_limit.lua` |
| Instance clock skew deciding the sale window | `buy.lua` uses Redis `TIME` |
| Stack traces in responses | `server.error.include-message/stacktrace: never` |
| Tampered build tool | Maven wrapper pinned with `distributionSha256Sum` (verified against Apache's SHA-512) |
| Vulnerable dependencies / code | GitHub Actions CI, CodeQL (`security-extended`), Dependabot, Trivy image scan (fails on fixable CRITICAL) |
| Known CVEs in bundled libraries | Stay on a supported Spring Boot line (4.x) and take its managed Tomcat, Netty and Spring versions; override a version in the pom only when Trivy finds a fixable CRITICAL that Spring Boot has not shipped yet, and remove the override at the next Boot bump. `.trivyignore.yaml` lists findings that do not apply, each with its reason and an expiry date after which the gate fails again (empty today) |
| Personal data in logs | Logs never contain order tokens, user ids or event payloads (CodeQL `java/sensitive-log`); the request id links log lines instead, and dead-lettered records keep the full payload on the DLT for admins |
| Stray topics | Kafka `auto.create.topics.enable=false` |
| Compromised app container | Runs as non-root `app` user, read-only filesystem, `cap_drop: ALL`, `no-new-privileges`, 512 MB memory limit |
| Internal endpoints reachable from outside | Nginx routes only `/sales/*` and `/orders/*`; sale containers are not published; order-service on `127.0.0.1` only |
| Slow or oversized requests | Nginx: 1 KB body limit, 5 s header/body timeouts, 1 s upstream connect timeout |
| Reading or paying someone else's order | `/orders` queries filter on the token subject |
| Monitoring exposed | Prometheus and Grafana bound to `127.0.0.1`; Grafana admin password from `.env`, sign-up, anonymous access and phone-home analytics off |

### Step B — identity with Keycloak (in place)

| Threat | Control |
| :---- | :---- |
| Anyone claiming to be any user | User id is the `sub` of a Keycloak access token; the `X-User-Id` header is gone |
| Forged or tampered tokens | RS256 signature checked against the realm's JWKS; `alg=none` and edited payloads → 401 |
| Tokens for another app or realm | `iss` must equal `OIDC_ISSUER` and `aud` must contain `stockrush-api` (an audience mapper on both clients adds it) |
| Stolen tokens living forever | 5-minute access tokens |
| Buyers using operator endpoints | `/admin/**` needs the `admin` realm role; operators cannot buy |
| Password guessing | Keycloak brute-force protection (lockout after 5 failures), 12-character minimum |
| Shared database blast radius | Keycloak has its own database and role; it cannot connect to `flashsale` |
| Cross-site request forgery | CSRF protection is on for every state-changing request that carries cookies (what a forged browser request carries). The API itself uses no cookies or sessions — only `Authorization: Bearer`, which browsers never add automatically — so token-less API calls get a clean 401/403 rather than a CSRF error |

- Realm `stockrush` is imported from `infra/keycloak/stockrush-realm.json`. Clients: `stockrush-web` (browser, authorization code + PKCE) and `stockrush-cli` (password grant, **local development only** — remove in production). Demo users `buyer1`, `buyer2` (`buyer`) and `shopadmin` (`admin`) get `DEMO_USER_PASSWORD` from `.env` at import. `scripts/token.sh <user>` prints a token.
- Keycloak runs `start-dev` (plain HTTP) for local use. `KC_HOSTNAME=http://localhost:8180` fixes the issuer, so tokens fetched on the host validate inside Docker, where the services read keys from `keycloak:8080`.
- **Load-test issuer.** k6 cannot log in 20 000 Keycloak users. `docker-compose.loadtest.yml` sets `LOADTEST_JWT_SECRET` on the services, which then also accept HS256 tokens with `iss = stockrush-loadtest`, signed with that secret (same audience, expiry and subject rules). Without the secret — the normal stack — such tokens are rejected (verified: 401). Services log a WARN at startup whenever it is on.
- Rate limits apply per authenticated account; Nginx keeps the per-IP limit.
- Schema change: `orders.user_id` is a UUID. (Before Flyway, this needed a database rebuild; such changes are now migrations.)

### Step C — after Phase 4

- ~~Virtual waiting room~~ — done: see §18. CAPTCHA at sign-up (Keycloak supports reCAPTCHA on registration).
- ~~TLS at Nginx~~ — done: see §11. ~~Redis ACL users per service~~ — done: see the table above. Kafka SASL (Kafka is reachable only on the Docker network and `127.0.0.1`).
- ~~Dead-letter topic for the consumer~~ — done: see §10, Order creation.
- ~~Flyway migrations~~ — done: see §5.
- Keycloak in production mode (`start`, TLS, a real hostname); drop the `stockrush-cli` password-grant client.
- ~~Image scanning in CI~~ — done: `.github/workflows/image-scan.yml` builds both images on every pull request, on `main` and weekly, scans them with Trivy (OS packages and the libraries inside the jar), uploads fixable HIGH/CRITICAL findings to Security → Code scanning and fails the build on any fixable CRITICAL.
- Branch protection on `main` (a repository setting: require pull requests and the CI, CodeQL and image-scan checks).
- ~~Structured JSON logs with request ids~~ — done: see §13 Logs and request ids.
- ~~Alerts~~ — done: see §13 Alerts.

## 18. Virtual waiting room

A sale can be sold through a waiting room (`flash_sales.waiting_room`, migration V4; demo: sale 2). Then nobody can buy until they have queued and been let in, which takes the advantage away from bots and from whoever's request happens to land first.

**API** (role `buyer`, through Nginx):

| Call | Answer |
| :---- | :---- |
| `POST /sales/{id}/queue` | join: `{"status":"WAITING","position":n}`, or `ADMITTED`, `NO_WAITING_ROOM` (the sale has none: buy directly), `SOLD_OUT` (410), `SALE_NOT_ACTIVE` (403) |
| `GET /sales/{id}/queue` | the same, without joining; `NOT_IN_QUEUE` (404) if the caller never joined. Clients poll it about once a second (the per-user limit of 5 requests/s applies) |
| `POST /sales/{id}/buy` before being admitted | `403 {"status":"NOT_ADMITTED"}` |

**How it works** (all in Redis, all atomic Lua):

- `queue.lua` adds the user to the sorted set `sale:{id}:queue`. Anyone who joins **before the sale opens** gets a random score in [0, 10⁹): a lottery, so being a millisecond faster gains nothing. Anyone joining **after** it opens gets 10⁹ + milliseconds since the start (microsecond precision), so they queue behind the lottery in arrival order. Joining twice keeps one's place.
- `admit.lua` runs every second on every sale-service instance (`AdmissionScheduler`). An `INCR` on the per-second key `sale:{id}:admit:{epochSecond}` lets only the first caller in each second act; it then moves the next `flashsale.waiting-room.admit-per-second` users (default 200) from the queue into the set `sale:{id}:admitted`. Nobody is admitted before the sale opens or once stock is 0 (waiting users then see `SOLD_OUT`).
- `buy.lua` checks the gate first: if `sale:{id}:gate` exists and the user is not in `sale:{id}:admitted`, it returns −3 (`NOT_ADMITTED`). The check is inside the same atomic script as the stock decrement, so it cannot be raced.
- order-service's warm-up sets `sale:{id}:gate` and adds the sale to `sale:gated` (the list the admission job reads) when `waiting_room` is true, and removes both otherwise; reset empties the queue and the admitted set.

**Load test** (`loadtest/waiting-room.js`): 2,000 honest buyers join, poll and buy once admitted, while 200 bots call buy directly. Exactly 100 honest buyers won, all 200 bot attempts got `NOT_ADMITTED` (0 bot orders in PostgreSQL), 757 waiting buyers were told `SOLD_OUT` instead of hammering buy, 0 errors.

**Trade-off.** Admission is a fixed rate, not tied to remaining stock, so more people are admitted than there are units (1,243 for 100 in the load test); the extra admitted buyers simply get `410`. Admitting in proportion to remaining stock is a possible refinement. The waiting room limits who may buy; one-account-per-person still depends on account creation (CAPTCHA at sign-up is outside this project).

## 19. Shop page (web/)

A single static page that a buyer uses to log in, watch the stock, buy, wait in the waiting room and pay. Plain HTML, CSS and JavaScript: no framework, no third-party code, no build step. Nginx serves it at `https://localhost:8443/` from a read-only mount of `web/`, on the same origin as the API, so there is no CORS on the API.

**Login.** OpenID Connect authorization code flow with PKCE (S256) against Keycloak's public client `stockrush-web`, written by hand in `app.js` (about 80 lines). The PKCE verifier, the `state` and the chosen sale wait in `sessionStorage` only for the round trip to Keycloak; `state` is checked on return. Access, refresh and ID tokens are kept **in memory only**: no script can find them in `localStorage` or cookies, and a reload goes back through Keycloak (instant while its session is alive). The access token is refreshed 30 s before it expires; logout ends the Keycloak session.

**Calls.** Every API call sends `Authorization: Bearer …` and `credentials: "omit"`. Cookies ignore ports, so without that a cookie for `localhost` from another local app (Grafana's, for example) would reach the services and switch on their CSRF check for cookie-carrying requests (§17).

**Flow.**

1. The page polls `GET /sales/{id}/stock` every 2 s while it is visible.
2. Buy sends `POST /sales/{id}/buy`, with these outcomes:
   - `202`: the order token is kept in `sessionStorage` (per sale and user) and the page polls `GET /orders/{token}` until the order leaves `PROCESSING`.
   - `NOT_ADMITTED`: the page joins the waiting room (`POST /sales/{id}/queue`), polls the queue every 2 s, and buys as soon as it is `ADMITTED`.
   - `410`, `409`, `SALE_NOT_ACTIVE` and `429` each get a plain message.
3. `PENDING_PAYMENT` shows a Pay button, which sends `POST /orders/{token}/pay`.

The page treats the sale as sold out only when the server says so (`410`). A stock of 0 can also mean the sale has not been warmed up yet, and buy then answers `SALE_NOT_ACTIVE`.

**Headers.** Nginx picks the Content Security Policy by path:

- The page's four files get: `default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self' http://localhost:8180; base-uri 'none'; form-action 'none'; frame-ancestors 'none'`. The Keycloak origin must match `stockrush-issuer` in `web/index.html` and `KC_HOSTNAME`.
- The API keeps `default-src 'none'`.
- The page's files are sent with `Cache-Control: no-cache`, so a new version shows at once.

**Keycloak.** `stockrush-web` allows only `https://localhost:8443/*` and `https://127.0.0.1:8443/*` as redirect and post-logout URLs, and the same origins for CORS on the token endpoint. Keycloak imports the realm file only when the realm does not exist, so an existing installation is updated with `scripts/keycloak-web-client.sh`, which uses `kcadm` and is safe to re-run.

**Tested** in Chromium (Playwright) against the running stack. These cases passed:

- login, buy, reserve, pay, and resuming the order after a reload;
- logout;
- the waiting room on sale 2: `403` → queue → admitted → `202`;
- already bought, a non-buyer account, sold out, and a sale that is not open.

In every run, zero cookies reached the API and there were no console or CSP errors.

## 20. Ops assistant (optional, OpenAI)

An admin asks `POST /admin/assistant/summary` (order-service, `admin` role, not routed by Nginx) and gets a short plain-text status report written by an OpenAI chat model: overall status, problems with likely causes, and next steps using the admin endpoints.

**What is sent.** Only `OpsFacts`, built by `OpsFactsCollector`. `GET /admin/assistant/facts` returns exactly the same snapshot without calling the AI. It contains:

- Per sale: window state, total stock, stock in Redis, order counts by status, and `unaccounted = totalStock - (stockInRedis + PENDING_PAYMENT + PAID)`. 0 is healthy; positive means lost orders; negative means an oversell.
- Dead letters counted by exception class (`DeadLetterService.summary()`). Never keys, payloads or exception messages: database and JSON errors quote the event's values.
- Firing alerts from Alertmanager's v2 API: name, severity, summary and start time only.
- `unavailable`: sources that could not be read. One failing source never fails the snapshot.

No user id, order token or other personal data. A test fails if any UUID appears in what is sent.

**Safeguards.**

- Off unless `OPENAI_API_KEY` is set in `.env`; the key is never logged or returned (`AssistantProperties.toString()` masks it).
- At most one AI call per 30 s, failed calls included, as a cost guard.
- Output is capped at 600 tokens and the request has a 30 s timeout.
- Provider errors return only the HTTP status and error code, because OpenAI's error messages can echo part of the key.
- The model is configurable (`OPENAI_MODEL`, default `gpt-4o-mini`), as is the API root (`OPENAI_BASE_URL`, for OpenAI-compatible providers).

**Tested** against a fake OpenAI endpoint and a fake Alertmanager (`AssistantTest`, 6 tests):

- aggregates only, with no UUIDs;
- the exact request sent (bearer key, model, instructions, snapshot);
- error mapping without the key;
- failed sources listed rather than fatal;
- admin role required;
- off without a key, and the rate limit.

