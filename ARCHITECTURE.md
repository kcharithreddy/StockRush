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
| Language / framework | Java 17, Spring Boot 3.x, Maven (use the Maven wrapper `./mvnw`) |
| Hot state / atomic stock | Redis 7 with AOF (`--appendonly yes`), Lua scripts |
| Async order pipeline | Apache Kafka 3.8 (KRaft mode, no ZooKeeper), image `apache/kafka:3.8.0` |
| Database | PostgreSQL 16 |
| Load balancer | Nginx 1.27 |
| Containers | Docker Compose (single `docker-compose.yml` at repo root) |
| Load testing | k6 |
| Monitoring | Micrometer + Prometheus + Grafana |

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
│   ├── schema.sql
│   ├── db-init/02-app-user.sh   # least-privilege DB role
│   ├── nginx.conf
│   └── prometheus.yml
├── sale-service/            # Spring Boot app, package com.flashsale.sale
│   ├── Dockerfile
│   └── src/main/resources/scripts/buy.lua
├── order-service/           # Spring Boot app, package com.flashsale.order
│   └── Dockerfile
├── loadtest/
│   └── flash-sale.js
└── docs/                    # load test results, Grafana screenshots
```

## 5. Data model (infra/schema.sql — mounted into Postgres init)

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
  user_id     BIGINT NOT NULL,
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

The tables are owned by the `postgres` superuser, which only initialises the database. The services connect as `DB_USER` (`infra/db-init/02-app-user.sh`): `SELECT` on `products` and `flash_sales`, `SELECT/INSERT/UPDATE/DELETE` on `orders`, no DDL, not a superuser.

## 6. Redis keys

| Key | Type | Meaning | Written by |
| :---- | :---- | :---- | :---- |
| `sale:{saleId}:stock` | string (int) | units left to win | order-service (warm-up, restore), Lua (decrement) |
| `sale:{saleId}:buyers` | set of userId | users who already won | Lua |
| `sale:{saleId}:window` | string `"startMillis,endMillis"` | sale active window | order-service warm-up |
| `rl:{userId}:{epochSecond}` | string (int), TTL 2 s | per-user rate-limit counter | sale-service |

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

- Topic `order-created`: 3 partitions, replication 1, created via a `NewTopic` bean in sale-service.
- Message key: `userId` as string. Value: JSON string.
- Event: `record OrderCreatedEvent(String orderToken, long saleId, long userId)`
- Producer: `acks=all`, String serializers. The send is synchronous with a 2 s timeout (`.get(2, SECONDS)`).
- Consumer group: `order-service`, `auto-offset-reset=earliest`. Delivery is at-least-once; the consumer MUST be idempotent.

## 9. API contracts

User identity is a mock header `X-User-Id: <long>` (README notes that production would use JWT).

### sale-service (behind Nginx at [http://localhost:8080](http://localhost:8080))

`POST /sales/{saleId}/buy` (header `X-User-Id` required)

| Lua result | HTTP | Body |
| :---- | :---- | :---- |
| 1 and Kafka send OK | 202 | `{"status":"PROCESSING","orderToken":"<uuid>"}` |
| 0 | 410 | `{"status":"SOLD_OUT"}` |
| -1 | 409 | `{"status":"ALREADY_BOUGHT"}` |
| -2 | 403 | `{"status":"SALE_NOT_ACTIVE"}` |
| 1 but Kafka send failed or timed out | 503 | `{"status":"TRY_AGAIN"}` |
| Redis unavailable | 503 + `Retry-After: 1` | `{"status":"TRY_AGAIN"}` |
| missing, non-numeric or non-positive `X-User-Id` | 401 | — |
| `saleId` ≤ 0 | 400 | — |
| > 5 req/s for this user | 429 + `Retry-After: 1` | — |

`GET /sales/{saleId}/stock` → `{"stock": <int>}` (reads Redis)

### order-service ([http://localhost:8090](http://localhost:8090))

| Method & path | Behaviour |
| :---- | :---- |
| `GET /orders/{token}` | `{"status": "PENDING_PAYMENT" | "PAID" | "CANCELLED" | "PROCESSING"}` — PROCESSING if the caller has no such order (yet) |
| `POST /orders/{token}/pay` | `UPDATE ... SET status='PAID' WHERE order_token=? AND user_id=? AND status='PENDING_PAYMENT'`; 200 `{"status":"PAID"}` if 1 row, else 409 `{"status":"NOT_PAYABLE"}` |
| `POST /admin/sales/{id}/warmup` | stock in Redis = total_stock − count(orders PENDING_PAYMENT or PAID); sets `window`; returns `{"saleId","stock"}` |
| `POST /admin/sales/{id}/reset` | TEST ONLY: delete the sale's orders, delete `stock` and `buyers` keys, then warm-up. Exists only when `RESET_ENABLED=true`; otherwise 404 |

Both `/orders` endpoints require `X-User-Id` (401 otherwise) and only ever see the caller's own orders: another user's token behaves exactly like an unknown one. A token that is not a UUID is 400. Nginx exposes `/orders/*` and `/sales/*` on :8080 and nothing else.

Admin endpoints are not authenticated until Step B of §17 (Keycloak `admin` role). Until then order-service must never be published beyond `127.0.0.1` or the Docker network.

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
- Records that can never succeed (malformed JSON, bad ids or token, unknown sale) are logged and skipped at once, so one bad record cannot block a partition. Step C replaces the skip with a dead-letter topic.

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
- **Per IP (Nginx):** `limit_req_zone $binary_remote_addr zone=perip:10m rate=50r/s;` with `burst=100 nodelay`, `limit_req_status 429`. The limit applies to the buy route only. The README must say to comment out the `limit_req` line for k6 runs (all k6 traffic is one IP).
- **Nginx routing (`infra/nginx.conf`):** `POST /sales/{id}/buy` and `GET /sales/{id}/stock` go round-robin to `sale-1`/`sale-2`; `/orders/{uuid}` and `/orders/{uuid}/pay` go to order-service; every other path (including `/admin/**` and `/actuator/**`) is 404. Upstreams use Docker DNS with `resolve`, so Nginx keeps running while an instance is down. A failed connect is retried on the other instance, but a request that reached an instance is never resent, so a buy is never sent twice.

## 12. Configuration

Each service reads env vars with local defaults so it runs from the IDE AND in Docker:

| Var | sale-service default | order-service default | Docker value |
| :---- | :---- | :---- | :---- |
| `PORT` | 8081 | — (fixed 8090) | 8080 for sale containers |
| `REDIS_HOST` | localhost | localhost | redis |
| `KAFKA` | localhost:9092 | localhost:9092 | kafka:29092 |
| `DB_HOST` | — | localhost | postgres |
| `MANAGEMENT_PORT` | 9081 | 9091 | 9080 for sale containers |
| `REDIS_PASSWORD` | from `.env` | from `.env` | from `.env` |
| `DB_USER` / `DB_PASSWORD` | — | from `.env` | from `.env` |
| `RESET_ENABLED` | — | false | from `.env` |

Secrets have no defaults: a service refuses to start without them. Locally each service imports the repo's `.env` (`spring.config.import: optional:file:.env[.properties]` and `../.env`); in Docker compose passes the same values as environment variables. Compose binds every published port to `127.0.0.1`. Redis and Lettuce use a 1 s command and connect timeout.

Kafka listeners in compose: `INTERNAL://kafka:29092` (containers), `EXTERNAL://localhost:9092` (host), `CONTROLLER://:9093`; `KAFKA_INTER_BROKER_LISTENER_NAME=INTERNAL`; all replication factors 1.

Both services: Actuator with `health,prometheus` exposed on the separate `MANAGEMENT_PORT` only (the public port returns 404 for `/actuator/**`), liveness/readiness probes on, graceful shutdown, `micrometer-registry-prometheus`. Error responses never include messages or stack traces. sale-service: `management.metrics.distribution.percentiles-histogram.http.server.requests=true`. Both main classes: `@EnableScheduling`.

## 13. Metrics

- `flashsale_buy_total{result="SUCCESS|SOLD_OUT|ALREADY_BOUGHT|NOT_ACTIVE|TRY_AGAIN"}` (sale-service)
- `flashsale_orders_created_total`, `flashsale_orders_expired_total`, `flashsale_orders_paid_total` (order-service)
- `flashsale_reconcile_mismatch_total{direction}` (order-service)

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

- `docker-compose.yml` with postgres, redis, kafka; `infra/schema.sql`.
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
curl -X POST localhost:8090/admin/sales/1/reset
docker compose exec redis redis-cli SET sale:1:stock 10
seq 1 200 | xargs -P 50 -I{} curl -s -o /dev/null -w "%{http_code}\n" \
  -X POST -H "X-User-Id: {}" localhost:8081/sales/1/buy | sort | uniq -c
```

prints exactly `10 202` and `190 410`; `SCARD sale:1:buyers` = 10; same user twice → 409; 6+ calls/second from one user → 429.

### Phase 3 — Order pipeline, payment, timeout, reconciliation, scale-out

- `OrderConsumer`, `OrderController` (status + pay), `PaymentTimeoutWorker`, `ReconciliationJob`.
- Dockerfiles (`FROM eclipse-temurin:17-jre`, copy jar), compose services `sale-1`, `sale-2`, `order-service`, `nginx`; `infra/nginx.conf`.

**Accept:** race test through `localhost:8080` (Nginx limit commented out) still gives exactly 10 winners; 10 `PENDING_PAYMENT` rows; pay 3 (`POST /orders/{token}/pay` with the owner's `X-User-Id`); with timeout = 1 min the other 7 become `CANCELLED` and Redis stock returns to 7; `docker compose stop sale-1` and buying still works.

### Phase 4 — Load test, monitoring, docs

- `loadtest/flash-sale.js`: k6 `shared-iterations`, 1000 VUs, 20000 iterations, unique `X-User-Id` per iteration (`__VU * 100000 + __ITER`), custom counters `orders_won` (202) and `sold_out` (410), check `status < 500`.
- Metrics per §13, `infra/prometheus.yml`, prometheus + grafana in compose.
- README: summary + headline result, architecture diagram, how to run, request flow, design decisions & trade-offs (Lua vs `SELECT ... FOR UPDATE` vs optimistic locking; why Kafka; idempotency; sold-out flag; failure scenarios), results table, Grafana screenshot, limitations & next steps.

**Accept:** after `reset` with stock 100, `k6 run loadtest/flash-sale.js` reports `orders_won` = 100 and `SELECT status, count(*) FROM orders WHERE sale_id=1 GROUP BY status` totals 100; Redis stock = 0; repeatable 3 runs in a row. Save the k6 summary to `docs/loadtest-results.md`.

## 16. Commands cheat-sheet

```sh
cp .env.example .env                      # once; then change the passwords
docker compose up -d                      # start everything
(cd sale-service && ./mvnw -DskipTests package)
(cd order-service && ./mvnw -DskipTests package)
docker compose up -d --build              # rebuild services after code changes
curl -X POST localhost:8090/admin/sales/1/reset
k6 run loadtest/flash-sale.js
docker compose exec postgres psql -U postgres -d flashsale
docker compose exec redis redis-cli     # authenticates via REDISCLI_AUTH
docker compose down -v                    # wipe data (needed after changing DB passwords)
```

## 17. Security

### In place (Step A)

| Threat | Control |
| :---- | :---- |
| Infrastructure reachable from the network | Every compose port bound to `127.0.0.1` |
| Anyone editing stock or flushing Redis | `requirepass` from `.env` |
| App able to drop tables / read everything | Least-privilege `DB_USER`; superuser only initialises |
| Secrets in git | `.env` git-ignored; no defaults for secrets; `.env.example` only |
| Metrics and health leaking internals | Actuator on an internal management port |
| Accidental data wipe | `reset` endpoint absent unless `RESET_ENABLED=true` |
| Malformed or hostile input | Positive `saleId` (400) and `X-User-Id` (401), header length capped |
| Hung threads / cascading failure on Redis outage | 1 s Redis timeouts, clean 503 + `Retry-After` |
| Rate-limit counter without TTL | Atomic `rate_limit.lua` |
| Instance clock skew deciding the sale window | `buy.lua` uses Redis `TIME` |
| Stack traces in responses | `server.error.include-message/stacktrace: never` |
| Tampered build tool | Maven wrapper pinned with `distributionSha256Sum` (verified against Apache's SHA-512) |
| Vulnerable dependencies / code | GitHub Actions CI, CodeQL (`security-extended`), Dependabot |
| Stray topics | Kafka `auto.create.topics.enable=false` |
| Compromised app container | Runs as non-root `app` user, read-only filesystem, `cap_drop: ALL`, `no-new-privileges`, 512 MB memory limit |
| Internal endpoints reachable from outside | Nginx routes only `/sales/*` and `/orders/*`; sale containers are not published; order-service on `127.0.0.1` only |
| Slow or oversized requests | Nginx: 1 KB body limit, 5 s header/body timeouts, 1 s upstream connect timeout |
| Reading or paying someone else's order | `/orders` queries filter on the caller's user id |

### Step B — after Phase 3: identity with Keycloak

- Keycloak in compose (realm `stockrush`, imported from `infra/keycloak/`), its own Postgres database.
- Both services become OAuth2 resource servers (Spring Security, JWT validation against the realm's JWKS).
- The user id comes from the token's `sub` claim; `X-User-Id` is removed.
- `/admin/**` requires the `admin` realm role. The order ownership check (already enforced on `X-User-Id`) moves to the token's subject.
- Rate limits apply per authenticated account; Nginx keeps the per-IP limit.

### Step C — after Phase 4

- Virtual waiting room / queue token issued before the sale opens; CAPTCHA on suspicious traffic.
- TLS at Nginx; Kafka SASL; Redis ACL users per service.
- Dead-letter topic and retry with backoff for the consumer; Flyway migrations.
- Image scanning in CI; branch protection on `main`.
- Structured JSON logs with request ids; alerts on `flashsale_reconcile_mismatch_total`.

