// Experiment 2: API latency under different concurrency levels (closed model, constant virtual users).
//
//   k6 run -e SEED=../../scripts/.seed/seed-output.json -e VUS=10 -e DURATION=3m \
//          --out csv=../../results/exp2-latency/vus-10/run1/k6.csv.gz latency.js
//
// Run once per level (VUS = 1, 5, 10, 25, 50), 3 runs each, with scripts/reset.py before every run.
// The analysis drops the first 30 s of each run as warm-up.
// Mix: 60% search, 20% doctor details, 15% slot listing, 5% booking (a booking may get 409 if the slot is
// taken; 409 is an expected answer, not an error).
import http from 'k6/http';
import { check } from 'k6';
import { baseUrl, futureSlots, jsonHeaders, patientTokens, pick } from './lib/common.js';

const seed = JSON.parse(open(__ENV.SEED || '../../scripts/.seed/seed-output.json'));
const BASE = baseUrl(seed);

export const options = {
  scenarios: {
    closed: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 10),
      duration: __ENV.DURATION || '3m',
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    // Not pass/fail criteria for the report; they only make k6 print per-route percentiles.
    'http_req_duration{name:GET /doctors}': ['p(95)>=0'],
    'http_req_duration{name:GET /doctors/{id}}': ['p(95)>=0'],
    'http_req_duration{name:GET /doctors/{id}/slots}': ['p(95)>=0'],
    'http_req_duration{name:POST /appointments}': ['p(95)>=0'],
  },
};

http.setResponseCallback(http.expectedStatuses(200, 201, 409));

export function setup() {
  return { tokens: patientTokens(seed, 20) };
}

export default function (data) {
  const r = Math.random();
  if (r < 0.6) {
    const params = `city=${encodeURIComponent(pick(seed.cities))}`
      + (Math.random() < 0.5 ? `&speciality=${encodeURIComponent(pick(seed.specialities))}` : '');
    const res = http.get(`${BASE}/doctors?${params}`, jsonHeaders(null, 'GET /doctors'));
    check(res, { 'search 200': (x) => x.status === 200 });
  } else if (r < 0.8) {
    const res = http.get(`${BASE}/doctors/${pick(seed.doctors).doctorId}`, jsonHeaders(null, 'GET /doctors/{id}'));
    check(res, { 'doctor 200': (x) => x.status === 200 });
  } else if (r < 0.95) {
    const res = http.get(`${BASE}/doctors/${pick(seed.doctors).doctorId}/slots`,
      jsonHeaders(null, 'GET /doctors/{id}/slots'));
    check(res, { 'slots 200': (x) => x.status === 200 });
  } else {
    const doctor = pick(seed.doctors);
    const slots = futureSlots(doctor);
    if (slots.length === 0) return;
    const res = http.post(`${BASE}/appointments`,
      JSON.stringify({ doctorId: doctor.doctorId, slotStart: pick(slots) }),
      jsonHeaders(data.tokens[(__VU - 1) % data.tokens.length], 'POST /appointments'));
    check(res, { 'booking 201 or 409': (x) => x.status === 201 || x.status === 409 });
  }
}
