#!/usr/bin/env bash
# Runs every SIS2 experiment and writes the tables to results/summary.md.
# Usage (from the repo root, with `docker compose up -d` running):   bash loadtest/run-all.sh
# Takes ~15 minutes. DUR=20s bash loadtest/run-all.sh for a shorter run.
set -uo pipefail
cd "$(dirname "$0")/.."
mkdir -p results
rm -f results/*.row results/summary.md
DUR=${DUR:-30s}
RATE=${RATE:-1000}      # sustained rate for the comparison; the step test below goes up to 3000
PSQL="docker compose exec -T postgres psql -U polls -d polls -tAq"
HEADER='| run | RPS | p50 ms | p95 ms | p99 ms | max ms | success | dropped | bytes sent/req | bytes recv/req |
|---|---|---|---|---|---|---|---|---|---|'
HEADER_AMP='| run | RPS | p50 ms | p95 ms | p99 ms | max ms | success | dropped | bytes sent/req | bytes recv/req | attempts/call |
|---|---|---|---|---|---|---|---|---|---|---|'

# gw protocol deadline attempt_timeout max_attempts jitter retry_on_deadline
gw() {
  curl -sf -X PUT localhost:8080/admin/settings -H 'Content-Type: application/json' -d \
    "{\"protocol\":\"$1\",\"deadline_ms\":$2,\"attempt_timeout_ms\":$3,\"max_attempts\":$4,\"base_backoff_ms\":10,\"max_backoff_ms\":50,\"jitter\":$5,\"retry_on_deadline\":$6}" >/dev/null
}
# chaos vote|poll latency_ms latency_rate fail_rate fail_after_rate
chaos() {
  local port=8091; [ "$1" = vote ] && port=8092
  curl -sf -X PUT localhost:$port/admin/chaos -H 'Content-Type: application/json' -d \
    "{\"latency_ms\":$2,\"latency_rate\":$3,\"fail_rate\":$4,\"fail_after_rate\":$5}" >/dev/null
}
chaos_off() { chaos vote 0 0 0 0; chaos poll 0 0 0 0; }
reset_votes() { $PSQL -c "TRUNCATE votes" >/dev/null; docker compose exec -T redis redis-cli FLUSHALL >/dev/null; }
# bench NAME MODE OP RATE [extra k6 args]
bench() {
  local name=$1 mode=$2 op=$3 rate=$4; shift 4
  k6 run -q -e NAME="$name" -e MODE="$mode" -e OP="$op" -e RATE="$rate" -e DURATION="$DUR" "$@" loadtest/bench.js \
    >/dev/null 2>"results/$name.log"
  cat "results/$name.row" 2>/dev/null || echo "| $name | k6 failed, see results/$name.log |"
}
# gateway attempt/call counters for cast_vote, to measure retry amplification
snap() {
  curl -s localhost:8080/actuator/prometheus | awk '
    /^gateway_backend_call_seconds_count\{.*method="cast_vote"/ {c+=$NF}
    /^gateway_backend_attempt_seconds_count\{.*method="cast_vote"/ {a+=$NF}
    END {printf "%d %d\n", c, a}'
}
# bench through the gateway and append "attempts per call" to the row
bench_gw() {
  local before after
  before=$(snap)
  local row; row=$(bench "$@")
  after=$(snap)
  local amp
  amp=$(echo "$before $after" | awk '{dc=$3-$1; da=$4-$2; if (dc>0) printf "%.3f", da/dc; else print "-"}')
  echo "$row $amp |"
}

echo "== seed"
if [ "$($PSQL -c "SELECT count(*) FROM polls WHERE question LIKE 'Seed poll #%'")" -lt 1000 ]; then
  $PSQL < loadtest/seed.sql
fi
chaos_off
gw grpc 100 0 3 true false

echo "== warm-up (JIT, connection pools)"
# The JVMs need ~1 min of traffic before latency is stable (JIT compilation).
for round in 1 2; do
  for m in grpc rest gateway; do DUR=20s bench "warmup-$m" $m vote 1000 >/dev/null; done
  gw rest 100 0 3 true false; DUR=20s bench warmup-gw-rest gateway vote 1000 >/dev/null
  gw grpc 100 0 3 true false; DUR=10s bench warmup-gw-results gateway results 1000 >/dev/null
  for m in grpc rest; do DUR=10s bench "warmup-$m-list" $m list 100 >/dev/null; done
  reset_votes
done
reset_votes

{
echo "## E1. Protocol comparison: gRPC vs REST/JSON (no faults)"
echo
echo "Direct = k6 calls the service itself. Gateway = public REST API, gateway -> service over the given protocol."
echo
echo "$HEADER"
for p in grpc rest; do reset_votes; bench "direct-$p-vote" $p vote $RATE; done
for p in grpc rest; do bench "direct-$p-results" $p results $RATE; done
for p in grpc rest; do bench "direct-$p-list500" $p list 50; done
for p in grpc rest; do gw $p 100 0 3 true false; reset_votes; bench "gateway-$p-vote" gateway vote $RATE; done
for p in grpc rest; do gw $p 100 0 3 true false; bench "gateway-$p-results" gateway results $RATE; done
for p in grpc rest; do gw $p 1000 0 3 true false; bench "gateway-$p-list500" gateway list 50; done
echo
echo "## E1b. Step test: votes through the gateway at increasing rates (where does it break?)"
echo
echo "$HEADER"
for r in 500 1000 1500 2000 3000; do
  for p in grpc rest; do gw $p 100 0 3 true false; reset_votes; DUR=20s bench "step-$p-$r" gateway vote $r; done
done
echo
echo "## E2. Deadlines: 5% of votes take +150 ms (tail latency), gateway over gRPC, 1000 RPS"
echo
echo "$HEADER_AMP"
chaos vote 150 0.05 0 0
for d in 50 100 200 300; do gw grpc $d 0 3 true false; reset_votes; bench_gw "deadline-${d}ms" gateway vote 1000; done
# per-attempt timeout + retry of slow attempts: helps only with spare capacity
for r in 500 1000; do
  gw grpc 100 0 3 true false; reset_votes; bench_gw "deadline-100ms-$r-rps" gateway vote $r
  gw grpc 100 50 3 true true; reset_votes; bench_gw "deadline-100ms-attempt-50ms-retry-$r-rps" gateway vote $r
done
chaos_off
echo
echo "## E3. Retries: vote-service fails a share of calls with UNAVAILABLE, gateway over gRPC, 1000 RPS"
echo
echo "$HEADER_AMP"
chaos vote 0 0 0.10 0
gw grpc 100 0 1 true false;  reset_votes; bench_gw "fail10-no-retry" gateway vote 1000
gw grpc 100 0 3 false false; reset_votes; bench_gw "fail10-3x-no-jitter" gateway vote 1000
gw grpc 100 0 3 true false;  reset_votes; bench_gw "fail10-3x-full-jitter" gateway vote 1000
chaos vote 0 0 0.50 0
gw grpc 100 0 1 true false;  reset_votes; bench_gw "fail50-no-retry" gateway vote 1000
gw grpc 100 0 3 true false;  reset_votes; bench_gw "fail50-3x-full-jitter" gateway vote 1000
chaos_off
gw grpc 100 0 3 true false
echo
echo "## E4. Idempotency: poll-service commits, then loses 30% of responses; client retries 503 up to 3x"
echo
echo "| key | result | duplicate polls created |"
echo "|---|---|---|"
chaos poll 0 0 0 0.30
for k in none reuse; do
  run="$k$RANDOM"
  out=$(k6 run -q -e KEY=$k -e RUN=$run -e N=500 loadtest/idempotency.js 2>/dev/null | tail -1)
  dup=$($PSQL -c "SELECT count(*) - count(DISTINCT question) FROM polls WHERE question LIKE 'idem-$run-%'")
  echo "| $k | $out | $dup |"
done
chaos_off
echo
echo "## E5. Payload size and encoding cost (same data, JSON vs Protobuf)"
echo
echo "| payload | JSON B | JSON gzip B | Protobuf B | Protobuf gzip B | JSON/Proto | JSON enc+dec µs | Proto enc+dec µs |"
echo "|---|---|---|---|---|---|---|---|"
curl -s "localhost:8080/admin/payload-sizes?limit=500" | python -c "
import json,sys
for r in json.load(sys.stdin):
    print('| {payload} | {json_bytes} | {json_gzip_bytes} | {protobuf_bytes} | {protobuf_gzip_bytes} | {json_to_protobuf_ratio} | {json_encode_decode_us} | {protobuf_encode_decode_us} |'.format(**r))"
} | tee results/summary.md

reset_votes
echo "Done -> results/summary.md"
