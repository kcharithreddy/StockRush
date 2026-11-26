// Waiting-room load test (ARCHITECTURE.md §18): a sale sold through the virtual queue.
//
// BUYERS honest users each join the queue, poll their status once a second until they are admitted (or the
// sale sells out), then buy once. BOTS users skip the queue and call buy directly. The run passes only if
// exactly STOCK honest buyers win, no bot ever gets past the gate, and nothing fails.
//
//   docker compose -f docker-compose.yml -f docker-compose.loadtest.yml up -d
//   set -a; . ./.env; set +a
//   curl -X POST -H "Authorization: Bearer $(scripts/token.sh shopadmin)" localhost:8090/admin/sales/2/reset
//   k6 run -e LOADTEST_JWT_SECRET loadtest/waiting-room.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'https://localhost:8443';
const SALE_ID = __ENV.SALE_ID || '2';
const STOCK = Number(__ENV.STOCK || 100);
const BUYERS = Number(__ENV.BUYERS || 2000);
const BOTS = Number(__ENV.BOTS || 200);
const SECRET = __ENV.LOADTEST_JWT_SECRET;
if (!SECRET || SECRET.length < 32) {
  throw new Error('Set LOADTEST_JWT_SECRET (32+ characters, same value as in .env)');
}

const ordersWon = new Counter('orders_won');
const admitted = new Counter('admitted');
const soldOutWhileWaiting = new Counter('sold_out_while_waiting');
const botBuysBlocked = new Counter('bot_buys_blocked');
const botBuysThrough = new Counter('bot_buys_through');
const unexpected = new Counter('unexpected_status');

export const options = {
  insecureSkipTLSVerify: BASE_URL.startsWith('https://localhost'),
  scenarios: {
    queue: { executor: 'per-vu-iterations', vus: BUYERS, iterations: 1, maxDuration: '3m', exec: 'honestBuyer' },
    bots: { executor: 'per-vu-iterations', vus: BOTS, iterations: 1, maxDuration: '3m', exec: 'bot', startTime: '2s' },
  },
  thresholds: {
    orders_won: [`count==${STOCK}`],
    bot_buys_through: ['count==0'],
    unexpected_status: ['count==0'],
    http_req_failed: ['rate==0'],
  },
};

const b64url = (s) => encoding.b64encode(s, 'rawurl');
const HEADER = b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));

function tokenFor(userId) {
  const now = Math.floor(Date.now() / 1000);
  const payload = b64url(JSON.stringify({
    iss: 'stockrush-loadtest', aud: 'stockrush-api', sub: userId, iat: now, exp: now + 600,
    realm_access: { roles: ['buyer'] },
  }));
  return `${HEADER}.${payload}.${crypto.hmac('sha256', SECRET, `${HEADER}.${payload}`, 'base64rawurl')}`;
}

function userId(kind, vu) {
  return `00000000-0000-4000-${kind}-${vu.toString(16).padStart(12, '0')}`;
}

http.setResponseCallback(http.expectedStatuses(200, 202, 403, 410));

export function honestBuyer() {
  const params = { headers: { Authorization: `Bearer ${tokenFor(userId('8000', __VU))}` } };
  let res = http.post(`${BASE_URL}/sales/${SALE_ID}/queue`, null, params);
  let status = res.json('status');
  for (let polls = 0; status === 'WAITING' && polls < 120; polls++) {
    sleep(1);
    res = http.get(`${BASE_URL}/sales/${SALE_ID}/queue`, params);
    status = res.json('status');
  }
  if (status === 'SOLD_OUT') {
    soldOutWhileWaiting.add(1);
    return;
  }
  if (status !== 'ADMITTED') {
    unexpected.add(1, { where: 'queue', status: String(status) });
    return;
  }
  admitted.add(1);
  const buy = http.post(`${BASE_URL}/sales/${SALE_ID}/buy`, null, params);
  if (buy.status === 202) {
    ordersWon.add(1);
  } else if (buy.status !== 410) {
    unexpected.add(1, { where: 'buy', status: String(buy.status) });
  }
  check(buy, { 'admitted buyer gets 202 or 410': (r) => r.status === 202 || r.status === 410 });
}

export function bot() {
  const params = { headers: { Authorization: `Bearer ${tokenFor(userId('9000', __VU))}` } };
  const res = http.post(`${BASE_URL}/sales/${SALE_ID}/buy`, null, params);
  if (res.status === 403 && res.json('status') === 'NOT_ADMITTED') {
    botBuysBlocked.add(1);
  } else if (res.status === 202) {
    botBuysThrough.add(1);
  } else if (res.status !== 410) {
    unexpected.add(1, { where: 'bot', status: String(res.status) });
  }
}
