// Chaos-test traffic (chaos/run.sh). Unlike flash-sale.js, which sells out in about a second, this sends a
// steady stream of new buyers (RATE per second for DURATION), so the sale is still selling while a fault is
// injected and while the system recovers. It does not judge the run: chaos/run.sh compares these counts
// with PostgreSQL and Redis afterwards. Results go to /out/result.json.
//
// Every attempt is a different user with its own load-test token (see flash-sale.js), so needs
// docker-compose.loadtest.yml.

import http from 'k6/http';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'https://localhost:8443';
const SALE_ID = __ENV.SALE_ID || '3';
const RATE = Number(__ENV.RATE || 30);
const DURATION = __ENV.DURATION || '60s';
const RUN = __ENV.RUN || '0';
const SECRET = __ENV.LOADTEST_JWT_SECRET;
if (!SECRET || SECRET.length < 32) {
  throw new Error('Set LOADTEST_JWT_SECRET (32+ characters, same value as in .env)');
}

// Every outcome is counted below; none of them makes k6 itself fail.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 599 }));

const won = new Counter('won');                     // 202: the buyer was told they have a unit
const soldOut = new Counter('sold_out');            // 410
const tryAgain = new Counter('try_again');          // 503 TRY_AGAIN: refused safely, nothing held
const gatewayError = new Counter('gateway_error');  // 502/504 from Nginx: no instance answered
const noResponse = new Counter('no_response');      // connection failed or timed out
const other = new Counter('other_status');

export const options = {
  insecureSkipTLSVerify: BASE_URL.startsWith('https://localhost'),
  scenarios: {
    chaos: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 100,
      maxVUs: 400,
    },
  },
};

const b64url = (s) => encoding.b64encode(s, 'rawurl');
const HEADER = b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));

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

/** Unique per attempt in this run; RUN keeps runs apart. */
function userId() {
  const run = Number(RUN).toString(16).padStart(4, '0').slice(-4);
  const n = exec.scenario.iterationInTest.toString(16).padStart(8, '0');
  return `00000000-0000-4000-8000-${run}${n}`;
}

export default function () {
  const res = http.post(`${BASE_URL}/sales/${SALE_ID}/buy`, null, {
    headers: { Authorization: `Bearer ${tokenFor(userId())}` },
    timeout: '10s',
    tags: { name: 'buy' },
  });
  if (res.status === 202) won.add(1);
  else if (res.status === 410) soldOut.add(1);
  else if (res.status === 503) tryAgain.add(1);
  else if (res.status === 502 || res.status === 504) gatewayError.add(1);
  else if (res.status === 0) noResponse.add(1);
  else other.add(1);
}

export function handleSummary(data) {
  const count = (name) => (data.metrics[name] ? data.metrics[name].values.count : 0);
  const result = {
    attempts: count('http_reqs'),
    won: count('won'),
    soldOut: count('sold_out'),
    tryAgain: count('try_again'),
    gatewayError: count('gateway_error'),
    noResponse: count('no_response'),
    other: count('other_status'),
    p95ms: data.metrics.http_req_duration ? Math.round(data.metrics.http_req_duration.values['p(95)']) : null,
  };
  return {
    '/out/result.json': JSON.stringify(result),
    stdout: `chaos traffic: ${JSON.stringify(result)}\n`,
  };
}
