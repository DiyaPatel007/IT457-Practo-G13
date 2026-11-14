// Experiment 4: concurrent booking of the SAME slot.
//
//   python ../../scripts/reset.py --stack practo-lite          (fresh slots first!)
//   k6 run -e SEED=../../scripts/.seed/seed-output.json -e SLOTS=10 -e RACERS=100 \
//          --out csv=../../results/exp4-race/transactional/run1/k6.csv.gz race.js
//   python ../../scripts/consistency_check.py --stack practo-lite --out ../../results/exp4-race/transactional/run1/consistency.json
//
// For each of SLOTS slots of one doctor, RACERS virtual users fire one booking each at the same moment
// (one scenario per slot, started GAP seconds apart). Expected with the transactional mode:
// exactly one 201 per slot, everything else 409. Repeat with the stack deployed with BookingMode=naive.
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import { baseUrl, futureSlots, jsonHeaders, patientTokens } from './lib/common.js';

const seed = JSON.parse(open(__ENV.SEED || '../../scripts/.seed/seed-output.json'));
const BASE = baseUrl(seed);
const SLOTS = Number(__ENV.SLOTS || 10);
const RACERS = Number(__ENV.RACERS || 100);
const GAP = Number(__ENV.GAP_SECONDS || 5);
const DOCTOR = seed.doctors[Number(__ENV.DOCTOR_INDEX || 0)];

const booked = new Counter('booked_201');
const taken = new Counter('conflict_409');
const capacity = new Counter('capacity_503');
const other = new Counter('other_status');

const scenarios = {};
const thresholds = {};
for (let i = 0; i < SLOTS; i++) {
  scenarios[`slot_${i}`] = {
    executor: 'shared-iterations',
    vus: RACERS,
    iterations: RACERS,
    startTime: `${i * GAP}s`,
    maxDuration: '30s',
    exec: 'race',
    env: { SLOT_INDEX: String(i) },
  };
  // Trivial thresholds make k6 print the counters per slot in the end-of-test summary.
  thresholds[`booked_201{scenario:slot_${i}}`] = ['count>=0'];
}

export const options = { scenarios, thresholds, setupTimeout: '120s' };

http.setResponseCallback(http.expectedStatuses(201, 409));

export function setup() {
  const slots = futureSlots(DOCTOR).slice(0, SLOTS);
  if (slots.length < SLOTS) {
    throw new Error(`Doctor has only ${slots.length} future slots; run scripts/reset.py --reslot`);
  }
  return { tokens: patientTokens(seed, 20), slots };
}

export function race(data) {
  const slot = data.slots[Number(__ENV.SLOT_INDEX)];
  const token = data.tokens[(__VU - 1) % data.tokens.length];
  const res = http.post(`${BASE}/appointments`, JSON.stringify({ doctorId: DOCTOR.doctorId, slotStart: slot }),
    jsonHeaders(token, 'POST /appointments'));
  if (res.status === 201) booked.add(1);
  else if (res.status === 409) taken.add(1);
  else if (res.status === 503) capacity.add(1);
  else other.add(1, { status: String(res.status) });
}
