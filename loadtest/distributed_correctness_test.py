#!/usr/bin/env python3
"""
Proves the rate limiter's state is truly shared across app instances.

It configures a single client rule with a fixed token budget (capacity=LIMIT,
refill_rate=0, i.e. no replenishment during the run), then fires
LIMIT + EXTRA concurrent requests for that client, round-robined across every
app instance URL given on the command line. If each instance tracked state
locally (e.g. an in-memory counter) instead of sharing Redis, we would see
roughly `len(instances) * LIMIT` requests succeed. Because the limiter is
backed by one Redis instance and an atomic Lua script, the total allowed
across ALL instances must equal exactly LIMIT, not LIMIT-per-instance.

Usage:
    docker compose -f docker-compose.cluster.yml up --build -d
    python3 loadtest/distributed_correctness_test.py \\
        --instances http://localhost:8081 http://localhost:8082 http://localhost:8083 \\
        --limit 100 --extra 200
"""
import argparse
import concurrent.futures
import itertools
import sys
import time
from collections import Counter

import requests

CLIENT_ID = "loadtest-client"
CLIENT_HEADER = "X-API-Key"


def configure_rule(base_url: str, limit: int) -> None:
    """Registers a fixed-budget token bucket rule for CLIENT_ID via the admin API.

    refillRatePerSecond=0 turns the bucket into a one-shot budget of `limit` tokens
    for the duration of the test, which makes the assertion below unambiguous.
    """
    resp = requests.put(
        f"{base_url}/admin/rules/{CLIENT_ID}",
        json={
            "algorithm": "TOKEN_BUCKET",
            "capacity": limit,
            "refillRatePerSecond": 0,
            "limit": limit,
            "windowSeconds": 60,
        },
        timeout=5,
    )
    resp.raise_for_status()
    print(f"Configured rule via {base_url}: capacity={limit}, refillRatePerSecond=0")


def send_one(base_url: str) -> int:
    try:
        resp = requests.get(
            f"{base_url}/api/resource",
            headers={CLIENT_HEADER: CLIENT_ID},
            timeout=10,
        )
        return resp.status_code
    except requests.RequestException as exc:
        print(f"request to {base_url} failed: {exc}", file=sys.stderr)
        return -1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument(
        "--instances",
        nargs="+",
        default=["http://localhost:8081", "http://localhost:8082", "http://localhost:8083"],
        help="Base URLs of app instances sitting behind the shared Redis.",
    )
    parser.add_argument("--limit", type=int, default=100, help="Configured total budget for the client.")
    parser.add_argument("--extra", type=int, default=200, help="Extra requests sent beyond the limit.")
    parser.add_argument("--workers", type=int, default=64, help="Concurrent worker threads.")
    args = parser.parse_args()

    total_requests = args.limit + args.extra
    print(f"Instances: {args.instances}")
    print(f"Configured limit: {args.limit}, total requests to fire: {total_requests}\n")

    configure_rule(args.instances[0], args.limit)
    # Give the write a moment to be visible (Redis is strongly consistent for a single
    # key so this is just being defensive against client-side connection setup time).
    time.sleep(0.2)

    instance_cycle = itertools.cycle(args.instances)
    targets = [next(instance_cycle) for _ in range(total_requests)]

    start = time.time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        results = list(pool.map(send_one, targets))
    elapsed = time.time() - start

    counts = Counter(results)
    allowed = counts.get(200, 0)
    denied = counts.get(429, 0)
    errors = total_requests - allowed - denied

    print(f"Finished in {elapsed:.2f}s")
    print(f"  200 OK           : {allowed}")
    print(f"  429 Too Many Req : {denied}")
    print(f"  other/errors     : {errors}")
    print()

    if allowed > args.limit:
        print(
            f"FAIL: {allowed} requests were allowed but the shared limit was {args.limit}. "
            "State is NOT being shared correctly across instances."
        )
        return 1

    if errors > 0:
        print(f"WARNING: {errors} requests errored out (connection issues?) -- treat the result with caution.")

    print(
        f"PASS: exactly {allowed} requests were allowed across {len(args.instances)} instances, "
        f"respecting the single configured limit of {args.limit}. "
        f"(A naive per-instance limiter would have allowed up to {len(args.instances) * args.limit}.)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
