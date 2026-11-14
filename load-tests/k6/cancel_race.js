// Experiment 4b: cancel while others try to re-book the same slot.
//
//   k6 run -e SEED=../../scripts/.seed/seed-output.json -e RACERS=20 cancel_race.js
//   then: python ../../scripts/consistency_check.py --stack practo-lite
//
// setup(): patient01 books one slot. Then one VU cancels it after 1 s, while RACERS other patients keep
// trying to book the same slot every 200 ms (20 racers = about 100 req/s, below the API throttle).
// Expected: the cancel succeeds once, exactly ONE
// re-booking gets 201 (only after the cancel), and the table still satisfies the invariant.
import http from 'k6/http';
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { baseUrl, futureSlots, jsonHeaders, patientTokens } from './lib/common.js';

const seed = JSON.parse(open(__ENV.SEED || '../../scripts/.seed/seed-output.json'));
const BASE = baseUrl(seed);
const RACERS = Number(__ENV.RACERS || 20);
const DOCTOR = seed.doctors[Number(__ENV.DOCTOR_INDEX || 1)];

const rebooked = new Counter('rebooked_201');
const cancelled = new Counter('cancel_200');

export const options = {
  scenarios: {
    canceller: { executor: 'per-vu-iterations', vus: 1, iterations: 1, startTime: '1s', exec: 'cancel' },
    rebookers: { executor: 'per-vu-iterations', vus: RACERS, iterations: 1, exec: 'rebook', maxDuration: '20s' },
  },
  thresholds: { rebooked_201: ['count==1'], cancel_200: ['count==1'] },
};

http.setResponseCallback(http.expectedStatuses(200, 201, 409));

export function setup() {
  const tokens = patientTokens(seed, 20);
  const slot = futureSlots(DOCTOR)[0];
  const res = http.post(`${BASE}/appointments`, JSON.stringify({ doctorId: DOCTOR.doctorId, slotStart: slot }),
    jsonHeaders(tokens[0], 'POST /appointments'));
  if (res.status !== 201) {
    throw new Error(`setup booking failed: ${res.status} ${res.body}; run scripts/reset.py first`);
  }
  return { tokens, slot, appointmentId: res.json('appointmentId') };
}

export function cancel(data) {
  const res = http.post(`${BASE}/appointments/${data.appointmentId}/cancel`, null,
    jsonHeaders(data.tokens[0], 'POST /appointments/{id}/cancel'));
  if (res.status === 200) cancelled.add(1);
}

export function rebook(data) {
  const token = data.tokens[1 + ((__VU - 1) % (data.tokens.length - 1))]; // never patient01
  for (let attempt = 0; attempt < 25; attempt++) {
    const res = http.post(`${BASE}/appointments`, JSON.stringify({ doctorId: DOCTOR.doctorId, slotStart: data.slot }),
      jsonHeaders(token, 'POST /appointments'));
    if (res.status === 201) {
      rebooked.add(1);
      return;
    }
    sleep(0.2);
  }
}
