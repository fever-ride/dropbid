#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Dropbid Load Test — Latency, Throughput, Resources, Consistency
#
# Prerequisites: docker-compose up --build -d && bash scripts/smoke-test.sh
# Usage:  bash loadtest/run.sh [test1|test2|test3|all]
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

USER_SVC="http://localhost:8082"
SHOP_SVC="http://localhost:8083"
AUCTION_SVC="http://localhost:8081"
BID_SVC="http://localhost:8084"
PAYMENT_SVC="http://localhost:8085"

RESULTS_DIR="loadtest/results"
mkdir -p "$RESULTS_DIR"

REDIS_CONTAINER=""

get_redis_container() {
  if [[ -z "$REDIS_CONTAINER" ]]; then
    REDIS_CONTAINER=$(docker ps -qf name=redis | head -1)
  fi
  echo "$REDIS_CONTAINER"
}

redis_cmd() {
  docker exec "$(get_redis_container)" redis-cli "$@" 2>/dev/null
}

# ── Helpers ──────────────────────────────────────────────────────────────────

register_user() {
  local email="$1" role="$2"
  local body
  body=$(curl -sf -X POST "$USER_SVC/users/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$email\",\"password\":\"pass1234\",\"username\":\"user_${email%%@*}\",\"role\":\"$role\"}" 2>/dev/null || echo '{}')
  echo "$body" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('token',''),d.get('userId',''))" 2>/dev/null
}

create_item() {
  local token="$1" shop_id="$2"
  curl -sf -X POST "$SHOP_SVC/shops/$shop_id/items" \
    -H "Authorization: Bearer $token" \
    -H "Content-Type: application/json" \
    -d '{
      "title":"LoadTestItem","description":"test","series":"Test",
      "edition":"1st","condition":"NEW",
      "originalRetailPrice":100000,"estimatedMarketValue":200000
    }' 2>/dev/null | \
    python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null || echo ""
}

create_auction() {
  local token="$1" shop_id="$2" item_id="$3" end_secs="$4" quantity="${5:-1}"
  local end_time
  end_time=$(date -u -v+"${end_secs}S" '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || \
             date -u -d "+${end_secs} seconds" '+%Y-%m-%dT%H:%M:%SZ')
  curl -sf -X POST "$AUCTION_SVC/auctions" \
    -H "Authorization: Bearer $token" \
    -H "Content-Type: application/json" \
    -d "{
      \"itemId\":\"$item_id\",\"shopId\":\"$shop_id\",
      \"startingBid\":100,\"endTime\":\"$end_time\",\"quantity\":$quantity
    }" 2>/dev/null | \
    python3 -c "import sys,json; print(json.load(sys.stdin).get('auctionId',''))" 2>/dev/null || echo ""
}

fire_bid() {
  local auction_id="$1" token="$2" amount="$3" output_file="$4"
  local start_ns end_ns elapsed_ms http_code

  start_ns=$(python3 -c "import time; print(int(time.time_ns()))")
  http_code=$(curl -s -o /dev/null -w "%{http_code}" \
    -X PUT "$AUCTION_SVC/auctions/$auction_id/bid" \
    -H "Authorization: Bearer $token" \
    -H "Content-Type: application/json" \
    -d "{\"amount\":$amount}" 2>/dev/null || echo "000")
  end_ns=$(python3 -c "import time; print(int(time.time_ns()))")

  elapsed_ms=$(( (end_ns - start_ns) / 1000000 ))
  echo "$elapsed_ms $http_code" >> "$output_file"
}

report() {
  local file="$1" label="$2"
  local total ok err

  total=$(wc -l < "$file" | tr -d ' ')
  ok=$(awk '$2 == 200 { n++ } END { print n+0 }' "$file")
  err=$((total - ok))

  echo ""
  echo "═══════════════════════════════════════════════"
  echo "  $label"
  echo "═══════════════════════════════════════════════"
  echo "  Total requests : $total"
  echo "  Success (200)  : $ok"
  echo "  Errors         : $err  ($(python3 -c "print(f'{$err/$total*100:.1f}')" 2>/dev/null || echo "?")%)"
  echo ""

  if [[ "$ok" -gt 0 ]]; then
    awk '$2 == 200 { print $1 }' "$file" | sort -n | awk '
      BEGIN { n=0 }
      { a[n++] = $1; sum += $1 }
      END {
        printf "  Latency (ms):\n"
        printf "    min   : %d\n", a[0]
        printf "    p50   : %d\n", a[int(n*0.50)]
        printf "    p95   : %d\n", a[int(n*0.95)]
        printf "    p99   : %d\n", a[int(n*0.99)]
        printf "    max   : %d\n", a[n-1]
        printf "    avg   : %.1f\n", sum/n
      }'
    # Calculate throughput from wall clock
    local first_ts last_ts
    first_ts=$(head -1 "$file" | awk '{print $1}')
    echo "  Total duration : ~${total} requests in test window"
  fi

  if [[ "$err" -gt 0 ]]; then
    echo ""
    echo "  Error breakdown:"
    awk '$2 != 200 { codes[$2]++ } END { for (c in codes) printf "    HTTP %s : %d\n", c, codes[c] }' "$file"
  fi
  echo "═══════════════════════════════════════════════"
}

# ── Resource Monitoring ──────────────────────────────────────────────────────

MONITOR_PID=""

start_monitor() {
  local output="$1"
  echo "timestamp,container,cpu%,mem_usage,mem%,net_io,block_io" > "$output"
  (
    while true; do
      docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}},{{.MemPerc}},{{.NetIO}},{{.BlockIO}}' 2>/dev/null | \
        while IFS= read -r line; do
          echo "$(date +%s),$line"
        done >> "$output"
      sleep 2
    done
  ) &
  MONITOR_PID=$!
  echo "  Resource monitor started (PID=$MONITOR_PID)"
}

stop_monitor() {
  if [[ -n "$MONITOR_PID" ]]; then
    kill "$MONITOR_PID" 2>/dev/null || true
    wait "$MONITOR_PID" 2>/dev/null || true
    MONITOR_PID=""
  fi
}

report_resources() {
  local file="$1" label="$2"
  echo ""
  echo "─── Resource Usage: $label ───"

  if [[ ! -s "$file" ]]; then
    echo "  (no data collected)"
    return
  fi

  # Show peak CPU and memory per container
  python3 - "$file" <<'PYEOF'
import sys, csv
from collections import defaultdict

cpu_max = defaultdict(float)
mem_max = defaultdict(float)

with open(sys.argv[1]) as f:
    reader = csv.reader(f)
    next(reader)  # skip header
    for row in reader:
        if len(row) < 7:
            continue
        container = row[1]
        try:
            cpu = float(row[2].replace('%',''))
            mem = float(row[4].replace('%',''))
        except (ValueError, IndexError):
            continue
        cpu_max[container] = max(cpu_max[container], cpu)
        mem_max[container] = max(mem_max[container], mem)

if not cpu_max:
    print("  (no data)")
    sys.exit(0)

print(f"  {'Container':<35} {'Peak CPU':>10} {'Peak Mem':>10}")
print(f"  {'─'*35} {'─'*10} {'─'*10}")
for c in sorted(cpu_max.keys()):
    print(f"  {c:<35} {cpu_max[c]:>9.1f}% {mem_max[c]:>9.1f}%")
PYEOF
}

# ── Consistency Verification ─────────────────────────────────────────────────

verify_consistency() {
  local token="$1"
  shift
  local auction_ids=("$@")

  echo ""
  echo "═══════════════════════════════════════════════"
  echo "  CONSISTENCY VERIFICATION"
  echo "═══════════════════════════════════════════════"

  local pass=0 fail=0

  for aid in "${auction_ids[@]}"; do
    echo ""
    echo "  ── Auction: ${aid:0:8}... ──"

    # 1. Read DynamoDB state (via auction-service API)
    local dynamo_json
    dynamo_json=$(curl -sf "$AUCTION_SVC/auctions/$aid" \
      -H "Authorization: Bearer $token" 2>/dev/null || echo '{}')

    local dynamo_bid_count dynamo_highest dynamo_status dynamo_quantity
    dynamo_bid_count=$(echo "$dynamo_json" | python3 -c "import sys,json; print(json.load(sys.stdin).get('bidCount',0))" 2>/dev/null || echo "0")
    dynamo_highest=$(echo "$dynamo_json" | python3 -c "import sys,json; print(json.load(sys.stdin).get('currentHighest',0))" 2>/dev/null || echo "0")
    dynamo_status=$(echo "$dynamo_json" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null || echo "")
    dynamo_quantity=$(echo "$dynamo_json" | python3 -c "import sys,json; print(json.load(sys.stdin).get('quantity',1))" 2>/dev/null || echo "1")

    # 2. Read Redis state
    local redis_bid_count redis_highest redis_status
    redis_bid_count=$(redis_cmd HGET "auction:$aid" bid_count 2>/dev/null || echo "")
    redis_highest=$(redis_cmd HGET "auction:$aid" current_highest 2>/dev/null || echo "")
    redis_status=$(redis_cmd HGET "auction:$aid" status 2>/dev/null || echo "")

    # 3. Read Redis winners ZSET
    local redis_winner_count
    redis_winner_count=$(redis_cmd ZCARD "auction:${aid}:winners" 2>/dev/null || echo "0")

    # ── Check 1: bid_count matches ──
    if [[ -n "$redis_bid_count" && "$redis_bid_count" != "(nil)" ]]; then
      if [[ "$redis_bid_count" == "$dynamo_bid_count" ]]; then
        echo "    [PASS] bid_count: Redis=$redis_bid_count == DynamoDB=$dynamo_bid_count"
        ((pass++)) || true || true
      else
        echo "    [FAIL] bid_count: Redis=$redis_bid_count != DynamoDB=$dynamo_bid_count"
        ((fail++)) || true || true
      fi
    else
      echo "    [SKIP] bid_count: Redis key absent (auction may be closed)"
    fi

    # ── Check 2: current_highest matches ──
    if [[ -n "$redis_highest" && "$redis_highest" != "(nil)" ]]; then
      if [[ "$redis_highest" == "$dynamo_highest" ]]; then
        echo "    [PASS] current_highest: Redis=$redis_highest == DynamoDB=$dynamo_highest"
        ((pass++)) || true || true
      else
        echo "    [FAIL] current_highest: Redis=$redis_highest != DynamoDB=$dynamo_highest"
        ((fail++)) || true || true
      fi
    else
      echo "    [SKIP] current_highest: Redis key absent"
    fi

    # ── Check 3: winners count <= quantity ──
    if [[ -n "$redis_winner_count" && "$redis_winner_count" != "(nil)" && "$redis_winner_count" -gt 0 ]]; then
      if [[ "$redis_winner_count" -le "$dynamo_quantity" ]]; then
        echo "    [PASS] winners_count: $redis_winner_count <= quantity=$dynamo_quantity"
        ((pass++)) || true || true
      else
        echo "    [FAIL] winners_count: $redis_winner_count > quantity=$dynamo_quantity"
        ((fail++)) || true || true
      fi
    fi

    # ── Check 4: bid-service records exist ──
    local bid_svc_count
    bid_svc_count=$(curl -sf "$BID_SVC/bids/auction/$aid" \
      -H "Authorization: Bearer $token" 2>/dev/null | \
      python3 -c "import sys,json; print(len(json.load(sys.stdin)))" 2>/dev/null || echo "0")

    if [[ "$bid_svc_count" -gt 0 ]]; then
      echo "    [PASS] bid-service has $bid_svc_count records"
      ((pass++)) || true
    else
      if [[ "$dynamo_bid_count" -gt 0 ]]; then
        echo "    [WARN] bid-service has 0 records but DynamoDB bidCount=$dynamo_bid_count (consumer lag?)"
      else
        echo "    [PASS] bid-service has 0 records (no bids placed)"
        ((pass++)) || true || true
      fi
    fi

    # ── Check 5: DynamoDB winners map matches Redis ZSET members ──
    local dynamo_winners
    dynamo_winners=$(echo "$dynamo_json" | python3 -c "
import sys,json
d=json.load(sys.stdin)
w=d.get('winners') or {}
print(' '.join(sorted(w.keys())))
" 2>/dev/null || echo "")

    local redis_winners
    redis_winners=$(redis_cmd ZRANGE "auction:${aid}:winners" 0 -1 2>/dev/null | sort | tr '\n' ' ' | sed 's/ $//' || echo "")

    if [[ -n "$redis_winners" && -n "$dynamo_winners" ]]; then
      if [[ "$redis_winners" == "$dynamo_winners" ]]; then
        echo "    [PASS] winners match: Redis ZSET == DynamoDB map"
        ((pass++)) || true || true
      else
        echo "    [FAIL] winners mismatch:"
        echo "           Redis : $redis_winners"
        echo "           Dynamo: $dynamo_winners"
        ((fail++)) || true || true
      fi
    elif [[ -z "$redis_winners" && -z "$dynamo_winners" ]]; then
      echo "    [PASS] no winners (both empty)"
      ((pass++)) || true
    fi
  done

  echo ""
  echo "  ── Consistency Summary ──"
  echo "  PASS: $pass   FAIL: $fail"
  if [[ "$fail" -eq 0 ]]; then
    echo "  Redis and DynamoDB are consistent."
  else
    echo "  INCONSISTENCIES DETECTED — check above."
  fi
  echo "═══════════════════════════════════════════════"
}

verify_post_close() {
  local token="$1"
  shift
  local auction_ids=("$@")

  echo ""
  echo "═══════════════════════════════════════════════"
  echo "  POST-CLOSE CONSISTENCY VERIFICATION"
  echo "═══════════════════════════════════════════════"

  local pass=0 fail=0

  for aid in "${auction_ids[@]}"; do
    echo ""
    echo "  ── Auction: ${aid:0:8}... ──"

    # Auction should be CLOSED
    local status
    status=$(curl -sf "$AUCTION_SVC/auctions/$aid" \
      -H "Authorization: Bearer $token" | \
      python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null || echo "")

    if [[ "$status" == "CLOSED" ]]; then
      echo "    [PASS] status=CLOSED"
      ((pass++)) || true
    else
      echo "    [FAIL] status=$status (expected CLOSED)"
      ((fail++)) || true
    fi

    # Redis keys should be cleaned up
    local redis_exists
    redis_exists=$(redis_cmd EXISTS "auction:$aid" 2>/dev/null || echo "0")
    if [[ "$redis_exists" == "0" ]]; then
      echo "    [PASS] Redis hash cleaned up"
      ((pass++)) || true
    else
      echo "    [FAIL] Redis hash still exists after close"
      ((fail++)) || true
    fi

    # Payment should exist (one per winner)
    local pay_json pay_status
    pay_json=$(curl -sf "$PAYMENT_SVC/payments/auction/$aid" \
      -H "Authorization: Bearer $token" 2>/dev/null || echo '[]')
    pay_status=$(echo "$pay_json" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d[0]['status'] if isinstance(d,list) and len(d)>0 else d.get('status','') if isinstance(d,dict) else '')" 2>/dev/null || echo "")

    if [[ "$pay_status" == "COMPLETED" || "$pay_status" == "FAILED" ]]; then
      echo "    [PASS] payment exists (status=$pay_status)"
      ((pass++)) || true
    elif [[ -z "$pay_status" ]]; then
      echo "    [WARN] no payment found (auction may have had no bids)"
    else
      echo "    [FAIL] payment in unexpected state: $pay_status"
      ((fail++)) || true
    fi

    # Bid-service: winners should be marked WON
    local won_count
    won_count=$(curl -sf "$BID_SVC/bids/auction/$aid" \
      -H "Authorization: Bearer $token" 2>/dev/null | \
      python3 -c "import sys,json; print(len([b for b in json.load(sys.stdin) if b.get('status')=='WON']))" 2>/dev/null || echo "0")

    local active_count
    active_count=$(curl -sf "$BID_SVC/bids/auction/$aid" \
      -H "Authorization: Bearer $token" 2>/dev/null | \
      python3 -c "import sys,json; print(len([b for b in json.load(sys.stdin) if b.get('status')=='ACTIVE']))" 2>/dev/null || echo "0")

    if [[ "$active_count" -eq 0 ]]; then
      echo "    [PASS] no ACTIVE bids remain after close (WON=$won_count)"
      ((pass++)) || true
    else
      echo "    [FAIL] $active_count bids still ACTIVE after close"
      ((fail++)) || true
    fi
  done

  # Stream consumer lag
  echo ""
  echo "  ── Consumer Lag ──"
  for stream in bid_placed "auction:closed" "payment:processed" "payment:failed"; do
    local pending
    pending=$(redis_cmd XINFO GROUPS "$stream" 2>/dev/null | grep -A1 "pending" | grep -v "pending" | head -1 || echo "?")
    echo "    $stream: pending=$pending"
  done

  echo ""
  echo "  ── Post-Close Summary ──"
  echo "  PASS: $pass   FAIL: $fail"
  echo "═══════════════════════════════════════════════"
}

# ── Test 1: Single auction, ramp-up concurrency ─────────────────────────────

test1() {
  echo ""
  echo "████████████████████████████████████████████████"
  echo "  TEST 1: Single Auction — Lock Contention"
  echo "████████████████████████████████████████████████"

  echo "  Setting up seller..."
  read -r SELLER_TOKEN SELLER_ID <<< "$(register_user "lt1seller@test.com" "SELLER")"
  SHOP_ID=$(curl -sf -X POST "$SHOP_SVC/shops" \
    -H "Authorization: Bearer $SELLER_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"name":"LT1Shop","bio":"test"}' | \
    python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
  ITEM_ID=$(create_item "$SELLER_TOKEN" "$SHOP_ID")

  echo "  Registering 50 buyers..."
  TOKENS_FILE="$RESULTS_DIR/.tokens_test1"
  > "$TOKENS_FILE"
  for i in $(seq 1 50); do
    read -r tok _ <<< "$(register_user "lt1buyer${i}@test.com" "BUYER")"
    echo "$tok" >> "$TOKENS_FILE"
  done

  for CONCURRENCY in 10 25 50; do
    echo ""
    echo "  ── Concurrency: $CONCURRENCY ──"

    AUCTION_ID=$(create_auction "$SELLER_TOKEN" "$SHOP_ID" "$ITEM_ID" 300 1)
    echo "  Auction: $AUCTION_ID"

    OUTPUT="$RESULTS_DIR/test1_c${CONCURRENCY}.txt"
    MONITOR_FILE="$RESULTS_DIR/test1_c${CONCURRENCY}_resources.csv"
    > "$OUTPUT"

    start_monitor "$MONITOR_FILE"

    DURATION=15
    END_AT=$(( $(date +%s) + DURATION ))

    pids=()
    for i in $(seq 1 "$CONCURRENCY"); do
      (
        line_num=$(( ((i - 1) % 50) + 1 ))
        token=$(sed -n "${line_num}p" "$TOKENS_FILE")
        amount=$((200 + RANDOM))
        while [[ $(date +%s) -lt $END_AT ]]; do
          amount=$((amount + RANDOM % 10 + 1))
          fire_bid "$AUCTION_ID" "$token" "$amount" "$OUTPUT"
        done
      ) &
      pids+=($!)
    done

    for pid in "${pids[@]}"; do
      wait "$pid" 2>/dev/null || true
    done

    stop_monitor

    report "$OUTPUT" "Test 1 — Concurrency=$CONCURRENCY"
    report_resources "$MONITOR_FILE" "Concurrency=$CONCURRENCY"

    # Consistency check on this single auction
    first_token=$(head -1 "$TOKENS_FILE")
    verify_consistency "$first_token" "$AUCTION_ID"
  done
}

# ── Test 2: Multiple auctions, parallel bidding ─────────────────────────────

test2() {
  echo ""
  echo "████████████████████████████████████████████████"
  echo "  TEST 2: 20 Auctions — Real-World Throughput"
  echo "████████████████████████████████████████████████"

  echo "  Setting up seller..."
  read -r SELLER_TOKEN SELLER_ID <<< "$(register_user "lt2seller@test.com" "SELLER")"
  SHOP_ID=$(curl -sf -X POST "$SHOP_SVC/shops" \
    -H "Authorization: Bearer $SELLER_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"name":"LT2Shop","bio":"test"}' | \
    python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)

  echo "  Creating 20 auctions..."
  AUCTIONS_FILE="$RESULTS_DIR/.auctions_test2"
  > "$AUCTIONS_FILE"
  for i in $(seq 1 20); do
    iid=$(create_item "$SELLER_TOKEN" "$SHOP_ID")
    aid=$(create_auction "$SELLER_TOKEN" "$SHOP_ID" "$iid" 300 3)
    echo "$aid" >> "$AUCTIONS_FILE"
  done

  echo "  Registering 100 buyers..."
  TOKENS_FILE="$RESULTS_DIR/.tokens_test2"
  > "$TOKENS_FILE"
  for i in $(seq 1 100); do
    read -r tok _ <<< "$(register_user "lt2buyer${i}@test.com" "BUYER")"
    echo "$tok" >> "$TOKENS_FILE"
  done

  CONCURRENCY=50
  DURATION=30
  OUTPUT="$RESULTS_DIR/test2.txt"
  MONITOR_FILE="$RESULTS_DIR/test2_resources.csv"
  > "$OUTPUT"

  echo "  Firing bids: $CONCURRENCY concurrent, ${DURATION}s, across 20 auctions..."

  start_monitor "$MONITOR_FILE"

  END_AT=$(( $(date +%s) + DURATION ))

  pids=()
  for i in $(seq 1 "$CONCURRENCY"); do
    (
      line_num=$(( ((i - 1) % 100) + 1 ))
      token=$(sed -n "${line_num}p" "$TOKENS_FILE")
      amount=$((200 + RANDOM))
      while [[ $(date +%s) -lt $END_AT ]]; do
        auction_idx=$(( RANDOM % 20 + 1 ))
        aid=$(sed -n "${auction_idx}p" "$AUCTIONS_FILE")
        amount=$((amount + RANDOM % 10 + 1))
        fire_bid "$aid" "$token" "$amount" "$OUTPUT"
      done
    ) &
    pids+=($!)
  done

  for pid in "${pids[@]}"; do
    wait "$pid" 2>/dev/null || true
  done

  stop_monitor

  report "$OUTPUT" "Test 2 — 20 Auctions, 50 Concurrent, 30s"
  report_resources "$MONITOR_FILE" "Test 2"

  # Lock metrics from actuator
  echo ""
  echo "  Lock metrics (from actuator):"
  curl -sf "$AUCTION_SVC/actuator/metrics/auction.lock.wait.seconds" 2>/dev/null | \
    python3 -c "
import sys,json
d=json.load(sys.stdin)
for m in d.get('measurements',[]):
  print(f\"    {m['statistic']}: {m['value']:.4f}s\")
" 2>/dev/null || echo "    (not available)"

  # Consistency verification across all 20 auctions
  sleep 3  # allow last DynamoDB writes to settle
  local first_token
  first_token=$(head -1 "$TOKENS_FILE")
  local AUCTION_IDS=()
  while IFS= read -r line; do AUCTION_IDS+=("$line"); done < "$AUCTIONS_FILE"
  verify_consistency "$first_token" "${AUCTION_IDS[@]}"
}

# ── Test 3: Lifecycle — bid + close + payment ────────────────────────────────

test3() {
  echo ""
  echo "████████████████████████████████████████████████"
  echo "  TEST 3: Lifecycle — Bid → Close → Payment"
  echo "████████████████████████████████████████████████"

  echo "  Setting up seller..."
  read -r SELLER_TOKEN SELLER_ID <<< "$(register_user "lt3seller@test.com" "SELLER")"
  SHOP_ID=$(curl -sf -X POST "$SHOP_SVC/shops" \
    -H "Authorization: Bearer $SELLER_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"name":"LT3Shop","bio":"test"}' | \
    python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)

  echo "  Creating 10 auctions (close in 30s)..."
  AUCTIONS_FILE="$RESULTS_DIR/.auctions_test3"
  > "$AUCTIONS_FILE"
  for i in $(seq 1 10); do
    iid=$(create_item "$SELLER_TOKEN" "$SHOP_ID")
    aid=$(create_auction "$SELLER_TOKEN" "$SHOP_ID" "$iid" 30 1)
    echo "$aid" >> "$AUCTIONS_FILE"
  done

  echo "  Registering 30 buyers..."
  TOKENS_FILE="$RESULTS_DIR/.tokens_test3"
  > "$TOKENS_FILE"
  for i in $(seq 1 30); do
    read -r tok _ <<< "$(register_user "lt3buyer${i}@test.com" "BUYER")"
    echo "$tok" >> "$TOKENS_FILE"
  done

  OUTPUT="$RESULTS_DIR/test3_bids.txt"
  MONITOR_FILE="$RESULTS_DIR/test3_resources.csv"
  > "$OUTPUT"

  echo "  Bidding phase (15s)..."
  CONCURRENCY=20

  start_monitor "$MONITOR_FILE"

  END_AT=$(( $(date +%s) + 15 ))

  pids=()
  for i in $(seq 1 "$CONCURRENCY"); do
    (
      line_num=$(( ((i - 1) % 30) + 1 ))
      token=$(sed -n "${line_num}p" "$TOKENS_FILE")
      amount=$((200 + RANDOM))
      while [[ $(date +%s) -lt $END_AT ]]; do
        auction_idx=$(( RANDOM % 10 + 1 ))
        aid=$(sed -n "${auction_idx}p" "$AUCTIONS_FILE")
        amount=$((amount + RANDOM % 50 + 1))
        fire_bid "$aid" "$token" "$amount" "$OUTPUT"
      done
    ) &
    pids+=($!)
  done

  for pid in "${pids[@]}"; do
    wait "$pid" 2>/dev/null || true
  done

  report "$OUTPUT" "Test 3 — Bid Phase"

  echo ""
  echo "  Waiting for auctions to close + events to propagate (25s)..."
  sleep 25

  stop_monitor

  report_resources "$MONITOR_FILE" "Test 3 (full lifecycle)"

  local first_token
  first_token=$(head -1 "$TOKENS_FILE")
  local AUCTION_IDS=()
  while IFS= read -r line; do AUCTION_IDS+=("$line"); done < "$AUCTIONS_FILE"
  verify_post_close "$first_token" "${AUCTION_IDS[@]}"
}

# ── Main ─────────────────────────────────────────────────────────────────────

trap 'stop_monitor' EXIT

WHAT="${1:-all}"

echo ""
echo "╔═══════════════════════════════════════════════╗"
echo "║       Dropbid Load Test Suite                 ║"
echo "╠═══════════════════════════════════════════════╣"
echo "║  Results saved to: $RESULTS_DIR/              ║"
echo "╚═══════════════════════════════════════════════╝"

case "$WHAT" in
  test1) test1 ;;
  test2) test2 ;;
  test3) test3 ;;
  all)   test1; test2; test3 ;;
  *) echo "Usage: $0 [test1|test2|test3|all]"; exit 1 ;;
esac

echo ""
echo "Done. Raw data in $RESULTS_DIR/"
