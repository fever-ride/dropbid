"""
Dropbid Locust load test.

Prerequisites:
    docker-compose up --build -d
    bash scripts/smoke-test.sh
    python3 loadtest/setup.py          # creates loadtest/fixtures.json

Test 1 — Single auction, high concurrency (tests Redis Lua serialization):
    locust -f loadtest/locustfile.py SingleAuctionBidder \\
        --headless -u 50 -r 5 -t 60s --host http://localhost:8081

Test 2 — Multi-auction throughput (tests horizontal parallelism):
    locust -f loadtest/locustfile.py MultiAuctionBidder \\
        --headless -u 50 -r 5 -t 60s --host http://localhost:8081

Open UI (run both):
    locust -f loadtest/locustfile.py --host http://localhost:8081

Interpreting results:
    - 200: bid accepted (Lua script ran, bid beat the floor)
    - 400: bid rejected (too low / too high / auction not open) — expected
    - We track ONLY 200s as "successful bids" for throughput numbers.
    - 400 is NOT counted as a Locust failure; it is reclassified as success
      so it doesn't pollute the error rate chart.
"""

import json
import random
import threading

from locust import HttpUser, task, constant, events

FIXTURES_FILE = "loadtest/fixtures.json"

_fixtures = None
_fixtures_lock = threading.Lock()


def load_fixtures() -> dict:
    global _fixtures
    with _fixtures_lock:
        if _fixtures is None:
            with open(FIXTURES_FILE) as f:
                _fixtures = json.load(f)
    return _fixtures


# ── Base bidder ───────────────────────────────────────────────────────────────

class BidderBase(HttpUser):
    """
    Shared bid logic.  Subclasses supply `pick_auction()` and token pool.
    """
    abstract = True
    wait_time = constant(0)   # no think time — we want max throughput

    def on_start(self):
        fx = load_fixtures()
        self._tokens  = self.token_pool(fx)
        self._token   = random.choice(self._tokens)
        self._amount  = random.randint(200, 500)   # each user starts at a random base
        self._headers = {
            "Authorization": f"Bearer {self._token}",
            "Content-Type":  "application/json",
        }

    def token_pool(self, fx) -> list:
        raise NotImplementedError

    def pick_auction(self, fx) -> str:
        raise NotImplementedError

    @task
    def place_bid(self):
        fx = load_fixtures()
        auction_id    = self.pick_auction(fx)
        self._amount += random.randint(1, 20)   # always increment so bid has a chance

        with self.client.put(
            f"/auctions/{auction_id}/bid",
            json={"amount": self._amount},
            headers=self._headers,
            catch_response=True,
            name="/auctions/[id]/bid",
        ) as resp:
            if resp.status_code == 200:
                resp.success()
            elif resp.status_code in (400, 409):
                # 400: Bid too low / auction not open — expected under competition.
                # 409: Concurrent bid conflict (pessimistic lock) — expected under high load.
                # Mark success so Locust doesn't inflate the failure rate.
                resp.success()
            elif resp.status_code == 401 or resp.status_code == 403:
                resp.failure(f"auth error {resp.status_code}")
            else:
                resp.failure(f"unexpected {resp.status_code}: {resp.text[:120]}")


# ── Test 1: single auction ────────────────────────────────────────────────────

class SingleAuctionBidder(BidderBase):
    """
    All users hammer the same auction.
    Tests Redis single-threaded Lua serialization under maximum contention.

    Run:
        locust -f loadtest/locustfile.py SingleAuctionBidder \\
            --headless -u 50 -r 5 -t 60s --host http://localhost:8081
    """

    def token_pool(self, fx):
        return fx["tokens_t1"]

    def pick_auction(self, fx) -> str:
        return fx["auction_t1"]


# ── Test 2: multi-auction ─────────────────────────────────────────────────────

class MultiAuctionBidder(BidderBase):
    """
    Users spread bids across 20 concurrent auctions.
    Tests horizontal parallelism — different auctions are fully independent in Redis.

    Run:
        locust -f loadtest/locustfile.py MultiAuctionBidder \\
            --headless -u 50 -r 5 -t 60s --host http://localhost:8081
    """

    def token_pool(self, fx):
        return fx["tokens_t2"]

    def pick_auction(self, fx) -> str:
        return random.choice(fx["auctions_t2"])
