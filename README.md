# StockRush

High-concurrency flash-sale backend that never oversells: atomic Redis Lua stock control, Kafka async order pipeline, Spring Boot microservices, and PostgreSQL, load-tested with k6 and monitored in Grafana.

> Work in progress. The full design is in [ARCHITECTURE.md](ARCHITECTURE.md); a complete README arrives with Phase 4.

## Quick start

Requires Docker and Java 17+.

```bash
cp .env.example .env        # then change the passwords in .env
docker compose up -d        # Postgres, Redis, Kafka (bound to 127.0.0.1 only)
(cd order-service && ./mvnw -DskipTests package && java -jar target/order-service-0.0.1-SNAPSHOT.jar) &
(cd sale-service  && ./mvnw -DskipTests package && java -jar target/sale-service-0.0.1-SNAPSHOT.jar) &

curl -X POST localhost:8090/admin/sales/1/warmup
curl -X POST -H "X-User-Id: 1" localhost:8081/sales/1/buy
```

## Security

See [SECURITY.md](SECURITY.md) and §17 of [ARCHITECTURE.md](ARCHITECTURE.md).
