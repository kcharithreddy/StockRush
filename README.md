# StockRush

High-concurrency flash-sale backend that never oversells: atomic Redis Lua stock control, Kafka async order pipeline, Spring Boot microservices, and PostgreSQL, load-tested with k6 and monitored in Grafana.

> Work in progress. The full design is in [ARCHITECTURE.md](ARCHITECTURE.md); a complete README arrives with Phase 4.

## Quick start

Requires Docker and Java 17+.

```bash
cp .env.example .env        # then change the passwords in .env
(cd sale-service && ./mvnw -DskipTests package)
(cd order-service && ./mvnw -DskipTests package)
docker compose up -d --build
```

Everything goes through Nginx on port 8080:

```bash
curl -X POST localhost:8090/admin/sales/1/warmup                 # admin: host only
curl -X POST -H "X-User-Id: 1" localhost:8080/sales/1/buy         # → orderToken
curl -H "X-User-Id: 1" localhost:8080/orders/<orderToken>
curl -X POST -H "X-User-Id: 1" localhost:8080/orders/<orderToken>/pay
```

For load tests (k6 or the race test), comment out the `limit_req` line in `infra/nginx.conf` and run `docker compose restart nginx`: all of that traffic comes from one IP.

## Security

See [SECURITY.md](SECURITY.md) and §17 of [ARCHITECTURE.md](ARCHITECTURE.md).
