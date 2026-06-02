# Load Test Plan

Full test strategy (including load test design, scenarios, metrics, and priority list) is in
[`docs/testing.md`](../docs/testing.md).

---

## Run

```bash
# Prerequisites: all services healthy
docker-compose up --build -d
bash scripts/smoke-test.sh

# Run all three tests
bash loadtest/run.sh

# Run individually
bash loadtest/run.sh test1   # single-auction concurrency ramp
bash loadtest/run.sh test2   # multi-auction throughput
bash loadtest/run.sh test3   # full lifecycle + post-close consistency
```

Results are saved to `loadtest/results/`. See [`RESULTS.md`](RESULTS.md) for the latest recorded run.
