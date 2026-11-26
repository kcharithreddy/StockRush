// Flash-sale load test (ARCHITECTURE.md §15, Phase 4).
//
// 1000 virtual users share 20 000 buy attempts against one sale; every attempt is a different user.
// The test passes only if exactly STOCK attempts win and no request fails with a 5xx.
//
// Every attempt carries its own bearer token for a distinct user. k6 signs these itself as the
// "stockrush-loadtest" issuer (HS256 with LOADTEST_JWT_SECRET), which the services accept only when
// started with docker-compose.loadtest.yml. That override also switches off the per-IP limit in Nginx.
//
//   docker compose -f docker-compose.yml -f docker-compose.loadtest.yml up -d
//   scripts/token.sh shopadmin | xargs -I{} curl -X POST -H "Authorization: Bearer {}" localhost:8090/admin/sales/1/reset
//   set -a; . ./.env; set +a
// Run with a local k6:
//   k6 run -e LOADTEST_JWT_SECRET loadtest/flash-sale.js
// or with Docker:
//   docker run --rm -i --network host -e LOADTEST_JWT_SECRET -v "$PWD/loadtest:/scripts:ro" grafana/k6:1.2.0 run /scripts/flash-sale.js

import http from 'k6/http';
import { check } from 'k6';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'https://localhost:8443';
const SALE_ID = __ENV.SALE_ID || '1';
const STOCK = Number(__ENV.STOCK || 100);
const VUS = Number(__ENV.VUS || 1000);
const ITERATIONS = Number(__ENV.ITERATIONS || 20000);
const SECRET = __ENV.LOADTEST_JWT_SECRET;
if (!SECRET || SECRET.length < 32) {
  throw new Error('Set LOADTEST_JWT_SECRET (32+ characters, same value as in .env)');
}

// 202 (won) and 410 (sold out) are both correct answers; only anything else counts as http_req_failed.
http.setResponseCallback(http.expectedStatuses(202, 410));

const ordersWon = new Counter('orders_won');
const soldOut = new Counter('sold_out');
const unexpected = new Counter('unexpected_status');

export const options = {
  // The local certificate comes from our own development CA (scripts/dev-cert.sh), which k6 cannot be told
  // to trust, so verification is skipped for localhost only. Against a real host, certificates are verified.
  insecureSkipTLSVerify: BASE_URL.startsWith('https://localhost'),
  scenarios: {
    flash_sale: {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: ITERATIONS,
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

const b64url = (s) => encoding.b64encode(s, 'rawurl');
const HEADER = b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));

/** A short-lived token for one user, signed like the services expect from the load-test issuer. */
function tokenFor(userId) {
  const now = Math.floor(Date.now() / 1000);
  const payload = b64url(JSON.stringify({
    iss: 'stockrush-loadtest',
    aud: 'stockrush-api',
    sub: userId,
    iat: now,
    exp: now + 300,
    realm_access: { roles: ['buyer'] },
  }));
  const signature = crypto.hmac('sha256', SECRET, `${HEADER}.${payload}`, 'base64rawurl');
  return `${HEADER}.${payload}.${signature}`;
}

/** Deterministic UUID per (VU, iteration): unique across the whole run. */
function userIdFor(vu, iter) {
  const n = (vu * 100000 + iter).toString(16).padStart(12, '0');
  return `00000000-0000-4000-8000-${n}`;
}

export default function () {
  const userId = userIdFor(__VU, __ITER);

  const res = http.post(`${BASE_URL}/sales/${SALE_ID}/buy`, null, {
    headers: { Authorization: `Bearer ${tokenFor(userId)}` },
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
