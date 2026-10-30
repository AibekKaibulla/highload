## E1. Protocol comparison: gRPC vs REST/JSON (no faults)

Direct = k6 calls the service itself. Gateway = public REST API, gateway -> service over the given protocol.

| run | RPS | p50 ms | p95 ms | p99 ms | max ms | success | dropped | bytes sent/req | bytes recv/req |
|---|---|---|---|---|---|---|---|---|---|
| direct-grpc-vote | 997 | 3.75 | 18.03 | 48.86 | 135.08 | 100.00% | 28 | 71 | 72 |
| direct-rest-vote | 998 | 3.68 | 9.71 | 25.17 | 77.59 | 100.00% | 0 | 203 | 186 |
| direct-grpc-results | 997 | 1.05 | 3.70 | 27.36 | 99.51 | 100.00% | 26 | 66 | 88 |
| direct-rest-results | 997 | 1.04 | 2.11 | 5.98 | 150.00 | 100.00% | 46 | 149 | 288 |
| direct-grpc-list500 | 50 | 6.41 | 12.42 | 16.77 | 35.84 | 100.00% | 0 | 120 | 73435 |
| direct-rest-list500 | 50 | 4.26 | 7.23 | 10.56 | 73.58 | 100.00% | 0 | 147 | 147760 |
| gateway-grpc-vote | 998 | 5.52 | 18.97 | 41.55 | 111.07 | 100.00% | 10 | 176 | 186 |
| gateway-rest-vote | 963 | 7.97 | 107.48 | 1136.40 | 1442.51 | 95.53% | 1060 | 176 | 187 |
| gateway-grpc-results | 998 | 1.59 | 4.79 | 11.23 | 78.13 | 100.00% | 0 | 90 | 314 |
| gateway-rest-results | 990 | 2.11 | 5.88 | 17.83 | 213.26 | 100.00% | 146 | 90 | 314 |
| gateway-grpc-list500 | 50 | 5.60 | 9.39 | 15.18 | 180.13 | 100.00% | 0 | 88 | 147760 |
| gateway-rest-list500 | 50 | 6.92 | 11.32 | 16.65 | 67.94 | 100.00% | 0 | 88 | 147760 |

## E1b. Step test: votes through the gateway at increasing rates (where does it break?)

| run | RPS | p50 ms | p95 ms | p99 ms | max ms | success | dropped | bytes sent/req | bytes recv/req |
|---|---|---|---|---|---|---|---|---|---|
| step-grpc-500 | 499 | 4.72 | 12.72 | 27.76 | 73.85 | 100.00% | 0 | 175 | 207 |
| step-rest-500 | 496 | 7.53 | 138.21 | 195.38 | 316.75 | 91.63% | 36 | 175 | 209 |
| step-grpc-1000 | 997 | 5.15 | 19.73 | 39.48 | 72.87 | 100.00% | 0 | 175 | 191 |
| step-rest-1000 | 997 | 6.57 | 27.02 | 45.12 | 112.92 | 100.00% | 0 | 175 | 191 |
| step-grpc-1500 | 1473 | 8.07 | 65.79 | 114.48 | 273.96 | 98.04% | 440 | 176 | 188 |
| step-rest-1500 | 1360 | 106.94 | 232.80 | 1164.86 | 1288.17 | 33.66% | 2670 | 176 | 208 |
| step-grpc-2000 | 1867 | 109.00 | 168.86 | 302.34 | 455.60 | 18.85% | 2350 | 176 | 294 |
| step-rest-2000 | 1731 | 141.18 | 304.93 | 392.04 | 479.30 | 4.09% | 5038 | 176 | 216 |
| step-grpc-3000 | 2161 | 136.28 | 320.44 | 401.20 | 517.41 | 2.26% | 16304 | 176 | 315 |
| step-rest-3000 | 1917 | 152.14 | 352.47 | 566.59 | 727.94 | 1.78% | 20981 | 176 | 217 |

## E2. Deadlines: 5% of votes take +150 ms (tail latency), gateway over gRPC, 1000 RPS

| run | RPS | p50 ms | p95 ms | p99 ms | max ms | success | dropped | bytes sent/req | bytes recv/req | attempts/call |
|---|---|---|---|---|---|---|---|---|---|---|
| deadline-50ms | 991 | 5.94 | 66.90 | 107.03 | 225.70 | 87.21% | 166 | 176 | 203 | 1.000 |
| deadline-100ms | 995 | 5.27 | 100.53 | 102.27 | 142.55 | 94.98% | 21 | 176 | 192 | 1.000 |
| deadline-200ms | 994 | 5.32 | 154.30 | 158.89 | 202.03 | 100.00% | 0 | 176 | 186 | 1.000 |
| deadline-300ms | 994 | 5.29 | 88.49 | 158.54 | 205.61 | 100.00% | 8 | 176 | 186 | 1.000 |
| deadline-100ms-attempt-30ms-retry | 996 | 5.52 | 101.34 | 110.39 | 169.75 | 94.46% | 61 | 176 | 189 | 1.156 |

## E3. Retries: vote-service fails a share of calls with UNAVAILABLE, gateway over gRPC, 1000 RPS

| run | RPS | p50 ms | p95 ms | p99 ms | max ms | success | dropped | bytes sent/req | bytes recv/req | attempts/call |
|---|---|---|---|---|---|---|---|---|---|---|
| fail10-no-retry | 998 | 5.35 | 21.15 | 39.47 | 93.60 | 90.21% | 0 | 176 | 190 | 1.000 |
| fail10-3x-no-jitter | 998 | 5.66 | 25.19 | 47.93 | 101.91 | 99.91% | 0 | 176 | 186 | 1.111 |
| fail10-3x-full-jitter | 998 | 5.60 | 19.15 | 32.35 | 66.57 | 99.91% | 0 | 176 | 186 | 1.111 |
| fail50-no-retry | 990 | 6.85 | 40.79 | 160.58 | 284.34 | 48.21% | 246 | 176 | 212 | 1.000 |
| fail50-3x-full-jitter | 979 | 14.16 | 73.47 | 172.12 | 487.90 | 84.89% | 531 | 176 | 195 | 1.750 |

## E4. Idempotency: poll-service commits, then loses 30% of responses; client retries 503 up to 3x

| key | result | duplicate polls created |
|---|---|---|
| none | logical=500 attempts=688 ok=492 | 188 |
| reuse | logical=500 attempts=698 ok=488 | 0 |

## E5. Payload size and encoding cost (same data, JSON vs Protobuf)

| payload | JSON B | JSON gzip B | Protobuf B | Protobuf gzip B | JSON/Proto | JSON enc+dec µs | Proto enc+dec µs |
|---|---|---|---|---|---|---|---|
| cast_vote response | 46 | 61 | 6 | 26 | 7.67 | 0.93 | 0.19 |
| get_results (2 options) | 87 | 80 | 10 | 27 | 8.7 | 1.32 | 0.14 |
| get_poll | 144 | 122 | 33 | 52 | 4.36 | 1.23 | 0.48 |
| list_polls (10 polls) | 1483 | 305 | 331 | 236 | 4.48 | 9.44 | 3.55 |
| list_polls (100 polls) | 14669 | 1645 | 3266 | 1364 | 4.49 | 108.13 | 43.23 |
| list_polls (500 polls) | 147287 | 12764 | 73123 | 11507 | 2.01 | 724.0 | 287.91 |
