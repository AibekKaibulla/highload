// Experiment: why poll creation needs an Idempotency-Key.
// poll-service is set to commit the poll and then "lose" the response (fail_after_rate).
// The client retries every 503 up to 3 times.
//   KEY=reuse -> the same key on every retry  -> expected: 0 duplicate polls
//   KEY=none  -> no key                        -> expected: duplicates ~ fail_after_rate
// Talks to poll-service REST directly, because the public gateway always requires a key.
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const KEY = __ENV.KEY || 'reuse';
const RUN = __ENV.RUN || 'run';
const attempts = new Counter('create_attempts');
const created = new Counter('create_ok');

export const options = {
  scenarios: { create: { executor: 'shared-iterations', vus: 10, iterations: Number(__ENV.N || 500) } },
};

function uuid() {
  const h = '0123456789abcdef';
  let s = '';
  for (let i = 0; i < 32; i++) s += h[Math.floor(Math.random() * 16)];
  return `${s.slice(0, 8)}-${s.slice(8, 12)}-4${s.slice(13, 16)}-8${s.slice(17, 20)}-${s.slice(20)}`;
}

export default function () {
  const i = exec.scenario.iterationInTest;
  const body = JSON.stringify({ question: `idem-${RUN}-${i}`, options: ['yes', 'no'] });
  const headers = { 'Content-Type': 'application/json', 'X-User-Id': String((i % 1000) + 1), 'X-Deadline-Ms': '1000' };
  if (KEY === 'reuse') headers['Idempotency-Key'] = uuid(); // generated ONCE per logical action

  for (let attempt = 1; attempt <= 3; attempt++) {
    attempts.add(1);
    const res = http.post('http://localhost:8091/internal/polls', body, { headers, timeout: '2s' });
    if (res.status === 200) {
      created.add(1);
      return;
    }
    if (res.status !== 503) return; // only retry transient errors
  }
}

export function handleSummary(data) {
  const m = data.metrics;
  const line = `logical=${m.iterations.values.count} attempts=${m.create_attempts.values.count} ok=${m.create_ok ? m.create_ok.values.count : 0}`;
  return { stdout: line + '\n' };
}
