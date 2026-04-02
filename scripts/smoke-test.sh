#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Dropbid Java — Full User Journey Smoke Test
# Mirrors the Go USER_JOURNEY.md test sequence.
#
# Prerequisites: all services running (docker-compose up -d)
# Usage:  bash scripts/smoke-test.sh
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

USER_SVC="http://localhost:8082"
SHOP_SVC="http://localhost:8083"
AUCTION_SVC="http://localhost:8081"
BID_SVC="http://localhost:8084"
PAYMENT_SVC="http://localhost:8085"

PASS=0
FAIL=0

# ── Helpers ──────────────────────────────────────────────────────────────────

assert_http() {
  local label="$1" expected="$2" actual="$3"
  if [[ "$actual" == "$expected" ]]; then
    echo "  [PASS] $label"
    ((PASS++))
  else
    echo "  [FAIL] $label — expected $expected, got $actual"
    ((FAIL++))
  fi
}

assert_field() {
  local label="$1" field="$2" body="$3"
  local value
  value=$(echo "$body" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('$field',''))" 2>/dev/null || echo "")
  if [[ -n "$value" && "$value" != "None" && "$value" != "null" ]]; then
    echo "  [PASS] $label (got: $value)"
    ((PASS++))
  else
    echo "  [FAIL] $label — '$field' missing or empty in: $body"
    ((FAIL++))
  fi
}

# ── 1. Register seller ───────────────────────────────────────────────────────

echo ""
echo "=== 1. Register Seller ==="
SELLER_BODY=$(curl -sf -X POST "$USER_SVC/users/register" \
  -H "Content-Type: application/json" \
  -d '{"email":"seller@test.com","password":"pass1234","username":"ToyCollectorSeller","role":"SELLER"}' || echo '{}')
assert_field "seller registration" "token" "$SELLER_BODY"
SELLER_TOKEN=$(echo "$SELLER_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])" 2>/dev/null || echo "")
SELLER_ID=$(echo "$SELLER_BODY"  | python3 -c "import sys,json; print(json.load(sys.stdin)['userId'])" 2>/dev/null || echo "")

# ── 2. Register buyer ────────────────────────────────────────────────────────

echo ""
echo "=== 2. Register Buyer ==="
BUYER_BODY=$(curl -sf -X POST "$USER_SVC/users/register" \
  -H "Content-Type: application/json" \
  -d '{"email":"buyer@test.com","password":"pass1234","username":"BlindBoxFan","role":"BUYER"}' || echo '{}')
assert_field "buyer registration" "token" "$BUYER_BODY"
BUYER_TOKEN=$(echo "$BUYER_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])" 2>/dev/null || echo "")

# ── 3. Seller creates a shop ─────────────────────────────────────────────────

echo ""
echo "=== 3. Create Seller Shop ==="
SHOP_BODY=$(curl -sf -X POST "$SHOP_SVC/shops" \
  -H "Authorization: Bearer $SELLER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"LimitedEditionToys","bio":"Rare and secondhand collectible toys"}' || echo '{}')
assert_field "shop creation" "id" "$SHOP_BODY"
SHOP_ID=$(echo "$SHOP_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null || echo "")

# ── 4. Seller lists a collectible item ──────────────────────────────────────

echo ""
echo "=== 4. List Collectible Item ==="
ITEM_BODY=$(curl -sf -X POST "$SHOP_SVC/shops/$SHOP_ID/items" \
  -H "Authorization: Bearer $SELLER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Molly Space Series 1 - Blue",
    "description": "Original Molly blind box, unopened",
    "series": "Space Series",
    "edition": "1st",
    "condition": "NEW",
    "originalRetailPrice": 6900,
    "estimatedMarketValue": 15000,
    "imageUrl": "https://example.com/molly-blue.jpg"
  }' || echo '{}')
assert_field "item listing" "id" "$ITEM_BODY"
ITEM_ID=$(echo "$ITEM_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null || echo "")

# ── 5. Seller creates an auction ─────────────────────────────────────────────

echo ""
echo "=== 5. Create Auction ==="
# Auction ends in 5 seconds
END_TIME=$(date -u -v+5S '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || date -u -d '+5 seconds' '+%Y-%m-%dT%H:%M:%SZ')
AUCTION_BODY=$(curl -sf -X POST "$AUCTION_SVC/auctions" \
  -H "Authorization: Bearer $SELLER_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"itemId\": \"$ITEM_ID\",
    \"shopId\": \"$SHOP_ID\",
    \"startingBid\": 10000,
    \"reservePrice\": 12000,
    \"endTime\": \"$END_TIME\"
  }" || echo '{}')
assert_field "auction creation" "auctionId" "$AUCTION_BODY"
AUCTION_ID=$(echo "$AUCTION_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['auctionId'])" 2>/dev/null || echo "")

# ── 6. Buyer places a bid ────────────────────────────────────────────────────

echo ""
echo "=== 6. Buyer Places Bid ==="
BID_BODY=$(curl -sf -X PUT "$AUCTION_SVC/auctions/$AUCTION_ID/bid" \
  -H "Authorization: Bearer $BUYER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"amount": 15000}' || echo '{}')
assert_field "bid placement" "amount" "$BID_BODY"

# ── 7. Verify bid history ────────────────────────────────────────────────────

echo ""
echo "=== 7. Bid History ==="
sleep 1  # allow async event processing
BID_LIST=$(curl -sf "$BID_SVC/bids/auction/$AUCTION_ID" \
  -H "Authorization: Bearer $BUYER_TOKEN" || echo '[]')
BID_COUNT=$(echo "$BID_LIST" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))" 2>/dev/null || echo "0")
if [[ "$BID_COUNT" -ge 1 ]]; then
  echo "  [PASS] bid history (count=$BID_COUNT)"
  ((PASS++))
else
  echo "  [FAIL] bid history — expected >= 1 bid, got $BID_COUNT"
  ((FAIL++))
fi

# ── 8. Wait for auction to expire, then check payment ────────────────────────

echo ""
echo "=== 8. Wait for Auction Close + Payment Trigger ==="
echo "  Waiting 8 seconds for auction to expire and payment to process..."
sleep 8

AUCTION_STATUS=$(curl -sf "$AUCTION_SVC/auctions/$AUCTION_ID" \
  -H "Authorization: Bearer $BUYER_TOKEN" | \
  python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null || echo "")
if [[ "$AUCTION_STATUS" == "CLOSED" ]]; then
  echo "  [PASS] auction closed (status=$AUCTION_STATUS)"
  ((PASS++))
else
  echo "  [FAIL] auction not closed yet (status=$AUCTION_STATUS)"
  ((FAIL++))
fi

PAYMENT_BODY=$(curl -sf "$PAYMENT_SVC/payments/auction/$AUCTION_ID" \
  -H "Authorization: Bearer $BUYER_TOKEN" || echo '{}')
PAYMENT_STATUS=$(echo "$PAYMENT_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null || echo "")
if [[ "$PAYMENT_STATUS" == "COMPLETED" || "$PAYMENT_STATUS" == "FAILED" ]]; then
  echo "  [PASS] payment triggered (status=$PAYMENT_STATUS)"
  ((PASS++))
else
  echo "  [FAIL] payment not processed (status=$PAYMENT_STATUS)"
  ((FAIL++))
fi

# ── 9. Concurrency strategy switch ──────────────────────────────────────────

echo ""
echo "=== 9. Strategy Switch ==="
for STRATEGY in pessimistic queue optimistic; do
  SWITCH_BODY=$(curl -sf -X PUT "$AUCTION_SVC/admin/strategy" \
    -H "Authorization: Bearer $SELLER_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"strategy\": \"$STRATEGY\"}" || echo '{}')
  ACTIVE=$(echo "$SWITCH_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('strategy',''))" 2>/dev/null || echo "")
  if [[ "$ACTIVE" == "$STRATEGY" ]]; then
    echo "  [PASS] switched to $STRATEGY"
    ((PASS++))
  else
    echo "  [FAIL] strategy switch to $STRATEGY (got '$ACTIVE')"
    ((FAIL++))
  fi
done

# ── Summary ──────────────────────────────────────────────────────────────────

echo ""
echo "══════════════════════════════════════════════"
echo "  Smoke test results: PASS=$PASS  FAIL=$FAIL"
echo "══════════════════════════════════════════════"
[[ $FAIL -eq 0 ]] && exit 0 || exit 1
