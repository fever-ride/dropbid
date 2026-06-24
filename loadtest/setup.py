"""
Dropbid load test setup script.
Run this once before starting Locust to create all test fixtures.

Usage:
    python3 loadtest/setup.py            # creates loadtest/fixtures.json
    python3 loadtest/setup.py --clean    # re-creates (re-registers users)
"""

import argparse
import json
import sys
import time
from datetime import datetime, timezone, timedelta

import requests

USER_SVC    = "http://localhost:8082"
SHOP_SVC    = "http://localhost:8083"
AUCTION_SVC = "http://localhost:8081"

FIXTURES_FILE = "loadtest/fixtures.json"

NUM_BUYERS_T1   = 50
NUM_BUYERS_T2   = 100
NUM_AUCTIONS_T2 = 20
AUCTION_DURATION_SECS = 600   # 10 min — long enough for the whole test


def future_time(secs: int) -> str:
    return (datetime.now(timezone.utc) + timedelta(seconds=secs)).strftime(
        "%Y-%m-%dT%H:%M:%SZ"
    )


def register(email: str, role: str) -> dict:
    r = requests.post(
        f"{USER_SVC}/users/register",
        json={"email": email, "password": "pass1234",
              "username": f"user_{email.split('@')[0]}", "role": role},
        timeout=10,
    )
    if r.status_code not in (200, 201, 409):
        raise RuntimeError(f"register {email}: {r.status_code} {r.text}")
    if r.status_code == 409:
        # already registered — login instead
        r2 = requests.post(
            f"{USER_SVC}/users/login",
            json={"email": email, "password": "pass1234"},
            timeout=10,
        )
        r2.raise_for_status()
        return r2.json()
    return r.json()


def create_shop(token: str, name: str, owner_id: str) -> str:
    r = requests.post(
        f"{SHOP_SVC}/shops",
        headers={"Authorization": f"Bearer {token}"},
        json={"name": name, "bio": "load test shop"},
        timeout=10,
    )
    if r.status_code == 409:
        # seller already has a shop — fetch it
        r2 = requests.get(
            f"{SHOP_SVC}/shops/owner/{owner_id}",
            headers={"Authorization": f"Bearer {token}"},
            timeout=10,
        )
        r2.raise_for_status()
        return r2.json()["id"]
    r.raise_for_status()
    return r.json()["id"]


def create_item(token: str, shop_id: str, title: str) -> str:
    r = requests.post(
        f"{SHOP_SVC}/shops/{shop_id}/items",
        headers={"Authorization": f"Bearer {token}"},
        json={
            "title": title, "description": "load test item",
            "series": "LoadTest", "edition": "1st", "condition": "NEW",
            "originalRetailPrice": 100000, "estimatedMarketValue": 200000,
        },
        timeout=10,
    )
    r.raise_for_status()
    return r.json()["id"]


def create_auction(token: str, shop_id: str, item_id: str,
                   duration_secs: int = AUCTION_DURATION_SECS,
                   quantity: int = 1) -> str:
    r = requests.post(
        f"{AUCTION_SVC}/auctions",
        headers={"Authorization": f"Bearer {token}"},
        json={
            "itemId": item_id, "shopId": shop_id,
            "startingBid": 100,
            "endTime": future_time(duration_secs),
            "quantity": quantity,
        },
        timeout=10,
    )
    r.raise_for_status()
    return r.json()["auctionId"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--clean", action="store_true")
    args = parser.parse_args()

    print("=== Dropbid load test setup ===")

    # ── Seller + shop ──────────────────────────────────────────────────────
    print("Registering seller...")
    seller = register("lt_seller@test.com", "SELLER")
    seller_token = seller["token"]
    seller_id = seller["userId"]
    shop_id = create_shop(seller_token, "LoadTestShop", seller_id)
    print(f"  shop_id = {shop_id}")

    # ── Test 1: single auction ─────────────────────────────────────────────
    print(f"Creating single auction for Test 1...")
    item_t1 = create_item(seller_token, shop_id, "T1-Item")
    auction_t1 = create_auction(seller_token, shop_id, item_t1,
                                duration_secs=AUCTION_DURATION_SECS, quantity=1)
    print(f"  auction_t1 = {auction_t1}")

    print(f"Registering {NUM_BUYERS_T1} buyers for Test 1...")
    tokens_t1 = []
    for i in range(1, NUM_BUYERS_T1 + 1):
        u = register(f"lt1_buyer{i}@test.com", "BUYER")
        tokens_t1.append(u["token"])
        if i % 10 == 0:
            print(f"  {i}/{NUM_BUYERS_T1}")

    # ── Test 2: multi-auction ──────────────────────────────────────────────
    print(f"Creating {NUM_AUCTIONS_T2} auctions for Test 2...")
    auctions_t2 = []
    for i in range(1, NUM_AUCTIONS_T2 + 1):
        item = create_item(seller_token, shop_id, f"T2-Item-{i}")
        aid  = create_auction(seller_token, shop_id, item,
                              duration_secs=AUCTION_DURATION_SECS, quantity=3)
        auctions_t2.append(aid)
    print(f"  created {len(auctions_t2)} auctions")

    print(f"Registering {NUM_BUYERS_T2} buyers for Test 2...")
    tokens_t2 = []
    for i in range(1, NUM_BUYERS_T2 + 1):
        u = register(f"lt2_buyer{i}@test.com", "BUYER")
        tokens_t2.append(u["token"])
        if i % 20 == 0:
            print(f"  {i}/{NUM_BUYERS_T2}")

    fixtures = {
        "seller_token": seller_token,
        "shop_id":      shop_id,
        "auction_t1":   auction_t1,
        "tokens_t1":    tokens_t1,
        "auctions_t2":  auctions_t2,
        "tokens_t2":    tokens_t2,
    }

    with open(FIXTURES_FILE, "w") as f:
        json.dump(fixtures, f, indent=2)

    print(f"\nFixtures written to {FIXTURES_FILE}")
    print("Ready to run: locust -f loadtest/locustfile.py")


if __name__ == "__main__":
    main()
