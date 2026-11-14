// Experiment 3: Lambda scaling behaviour (open model: a fixed arrival rate, independent of response time).
//
//   k6 run -e SEED=../../scripts/.seed/seed-output.json -e PROFILE=ramp \
//          --out csv=../../results/exp3-scaling/ramp/run1/k6.csv.gz scaling.js
//   PROFILE=ramp  : 1 -> 10 -> 25 -> 50 -> 100 requests/s, 2 minutes per step
//   PROFILE=spike : 5 req/s, then 100 req/s within 10 s (a COVID-style surge), then back to 5
//
// Read-only public routes, so no tokens are needed and no data changes.
// Record the start and end time (UTC) printed by k6, then run scripts/cloudwatch_export.py for that window.
// Keep the peak below the API throttle (ApiThrottleRate parameter, default 200 req/s).
import http from 'k6/http';
import { check } from 'k6';
import { baseUrl, jsonHeaders, pick } from './lib/common.js';

const seed = JSON.parse(open(__ENV.SEED || '../../scripts/.seed/seed-output.json'));
const BASE = baseUrl(seed);

const PROFILES = {
  ramp: [
    { target: 1, duration: '30s' }, { target: 1, duration: '1m30s' },
    { target: 10, duration: '10s' }, { target: 10, duration: '2m' },
    { target: 25, duration: '10s' }, { target: 25, duration: '2m' },
    { target: 50, duration: '10s' }, { target: 50, duration: '2m' },
    { target: 100, duration: '10s' }, { target: 100, duration: '2m' },
    { target: 0, duration: '10s' },
  ],
  spike: [
    { target: 5, duration: '10s' }, { target: 5, duration: '2m' },
    { target: 100, duration: '10s' }, { target: 100, duration: '2m' },
    { target: 5, duration: '10s' }, { target: 5, duration: '1m' },
  ],
};

const profile = __ENV.PROFILE || 'ramp';

export const options = {
  scenarios: {
    open: {
      executor: 'ramping-arrival-rate',
      startRate: 1,
      timeUnit: '1s',
      preAllocatedVUs: Number(__ENV.PRE_VUS || 50),
      maxVUs: Number(__ENV.MAX_VUS || 300),
      stages: PROFILES[profile],
    },
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  console.log(`START_UTC=${new Date().toISOString()} PROFILE=${profile}`);
}

export default function () {
  const r = Math.random();
  let res;
  if (r < 0.6) {
    res = http.get(`${BASE}/doctors?city=${encodeURIComponent(pick(seed.cities))}`, jsonHeaders(null, 'GET /doctors'));
  } else if (r < 0.85) {
    res = http.get(`${BASE}/doctors/${pick(seed.doctors).doctorId}`, jsonHeaders(null, 'GET /doctors/{id}'));
  } else {
    res = http.get(`${BASE}/doctors/${pick(seed.doctors).doctorId}/slots`, jsonHeaders(null, 'GET /doctors/{id}/slots'));
  }
  check(res, { 'status 200': (x) => x.status === 200 });
}

export function teardown() {
  console.log(`END_UTC=${new Date().toISOString()}`);
}
