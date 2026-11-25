// Flash-sale load test (ARCHITECTURE.md §15, Phase 4).
//
// 1000 virtual users share 20 000 buy attempts against one sale; every attempt is a different user.
// The test passes only if exactly STOCK attempts win and no request fails with a 5xx.
//
// Before running: reset the sale and switch off the per-IP limit in Nginx (all k6 traffic is one IP):
//   curl -X POST localhost:8090/admin/sales/1/reset
//   docker compose -f docker-compose.yml -f docker-compose.loadtest.yml up -d nginx
// Run with a local k6:
//   k6 run loadtest/flash-sale.js
// or with Docker:
//   docker run --rm -i --network host -v "$PWD/loadtest:/scripts:ro" grafana/k6:1.2.0 run /scripts/flash-sale.js

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SALE_ID = __ENV.SALE_ID || '1';
const STOCK = Number(__ENV.STOCK || 100);

// 202 (won) and 410 (sold out) are both correct answers; only anything else counts as http_req_failed.
http.setResponseCallback(http.expectedStatuses(202, 410));

const ordersWon = new Counter('orders_won');
const soldOut = new Counter('sold_out');
const unexpected = new Counter('unexpected_status');

export const options = {
  scenarios: {
    flash_sale: {
      executor: 'shared-iterations',
      vus: 1000,
      iterations: 20000,
      maxDuration: '2m',
    },
  },
  thresholds: {
    // The headline guarantee: exactly STOCK winners, never more.
    orders_won: [`count==${STOCK}`],
    checks: ['rate==1.0'],
    unexpected_status: ['count==0'],
    http_req_failed: ['rate==0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  // Unique per iteration: VU ids start at 1 and each VU runs far fewer than 100 000 iterations.
  const userId = __VU * 100000 + __ITER;

  const res = http.post(`${BASE_URL}/sales/${SALE_ID}/buy`, null, {
    headers: { 'X-User-Id': String(userId) },
    tags: { name: 'buy' },
  });

  if (res.status === 202) {
    ordersWon.add(1);
  } else if (res.status === 410) {
    soldOut.add(1);
  } else {
    unexpected.add(1, { status: String(res.status) });
  }

  check(res, {
    'status < 500': (r) => r.status > 0 && r.status < 500,
  });
}
