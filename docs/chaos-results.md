# Chaos test results

A load test proves the system is right when everything works. These tests break one part of StockRush **in the middle of a sale** and prove the guarantees still hold afterwards.

## How a run works

`chaos/run.sh` repeats these steps for each scenario:

1. Resets the **chaos sale** (sale 3: 1,000 units, added by migration V5).
2. Starts a steady stream of new buyers: 30 per second for 60 s (`loadtest/chaos-sale.js`), every buyer a different user. The sale is still selling when the fault hits.
3. After **10 s**, kills one container (`docker compose kill`, i.e. SIGKILL: no graceful shutdown).
4. After **15 s** more, starts the container again.
5. Waits for the traffic to end and the stack to report healthy.
6. Checks four rules against PostgreSQL and Redis themselves. It does not trust the services' own answers.

| # | Check | Rule |
|---|---|---|
| 1 | **No oversell** | orders (`PENDING_PAYMENT` + `PAID`) ≤ 1,000 |
| 2 | **No lost purchase** | every buyer who was told `202` has an order: orders ≥ confirmed wins |
| 3 | **Stock adds up** | units left in Redis + orders = 1,000, reached within 3 minutes of the end of the traffic |
| 4 | **Nothing parked** | no new records on the dead-letter topic |

The run fails if any check fails in any scenario.

## Results

One run of all six scenarios, back to back, on a single 4-vCPU machine that also ran k6 and the whole stack:

| Scenario | Fault | Attempts | Told "won" | Refused safely (503) | No answer / 502 / 504 | Orders | Redis left | Checks |
| :-- | :-- | --: | --: | --: | --: | --: | --: | :-- |
| baseline | No fault (reference run) | 1801 | 1000 | 0 | 0 | 1000 | 0 | no oversell ✓, no lost purchase ✓, stock adds up ✓, nothing parked ✓ |
| sale-instance | sale-1 killed; sale-2 keeps serving | 1800 | 1000 | 0 | 12 | 1000 | 0 | no oversell ✓, no lost purchase ✓, stock adds up ✓, nothing parked ✓ |
| kafka | Kafka broker killed | 1801 | 894 | 907 | 0 | 894 | 106 | no oversell ✓, no lost purchase ✓, stock adds up ✓, nothing parked ✓ |
| order-service | order-service killed | 1801 | 1000 | 0 | 0 | 1000 | 0 | no oversell ✓, no lost purchase ✓, stock adds up ✓, nothing parked ✓ |
| postgres | PostgreSQL killed | 1801 | 1000 | 0 | 0 | 1000 | 0 | no oversell ✓, no lost purchase ✓, stock adds up ✓, nothing parked ✓ |
| redis | Redis killed (SIGKILL), restarted from its append-only file | 1801 | 1000 | 572 | 0 | 1000 | 0 | no oversell ✓, no lost purchase ✓, stock adds up ✓, nothing parked ✓ |

## What happened in each scenario

**sale-1 killed.**
- Nginx stopped sending new requests to the dead instance and sale-2 served the rest.
- 12 requests that were *inside* sale-1 when it died got no answer.
- The sale still sold exactly 1,000 units, and each one has its order.

**Kafka killed for 15 s.**
- A buy cannot be confirmed without its order event, so sale-service gives Kafka at most 2 s and otherwise answers `503 TRY_AGAIN`. The unit goes back to stock: at once if the event never reached Kafka, or when Kafka gives its final answer (≤ 10 s) if delivery was uncertain. That happened to 907 buyers; none of them holds a unit without an order.
- Once Kafka was back, buying resumed.
- The 60 s of traffic ended before the sale sold out: 894 orders + 106 units left = 1,000.

**order-service killed.**
- Buying never stopped. Kafka kept every order event, and order-service wrote them all after it restarted, so all 1,000 orders arrived.
- The consumer is idempotent, so the events it had already started on before the crash were not written twice.

**PostgreSQL killed.**
- Buyers noticed nothing: buying needs only Redis and Kafka.
- order-service retried the order writes (0.5 s, 1 s, 2 s … up to 5 minutes) until the database came back, then caught up. Nothing went to the dead-letter topic.

**Redis killed (SIGKILL).**
- While Redis was down, 572 buyers got `503 TRY_AGAIN` within 1 s. No thread hung.
- Redis restarted from its append-only file with the stock exactly where it was. Every confirmed decrement had already been written there before the buyer got an answer.

## Found along the way

- **Keycloak after a database restart.** Keycloak shares the PostgreSQL server. After PostgreSQL restarts, Keycloak's first logins fail on its stale pooled connections ("This connection has been closed"), and it recovers on its own after a retry. Tokens that were already issued keep working, because the services verify them with Keycloak's public keys and do not call Keycloak per request. The test runner retries logins, as a real client would.
- **Docker Compose before v2.24.4** garbled unquoted numbers in `environment:` when an override file was layered on top, so sale-service would not start in load-test mode. All such values are now quoted.

## Limits, stated honestly

- **The tests kill processes, not machines.** Redis writes every change to its append-only file before answering, but `fsync`s it to disk once per second (`appendfsync everysec`). A process crash loses nothing, as above. A **power loss or kernel crash** of the Redis host can lose up to about 1 s of decrements, and Redis would then hold *more* stock than is really left: an oversell risk.
  - The reconciliation job compares Redis with PostgreSQL every 60 s and raises `redis_high` (oversell risk, an ERROR and an alert).
  - Ways to close the gap: `appendfsync always`, which costs write latency, or a Redis replica with `WAIT`.
- **A crash between the stock decrement and the Kafka send** (a window of a few milliseconds inside one request) leaves a unit reserved without an order. That is an *undersell*, never an oversell. Reconciliation reports it as `redis_low`. In these runs it did not happen, and check 3 would have caught it.
- **One fault at a time.** Combined faults (for example Kafka and PostgreSQL together) are not covered yet.

## Run it yourself

```bash
docker compose -f docker-compose.yml -f docker-compose.loadtest.yml up -d
```
```bash
chaos/run.sh                      # all scenarios, about 13 minutes
```
```bash
chaos/run.sh kafka redis          # just these
```

Settings, as environment variables:

| Variable | Default | Meaning |
|---|---|---|
| `RATE` | 30 | new buyers per second |
| `DURATION` | 60s | how long the traffic runs |
| `FAULT_AT` | 10 | seconds before the fault |
| `OUTAGE` | 15 | seconds the component stays down |

In CI, the **Chaos tests** workflow runs the whole suite weekly and on demand (Actions → Chaos tests → Run workflow). It builds everything on a fresh runner with random passwords and puts this table on the run's summary page.
