// k6 load test: many virtual users race to hold and book random seats, flash-sale style.
//
// Every iteration:
//   1. POST /api/holds on a random seat (201 = won, 409 = someone else has it)
//   2. on a win, POST /api/bookings with a fresh Idempotency-Key (expect 201)
//   3. replay the same booking request with the same key (expect 200 and the SAME bookingId)
//
// The run fails if any response has an unexpected status or a replay returns a different booking.
// Double bookings are checked afterwards with SQL against PostgreSQL (see README).
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SHOWS = parseInt(__ENV.SHOWS || '4', 10);

export const options = {
  scenarios: {
    flash_sale: {
      executor: 'constant-vus',
      vus: parseInt(__ENV.VUS || '200', 10),
      duration: __ENV.DURATION || '60s',
    },
  },
  thresholds: {
    unexpected_status: ['count==0'],
    replay_mismatch: ['count==0'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const holdLatency = new Trend('hold_latency', true);
const confirmLatency = new Trend('confirm_latency', true);
const replayLatency = new Trend('replay_latency', true);
const holdsWon = new Counter('holds_won');
const holdsConflict = new Counter('holds_conflict');
const bookingsCreated = new Counter('bookings_created');
const replaysOk = new Counter('replays_ok');
const replayMismatch = new Counter('replay_mismatch');
const unexpectedStatus = new Counter('unexpected_status');

const JSON_HEADERS = { 'Content-Type': 'application/json' };

export function setup() {
  const seatIds = [];
  for (let s = 0; s < SHOWS; s++) {
    const show = http.post(`${BASE_URL}/api/shows`, JSON.stringify({
      name: `Load test show ${s + 1}`,
      startsAt: '2027-01-01T18:00:00Z',
      rows: 26,
      seatsPerRow: 100,
    }), { headers: JSON_HEADERS });
    if (show.status !== 201) {
      throw new Error(`could not create show: ${show.status} ${show.body}`);
    }
    const seats = http.get(`${BASE_URL}/api/shows/${show.json('id')}/seats`);
    for (const seat of seats.json()) {
      seatIds.push(seat.id);
    }
  }
  return { seatIds };
}

export default function (data) {
  const seatId = data.seatIds[Math.floor(Math.random() * data.seatIds.length)];
  const customerId = `vu-${__VU}-it-${__ITER}`;

  const hold = http.post(`${BASE_URL}/api/holds`, JSON.stringify({ seatId, customerId }),
    { headers: JSON_HEADERS, tags: { name: 'POST /api/holds' }, responseCallback: http.expectedStatuses(201, 409) });
  holdLatency.add(hold.timings.duration);
  if (hold.status === 409) {
    holdsConflict.add(1);
    return;
  }
  if (hold.status !== 201) {
    unexpectedStatus.add(1, { step: 'hold', status: String(hold.status) });
    return;
  }
  holdsWon.add(1);

  const key = `k6-${__VU}-${__ITER}-${Date.now()}`;
  const body = JSON.stringify({ holdId: hold.json('holdId'), customerId });
  const headers = Object.assign({ 'Idempotency-Key': key }, JSON_HEADERS);

  const booking = http.post(`${BASE_URL}/api/bookings`, body, { headers, tags: { name: 'POST /api/bookings' } });
  confirmLatency.add(booking.timings.duration);
  if (!check(booking, { 'booking created (201)': (r) => r.status === 201 })) {
    unexpectedStatus.add(1, { step: 'confirm', status: String(booking.status) });
    return;
  }
  bookingsCreated.add(1);

  const replay = http.post(`${BASE_URL}/api/bookings`, body, { headers, tags: { name: 'POST /api/bookings (replay)' } });
  replayLatency.add(replay.timings.duration);
  const sameBooking = replay.status === 200 && replay.json('bookingId') === booking.json('bookingId');
  if (check(replay, { 'replay returns same booking (200)': () => sameBooking })) {
    replaysOk.add(1);
  } else {
    replayMismatch.add(1);
  }
}

export function handleSummary(data) {
  return {
    '/load/results/summary.json': JSON.stringify(data, null, 2),
    stdout: textSummary(data),
  };
}

function textSummary(data) {
  const m = data.metrics;
  const v = (name, field) => (m[name] && m[name].values[field] !== undefined ? m[name].values[field] : 0);
  const ms = (name, field) => `${v(name, field).toFixed(1)} ms`;
  return [
    '',
    '==== booking-race summary ====',
    `virtual users            : ${__ENV.VUS || 200} for ${__ENV.DURATION || '60s'}`,
    `http requests            : ${v('http_reqs', 'count')} (${v('http_reqs', 'rate').toFixed(1)}/s)`,
    `holds won / conflicts    : ${v('holds_won', 'count')} / ${v('holds_conflict', 'count')}`,
    `bookings created         : ${v('bookings_created', 'count')}`,
    `idempotent replays ok    : ${v('replays_ok', 'count')}  mismatches: ${v('replay_mismatch', 'count')}`,
    `unexpected statuses      : ${v('unexpected_status', 'count')}`,
    `hold    p50/p95/p99      : ${ms('hold_latency', 'med')} / ${ms('hold_latency', 'p(95)')} / ${ms('hold_latency', 'p(99)')}`,
    `confirm p50/p95/p99      : ${ms('confirm_latency', 'med')} / ${ms('confirm_latency', 'p(95)')} / ${ms('confirm_latency', 'p(99)')}`,
    `replay  p50/p95/p99      : ${ms('replay_latency', 'med')} / ${ms('replay_latency', 'p(95)')} / ${ms('replay_latency', 'p(99)')}`,
    `all requests p95         : ${ms('http_req_duration', 'p(95)')}`,
    '',
  ].join('\n');
}
