# Distributed Rate Limiter

A Spring Boot service that rate-limits HTTP requests per client (API key), with state
shared across multiple app instances via Redis. Correctness under concurrency comes from
executing the check-and-decrement operation as a single atomic Lua script (`EVAL`) inside
Redis, not from any locking or coordination in the application tier.

Two interchangeable algorithms are implemented behind a Strategy interface:

- **Token Bucket** (primary) — smooth throughput with configurable burst capacity.
- **Sliding Window Log** — precise, no boundary bursts, more memory per client.

---

## Table of contents

- [Architecture](#architecture)
- [Why Redis + Lua](#why-redis--lua)
- [Token Bucket vs Sliding Window vs Fixed Window](#token-bucket-vs-sliding-window-vs-fixed-window)
- [API reference](#api-reference)
- [Running locally](#running-locally)
- [Proving distributed correctness (load test)](#proving-distributed-correctness-load-test)
- [Tests](#tests)
- [CI/CD pipeline](#cicd-pipeline)
- [What I'd do differently at real scale](#what-id-do-differently-at-real-scale)
- [Project layout](#project-layout)

---

## Architecture

```
                       ┌─────────────────────────────────────────────┐
                       │              App instance(s)                │
                       │                                              │
   HTTP request        │  ┌────────────────┐    ┌───────────────┐    │
  ───────────────────► │  │ RateLimitFilter │───►│ /api/resource │    │
  X-API-Key: client-1  │  │ (OncePerRequest │    │  (dummy        │   │
                       │  │     Filter)     │    │   downstream)  │   │
                       │  └───────┬─────────┘    └───────────────┘    │
                       │          │ tryConsume(clientId)               │
                       │          ▼                                   │
                       │  ┌─────────────────┐    ┌──────────────────┐│
                       │  │ RateLimiterService│──►│  RuleService      ││
                       │  │  (picks strategy) │   │ (resolves rule:   ││
                       │  └───────┬───────────┘   │  per-client or    ││
                       │          │                │  default)         ││
                       │          ▼                └────────┬─────────┘│
                       │  ┌───────────────────┐              │         │
                       │  │ TokenBucket        │             │         │
                       │  │ RateLimiter        │             │         │
                       │  │  -- or --          │             │         │
                       │  │ SlidingWindow      │             │         │
                       │  │ RateLimiter        │             │         │
                       │  └─────────┬──────────┘             │         │
                       └────────────┼────────────────────────┼─────────┘
                                    │ EVAL token_bucket.lua   │ rl:rules hash
                                    │ EVAL sliding_window.lua │ (RateLimitRuleRepository)
                                    ▼                         ▼
                       ┌───────────────────────────────────────────┐
                       │                   Redis                    │
                       │  rl:tb:{clientId}   (hash: tokens, ts)      │
                       │  rl:sw:{clientId}   (zset: request log)     │
                       │  rl:rules           (hash: per-client rules)│
                       └───────────────────────────────────────────┘
                                          ▲
                                          │ shared by every instance
                       ┌──────────────────┴──────────────────┐
                       │        App instance #2, #3, ...       │
                       └────────────────────────────────────────┘
```

Every app instance talks to the **same Redis**, so no matter which instance a request
lands on (behind a load balancer), the limiter sees a globally consistent view of how many
requests that client has made. The admin API and the request-serving path both go through
`RuleService`, which reads/writes rules in the same Redis instance (hash `rl:rules`), so a
rule change made via one instance is immediately visible to all the others.

### Request flow

1. `RateLimitFilter` (a `OncePerRequestFilter`) intercepts every request except
   `/admin/**` and `/actuator/**` (configured in `application.yml` under
   `ratelimiter.excluded-paths`).
2. It extracts the client id from the `X-API-Key` header (configurable), falling back to
   the caller's remote address for anonymous/unrecognized clients.
3. `RateLimiterService` asks `RuleService` for the effective rule (the client's own rule,
   or the default/global rule if none is configured) and picks the matching strategy bean.
4. The strategy (`TokenBucketRateLimiter` or `SlidingWindowRateLimiter`) runs one `EVAL`
   against Redis and gets back `{allowed, remaining, retryAfterMs}` atomically.
5. If denied, the filter returns `429 Too Many Requests` with a `Retry-After` header (in
   seconds) and a small JSON body; otherwise the request proceeds to the controller.

---

## Why Redis + Lua

The check-and-decrement operation is inherently **read-modify-write**: read the current
token count, compute the new value, write it back. If those three steps aren't atomic,
concurrent requests race.

### A concrete example of what breaks without atomicity

Say a client has a token bucket with **1 token left**, and two requests arrive at the same
instant on two different app instances (or even two threads on the same instance, using
naive `GET`/`SET` instead of a script):

| Time | Instance A                          | Instance B                          |
|------|--------------------------------------|--------------------------------------|
| t0   | `GET tokens` → reads `1`             |                                       |
| t1   |                                       | `GET tokens` → reads `1`             |
| t2   | sees `1 >= 1`, decides to **allow**  |                                       |
| t3   |                                       | sees `1 >= 1`, decides to **allow**  |
| t4   | `SET tokens = 0`                     |                                       |
| t5   |                                       | `SET tokens = 0`                     |

Both instances independently read the same value before either wrote back its decrement,
so **both allow the request**. The client just consumed 2 tokens from a bucket that only
had 1 — the limit was silently violated, and it gets worse under real load (dozens of
requests can pile into the same race window). This is the classic **check-then-act** /
**lost update** race condition, and it gets *more* likely, not less, the more instances and
the more concurrent traffic you have — exactly the conditions a rate limiter exists for.

### Why a Lua script fixes it

Redis executes commands (and Lua scripts) **single-threaded**. When `EVAL` runs
`token_bucket.lua`, no other client — not even another replica of this same service — can
interleave a command against that key until the script finishes. The read, the refill
math, and the write all happen as one indivisible unit, so the race window above simply
cannot open. This is cheaper and simpler than the alternatives:

- **Distributed locks** (e.g. Redlock) would also serialize access, but add latency (extra
  round trips to acquire/release) and a whole new failure mode (lock expiry vs. slow
  clients).
- **`WATCH`/`MULTI`/`EXEC` (optimistic transactions)** work, but require a retry loop on
  conflict and still cost multiple round trips per attempt.
- **A Lua script is a single round trip, server-side, and atomic by construction** — no
  retries, no lock management, no partial failure between the read and the write.

Both `scripts/token_bucket.lua` and `scripts/sliding_window.lua` follow this pattern.

---

## Token Bucket vs Sliding Window vs Fixed Window

| | **Token Bucket** (implemented) | **Sliding Window Log** (implemented) | **Fixed Window Counter** (discussed, not implemented) |
|---|---|---|---|
| **Redis structure** | 1 hash per client (`tokens`, `ts`) — O(1) space | 1 sorted set per client, one member per request in the window — O(N) space where N = requests/window | 1 counter per client per window — O(1) space |
| **Memory usage** | Constant, tiny (2 fields) regardless of traffic | Grows with the request rate inside the window; a client doing 10k req/window costs 10k zset entries until they expire | Constant, tiny (1 integer) |
| **Burst handling** | Explicit and tunable: `capacity` is exactly how much burst you allow above the steady `refillRatePerSecond` | No extra burst allowance beyond the limit itself — requests are smoothly bounded to `limit` per rolling window | Allows a **hidden double burst**: a client can send `limit` requests at 0:59 and another `limit` at 1:00, i.e. `2×limit` in a couple seconds, right at the window boundary |
| **Accuracy** | Approximate but well-behaved: tracks a continuous refill rate, not tied to wall-clock window edges | Exact: counts real requests in the last `windowSeconds`, no boundary artifacts | Inaccurate at window edges (the "boundary burst" problem above) |
| **Complexity** | Small Lua script, simple math (refill formula) | Slightly more Lua (evict + count + add), and needs a unique per-request member to avoid zset collisions | Simplest possible: `INCR` + `EXPIRE` |
| **Best for** | APIs that want to allow short bursts (e.g. a user clicking rapidly) while capping sustained throughput | APIs that need strict, precise limits with no edge-case bursts, and can afford the extra memory | High-volume, low-precision limiting where simplicity/cost matters more than exactness |

This project implements **Token Bucket** as the primary strategy and **Sliding Window
Log** as the swappable second strategy (via `RateLimitAlgorithm.SLIDING_WINDOW`), since
between them they cover the two ends of the trade-off (tunable burst vs. exact
precision). Fixed Window Counter is included in the comparison above because it's the
usual third option people reach for, but is left unimplemented here — its boundary-burst
problem is exactly the failure mode Token Bucket and Sliding Window Log both solve, so
adding it as a real code path would mostly demonstrate the trade-off table above with no
new production value. (Its Lua would just be `INCR` + conditional `EXPIRE`.)

Switching a client between algorithms is just a field: `PUT /admin/rules/{clientId}` with
`"algorithm": "SLIDING_WINDOW"` instead of `"TOKEN_BUCKET"` — the Strategy pattern
(`RateLimiterStrategy` interface, dispatched by `RateLimiterService`) means no other code
changes.

---

## API reference

### Downstream (rate-limited)

| Method | Path | Description |
|---|---|---|
| GET | `/api/resource` | Dummy protected endpoint. Send `X-API-Key: <clientId>` to be rate-limited per client; omit it to be limited by remote IP under the default rule. |

Rate-limited responses:

- **Allowed**: normal response, plus `X-RateLimit-Remaining` and `X-RateLimit-Client` headers.
- **Denied**: `HTTP 429`, `Retry-After: <seconds>` header, JSON body:
  ```json
  {"error":"rate_limit_exceeded","message":"Too many requests for client 'client-1'. Retry after 3s.","retryAfterSeconds":3}
  ```

### Admin (not rate-limited)

| Method | Path | Description |
|---|---|---|
| GET | `/admin/rules` | List all explicit per-client rules. |
| GET | `/admin/rules/default` | View the default/global fallback rule. |
| PUT | `/admin/rules/default` | Replace the default/global rule. |
| GET | `/admin/rules/{clientId}` | View a specific client's rule (404 if none set). |
| PUT | `/admin/rules/{clientId}` | Create or update a client's rule. |
| DELETE | `/admin/rules/{clientId}` | Remove a client's rule (they fall back to the default). |

Rule payload:

```json
{
  "algorithm": "TOKEN_BUCKET",
  "capacity": 20,
  "refillRatePerSecond": 5,
  "limit": 20,
  "windowSeconds": 10
}
```

`capacity`/`refillRatePerSecond` are used by `TOKEN_BUCKET`; `limit`/`windowSeconds` are
used by `SLIDING_WINDOW`. Both sets of fields are always accepted so you can switch a
client's algorithm without losing the other algorithm's settings.

---

## Running locally

Requires Docker and Docker Compose.

```bash
docker compose up --build
```

This starts Redis and the app together. Once it's up:

```bash
# First few requests succeed
curl -i http://localhost:8080/api/resource -H "X-API-Key: alice"

# Hammer it past the default burst capacity (20) to see a 429
for i in $(seq 1 25); do
  curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/resource -H "X-API-Key: alice"
done

# Give alice a bigger, custom limit
curl -i -X PUT http://localhost:8080/admin/rules/alice \
  -H "Content-Type: application/json" \
  -d '{"algorithm":"TOKEN_BUCKET","capacity":100,"refillRatePerSecond":20,"limit":100,"windowSeconds":10}'

# Switch bob to the sliding window algorithm
curl -i -X PUT http://localhost:8080/admin/rules/bob \
  -H "Content-Type: application/json" \
  -d '{"algorithm":"SLIDING_WINDOW","capacity":10,"refillRatePerSecond":0,"limit":10,"windowSeconds":5}'
```

Stop everything with `docker compose down` (add `-v` to also drop the Redis volume/data).

---

## Proving distributed correctness (load test)

The whole point of backing this with Redis instead of an in-memory counter is that the
limit holds **across instances**, not per-instance. `docker-compose.cluster.yml` starts
three app instances (`app1`, `app2`, `app3`, on host ports 8081-8083) all pointed at the
same Redis, and `loadtest/distributed_correctness_test.py` hits them concurrently to prove
it.

```bash
docker compose -f docker-compose.cluster.yml up --build -d

pip install requests
python3 loadtest/distributed_correctness_test.py \
  --instances http://localhost:8081 http://localhost:8082 http://localhost:8083 \
  --limit 100 --extra 200
```

What it does:

1. Configures a single client (`loadtest-client`) with a fixed one-shot budget of
   `--limit` tokens (`refillRatePerSecond: 0`, so there's no replenishment to muddy the
   numbers during the run).
2. Fires `limit + extra` requests concurrently (default 64 worker threads), round-robining
   across all three instance URLs.
3. Tallies `200` vs `429` responses.

### Interpreting the result

```
Finished in 1.34s
  200 OK           : 100
  429 Too Many Req : 200
  other/errors     : 0

PASS: exactly 100 requests were allowed across 3 instances, respecting the single
configured limit of 100. (A naive per-instance limiter would have allowed up to 300.)
```

- **Exactly `--limit` requests get `200`**, no matter how the traffic was split across the
  three instances or how much concurrency was used. That's the atomic Lua script doing its
  job: every instance is decrementing the *same* Redis-side counter through the *same*
  atomic operation, so there's no window for two instances to both think a token is
  available.
- If the limiter's state were kept in a local `HashMap` per instance instead of Redis,
  each of the 3 instances would maintain its own budget of 100, and you'd see roughly
  **300** requests succeed instead of 100 — the script prints that comparison explicitly
  so a regression (e.g. someone "optimizing" by caching counts locally) is obvious.
- A non-zero `errors` count usually means a container wasn't ready yet — rerun after
  confirming `docker compose -f docker-compose.cluster.yml ps` shows all three app
  containers healthy.

---

## Tests

```bash
mvn test
```

- **Unit tests** (`RateLimiterServiceTest`, `RateLimitFilterTest`) use Mockito only — no
  Redis, no Spring context — and cover strategy dispatch and the filter's HTTP-level
  behavior (429 + `Retry-After`, header propagation, path exclusion).
- **Token bucket / Lua script tests** (`TokenBucketRateLimiterTest`) run the real
  `token_bucket.lua` script against a real Redis (via Testcontainers), asserting burst
  capacity, denial + `retryAfterMs`, refill-over-time behavior, the capacity cap after long
  idle periods, and per-client isolation.
- **Sliding window tests** (`SlidingWindowRateLimiterTest`) do the same for
  `sliding_window.lua`: limit enforcement, window expiry, per-client isolation.
- **Full-stack integration test** (`RateLimiterIntegrationTest`) boots the entire Spring
  context on a random port with a real Redis and drives it over real HTTP, including the
  admin API creating a rule that overrides the default.

Testcontainers needs a working Docker daemon on the machine running the tests (this is
provided automatically by GitHub Actions' `ubuntu-latest` runners — see below).

---

## CI/CD pipeline

Defined in [`.github/workflows/ci.yml`](.github/workflows/ci.yml), triggered on every push
and pull request to `main`.

### Stage 1 — Build
`actions/setup-java` installs Temurin JDK 17 (with Maven dependency caching), then
`mvn -B clean verify` compiles both main and test sources.

### Stage 2 — Test
The same `mvn verify` invocation runs the full test suite: fast Mockito-based unit tests
and the Testcontainers-backed integration tests, which transparently spin up a real Redis
container via the Docker daemon already present on the runner. A `redis:7-alpine` **service
container** is also declared on the job (mapped to `localhost:6379`) so anything relying on
a plain, already-running Redis at a fixed address is satisfied too. Surefire XML reports
are uploaded as a build artifact and summarized as a check via `dorny/test-reporter`, so
pass/fail is visible directly on the PR/commit without opening logs.

### Stage 3 — Containerize
A second job, `docker-build-push`, depends on `build-and-test` succeeding first (`needs:`).
It builds the multi-stage `Dockerfile` (Maven build stage → slim `eclipse-temurin:17-jre-alpine`
runtime stage) using `docker/build-push-action`.

### Stage 4 — Publish
Still inside `docker-build-push`, and **gated on `github.ref == 'refs/heads/main'`** (so
PRs build the image to verify it compiles, but never push it), the image is tagged both
`latest` and with the short commit SHA, then pushed to **GitHub Container Registry**
(`ghcr.io/<owner>/<repo>`) using the repo's own `GITHUB_TOKEN` — no extra secrets to
configure.

```
push/PR ──► [Build: mvn compile] ──► [Test: mvn verify + Testcontainers] ──► (main only) [Containerize: docker build] ──► [Publish: push to GHCR]
```

---

## What I'd do differently at real scale

This project optimizes for being a complete, correct, readable reference implementation.
At real production scale I'd change:

- **Redis Cluster / sharding.** A single Redis instance is a throughput ceiling and a
  single point of failure. I'd shard client keys across a Redis Cluster (hash slots
  already distribute `rl:tb:{clientId}` / `rl:sw:{clientId}` keys naturally since they're
  independent per client) and use Cluster-aware Lua (each script only touches keys in one
  slot, which this design already respects — no cross-slot `EVAL`). I'd also put a
  read replica in front of the admin "list rules" endpoint, which doesn't need
  linearizable reads.
- **Circuit breakers / fail-open-or-closed policy for Redis outages.** Right now, if Redis
  is unreachable, every request fails with an exception (fail-closed by accident, not by
  design). At scale I'd wrap the Redis call in a circuit breaker (e.g. Resilience4j) with
  an explicit, configurable policy: fail-open (let traffic through, log/alert loudly) for
  low-risk APIs, fail-closed for anything sensitive to abuse — and fall back to a
  short-lived local in-memory limiter as a degraded mode while Redis recovers, instead of
  an outright outage.
- **Edge/CDN-level limiting.** Doing all limiting inside the app tier means abusive
  traffic still pays the cost of a full TLS handshake, load balancer hop, and app-server
  connection before being rejected. At scale I'd push coarse-grained limiting to the
  edge (e.g. Cloudflare, an API gateway, or nginx/Envoy with a Redis-backed or local
  approximate limiter) to shed obvious abuse before it reaches this service, and keep this
  service's precise, rule-aware limiting as the second, authoritative layer for anything
  that gets through.
- **Async/pipelined Redis calls.** Each request currently does one synchronous `EVAL`
  round trip. Under very high QPS I'd look at request coalescing or a local
  short-TTL cache of "definitely still has tokens" results to cut Redis round trips for
  clients far under their limit, only falling through to Redis when getting close to the
  boundary.
- **Per-key hot-spotting.** A single extremely high-traffic client still funnels through
  one Redis key. I'd consider a striped counter (split one client's budget across N keys,
  each holding `capacity/N`) for the handful of clients large enough to make that a real
  bottleneck, trading a little precision for horizontal scalability on that hot key.

---

## Project layout

```
src/main/java/com/ratelimiter/
├── RateLimiterApplication.java
├── config/
│   ├── RedisConfig.java            # RedisTemplate + Lua script beans
│   └── RateLimiterProperties.java  # ratelimiter.* config binding
├── model/
│   ├── RateLimitAlgorithm.java
│   ├── RateLimitRule.java
│   └── RateLimitResult.java
├── service/
│   ├── RateLimiterStrategy.java    # Strategy interface
│   ├── TokenBucketRateLimiter.java
│   ├── SlidingWindowRateLimiter.java
│   ├── RateLimiterService.java     # picks + delegates to a strategy
│   └── RuleService.java            # per-client + default rule management
├── repository/
│   └── RateLimitRuleRepository.java  # rules persisted in Redis
├── filter/
│   └── RateLimitFilter.java        # OncePerRequestFilter, 429 + Retry-After
└── controller/
    ├── ResourceController.java     # dummy protected endpoint
    └── AdminController.java        # rule CRUD

src/main/resources/
├── application.yml
└── scripts/
    ├── token_bucket.lua
    └── sliding_window.lua

src/test/java/com/ratelimiter/      # unit + Testcontainers integration tests
loadtest/distributed_correctness_test.py
docker-compose.yml                  # single instance, for local dev
docker-compose.cluster.yml          # 3 instances + shared Redis, for the load test
.github/workflows/ci.yml
```
