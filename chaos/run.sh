#!/usr/bin/env bash
# Chaos tests: break one part of StockRush in the middle of a sale, bring it back, and check that the
# guarantees still hold. See docs/chaos-results.md.
#
#   docker compose -f docker-compose.yml -f docker-compose.loadtest.yml up -d
#   chaos/run.sh                      # every scenario
#   chaos/run.sh kafka postgres       # just these
#
# Scenarios: baseline sale-instance kafka order-service postgres redis
#
# Each scenario: reset the 1,000-unit chaos sale (sale 3), start a steady stream of new buyers
# (loadtest/chaos-sale.js), kill one container FAULT_AT seconds in, start it again OUTAGE seconds later,
# wait for the traffic to end and the system to settle, then check:
#   1. no oversell:       orders (PENDING_PAYMENT + PAID) <= total stock
#   2. no lost purchase:  every buyer who was told 202 has an order (orders >= confirmed wins)
#   3. stock adds up:     units left in Redis + orders == total stock
#   4. nothing parked:    no new records on the dead-letter topic
# Exits 1 if any check fails in any scenario.
set -euo pipefail
trap 'echo "chaos/run.sh: command failed at line $LINENO" >&2' ERR

cd "$(dirname "${BASH_SOURCE[0]}")/.."
set -a; . ./.env; set +a

SALE=3
TOTAL=1000
RATE="${RATE:-30}"
DURATION="${DURATION:-60s}"
FAULT_AT="${FAULT_AT:-10}"
OUTAGE="${OUTAGE:-15}"
SETTLE_TIMEOUT="${SETTLE_TIMEOUT:-180}"
K6_IMAGE="${K6_IMAGE:-grafana/k6:1.2.0}"
ALL_SCENARIOS=(baseline sale-instance kafka order-service postgres redis)
if [ $# -eq 0 ]; then SCENARIOS=("${ALL_SCENARIOS[@]}"); else SCENARIOS=("$@"); fi

OUT="$(mktemp -d)"
chmod 777 "$OUT"   # k6 runs as a non-root user and writes its result here
trap 'rm -rf "$OUT"' EXIT

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }

# After PostgreSQL restarts, Keycloak's first logins can fail on its stale pooled connections
# ("This connection has been closed"); it recovers on its own, so retry like a real client would.
admin_token() {
  local attempt
  for attempt in 1 2 3 4 5; do
    scripts/token.sh shopadmin 2>/dev/null && return 0
    sleep 3
  done
  scripts/token.sh shopadmin
}

admin() {  # admin METHOD PATH: order-service admin API as shopadmin
  curl -sS --fail-with-body -X "$1" -H "Authorization: Bearer $(admin_token)" "localhost:8090$2"
}

sql() {
  docker compose exec -T postgres psql -tA -U postgres -d flashsale -c "$1" | tr -d '[:space:]'
}

redis_stock() {
  docker compose exec -T redis redis-cli --no-auth-warning GET "sale:$SALE:stock" | tr -d '[:space:]'
}

orders() {
  sql "SELECT count(*) FROM orders WHERE sale_id = $SALE AND status IN ('PENDING_PAYMENT', 'PAID')"
}

dead_letters() {
  # grep exits 1 when there are none; under pipefail that must not count as a failure
  admin GET "/admin/dead-letters?limit=1000" | { grep -o '"partition"' || true; } | wc -l | tr -d '[:space:]'
}

wait_healthy() {  # every app container healthy, or give up after SETTLE_TIMEOUT
  local deadline=$((SECONDS + SETTLE_TIMEOUT))
  while ((SECONDS < deadline)); do
    local unhealthy
    unhealthy="$(docker compose ps --format '{{.Service}} {{.Health}}' \
      | awk '$1 ~ /^(postgres|redis|kafka|keycloak|sale-1|sale-2|order-service)$/ && $2 != "healthy"' || true)"
    if [ -z "$unhealthy" ] && [ "$(docker compose ps --format '{{.Service}}' | grep -cxE 'postgres|redis|kafka|keycloak|sale-1|sale-2|order-service|nginx')" -eq 8 ]; then
      return 0
    fi
    sleep 3
  done
  log "not healthy after ${SETTLE_TIMEOUT}s:"; docker compose ps >&2
  return 1
}

# Orders arrive asynchronously (Kafka): wait until Redis + orders adds up, or the timeout passes.
wait_settled() {
  local deadline=$((SECONDS + SETTLE_TIMEOUT)) stock count
  while ((SECONDS < deadline)); do
    stock="$(redis_stock)"; count="$(orders)"
    if [ -n "$stock" ] && [ $((stock + count)) -eq "$TOTAL" ]; then
      return 0
    fi
    sleep 3
  done
  return 1
}

fault() {  # fault SCENARIO start|stop
  local scenario=$1 action=$2
  case "$scenario:$action" in
    baseline:*)            ;;
    sale-instance:start)   docker compose kill sale-1 >/dev/null ;;
    sale-instance:stop)    docker compose start sale-1 >/dev/null ;;
    kafka:start)           docker compose kill kafka >/dev/null ;;
    kafka:stop)            docker compose start kafka >/dev/null ;;
    order-service:start)   docker compose kill order-service >/dev/null ;;
    order-service:stop)    docker compose start order-service >/dev/null ;;
    postgres:start)        docker compose kill postgres >/dev/null ;;
    postgres:stop)         docker compose start postgres >/dev/null ;;
    # Redis is killed (SIGKILL, no shutdown save) and later restarts from its append-only file.
    redis:start)           docker compose kill redis >/dev/null ;;
    redis:stop)            docker compose start redis >/dev/null ;;
    *) echo "unknown scenario: $scenario" >&2; exit 2 ;;
  esac
}

describe() {
  case "$1" in
    baseline)      echo "No fault (reference run)" ;;
    sale-instance) echo "sale-1 killed; sale-2 keeps serving" ;;
    kafka)         echo "Kafka broker killed" ;;
    order-service) echo "order-service killed" ;;
    postgres)      echo "PostgreSQL killed" ;;
    redis)         echo "Redis killed (SIGKILL), restarted from its append-only file" ;;
  esac
}

json() {  # json FILE KEY: a number from k6's result.json
  grep -o "\"$2\":[0-9]*" "$1" | cut -d: -f2
}

printf '| Scenario | Fault | Attempts | Told "won" | Refused safely (503) | No answer / 502 / 504 | Orders | Redis left | Checks |\n' > "$OUT/summary.md"
printf '| :-- | :-- | --: | --: | --: | --: | --: | --: | :-- |\n' >> "$OUT/summary.md"
failed=0
run=0

for scenario in "${SCENARIOS[@]}"; do
  run=$((run + 1))
  log "=== $scenario: $(describe "$scenario")"
  wait_healthy
  admin POST "/admin/sales/$SALE/reset" >/dev/null
  sleep 6   # let the services' local sold-out flags expire
  dlt_before="$(dead_letters)"

  rm -f "$OUT/result.json"
  docker run --rm --network host -e LOADTEST_JWT_SECRET -e SALE_ID="$SALE" -e RATE="$RATE" -e DURATION="$DURATION" \
    -e RUN="$((RANDOM + run))" -v "$PWD/loadtest:/scripts:ro" -v "$OUT:/out" "$K6_IMAGE" run -q /scripts/chaos-sale.js \
    > "$OUT/k6.log" 2>&1 &
  k6_pid=$!

  if [ "$scenario" != baseline ]; then
    sleep "$FAULT_AT"
    log "fault: $(describe "$scenario")"
    fault "$scenario" start
    sleep "$OUTAGE"
    log "recover"
    fault "$scenario" stop
  fi
  wait "$k6_pid" || { log "k6 failed:"; cat "$OUT/k6.log" >&2; exit 1; }

  log "traffic done; waiting for the system to settle"
  wait_healthy
  settled=yes
  wait_settled || settled=no

  r="$OUT/result.json"
  attempts="$(json "$r" attempts)"; won="$(json "$r" won)"; try_again="$(json "$r" tryAgain)"
  no_answer=$(( $(json "$r" noResponse) + $(json "$r" gatewayError) ))
  count="$(orders)"; stock="$(redis_stock)"; dlt_after="$(dead_letters)"

  checks=()
  ok=yes
  if [ "$count" -le "$TOTAL" ]; then checks+=("no oversell ✓"); else checks+=("OVERSELL ✗"); ok=no; fi
  if [ "$count" -ge "$won" ]; then checks+=("no lost purchase ✓"); else checks+=("LOST PURCHASES ✗"); ok=no; fi
  if [ "$settled" = yes ]; then checks+=("stock adds up ✓"); else checks+=("STOCK MISMATCH ✗"); ok=no; fi
  if [ "$dlt_after" -eq "$dlt_before" ]; then checks+=("nothing parked ✓"); else checks+=("DEAD-LETTERED ✗"); ok=no; fi
  [ "$ok" = yes ] || failed=1

  line="| $scenario | $(describe "$scenario") | $attempts | $won | $try_again | $no_answer | $count | $stock | $(IFS=,; echo "${checks[*]}" | sed 's/,/, /g') |"
  echo "$line" >> "$OUT/summary.md"
  log "$line"
done

echo
cat "$OUT/summary.md"
# In GitHub Actions, also show the table on the run's summary page.
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  { echo "## Chaos tests"; echo; cat "$OUT/summary.md"; } >> "$GITHUB_STEP_SUMMARY"
fi
if [ "$failed" -ne 0 ]; then
  echo; echo "CHAOS TESTS FAILED"; exit 1
fi
echo; echo "All chaos scenarios passed."
