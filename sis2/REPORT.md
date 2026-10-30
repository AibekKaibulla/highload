# SIS #2 — Protocol implementation: measurements and decisions

Polls & Voting service (design from SIS #1), implemented in Java 21 / Spring Boot 3.
**Public edge: REST/JSON. Internal calls (gateway → services): gRPC.** Every internal operation also
exists as REST/JSON (HTTP/1.1), so the two can be compared on the same code, data and machine.

All numbers below come from `bash loadtest/run-all.sh` (raw output: [results/summary.md](results/summary.md),
per-run k6 JSON in `results/`). Reproduce with `docker compose up --build -d` then the same script.

## Main results

| Topic | Result | Section |
|---|---|---|
| gRPC vs REST below capacity | Latency is almost the same (~4 ms per vote, mostly the Postgres transaction). gRPC sends **~2.6× fewer bytes** per call, mostly thanks to HTTP/2 header compression. | §2 |
| gRPC vs REST under load | At **1500 RPS** through the gateway, gRPC keeps **98%** of votes within the deadline, REST only **34%**. HTTP/2 multiplexes calls over one connection; HTTP/1.1 keeps opening new ones. | §2.2 |
| Capacity | ~**1500 RPS** end to end on one laptop (k6 + all containers share 8 vCPU / 4 GB). The 3000 RPS target needs horizontal scaling (SIS #4). | §2.3 |
| Payload size | Protobuf is **4–9× smaller** for small messages, only **2×** for a 500-poll list (mostly text). With gzip the big-list gap almost disappears (12.8 KB vs 11.5 KB). | §3 |
| Encoding CPU | JSON is 2.5–9× slower to encode/decode, but ~1 µs per vote doesn't matter next to a 4 ms DB call. The transport matters more than the encoding. | §3 |
| Deadline | **100 ms** (= SIS #1 p99 requirement): fails only the injected slow 5%, p99 stays at ~102 ms. 50 ms also cuts healthy requests; 200 ms pushes p99 to 159 ms. | §4 |
| Deadline propagation | gRPC sends deadline + cancellation to the server: **5 789** calls were skipped because the caller had already given up. REST can't do that. | §4 |
| Retries | 3 attempts with full jitter: 10% failures → **0.09%**, for 11% extra calls (matches 1 − f³). At 50% failures retries add **75%** more load, so retries happen in one layer only. | §5 |
| Idempotency | Poll creation with lost responses + client retries: **188** duplicate polls of 500 without a key, **0** with the `Idempotency-Key`. Votes are idempotent via `PK(poll_id, user_id)`. | §6 |

## 1. Setup and how to read the numbers

| | |
|---|---|
| Machine | one laptop, 8 vCPU, 8 GB RAM; Docker Desktop VM with 4 GB. **k6, gateway, both services, Postgres, Redis, Prometheus and Grafana all share it.** |
| Load | k6 `constant-arrival-rate` (open model: requests keep arriving even if the server slows down, so overload shows up as latency and errors instead of being hidden). 30 s per run. |
| Warm-up | ~2 min of traffic before measuring. Without it the first ~40 s after a restart are 10–100× slower (JIT compilation): one check run went from p99 1141 ms → 245 ms → 38 ms in three consecutive 20 s runs. |
| Columns | p50/p95/p99 = client-side latency seen by k6. `success` = share of requests with the expected status. `dropped` = requests k6 could not even start (the sender itself ran out of capacity). `bytes/req` = wire bytes incl. HTTP headers, measured by k6. |
| Modes | **direct** = k6 → service (isolates the service). **gateway** = k6 → public REST → gateway → service over gRPC or REST. Gateway mode is the fair protocol comparison: Java client on both sides. |

Laptop numbers are noisy (other processes, GC, Docker networking). A single bad row (e.g. `step-rest-500` below) should not be over-read; trends that repeat across runs are what matters.

## 2. Protocol comparison (E1)

### 2.1 Same operations, 1000 RPS, no faults

| run | RPS | p50 ms | p95 ms | p99 ms | success | bytes sent/req | bytes recv/req |
|---|---|---|---|---|---|---|---|
| direct-grpc-vote | 997 | 3.75 | 18.03 | 48.86 | 100.00% | **71** | **72** |
| direct-rest-vote | 998 | 3.68 | 9.71 | 25.17 | 100.00% | 203 | 186 |
| direct-grpc-results | 997 | 1.05 | 3.70 | 27.36 | 100.00% | 66 | 88 |
| direct-rest-results | 997 | 1.04 | 2.11 | 5.98 | 100.00% | 149 | 288 |
| direct-grpc-list500 (50 RPS) | 50 | 6.41 | 12.42 | 16.77 | 100.00% | 120 | **73 435** |
| direct-rest-list500 (50 RPS) | 50 | 4.26 | 7.23 | 10.56 | 100.00% | 147 | 147 760 |
| gateway-grpc-vote | 998 | 5.52 | 18.97 | **41.55** | **100.00%** | 176 | 186 |
| gateway-rest-vote | 963 | 7.97 | 107.48 | **1136.40** | **95.53%** | 176 | 187 |
| gateway-grpc-results | 998 | 1.59 | 4.79 | 11.23 | 100.00% | 90 | 314 |
| gateway-rest-results | 990 | 2.11 | 5.88 | 17.83 | 100.00% | 90 | 314 |
| gateway-grpc-list500 (50 RPS) | 50 | 5.60 | 9.39 | 15.18 | 100.00% | 88 | 147 760 |
| gateway-rest-list500 (50 RPS) | 50 | 6.92 | 11.32 | 16.65 | 100.00% | 88 | 147 760 |

(In gateway mode the public side is JSON in both cases, so the k6 byte columns are equal; the internal difference is in §3.)

### 2.2 Step test: votes through the gateway, rising rate

| rate | gRPC p99 ms | gRPC success | REST p99 ms | REST success |
|---|---|---|---|---|
| 500 | 27.8 | 100.00% | 195.4 | 91.63% (*) |
| 1000 | 39.5 | 100.00% | 45.1 | 100.00% |
| **1500** | **114.5** | **98.04%** | **1164.9** | **33.66%** |
| 2000 | 302.3 | 18.85% | 392.0 | 4.09% |
| 3000 | 401.2 | 2.26% (only 2161 RPS sent) | 566.6 | 1.78% (1917 RPS sent) |

(*) one-off hiccup; the same config was clean at 1000 RPS right after and in E1.

### 2.3 What this shows

1. **Below capacity, the protocol barely changes latency.** At 1000 RPS a vote costs ~4 ms, almost all of it the Postgres transaction (BEGIN, `SET LOCAL statement_timeout`, INSERT, COMMIT). Encoding a vote takes ~1 µs (JSON) vs ~0.2 µs (Protobuf), see §3. That is noise next to 4 ms.
2. **On the wire gRPC is ~2.6× smaller for small calls** (vote: 71 vs 203 B sent, 72 vs 186 B received). Most of this is **transport**, not encoding: HTTP/1.1 resends ~150 B of plain-text headers on every request; HTTP/2 compresses repeated headers (HPACK) to a few bytes. The body itself is 6 B (Protobuf) vs 46 B (JSON).
3. **Under load, the transport decides.** The gateway → service hop over REST collapses first: at 1500 RPS gRPC still serves 98% within the deadline, REST only 34%. HTTP/1.1 carries one request at a time per connection, so the client needs as many connections as there are requests in flight. By Little's Law that is 1500 RPS × 0.005 s ≈ 8 normally, but once latency grows the client keeps opening new TCP connections (handshake, socket, server thread each), which adds latency and makes it grow further. gRPC multiplexes all calls as streams over one long-lived HTTP/2 connection, so there is no new connection setup under load. (This explanation fits the data; counting sockets with `ss -tn` during a run would confirm it directly.)
4. **Direct gRPC looks slightly *worse* than direct REST in k6.** That is the load generator, not the service: k6 converts every Protobuf message into a JavaScript object, which is expensive, especially for the 73 KB list. The gateway rows (Java on both ends) are the fair comparison, and there gRPC is equal or better everywhere.
5. **Capacity of this laptop: ~1500 RPS end to end, not 3000.** The SIS #1 target of 3000 vote RPS is not reached on one machine where the load generator and all seven containers share 8 vCPUs and 4 GB. Above ~1500 RPS k6 itself starts dropping requests (`dropped` column). In production the gateway and vote-service are stateless and scale horizontally behind a load balancer (SIS #4). Postgres at 3000 single-row inserts/s and Redis at 3000 HINCRBY/s are well within their limits.

## 3. Payload size and encoding cost (E5)

Same real data serialized both ways inside the gateway (`GET /admin/payload-sizes`). enc+dec = encode + decode once, average.

| payload | JSON B | JSON gzip B | Protobuf B | Protobuf gzip B | JSON/Proto | JSON enc+dec µs | Proto enc+dec µs |
|---|---|---|---|---|---|---|---|
| cast_vote response | 46 | 61 | 6 | 26 | 7.67 | 0.93 | 0.19 |
| get_results | 87 | 80 | 10 | 27 | 8.70 | 1.32 | 0.14 |
| get_poll | 144 | 122 | 33 | 52 | 4.36 | 1.23 | 0.48 |
| list_polls (10) | 1 483 | 305 | 331 | 236 | 4.48 | 9.44 | 3.55 |
| list_polls (100) | 14 669 | 1 645 | 3 266 | 1 364 | 4.49 | 108.13 | 43.23 |
| list_polls (500) | 147 287 | 12 764 | 73 123 | 11 507 | 2.01 | 724.00 | 287.91 |

- **Small messages: Protobuf is 4–9× smaller.** JSON repeats every field name (`"option_id":`) as text. Protobuf sends a 1-byte field tag plus a varint, so a small id takes 1–3 bytes.
- **Large lists: only 2× smaller.** The 500-poll list is mostly text (questions, option labels), and text is the same size in both formats.
- **gzip is not serialization.** On the 500-poll list gzip shrinks JSON 11.5× and nearly closes the gap (12.8 KB vs 11.5 KB). On tiny messages gzip makes things *bigger* (46 → 61 B, header overhead). Compression also doesn't remove the parsing cost.
- **CPU: JSON is 2.5–9× slower to encode and decode**, but in absolute terms 3000 votes/s × ~1 µs = 3 ms of CPU per second. That doesn't matter here. For the 500-poll list, ~0.7 ms per call vs ~0.3 ms would matter at high rates.
- **Where the gap stops mattering:** for text-heavy payloads above ~100 items with gzip on, size differs by only ~1.2×. For our hot path (tiny vote messages at high rate) the size gap is large, but the bytes are so few that the **transport** (HTTP/2 multiplexing, header compression) is the bigger win (§2.3).

## 4. Deadlines and timeouts (E2)

Fault: vote-service makes **5% of calls 150 ms slower** (tail latency). Gateway → vote-service over gRPC, 1000 RPS.

| total deadline | p95 ms | p99 ms | success | attempts/call |
|---|---|---|---|---|
| 50 ms | 66.9 | 107.0 | 87.21% | 1.000 |
| **100 ms (chosen)** | 100.5 | **102.3** | **94.98%** | 1.000 |
| 200 ms | 154.3 | 158.9 | 100.00% | 1.000 |
| 300 ms | 88.5 | 158.5 | 100.00% | 1.000 |

- **50 ms is too tight.** It fails the injected 5% *and* ~8% of healthy requests, because under load the normal p99 on this laptop is ~40 ms.
- **100 ms fails almost exactly the injected 5%**, and latency stays bounded: p99 102 ms ≈ the deadline. The user gets a fast 504 instead of waiting, and can safely retry because votes are idempotent.
- **200/300 ms saves every request but p99 becomes ~159 ms**, which breaks the SIS #1 requirement (p99 < 100 ms).
- **Why 100 ms:** it *is* the SIS #1 latency requirement, and it is 2.5× the healthy p99, so healthy requests are not cut. If the product preferred "never fail a vote" over "always fast", 200 ms would be the choice.

**Per-attempt timeout + retry of slow attempts** (attempt timeout 50 ms inside the 100 ms budget, `retry_on_deadline=true`; [results/e2b-manual.md](results/e2b-manual.md)):

| rate | without | with per-attempt retry |
|---|---|---|
| 500 RPS | 95.01% success, p99 101.8 | **97.67%**, p99 128.1 |
| 1000 RPS | 94.46%, p99 116.5 | **91.42%** (worse), p99 161.3 |

It helps only when there is spare capacity. Near the limit, the extra attempts are extra load and make things worse. With a 30 ms attempt timeout (below the healthy p99) it also cut healthy requests (94.5%). **Decision: off by default.** Turn it on only if the attempt timeout is well above the healthy p99 *and* the service has headroom.

**Deadline propagation.** The gateway sets one budget per public request. Every attempt gets only what remains. gRPC sends the deadline to the server automatically (`withDeadlineAfter`). The server reads it and uses it as the Postgres `statement_timeout`, so the database stops work nobody is waiting for. gRPC also propagates **cancellation**: in E2 vote-service skipped **5 789** calls whose caller had already given up (`chaos_injected_total{type="work_skipped_cancelled"}`). REST has no deadline or cancellation built in. We send the remaining budget in an `X-Deadline-Ms` header, but the server can't find out that the client hung up mid-request.

**All timeouts and their reasons**

| Timeout | Value | Why |
|---|---|---|
| Public request budget (gateway) | 100 ms | = SIS #1 p99 requirement; 2.5× healthy p99 (E2) |
| gRPC deadline / REST request timeout per attempt | remaining budget | an attempt can never outlive the user's request |
| Postgres `statement_timeout` | remaining budget (`SET LOCAL`) | last hop of propagation; DB cancels useless work |
| Connect timeout (gateway → services) | 500 ms | TCP connect inside one network takes < 1 ms; 500 ms means "down", fail fast |
| HikariCP `connection-timeout` | 250 ms (Hikari's minimum) | pool exhausted → `UNAVAILABLE` quickly instead of queueing |
| Redis command timeout | 50 ms | Redis answers in ~1 ms; slower means trouble. A vote is still saved if HINCRBY times out (602 times during the overload steps); the reconciler repairs the counter. |
| Server cap when no deadline is sent | 1000 ms | no request can run forever |

## 5. Retries (E3)

Fault: vote-service fails a share of calls with `UNAVAILABLE` *before* doing work. Gateway over gRPC, 1000 RPS.

| failure rate | policy | success | expected | attempts/call | expected | p99 ms |
|---|---|---|---|---|---|---|
| 10% | no retry | 90.21% | 90% | 1.000 | 1 | 39.5 |
| 10% | 3 attempts, no jitter | 99.91% | 99.9% | 1.111 | 1.11 | 47.9 |
| 10% | 3 attempts, full jitter | 99.91% | 99.9% | 1.111 | 1.11 | **32.4** |
| 50% | no retry | 48.21% | 50% | 1.000 | 1 | 160.6 |
| 50% | 3 attempts, full jitter | 84.89% | 87.5% | **1.750** | 1.75 | 172.1 |

- **The measurements match the math:** success = 1 − f³, attempts = 1 + f + f². This shows the retry code does exactly what it should.
- **Retries are the cheap fix for a few transient errors:** 10% failures become 0.09% for 11% extra calls.
- **Retry amplification:** at 50% failures the struggling service receives **75% more load** (1.75 attempts per call) exactly when it is weakest. That is why retries happen in **one layer only** (the gateway; built-in gRPC retries are disabled, the services never retry). If three layers each retried 3×, one database blip could become 27× the traffic.
- **Jitter:** with independent random failures the effect is small (p99 32 vs 48 ms). Jitter matters most for *correlated* failures (a short outage where all clients fail together and, without jitter, retry at the same millisecond). This test doesn't create that case; it's listed under limitations.

**Retry policy, and why**

| Rule | Value | Why |
|---|---|---|
| What is retried | `UNAVAILABLE` only (+ `DEADLINE_EXCEEDED` if per-attempt timeouts are enabled) | transient. 400/401/404/409/422 are never retried: the second try would fail the same way |
| Which calls | all internal calls | each is idempotent: votes by `PK(poll_id, user_id)`, poll creation by `Idempotency-Key`, reads by nature |
| Max attempts | 3 | 99.9% success at 10% failure; more attempts mostly add amplification |
| Backoff | exponential 10 → 20 → 40 ms, capped at 50 ms, **full jitter** | fits a 100 ms budget; jitter spreads retries out |
| Budget check | no retry if remaining budget − backoff < 5 ms | a retry that can't finish in time is pure extra load |
| Where | gateway only (`ResilientCaller`) | no retry amplification across layers |

## 6. Idempotency (E4)

Fault: poll-service **commits the poll and then loses 30% of responses** (the timeout "unknown outcome" case). The client retries every 503 up to 3 times. 500 logical "create poll" actions.

| client behaviour | attempts | duplicate polls created |
|---|---|---|
| no Idempotency-Key | 688 | **188** |
| same Idempotency-Key on every retry | 698 | **0** |

Without the key, every retry after a lost response creates another poll: 188 of 500 polls are duplicates. With the key, the server finds the key it stored in the same transaction as the poll and returns the original response (`Idempotent-Replayed: true`).
- Key + poll + stored response are written in **one transaction**, so either all of them exist or none.
- `PRIMARY KEY (user_id, key)` makes two racing retries safe: the second one waits for the first to commit, then reads its result.
- The same key with a different body → **422**. Keys are deleted after 24 h.

**Votes need no key.** `PK(poll_id, user_id)` + `INSERT … ON CONFLICT DO NOTHING`: a retried vote returns 200 with the original vote, and the Redis counter is only incremented when a row was really inserted, so it never double-counts. The `votes_duplicates_total` metric counts the blocked duplicates.



