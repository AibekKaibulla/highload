Run manually after run-all.sh (same stack, chaos vote-service latency 150 ms on 5% of calls, gRPC, deadline 100 ms):
| run | RPS | p50 ms | p95 ms | p99 ms | max ms | success | dropped | bytes sent/req | bytes recv/req |
|---|---|---|---|---|---|---|---|---|---|
| base-1000 | 991 | 7.21 | 101.28 | 116.47 | 184.22 | 94.46% | 129 | 176 | 193 |
| hedge50-1000 | 985 | 8.33 | 109.97 | 161.27 | 249.99 | 91.42% | 323 | 176 | 190 |
| base-500 | 498 | 4.27 | 62.78 | 101.76 | 132.04 | 95.01% | 0 | 175 | 203 |
| hedge50-500 | 497 | 4.81 | 62.68 | 128.06 | 215.57 | 97.67% | 1 | 175 | 197 |
