// Load test for the protocol comparison and the resilience experiments.
//
//   MODE=grpc     -> straight to the service over gRPC   (vote-service :9092, poll-service :9091)
//   MODE=rest     -> straight to the service over REST    (vote-service :8092, poll-service :8091)
//   MODE=gateway  -> through the public API (:8080); the gateway's internal protocol is set via /admin/settings
//   OP=vote | results | list        RATE=requests per second     DURATION=30s
//
// Example: k6 run -e MODE=grpc -e OP=vote -e RATE=3000 loadtest/bench.js
import http from 'k6/http';
import grpc from 'k6/net/grpc';
import exec from 'k6/execution';
import { Rate } from 'k6/metrics';

const MODE = __ENV.MODE || 'grpc';
const OP = __ENV.OP || 'vote';
const RATE = Number(__ENV.RATE || 3000);
const DURATION = __ENV.DURATION || '30s';
const NAME = __ENV.NAME || `${MODE}-${OP}-${RATE}`;
const LIST_LIMIT = Number(__ENV.LIST_LIMIT || 500);
const HOST = __ENV.HOST || 'localhost';

const ok = new Rate('ok');

export const options = {
  scenarios: {
    load: {
      // Open model: k6 starts RATE requests per second no matter how slow the server is
      // (a closed model would hide overload by slowing down the sender).
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Number(__ENV.VUS || 100),
      maxVUs: Number(__ENV.MAX_VUS || 400), // a laptop can't hold thousands of connections
    },
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
  discardResponseBodies: true,
};

const client = new grpc.Client();
client.load(['../api/src/main/proto'], 'polls.proto');

// Fetch the seeded polls and their option ids once.
export function setup() {
  // Directly from poll-service with a generous deadline (1000 polls don't fit into the gateway's 100 ms budget).
  const res = http.get(`http://${HOST}:8091/internal/polls?limit=1000`,
    { responseType: 'text', headers: { 'X-Deadline-Ms': '5000' } });
  const polls = JSON.parse(res.body).polls
    .filter((p) => p.question.startsWith('Seed poll #'))
    .map((p) => ({ id: p.poll_id, opts: p.options.map((o) => o.option_id) }));
  if (polls.length === 0) throw new Error('no polls: run loadtest/seed.sql first');
  return { polls };
}

const JSON_HEADERS = { 'Content-Type': 'application/json', 'X-Deadline-Ms': '1000' };

export default function (data) {
  // Every iteration gets a unique (poll, user) pair, so every vote is a real INSERT.
  const i = exec.scenario.iterationInTest;
  const user = (i % 100000) + 1;
  const poll = OP === 'vote' ? data.polls[Math.floor(i / 100000) % data.polls.length] : data.polls[i % data.polls.length];
  const option = poll.opts[i % poll.opts.length];

  if (MODE === 'grpc') {
    if (exec.vu.iterationInScenario === 0) {
      const port = OP === 'list' ? 9091 : 9092;
      client.connect(`${HOST}:${port}`, { plaintext: true, timeout: '2s' });
    }
    let res;
    if (OP === 'vote') {
      res = client.invoke('polls.v1.VoteService/CastVote', { poll_id: poll.id, user_id: user, option_id: option }, { timeout: '1s' });
    } else if (OP === 'results') {
      res = client.invoke('polls.v1.VoteService/GetResults', { poll_id: poll.id }, { timeout: '1s' });
    } else {
      res = client.invoke('polls.v1.PollService/ListPolls', { after_id: 0, limit: LIST_LIMIT }, { timeout: '1s' });
    }
    ok.add(res.status === grpc.StatusOK);
    return;
  }

  let res;
  if (MODE === 'rest') {
    if (OP === 'vote') {
      res = http.post(`http://${HOST}:8092/internal/polls/${poll.id}/votes`, JSON.stringify({ option_id: option }),
        { headers: Object.assign({ 'X-User-Id': String(user) }, JSON_HEADERS), timeout: '1s' });
    } else if (OP === 'results') {
      res = http.get(`http://${HOST}:8092/internal/polls/${poll.id}/results`, { headers: JSON_HEADERS, timeout: '1s' });
    } else {
      res = http.get(`http://${HOST}:8091/internal/polls?limit=${LIST_LIMIT}`, { headers: JSON_HEADERS, timeout: '1s' });
    }
    ok.add(res.status === 200);
    return;
  }

  // MODE=gateway: the real public API, end to end.
  if (OP === 'vote') {
    res = http.post(`http://${HOST}:8080/v1/polls/${poll.id}/votes`, JSON.stringify({ option_id: option }),
      { headers: { 'Content-Type': 'application/json', 'X-User-Id': String(user) }, timeout: '2s' });
    ok.add(res.status === 201 || res.status === 200);
  } else if (OP === 'results') {
    res = http.get(`http://${HOST}:8080/v1/polls/${poll.id}/results`, { timeout: '2s' });
    ok.add(res.status === 200);
  } else {
    res = http.get(`http://${HOST}:8080/v1/polls?limit=${LIST_LIMIT}`, { timeout: '2s' });
    ok.add(res.status === 200);
  }
}

// One markdown table row per run -> results/<NAME>.row (REPORT.md is built from these).
export function handleSummary(data) {
  const m = data.metrics;
  const dur = (MODE === 'grpc' ? m.grpc_req_duration : m.http_req_duration) || { values: {} };
  const v = dur.values;
  const iters = m.iterations ? m.iterations.values.count : 0;
  const rps = m.iterations ? m.iterations.values.rate : 0;
  const per = (x) => (x ? (x.values.count / Math.max(iters, 1)).toFixed(0) : '0');
  const f = (x) => (x === undefined ? '-' : x.toFixed(2));
  const row = `| ${NAME} | ${rps.toFixed(0)} | ${f(v['p(50)'])} | ${f(v['p(95)'])} | ${f(v['p(99)'])} | ${f(v.max)} | `
    + `${((m.ok ? m.ok.values.rate : 0) * 100).toFixed(2)}% | ${m.dropped_iterations ? m.dropped_iterations.values.count : 0} | `
    + `${per(m.data_sent)} | ${per(m.data_received)} |`;
  return {
    [`results/${NAME}.row`]: row + '\n',
    [`results/${NAME}.json`]: JSON.stringify(data, null, 1),
    stdout: row + '\n',
  };
}
